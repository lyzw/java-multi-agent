package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.infrastructure.messaging.PgQueueRepository;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;
import tools.jackson.databind.ObjectMapper;

/**
 * 消息通道切换残留守卫集成测试（P4-D17 · 反证）。
 *
 * <p><b>⚠️ 本类验证的是「切换的代价被强制显性化」</b>：
 * 通道切换若不留神，旧通道里的未消费消息会<b>永远滞留</b>——
 * Run 永不执行、API 正常返回、无报错、无告警。
 * 本项目已多次记录这类「系统表面完全正常」的断链形态，
 * 故守卫必须在<b>启动期</b>把它变成明确失败。
 *
 * <p><b>⚠️ 必须跑在真实 PG 上</b>：守卫读的是队列的真实残留量。
 *
 * <p><b>⚠️⚠️ 必须用专用租户（{@code it-switch-a}），不得用 {@code t-a}</b>：
 * 本类要在「制造残留」与「守卫读残留」之间保持残留<b>不被动过</b>。
 * 而开发机上常驻的平台实例会按 {@code agent.worker.tenant-slugs}（默认 {@code t-a}）
 * 轮询<b>同一个库</b>，在两次断言之间把消息领走（实测 &lt; 1 秒）——
 * 于是守卫读到 0、反证变成「期望抛异常却没抛」，失败现场完全指向错误方向。
 * 同理，{@code passesWhenNoResidual} 要求队列<b>真空</b>，
 * 而运行实例的 Outbox 中继可能随时往真实租户投递新消息。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class MessagingSwitchGuardIT {

    /** IT 专用租户 slug（前缀 {@code it-} 由 {@link #provisionTenant} 硬校验）。 */
    private static final String SLUG = "it-switch-a";

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static PgQueueRepository repository;
    private static String tenantA;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = pool(System.getenv().getOrDefault("IT_PG_USER", "app_agent"),
                System.getenv("IT_PG_PASSWORD"), 4);
        adminPool = pool(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"),
                System.getenv("IT_PG_ADMIN_PASSWORD"), 1);
        repository = new PgQueueRepository(appPool, new ObjectMapper());
        tenantA = provisionTenant(SLUG);
        cleanQueue();
        // 回收上一轮遗留的 Run：本类每例都 createRun，不回收则 it-switch-a 的 Run 无界增长
        // （run 的子表全部 ON DELETE CASCADE；快照不可删，不动）。
        purgeRuns();
    }

    @AfterAll
    static void tearDown() throws Exception {
        cleanQueue();
        if (appPool != null) {
            appPool.close();
        }
        if (adminPool != null) {
            adminPool.close();
        }
    }

    @Test
    @DisplayName("⛔ 反证 S-1：DB 队列有残留时，切到 rabbitmq 的租户必须被拒绝启动")
    void refusesWhenDatabaseHasResidual() throws Exception {
        // 前置：制造「该租户已切到 rabbitmq，但消息还留在 DB 队列」的残留
        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));
        try {
            assertThat(repository.pendingDepth(tenantA))
                    .as("前置条件：DB 队列确有残留").isEqualTo(1);

            // 该租户当前是 rabbitmq 模式
            MessagingChannels channels = channelsWith("rabbitmq");

            assertThatThrownBy(() -> MessagingSwitchGuard.verify(
                    channels, repository, null, null, 5672, "agent.run.execute"))
                    .as("⚠️⚠️ 残留必须导致启动失败（否则这些消息永远滞留、Run 永不执行）")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("残留");
        } finally {
            cleanQueue();
        }
    }

    @Test
    @DisplayName("✅ 反证 S-2：DB 队列无残留时，守卫放行（守卫不得误伤正常启动）")
    void passesWhenNoResidual() throws Exception {
        cleanQueue();
        MessagingChannels channels = channelsWith("rabbitmq");

        // ⚠️ 不抛异常即为放行；守卫在 broker 不可达且无 DB 模式租户时应放行
        MessagingSwitchGuard.verify(channels, repository, null, null, 5672, "agent.run.execute");
    }

    @Test
    @DisplayName("✅ 反证 S-3：纯 DB 部署（无 rabbit 租户）时守卫不因 broker 不可达而拒绝")
    void pureDatabaseDeploymentStartsWithoutBroker() throws Exception {
        cleanQueue();
        // 该租户当前是 database 模式 → 不检查 Rabbit 侧残留
        MessagingChannels channels = channelsWith("database");

        // broker 地址指向一个必然连不上的端口：纯 DB 部署不应因此拒绝启动
        MessagingSwitchGuard.verify(channels, repository, null, "127.0.0.1", 1, "agent.run.execute");
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    /** 构造「本 IT 专用租户为指定模式」的通道分配（走真实解析路径，slug → tenantId）。 */
    private static MessagingChannels channelsWith(String mode) throws SQLException {
        return MessagingChannels.resolve(adminPool, mode, "", List.of(SLUG));
    }

    /**
     * 幂等创建 IT 专用租户（实现见 {@link ItTenantProvisioner}）。
     *
     * <p>守卫只关心「这个租户的队列里有没有残留」，故必须有真实租户；
     * 但<b>不能</b>借用 {@code t-a}——理由见类注释（运行实例会消费它）。
     */
    private static String provisionTenant(String slug) throws SQLException {
        return ItTenantProvisioner.provision(
                adminPool, slug, "IT 专用租户 · 消息通道切换守卫（勿删，幂等创建）");
    }

    private static HikariDataSource pool(String user, String password, int size) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(user);
        if (password != null && !password.isBlank()) {
            cfg.setPassword(password);
        }
        cfg.setMaximumPoolSize(size);
        return new HikariDataSource(cfg);
    }

    private static String createRun(String tenantId) throws SQLException {
        String runId = UUID.randomUUID().toString();
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO run (run_id, tenant_id, snapshot_id, idempotency_key, "
                            + "request_digest, input_payload, correlation_id, state) "
                            + "SELECT ?::uuid, ?::uuid, snapshot_id, ?, ?, '{}'::jsonb, ?, 'QUEUED' "
                            + "FROM execution_snapshot WHERE tenant_id = ?::uuid LIMIT 1")) {
                ps.setObject(1, UUID.fromString(runId));
                ps.setString(2, tenantId);
                ps.setString(3, "guard-" + runId);
                ps.setString(4, "d-" + runId);
                ps.setString(5, "c-" + runId);
                ps.setString(6, tenantId);
                ps.executeUpdate();
            }
            conn.commit();
        }
        return runId;
    }

    private static java.util.Map<String, Object> payload(String runId) {
        return java.util.Map.of(
                "runId", runId,
                "tenantId", tenantA,
                "attempt", 0,
                "fencingToken", 0,
                "enqueuedAt", java.time.Instant.now().toString());
    }

    /** 清空本 IT 专用租户的 Run（只删本租户，绝不碰真实租户）。 */
    private static void purgeRuns() throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM run WHERE tenant_id = ?::uuid")) {
            ps.setString(1, tenantA);
            ps.executeUpdate();
        }
    }

    private static void cleanQueue() throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM agent_message_queue WHERE tenant_id = ?::uuid")) {
            ps.setString(1, tenantA);
            ps.executeUpdate();
        }
    }
}
