package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
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

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.ResultQueryPort;

/**
 * Run 结果查询实现（P4-D10 / G5）。
 *
 * <p>⚠️ <b>{@code tenant_id} 条件不可删除</b>：
 * 即便 RLS 已隔离，应用侧条件仍是第一道防线；
 * 若某天 RLS 策略被误删，显式条件仍能挡住跨租户读取。
 *
 * <p>⚠️ <b>失败原因不外泄，且由 SQL 层保证</b>：
 * 失败 Run 的 {@code result_payload} 形状是 {@code {"reason":"<内部失败原因>"}}，
 * 原因文本属内部错误细节。取讲解内容时用 {@code CASE WHEN state='SUCCEEDED'}
 * **在 SQL 层就把它挡掉**——这样「返回失败原文」不是一个可能被忘记的分支，
 * 而是**结构上不可能**。
 */
@Repository
public class JdbcResultQueryRepository implements ResultQueryPort {

    /**
     * ⚠️ <b>不取 {@code input_payload} 与 {@code error_message}</b>：
     * 前者含学生提交的题目与解答（敏感载荷），
     * 后者是内部错误原文（可能含实现细节）。
     *
     * <p>⚠️ <b>讲解内容由 SQL 层按状态截取</b>：
     * 只有 {@code SUCCEEDED} 才取 {@code result_payload->>'content'}，
     * 其余状态一律返回 {@code NULL}。
     *
     * <p>⚠️ <b>{@code tenant_id} 是为染色校验而取的</b>：不对外返回，仅用于
     * 把「结果行的真实租户」与 Gate 算出的 {@code ScopeFilter} 比对。
     * 若不取本列而拿「查询绑定租户」去比对，染色就是同义反复 —— 永远不可能违例，
     * 也就永远抓不到「谓词被删/被改坏」的情况。
     */
    private static final String SQL_FIND_RESULT = """
            SELECT run_id::text, tenant_id::text AS tenant_id, state::text, completed_at, error_code,
                   CASE WHEN state = 'SUCCEEDED' THEN result_payload->>'content' END AS explanation
            FROM run
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            """;

    /** 薄弱点：按 Run 检索（索引 idx_weakness_run 由 V9 迁移提供）。 */
    private static final String SQL_LOAD_WEAKNESSES = """
            SELECT tenant_id::text AS tenant_id, knowledge_point, error_type, confidence
            FROM weakness_record
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            ORDER BY created_at ASC
            LIMIT 50
            """;

    /** 步骤：按 Run 检索（索引 idx_run_step_run 已存在）。 */
    private static final String SQL_LOAD_STEPS = """
            SELECT tenant_id::text AS tenant_id, step_number, state::text, step_type,
                   output_payload::text AS output
            FROM run_step
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            ORDER BY step_number ASC
            LIMIT 100
            """;

    /**
     * 未决操作：<b>权威来源是 tool_operation 的 UNKNOWN 状态</b>。
     *
     * <p>⚠️ <b>不是 run.state</b>：工具结果未知时 Run 停留在 {@code RUNNING}
     * 等待对账（重投 = 重复副作用 = Sev-0，故一律 ack 丢弃），
     * {@code run_state} 枚举中<b>不存在</b> {@code RECONCILIATION_REQUIRED}。
     */
    private static final String SQL_LOAD_UNRESOLVED = """
            SELECT operation_id::text, tenant_id::text AS tenant_id, tool_name, state::text
            FROM tool_operation
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid AND state = 'UNKNOWN'
            ORDER BY created_at ASC
            LIMIT 50
            """;

    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    // ⚠️ 非 final：由 Spring 启动期通过 {@link #bindAuthzShadow} 一次性绑定，之后只读。
    //    容器里没有 AuthzShadowBinding 时保持构造期默认值（null / false）⇒ 零行为变更。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有单参构造，确保既有的 @Repository 装配不变）。 */
    @Autowired
    public JdbcResultQueryRepository(@NonNull DataSource dataSource) {
        this(dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthConfiguration} 在开启染色时调用；测试用）。
     *
     * @param shadowEnabled 是否启用影子比对 + 染色（P1 默认 false；P2 翻转为 true 进入阻断模式）
     * @param sentinelTenantId 哨兵（共享）租户标识，与 {@code SqlScopeTranslator} 共用配置项
     */
    JdbcResultQueryRepository(@NonNull DataSource dataSource,
                              @Nullable ScopedDataAccess scoped,
                              @Nullable AuthzShadowReporter reporter,
                              boolean shadowEnabled,
                              @NonNull String sentinelTenantId) {
        this.dataSource = dataSource;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
    }

    @Override
    public @Nullable RunResult findResult(@NonNull String tenantId, @NonNull String runId) {
        shadowCompare("run", tenantId, true);
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(SQL_FIND_RESULT)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        // ⚠️ 不存在与「不属于该租户」返回同一个 null：
                        //    上层据此统一 404，不区分二者——否则构成存在性探测通道。
                        return null;
                    }
                    // ⚠️ 主体 = 本次查询的租户；行归属 = 结果行真实 tenant_id。
                    //    两者必须分开传：用行租户当主体是同义反复（恒不违例）。
                    shadowTaint("run", tenantId, rs.getString("tenant_id"));
                    String state = rs.getString("state");
                    String explanation = rs.getString("explanation");
                    // ⚠️ 纵深防御：即使 SQL 被改坏（去掉了 CASE WHEN），
                    //    非成功状态也**不会**返回讲解内容。
                    if (!"SUCCEEDED".equals(state)) {
                        explanation = null;
                    }
                    List<Weakness> weaknesses = loadWeaknesses(lease.connection(), tenantId, runId);
                    List<Step> steps = loadSteps(lease.connection(), tenantId, runId);
                    List<UnknownOperation> unresolved =
                            loadUnresolved(lease.connection(), tenantId, runId);

                    return new RunResult(
                            rs.getString("run_id"),
                            state,
                            // ⚠️⚠️ completedAt 必须保持 null（不可退化成 Instant.EPOCH）：
                            //    非终态返回 1970-01-01T00:00:00Z 等于宣称
                            //    「该 Run 于 1970 年完成」——错误且误导。
                            toNullableInstant(rs.getTimestamp("completed_at")),
                            rs.getString("error_code"),
                            explanation,
                            weaknesses,
                            steps,
                            new Reconciliation(!unresolved.isEmpty(), unresolved));
                }
            } finally {
                // ⚠️ 只读查询：回滚以释放读事务。
                lease.rollbackQuietly();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Run 结果失败", e);
        }
    }

    private List<Weakness> loadWeaknesses(java.sql.Connection conn,
                                          String tenantId, String runId) throws SQLException {
        List<Weakness> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_LOAD_WEAKNESSES)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && result.size() < 50) {
                    BigDecimal confidence = rs.getBigDecimal("confidence");
                    shadowTaint("weakness_record", tenantId, rs.getString("tenant_id"));
                    result.add(new Weakness(
                            rs.getString("knowledge_point"),
                            rs.getString("error_type"),
                            confidence));
                }
            }
        }
        return result;
    }

    private List<Step> loadSteps(java.sql.Connection conn,
                                  String tenantId, String runId) throws SQLException {
        List<Step> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_LOAD_STEPS)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && result.size() < 100) {
                    shadowTaint("run_step", tenantId, rs.getString("tenant_id"));
                    result.add(new Step(
                            rs.getInt("step_number"),
                            rs.getString("state"),
                            rs.getString("step_type"),
                            rs.getString("output")));
                }
            }
        }
        return result;
    }

    private List<UnknownOperation> loadUnresolved(java.sql.Connection conn,
                                                   String tenantId, String runId) throws SQLException {
        List<UnknownOperation> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_LOAD_UNRESOLVED)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && result.size() < 50) {
                    shadowTaint("tool_operation", tenantId, rs.getString("tenant_id"));
                    result.add(new UnknownOperation(
                            rs.getString("operation_id"),
                            rs.getString("tool_name"),
                            rs.getString("state")));
                }
            }
        }
        return result;
    }

    /**
     * 可空时刻：{@code null} 原样保留。
     *
     * <p>⚠️ 与 {@code JdbcRunQueryRepository.toInstant} 的区别是刻意的：
     * {@code created_at} 是 NOT NULL 列，用 EPOCH 兜底可掩盖数据异常；
     * 而 {@code completed_at} 在非终态下<b>本就应该</b>为 {@code null}——
     * 把 {@code null} 变成 EPOCH 会伪造出一个「已完成」的时间点。
     */
    private static @Nullable Instant toNullableInstant(@Nullable Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>⚠️ 用 setter 而不是可选构造参数：本仓既有构造签名被大量单测与 {@code @Bean} 装配依赖，
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

    /**
     * 取得已绑定租户的连接。
     *
     * <p>⚠️ 与 {@code JdbcRunQueryRepository} / {@code JdbcProfileQueryRepository} 同源铁律：
     * 外层有事务则复用，否则自开并绑定租户。
     * {@code set_config(is_local=true)} <b>必须配合事务</b>，自动提交下立即失效。
     */
    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        java.sql.Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, systemContext(tenantId));
    }

    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }
}
