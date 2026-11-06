package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RiskEventPort;

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
 */
public class JdbcRiskEventRepository implements RiskEventPort {

    private final DataSource dataSource;

    public JdbcRiskEventRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void record(@NonNull String tenantId,
                       @NonNull String runId,
                       @NonNull String eventType,
                       @Nullable String detailDigest) {
        try (Connection conn = openTenant(tenantId)) {
            String traceId = resolveTraceId(conn, runId);
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
    private @Nullable String resolveTraceId(Connection conn, String runId) throws SQLException {
        String runTrace;
        String correlation;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT trace_id, correlation_id FROM public.run WHERE run_id = ?::uuid")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                runTrace = rs.getString("trace_id");
                correlation = rs.getString("correlation_id");
            }
        }
        if (runTrace != null && !runTrace.isBlank()) {
            return runTrace;
        }
        String invocationTrace = readInvocationTraceId(conn, runId);
        if (invocationTrace != null && !invocationTrace.isBlank()) {
            return invocationTrace;
        }
        return (correlation != null && !correlation.isBlank()) ? correlation : null;
    }

    private @Nullable String readInvocationTraceId(Connection conn, String runId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT trace_id FROM public.agent_invocation "
                        + "WHERE run_id = ?::uuid AND depth = 1 ORDER BY started_at LIMIT 1")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("trace_id") : null;
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
}
