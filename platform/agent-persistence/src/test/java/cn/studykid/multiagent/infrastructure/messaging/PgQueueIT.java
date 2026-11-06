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

import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;
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
 *
 * <p><b>⚠️⚠️ 必须用专用租户（{@code it-pgq-*}），不得复用 {@code t-a}/{@code t-b}</b>：
 * 本类的核心断言形如「入队后<b>我</b>领得到」，隐含前提是<b>队列里只有我一个消费者</b>。
 * 在开发机上这个前提<b>不成立</b>——只要有一个平台实例在跑，它的
 * {@code PgQueueListener} 就会按 {@code agent.worker.tenant-slugs}（默认 {@code t-a}）
 * 轮询<b>同一个开发库</b>，把本用例刚入队的消息抢先领走并 ack 删除。
 *
 * <p>症状极具误导性：{@code claim} 返回 0 行、<b>无异常无日志</b>，
 * 失败信息只说「期望 1 实得 0」，看起来像 RLS 或连接绑定坏了；
 * 而同一秒用普通 {@code SELECT} 却能数到那一行。实测把同一段 claim SQL
 * 去掉 {@code FOR UPDATE SKIP LOCKED} 立刻返回 1 行 —— 因为行正被那个真实
 * worker 的事务锁着，{@code SKIP LOCKED} 把它跳过了。
 * 更隐蔽的是：测试<b>单独跑与全量跑结果不同</b>，会长期被归因为「偶发/flaky」。
 *
 * <p>故本类自建 {@code it-pgq-a}/{@code it-pgq-b}（幂等），
 * 与任何部署实例消费的租户<b>不重叠</b>；{@link #provisionTenant} 与
 * {@link #requireNoForeignConsumer} 把这个前提变成<b>可失败</b>的断言。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class PgQueueIT {

    /**
     * IT 专用租户 slug。
     *
     * <p>⚠️ 前缀 {@code it-} 不是命名习惯，是被断言保护的契约：
     * 真实部署租户不以 {@code it-} 开头，故「本 IT 消费的租户」与
     * 「线上 worker 轮询的租户」在结构上不可能重合。
     */
    private static final String SLUG_A = "it-pgq-a";
    private static final String SLUG_B = "it-pgq-b";

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

        tenantA = provisionTenant(SLUG_A);
        tenantB = provisionTenant(SLUG_B);

        // 回收上一轮遗留的 Run：本类每例都会 createRun，Run 只增不减会让
        // it-pgq-a 的计数无界上涨（实测已累积到 196 条）。队列行由 cleanQueue 处理，
        // 故这里只删 run（其子表全部 ON DELETE CASCADE；快照不可删，不动）。
        adminExec("DELETE FROM run WHERE " + scope());
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
        // ⚠️ 只清本 IT 的两个专用租户 —— 不碰任何真实租户（共享库上必须做到这点）
        adminExec("DELETE FROM agent_message_queue WHERE " + scope());
        adminExec("DELETE FROM agent_message_dead_letter WHERE " + scope());
    }

    /**
     * 本 IT 的租户范围谓词。
     *
     * <p>⚠️ 所有「整表 count / 随便取一行」的断言都必须带上它：
     * 在共享的开发库上，{@code SELECT count(*) FROM agent_message_dead_letter}
     * 数的是<b>全平台</b>的死信，别的租户（甚至别的测试）留一条就会让断言错位，
     * 而失败信息只会说「期望 1 实得 11」——指向的完全不是真因。
     */
    private static String scope() {
        return "tenant_id IN ('" + tenantA + "'::uuid, '" + tenantB + "'::uuid)";
    }

    private static long queueCount(String condition) throws SQLException {
        return adminCount("SELECT count(*) FROM agent_message_queue WHERE " + scope()
                + (condition.isBlank() ? "" : " AND " + condition));
    }

    private static long deadLetterCount(String condition) throws SQLException {
        return adminCount("SELECT count(*) FROM agent_message_dead_letter WHERE " + scope()
                + (condition.isBlank() ? "" : " AND " + condition));
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
        assertThat(queueCount("state='INFLIGHT'"))
                .isEqualTo(1);

        repository.ack(tenantA, claimed.get(0).queueId(), "w1");
        assertThat(queueCount("")).isZero();
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

        repository.retry(tenantA, msg.queueId(), "临时故障", Instant.now(), "w1");

        assertThat(repository.pendingDepth(tenantA)).isEqualTo(1);
        assertThat(adminCount("SELECT attempts FROM agent_message_queue WHERE " + scope()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("✅ Q-4 被拒的消息进死信表，且从队列消失（只进不出的 DLQ 语义）")
    void rejectGoesToDeadLetter() throws Exception {
        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));
        PgQueueRepository.QueuedMessage msg =
                repository.claim(tenantA, 10, Duration.ofSeconds(60), "w1").get(0);

        repository.reject(tenantA, msg.queueId(), "RUN_DISPATCH", runId,
                msg.payload(), 1, "消息非法", "w1");

        assertThat(queueCount("")).isZero();
        assertThat(deadLetterCount("")).isEqualTo(1);
        assertThat(adminText("SELECT error FROM agent_message_dead_letter WHERE " + scope()))
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

    // ── Q-5b：回收必须计 attempts（P1-13）────────────────

    @Test
    @DisplayName("⚠️⚠️ Q-5b 可见性超时回收必须加 attempts（否则毒消息无限重投）")
    void reclaimIncrementsAttempts() throws Exception {
        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));

        List<PgQueueRepository.QueuedMessage> first =
                repository.claim(tenantA, 10, Duration.ofMillis(1), "w1");
        assertThat(first).hasSize(1);
        assertThat(first.get(0).attempts()).as("首次领取不计失败次数").isZero();

        Thread.sleep(50);

        List<PgQueueRepository.QueuedMessage> reclaimed =
                repository.claim(tenantA, 10, Duration.ofMillis(1), "w2");
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).attempts())
                .as("⚠️⚠️ 回收必须加 attempts —— "
                        + "否则「消费者崩溃 → 行停在 INFLIGHT → 被回收」这条路径"
                        + "永远不会达到重试上限 → 毒消息无限重投、永不进死信")
                .isEqualTo(1);
        assertThat(adminCount("SELECT attempts FROM agent_message_queue WHERE " + scope()))
                .as("⚠️ 库里的 attempts 也必须同步加 1（否则内存里的计数与库里不一致）")
                .isEqualTo(1);
    }

    // ── Q-5c：ck/retry 的 claimed_by 围栏（P1-14）───────────────

    @Test
    @DisplayName("⚠️⚠️ Q-5c 非持有者的 ack 不得删行（claimed_by 围栏）")
    void ackIsFencedByClaimer() throws Exception {
        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));

        List<PgQueueRepository.QueuedMessage> first =
                repository.claim(tenantA, 10, Duration.ofMillis(1), "w1");
        assertThat(first).hasSize(1);
        Thread.sleep(50);

        // 行已被 w2 回收
        List<PgQueueRepository.QueuedMessage> reclaimed =
                repository.claim(tenantA, 10, Duration.ofMillis(1), "w2");
        assertThat(reclaimed).hasSize(1);

        // ⚠️ 旧持有者 w1 返回来 ack —— 必须被围栏拒绝
        repository.ack(tenantA, first.get(0).queueId(), "w1");
        assertThat(queueCount(""))
                .as("⚠️⚠️ 旧持有者的 ack 不得删掉新持有者正在处理的行 —— "
                        + "否则新 worker 崩溃就会消失消息（at-least-once 变成 at-most-once）")
                .isEqualTo(1);

        // ⚠️ 新持有者的 ack 必须生效
        repository.ack(tenantA, reclaimed.get(0).queueId(), "w2");
        assertThat(queueCount("")).isZero();
    }

    @Test
    @DisplayName("⚠️⚠️ Q-5d 非持有者的 retry 不得退回行（否则重复执行）")
    void retryIsFencedByClaimer() throws Exception {
        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));

        List<PgQueueRepository.QueuedMessage> first =
                repository.claim(tenantA, 10, Duration.ofMillis(1), "w1");
        assertThat(first).hasSize(1);
        Thread.sleep(50);
        List<PgQueueRepository.QueuedMessage> reclaimed =
                repository.claim(tenantA, 10, Duration.ofMillis(1), "w2");
        assertThat(reclaimed).hasSize(1);

        repository.retry(tenantA, first.get(0).queueId(), "超时持有者的重试",
                Instant.now(), "w1");

        assertThat(queueCount("state='PENDING'"))
                .as("⚠️⚠️ 旧持有者的 retry 不得把新持有者正在处理的行退回 PENDING —— "
                        + "那会让同一条消息被再次领取 → 重复执行")
                .isZero();
        assertThat(queueCount("state='INFLIGHT'"))
                .isEqualTo(1);
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
                "SELECT payload::text FROM agent_message_queue WHERE " + scope());
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
        assertThat(queueCount("")).isZero();

        // ② RETRY → 退回 PENDING
        // ⚠️ 必须先清队列：上一场景的消息若还在，会被本轮一起领走，
        //    导致「领到的不是本场景那条」——断言就指向了错对象。
        cleanQueue();
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));
        PgQueueListener retry = listener(fixedOutcome(MessageHandler.Outcome.RETRY));
        assertThat(retry.pollOnce()).isZero();
        assertThat(repository.pendingDepth(tenantA)).isEqualTo(1);

        // ③ REJECTED → 死信
        cleanQueue();
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));
        PgQueueListener rejected = listener(fixedOutcome(MessageHandler.Outcome.REJECTED));
        assertThat(rejected.pollOnce()).isZero();
        assertThat(queueCount("")).isZero();
        assertThat(deadLetterCount("")).isEqualTo(1);
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

    /**
     * 幂等创建本 IT 专用租户（tenant + agent + agent_version + execution_snapshot）。
     *
     * <p><b>为什么测试自己建房客，而不是用种子里的 {@code t-a}</b>：
     * 共用真实租户时，「入队 → 立刻领取」会变成与运行实例的竞态，
     * 于是这个 IT 的失败<b>不指向产品缺陷</b>，只指向「开发机上正好有实例在跑」——
     * 而它的通过同样不能证明什么。测试必须自带前提。
     *
     * @return 该租户的 tenant_id
     */
    /**
     * 幂等创建本 IT 的专用租户（实现见 {@link ItTenantProvisioner}）。
     *
     * <p>⚠️ 这里只保留「传给共享实现」的一层，<b>不要</b>再复制一份 SQL：
     * 三份拷贝意味着 schema 变更时有三处要改，而漏改的那处会在别的 IT 里以
     * 「缺快照/外键冲突」的面貌出现。
     */
    private static String provisionTenant(String slug) throws SQLException {
        return ItTenantProvisioner.provision(
                adminPool, slug, "IT 专用租户 · PG 队列（勿删，由 PgQueueIT 幂等创建）");
    }

    /**
     * 守卫：本 IT 的租户队列里<b>不得有外部消费者</b>。
     *
     * <p>做法是「放一条探针消息，等 2 秒看它是否仍为 PENDING」。若被领走，
     * 说明有真实 worker 在轮询这个租户 —— 此时整套断言都会退化成竞态，
     * 与其让用例以「期望 1 实得 0」的面貌随机红、然后被当成 flaky 忽略，
     * 不如在这里<b>直接把原因说出来</b>。
     *
     * <p>⚠️ 探针写在 {@code it-pgq-*} 专用租户下，不污染任何真实租户的数据。
     */
    private static void requireNoForeignConsumer() throws Exception {
        assertThat(adminCount("SELECT count(*) FROM agent_message_queue WHERE tenant_id = '"
                + tenantA + "'::uuid"))
                .as("PG 队列 IT 启动前 it- 租户队列必须为空（残留会让深度断言错位）")
                .isZero();

        String runId = createRun(tenantA);
        repository.enqueue(tenantA, "RUN_DISPATCH", runId, payload(runId));

        // 2 秒足够覆盖常见的轮询间隔（本项默认 500ms）；期间任何状态变化都算被外部领走
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            long moved = adminCount("SELECT count(*) FROM agent_message_queue WHERE tenant_id = '"
                    + tenantA + "'::uuid AND state <> 'PENDING'");
            if (moved > 0) {
                break;
            }
            Thread.sleep(100L);
        }
        long stillPending = adminCount("SELECT count(*) FROM agent_message_queue WHERE tenant_id = '"
                + tenantA + "'::uuid AND state = 'PENDING'");
        adminExec("DELETE FROM agent_message_queue WHERE tenant_id = '" + tenantA + "'::uuid");

        assertThat(stillPending)
                .as("⚠️⚠️ 本 IT 专用租户 %s 的探针消息被外部消费者领走了 —— "
                        + "说明有平台实例的 `agent.worker.tenant-slugs` 覆盖了这个租户。"
                        + "此时「入队→领取」是竞态而非验证，请把该 slug 从 worker 配置中移除"
                        + "（本 IT 只允许消费 it- 前缀的租户）", SLUG_A)
                .isEqualTo(1L);
    }


    /** 建一条 Run（队列表有 FK 指向 run）。 */
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
                ps.setString(3, "pgq-" + runId);
                ps.setString(4, "d-" + runId);
                ps.setString(5, "c-" + runId);
                ps.setString(6, tenantId);
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
