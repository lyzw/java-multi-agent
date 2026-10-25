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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.tenant.TenantContext;
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
 */
public class JdbcRunClaimRepository implements RunClaimPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcRunClaimRepository.class);

    private final DataSource dataSource;

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

    @Override
    public @NonNull List<LeasedTask> claimTasks(@NonNull String tenantId,
                                                @NonNull String workerId,
                                                @NonNull Duration leaseDuration,
                                                int limit) {
        // SKIP LOCKED：并发 Worker 各自锁住不同的行，无需相互等待
        // 同时捞取 QUEUED（未领取）与 RUNNING 且租约已过期（可接管）的任务
        String sql = """
                UPDATE run r
                SET state = 'RUNNING',
                    lease_owner = ?,
                    lease_expires_at = now() + (? || ' seconds')::interval,
                    fencing_token = r.fencing_token + 1,
                    attempt = r.attempt + 1,
                    updated_at = now()
                WHERE r.run_id IN (
                    SELECT run_id FROM run
                    WHERE tenant_id = ?::uuid
                      AND (state = 'QUEUED'
                           OR (state = 'RUNNING'
                               AND (lease_expires_at IS NULL OR lease_expires_at < now())))
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING run_id, fencing_token, lease_expires_at, attempt, deadline_at
                """;
        List<LeasedTask> tasks = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            boolean prev = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                TenantSessionBinder.bind(conn, systemContext(tenantId));
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, workerId);
                    ps.setInt(2, (int) leaseDuration.toSeconds());
                    ps.setString(3, tenantId);
                    ps.setInt(4, limit);
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
        // ⚠️ 两道防线：
        //   1) WHERE fencing_token = ? —— 旧世代影响 0 行
        //   2) 数据库触发器 enforce_fencing_on_write —— 拒绝 token 回退
        String sql = """
                UPDATE run
                SET state = ?::run_state,
                    result_payload = ?::jsonb,
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
                    ps.setString(3, tenantId);
                    ps.setString(4, runId);
                    ps.setLong(5, expectedFencingToken);
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

    private int execUpdate(String sql, StatementBinder binder, String tenantId) {
        try (Connection conn = dataSource.getConnection()) {
            boolean prev = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
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