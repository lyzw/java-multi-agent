package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.UnknownOperationPort;

/**
 * UNKNOWN 操作查询与人工处置实现（P4-D13 / G9）。
 *
 * <p>⛔ <b>本类不存在任何把状态改回 {@code PENDING}/{@code IN_FLIGHT} 的路径</b>。
 * UNKNOWN 的定义是「副作用是否发生<b>未知</b>」，
 * 此时重放 = 可能在学生数据上重复施加副作用（Sev-0）。
 * 处置的三个出口全部是<b>终态归档</b>。
 *
 * <p><b>P1 影子态接入（12 文 §4.5 / 13 文 §5.2）</b>：
 * 在读路径（{@code list} / {@code find} / {@code lockAndRead}）前做 ScopeFilter 比对，
 * 结果集逐行做租户染色校验。默认全部字段为 null / false ⇒ 现有行为 100% 不变；
 * 仅当 Spring 容器里存在 {@link AuthzShadowBinding}（{@code agent.authz.shadow.enabled=true}）时，
 * 由 {@link #bindAuthzShadow} 一次性接线并置 {@code shadowEnabled=true}。
 * 比对与染色只记日志、不改变请求结果（P1 影子期）。
 */
@Repository
public class JdbcUnknownOperationRepository implements UnknownOperationPort {

    private static final String SQL_SELECT = """
            SELECT tenant_id, operation_id, run_id, tool_name, risk_level, state,
                   created_at, updated_at, reconciliation_source,
                   manual_disposed_by, manual_disposed_at
            FROM tool_operation
            WHERE tenant_id = ?::uuid
            """;

    private final DataSource dataSource;
    private final AuditPort audit;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    // ⚠️ 非 final：由 Spring 启动期通过 {@link #bindAuthzShadow} 一次性绑定，之后只读。
    //    容器里没有 AuthzShadowBinding 时保持构造期默认值（null / false）⇒ 零行为变更。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有双参构造，确保 @Repository 装配不变）。 */
    @Autowired
    public JdbcUnknownOperationRepository(@NonNull DataSource dataSource,
                                          @NonNull AuditPort audit) {
        this(dataSource, audit, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     *
     * @param shadowEnabled 是否启用影子比对 + 染色（P1 默认 false；P2 翻转为 true 进入阻断模式）
     * @param sentinelTenantId 哨兵（共享）租户标识，与 {@code SqlScopeTranslator} 共用配置项
     */
    JdbcUnknownOperationRepository(@NonNull DataSource dataSource,
                                  @NonNull AuditPort audit,
                                  @Nullable ScopedDataAccess scoped,
                                  @Nullable AuthzShadowReporter reporter,
                                  boolean shadowEnabled,
                                  @NonNull String sentinelTenantId) {
        this.dataSource = dataSource;
        this.audit = audit;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
    }

    @Override
    public @NonNull Page<OperationItem> list(@NonNull String tenantId,
                                             @Nullable String state,
                                             @Nullable String cursor,
                                             int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        PageCursor after = cursor == null || cursor.isBlank()
                ? null : PageCursor.decode(cursor);

        StringBuilder sql = new StringBuilder(SQL_SELECT);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (state != null) {
            sql.append(" AND state = ?::tool_operation_state");
            params.add(state);
        }
        if (after != null) {
            sql.append(" AND (created_at < ? OR (created_at = ? AND operation_id < ?::uuid))");
            params.add(Timestamp.from(after.createdAt()));
            params.add(Timestamp.from(after.createdAt()));
            params.add(after.id());
        }
        sql.append(" ORDER BY created_at DESC, operation_id DESC LIMIT ?");
        params.add(limit + 1);

        // ⚠️ P1 影子比对：本次查询绑定租户 vs Gate 算出的 ScopeFilter（只记日志）。
        shadowCompare("tool_operation", tenantId, true);
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            List<OperationItem> rows = new ArrayList<>(limit);
            try (PreparedStatement ps = lease.connection().prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // ⚠️ 主体 = 本次查询的租户；行归属 = 结果行真实 tenant_id。
                        //    两者必须分开传：用行租户当主体是同义反复（恒不违例）。
                        String rowTenant = rs.getString("tenant_id");
                        rows.add(read(rs));
                        shadowTaint("tool_operation", tenantId, rowTenant);
                    }
                }
            }
            String next = null;
            if (rows.size() > limit) {
                rows.remove(rows.size() - 1);
                OperationItem last = rows.get(rows.size() - 1);
                next = new PageCursor(last.createdAt(), last.operationId()).encode();
            }
            return Page.of(rows, next);
        } catch (SQLException e) {
            throw new IllegalStateException("查询操作列表失败", e);
        }
    }

    @Override
    public @Nullable OperationItem find(@NonNull String tenantId, @NonNull String operationId) {
        String sql = SQL_SELECT + " AND operation_id = ?::uuid";
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter。
        shadowCompare("tool_operation", tenantId, true);
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, operationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        // ⚠️ 不存在与「不属于该租户」返回同一个 null（见 findResult 同源纪律）。
                        return null;
                    }
                    String rowTenant = rs.getString("tenant_id");
                    OperationItem item = read(rs);
                    shadowTaint("tool_operation", tenantId, rowTenant);
                    return item;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询操作失败", e);
        }
    }

    @Override
    public @NonNull DispositionResult dispose(@NonNull String tenantId,
                                             @NonNull String operationId,
                                             @NonNull Disposition disposition,
                                             @NonNull String operatorId,
                                             @Nullable String note) {
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            conn.setAutoCommit(false);
            try {
                OperationItem current = lockAndRead(conn, tenantId, operationId);
                if (current == null) {
                    conn.rollback();
                    throw new UnknownOperationPort.OperationNotFoundException(operationId);
                }
                // ⛔ 非 UNKNOWN 态一律拒绝：重复处置、或已被 P3-D03 自动对账接管
                if (!"UNKNOWN".equals(current.state())) {
                    throw new NotInUnknownStateException(current.state());
                }
                Instant now = Instant.now();
                update(conn, tenantId, operationId, disposition, operatorId, note, now);
                conn.commit();

                audit.recordRequired(tenantId, operatorId, AuditPort.Actions.UNKNOWN_DISPOSE,
                        "tool_operation", operationId,
                        java.util.Map.of("disposition", disposition.name(),
                                "runId", current.runId(),
                                "toolName", current.toolName()));
                return new DispositionResult(operationId, disposition.name(), operatorId, now);
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e instanceof RuntimeException re
                        ? re : new IllegalStateException("UNKNOWN 处置失败", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("UNKNOWN 处置失败", e);
        }
    }

    private OperationItem lockAndRead(Connection conn, String tenantId, String operationId)
            throws SQLException {
        String sql = SQL_SELECT + " AND operation_id = ?::uuid FOR UPDATE";
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter。
        shadowCompare("tool_operation", tenantId, true);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String rowTenant = rs.getString("tenant_id");
                OperationItem item = read(rs);
                shadowTaint("tool_operation", tenantId, rowTenant);
                return item;
            }
        }
    }

    private void update(Connection conn, String tenantId, String operationId,
                        Disposition disposition, String operatorId, String note, Instant now)
            throws SQLException {
        // ⚠️ reconciliation_source='MANUAL' 与 P3-D03 的自动对账来源可区分，
        //    事后能回答「这条是系统判的，还是人判的」。
        String sql = """
                UPDATE tool_operation
                SET state = ?::tool_operation_state,
                    reconciled_at = ?,
                    reconciliation_source = 'MANUAL',
                    manual_disposed_by = ?,
                    manual_disposed_at = ?,
                    updated_at = now()
                WHERE tenant_id = ?::uuid AND operation_id = ?::uuid
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, disposition.name());
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setString(3, note == null ? operatorId : operatorId + ": " + note);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.setString(5, tenantId);
            ps.setString(6, operationId);
            int n = ps.executeUpdate();
            if (n != 1) {
                throw new IllegalStateException("操作更新行数异常: " + n);
            }
        }
    }

    private OperationItem read(ResultSet rs) throws SQLException {
        return new OperationItem(
                rs.getString("operation_id"),
                rs.getString("run_id"),
                rs.getString("tool_name"),
                rs.getString("risk_level"),
                rs.getString("state"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("reconciliation_source"),
                rs.getTimestamp("manual_disposed_at") != null);
    }

    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, new TenantContext(tenantId, "system", java.util.Set.of(), 0L));
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>⚠️ 用 setter 而不是可选构造参数：本仓既有构造签名被 {@code @Repository} 装配依赖，
     * 改成可选构造参数会把「影子态」渗进每一个调用点。setter 只在启动期被调用一次。
     */
    @Autowired(required = false)
    public void bindAuthzShadow(@Nullable AuthzShadowBinding binding) {
        if (binding == null) {
            return;
        }
        this.scoped = binding.scoped();
        this.reporter = binding.reporter();
        this.shadowEnabled = true;
        this.sentinelTenantId = binding.sentinelTenantId();
    }

    /** 影子比对：Gate 算出的 ScopeFilter vs 本次查询的绑定租户（只记日志）。 */
    void shadowCompare(@NonNull String resourceType,
                       @NonNull String boundTenantId,
                       boolean hasTenantPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(boundTenantId), boundTenantId, hasTenantPredicate);
    }

    /**
     * 逐行染色：结果行的真实 {@code tenant_id} 与 Gate 算出的 ScopeFilter 比对。
     *
     * <p>⚠️ <b>主体与行归属必须分开</b>（12 文 §4.5 · D6/D13）：
     * 主体是<b>发起本次查询的租户</b>，行归属从<b>结果行</b>读出的 {@code tenant_id}。
     * 若把行租户当作主体（{@code SubjectContext.forTenant(rowTenantId)}），
     * 策略引擎必然算出 {@code TenantOnly(行租户)}，逐行比对恒通过 ——
     * 染色会退化成「永远 0 违例」的装饰。
     *
     * @param subjectTenantId 发起查询的租户（主体）
     * @param rowTenantId 结果行的真实租户（不可用查询绑定租户代替，否则是同义反复）
     */
    void shadowTaint(@NonNull String resourceType,
                     @Nullable String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        // 哨兵行可由「行租户 == 哨兵租户」直接推断，无需调用方另行声明。
        boolean shared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                subjectTenantId == null ? null : SubjectContext.forTenant(subjectTenantId),
                rowTenantId, shared);
    }
}
