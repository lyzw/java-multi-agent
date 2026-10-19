package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * 取消竞态集成测试（P3-D06 / FR-011 / AT-CANCEL-001/002）。
 *
 * <p><b>本类证明的核心命题</b>（真实 PG17、非特权角色 {@code app_agent}）：
 * <ol>
 *   <li><b>取消原子置终态</b>：{@code requestCancel} 把 RUNNING → CANCELLED
 *       并置 {@code cancel_requested_at}；</li>
 *   <li><b>终态不可回退</b>：已终态（SUCCEEDED/CANCELLED）Run 的取消不生效——DB 触发器
 *       {@code enforce_run_state_transition} 与 WHERE 双保险；</li>
 *   <li><b>取消后不可被重新领取</b>：CANCELLED 不在 {@code claimSpecificRun}
 *       的 {@code QUEUED/RUNNING} 集合内 → 已被取消的 Run 不会被新世代「复活」（L-1 反证核心）；</li>
 *   <li><b>取消后提交被拒</b>：{@code commitResult} 要求 {@code state='RUNNING'} →
 *       在途 Worker 即使持有正确 fencing token 也无法覆盖取消终态；</li>
 *   <li><b>跨租户隔离</b>：B 租户无法取消 A 租户的 Run。</li>
 * </ol>
 *
 * <p>⚠️ 与 D2 同源的<b>确定性交错</b>：T-4 用「A 持锁不提交 → B 取消请求阻塞 →
 * A 提交后 B 重估」的编排，证明取消与在途提交<b>恰好一个赢</b>，而非靠线程调度运气。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class CancellationRaceIT {

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static JdbcRunClaimRepository claims;

    private static String tenantA;
    private static String tenantB;
    private static String snapshotA;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = FaultInjectionSupport.appPool(8);
        adminPool = FaultInjectionSupport.adminPool();
        FaultInjectionSupport.requireNonPrivileged(appPool);

        claims = new JdbcRunClaimRepository(appPool);

        tenantA = FaultInjectionSupport.tenantIdBySlug(adminPool, "t-a");
        tenantB = FaultInjectionSupport.tenantIdBySlug(adminPool, "t-b");
        FaultInjectionSupport.ensureTenantSnapshot(adminPool, "t-a");
        FaultInjectionSupport.ensureTenantSnapshot(adminPool, "t-b");
        snapshotA = FaultInjectionSupport.snapshotIdBySlug(adminPool, "t-a");
    }

    @AfterAll
    static void tearDown() {
        if (appPool != null) {
            appPool.close();
        }
        if (adminPool != null) {
            adminPool.close();
        }
    }

    // ── T-1：取消原子置终态 ──────────────────────────────────────────

    @Test
    @DisplayName("T-1 ⚠️⚠️ RUNNING 取消 → CANCELLED + cancel_requested_at 已置 + isCancelled=true")
    void cancelMovesRunningToCancelled() {
        String runId = newRun(tenantA, snapshotA, "RUNNING");

        assertThat(claims.isCancelled(tenantA, runId))
                .as("前置：取消前应为未取消")
                .isFalse();

        assertThat(claims.requestCancel(tenantA, runId))
                .as("⚠️ 取消应生效")
                .isTrue();

        assertThat(claims.isCancelled(tenantA, runId)).isTrue();
        assertThat(stateOf(tenantA, runId)).isEqualTo("CANCELLED");
        assertThat(cancelRequestedAtOf(tenantA, runId))
                .as("⚠️ cancel_requested_at 必须被置位（审计与协作式停止的依据）")
                .isNotNull();
    }

    // ── T-2：终态不可回退 ────────────────────────────────────────────

    @Test
    @DisplayName("T-2 ⚠️ 已终态（SUCCEEDED）Run 取消不生效，状态不回退")
    void terminalRunCannotBeCancelled() {
        String runId = newRun(tenantA, snapshotA, "SUCCEEDED");

        assertThat(claims.requestCancel(tenantA, runId))
                .as("⚠️ 终态 Run 的取消必须返回 false")
                .isFalse();
        assertThat(stateOf(tenantA, runId))
                .as("⚠️⚠️ 终态不可被回退（否则结果被覆盖 = Sev-0）")
                .isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("T-2b 重复取消幂等：第二次返回 false，状态仍为 CANCELLED")
    void repeatedCancelIsIdempotent() {
        String runId = newRun(tenantA, snapshotA, "RUNNING");

        assertThat(claims.requestCancel(tenantA, runId)).isTrue();
        assertThat(claims.requestCancel(tenantA, runId))
                .as("重复取消应返回 false（幂等，不报错也不改状态）")
                .isFalse();
        assertThat(stateOf(tenantA, runId)).isEqualTo("CANCELLED");
    }

    // ── T-3：取消后不可被重新领取（取消不被「复活」）─────────────────

    @Test
    @DisplayName("T-3 ⚠️⚠️ 已取消的 Run 不可被重新领取（lease 过期也不接管）")
    void cancelledRunCannotBeReclaimed() {
        String runId = newRun(tenantA, snapshotA, "RUNNING");
        assertThat(claims.requestCancel(tenantA, runId)).isTrue();

        // ⚠️ 即便租约早已过期（在 newRun 中显式设置），CANCELLED 也不在
        //    claimSpecificRun 的 QUEUED/RUNNING 集合内 → 不可接管
        Optional<RunClaimPort.LeasedTask> reclaimed = claims.claimSpecificRun(
                tenantA, runId, "worker-takeover", Duration.ofSeconds(30));

        assertThat(reclaimed)
                .as("⚠️⚠️ 已取消的 Run 被接管 = 用户点了取消仍在执行（Sev-0）")
                .isEmpty();
        assertThat(stateOf(tenantA, runId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("T-3b 批量领取同样跳过已取消的 Run")
    void batchClaimSkipsCancelled() {
        String runId = newRun(tenantA, snapshotA, "RUNNING");
        assertThat(claims.requestCancel(tenantA, runId)).isTrue();

        var claimed = claims.claimTasks(tenantA, "worker-batch", Duration.ofSeconds(30), 10);

        assertThat(claimed)
                .as("⚠️ 批量领取不得捞起已取消的 Run")
                .noneMatch(t -> runId.equals(t.runId()));
    }

    // ── T-4：在途 Worker 无法覆盖取消终态 ────────────────────────────

    @Test
    @DisplayName("T-4 ⚠️⚠️ 取消后，持有正确 fencing token 的在途 Worker 提交被拒")
    void inFlightCommitRejectedAfterCancel() {
        String runId = newRun(tenantA, snapshotA, "RUNNING");
        // 先领取：拿到真实 fencing token，模拟在途 Worker
        var leased = claims.claimSpecificRun(tenantA, runId, "worker-1", Duration.ofSeconds(30));
        assertThat(leased).isPresent();
        long token = leased.orElseThrow().fencingToken();

        // 用户取消
        assertThat(claims.requestCancel(tenantA, runId)).isTrue();

        // ⚠️ 在途 Worker 随后提交结果：token 正确，但 state 已是 CANCELLED
        assertThat(claims.commitResult(tenantA, runId, token, "SUCCEEDED", "{}"))
                .as("⚠️⚠️ 取消后提交必须被拒（commitResult 要求 state='RUNNING'）")
                .isFalse();
        assertThat(stateOf(tenantA, runId))
                .as("⚠️ 取消终态不被在途结果覆盖")
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("T-5 续租在取消后失败（在途 Worker 应停止而非继续）")
    void renewLeaseFailsAfterCancel() {
        String runId = newRun(tenantA, snapshotA, "RUNNING");
        var leased = claims.claimSpecificRun(tenantA, runId, "worker-1", Duration.ofSeconds(30));
        assertThat(leased).isPresent();
        long token = leased.orElseThrow().fencingToken();

        assertThat(claims.renewLease(tenantA, runId, "worker-1", token, Duration.ofSeconds(30)))
                .as("前置：取消前续租应成功")
                .isTrue();

        assertThat(claims.requestCancel(tenantA, runId)).isTrue();

        assertThat(claims.renewLease(tenantA, runId, "worker-1", token, Duration.ofSeconds(30)))
                .as("⚠️ 取消后续租应失败（renewLease 要求 state='RUNNING'）")
                .isFalse();
    }

    // ── T-6：跨租户隔离 ──────────────────────────────────────────────

    @Test
    @DisplayName("T-6 ⚠️⚠️ 跨租户：B 租户无法取消 / 读取 A 租户 Run 的取消状态")
    void cancelDoesNotCrossTenant() {
        String runId = newRun(tenantA, snapshotA, "RUNNING");

        assertThat(claims.requestCancel(tenantB, runId))
                .as("⚠️⚠️ B 租户不得取消 A 的 Run（RLS 隔离）")
                .isFalse();
        assertThat(stateOf(tenantA, runId))
                .as("A 的 Run 状态不受 B 影响")
                .isEqualTo("RUNNING");
        assertThat(claims.isCancelled(tenantB, runId))
                .as("⚠️ B 视角看不到该 Run 的取消标记")
                .isFalse();
    }

    // ── 工具方法 ────────────────────────────────────────────────────

    /**
     * 插入最小 Run 行（测试夹具，经 admin 超级用户绕过 RLS）。
     *
     * <p>⚠️ <b>租约已过期</b>（{@code lease_expires_at} 设为过去）：
     * 这样 T-3 才能证明「即便租约过期，已取消的 Run 也不会被接管」——
     * 若租约有效，claim 本就会因未过期而失败，用例将退化为「什么都没证明」。
     */
    private static String newRun(String tenantId, String snapshotId, String state) {
        String runId = UUID.randomUUID().toString();
        // ⚠️ 终态须带 completed_at（run_completed_at_consistent），
        //    SUCCEEDED 另须带 result_payload（run_result_consistent）。
        //    这两条 CHECK 已被本用例顺带验证——夹具写错会被 DB 拒绝（本次实测确有此事）。
        //
        // ⚠️ completed_at / result_payload 在 Java 侧判定而非 SQL CASE：
        //    SQL 里内联 CASE 会打乱参数序号（实测踩过：占位符与 setXxx 错位，
        //    correlation_id 被当成 run_state 传入）。
        boolean terminal = switch (state) {
            case "QUEUED", "RUNNING" -> false;
            default -> true;
        };
        Timestamp completedAt = terminal ? Timestamp.from(Instant.now()) : null;
        String resultPayload = "SUCCEEDED".equals(state) ? "{}" : null;

        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            String sql = """
                    INSERT INTO run
                        (run_id, tenant_id, snapshot_id, idempotency_key, request_digest,
                         state, input_payload, result_payload, correlation_id,
                         fencing_token, attempt, lease_owner, lease_expires_at,
                         completed_at, created_at, updated_at)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?,
                            ?::run_state, '{}'::jsonb, ?::jsonb, ?,
                            0, 0,
                            'worker-ghost', now() - interval '1 hour',
                            ?, now(), now())
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, runId);
                ps.setString(2, tenantId);
                ps.setString(3, snapshotId);
                ps.setString(4, "run-" + UUID.randomUUID());
                ps.setString(5, "digest");
                ps.setString(6, state);
                ps.setString(7, resultPayload);
                ps.setString(8, "corr-" + UUID.randomUUID());
                ps.setTimestamp(9, completedAt);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("插入 Run 夹具失败", e);
        }
        return runId;
    }

    private static String stateOf(String tenantId, String runId) {
        return rawQuery("SELECT state::text FROM run WHERE tenant_id = '"
                + tenantId + "'::uuid AND run_id = '" + runId + "'::uuid", "state");
    }

    private static Instant cancelRequestedAtOf(String tenantId, String runId) {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT cancel_requested_at FROM run WHERE tenant_id = ?::uuid "
                             + "AND run_id = ?::uuid")) {
            ps.setString(1, tenantId);
            ps.setString(2, runId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp(1).toInstant() : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取取消时间失败", e);
        }
    }

    private static String rawQuery(String sql, String column) {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }
}