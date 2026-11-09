package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.RiskEventPort;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 安全事件 JDBC 写入（P7-4 · {@code platform.agent_risk_event}）。
 *
 * <p><b>⚠️ trace_id 解析口径与指标采集器逐字一致</b>
 * （{@code JdbcTraceFactRepository.readRun}）：
 * {@code run.trace_id} → {@code agent_invocation(depth=1).trace_id} →
 * {@code correlation_id} → 都解析不出则<b>放弃写入</b>——
 * 宁可缺一条合规明细，也不落一个假 trace_id 污染聚合。
 *
 * <p><b>⚠️ 读在租户域、写在平台域</b>：解析 trace_id 要读带 FORCE RLS 的
 * {@code public.run} / {@code public.agent_invocation}，故连接必须先绑定租户；
 * 而 {@code platform.agent_risk_event} 无 RLS，同一连接可直接写入
 * （延续 {@code JdbcAgentMetricRepository} 的同连接跨域写入做法）。
 *
 * <p><b>⚠️ 合规红线</b>：只落 {@code detail_digest}（摘要），绝不落原文。
 *
 * <p><b>⚠️ 写入失败绝不冒泡到执行主流程</b>：吞异常记 WARN——
 * 安全事件缺一条是可接受的观测损失，让一次本可成功的 Run 失败不可接受。
 *
 * <p>⚠️ <b>P1 影子态（2026-11-09 补谓词 + 接线）</b>：{@code resolveTraceId} /
 * {@code readInvocationTraceId} 两个内部读此前<b>缺显式租户谓词</b>
 * （完全靠 RLS 兜底，属 {@code rlsDependent} 清单 —— 退役 RLS 后即越权）。
 * 现已补显式 {@code tenant_id = ?::uuid} 谓词并接影子钩子
 * （{@code run} / {@code agent_invocation} 均已登记 TENANT_ONLY），
 * SELECT 补 {@code tenant_id::text} 供逐行染色取行归属。
 * 写路径 {@link #record}（INSERT 平台域 {@code agent_risk_event}，无 RLS 表）
 * 属 P2 范围，P1 不观测。
 */
public class JdbcRiskEventRepository implements RiskEventPort {

    /** 影子比对/染色的资源类型（对齐 policy-mapping.yaml 的 TENANT_ONLY，均已登记）。 */
    private static final String RESOURCE_TYPE_RUN = "run";
    private static final String RESOURCE_TYPE_AGENT_INVOCATION = "agent_invocation";

    private final DataSource dataSource;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    @Autowired
    public JdbcRiskEventRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 影子态接线构造（测试与包内装配用；生产走 {@link #bindAuthzShadow} setter）。 */
    JdbcRiskEventRepository(@NonNull DataSource dataSource,
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
    public void record(@NonNull String tenantId,
                       @NonNull String runId,
                       @NonNull String eventType,
                       @Nullable String detailDigest) {
        try (Connection conn = openTenant(tenantId)) {
            String traceId = resolveTraceId(conn, tenantId, runId);
            if (traceId == null) {
                org.slf4j.LoggerFactory.getLogger(JdbcRiskEventRepository.class)
                        .warn("⚠️ 安全事件无可用 trace_id（run 无 trace_id/invocation/correlation），"
                                + "放弃写入（宁缺毋假）: runId={}, type={}", runId, eventType);
                return;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO platform.agent_risk_event "
                            + "(trace_id, tenant_id, event_type, detail_digest) "
                            + "VALUES (?, ?::uuid, ?, ?)")) {
                ps.setString(1, traceId);
                ps.setString(2, tenantId);
                ps.setString(3, eventType);
                ps.setString(4, detailDigest);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            org.slf4j.LoggerFactory.getLogger(JdbcRiskEventRepository.class)
                    .warn("⚠️ 写入安全事件失败（不影响本次执行结果）: runId={}, type={}, cause={}",
                            runId, eventType, e.toString());
        }
    }

    /**
     * trace_id 三级解析（与 {@code JdbcTraceFactRepository.readRun} 同口径）。
     *
     * @return 可用的 trace_id；三级都解析不出返回 {@code null}
     */
    private @Nullable String resolveTraceId(Connection conn, String tenantId, String runId) throws SQLException {
        String runTrace;
        String correlation;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tenant_id::text, trace_id, correlation_id FROM public.run "
                        + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            // ⚠️ hasTenantPredicate=true：已补显式 `tenant_id = ?::uuid`（此前纯靠 RLS 兜底）。
            shadowCompare(RESOURCE_TYPE_RUN, tenantId, true);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                // 逐行染色：行归属取结果行的 tenant_id（与绑定租户比对）。
                shadowTaint(RESOURCE_TYPE_RUN, tenantId, rs.getString("tenant_id"));
                runTrace = rs.getString("trace_id");
                correlation = rs.getString("correlation_id");
            }
        }
        if (runTrace != null && !runTrace.isBlank()) {
            return runTrace;
        }
        String invocationTrace = readInvocationTraceId(conn, tenantId, runId);
        if (invocationTrace != null && !invocationTrace.isBlank()) {
            return invocationTrace;
        }
        return (correlation != null && !correlation.isBlank()) ? correlation : null;
    }

    private @Nullable String readInvocationTraceId(Connection conn, String tenantId, String runId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tenant_id::text, trace_id FROM public.agent_invocation "
                        + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid AND depth = 1 "
                        + "ORDER BY started_at LIMIT 1")) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            // ⚠️ hasTenantPredicate=true：已补显式 `tenant_id = ?::uuid`（此前纯靠 RLS 兜底）。
            shadowCompare(RESOURCE_TYPE_AGENT_INVOCATION, tenantId, true);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                // 逐行染色：行归属取结果行的 tenant_id（与绑定租户比对）。
                shadowTaint(RESOURCE_TYPE_AGENT_INVOCATION, tenantId, rs.getString("tenant_id"));
                return rs.getString("trace_id");
            }
        }
    }

    private Connection openTenant(String tenantId) throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        // ⚠️ RLS 铁律：事务内、任何业务 SQL 之前绑定
        TenantSessionBinder.bind(conn,
                new TenantContext(tenantId, "agent-risk-event-writer", Set.of(), 0L));
        return conn;
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

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

    /**
     * 影子比对：Gate 算出的 ScopeFilter vs 本次查询的绑定租户（只记日志）。
     *
     * @param hasTenantPredicate 该查询的 SQL 是否真的带了显式租户谓词（必须如实填写）。
     */
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
