package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.RunMessagePort;

/**
 * 对话消息 JDBC 写入（P1 · G1 回放底座）。
 *
 * <p><b>⚠️ 写 {@code public.run_message}（带 FORCE RLS）</b>：必须在事务内先绑定租户，
 * 否则 RLS 默认拒绝 → <b>静默零行写入</b>（不报错）。
 * 这条症状极具欺骗性：「执行全绿、Run 成功、回放永远是空的」。
 * ⚠️ RLS 退役期（RLS依赖清理改造计划 M1/M4）：本连接自管（不经
 * {@code TransactionalConnectionHolder}），GUC 绑定是本类连接的 RLS 前置，
 * <b>保留至 M4 退役 {@code run_message} 策略批次</b>；SQL 自带显式
 * {@code tenant_id} 谓词，RLS 退役后功能不变。
 *
 * <p><b>⚠️ 幂等</b>：按 {@code (tenant_id, run_id, idem_key)} 冲突时覆盖——
 * 执行循环重试/重放会重新走一遍埋点，不去重会让回放出现重复对话。
 * <b>⚠️ 但同一轮可能有多条 TOOL 消息</b>（多工具并行），
 * 故 TOOL 消息的幂等键额外含工具名与操作标识，见 {@link #idempotencyKey}。
 *
 * <p><b>⚠️ 写入失败绝不抛出到主流程</b>：对话消息是复盘素材，
 * 但一次本可成功的辅导不能因「消息没记下」而变成失败——异常在此吞掉并记 WARN
 * （与 {@code JdbcRunStepRepository} 同纪律）。
 *
 * <p>⚠️ <b>刻意不挂 {@code @Repository}</b>：本仓储由
 * {@code ApplicationConfiguration} 显式 {@code @Bean} 注册（沿用 P4-D07 惯例）。
 * 若同时挂注解，组件扫描会再发现一份 → 同类型两个 Bean →
 * {@code NoUniqueBeanDefinitionException}，<b>整个 Spring 上下文启动失败</b>。
 */
public class JdbcRunMessageRepository implements RunMessagePort {

    /**
     * ⚠️ <b>静态单例而非每行 new</b>：{@code ObjectMapper} 构造代价高且线程安全，
     * 每行新建会让高 QPS 下的消息写入成为 CPU 热点。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    // ── 影子态接入（P1 影子 / 改造计划 WP-M1-1）──────────────────────────
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private volatile boolean shadowEnabled;
    private @NonNull String sentinelTenantId = "";

    public JdbcRunMessageRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 影子态装配入口：容器里存在 {@link AuthzShadowBinding} 即开启（P1 影子期），
     * 不存在即完全关闭（零行为变更）。
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

    @Override
    public void append(@NonNull String tenantId,
                       @NonNull String runId,
                       long fencingToken,
                       int stepNumber,
                       @NonNull String role,
                       @NonNull Map<String, Object> content,
                       @Nullable String toolOperationId,
                       @Nullable String modelId,
                       @Nullable Long inputTokens,
                       @Nullable Long outputTokens,
                       @Nullable Long latencyMs) {
        // ⚠️ 影子比对（P1 只记日志）：Gate 算出的 ScopeFilter vs 本次绑定租户。
        //    INSERT 自带显式 tenant_id 谓词 ⇒ hasTenantPredicate=true（不产 rlsDependent）。
        shadowCompare(tenantId);
        try (Connection conn = openTenant(tenantId)) {
            String json = serialize(content);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO public.run_message "
                            + "(tenant_id, run_id, step_number, role, content, tool_operation_id, "
                            + " model_id, input_tokens, output_tokens, latency_ms, fencing_token, idem_key) "
                            + "VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb, ?::uuid, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (tenant_id, run_id, idem_key) DO UPDATE SET "
                            + "  content = EXCLUDED.content, "
                            + "  tool_operation_id = EXCLUDED.tool_operation_id, "
                            + "  model_id = EXCLUDED.model_id, "
                            + "  input_tokens = EXCLUDED.input_tokens, "
                            + "  output_tokens = EXCLUDED.output_tokens, "
                            + "  latency_ms = EXCLUDED.latency_ms, "
                            + "  fencing_token = EXCLUDED.fencing_token")) {
                int i = 1;
                ps.setString(i++, tenantId);
                ps.setString(i++, runId);
                ps.setInt(i++, stepNumber);
                ps.setString(i++, role);
                ps.setString(i++, json);
                ps.setString(i++, toolOperationId);
                ps.setString(i++, modelId);
                setNullableLong(ps, i++, inputTokens);
                setNullableLong(ps, i++, outputTokens);
                setNullableLong(ps, i++, latencyMs);
                ps.setLong(i++, fencingToken);
                ps.setString(i, idempotencyKey(role, stepNumber, toolOperationId));
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            // ⚠️ 消息写入失败绝不冒泡到执行主流程（见类注释）
            org.slf4j.LoggerFactory.getLogger(JdbcRunMessageRepository.class)
                    .warn("⚠️ 写入对话消息失败（不影响本次执行结果）: runId={}, role={}, step={}, cause={}",
                            runId, role, stepNumber, e.toString());
        }
    }

    /**
     * 计算幂等键。
     *
     * <p>⚠️ <b>为何需要它</b>：{@code seq} 是 IDENTITY 自增，<b>不会冲突</b>，
     * 若直接 INSERT，执行循环重试一次就多一条消息 → 回放出现重复对话。
     *
     * <p>⚠️ <b>为何 TOOL 要额外区分</b>：同一轮可能有多个工具并行调用
     * （{@code step_number} 相同、{@code role} 也相同），
     * 只按 (step, role) 去重会把后一个工具的消息<b>覆盖掉</b> → 回放丢失工具调用。
     * 故 TOOL 消息的键含工具操作标识；其余角色用固定后缀即可。
     */
    private static String idempotencyKey(String role, int stepNumber,
                                         @Nullable String toolOperationId) {
        if (RunMessagePort.Roles.TOOL.equals(role)) {
            return "TOOL:" + stepNumber + ":" + (toolOperationId == null ? "-" : toolOperationId);
        }
        return role + ":" + stepNumber;
    }

    private static String serialize(Map<String, Object> content) {
        try {
            return JSON.writeValueAsString(content == null ? Map.of() : content);
        } catch (RuntimeException e) {
            // ⚠️ 序列化失败不能冒泡（消息是复盘素材，不是执行成败要件）
            return "{}";
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, @Nullable Long value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private Connection openTenant(String tenantId) throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        // ⚠️ RLS 铁律：事务内、任何业务 SQL 之前绑定（保留至 M4 退役 run_message 策略批次）
        TenantSessionBinder.bind(conn,
                new TenantContext(tenantId, "agent-run-message-writer", Set.of(), 0L));
        return conn;
    }

    // ── 影子辅助（守卫要求：声明与调用分离，查询路径必须真调用）────────

    /**
     * 影子比对：Gate 算出的 ScopeFilter vs 本次写入绑定租户（只记日志）。
     * 写路径没有结果行可染色，比对是本仓唯一的影子观测点。
     */
    void shadowCompare(@NonNull String boundTenantId) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, "run_message",
                SubjectContext.forTenant(boundTenantId), boundTenantId, true);
    }
}
