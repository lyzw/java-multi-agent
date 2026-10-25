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
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 配置变更申请仓储（P6R4 · 管理端点）。
 *
 * <p>⚠️ <b>四步均审计 fail-closed</b>：申请 / 批准 / 拒绝 / 执行，
 * 任一审计写失败即中止 —— 高危变更无痕比变更失败危险得多。
 *
 * <p>⚠️ <b>双人复核双保险</b>：应用层比对 requester / approver，
 * DB 侧另有 {@code cap_config_publish_request_two_person_ck} CHECK。
 * 应用层可被绕过，DB 不会。
 *
 * <p>⚠️ <b>状态迁移在存储层也有守卫</b>：{@code decide} 只接受 {@code PENDING}，
 * {@code markApplied} / {@code markFailed} 只接受 {@code APPROVED}。
 * 越级迁移必须失败 —— 否则「没审批就生效」会从应用层漏到存储层。
 *
 * <p>⚠️ <b>租户绑定</b>：所有读写都走租户连接（{@code app.tenant_id} 会话变量），
 * 表上另有 RLS 强制「本租户行」。两层都不可省：
 * 应用层的 {@code tenant_id = ?} 可能被漏写，RLS 不会。
 *
 * <p>⚠️ <b>第三十四批起支持 PLATFORM 作用域</b>：{@code tenantId == null} 即
 * 「平台级变更」。此时：
 * <ul>
 *   <li>SQL 用 {@code tenant_id IS NOT DISTINCT FROM ?::uuid} ——
 *       {@code = NULL} 在 SQL 里恒为 NULL（不是 TRUE），
 *       用 {@code =} 会让平台申请单<b>永远查不到</b>；</li>
 *   <li>租户上下文绑定**零 UUID 哨兵**（平台作用域没有真实租户）；
 *       运维角色带 BYPASSRLS，哨兵只是一个约定，不是安全边界 ——
 *       安全边界在<b>用哪条连接</b>（应用角色 vs 平台运维角色）；</li>
 *   <li>应用角色连接**读不到也写不进**平台行（RLS 读写策略是严格相等），
 *       所以平台作用域必须由**运维连接**的那个实例承担。</li>
 * </ul>
 */
@Repository
@org.springframework.context.annotation.Primary
public class JdbcConfigPublishRequestRepository implements ConfigPublishRequestPort {

    /** Postgres check_violation（CHECK 约束冲突）。 */
    private static final String CHECK_VIOLATION = "23514";

    /**
     * PLATFORM 作用域的租户哨兵：零 UUID（合法 UUID，不对应任何真实租户）。
     *
     * <p>⚠️ <b>哨兵不是安全边界</b>：真正的边界是「用哪条连接」——
     * 平台作用域的申请单只能由**平台运维角色（BYPASSRLS）连接**读写；
     * 应用角色连接因 RLS 严格相等而读不到、写不进平台行。
     */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private final DataSource dataSource;
    private final AuditPort audit;

    public JdbcConfigPublishRequestRepository(@NonNull DataSource dataSource,
                                              @NonNull AuditPort audit) {
        this.dataSource = dataSource;
        this.audit = audit;
    }

    @Override
    @NonNull
    public ConfigPublishRequest insert(@Nullable String tenantId,
                                        ConfigPublishRequest.@NonNull Operation operation,
                                        @NonNull String payload,
                                        @NonNull String reason,
                                        @NonNull String requesterId) {
            return TransactionalConnectionHolder.inTransaction(dataSource,
                    tenantContext(tenantId),
                    conn -> {
                        String sql = """
                                INSERT INTO cap_config_publish_request
                                    (tenant_id, scope, operation, payload, reason, requester_id, state)
                                VALUES (?::uuid, ?, ?, ?::jsonb, ?, ?, 'PENDING')
                                RETURNING request_id::text, requested_at
                                """;
                        String requestId;
                        Instant requestedAt;
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            bindTenant(ps, 1, tenantId);
                            ps.setString(2, scopeOf(tenantId).name());
                            ps.setString(3, operation.name());
                            ps.setString(4, payload);
                            ps.setString(5, reason);
                            ps.setString(6, requesterId);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (!rs.next()) {
                                    throw new IllegalStateException(
                                            "变更申请插入未返回 request_id");
                                }
                                requestId = rs.getString(1);
                                requestedAt = rs.getTimestamp(2).toInstant();
                            }
                        }
                        // ⚠️⚠️ **审计与状态变更同事务**（P1-15 修复）：
                        //    修复前的顺序是「先 commit 状态变更、再写审计」，
                        //    审计写失败（fail-closed 抛异常）时状态变更**已经生效** ——
                        //    得到的是「无审计的高危变更」，而类注释写明的恰恰是
                        //    「审计写不进去就不生效」。现在两者在同一事务里：
                        //    审计写不进去 → 全部回滚。
                        //
                        // ⚠️ 审计明细只放**系统常量**，不放 payload / reason ——
                        //    二者是用户自由文本，PIPL 敏感信息扫描（fail-closed）会拒写。
                        audit.recordRequired(auditTenant(tenantId), requesterId,
                                AuditPort.Actions.CONFIG_PUBLISH_REQUEST, "config_publish_request",
                                requestId, Map.of("operation", operation.name()));
                        return new ConfigPublishRequest(requestId, tenantId,
                                operation, payload, reason,
                                requesterId, ConfigPublishRequest.State.PENDING,
                                null, null, requestedAt, null, null, null,
                                scopeOf(tenantId));
                    });
    }

    @Override
    @NonNull
    public Optional<ConfigPublishRequest> find(@Nullable String tenantId,
                                                @NonNull String requestId) {
        String sql = selectColumns() + """
                WHERE tenant_id IS NOT DISTINCT FROM ?::uuid AND request_id = ?::uuid
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                bindTenant(ps, 1, tenantId);
                ps.setString(2, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询变更申请失败", e);
        }
    }

    @Override
    @NonNull
    public Page<ConfigPublishRequest> list(@Nullable String tenantId,
                                            ConfigPublishRequest.@Nullable State state,
                                            @Nullable String cursor,
                                            int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        PageCursor after = (cursor == null || cursor.isBlank()) ? null : PageCursor.decode(cursor);

        StringBuilder sql = new StringBuilder(selectColumns());
        sql.append("WHERE tenant_id IS NOT DISTINCT FROM ?::uuid");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (state != null) {
            sql.append(" AND state = ?");
            params.add(state.name());
        }
        if (after != null) {
            // ⚠️ 全序游标：仅按 requested_at 会漏读同刻行
            sql.append(" AND (requested_at < ? OR (requested_at = ? AND request_id < ?::uuid))");
            params.add(Timestamp.from(after.createdAt()));
            params.add(Timestamp.from(after.createdAt()));
            params.add(after.id());
        }
        sql.append(" ORDER BY requested_at DESC, request_id DESC LIMIT ?");
        params.add(limit + 1);

        List<ConfigPublishRequest> rows = new ArrayList<>();
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

        String next = null;
        if (rows.size() > limit) {
            ConfigPublishRequest last = rows.get(limit - 1);
            rows = new ArrayList<>(rows.subList(0, limit));
            next = new PageCursor(last.requestedAt(), last.requestId()).encode();
        }
        return Page.of(rows, next);
    }

    @Override
    @NonNull
    public ConfigPublishRequest decide(@Nullable String tenantId,
                                        @NonNull String requestId,
                                        @NonNull Decision decision,
                                        @Nullable String note,
                                        @NonNull String approverId) {
        try {
            return TransactionalConnectionHolder.inTransaction(dataSource,
                    tenantContext(tenantId),
                    conn -> {
                        try {
                            ConfigPublishRequest existing = loadForUpdate(conn, tenantId, requestId);
                            // ⚛️ **双人复核第一道**：应用层比对身份
                            if (existing.requesterId().equals(approverId)) {
                                throw new SelfApprovalException();
                            }
                            if (existing.state() != ConfigPublishRequest.State.PENDING) {
                                throw new StateConflictException(
                                        "申请已处于 " + existing.state() + "，不可再决定");
                            }
                            ConfigPublishRequest.State target = decision == Decision.APPROVE
                                    ? ConfigPublishRequest.State.APPROVED
                                    : ConfigPublishRequest.State.REJECTED;
                            ConfigPublishRequest updated = updateDecision(conn, tenantId, requestId,
                                    target, note, approverId);
                            // ⚠️⚠️ **审计与状态变更同事务**（P1-15 修复）：
                            //    审计写不进去 → 状态变更一并回滚（无审计的批准不存在）。
                            audit.recordRequired(auditTenant(tenantId), approverId,
                                    decision == Decision.APPROVE
                                            ? AuditPort.Actions.CONFIG_PUBLISH_APPROVE
                                            : AuditPort.Actions.CONFIG_PUBLISH_REJECT,
                                    "config_publish_request", requestId,
                                    Map.of("operation", existing.operation().name(),
                                            "state", target.name()));
                            return updated;
                        } catch (SQLException e) {
                            // ⚛️ 保留 SQLState：双人复核的 DB CHECK 需要它才能区分
                            throw new SqlStateCarrier(e.getSQLState(), e);
                        }
                    });
        } catch (SqlStateCarrier e) {
            if (CHECK_VIOLATION.equals(e.sqlState())) {
                // ⚛️ **双人复核第二道**：DB CHECK 拦截（应用层被绕过时生效）
                throw new SelfApprovalException();
            }
            throw new IllegalStateException("审批失败", e.getCause());
        }
    }

    @Override
    @NonNull
    public ConfigPublishRequest markApplied(@Nullable String tenantId,
                                             @NonNull String requestId,
                                             @NonNull String actorId,
                                             @NonNull Instant appliedAt) {
        return transitionTo(tenantId, requestId,
                ConfigPublishRequest.State.APPLIED, null, actorId, appliedAt);
    }

    @Override
    @NonNull
    public ConfigPublishRequest markFailed(@Nullable String tenantId,
                                            @NonNull String requestId,
                                            @NonNull String reason) {
        return transitionTo(tenantId, requestId,
                ConfigPublishRequest.State.FAILED, reason, null, null);
    }

    private ConfigPublishRequest transitionTo(String tenantId, String requestId,
                                              ConfigPublishRequest.State target,
                                              String failureReason, String actorId,
                                              Instant appliedAt) {
        try {
            return TransactionalConnectionHolder.inTransaction(dataSource,
                    tenantContext(tenantId),
                    conn -> {
                        try {
                            ConfigPublishRequest existing = loadForUpdate(conn, tenantId, requestId);
                            if (existing.state() != ConfigPublishRequest.State.APPROVED) {
                                throw new StateConflictException(
                                        "只有 APPROVED 状态可执行，当前为 " + existing.state());
                            }
                            String sql = """
                                    UPDATE cap_config_publish_request
                                    SET state = ?, failure_reason = ?, applied_at = ?
                                    WHERE tenant_id IS NOT DISTINCT FROM ?::uuid
                                      AND request_id = ?::uuid
                                    RETURNING request_id::text, tenant_id::text, operation, payload::text,
                                              reason, requester_id, state, approver_id, decision_note,
                                              requested_at, decided_at, applied_at, failure_reason, scope
                                    """;
                            ConfigPublishRequest updated;
                            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                                ps.setString(1, target.name());
                                ps.setString(2, failureReason);
                                if (appliedAt == null) {
                                    ps.setNull(3, java.sql.Types.TIMESTAMP);
                                } else {
                                    ps.setTimestamp(3, Timestamp.from(appliedAt));
                                }
                                bindTenant(ps, 4, tenantId);
                                ps.setString(5, requestId);
                                try (ResultSet rs = ps.executeQuery()) {
                                    if (!rs.next()) {
                                        throw new NotFoundException(requestId);
                                    }
                                    updated = read(rs);
                                }
                            }
                            // ⚠️⚠️ **审计与状态变更同事务**（P1-15 修复）。
                            if (target == ConfigPublishRequest.State.APPLIED) {
                                audit.recordRequired(auditTenant(tenantId), actorId,
                                        AuditPort.Actions.CONFIG_PUBLISH_APPLY, "config_publish_request",
                                        requestId, Map.of("operation", existing.operation().name()));
                            } else {
                                // ⚠️ 失败也要留痕：一份「批过但没生效」的申请必须能被查到
                                audit.recordRequired(auditTenant(tenantId),
                                        actorId == null ? "system" : actorId,
                                        AuditPort.Actions.CONFIG_PUBLISH_FAILED, "config_publish_request",
                                        requestId, Map.of("operation", existing.operation().name()));
                            }
                            return updated;
                        } catch (SQLException e) {
                            throw new SqlStateCarrier(e.getSQLState(), e);
                        }
                    });
        } catch (SqlStateCarrier e) {
            throw new IllegalStateException("更新申请状态失败", e.getCause());
        }
    }

    private ConfigPublishRequest loadForUpdate(Connection conn, String tenantId,
                                               String requestId) throws SQLException {
        String sql = selectColumns() + """
                WHERE tenant_id IS NOT DISTINCT FROM ?::uuid AND request_id = ?::uuid
                FOR UPDATE
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindTenant(ps, 1, tenantId);
            ps.setString(2, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundException(requestId);
                }
                return read(rs);
            }
        }
    }

    private ConfigPublishRequest updateDecision(Connection conn, String tenantId,
                                                 String requestId,
                                                 ConfigPublishRequest.State target,
                                                 String note, String approverId)
            throws SQLException {
        String sql = """
                UPDATE cap_config_publish_request
                SET state = ?, approver_id = ?, decided_at = now(), decision_note = ?
                WHERE tenant_id IS NOT DISTINCT FROM ?::uuid AND request_id = ?::uuid
                RETURNING request_id::text, tenant_id::text, operation, payload::text,
                          reason, requester_id, state, approver_id, decision_note,
                          requested_at, decided_at, applied_at, failure_reason, scope
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, target.name());
            ps.setString(2, approverId);
            ps.setString(3, note);
            bindTenant(ps, 4, tenantId);
            ps.setString(5, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundException(requestId);
                }
                return read(rs);
            }
        }
    }

    private static String selectColumns() {
        return """
                SELECT request_id::text, tenant_id::text, operation, payload::text,
                       reason, requester_id, state, approver_id, decision_note,
                       requested_at, decided_at, applied_at, failure_reason, scope
                FROM cap_config_publish_request
                """;
    }

    private static ConfigPublishRequest read(ResultSet rs) throws SQLException {
        return new ConfigPublishRequest(
                rs.getString("request_id"),
                rs.getString("tenant_id"),
                ConfigPublishRequest.Operation.valueOf(rs.getString("operation")),
                rs.getString("payload"),
                rs.getString("reason"),
                rs.getString("requester_id"),
                ConfigPublishRequest.State.valueOf(rs.getString("state")),
                rs.getString("approver_id"),
                rs.getString("decision_note"),
                rs.getTimestamp("requested_at").toInstant(),
                instantOrNull(rs.getTimestamp("decided_at")),
                instantOrNull(rs.getTimestamp("applied_at")),
                rs.getString("failure_reason"),
                ConfigRelease.Scope.valueOf(rs.getString("scope")));
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            int index = i + 1;
            if (p == null) {
                // ⚠️ 平台作用域的 tenant_id 为 NULL —— 必须按 SQL NULL 绑定，
                //    setString(index, null) 会写成「字符串 'null'」而不是 SQL NULL。
                ps.setNull(index, java.sql.Types.VARCHAR);
            } else if (p instanceof Timestamp ts) {
                ps.setTimestamp(index, ts);
            } else if (p instanceof Integer value) {
                // ⚠️ LIMIT / 数值参数必须按整数类型绑定：
                //    setString 会让 PG 报「argument of LIMIT must be type bigint」。
                ps.setInt(index, value);
            } else if (p instanceof Long value) {
                ps.setLong(index, value);
            } else {
                ps.setString(index, String.valueOf(p));
            }
        }
    }

    @Nullable
    private static Instant instantOrNull(@Nullable Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * 携带 SQLState 的运行时异常（事务边界内部使用）。
     *
     * <p>⚠️ 为什么需要它：{@code TransactionalConnectionHolder.inTransaction}
     * 会把 {@link SQLException} 包装成 {@link IllegalStateException}，SQLState 丢了。
     * 而「双人复核的 DB CHECK」只能靠 {@code SQLState=23514} 识别。
     */
    private static final class SqlStateCarrier extends RuntimeException {

        private final String sqlState;

        SqlStateCarrier(String sqlState, SQLException cause) {
            super(cause);
            this.sqlState = sqlState;
        }

        String sqlState() {
            return sqlState;
        }
    }

    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, tenantContext(tenantId));
    }

    /**
     * 租户上下文：平台作用域绑定<b>零 UUID 哨兵</b>。
     *
     * <p>⚠️ 哨兵必须是<b>合法 UUID</b>：{@code current_tenant_id()} 的实现是
     * {@code NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID}，
     * 绑非 UUID 文本会让类型转换直接报错，报错点离「这是平台级作用域」很远。
     */
    private static TenantContext tenantContext(@Nullable String tenantId) {
        return new TenantContext(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId,
                "system", java.util.Set.of(), 0L);
    }

    /**
     * 绑定 tenant_id 参数：平台作用域按 <b>SQL NULL</b> 绑定。
     */
    private static void bindTenant(PreparedStatement ps, int index, @Nullable String tenantId)
            throws SQLException {
        if (tenantId == null) {
            ps.setNull(index, java.sql.Types.OTHER);
        } else {
            ps.setString(index, tenantId);
        }
    }

    /** 申请单的作用域由 tenantId 决定：{@code null} = PLATFORM。 */
    private static ConfigRelease.Scope scopeOf(@Nullable String tenantId) {
        return tenantId == null ? ConfigRelease.Scope.PLATFORM : ConfigRelease.Scope.TENANT;
    }

    /** 审计的租户维度：平台作用域用零 UUID 哨兵。 */
    private static String auditTenant(@Nullable String tenantId) {
        return tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId;
    }
}
