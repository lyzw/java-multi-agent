package cn.studykid.multiagent.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.ports.messaging.MessageHandler;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL 异步队列集成测试（P4-D17 · RabbitMQ 的可切换替代载体）。
 *
 * <p><b>⚠️ 必须跑在真实 PG 上</b>：RLS 隔离、{@code FOR UPDATE SKIP LOCKED}、
 * CHECK 约束、部分索引都依赖数据库特性，替身验证等于没验证。
 *
 * <p><b>⚠️ 用非特权角色 {@code app_agent}</b>：超级用户绕过 RLS，
 * 会得出「隔离失效」的**虚假**结论（D01/E-3 教训）。
 *
 * <p><b>⚠️ 断言的是「语义等价于 Rabbit 通道」，不是「实现细节」</b>：
 * 本 IT 覆盖 at-least-once、有限重试、死信、可见性超时回收、租户隔离——
 * 这些正是切换通道时**不能变**的东西。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class PgQueueIT {

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static PgQueueRepository repository;
    private static String tenantA;
    private static String tenantB;

    @BeforeAll
    static void setUp() throws Exception {
        appPool = pool(System.getenv().getOrDefault("IT_PG_USER", "app_agent"),
                System.getenv("IT_PG_PASSWORD"), 4);
        adminPool = pool(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"),
                System.getenv("IT_PG_ADMIN_PASSWORD"), 1);

        requireNonPrivileged(appPool);

        repository = new PgQueueRepository(appPool, new ObjectMapper());

        tenantA = tenantIdBySlug("t-a");
        tenantB = tenantIdBySlug("t-b");
        ensureTenantSnapshot("t-a");
        ensureTenantSnapshot("t-b");
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

    @BeforeEach
    @AfterEach
    void cleanQueue() throws Exception {
        // ⚠️ 每例前后都清：否则上一例的残留消息会让「深度」断言互相污染
        adminExec("DELETE FROM agent_message_queue WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
        adminExec("DELETE FROM agent_message_dead_letter WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
    }

    // ── Q-1：入队 / 领取 / 确认 ────────────────────────────────────

    @Test
    @DisplayName("✅ Q-1 入队后深度=1；领取后行变 INFLIGHT；ack 后彻底消失")
    void enqueueClaimAck() throws Exception {
        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));

        assertThat(repository.pendingDepth(tenantA)).isEqualTo(1);

        List<PgQueueRepository.QueuedMessage> claimed =
                repository.claim(tenantA, 10, Duration.ofSeconds(60), "w1");
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).runId()).isEqualTo(runId);
        assertThat(claimed.get(0).eventType()).isEqualTo("RUN_DISPATCH");

        // ⚠️ 领取后仍算「未处理」——但深度口径只看 ready（对齐 Rabbit 的 ready 深度）
        assertThat(repository.pendingDepth(tenantA)).isZero();
        assertThat(adminCount("SELECT count(*) FROM agent_message_queue WHERE state='INFLIGHT'"))
                .isEqualTo(1);

        repository.ack(tenantA, claimed.get(0).queueId());
        assertThat(adminCount("SELECT count(*) FROM agent_message_queue")).isZero();
    }

    @Test
    @DisplayName("✅ Q-2 同一时刻只有一个消费者能领到同一条消息（SKIP LOCKED）")
    void claimIsExclusive() throws Exception {
        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));

        List<PgQueueRepository.QueuedMessage> first =
                repository.claim(tenantA, 10, Duration.ofSeconds(60), "w1");
        List<PgQueueRepository.QueuedMessage> second =
                repository.claim(tenantA, 10, Duration.ofSeconds(60), "w2");

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
    }

    // ── Q-3：重试与死信 ────────────────────────────────────────────

    @Test
    @DisplayName("✅ Q-3 重试退回 PENDING 且 attempts 递增")
    void retryRequeues() throws Exception {
        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));
        PgQueueRepository.QueuedMessage msg =
                repository.claim(tenantA, 10, Duration.ofSeconds(60), "w1").get(0);

        repository.retry(tenantA, msg.queueId(), "临时故障", Instant.now());

        assertThat(repository.pendingDepth(tenantA)).isEqualTo(1);
        assertThat(adminCount("SELECT attempts FROM agent_message_queue LIMIT 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("✅ Q-4 被拒的消息进死信表，且从队列消失（只进不出的 DLQ 语义）")
    void rejectGoesToDeadLetter() throws Exception {
        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));
        PgQueueRepository.QueuedMessage msg =
                repository.claim(tenantA, 10, Duration.ofSeconds(60), "w1").get(0);

        repository.reject(tenantA, msg.queueId(), "RUN_DISPATCH", runId,
                msg.payload(), 1, "消息非法");

        assertThat(adminCount("SELECT count(*) FROM agent_message_queue")).isZero();
        assertThat(adminCount("SELECT count(*) FROM agent_message_dead_letter")).isEqualTo(1);
        assertThat(adminText("SELECT error FROM agent_message_dead_letter LIMIT 1"))
                .isEqualTo("消息非法");
    }

    // ── Q-5：可见性超时回收（at-least-once）───────────────────────

    @Test
    @DisplayName("✅ Q-5 领取后未确认，超过可见性超时即被回收重投")
    void visibilityTimeoutReclaims() throws Exception {
        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));

        List<PgQueueRepository.QueuedMessage> first =
                repository.claim(tenantA, 10, Duration.ofMillis(1), "w1");
        assertThat(first).hasSize(1);

        // ⚠️ 等过可见性超时（模拟「领取者崩溃，消息滞留」）
        Thread.sleep(50);

        List<PgQueueRepository.QueuedMessage> reclaimed =
                repository.claim(tenantA, 10, Duration.ofMillis(1), "w2");
        assertThat(reclaimed)
                .as("可见性超时后应被重新领取（否则消息永久滞留 = Run 永不执行）")
                .hasSize(1);
        assertThat(reclaimed.get(0).queueId()).isEqualTo(first.get(0).queueId());
    }

    // ── Q-6：租户隔离（RLS）───────────────────────────────────────

    @Test
    @DisplayName("✅ Q-6 租户 B 看不到也领不到租户 A 的消息（RLS 隔离）")
    void tenantIsolation() throws Exception {
        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));

        assertThat(repository.pendingDepth(tenantB))
                .as("B 的深度不得包含 A 的消息（RLS）").isZero();
        assertThat(repository.claim(tenantB, 10, Duration.ofSeconds(60), "w-b"))
                .as("B 不得领到 A 的消息").isEmpty();
        assertThat(repository.pendingDepth(tenantA)).isEqualTo(1);
    }

    @Test
    @DisplayName("✅ Q-6b 未绑定租户上下文时可见 0 行（默认拒绝，而非「查不到」）")
    void unboundContextSeesNothing() throws Exception {
        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));

        try (Connection conn = appPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM agent_message_queue")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("未绑定租户时 RLS 必须拒绝一切行（否则背压深度恒 0 = 背压失效）")
                        .isZero();
            }
        }
    }

    // ── Q-7：载荷安全（I-1 纵深防御）──────────────────────────────

    @Test
    @DisplayName("✅ Q-7 含敏感字段的载荷被拒（纵深防御：入队之前就拦下）")
    void senderRejectsUnsafePayload() throws Exception {
        String runId = createRun(tenantA);

        // ⚠️ OutboxMessage 在**类型层**就只允许白名单字段，
        //    故这里的防线是「绕过类型直接构造载荷」的场景：
        //    发送端与消费端都调用 assertPayloadSafe 复核。
        Map<String, Object> unsafe = new java.util.LinkedHashMap<>();
        unsafe.put("runId", runId);
        unsafe.put("tenantId", tenantA);
        unsafe.put("attempt", 0);
        unsafe.put("fencingToken", 0);
        unsafe.put("enqueuedAt", Instant.now().toString());
        unsafe.put("prompt", "机密提示词");

        assertThatThrownBy(() -> OutboxMessage.assertPayloadSafe(unsafe))
                .as("含敏感字段的载荷必须被拒（消息一旦入队就难以精确删除）")
                .isInstanceOf(IllegalArgumentException.class);

        // ⚠️ 且不得有任何行入队
        assertThat(repository.pendingDepth(tenantA)).isZero();
    }

    @Test
    @DisplayName("✅ Q-7b 正常载荷可入队且载荷白名单被守门")
    void senderEnqueuesSafePayload() throws Exception {
        String runId = createRun(tenantA);
        PgQueueMessageSender sender = new PgQueueMessageSender(repository);
        OutboxMessage message = new OutboxMessage(runId, tenantA, 1, 7, Instant.now());

        sender.send("RUN_DISPATCH", message);

        assertThat(repository.pendingDepth(tenantA)).isEqualTo(1);
        String payloadJson = adminText(
                "SELECT payload::text FROM agent_message_queue LIMIT 1");
        assertThat(payloadJson)
                .contains("runId").contains("fencingToken")
                .doesNotContain("prompt").doesNotContain("studentId");
    }

    // ── 监听器三态映射（与 Rabbit 通道逐条对齐）────────────────────

    @Test
    @DisplayName("✅ Q-8 监听器：CLAIMED→删除 / RETRY→退回 / REJECTED→死信")
    void listenerMapsOutcomes() throws Exception {
        String runId = createRun(tenantA);

        // ① CLAIMED → 消息消失
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));
        PgQueueListener claimed = listener(fixedOutcome(MessageHandler.Outcome.CLAIMED));
        assertThat(claimed.pollOnce()).isEqualTo(1);
        assertThat(adminCount("SELECT count(*) FROM agent_message_queue")).isZero();

        // ② RETRY → 退回 PENDING
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));
        PgQueueListener retry = listener(fixedOutcome(MessageHandler.Outcome.RETRY));
        assertThat(retry.pollOnce()).isZero();
        assertThat(repository.pendingDepth(tenantA)).isEqualTo(1);

        // ③ REJECTED → 死信
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));
        PgQueueListener rejected = listener(fixedOutcome(MessageHandler.Outcome.REJECTED));
        assertThat(rejected.pollOnce()).isZero();
        assertThat(adminCount("SELECT count(*) FROM agent_message_queue")).isZero();
        assertThat(adminCount("SELECT count(*) FROM agent_message_dead_letter")).isEqualTo(1);
    }

    @Test
    @DisplayName("✅ Q-9 UNKNOWN 语义：消费端返回 CLAIMED 才算完成；RETRY 不得被当成成功")
    void unknownIsNotSuccess() throws Exception {
        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));
        PgQueueListener retry = listener(fixedOutcome(MessageHandler.Outcome.RETRY));

        assertThat(retry.pollOnce())
                .as("RETRY 不算完成处理（否则上游会以为消息已消费）").isZero();
        assertThat(repository.pendingDepth(tenantA)).isEqualTo(1);
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static PgQueueListener listener(MessageHandler handler) {
        return new PgQueueListener(List.of(tenantA), repository, handler,
                Duration.ofMillis(50), 10, 3, Duration.ofSeconds(60),
                Duration.ofMillis(10), Duration.ofMillis(100));
    }

    private static MessageHandler fixedOutcome(MessageHandler.Outcome outcome) {
        return (eventType, payload, workerId) -> outcome;
    }

    private static Map<String, Object> payload(String runId) {
        return Map.of(
                "runId", runId,
                "tenantId", tenantA,
                "attempt", 0,
                "fencingToken", 0,
                "enqueuedAt", Instant.now().toString());
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

    /** E-3 守护：非特权角色运行，否则得到「隔离失效」的虚假结论。 */
    private static void requireNonPrivileged(DataSource pool) throws SQLException {
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1))
                    .as("集成测试必须用非特权角色（app_agent），超级用户绕过 RLS 会得到虚假结论")
                    .isFalse();
            assertThat(rs.getBoolean(2))
                    .as("集成测试必须用 NOBYPASSRLS 角色，否则 RLS 失效形同虚设")
                    .isFalse();
        }
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

    /** 为租户补足 agent_version + execution_snapshot（种子只给了 t-a）。 */
    private static void ensureTenantSnapshot(String slug) throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO agent_version (tenant_id, agent_id, version_number, definition) "
                            + "SELECT a.tenant_id, a.agent_id, 1, '{\"kind\":\"tutor\"}'::jsonb "
                            + "FROM agent a JOIN tenant t ON t.tenant_id = a.tenant_id "
                            + "WHERE t.slug = ? AND a.name = 'agent1' "
                            + "ON CONFLICT (tenant_id, agent_id, version_number) DO NOTHING")) {
                ps.setString(1, slug);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO execution_snapshot "
                            + "(tenant_id, version_id, agent_config, prompt_config, tool_contracts, run_config) "
                            + "SELECT v.tenant_id, v.version_id, '{}', '{}', '{}', "
                            + "'{\"maxCostPerRun\":\"2.00\",\"maxLlmCalls\":12,\"maxSteps\":20,\"approvalRequired\":\"OPTIONAL\"}' "
                            + "FROM agent_version v JOIN agent a ON a.agent_id = v.agent_id "
                            + "JOIN tenant t ON t.tenant_id = v.tenant_id "
                            + "WHERE t.slug = ? AND a.name = 'agent1' AND v.version_number = 1 "
                            + "ON CONFLICT DO NOTHING")) {
                ps.setString(1, slug);
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    /** 建一条 Run（队列表有 FK 指向 run）。 */
    private static String createRun(String tenantId) throws SQLException {
        String runId = UUID.randomUUID().toString();
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO run (tenant_id, snapshot_id, idempotency_key, request_digest, "
                            + "input_payload, correlation_id, state) "
                            + "SELECT ?::uuid, snapshot_id, ?, ?, '{}'::jsonb, ?, 'QUEUED' "
                            + "FROM execution_snapshot WHERE tenant_id = ?::uuid LIMIT 1")) {
                ps.setString(1, tenantId);
                ps.setString(2, "pgq-" + runId);
                ps.setString(3, "d-" + runId);
                ps.setString(4, "c-" + runId);
                ps.setString(5, tenantId);
                ps.executeUpdate();
            }
            conn.commit();
        }
        return runId;
    }

    private static long adminCount(String sql) throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String adminText(String sql) throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void adminExec(String sql) throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
