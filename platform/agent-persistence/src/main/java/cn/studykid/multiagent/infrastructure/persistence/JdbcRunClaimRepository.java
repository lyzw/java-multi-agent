package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import cn.studykid.multiagent.core.run.RunState;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * {@link RunClaimPort} 的 JDBC 实现。
 *
 * <p><b>关键实现点</b>：
 * <ol>
 *   <li><b>{@code FOR UPDATE SKIP LOCKED}</b> —— 多 Worker 并发领取时互不阻塞；
 *       缺失会导致争抢同一行，高并发下退化为串行。</li>
 *   <li><b>fencing token 递增</b> —— 每次接管 +1，使旧世代失效。</li>
 *   <li><b>UPDATE 条件带 fencing_token</b> —— 旧世代提交影响 0 行，
 *       与数据库触发器形成双保险。</li>
 * </ol>
 *
 * <p><b>P1 影子态（RLS→应用层迁移）处理（改造计划 WP-M1-1）</b>：本仓为<b>写主导</b>
 * （几乎全是 {@code UPDATE … RETURNING} 的领取/续租/释放/提交/取消），不参与 P1 影子染色
 * （12 文 §4.5 / 13 文 §5.2 明确 P1 只观测「返回行给调用方的读路径」）。
 * 唯一的 SELECT（{@code messageTokenOf} 取 token、{@code isCurrentGeneration} /
 * {@code isCancelled} 的存在性布尔检查）均<b>不向调用方返回数据行</b>，无可染色的行级租户归属。
 * ⚠️ 已按写路径样板接入<b>影子比对</b>（{@code compare}，无染色点）：在全部 5 个 GUC 绑定
 * 站点前 1:1 观测「Gate 算出的 ScopeFilter vs 本次绑定租户」；全部 SQL 自带显式
 * {@code tenant_id} 谓词 ⇒ {@code hasTenantPredicate=true}。租户隔离在 P1 仍由 RLS
 * 强制兜底；绑定站点的自管 GUC bind 保留至 M4 退役 {@code run} 策略批次。
 */
public class JdbcRunClaimRepository implements RunClaimPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcRunClaimRepository.class);

    private final DataSource dataSource;

    /**
     * Run 重试硬上限（{@code agent.run.max-attempts}，默认 3）。
     *
     * <p>⚠️ claimTasks 是崩溃恢复的唯一接管入口：此前对 attempt 无任何上限，
     * 失败 Run 每 30 秒被重新领取一次、attempt 无限递增，永不收敛 FAILED。
     * 这里在领取前先把<b>租约已过期且已达上限</b>的 RUNNING 收敛为 FAILED，
     * 同一事务内的后续领取自然看不到它们（state 已变）。
     * 字段初值保证直接 new 出来（单测）时也有确定行为；生产由 Spring 注入覆盖。
     */
    @Value("${agent.run.max-attempts:3}")
    private int maxAttempts = 3;

    /**
     * 审计端口（P3-D07 / R-1）。
     *
     * <p>⚠️ <b>可空，默认空实现</b>：取消是<b>用户主动的安全动作</b>，
     * 「谁取消的」必须可追溯（D6 §5 L-2）。生产装配必须传入真实实现。
     */
    private final AuditPort audit;

    /** 空审计占位（未装配时只记 ERROR 日志，不静默吞掉）。 */
    private static final AuditPort NO_AUDIT =
            new AuditPort() {
                @Override
                public void recordRequired(String tenantId, String actorId, String action,
                        String resourceType, String resourceId,
                        java.util.Map<String, Object> detail) {
                    log.error("⚠️⚠️ 关键审计未装配，取消动作将无痕（tenant={}, run={}）",
                            tenantId, resourceId);
                }

                @Override
                public void recordAccess(String tenantId, String actorId, String action,
                        String resourceType, String resourceId,
                        java.util.Map<String, Object> detail) {
                    // no-op
                }

                @Override
                public java.util.List<
                        AuditPort.AuditView>
                        findByResource(String tenantId, String resourceType,
                                String resourceId) {
                    return java.util.List.of();
                }
            };

    public JdbcRunClaimRepository(@NonNull DataSource dataSource) {
        this(dataSource, NO_AUDIT);
    }

    public JdbcRunClaimRepository(@NonNull DataSource dataSource,
                                  @NonNull AuditPort audit) {
        this.dataSource = dataSource;
        this.audit = audit;
    }

    // ── 影子态接入（P1 影子比对 / 改造计划 WP-M1-1）──────────────────────
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private volatile boolean shadowEnabled;
    private @NonNull String sentinelTenantId = "";

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
    public @NonNull List<LeasedTask> claimTasks(@NonNull String tenantId,
                                                @NonNull String workerId,
                                                @NonNull Duration leaseDuration,
                                                int limit) {
        // SKIP LOCKED：并发 Worker 各自锁住不同的行，无需相互等待
        // 同时捞取 QUEUED（未领取）与 RUNNING 且租约已过期（可接管）的任务
        //
        // ⚠️⚠️ 必须是 MATERIALIZED CTE + UPDATE…FROM，绝不能写回
        //    UPDATE…WHERE run_id IN (SELECT … LIMIT ? FOR UPDATE SKIP LOCKED)。
        //    2026-11-10 在 app_agent(NOBYPASSRLS) + FORCE RLS 下实测：RLS 的 security-barrier
        //    让 IN 子查询退化成「对每一行外层候选重新求值」的 SubPlan —— 第一次求值锁走第 1 行，
        //    第二次 SKIP LOCKED 跳过它改返第 2 行……于是 LIMIT 1 实际领走了全部可领取行
        //    （3 个候选全部 RETURNING，attempt 全变 1）。超发 Worker 配额，且把本不该本次领取的
        //    行一并带走。同样的 SQL 用 superuser 跑则正确返回 1 行 —— 这正是「必须非特权角色测」的原因。
        //    MATERIALIZED 强制候选集只求值一次，LIMIT 才是真上限；并发下 SKIP LOCKED 语义不变。
        // ⚠️ 先收敛「租约过期且 attempt 已达上限」的 RUNNING 为 FAILED（P0 修复）：
        //    同一事务内先 UPDATE，后续领取的 SELECT 自然看不到它们（state 已变 FAILED），
        //    等价于「领取候选排除 attempt >= maxAttempts」，且不会让超限行无限重领。
        //    用行级 fencing 无关的窄更新（state='RUNNING' 围栏即可）：这些行租约已过期，
        //    原持有者若仍活着，其 commitResult 会因 state 非 RUNNING 而失败（幂等收敛）。
        String failOverCapSql = """
                UPDATE run
                SET state = 'FAILED',
                    error_code = 'MAX_ATTEMPTS_EXCEEDED',
                    result_payload = jsonb_build_object('reason', '重试次数已达上限'),
                    completed_at = now(),
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    updated_at = now()
                WHERE tenant_id = ?::uuid
                  AND state = 'RUNNING'
                  AND attempt >= ?
                  AND (lease_expires_at IS NULL OR lease_expires_at < now())
                """;
        String sql = """
                WITH candidates AS MATERIALIZED (
                    SELECT run_id FROM run
                    WHERE tenant_id = ?::uuid
                      -- ⚠️ 请求级 SSE 编排的 RUNNING 占位行（不写 Outbox）绝不交给 Worker：
                      --    否则长 SSE 请求租约一过期，RecoverySweeper 会把它当崩溃任务接管重跑。
                      --    IS DISTINCT FROM 同时放行 lease_owner IS NULL 的既有 QUEUED 行。
                      AND lease_owner IS DISTINCT FROM '%s'
                      AND (state = 'QUEUED'
                           OR (state = 'RUNNING'
                               AND (lease_expires_at IS NULL OR lease_expires_at < now())))
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE run r
                SET state = 'RUNNING',
                    lease_owner = ?,
                    lease_expires_at = now() + (? || ' seconds')::interval,
                    fencing_token = r.fencing_token + 1,
                    attempt = r.attempt + 1,
                    updated_at = now()
                FROM candidates c
                WHERE r.run_id = c.run_id
                RETURNING r.run_id AS run_id, r.fencing_token AS fencing_token,
                          r.lease_expires_at AS lease_expires_at, r.attempt AS attempt,
                          r.deadline_at AS deadline_at
                """.formatted(RunClaimPort.SSE_DIRECT_LEASE_OWNER);
        List<LeasedTask> tasks = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            boolean prev = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                shadowCompare(tenantId);
                TenantSessionBinder.bind(conn, systemContext(tenantId));
                // 先收敛超限的过期 RUNNING（state='RUNNING' + attempt>=上限 双围栏）；
                // 同一事务内 UPDATE 先行，下面的候选 SELECT 自然不再看到这些行。
                try (PreparedStatement ps = conn.prepareStatement(failOverCapSql)) {
                    ps.setString(1, tenantId);
                    ps.setInt(2, maxAttempts);
                    int overCap = ps.executeUpdate();
                    if (overCap > 0) {
                        log.warn("租户 {} 收敛重试超限的过期 RUNNING {} 个 "
                                        + "(error_code=MAX_ATTEMPTS_EXCEEDED, maxAttempts={})",
                                tenantId, overCap, maxAttempts);
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    // ⚠️ 占位符顺序必须与新 SQL 逐字对应：CTE 内 tenant_id → LIMIT，
                    //    外层 UPDATE 的 lease_owner → lease 秒数（顺序与旧版不同，错位会导致
                    //    workerId 写进 tenant_id 比较、limit 写进 lease 间隔，静默失真）。
                    ps.setString(1, tenantId);
                    ps.setInt(2, limit);
                    ps.setString(3, workerId);
                    ps.setInt(4, (int) leaseDuration.toSeconds());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Timestamp exp = rs.getTimestamp("lease_expires_at");
                            tasks.add(new LeasedTask(
                                    rs.getString("run_id"),
                                    rs.getLong("fencing_token"),
                                    exp.toInstant(),
                                    rs.getInt("attempt"),
                                    // ⚠️ deadline_at 可空（NULL = 不限时）：
                                    //    rs.getTimestamp 返回 null 时不能调 toInstant()，
                                    //    那会 NPE 把「不限时」变成「领取失败」。
                                    deadlineOf(rs)));
                        }
                    }
                }
                conn.commit();
                if (!tasks.isEmpty() && log.isDebugEnabled()) {
                    log.debug("Worker={} 领取 {} 个任务", workerId, tasks.size());
                }
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(prev);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("领取任务失败", e);
        }
        return tasks;
    }

    @Override
    public java.util.Optional<LeasedTask> claimSpecificRun(@NonNull String tenantId,
                                                           @NonNull String runId,
                                                           @NonNull String workerId,
                                                           @NonNull Duration leaseDuration) {
        // ⚠️ 与 claimTasks 的区别：不捞「所有待办」，只认消息指定的那一个。
        //    批量领取会拿到消息里没有的任务，导致消息与执行错配。
        // ⚠️ WHERE 含 fencing_token 条件：已被他人领取时影响 0 行 → 返回 empty。
        //    这是「重复投递」的正常形态，调用方应丢弃而非重试。
        String sql = """
                UPDATE run
                SET state = 'RUNNING',
                    lease_owner = ?,
                    lease_expires_at = now() + (? || ' seconds')::interval,
                    fencing_token = fencing_token + 1,
                    attempt = attempt + 1,
                    updated_at = now()
                WHERE tenant_id = ?::uuid AND run_id = ?::uuid
                  AND fencing_token = ?
                  AND (state = 'QUEUED'
                       OR (state = 'RUNNING'
                           AND (lease_expires_at IS NULL OR lease_expires_at < now())))
                RETURNING run_id, fencing_token, lease_expires_at, attempt, deadline_at
                """;
        return execReturning(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workerId);
                ps.setInt(2, (int) leaseDuration.toSeconds());
                ps.setString(3, tenantId);
                ps.setString(4, runId);
                ps.setLong(5, messageTokenOf(conn, tenantId, runId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return java.util.Optional.empty();
                    }
                    Timestamp exp = rs.getTimestamp("lease_expires_at");
                    return java.util.Optional.of(new LeasedTask(
                            rs.getString("run_id"),
                            rs.getLong("fencing_token"),
                            exp.toInstant(),
                            rs.getInt("attempt"),
                            deadlineOf(rs)));
                }
            }
        }, tenantId);
    }

    /**
     * 领取挂起等待审批的 Run（M1 / G2 · RESUME 前置）。
     *
     * <p>⚠️ 与 {@link #claimSpecificRun} 的唯一差异是<b>入口状态集合</b>：
     * 只收 {@code WAITING_APPROVAL}（审批决定已到达的挂起 Run）。
     * CAS 仍按当前 fencing token —— 并发唤醒/重复唤醒只有一个能赢，
     * 输家拿到 empty（正常形态，丢弃不重试）。
     */
    @Override
    public java.util.Optional<LeasedTask> claimParkedRunForResume(@NonNull String tenantId,
                                                                   @NonNull String runId,
                                                                   @NonNull String workerId,
                                                                   @NonNull Duration leaseDuration) {
        String sql = """
                UPDATE run
                SET state = 'RUNNING',
                    lease_owner = ?,
                    lease_expires_at = now() + (? || ' seconds')::interval,
                    fencing_token = fencing_token + 1,
                    attempt = attempt + 1,
                    updated_at = now()
                WHERE tenant_id = ?::uuid AND run_id = ?::uuid
                  AND fencing_token = ?
                  AND state = 'WAITING_APPROVAL'
                RETURNING run_id, fencing_token, lease_expires_at, attempt, deadline_at
                """;
        return execReturning(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, workerId);
                ps.setInt(2, (int) leaseDuration.toSeconds());
                ps.setString(3, tenantId);
                ps.setString(4, runId);
                ps.setLong(5, messageTokenOf(conn, tenantId, runId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return java.util.Optional.empty();
                    }
                    Timestamp exp = rs.getTimestamp("lease_expires_at");
                    return java.util.Optional.of(new LeasedTask(
                            rs.getString("run_id"),
                            rs.getLong("fencing_token"),
                            exp.toInstant(),
                            rs.getInt("attempt"),
                            deadlineOf(rs)));
                }
            }
        }, tenantId);
    }

    /**
     * 读取 Run 的业务截止时间。
     *
     * <p>⚠️ <b>NULL = 不限时</b>：{@code rs.getTimestamp} 返回 {@code null} 时
     * <b>不得</b>调 {@code toInstant()}（NPE），必须原样返回 {@code null}。
     *
     * @param rs 结果集（已定位到当前行）
     * @return 业务截止时刻；无截止时间时为 {@code null}
     */
    private static java.time.Instant deadlineOf(ResultSet rs) throws SQLException {
        Timestamp deadline = rs.getTimestamp("deadline_at");
        return deadline == null ? null : deadline.toInstant();
    }

    /** 读取当前 token（用于乐观校验，避免领取到已被接管的世代）。 */
    private long messageTokenOf(Connection conn, String tenantId, String runId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT fencing_token FROM run WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    /** 在已绑定租户上下文的事务中执行并返回结果。 */
    private <T> T execReturning(ReturningAction<T> action, String tenantId) {
        try (Connection conn = dataSource.getConnection()) {
            boolean prev = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                shadowCompare(tenantId);
                TenantSessionBinder.bind(conn, systemContext(tenantId));
                T result = action.apply(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // 连接即将归还
                }
                throw e instanceof RuntimeException re
                        ? re : new IllegalStateException("领取指定任务失败", e);
            } finally {
                try {
                    conn.setAutoCommit(prev);
                } catch (SQLException ignored) {
                    // 连接即将归还
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("领取指定任务失败", e);
        }
    }

    @FunctionalInterface
    private interface ReturningAction<T> {
        T apply(Connection conn) throws SQLException;
    }

    @Override
    public boolean renewLease(@NonNull String tenantId, @NonNull String runId,
                              @NonNull String workerId, long expectedFencingToken,
                              @NonNull Duration leaseDuration) {
        // ⚠️ fencing_token 条件是核心：旧世代续租必然影响 0 行
        String sql = """
                UPDATE run SET lease_expires_at = now() + (? || ' seconds')::interval,
                               updated_at = now()
                WHERE tenant_id = ?::uuid AND run_id = ?::uuid
                  AND state = 'RUNNING'
                  AND lease_owner = ?
                  AND fencing_token = ?
                """;
        return execUpdate(sql,
                ps -> {
                    ps.setInt(1, (int) leaseDuration.toSeconds());
                    ps.setString(2, tenantId);
                    ps.setString(3, runId);
                    ps.setString(4, workerId);
                    ps.setLong(5, expectedFencingToken);
                },
                tenantId) > 0;
    }

    @Override
    public boolean releaseLease(@NonNull String tenantId, @NonNull String runId,
                                @NonNull String workerId, long expectedFencingToken) {
        String sql = """
                UPDATE run SET lease_owner = NULL, lease_expires_at = NULL, updated_at = now()
                WHERE tenant_id = ?::uuid AND run_id = ?::uuid
                  AND lease_owner = ? AND fencing_token = ?
                """;
        return execUpdate(sql,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, runId);
                    ps.setString(3, workerId);
                    ps.setLong(4, expectedFencingToken);
                },
                tenantId) > 0;
    }

    @Override
    public boolean commitResult(@NonNull String tenantId, @NonNull String runId,
                                long expectedFencingToken, @NonNull String state,
                                @NonNull String resultPayload) {
        return commitResult(tenantId, runId, expectedFencingToken, state, resultPayload, null);
    }

    @Override
    public boolean commitResult(@NonNull String tenantId, @NonNull String runId,
                                long expectedFencingToken, @NonNull String state,
                                @NonNull String resultPayload, @Nullable String errorCode) {
        // ⚠️ 状态机前置校验（评审 P0-4）：目标状态必须合法且 RUNNING 可达。
        //    此前合法流转全靠 SQL WHERE 偶然挡住；DB 触发器（V34 起为完整迁移表）
        //    是「法律」，这里是 fail-fast 的「礼仪」——把领域异常而不是 SQL 异常抛给调用方。
        //    未知状态串在此直接抛 IAE，不再等到 ?::run_state 转型失败。
        RunState.RUNNING.requireTransitionTo(RunState.valueOf(state));
        // ⚠️ 两道防线：
        //   1) WHERE fencing_token = ? —— 旧世代影响 0 行
        //   2) 数据库触发器 enforce_fencing_on_write —— 拒绝 token 回退
        // ⚠️ error_code（P7-2）：FAILED 时写结构化错误码，供指标做终止原因归因。
        //    setString(i, null) 即把列置 SQL NULL（成功提交无需错误码）。
        String sql = """
                UPDATE run
                SET state = ?::run_state,
                    result_payload = ?::jsonb,
                    error_code = ?,
                    completed_at = now(),
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    updated_at = now()
                WHERE tenant_id = ?::uuid AND run_id = ?::uuid
                  AND state = 'RUNNING'
                  AND fencing_token = ?
                """;
        return execUpdate(sql,
                ps -> {
                    ps.setString(1, state);
                    ps.setString(2, resultPayload);
                    ps.setString(3, errorCode);
                    ps.setString(4, tenantId);
                    ps.setString(5, runId);
                    ps.setLong(6, expectedFencingToken);
                },
                tenantId) > 0;
    }

    @Override
    public boolean isCurrentGeneration(@NonNull String tenantId, @NonNull String runId,
                                       long fencingToken) {
        String sql = """
                SELECT 1 FROM run
                WHERE tenant_id = ?::uuid AND run_id = ?::uuid AND fencing_token = ?
                """;
        // ⚠️ 必须关闭自动提交：set_config(..., is_local=true) 是事务局部语义，
        //    自动提交模式下绑定立即失效，RLS 将看不到任何行 → 恒返回 false。
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                shadowCompare(tenantId);
                TenantSessionBinder.bind(conn, systemContext(tenantId));
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, tenantId);
                    ps.setString(2, runId);
                    ps.setLong(3, fencingToken);
                    try (ResultSet rs = ps.executeQuery()) {
                        boolean current = rs.next();
                        conn.rollback();
                        return current;
                    }
                }
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检查世代失败", e);
        }
    }

    /** 系统身份仅用于绑定 RLS 上下文，不携带任何角色权限。 */
    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }

    // ── 影子辅助（守卫要求：声明与调用分离，5 个绑定站点 1:1 真调用）────

    /**
     * 影子比对：Gate 算出的 ScopeFilter vs 本次绑定租户（只记日志）。
     * 写主导仓无行级染色点，每个 GUC 绑定站点前的比对是本仓唯一的影子观测面；
     * 全部 SQL 自带显式 {@code tenant_id} 谓词 ⇒ {@code hasTenantPredicate=true}。
     */
    void shadowCompare(@NonNull String boundTenantId) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, "run",
                SubjectContext.forTenant(boundTenantId), boundTenantId, true);
    }

    @Override
    public boolean requestCancel(@NonNull String tenantId, @NonNull String runId) {
        // ⚠️ 直接置终态 CANCELLED + 置 cancel_requested_at。
        //    WHERE 仅收 QUEUED/RUNNING —— 已终态的 Run 取消不生效（返回 false）。
        //    触发器 enforce_run_state_transition 已允许 QUEUED/RUNNING → CANCELLED，不冲突。
        String sql = """
                UPDATE run
                SET state = 'CANCELLED'::run_state,
                    cancel_requested_at = now(),
                    completed_at = now(),
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    updated_at = now()
                WHERE tenant_id = ?::uuid AND run_id = ?::uuid
                  AND state IN ('QUEUED', 'RUNNING')
                """;
        boolean cancelled = execUpdate(sql,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, runId);
                },
                tenantId) > 0;
        // ⚠️ 只在真正取消时留痕：返回 false（已是终态）时记一条会污染追溯。
        //    actor 用 "api" —— 取消由用户经 API 发起，具体主体待认证上下文接入后细化
        //    （D6 L-2 的残余：端口层尚无「仅创建者可取消」的二次鉴权）。
        if (cancelled) {
            audit.recordRequired(tenantId, "api",
                    AuditPort.Actions.RUN_CANCEL,
                    "run", runId, java.util.Map.of());
        }
        return cancelled;
    }

    @Override
    public boolean isCancelled(@NonNull String tenantId, @NonNull String runId) {
        String sql = """
                SELECT 1 FROM run
                WHERE tenant_id = ?::uuid AND run_id = ?::uuid
                  AND (state = 'CANCELLED' OR cancel_requested_at IS NOT NULL)
                """;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                shadowCompare(tenantId);
                TenantSessionBinder.bind(conn, systemContext(tenantId));
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, tenantId);
                    ps.setString(2, runId);
                    try (ResultSet rs = ps.executeQuery()) {
                        boolean cancelled = rs.next();
                        conn.rollback();
                        return cancelled;
                    }
                }
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检查取消状态失败", e);
        }
    }

    /**
     * 收敛挂起超时的 Run（M1-4 / G2 · 防挂起泄漏兜底）。
     *
     * <p>⚠️ <b>为什么必须兜底</b>：PARK 后的 Run 处于 {@code WAITING_APPROVAL}，
     * 不占 worker 线程、不续租。若审批决定始终没来，它会<b>永久停在挂起态</b> ——
     * 不报错、不告警、只是再也不动。
     *
     * <p>⚠️ <b>为什么以 {@code updated_at} 为基准</b>：PARK 时会更新该列，
     * 之后无人续租、无人改状态 → 它就是「进入挂起态的时刻」。
     * 用 {@code lease_expires_at} 反而不对：PARK 已把租约释放（置 NULL）。
     *
     * <p>⚠️ <b>收敛而非重试</b>：置 FAILED（{@code APPROVAL_TIMEOUT}）。
     * 工具在 PARK 时<b>尚未执行</b>（PARK 发生在执行前），故无副作用；
     * 重新排队等于让一批无人批准的副作用再排一次队。
     */
    @Override
    public int failStaleParkedRuns(@NonNull String tenantId,
                                    @NonNull Duration maxWaiting) {
        String sql = """
                UPDATE run
                SET state = 'FAILED'::run_state,
                    error_code = 'APPROVAL_TIMEOUT',
                    error_message = '等待人工审批超时，未执行',
                    result_payload = '{"reason":"approval_timeout"}'::jsonb,
                    completed_at = now(),
                    updated_at = now(),
                    lease_owner = NULL,
                    lease_expires_at = NULL
                WHERE tenant_id = ?::uuid
                  AND state = 'WAITING_APPROVAL'
                  AND updated_at < now() - (? || ' seconds')::interval
                """;
        int failed = execUpdate(sql, ps -> {
            ps.setString(1, tenantId);
            ps.setInt(2, (int) maxWaiting.toSeconds());
        }, tenantId);
        if (failed > 0) {
            log.warn("收敛挂起超时的 Run {} 个（等待审批超过 {}s，工具未执行）",
                    failed, maxWaiting.toSeconds());
        }
        return failed;
    }

    private int execUpdate(String sql, StatementBinder binder, String tenantId) {
        try (Connection conn = dataSource.getConnection()) {
            boolean prev = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                shadowCompare(tenantId);
                TenantSessionBinder.bind(conn, systemContext(tenantId));
                int affected;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    binder.bind(ps);
                    affected = ps.executeUpdate();
                }
                conn.commit();
                return affected;
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // 连接即将归还
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(prev);
                } catch (SQLException ignored) {
                    // 连接即将归还
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("执行更新失败", e);
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}