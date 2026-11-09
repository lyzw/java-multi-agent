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
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AgentInvocationPort;

import org.springframework.beans.factory.annotation.Autowired;

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

    /**
     * 查询列清单（{@code find} 与 {@code listByRun} 共用）。
     *
     * <p>⚠️ 两处各写一遍迟早漏一列，而漏列的症状不是报错、
     * 是 {@code map()} 把该列读成 {@code null} ——「快照看起来没落库」的假象。
     */
    private static final String COLUMNS = """
            invocation_id::text, tenant_id::text, run_id::text,
                   parent_invocation_id::text, parent_agent_id::text, parent_agent_version,
                   target_agent_id::text, target_agent_version,
                   requested_target_agent_id::text,
                   status, depth, trace_id, input_digest, output_digest, reason_code,
                   fencing_token, started_at, finished_at,
                   routing_decision::text, policy_ref, policy_fingerprint
            """;

    private static final String SQL_INSERT = """
            INSERT INTO agent_invocation
                (invocation_id, tenant_id, run_id, parent_invocation_id,
                 parent_agent_id, parent_agent_version,
                 target_agent_id, target_agent_version,
                 requested_target_agent_id,
                 status, depth, trace_id, input_digest, output_digest, reason_code,
                 fencing_token, started_at, finished_at,
                 routing_decision, policy_ref, policy_fingerprint)
            VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (tenant_id, invocation_id) DO NOTHING
            """;

    private static final String SQL_FIND = "SELECT \s" + COLUMNS + """
            FROM agent_invocation
            WHERE tenant_id = ?::uuid AND invocation_id = ?::uuid
            """;

    private static final String SQL_LIST_BY_RUN = "SELECT \s" + COLUMNS + """
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

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // agent_invocation 为租户隔离实体（policy-mapping.yaml: TENANT_ONLY + FORCE RLS）。
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有单参构造，确保 @Bean 装配不变）。 */
    @Autowired
    public JdbcAgentInvocationRepository(@NonNull DataSource dataSource) {
        this(dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     */
    JdbcAgentInvocationRepository(@NonNull DataSource dataSource,
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
                        // ⚠️ 「模型原始候选」可空：存量行的回填口径是「不回填」
                        //    （当时只有日志留痕，没有可靠副本 —— 见 V25 迁移脚本）。
                        if (invocation.requestedTargetAgentId() == null) {
                            ps.setNull(9, java.sql.Types.OTHER);
                        } else {
                            ps.setString(9, invocation.requestedTargetAgentId());
                        }
                        ps.setString(10, invocation.status().name());
                        ps.setInt(11, invocation.depth());
                        ps.setString(12, invocation.traceId());
                        ps.setString(13, invocation.inputDigest());
                        ps.setString(14, invocation.outputDigest());
                        ps.setString(15, invocation.reasonCode());
                        ps.setLong(16, invocation.fencingToken());
                        if (invocation.startedAt() == null) {
                            ps.setNull(17, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(17, Timestamp.from(invocation.startedAt()));
                        }
                        if (invocation.finishedAt() == null) {
                            ps.setNull(18, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(18, Timestamp.from(invocation.finishedAt()));
                        }
                        // ⚠️ 决策快照走 jsonb 而不是 text：库要能按 ->>'resolution' 直接问
                        //    「这批委派有多少走了兜底」，而 text 列只能靠字符串匹配。
                        //    null 的语义是「当时没做路由决策」（非 ROUTER 模式 / 存量行），
                        //    与「决策失败」（那不会有记录）不同 —— 见列注释。
                        if (invocation.routingDecision() == null) {
                            ps.setNull(19, java.sql.Types.OTHER);
                        } else {
                            ps.setString(19, invocation.routingDecision());
                        }
                        ps.setString(20, invocation.policyRef());
                        ps.setString(21, invocation.policyFingerprint());
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
                    // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
                    shadowCompare("agent_invocation", tenantId, true);
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, invocationId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                return Optional.<AgentInvocation>empty();
                            }
                            // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                            String rowTenant = rs.getString("tenant_id");
                            shadowTaint("agent_invocation", tenantId, rowTenant);
                            return Optional.of(map(rs));
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
                    // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
                    shadowCompare("agent_invocation", tenantId, true);
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_BY_RUN)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, runId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                                shadowTaint("agent_invocation", tenantId, rs.getString("tenant_id"));
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
                rs.getString("requested_target_agent_id"),
                AgentInvocationStatus.valueOf(rs.getString("status")),
                rs.getInt("depth"),
                rs.getString("trace_id"),
                rs.getString("input_digest"),
                rs.getString("output_digest"),
                startedAt == null ? null : startedAt.toInstant(),
                finishedAt == null ? null : finishedAt.toInstant(),
                rs.getString("reason_code"),
                rs.getLong("fencing_token"),
                rs.getString("routing_decision"),
                rs.getString("policy_ref"),
                rs.getString("policy_fingerprint"));
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
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
