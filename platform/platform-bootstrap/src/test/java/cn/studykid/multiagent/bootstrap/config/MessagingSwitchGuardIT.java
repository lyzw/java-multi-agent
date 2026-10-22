package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class MessagingSwitchGuardIT {

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
        tenantA = tenantIdBySlug("t-a");
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

    /** 构造「t-a 为指定模式」的通道分配（走真实解析路径，slug → tenantId）。 */
    private static MessagingChannels channelsWith(String mode) throws SQLException {
        return MessagingChannels.resolve(adminPool, mode, "", List.of("t-a"));
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

    private static String tenantIdBySlug(String slug) throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT tenant_id::text FROM tenant WHERE slug = ?")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("种子数据缺失租户 %s，请先执行 ./scripts/dev-postgres.sh start", slug)
                        .isTrue();
                return rs.getString(1);
            }
        }
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

    private static void cleanQueue() throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM agent_message_queue WHERE tenant_id = ?::uuid")) {
            ps.setString(1, tenantA);
            ps.executeUpdate();
        }
    }
}
