package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * Worker fencing 终态接管故障注入测试（P3-D02 / FR-006 / AT-LEASE-001/002）。
 *
 * <p>与 {@link RunClaimFencingIT}（串行场景）互补，这里证明的是
 * <b>并发交错与崩溃</b>下防线依然成立：
 * <ul>
 *   <li>FI-LEASE-002 —— 旧世代提交与新世代接管<b>并发</b>交错，恰好一个赢；</li>
 *   <li>FI-LEASE-003 —— 终态 Run（即使租约残留且已过期）不可被接管；</li>
 *   <li>FI-LEASE-004 —— 崩溃后新世代接管（token = 前+1），旧世代复活三路全拒。</li>
 * </ul>
 *
 * <p><b>⚠️ 非特权角色</b>（{@link FaultInjectionSupport#requireNonPrivileged}）：
 * 超级用户绕过 RLS 与触发器会得到虚假结论。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class FencingTakeoverIT {

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static JdbcRunClaimRepository claimRepo;
    private static JdbcRunCreationRepository creationRepo;
    private static String tenantId;
    private static String snapshotId;

    private static final int BATCH = 1000;

    @BeforeAll
    static void setUp() throws Exception {
        // ⚠️ 并发用例需要 ≥2 连接；池扰动（短 maxLifetime）继承 D1 框架
        appPool = FaultInjectionSupport.appPool(4);
        adminPool = FaultInjectionSupport.adminPool();
        FaultInjectionSupport.requireNonPrivileged(appPool);
        claimRepo = new JdbcRunClaimRepository(appPool);
        creationRepo = new JdbcRunCreationRepository(appPool);
        tenantId = FaultInjectionSupport.tenantIdBySlug(adminPool, "t-a");
        FaultInjectionSupport.ensureTenantSnapshot(adminPool, "t-a");
        snapshotId = FaultInjectionSupport.snapshotIdBySlug(adminPool, "t-a");
    }

    @BeforeEach
    void resetPendingRuns() throws Exception {
        inTenant(conn -> {
            try (var ps = conn.prepareStatement(
                    "UPDATE run SET state='QUEUED', lease_owner=NULL, lease_expires_at=NULL "
                  + "WHERE tenant_id=?::uuid AND state IN ('QUEUED','RUNNING')")) {
                ps.setString(1, tenantId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private String createQueuedRun() {
        String runId = UUID.randomUUID().toString();
        creationRepo.insertRunAtomically(tenantId, runId, snapshotId,
                new IdempotencyKey("run.create", "tkv-" + runId),
                RequestDigest.ofText("d-" + runId),
                Map.of("q", "一元二次方程"), "corr-" + runId,
                new OutboxMessage(runId, tenantId, 0, 0L, Instant.now()));
        return runId;
    }

    private RunClaimPort.LeasedTask claimFor(String runId, String workerId) {
        return claimRepo.claimTasks(tenantId, workerId, Duration.ofSeconds(30), BATCH)
                .stream().filter(t -> t.runId().equals(runId)).findFirst()
                .orElseThrow(() -> new AssertionError("任务 " + runId + " 未被领取"));
    }

    private void expireLease(String runId) throws Exception {
        inTenant(conn -> {
            try (var ps = conn.prepareStatement(
                    "UPDATE run SET lease_expires_at = now() - interval '1 minute' "
                  + "WHERE tenant_id=?::uuid AND run_id=?::uuid")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private <T> T inTenant(TenantQuery<T> body) throws Exception {
        return FaultInjectionSupport.inTenant(appPool, tenantId, body::apply);
    }

    @FunctionalInterface
    private interface TenantQuery<T> {
        T apply(java.sql.Connection conn) throws Exception;
    }

    // ── FI-LEASE-002：并发接管竞态 ─────────────────────────────────

    @Test
    @DisplayName("FI-LEASE-002 ⚠️⚠️ 旧世代提交与新世代接管并发交错 → 恰好一个赢")
    void concurrentCommitVsTakeoverExactlyOneWinner() throws Exception {
        String runId = createQueuedRun();
        var stale = claimFor(runId, "w-old");
        expireLease(runId);

        var aWon = new AtomicBoolean(false);
        var bWon = new AtomicBoolean(false);
        var barrier = new CyclicBarrier(2);
        var error = new AtomicReference<Exception>(null);

        Thread a = new Thread(() -> {
            try {
                barrier.await();
                // 旧世代带旧 token 提交
                aWon.set(claimRepo.commitResult(tenantId, runId,
                        stale.fencingToken(), "SUCCEEDED", "{\"content\":\"old\"}"));
            } catch (Exception e) {
                error.set(e);
            }
        });
        Thread b = new Thread(() -> {
            try {
                barrier.await();
                // 新世代尝试接管（RUNNING + 租约过期 → 可接管）
                bWon.set(claimRepo.claimSpecificRun(tenantId, runId, "w-new",
                        Duration.ofSeconds(30)).isPresent());
            } catch (Exception e) {
                error.set(e);
            }
        });
        a.start();
        b.start();
        a.join(10_000);
        b.join(10_000);

        assertThat(error.get()).as("并发路径不应抛异常: %s", error.get()).isNull();
        assertThat(aWon.get() ^ bWon.get())
                .as("⚠️⚠️ 恰好一个赢。双胜 = fencing 失效（Sev-0）；"
                        + "双败 = Run 悬死（消息丢失，Sev-1）。a=%s b=%s", aWon.get(), bWon.get())
                .isTrue();

        // 赢家决定终局：A 赢 → SUCCEEDED；B 赢 → RUNNING 且 token 递增、租约归 B
        String finalState = queryState(runId);
        if (aWon.get()) {
            assertThat(finalState).isEqualTo("SUCCEEDED");
        } else {
            assertThat(finalState).isEqualTo("RUNNING");
            Long token = queryToken(runId);
            assertThat(token)
                    .as("接管世代 token 必须严格递增（不硬编码 ==1）")
                    .isEqualTo(stale.fencingToken() + 1);
        }
    }

    // ── FI-LEASE-003：终态接管拒绝 ─────────────────────────────────

    @Test
    @DisplayName("FI-LEASE-003 ⚠️ 终态 Run（含过期租约残留）不可被接管，且不可迁出终态")
    void terminalRunCannotBeTakenOver() throws Exception {
        for (String terminal : List.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT")) {
            String runId = createQueuedRun();
            // QUEUED → 终态（合法迁移），并留下已过期租约（模拟残留）
            inTenant(conn -> {
                try (var ps = conn.prepareStatement(
                        "UPDATE run SET state=?::run_state, completed_at=now(), "
                      + "result_payload='{\"terminal\":true}'::jsonb, "
                      + "lease_owner='w-ghost', "
                      + "lease_expires_at=now() - interval '1 hour' "
                      + "WHERE tenant_id=?::uuid AND run_id=?::uuid")) {
                    ps.setString(1, terminal);
                    ps.setString(2, tenantId);
                    ps.setString(3, runId);
                    ps.executeUpdate();
                }
                return null;
            });

            var claimed = claimRepo.claimSpecificRun(tenantId, runId, "w-new",
                    Duration.ofSeconds(30));
            assertThat(claimed)
                    .as("⚠️ 终态 %s 即使租约过期也不可接管——接管即复活，结果被覆盖", terminal)
                    .isEmpty();

            // 触发器兜底：直接把终态 Run 改回 QUEUED 必须被 DB 拒绝
            assertThatThrownBy(() -> inTenant(conn -> {
                try (var ps = conn.prepareStatement(
                        "UPDATE run SET state='QUEUED' "
                      + "WHERE tenant_id=?::uuid AND run_id=?::uuid")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, runId);
                    ps.executeUpdate();
                }
                return null;
            }))
                    .as("终态 %s 迁出必须被 enforce_run_state_transition 拒绝", terminal)
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("终态");
        }
    }

    // ── FI-LEASE-004：崩溃接管 + 旧世代复活 ────────────────────────

    @Test
    @DisplayName("FI-LEASE-004 ⚠️⚠️ 崩溃后新世代接管（token=前+1），旧世代复活三路全拒")
    void crashTakeoverThenStaleRevivalRejected() throws Exception {
        String runId = createQueuedRun();
        var old = claimFor(runId, "w-old");

        // 旧世代「崩溃」：不做任何清理，仅租约超时
        expireLease(runId);

        // 新世代接管
        var fresh = claimRepo.claimSpecificRun(tenantId, runId, "w-new",
                Duration.ofSeconds(30));
        assertThat(fresh).as("接管必须成功").isPresent();
        long oldToken = old.fencingToken();
        long newToken = fresh.get().fencingToken();
        assertThat(newToken)
                .as("token 严格 +1（不硬编码起点）")
                .isEqualTo(oldToken + 1);
        assertThat(fresh.get().attempt())
                .as("attempt 与 token 同步递增")
                .isEqualTo(old.attempt() + 1);

        // 旧世代复活，三条路全部被拒 ─────────────────────────────
        // 路 1：带旧 token 提交结果
        assertThat(claimRepo.commitResult(tenantId, runId, oldToken,
                "SUCCEEDED", "{\"content\":\"zombie\"}"))
                .as("旧世代提交必须影响 0 行")
                .isFalse();
        // 路 2：带旧 token 续租
        assertThat(claimRepo.renewLease(tenantId, runId, "w-old",
                oldToken, Duration.ofSeconds(30)))
                .as("旧世代续租必须影响 0 行")
                .isFalse();
        // 路 3：直接 UPDATE 回退 token（绕过仓储）—— 触发器拒绝
        assertThatThrownBy(() -> inTenant(conn -> {
            try (var ps = conn.prepareStatement(
                    "UPDATE run SET fencing_token=? "
                  + "WHERE tenant_id=?::uuid AND run_id=?::uuid")) {
                ps.setLong(1, oldToken);
                ps.setString(2, tenantId);
                ps.setString(3, runId);
                ps.executeUpdate();
            }
            return null;
        }))
                .as("token 回退必须被 enforce_fencing_on_write 拒绝")
                .isInstanceOf(Exception.class)
                .hasMessageContaining("fencing");

        // 终局：数据库里仍是新世代
        assertThat(queryToken(runId)).isEqualTo(newToken);
        assertThat(queryState(runId)).isEqualTo("RUNNING");
    }

    // ── 辅助查询 ────────────────────────────────────────────────────

    private String queryState(String runId) throws Exception {
        return inTenant(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT state::text FROM run WHERE tenant_id=?::uuid AND run_id=?::uuid")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return rs.getString(1);
                }
            }
        });
    }

    private Long queryToken(String runId) throws Exception {
        return inTenant(conn -> {
            try (var ps = conn.prepareStatement(
                    "SELECT fencing_token FROM run WHERE tenant_id=?::uuid AND run_id=?::uuid")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return rs.getLong(1);
                }
            }
        });
    }
}
