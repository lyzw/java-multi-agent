package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort.LeasedTask;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;

/**
 * 请求级 SSE Run 的<b>重复执行防线</b>集成测试（Sev-0 守卫）。
 *
 * <p><b>为什么必须真实数据库</b>：{@code JdbcRunClaimRepository.claimTasks} 的防护是
 * SQL WHERE 里的 {@code lease_owner IS DISTINCT FROM 'agent-chat-sse'}。任何 mock
 * 都测不出这条谓词是否真的生效——只有让 PG 真的执行那条 UPDATE ... RETURNING，
 * 才能证明 {@code RunRecoverySweeper} 每 30s 的扫描捞不到请求级 SSE 占位 Run。
 *
 * <p><b>为什么这是 Sev-0</b>：请求级 SSE 已在一个 HTTP 请求内跑完路由 → RAG →
 * 工具 → 回答并收敛终态。若租约过期后被 Worker 接管重跑，用户会收到<b>两次</b>
 * 回答、工具<b>重复执行</b>（可能有副作用）、审计 fencing 世代被打乱。
 * 与「不写 Outbox」构成两条独立防线：没有唤醒信号，且即使扫表也捞不到。
 *
 * <p><b>反向验证锚点</b>：删掉 {@code claimTasks} 里那行
 * {@code AND lease_owner IS DISTINCT FROM ...}，
 * {@link #expiredSseRunIsNeverClaimedEvenAsSoleCandidate()} 与
 * {@link #sseOwnerExcludedWhileNormalStaleRunsStillClaimed()} 都会立即红。
 *
 * <p>⚠️ 用 {@code app_agent}（NOBYPASSRLS）运行，且只用 {@code it-} 专用租户；
 * 常量 {@link RunClaimPort#SSE_DIRECT_LEASE_OWNER} 从端口读取，测试不重复定义字面量。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class SseDirectRunClaimIT {

    private static final String SLUG = "it-sse-claim-a";
    private static final String OTHER_SLUG = "it-sse-claim-b";
    /** 挤出压力：让 SSE 行的 created_at 明显更早，若过滤消失它必然先被 LIMIT 1 选中。 */
    private static final String SSE_CREATED_AT = "now() - interval '10 minutes'";

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static JdbcRunClaimRepository claimRepo;
    private static JdbcRunCreationRepository creationRepo;
    private static String tenantId;
    private static String otherTenantId;
    private static String snapshotId;
    private static String otherSnapshotId;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = FaultInjectionSupport.appPool(4);
        adminPool = FaultInjectionSupport.adminPool();
        FaultInjectionSupport.requireNonPrivileged(appPool);
        claimRepo = new JdbcRunClaimRepository(appPool);
        creationRepo = new JdbcRunCreationRepository(appPool);

        tenantId = FaultInjectionSupport.itTenant(adminPool, SLUG);
        snapshotId = FaultInjectionSupport.itSnapshot(adminPool, tenantId);
        otherTenantId = FaultInjectionSupport.itTenant(adminPool, OTHER_SLUG);
        otherSnapshotId = FaultInjectionSupport.itSnapshot(adminPool, otherTenantId);
        FaultInjectionSupport.purgeItTenantRuns(adminPool, SLUG);
        FaultInjectionSupport.purgeItTenantRuns(adminPool, OTHER_SLUG);
    }

    @AfterAll
    static void closePools() {
        if (appPool != null) {
            appPool.close();
        }
        if (adminPool != null) {
            adminPool.close();
        }
    }

    @BeforeEach
    void resetRuns() throws Exception {
        FaultInjectionSupport.purgeItTenantRuns(adminPool, SLUG);
        FaultInjectionSupport.purgeItTenantRuns(adminPool, OTHER_SLUG);
    }

    @Test
    @DisplayName("⛔ 过期的 SSE 专属 Run 即使租约已过期也不被任何 Worker 领取")
    void expiredSseRunIsNeverClaimedEvenAsSoleCandidate() throws Exception {
        String sseRun = createQueuedRun(tenantId, snapshotId, "sse-sole");
        markSseOwnedExpired(sseRun);

        List<LeasedTask> claimed = claimRepo.claimTasks(
                tenantId, "recovery-sweeper", Duration.ofSeconds(30), 1000);

        assertThat(claimed).as("请求级 SSE Run 绝不交给 Worker（否则同一轮对话执行两次）")
                .noneMatch(t -> t.runId().equals(sseRun));
        RunRow after = rowOf(tenantId, sseRun);
        assertThat(after.state()).isEqualTo("RUNNING");
        assertThat(after.leaseOwner()).isEqualTo(RunClaimPort.SSE_DIRECT_LEASE_OWNER);
        assertThat(after.fencingToken()).isZero();
        assertThat(after.attempt()).isZero();
    }

    @Test
    @DisplayName("⛔ 挤出压力：SSE 行最早入队也不被 LIMIT 1 抢走，正常过期行照常可被接管")
    void sseOwnerExcludedWhileNormalStaleRunsStillClaimed() throws Exception {
        String sseRun = createQueuedRun(tenantId, snapshotId, "sse-oldest");
        markSseOwnedExpired(sseRun);
        String normalStale = createQueuedRun(tenantId, snapshotId, "normal-stale");
        markNormalStale(normalStale);
        String queued = createQueuedRun(tenantId, snapshotId, "plain-queued");

        // 保证「若过滤消失，SSE 行一定最先被 LIMIT 1 命中」——否则本守卫可能是假绿。
        assertThat(rowOf(tenantId, sseRun).createdAt())
                .as("挤出压力前提：SSE 行必须比正常行更早创建")
                .isBefore(rowOf(tenantId, normalStale).createdAt())
                .isBefore(rowOf(tenantId, queued).createdAt());

        List<LeasedTask> first = claimRepo.claimTasks(
                tenantId, "worker-new", Duration.ofSeconds(30), 1);

        assertThat(first).hasSize(1);
        assertThat(first.get(0).runId())
                .as("LIMIT 1 必须跳过 SSE 行，落到真正可接管的过期 RUNNING 行")
                .isEqualTo(normalStale);
        assertThat(first.get(0).fencingToken())
                .as("接管应递增世代").isEqualTo(1L);

        // 大 LIMIT 再看一次：SSE 行依旧完全不可见（不是「恰好没轮到」）。
        List<LeasedTask> all = claimRepo.claimTasks(
                tenantId, "worker-big", Duration.ofSeconds(30), 1000);
        assertThat(all).noneMatch(t -> t.runId().equals(sseRun))
                .anyMatch(t -> t.runId().equals(queued));

        RunRow sseAfter = rowOf(tenantId, sseRun);
        assertThat(sseAfter.state()).isEqualTo("RUNNING");
        assertThat(sseAfter.leaseOwner()).isEqualTo(RunClaimPort.SSE_DIRECT_LEASE_OWNER);
        assertThat(sseAfter.fencingToken()).isZero();
        assertThat(sseAfter.attempt()).isZero();
    }

    @Test
    @DisplayName("⛔ RLS 下 LIMIT 必须真是上限（旧 IN 子查询被 SKIP LOCKED 放大成领取全部）")
    void claimTasksHonorsLimitUnderForceRls() throws Exception {
        String r1 = createQueuedRun(tenantId, snapshotId, "limit-1");
        String r2 = createQueuedRun(tenantId, snapshotId, "limit-2");
        String r3 = createQueuedRun(tenantId, snapshotId, "limit-3");

        List<LeasedTask> claimed = claimRepo.claimTasks(
                tenantId, "worker-limit", Duration.ofSeconds(30), 1);

        // ⛔ 反向验证锚点：把 MATERIALIZED CTE 改回 UPDATE…WHERE run_id IN
        //    (SELECT … LIMIT ? FOR UPDATE SKIP LOCKED)，本断言立即红（实测领走 3 行）。
        assertThat(claimed).as("FORCE RLS 下 LIMIT 1 必须只领 1 行").hasSize(1);
        String claimedId = claimed.get(0).runId();
        for (String id : List.of(r1, r2, r3)) {
            if (!id.equals(claimedId)) {
                assertThat(rowOf(tenantId, id).state())
                        .as("超出 LIMIT 的行必须仍为 QUEUED，不得被本次越权领取").isEqualTo("QUEUED");
                assertThat(rowOf(tenantId, id).attempt()).isZero();
            }
        }
    }

    @Test
    @DisplayName("⛔ RLS 非特权验证：app_agent 绑定另一租户时看不到本租户的 SSE Run")
    void nonPrivilegedAppCannotSeeSseRunAcrossTenants() throws Exception {
        String sseRun = createQueuedRun(tenantId, snapshotId, "sse-rls");
        markSseOwnedExpired(sseRun);
        // 另建一条 B 租户自己的 QUEUED Run，证明 claimTasks 对 B 是「活的」——
        // 否则「B 看不到 A 的 Run」可能只是查询空转，而不是 RLS 真的生效。
        String otherQueued = createQueuedRun(otherTenantId, otherSnapshotId, "other-queued");

        List<LeasedTask> otherClaims = claimRepo.claimTasks(otherTenantId, "worker-b",
                Duration.ofSeconds(30), 1000);
        assertThat(otherClaims).anyMatch(t -> t.runId().equals(otherQueued))
                .as("B 租户自己的任务必须能被领取（否则本例是空转假绿）")
                .noneMatch(t -> t.runId().equals(sseRun))
                .as("A 租户的 SSE Run 绝不跨租户泄漏");
        // 且直连查询必须因 FORCE RLS 不可见（不是「WHERE 恰好没匹配」）
        long visible = inTenant(appPool, otherTenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM run WHERE run_id = ?::uuid")) {
                ps.setString(1, sseRun);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        assertThat(visible).as("app_agent 必须受 FORCE RLS 约束，否则整个 IT 是假绿").isZero();
        assertThat(rowOf(tenantId, sseRun).leaseOwner())
                .isEqualTo(RunClaimPort.SSE_DIRECT_LEASE_OWNER);
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    private static String createQueuedRun(String tenant, String snapshot, String tag) {
        String runId = UUID.randomUUID().toString();
        creationRepo.insertRunAtomically(tenant, runId, snapshot,
                new IdempotencyKey("run.create", tag + "-" + runId),
                RequestDigest.ofText("d-" + tag + "-" + runId),
                Map.of("q", "claim-guard"), "corr-" + tag + "-" + runId,
                new OutboxMessage(runId, tenant, 0, 0L, Instant.now()));
        return runId;
    }

    /** 模拟请求级 SSE 编排留下的 RUNNING 占位：owner 专用、租约已过期、创建时间最早。 */
    private static void markSseOwnedExpired(String runId) throws Exception {
        inTenant(appPool, tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE run SET state = 'RUNNING', lease_owner = ?, "
                  + "lease_expires_at = now() - interval '1 minute', "
                  + "fencing_token = 0, attempt = 0, created_at = " + SSE_CREATED_AT + " "
                  + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
                ps.setString(1, RunClaimPort.SSE_DIRECT_LEASE_OWNER);
                ps.setString(2, tenantId);
                ps.setString(3, runId);
                assertThat(ps.executeUpdate()).as("SSE 夹具行必须存在").isEqualTo(1);
            }
            return null;
        });
    }

    /** 模拟普通 Worker 崩溃后的过期 RUNNING 行（应可被合法接管）。 */
    private static void markNormalStale(String runId) throws Exception {
        inTenant(appPool, tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE run SET state = 'RUNNING', lease_owner = 'worker-crashed', "
                  + "lease_expires_at = now() - interval '1 minute' "
                  + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                assertThat(ps.executeUpdate()).as("普通过期夹具行必须存在").isEqualTo(1);
            }
            return null;
        });
    }

    private record RunRow(String state, String leaseOwner, long fencingToken,
                          int attempt, Instant createdAt) {
    }

    private static RunRow rowOf(String tenant, String runId) throws Exception {
        return inTenant(appPool, tenant, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT state, lease_owner, fencing_token, attempt, created_at "
                  + "FROM run WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
                ps.setString(1, tenant);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("夹具 Run 必须存在").isTrue();
                    Timestamp created = rs.getTimestamp("created_at");
                    return new RunRow(rs.getString("state"), rs.getString("lease_owner"),
                            rs.getLong("fencing_token"), rs.getInt("attempt"),
                            created.toInstant());
                }
            }
        });
    }

    /** 必须在事务内、任何 SQL 前绑定租户；自动提交会让 set_config(is_local) 立即失效。 */
    private static <T> T inTenant(DataSource pool, String tenantId, TenantQuery<T> body)
            throws Exception {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                TenantSessionBinder.bind(conn,
                        new TenantContext(tenantId, "it", java.util.Set.of(), 0L));
                T result = body.apply(conn);
                conn.commit();
                return result;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @FunctionalInterface
    private interface TenantQuery<T> {
        T apply(Connection conn) throws Exception;
    }
}
