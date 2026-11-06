package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.ConfigChangePort;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort.OverrideEntry;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 高危配置变更申请仓储（P4-D16 / C7）。
 *
 * <p>⚠️ <b>四步均审计 fail-closed</b>：请求 / 批准 / 拒绝 / 应用，
 * 任一审计写失败即中止——高危变更无痕比变更失败危险得多。
 *
 * <p>⚠️ <b>双人复核双保险</b>：应用层比对 requester/approver，
 * DB 侧另有 {@code chg_no_self_approval} CHECK。应用层可被绕过，DB 不会。
 *
 * <p>⚠️ <b>apply 时重做收紧校验</b>：申请提交后、审批之前，
 * 配置可能已被他人改过——此时的「收紧」相对<b>新基线</b>可能已是放松。
 * 故 apply 时重跑 {@code ConfigTighteningPolicy}。
 */
@Repository
public class JdbcConfigChangeRepository implements ConfigChangePort {

    /** Postgres check_violation（CHECK 约束冲突）。 */
    private static final String CHECK_VIOLATION = "23514";
    /** Postgres restrict_violation（状态机守卫触发器）。 */
    private static final String RESTRICT_VIOLATION = "23001";

    private final DataSource dataSource;
    private final AuditPort audit;
    private final ConfigOverridePort overrides;

    public JdbcConfigChangeRepository(@NonNull DataSource dataSource,
                                      @NonNull AuditPort audit,
                                      @NonNull ConfigOverridePort overrides) {
        this.dataSource = dataSource;
        this.audit = audit;
        this.overrides = overrides;
    }

    @Override
    public @NonNull ChangeItem request(@NonNull String tenantId,
                                       @NonNull String key,
                                       @NonNull String value,
                                       @NonNull String reason,
                                       @NonNull String requesterId) {
        if (!ConfigOverridePort.WRITABLE.containsKey(key)) {
            throw new ConfigOverridePort.UnknownConfigKeyException(key);
        }
        String valueType = valueTypeOf(key);
        ChangeItem created;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            conn.setAutoCommit(false);
            try {
                String sql = """
                        INSERT INTO config_change_request
                            (tenant_id, config_key, proposed_value, value_type,
                             reason, requester_id, requested_at, state)
                        VALUES (?::uuid, ?, ?, ?, ?, ?, now(), 'PENDING')
                        RETURNING change_id::text, requested_at
                        """;
                String changeId;
                Instant requestedAt;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, tenantId);
                    ps.setString(2, key);
                    ps.setString(3, value);
                    ps.setString(4, valueType);
                    ps.setString(5, reason);
                    ps.setString(6, requesterId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("变更申请插入未返回 change_id");
                        }
                        changeId = rs.getString(1);
                        requestedAt = rs.getTimestamp(2).toInstant();
                    }
                }
                conn.commit();
                // ⚠️ 明细只放**系统常量**（键/类型/分级），不放 value 与 reason——
                //    二者是用户自由文本，PIPL 敏感信息扫描（fail-closed）会拒写。
                audit.recordRequired(tenantId, requesterId,
                        AuditPort.Actions.CONFIG_CHANGE_REQUEST, "config_change", changeId,
                        Map.of("configKey", key,
                                "valueType", valueType,
                                "tier", ConfigOverridePort.tierOf(key).name()));
                created = new ChangeItem(changeId, key, value, valueType, reason,
                        requesterId, requestedAt, ChangeState.PENDING,
                        null, null, null, null);
            } catch (SQLException | RuntimeException e) {
                safeRollback(conn);
                throw e instanceof RuntimeException re
                        ? re : new IllegalStateException("创建变更申请失败", e);
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建变更申请失败", e);
        }
        return created;
    }

    @Override
    public @NonNull Page<ChangeItem> list(@NonNull String tenantId,
                                          @Nullable ChangeState state,
                                          @Nullable String cursor,
                                          int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        PageCursor after = (cursor == null || cursor.isBlank()) ? null : PageCursor.decode(cursor);

        StringBuilder sql = new StringBuilder("""
                SELECT change_id::text, config_key, proposed_value, value_type, reason,
                       requester_id, requested_at, state::text,
                       approver_id, decided_at, decision_note, applied_at
                FROM config_change_request
                WHERE tenant_id = ?::uuid
                """);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (state != null) {
            sql.append(" AND state = ?::config_change_state");
            params.add(state.name());
        }
        if (after != null) {
            // ⚠️ 全序游标（V12 索引同序）：仅按 requested_at 会漏读同刻行。
            sql.append(" AND (requested_at < ? OR (requested_at = ? AND change_id < ?::uuid))");
            params.add(Timestamp.from(after.createdAt()));
            params.add(Timestamp.from(after.createdAt()));
            params.add(after.id());
        }
        sql.append(" ORDER BY requested_at DESC, change_id DESC LIMIT ?");
        params.add(limit + 1);

        List<ChangeItem> rows = new ArrayList<>();
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(read(rs));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询变更申请失败", e);
        }

        // 多取的那一行只用于判定，不返回（与 P4-D13 一致）
        String next = null;
        if (rows.size() > limit) {
            ChangeItem last = rows.get(limit - 1);
            rows = new ArrayList<>(rows.subList(0, limit));
            next = new PageCursor(last.requestedAt(), last.changeId()).encode();
        }
        return Page.of(rows, next);
    }

    @Override
    public @NonNull ChangeItem decide(@NonNull String tenantId,
                                      @NonNull String changeId,
                                      @NonNull Decision decision,
                                      @Nullable String note,
                                      @NonNull String approverId) {
        ChangeItem decided;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            conn.setAutoCommit(false);
            try {
                ChangeItem existing = loadForUpdate(conn, tenantId, changeId);
                // ⚛️ **双人复核第一道**：应用层比对身份
                if (existing.requesterId().equals(approverId)) {
                    throw new SelfApprovalException();
                }
                if (existing.state() != ChangeState.PENDING) {
                    throw new IllegalStateConflictException(
                            "申请已处于 " + existing.state() + "，不可再决定");
                }
                ChangeState target = decision == Decision.APPROVE
                        ? ChangeState.APPROVED : ChangeState.REJECTED;
                ChangeItem updated = updateDecision(conn, tenantId, changeId,
                        target, note, approverId);
                conn.commit();
                audit.recordRequired(tenantId, approverId,
                        decision == Decision.APPROVE
                                ? AuditPort.Actions.CONFIG_CHANGE_APPROVE
                                : AuditPort.Actions.CONFIG_CHANGE_REJECT,
                        "config_change", changeId,
                        Map.of("configKey", existing.key(), "state", target.name()));
                decided = updated;
            } catch (SQLException | RuntimeException e) {
                safeRollback(conn);
                if (e instanceof SQLException se && CHECK_VIOLATION.equals(se.getSQLState())) {
                    // ⚛️ **双人复核第二道**：DB CHECK 拦截（应用层被绕过时生效）
                    throw new SelfApprovalException();
                }
                if (e instanceof SQLException se && RESTRICT_VIOLATION.equals(se.getSQLState())) {
                    throw new IllegalStateConflictException("申请状态已变更，请刷新后重试");
                }
                throw e instanceof RuntimeException re
                        ? re : new IllegalStateException("审批失败", e);
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("审批失败", e);
        }
        return decided;
    }

    @Override
    public @NonNull ChangeItem apply(@NonNull String tenantId,
                                     @NonNull String changeId,
                                     @NonNull String actorId) {
        ChangeItem applied;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            conn.setAutoCommit(false);
            try {
                ChangeItem existing = loadForUpdate(conn, tenantId, changeId);
                if (existing.state() != ChangeState.APPROVED) {
                    throw new IllegalStateConflictException(
                            "只有 APPROVED 状态可应用，当前为 " + existing.state());
                }
                // ⚠️ **apply 时重做收紧校验**：申请提交后配置可能已被他人改过，
                //    此时的「收紧」相对新基线可能已是放松。
                revalidateTightening(tenantId, existing);

                ChangeItem updated = markApplied(conn, tenantId, changeId, actorId);
                // ⚠️ **先写配置表，再置 APPLIED**：同一事务内，任一失败全回滚。
                overrides.apply(tenantId, existing.key(), existing.proposedValue(),
                        existing.reason(), actorId);
                conn.commit();
                audit.recordRequired(tenantId, actorId,
                        AuditPort.Actions.CONFIG_CHANGE_APPLY, "config_change", changeId,
                        Map.of("configKey", existing.key()));
                applied = updated;
            } catch (SQLException | RuntimeException e) {
                safeRollback(conn);
                if (e instanceof SQLException se && RESTRICT_VIOLATION.equals(se.getSQLState())) {
                    throw new IllegalStateConflictException("申请状态已变更，请刷新后重试");
                }
                throw e instanceof RuntimeException re
                        ? re : new IllegalStateException("应用变更失败", e);
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("应用变更失败", e);
        }
        return applied;
    }

    /**
     * 重跑收紧校验。
     *
     * <p>⚠️ 这里读的是**当前生效值**（DB 覆盖 ⊕ yml 默认），
     * 不是申请提交时的快照——否则「申请时合法、审批时已过期」的变更
     * 会绕过棘轮。
     */
    private void revalidateTightening(String tenantId, ChangeItem existing) {
        String current = null;
        Map<String, OverrideEntry> existingOverrides = overrides.overridesFor(tenantId);
        OverrideEntry entry = existingOverrides.get(existing.key());
        if (entry != null) {
            current = entry.value();
        }
        // current 为 null 时 policy 只做格式校验（首次写入不受限）——
        // 这是安全的：没有基线就没有「放松」可言。
        cn.studykid.multiagent.application.config.ConfigTighteningPolicy.validate(
                existing.key(), existing.proposedValue(), current,
                existing.reason(), valueTypeToPolicyType(existing.valueType()));
    }

    private ChangeItem loadForUpdate(Connection conn, String tenantId, String changeId)
            throws SQLException {
        String sql = """
                SELECT change_id::text, config_key, proposed_value, value_type, reason,
                       requester_id, requested_at, state::text,
                       approver_id, decided_at, decision_note, applied_at
                FROM config_change_request
                WHERE tenant_id = ?::uuid AND change_id = ?::uuid
                FOR UPDATE
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, changeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ChangeNotFoundException(changeId);
                }
                return read(rs);
            }
        }
    }

    private ChangeItem updateDecision(Connection conn, String tenantId, String changeId,
                                      ChangeState target, String note, String approverId)
            throws SQLException {
        String sql = """
                UPDATE config_change_request
                SET state = ?::config_change_state, approver_id = ?, decided_at = now(),
                    decision_note = ?
                WHERE tenant_id = ?::uuid AND change_id = ?::uuid
                RETURNING change_id::text, config_key, proposed_value, value_type, reason,
                          requester_id, requested_at, state::text,
                          approver_id, decided_at, decision_note, applied_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, target.name());
            ps.setString(2, approverId);
            ps.setString(3, note);
            ps.setString(4, tenantId);
            ps.setString(5, changeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ChangeNotFoundException(changeId);
                }
                return read(rs);
            }
        }
    }

    private ChangeItem markApplied(Connection conn, String tenantId, String changeId,
                                  String actorId) throws SQLException {
        String sql = """
                UPDATE config_change_request
                SET state = 'APPLIED', applied_at = now()
                WHERE tenant_id = ?::uuid AND change_id = ?::uuid
                RETURNING change_id::text, config_key, proposed_value, value_type, reason,
                          requester_id, requested_at, state::text,
                          approver_id, decided_at, decision_note, applied_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, changeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ChangeNotFoundException(changeId);
                }
                return read(rs);
            }
        }
    }

    private static ChangeItem read(ResultSet rs) throws SQLException {
        return new ChangeItem(
                rs.getString("change_id"),
                rs.getString("config_key"),
                rs.getString("proposed_value"),
                rs.getString("value_type"),
                rs.getString("reason"),
                rs.getString("requester_id"),
                rs.getTimestamp("requested_at").toInstant(),
                ChangeState.valueOf(rs.getString("state")),
                rs.getString("approver_id"),
                instantOrNull(rs.getTimestamp("decided_at")),
                rs.getString("decision_note"),
                instantOrNull(rs.getTimestamp("applied_at")));
    }

    /**
     * 配置键 → V12 的 {@code value_type}。
     *
     * <p>⚠️ 与 {@code JdbcConfigOverrideRepository.valueTypeOf} <b>必须一致</b>：
     * 两处漂移会导致「校验按 DECIMAL 放行、按 INTEGER 落库」。
     */
    private static String valueTypeOf(String key) {
        return switch (key) {
            case "agent.run.max-cost-per-run" -> "DECIMAL";
            case "agent.approval.required-by-risk" -> "ENUM";
            case "agent.approval.ttl" -> "DURATION";
            default -> "INTEGER";
        };
    }

    private static cn.studykid.multiagent.application.config.ConfigTighteningPolicy.ValueType
            valueTypeToPolicyType(String stored) {
        return switch (stored) {
            case "DECIMAL" -> cn.studykid.multiagent.application.config
                    .ConfigTighteningPolicy.ValueType.DECIMAL;
            case "ENUM" -> cn.studykid.multiagent.application.config
                    .ConfigTighteningPolicy.ValueType.ENUM;
            case "DURATION" -> cn.studykid.multiagent.application.config
                    .ConfigTighteningPolicy.ValueType.DURATION;
            default -> cn.studykid.multiagent.application.config
                    .ConfigTighteningPolicy.ValueType.INTEGER;
        };
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Timestamp ts) {
                ps.setTimestamp(i + 1, ts);
            } else {
                ps.setString(i + 1, String.valueOf(p));
            }
        }
    }

    @Nullable
    private static Instant instantOrNull(@Nullable Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // rollback 失败不掩盖原始异常
        }
    }

    private static void restoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 同上
        }
    }

    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, new TenantContext(tenantId, "system", java.util.Set.of(), 0L));
    }
}