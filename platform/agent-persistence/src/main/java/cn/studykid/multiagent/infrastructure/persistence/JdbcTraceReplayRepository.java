package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 对话回放 JDBC 读取（P1 · G1 闭环）。
 *
 * <p><b>⚠️ 这些表全带 FORCE RLS</b>（{@code run_message} / {@code tool_operation}
 * 策略形如 {@code tenant_id = current_tenant_id()}）。
 * 因此必须先用 {@link TenantSessionBinder#bind} 在<b>事务内</b>绑定租户——
 * 用系统连接直读会<b>静默返回空集</b>：症状是「回放永远是空的」而不报错，
 * 极易被误判为「没有采集到内容」。
 *
 * <p><b>⚠️ SQL 必须显式携带 {@code tenant_id} 谓词</b>：即便 RLS 已隔离，
 * 应用侧条件仍是第一道防线；若某天 RLS 策略被误删，显式条件仍能挡住跨租户读取。
 *
 * <p><b>⚠️ 只读</b>：本类不写任何表。
 *
 * <p>⚠️ <b>刻意不挂 {@code @Repository}</b>：本仓储由
 * {@code ApplicationConfiguration} 显式 {@code @Bean} 注册（沿用 P4-D07 惯例）。
 * 若同时挂注解，组件扫描会再发现一份 → 同类型两个 Bean →
 * {@code NoUniqueBeanDefinitionException}，<b>整个 Spring 上下文启动失败</b>。
 */
public class JdbcTraceReplayRepository implements TraceReplayPort {

    /**
     * ⚠️ <b>静态单例而非每行 new</b>：{@code ObjectMapper} 构造代价高且线程安全。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    public JdbcTraceReplayRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull List<MessageView> messages(@NonNull String tenantId, @NonNull String runId) {
        List<MessageView> out = new ArrayList<>();
        try (Connection conn = openTenant(tenantId)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tenant_id, seq, role, step_number, content, tool_operation_id, model_id, "
                            + "       input_tokens, output_tokens, latency_ms, created_at "
                            + "FROM public.run_message "
                            + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid "
                            + "ORDER BY seq ASC")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                shadowCompare("run_message", tenantId, true);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String rowTenant = rs.getString("tenant_id");
                        shadowTaint("run_message", tenantId, rowTenant);
                        out.add(new MessageView(
                                rs.getLong("seq"),
                                rs.getString("role"),
                                nullableInt(rs, "step_number"),
                                readJsonObject(rs.getString("content")),
                                rs.getString("tool_operation_id"),
                                rs.getString("model_id"),
                                nullableLong(rs, "input_tokens"),
                                nullableLong(rs, "output_tokens"),
                                nullableLong(rs, "latency_ms"),
                                rs.getTimestamp("created_at").toInstant()));
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("读取对话消息失败: run=" + runId, e);
        }
        return out;
    }

    @Override
    public @NonNull Optional<Map<String, Object>> toolOperation(@NonNull String tenantId,
                                                                 @NonNull String operationId) {
        try (Connection conn = openTenant(tenantId)) {
            Optional<Map<String, Object>> out = readToolOperation(conn, tenantId, operationId);
            conn.commit();
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("读取工具操作失败: operation=" + operationId, e);
        }
    }

    private Optional<Map<String, Object>> readToolOperation(Connection conn, String tenantId,
                                                           String operationId) throws SQLException {
        // ⚠️⚠️ **列名必须与 01 终态快照逐字对齐**（2026-11-09 修正）：
        //    初版 SQL 引用了 error_class / completed_at 两个**根本不存在的列**
        //    （表里是 request_digest / updated_at）。因为 toolOperation() 此前
        //    没有任何 IT 覆盖，这个错误一直潜伏 —— 端点一旦被调用就报
        //    `column "error_class" does not exist`，而 fail-soft 只留一行 WARN。
        //    这正是「方法写了、却从没真跑过」的典型形态。
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tenant_id, operation_id, tool_name, risk_level, state, tool_arguments, "
                        + "       result_payload, request_digest, created_at, updated_at "
                        + "FROM public.tool_operation "
                        + "WHERE tenant_id = ?::uuid AND operation_id = ?::uuid")) {
            ps.setString(1, tenantId);
            ps.setString(2, operationId);
            shadowCompare("tool_operation", tenantId, true);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String rowTenant = rs.getString("tenant_id");
                shadowTaint("tool_operation", tenantId, rowTenant);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("operationId", rs.getString("operation_id"));
                out.put("toolName", rs.getString("tool_name"));
                out.put("riskLevel", rs.getString("risk_level"));
                out.put("state", rs.getString("state"));
                out.put("toolArguments", readJsonObject(rs.getString("tool_arguments")));
                out.put("resultPayload", readJsonObject(rs.getString("result_payload")));
                // ⚠️ 无 error_class 列：失败原因归并进 request_digest（参数摘要）
                out.put("requestDigest", rs.getString("request_digest"));
                out.put("createdAt", rs.getTimestamp("created_at").toInstant());
                Timestamp updated = rs.getTimestamp("updated_at");
                out.put("updatedAt", updated == null ? null : updated.toInstant());
                return Optional.of(out);
            }
        }
    }

    @Override
    public @NonNull Optional<RunMeta> runMeta(@NonNull String tenantId, @NonNull String runId) {
        try (Connection conn = openTenant(tenantId)) {
            Optional<RunMeta> out = readRunMeta(conn, tenantId, runId);
            conn.commit();
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Run 元信息失败: run=" + runId, e);
        }
    }

    @Override
    public @NonNull List<StepView> steps(@NonNull String tenantId, @NonNull String runId) {
        List<StepView> out = new ArrayList<>();
        try (Connection conn = openTenant(tenantId)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tenant_id, step_number, step_type, "
                            + "       EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000 AS duration_ms "
                            + "FROM public.run_step "
                            + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid "
                            + "ORDER BY step_number ASC")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                shadowCompare("run_step", tenantId, true);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String rowTenant = rs.getString("tenant_id");
                        shadowTaint("run_step", tenantId, rowTenant);
                        out.add(new StepView(
                                rs.getInt("step_number"),
                                rs.getString("step_type"),
                                nullableLong(rs, "duration_ms")));
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("读取执行步骤失败: run=" + runId, e);
        }
        return out;
    }

    @Override
    public @NonNull Optional<Map<String, Object>> snapshot(@NonNull String tenantId,
                                                            @NonNull String snapshotId) {
        try (Connection conn = openTenant(tenantId)) {
            Optional<Map<String, Object>> out = readSnapshot(conn, tenantId, snapshotId);
            conn.commit();
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("读取配置快照失败: snapshot=" + snapshotId, e);
        }
    }

    @Override
    public @NonNull Optional<TokenTotal> tokenTotal(@NonNull String tenantId,
                                                      @NonNull String runId) {
        try (Connection conn = openTenant(tenantId)) {
            Optional<TokenTotal> out = readTokenTotal(conn, tenantId, runId);
            conn.commit();
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("读取用量汇总失败: run=" + runId, e);
        }
    }

    @Override
    public @NonNull Optional<String> resolveTenantId(@NonNull String runId) {
        // ⚠️⚠️ **绝不能查 public.run**：它是 FORCE RLS 表，应用角色 app_agent 是
        //    NOBYPASSRLS，未绑 app.tenant_id 时 SELECT 静默返回 0 行；
        //    而平台侧恰恰「不知道该绑哪个租户」——旧实现因此对所有 Run 都返回
        //    Optional.empty()，平台回放端点恒 404（不报错、功能全程空转）。
        //
        //    实证（RDS agent_dev, SET LOCAL ROLE app_agent）：
        //      未绑定 → 0 行；set_config 清空 → 0 行；绑定正确 tenant_id → 1 行。
        //
        // ✅ 改查平台域宽表 platform.agent_trace_metric（无 RLS、由 P0 自动采集，
        //    含 run_id + tenant_id）。clear 只影响租户表，平台表不受约束。
        // ⚠️ 语义收窄：**只有已被采集过指标的终态 Run** 能仅凭 runId 反查；
        //    未采集的 Run 必须由调用方显式传 tenantId（平台端点已支持该参数）。
        //    这不是「完整反查」，端点 404 文案必须如实说明，不能伪装成「Run 不存在」。
        //
        // ⚠️ <b>P1 影子态豁免</b>：本方法是<b>平台域</b>反查（系统连接、按 run_id 查、
        //    不带租户谓词），平台运维角色合法跨租户读取——与 {@code JdbcReviewTaskRepository}
        //    同属平台域，<b>不在</b>租户级读路径影子观测范围内（D5/C 组平台级豁免口径）。
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            TenantSessionBinder.clear(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tenant_id FROM platform.agent_trace_metric "
                            + "WHERE run_id = ?::uuid LIMIT 1")) {
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString("tenant_id"));
                    }
                }
            }
            conn.commit();
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("按 runId 反查租户失败: run=" + runId, e);
        }
    }

    private Optional<RunMeta> readRunMeta(Connection conn, String tenantId, String runId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tenant_id, run_id, trace_id, state, snapshot_id, created_at, completed_at, "
                        + "       EXTRACT(EPOCH FROM (completed_at - created_at)) * 1000 AS duration_ms "
                        + "FROM public.run "
                        + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            shadowCompare("run", tenantId, true);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String rowTenant = rs.getString("tenant_id");
                shadowTaint("run", tenantId, rowTenant);
                return Optional.of(new RunMeta(
                        rs.getString("run_id"),
                        rs.getString("trace_id"),
                        rs.getString("state"),
                        rs.getString("snapshot_id"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null
                                ? null : rs.getTimestamp("completed_at").toInstant(),
                        nullableLong(rs, "duration_ms")));
            }
        }
    }

    private Optional<Map<String, Object>> readSnapshot(Connection conn, String tenantId,
                                                       String snapshotId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tenant_id, snapshot_id, version_id, agent_config, prompt_config, "
                        + "       tool_contracts, run_config, created_at "
                        + "FROM public.execution_snapshot "
                        + "WHERE tenant_id = ?::uuid AND snapshot_id = ?::uuid")) {
            ps.setString(1, tenantId);
            ps.setString(2, snapshotId);
            shadowCompare("execution_snapshot", tenantId, true);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String rowTenant = rs.getString("tenant_id");
                shadowTaint("execution_snapshot", tenantId, rowTenant);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("snapshotId", rs.getString("snapshot_id"));
                out.put("versionId", rs.getString("version_id"));
                out.put("agentConfig", readJsonObject(rs.getString("agent_config")));
                out.put("promptConfig", readJsonObject(rs.getString("prompt_config")));
                out.put("toolContracts", readJsonObject(rs.getString("tool_contracts")));
                out.put("runConfig", readJsonObject(rs.getString("run_config")));
                out.put("createdAt", rs.getTimestamp("created_at").toInstant());
                return Optional.of(out);
            }
        }
    }

    private Optional<TokenTotal> readTokenTotal(Connection conn, String tenantId, String runId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(input_tokens), 0) AS input_tokens, "
                        + "       COALESCE(SUM(output_tokens), 0) AS output_tokens, "
                        + "       SUM(cost_amount) AS cost_amount "
                        + "FROM public.usage_record "
                        + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            shadowCompare("usage_record", tenantId, true);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long input = rs.getLong("input_tokens");
                long output = rs.getLong("output_tokens");
                BigDecimal cost = rs.getBigDecimal("cost_amount");
                if (input == 0 && output == 0 && cost == null) {
                    // ⚠️ 无用量记录与「用量为零」不可区分 —— 保留 empty，
                    //    让回放显式呈现「没有用量数据」而不是「这次没花钱」。
                    return Optional.empty();
                }
                return Optional.of(new TokenTotal(input, output, cost));
            }
        }
    }

    /**
     * 解析 jsonb 列。
     *
     * <p>⚠️ <b>解析失败必须降级为空对象，不能抛</b>：一条坏消息不应让整个回放 500。
     * 坏数据本身已经是异常信号（写侧序列化有问题），回放侧如实呈现「这条读不出来」即可。
     */
    private static Map<String, Object> readJsonObject(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(raw, Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Connection openTenant(String tenantId) throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        // ⚠️ RLS 铁律：事务内、任何业务 SQL 之前绑定
        TenantSessionBinder.bind(conn,
                new TenantContext(tenantId, "agent-trace-replay-reader", Set.of(), 0L));
        return conn;
    }

    // ══════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
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
     */
    void shadowTaint(@NonNull String resourceType,
                     @Nullable String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        boolean shared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                subjectTenantId == null ? null : SubjectContext.forTenant(subjectTenantId),
                rowTenantId, shared);
    }
}
