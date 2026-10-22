package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * Worker 租约与 fencing 集成测试（P1-06 / 红线 5）。
 *
 * <p><b>验证核心场景</b>：Worker A 租约超时 → Worker B 接管 →
 * A 尝试提交结果<b>必须被拒绝</b>。
 *
 * <p>这正是仅靠租约防不住的场景：A 察觉租约过期前仍可能在执行，
 * 若只校验「租约是否过期」，两边都会认为自己是合法持有者。
 * fencing token 提供<b>强保证</b>：旧世代写入被数据库直接拒绝。
 *
 * <p><b>⚠️ 用非特权角色运行</b>——超级用户绕过 RLS 会导致虚假结论。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class RunClaimFencingIT {

    private static HikariDataSource appPool;
    private static JdbcRunClaimRepository claimRepo;
    private static JdbcRunCreationRepository creationRepo;
    private static String tenantId;
    private static String snapshotId;

    /** 领取批次上限：库中会累积 QUEUED Run，故取大值后按 runId 过滤。 */
    private static final int BATCH = 1000;

    /**
     * 关闭应用连接池（W-PERM-P0 阶段实测修正）。
     *
     * <p>⚠️ <b>必须关闭</b>：本类原先建池不关。全量构建时各 IT 类累积的
     * 连接会打满 PostgreSQL 的 {@code max_connections}（默认 100），
     * 导致<b>后续模块</b>（platform-bootstrap）的 Spring 上下文加载以
     * {@code FATAL: sorry, too many clients already} 失败 ——
     * 失败现场在别的模块，报错点离真因（本类泄漏了连接）极远，
     * 极易被误判成「装配被改坏了」。
     */
    @AfterAll
    static void closeAppPool() {
        if (appPool != null) {
            appPool.close();
            appPool = null;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        String url = System.getenv("IT_PG_URL");
        String user = System.getenv().getOrDefault("IT_PG_USER", "app_agent");
        String pwd = System.getenv("IT_PG_PASSWORD");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        if (pwd != null && !pwd.isBlank()) {
            cfg.setPassword(pwd);
        }
        cfg.setMaximumPoolSize(4);
        appPool = new HikariDataSource(cfg);
        claimRepo = new JdbcRunClaimRepository(appPool);
        creationRepo = new JdbcRunCreationRepository(appPool);

        tenantId = adminQuery("SELECT tenant_id::text FROM tenant WHERE slug='t-a'");
        snapshotId = adminQuery("SELECT snapshot_id::text FROM execution_snapshot LIMIT 1");
    }

    private static String adminQuery(String sql) throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        // ⚠️ 管理员池（造跨租户夹具）：本地 trust 认证无需口令；
        // 密口认证的库（RDS / CI）必须显式给 IT_PG_ADMIN_PASSWORD，
        // 否则连接失败（错误作用域级隔离可能被误读为「失效」）。
        String adminPwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        if (adminPwd != null && !adminPwd.isBlank()) {
            cfg.setPassword(adminPwd);
        }
        cfg.setMaximumPoolSize(1);
        try (HikariDataSource adminPool = new HikariDataSource(cfg);
             Connection conn = adminPool.getConnection();
             var ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            assertThat(rs.next())
                    .as("种子数据缺失，请先执行 ./scripts/dev-postgres.sh start")
                    .isTrue();
            return rs.getString(1);
        }
    }

    /** 在租户上下文内执行。必须关闭自动提交——set_config(is_local) 才有意义。 */
    private static <T> T inTenantScope(TenantQuery<T> body) throws Exception {
        try (Connection conn = appPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                TenantSessionBinder.bind(conn,
                        new TenantContext(tenantId, "it", Set.of(), 0L));
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

    /** 重置所有非终态 Run，避免多轮测试互相干扰。 */
    @BeforeEach
    void resetPendingRuns() throws Exception {
        inTenantScope(conn -> {
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
                new IdempotencyKey("run.create", "claim-" + runId),
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

    /** 强制把租约置为已过期，模拟 Worker 崩溃后的超时场景。 */
    private void expireLease(String runId) throws Exception {
        inTenantScope(conn -> {
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

    // ── 测试 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 领取任务：建立租约并递增 fencing token")
    void claimEstablishesLeaseAndIncrementsToken() throws Exception {
        String runId = createQueuedRun();

        long tokenBefore = currentTokenOf(runId);
        RunClaimPort.LeasedTask task = claimFor(runId, "worker-1");

        assertThat(task.fencingToken())
                .as("领取应使 fencing token 递增一代（token 单调递增，不从 1 重启）")
                .isEqualTo(tokenBefore + 1);
        assertThat(task.attempt()).as("attempt 应同步递增").isEqualTo(1);
    }

    /** 读取当前 token（不在事务内，仅用于比较基准）。 */
    private long currentTokenOf(String runId) throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"));
        // ⚠️ 管理员池（造跨租户夹具）：本地 trust 认证无需口令；
        // 密口认证的库（RDS / CI）必须显式给 IT_PG_ADMIN_PASSWORD，
        // 否则连接失败（错误作用域级隔离可能被误读为「失效」）。
        String adminPwd = System.getenv("IT_PG_ADMIN_PASSWORD");
        if (adminPwd != null && !adminPwd.isBlank()) {
            cfg.setPassword(adminPwd);
        }
        cfg.setMaximumPoolSize(1);
        try (HikariDataSource adminPool = new HikariDataSource(cfg);
             Connection conn = adminPool.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT fencing_token FROM run WHERE run_id=?::uuid")) {
            ps.setString(1, runId);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    @Test
    @DisplayName("⚠️ 已租约任务不会被重复领取（SKIP LOCKED 生效）")
    void leasedTaskNotClaimedTwice() {
        String runId = createQueuedRun();
        claimFor(runId, "worker-1");

        List<RunClaimPort.LeasedTask> second =
                claimRepo.claimTasks(tenantId, "worker-2", Duration.ofSeconds(30), BATCH);

        assertThat(second)
                .as("租约未过期的 RUNNING 任务不应被其他 Worker 领取")
                .noneMatch(t -> t.runId().equals(runId));
    }

    @Test
    @DisplayName("⚠️⚠️ fencing：旧世代提交结果被拒绝（红线 5 核心场景）")
    void staleGenerationCannotCommit() throws Exception {
        String runId = createQueuedRun();

        // Worker A 领取
        long tokenA = claimFor(runId, "worker-A").fencingToken();

        // 租约过期 → B 接管，token 递增
        expireLease(runId);
        long tokenB = claimFor(runId, "worker-B").fencingToken();
        assertThat(tokenB).as("接管后世代必须递增").isGreaterThan(tokenA);

        // 关键：A 用旧世代提交，必须失败
        assertThat(claimRepo.commitResult(
                tenantId, runId, tokenA, "SUCCEEDED", "{\"by\":\"stale-worker\"}"))
                .as("⚠️ 旧 fencing 世代提交结果必须被拒绝——否则会覆盖新世代产出（Sev-0）")
                .isFalse();

        // 新世代提交成功
        assertThat(claimRepo.commitResult(
                tenantId, runId, tokenB, "SUCCEEDED", "{\"by\":\"current-worker\"}"))
                .as("当前世代应能正常提交")
                .isTrue();
    }

    @Test
    @DisplayName("⚠️ 旧世代续租被拒绝")
    void staleGenerationCannotRenew() throws Exception {
        String runId = createQueuedRun();
        long tokenA = claimFor(runId, "worker-A").fencingToken();

        expireLease(runId);
        claimFor(runId, "worker-B");

        assertThat(claimRepo.renewLease(tenantId, runId, "worker-A", tokenA, Duration.ofSeconds(30)))
                .as("已被接管的旧世代不得续租（否则会续上别人的任务）")
                .isFalse();
    }

    @Test
    @DisplayName("✅ 当前世代可正常续租")
    void currentGenerationCanRenew() {
        String runId = createQueuedRun();
        long token = claimFor(runId, "worker-1").fencingToken();

        assertThat(claimRepo.renewLease(tenantId, runId, "worker-1", token, Duration.ofSeconds(30)))
                .isTrue();
    }

    @Test
    @DisplayName("⚠️ 他人不可释放本世代的租约")
    void cannotReleaseOthersLease() {
        String runId = createQueuedRun();
        long token = claimFor(runId, "worker-1").fencingToken();

        assertThat(claimRepo.releaseLease(tenantId, runId, "worker-2", token))
                .as("仅持有者可释放租约").isFalse();
        assertThat(claimRepo.releaseLease(tenantId, runId, "worker-1", token)).isTrue();
    }

    @Test
    @DisplayName("✅ isCurrentGeneration 正确识别世代")
    void generationCheck() {
        String runId = createQueuedRun();
        long token = claimFor(runId, "worker-1").fencingToken();

        assertThat(claimRepo.isCurrentGeneration(tenantId, runId, token))
                .as("领取后的 token 应是当前世代")
                .isTrue();
        assertThat(claimRepo.isCurrentGeneration(tenantId, runId, token - 1))
                .as("更旧的世代不是合法持有者")
                .isFalse();
    }
}