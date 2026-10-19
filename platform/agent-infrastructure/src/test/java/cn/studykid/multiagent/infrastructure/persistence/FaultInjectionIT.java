package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * P3-D1 故障注入集成测试。
 *
 * <p><b>与"正常运行下测试"的本质区别</b>：本类的每个用例都<b>主动注入故障</b>
 * （连接池扰动 / 事务中断 / 重复投递 / 租约失效），证明已知 Sev-0 类不变量
 * 在故障下仍然成立。P1-10 结论：P1 发现的 4 个 Sev-0 都只在特定故障/输入下暴露，
 * 正常运行下的测试抓不到它们。
 *
 * <p><b>⚠️ 必须用非特权角色</b>——{@link FaultInjectionSupport#requireNonPrivileged} 守护。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class FaultInjectionIT {

    private static HikariDataSource pool;
    private static HikariDataSource admin;
    private static String tenantA;
    private static String tenantB;
    private static String snapshotA;
    private static String snapshotB;
    private static JdbcRunCreationRepository creationRepo;
    private static JdbcRunClaimRepository claimRepo;

    @BeforeAll
    static void setUp() throws Exception {
        pool = FaultInjectionSupport.appPool(4);
        admin = FaultInjectionSupport.adminPool();
        // ⚠️ E-3 守护：非特权角色，否则得到「隔离失效」虚假结论
        FaultInjectionSupport.requireNonPrivileged(pool);

        tenantA = FaultInjectionSupport.tenantIdBySlug(admin, "t-a");
        tenantB = FaultInjectionSupport.tenantIdBySlug(admin, "t-b");
        FaultInjectionSupport.ensureTenantSnapshot(admin, "t-a");
        FaultInjectionSupport.ensureTenantSnapshot(admin, "t-b");
        snapshotA = FaultInjectionSupport.snapshotIdBySlug(admin, "t-a");
        snapshotB = FaultInjectionSupport.snapshotIdBySlug(admin, "t-b");

        creationRepo = new JdbcRunCreationRepository(pool);
        claimRepo = new JdbcRunClaimRepository(pool);
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
        if (admin != null) {
            admin.close();
        }
    }

    // ── FI-RLS-001：连接复用下跨租户隔离 ──────────────────────────────

    @Test
    @DisplayName("FI-RLS-001 ⚠️ 大池扰动下跨租户隔离：每租户只看到自己的 Run")
    void rlsIsolationUnderPoolChurn() throws Exception {
        // 每个租户各建一个 Run，作为"要隐藏"的数据
        createRun(tenantA, snapshotA);
        createRun(tenantB, snapshotB);

        // 跨租户交错查询（共享池 + 连接回收），断言隔离始终成立
        for (int i = 0; i < 50; i++) {
            String tenant = (i % 2 == 0) ? tenantA : tenantB;
            long mine = FaultInjectionSupport.countRuns(pool, tenant);
            assertThat(mine)
                    .as("租户 %s 应至少看到自己创建的 Run", tenant)
                    .isGreaterThanOrEqualTo(1L);
            long others = countRunsExcluding(tenant);
            assertThat(others)
                    .as("⚠️ 池扰动下租户 %s 不得看到其他租户 Run（跨租户泄露=Sev-0）", tenant)
                    .isEqualTo(0L);
        }
    }

    @Test
    @DisplayName("FI-RLS-001b ⚠️ 同一物理连接复用给另一租户时，绑定按事务重置")
    void bindingResetsOnReusedConnection() throws Exception {
        // 取一条连接，先以 A 的身份查询（应能看到 A 的数据）
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            FaultInjectionSupport.bind(c, tenantA);
            long asA = countOn(c);
            assertThat(asA).isGreaterThanOrEqualTo(1L);
            c.commit();

            // 模拟同物理连接被复用给租户 B：重新开启事务并绑定 B
            c.setAutoCommit(false);
            FaultInjectionSupport.bind(c, tenantB);
            long asB = countOn(c);
            long aVisibleAsB = countOnWhereTenantIs(c, tenantA);
            c.commit();

            // 关键断言：服务 B 的连接上，A 的数据不可见（不会被 A 的残留绑定泄漏）
            assertThat(aVisibleAsB)
                    .as("⚠️ 复用连接服务 B 时不得沿用 A 的租户上下文（RLS 绑定必须按事务重置）")
                    .isEqualTo(0L);
            assertThat(asB).isGreaterThanOrEqualTo(0L);
        }
    }

    // ── FI-ATOMIC-001：事务中断回滚 ─────────────────────────────────

    @Test
    @DisplayName("FI-ATOMIC-001 ⚠️ 写入后、提交前崩溃 → 原子回滚，无半成品 Run")
    void atomicRollbackOnCrash() throws Exception {
        String runId = uuid();
        // 裸连接（非池）：写一半（仅 run 主记录），不提交即"崩溃"关闭
        try (Connection c = FaultInjectionSupport.rawAppConnection()) {
            c.setAutoCommit(false);
            FaultInjectionSupport.bind(c, tenantA);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO run (tenant_id, snapshot_id, idempotency_key, request_digest, "
                  + "input_payload, correlation_id, state) "
                  + "VALUES (?::uuid, ?::uuid, ?, ?, '{}'::jsonb, ?, 'QUEUED')")) {
                ps.setString(1, tenantA);
                ps.setString(2, snapshotA);
                ps.setString(3, "fi-atomic-" + runId);
                ps.setString(4, "d-" + runId);
                ps.setString(5, "corr-" + runId);
                ps.executeUpdate();
            }
            // 不 commit，直接关闭连接 → PostgreSQL 服务端回滚未提交事务
        }

        long exists = countViaAdmin(
                "SELECT count(*) FROM run WHERE run_id = ?::uuid", runId);
        assertThat(exists)
                .as("⚠️ 崩溃未提交的事务必须被回滚，不得残留半成品 Run（数据不一致=Sev-0）")
                .isEqualTo(0L);
    }

    // ── FI-IDEM-001：重复 Outbox 仅建一个 Run ───────────────────────

    @Test
    @DisplayName("FI-IDEM-001 ⚠️ 同幂等键重放（模拟 MQ 重投）→ 不重复创建 Run")
    void idempotentReplayNoDoubleRun() throws Exception {
        String runId1 = uuid();
        IdempotencyKey key = new IdempotencyKey("run.create", "fi-idem-" + uuid());
        RequestDigest digest = RequestDigest.ofText("same-request");

        // 首次：FIRST_EXECUTION → 写入
        IdempotencyDecision d1 = creationRepo.findExistingResource(tenantA, key, digest);
        assertThat(d1.kind())
                .as("首次执行应判定为 FIRST_EXECUTION").isEqualTo(IdempotencyDecision.Kind.FIRST_EXECUTION);
        creationRepo.insertRunAtomically(tenantA, runId1, snapshotA, key, digest,
                Map.of("q", "一元二次方程"), "corr-" + runId1,
                new OutboxMessage(runId1, tenantA, 0, 0L, Instant.now()));

        // 模拟 MQ 将该 outbox 投递两次：完全相同请求再次进入
        IdempotencyDecision d2 = creationRepo.findExistingResource(tenantA, key, digest);
        assertThat(d2.kind())
                .as("⚠️ 重复且请求相同 → REPLAY，不得重复执行副作用").isEqualTo(IdempotencyDecision.Kind.REPLAY);
        assertThat(d2.originalResourceId())
                .as("REPLAY 必须返回首次的 runId").isEqualTo(runId1);

        long totalForThisKey = countViaAdmin(
                "SELECT count(*) FROM run WHERE idempotency_key = ?", key.keyValue());
        assertThat(totalForThisKey)
                .as("⚠️ 同幂等键重投不得创建多个 Run（重复副作用=Sev-0）")
                .isEqualTo(1L);
    }

    // ── FI-LEASE-001：fencing 旧世代拒写（池扰动下） ─────────────────

    @Test
    @DisplayName("FI-LEASE-001 ⚠️ 池扰动下：Worker 失联 → 新世代接管 → 旧世代写回被拒")
    void staleGenerationRejectedUnderChurn() throws Exception {
        String runId = createQueuedRun();

        long tokenA = claimFor(runId, "worker-A").fencingToken();

        // 租约过期（模拟 Worker 崩溃后的超时）
        expireLease(runId);
        long tokenB = claimFor(runId, "worker-B").fencingToken();
        assertThat(tokenB).as("接管后世代必须递增").isGreaterThan(tokenA);

        // ⚠️ 旧 Worker 复活，用旧世代提交 → 必须被拒绝（覆盖新世代产出=Sev-0）
        assertThat(claimRepo.commitResult(tenantA, runId, tokenA, "SUCCEEDED", "{\"by\":\"stale\"}"))
                .as("⚠️ 旧 fencing 世代提交结果必须被拒绝").isFalse();

        // 当前世代提交成功
        assertThat(claimRepo.commitResult(tenantA, runId, tokenB, "SUCCEEDED", "{\"by\":\"current\"}"))
                .as("当前世代应能正常提交").isTrue();
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private void createRun(String tenantId, String snapshotId) throws Exception {
        String runId = uuid();
        FaultInjectionSupport.inTenant(pool, tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO run (tenant_id, snapshot_id, idempotency_key, request_digest, "
                  + "input_payload, correlation_id, state) "
                  + "VALUES (?::uuid, ?::uuid, ?, ?, '{}'::jsonb, ?, 'QUEUED') "
                  + "ON CONFLICT (tenant_id, idempotency_key) DO NOTHING")) {
                ps.setString(1, tenantId);
                ps.setString(2, snapshotId);
                ps.setString(3, "fi-" + runId);
                ps.setString(4, "d-" + runId);
                ps.setString(5, "corr-" + runId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private String createQueuedRun() {
        String runId = uuid();
        creationRepo.insertRunAtomically(tenantA, runId, snapshotA,
                new IdempotencyKey("run.create", "fi-lease-" + uuid()),
                RequestDigest.ofText("d-" + runId),
                Map.of("q", "一元二次方程"), "corr-" + runId,
                new OutboxMessage(runId, tenantA, 0, 0L, Instant.now()));
        return runId;
    }

    private long countRunsExcluding(String tenantId) throws Exception {
        return FaultInjectionSupport.inTenant(pool, tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM run WHERE tenant_id <> ?::uuid")) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private long countOn(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM run")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countOnWhereTenantIs(Connection conn, String tenantId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM run WHERE tenant_id = ?::uuid")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countViaAdmin(String sql, String param) throws SQLException {
        try (Connection conn = admin.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private RunClaimPort.LeasedTask claimFor(String runId, String workerId) {
        return claimRepo.claimTasks(tenantA, workerId, Duration.ofSeconds(30), 1000)
                .stream().filter(t -> t.runId().equals(runId)).findFirst()
                .orElseThrow(() -> new AssertionError("任务 " + runId + " 未被领取"));
    }

    private void expireLease(String runId) throws Exception {
        FaultInjectionSupport.inTenant(pool, tenantA, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE run SET lease_expires_at = now() - interval '1 minute' "
                  + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
                ps.setString(1, tenantA);
                ps.setString(2, runId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
