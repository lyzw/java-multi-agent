package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.orchestration.AgentInvocation;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.AgentInvocationPort;

/**
 * {@link AgentInvocationPort} 的 PostgreSQL 实现（多级 Agent v1.1 · P2-MA-01）。
 *
 * <p>⚠️ <b>幂等插入</b>：{@code ON CONFLICT (tenant_id, invocation_id) DO NOTHING}。
 * 重复投递不创建重复逻辑调用（MA-T10）。
 *
 * <p>⚠️ <b>终态写入是 CAS</b>：{@code WHERE fencing_token = ? AND status NOT IN (终态)}。
 * 旧世代带过期 token 提交 → 影响行数 0 → 返回 {@code false}（MA-T12）；
 * 已是终态也不覆盖（第一次写入的终态是权威事实）。
 */
public class JdbcAgentInvocationRepository implements AgentInvocationPort {

    private static final String SQL_INSERT = """
            INSERT INTO agent_invocation
                (invocation_id, tenant_id, run_id, parent_invocation_id,
                 parent_agent_id, parent_agent_version,
                 target_agent_id, target_agent_version,
                 status, depth, trace_id, input_digest, output_digest, reason_code,
                 fencing_token, started_at, finished_at)
            VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, invocation_id) DO NOTHING
            """;

    private static final String SQL_FIND = """
            SELECT invocation_id::text, tenant_id::text, run_id::text,
                   parent_invocation_id::text, parent_agent_id::text, parent_agent_version,
                   target_agent_id::text, target_agent_version,
                   status, depth, trace_id, input_digest, output_digest, reason_code,
                   fencing_token, started_at, finished_at
            FROM agent_invocation
            WHERE tenant_id = ?::uuid AND invocation_id = ?::uuid
            """;

    private static final String SQL_LIST_BY_RUN = """
            SELECT invocation_id::text, tenant_id::text, run_id::text,
                   parent_invocation_id::text, parent_agent_id::text, parent_agent_version,
                   target_agent_id::text, target_agent_version,
                   status, depth, trace_id, input_digest, output_digest, reason_code,
                   fencing_token, started_at, finished_at
            FROM agent_invocation
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            ORDER BY started_at ASC, invocation_id ASC
            """;

    private static final String SQL_MARK_TERMINAL = """
            UPDATE agent_invocation
               SET status = ?, reason_code = ?, output_digest = ?, finished_at = ?
             WHERE tenant_id = ?::uuid AND invocation_id = ?::uuid
               AND fencing_token = ?
               AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'UNKNOWN')
            """;

    /**
     * 接管时提升在途记录的世代令牌（MA-T12 · P3-MA-02）。
     *
     * <p>⚠️ 三个条件缺一不可：
     * <ul>
     *   <li>{@code status NOT IN (终态)} → 只动在途记录，<b>终态不被改写</b>
     *       （终态是权威事实，接管方没资格改）；</li>
     *   <li>{@code fencing_token < ?} → 只提升<b>更旧</b>世代的记录；
     *       已经等于新世代的不动（幂等：重复接管不会重复改）；</li>
     *   <li>单条原子 UPDATE → 并发接管不会互相覆盖。</li>
     * </ul>
     */
    private static final String SQL_ADOPT_IN_FLIGHT = """
            UPDATE agent_invocation
               SET fencing_token = ?
             WHERE tenant_id = ?::uuid AND run_id = ?::uuid
               AND fencing_token < ?
               AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'UNKNOWN')
            """;

    /**
     * CAS 未命中后的诊断查询：区分「已是终态」与「token 过期」（MA-T12）。
     */
    private static final String SQL_DIAGNOSE = """
            SELECT status, fencing_token
            FROM agent_invocation
            WHERE tenant_id = ?::uuid AND invocation_id = ?::uuid
            """;

    private final DataSource dataSource;

    public JdbcAgentInvocationRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean insert(@NonNull AgentInvocation invocation) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(invocation.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, invocation.invocationId());
                        ps.setString(2, invocation.tenantId());
                        ps.setString(3, invocation.runId());
                        if (invocation.parentInvocationId() == null) {
                            ps.setNull(4, java.sql.Types.OTHER);
                        } else {
                            ps.setString(4, invocation.parentInvocationId());
                        }
                        ps.setString(5, invocation.parentAgentId());
                        ps.setInt(6, invocation.parentAgentVersion());
                        ps.setString(7, invocation.targetAgentId());
                        ps.setInt(8, invocation.targetAgentVersion());
                        ps.setString(9, invocation.status().name());
                        ps.setInt(10, invocation.depth());
                        ps.setString(11, invocation.traceId());
                        ps.setString(12, invocation.inputDigest());
                        ps.setString(13, invocation.outputDigest());
                        ps.setString(14, invocation.reasonCode());
                        ps.setLong(15, invocation.fencingToken());
                        if (invocation.startedAt() == null) {
                            ps.setNull(16, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(16, Timestamp.from(invocation.startedAt()));
                        }
                        if (invocation.finishedAt() == null) {
                            ps.setNull(17, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(17, Timestamp.from(invocation.finishedAt()));
                        }
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public @NonNull Optional<AgentInvocation> find(@NonNull String tenantId,
                                                    @NonNull String invocationId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, invocationId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.<AgentInvocation>empty();
                        }
                    }
                });
    }

    @Override
    public @NonNull List<AgentInvocation> listByRun(@NonNull String tenantId,
                                                    @NonNull String runId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<AgentInvocation> rows = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_BY_RUN)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, runId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(map(rs));
                            }
                        }
                    }
                    return rows;
                });
    }

    @Override
    public int adoptInFlight(@NonNull String tenantId,
                             @NonNull String runId,
                             long newFencingToken) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_ADOPT_IN_FLIGHT)) {
                        ps.setLong(1, newFencingToken);
                        ps.setString(2, tenantId);
                        ps.setString(3, runId);
                        ps.setLong(4, newFencingToken);
                        return ps.executeUpdate();
                    }
                });
    }

    @Override
    public @NonNull TerminalWriteOutcome markTerminal(@NonNull String tenantId,
                                                      @NonNull String invocationId,
                                                      @NonNull AgentInvocationStatus status,
                                                      @Nullable String reasonCode,
                                                      @Nullable String outputDigest,
                                                      @Nullable Instant finishedAt,
                                                      long fencingToken) {
        if (!status.isTerminal()) {
            // ⚠️ 非终态不得走终态写入路径：那会把中间态固化成「结果」。
            throw new IllegalArgumentException("status 必须是终态: " + status);
        }
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_MARK_TERMINAL)) {
                        ps.setString(1, status.name());
                        ps.setString(2, reasonCode);
                        ps.setString(3, outputDigest);
                        if (finishedAt == null) {
                            ps.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(4, Timestamp.from(finishedAt));
                        }
                        ps.setString(5, tenantId);
                        ps.setString(6, invocationId);
                        ps.setLong(7, fencingToken);
                        if (ps.executeUpdate() > 0) {
                            return TerminalWriteOutcome.WRITTEN;
                        }
                    }
                    // ⚠️ 影响行数 0 有两种成因，必须区分（MA-T12）：
                    //    「已是终态」（幂等重放，返回既有终态）
                    //    vs 「token 过期」（旧世代越权提交，不得承认本次结果）。
                    return diagnoseMissedUpdate(conn, tenantId, invocationId, fencingToken);
                });
    }

    /**
     * 诊断「CAS 未命中」的原因。
     *
     * @param conn         连接
     * @param tenantId     租户标识
     * @param invocationId 调用标识
     * @param fencingToken 本次提交的世代令牌
     * @return 细分后的写入结果
     */
    private static @NonNull TerminalWriteOutcome diagnoseMissedUpdate(
            java.sql.Connection conn, @NonNull String tenantId,
            @NonNull String invocationId, long fencingToken) {
        try (PreparedStatement ps = conn.prepareStatement(SQL_DIAGNOSE)) {
            ps.setString(1, tenantId);
            ps.setString(2, invocationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return TerminalWriteOutcome.NOT_FOUND;
                }
                String currentStatus = rs.getString("status");
                long currentToken = rs.getLong("fencing_token");
                AgentInvocationStatus parsed = AgentInvocationStatus.valueOf(currentStatus);
                if (parsed.isTerminal()) {
                    // ⚠️ 已是终态 → 幂等重放，**不得**覆盖既有终态
                    return TerminalWriteOutcome.ALREADY_TERMINAL;
                }
                if (currentToken != fencingToken) {
                    // ⚠️ 旧世代带过期 token 提交 → 必须失败（MA-T12）
                    return TerminalWriteOutcome.TOKEN_EXPIRED;
                }
                // ⚠️ 非终态且 token 匹配却没写进去：条件不成立但又不像过期 ——
                //    保守按 token 过期处理（不得承认本次结果）。
                return TerminalWriteOutcome.TOKEN_EXPIRED;
            }
        } catch (SQLException e) {
            // ⚠️ 诊断读不到 → 保守按 token 过期处理（fail-closed）
            throw new IllegalStateException("诊断终态写入失败: invocation=" + invocationId, e);
        }
    }

    private static AgentInvocation map(ResultSet rs) throws SQLException {
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        Timestamp startedAt = rs.getTimestamp("started_at");
        return new AgentInvocation(
                rs.getString("invocation_id"),
                rs.getString("tenant_id"),
                rs.getString("run_id"),
                rs.getString("parent_invocation_id"),
                rs.getString("parent_agent_id"),
                rs.getInt("parent_agent_version"),
                rs.getString("target_agent_id"),
                rs.getInt("target_agent_version"),
                AgentInvocationStatus.valueOf(rs.getString("status")),
                rs.getInt("depth"),
                rs.getString("trace_id"),
                rs.getString("input_digest"),
                rs.getString("output_digest"),
                startedAt == null ? null : startedAt.toInstant(),
                finishedAt == null ? null : finishedAt.toInstant(),
                rs.getString("reason_code"),
                rs.getLong("fencing_token"));
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }
}
