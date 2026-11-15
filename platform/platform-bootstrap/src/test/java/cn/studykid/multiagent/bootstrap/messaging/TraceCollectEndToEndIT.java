package cn.studykid.multiagent.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.bootstrap.PlatformApplication;
import cn.studykid.multiagent.infrastructure.messaging.RabbitMessageSender;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;

/**
 * P0 自动采集接线的<b>真实端到端验收</b>（G2）。
 *
 * <p><b>本类要证明的唯一一件事</b>：
 * <b>采集事件 → 真实 RabbitMQ → 生产消费者 → 生产采集器 → {@code platform.agent_trace_metric} 落一行</b>。
 * 这正是 P0 的验收口径——宽表从「空转」变「实时」。
 *
 * <p><b>⚠️ 为什么必须是 IT 而不是单测</b>：
 * 单测能证明「调用方发了消息」「消费者调了采集器」，
 * 但<b>证明不了中间那一段</b>——消息是否真的被路由、被消费，
 * 采集器是否真的跨过 RLS 读到事实、指标是否真的写进表。
 * 任何一环断了，单测全绿而看板依旧空白（本项目的 P7 指标体系当初正是如此）。
 *
 * <p><b>⚠️ 本类跑的是**生产 Bean 本身**，不是重造一套</b>：
 * 发布用生产的 {@code traceCollectMessageSender}（同一个 {@link RabbitMessageSender} 实现），
 * 消费用生产的 {@code TraceMetricCollectorListener}，
 * 采集用生产的 {@code AgentMetricCollector} → 生产的 JDBC 仓储。
 * 唯独把 <b>MQ 拓扑名</b>换成 {@code .it} 后缀（见 {@code @TestPropertySource}），
 * 于是整条链路与生产<b>同代码、同配置结构</b>，却<b>不碰生产队列</b>。
 *
 * <p><b>⚠️ 拓扑隔离纪律（与 {@code RealBrokerEndToEndIT} 同源）</b>：
 * 若本类往生产队列 {@code agent.run.execute} 写一条采集消息，
 * 就会被 {@code RunDispatchConsumer} 当成「唤醒执行」→ <b>已终态的 Run 被重跑（Sev-0）</b>。
 * 故用 {@code .it} 后缀把交换机 / 队列 / 死信全部隔开，
 * 并用「生产队列深度前后不变」断言隔离性——<b>隔离靠断言，不靠自觉</b>。
 *
 * <p><b>⚠️ 租户隔离纪律</b>：用 {@code it-} 前缀的专用租户
 * （{@link ItTenantProvisioner}），断言必须带租户过滤——
 * 本机常驻实例会消费/改写共享库数据，不能依赖「库里只有我的数据」。
 *
 * <p><b>⚠️ S2 边界</b>：本类只验「同一输入重投 → 输出确定、无副作用、不二次计费」；
 * <b>不验</b>「压缩态不重复落库」——{@code checkpoint} 表 /
 * {@code ContextCheckpointPort} / compaction 幂等键尚未落地，归 S3-M8。
 *
 * <p><b>⚠️ 需要真实基建</b>：PG（{@code IT_PG_URL}）与 RabbitMQ 都必须可用。
 * 无 PG 时跳过；无 broker 时本类失败——这是 IT 的正确行为
 * （它本就要在真实基建上验证消息路由）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(classes = PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        // ⚠️ 本类要真的**消费**采集消息，故容器必须启动。
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "spring.rabbitmq.username=${RABBITMQ_USER:agent}",
        "spring.rabbitmq.password=${RABBITMQ_PASSWORD:}",
        "spring.rabbitmq.virtual-host=${RABBITMQ_VHOST:agent}",
        // ⚠️ worker 保持开启：采集发送器与采集消费者都定义在 WorkerLoopConfig 里，
        //    该类带 @ConditionalOnProperty(agent.worker.enabled=true)；
        //    worker 关掉时这两个 Bean 根本不存在（= 采集链路整条不存在）。
        "agent.worker.enabled=true",
        // ⚠️ 中继关闭：本类**不**该去轮询共享库的 outbox_event——
        //    那会把别的 IT 造的 outbox 行标记成已发布，污染它们的断言。
        //    本类自己直接发消息，不经 Outbox。
        "agent.worker.relay-enabled=false",
        // ⚠️ 走 RabbitMQ 通道（本类的核心就是 MQ 那一段）。
        "agent.messaging.mode=rabbitmq",
        // ── 拓扑隔离：全部换成 .it 后缀，绝不碰生产队列 ──────────────
        "agent.mq.exchange=agent.run.dispatch.it",
        "agent.mq.dead-letter-exchange=agent.run.dlx.it",
        "agent.mq.queues.execute.name=agent.run.execute.it",
        "agent.mq.queues.execute.dead-letter-exchange=agent.run.dlx.it",
        "agent.mq.queues.execute.dead-letter-routing-key=agent.run.dead.it",
        "agent.mq.queues.dead.name=agent.run.dead.it",
        "agent.mq.queues.trace-collect.name=agent.trace.collect.it",
        "agent.mq.queues.trace-collect.dead-letter-exchange=agent.run.dlx.it",
        "agent.mq.queues.trace-collect.dead-letter-routing-key=agent.trace.collect.dead.it"
})
@DisplayName("P0 自动采集接线真实端到端验收（采集事件 → MQ → 指标落库）")
class TraceCollectEndToEndIT {

    /** ⚠️ 生产执行队列名（本类**不得使用**，只用于隔离性断言）。 */
    private static final String PRODUCTION_EXECUTE_QUEUE = "agent.run.execute";

    private static final String TENANT_SLUG = "it-trace-collect";

    /** 重投后等待「第二次真的被消费且覆盖写」的上限；超时即断言红，不做弱等待。 */
    private static final long REPLAY_TIMEOUT_MS = 15_000L;

    @Autowired
    private DataSource dataSource;

    /** ⚠️ 生产装配的**采集专用发送器**（不是重造的）。 */
    @Autowired
    @Qualifier("traceCollectMessageSender")
    private RabbitMessageSender traceCollectMessageSender;

    /** ⚠️ 生产装配的**采集消费者**：用 ack 计数证明「第二次投递真的被消费」，堵死重投空断言。 */
    @Autowired
    private TraceMetricCollectorListener traceCollectListener;

    /** owner 连接池（造数用）：应用角色对 {@code public.run} 无写权。 */
    private static HikariDataSource adminPool;

    private static AmqpAdmin topologyAdmin;

    private String tenantId;
    private String snapshotId;
    private UUID runId;
    private String traceId;

    @BeforeAll
    static void setup() {
        // ⚠️ 没有 broker 时本类应当**失败**（不是跳过）——
        //    它本就要在真实基建上验证消息路由。
        String host = env("RABBITMQ_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("RABBITMQ_PORT", "5672"));
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 2000);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "本 IT 需要真实 RabbitMQ（" + host + ":" + port + "）：" + e, e);
        }

        // ⚠️ owner 连接池：造数要写 public.run（应用角色受 RLS 且无写权）。
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(env("IT_PG_URL", ""));
        cfg.setUsername(env("IT_PG_ADMIN_USER", "agent"));
        cfg.setPassword(env("IT_PG_ADMIN_PASSWORD", ""));
        cfg.setMaximumPoolSize(2);
        cfg.setPoolName("trace-it-admin");
        adminPool = new HikariDataSource(cfg);

        // ⚠️ 幂等声明本类的 .it 拓扑：显式声明可消除「监听容器先于队列存在」的竞态。
        CachingConnectionFactory cf = new CachingConnectionFactory(host, port);
        cf.setUsername(env("RABBITMQ_USER", "agent"));
        cf.setPassword(env("RABBITMQ_PASSWORD", ""));
        cf.setVirtualHost(env("RABBITMQ_VHOST", "agent"));
        topologyAdmin = new RabbitAdmin(cf);
        TopicExchange exchange = new TopicExchange("agent.run.dispatch.it", true, false);
        TopicExchange dlx = new TopicExchange("agent.run.dlx.it", true, false);
        topologyAdmin.declareExchange(exchange);
        topologyAdmin.declareExchange(dlx);
        Queue traceQueue = QueueBuilder.durable("agent.trace.collect.it")
                .quorum().deliveryLimit(3)
                .deadLetterExchange("agent.run.dlx.it")
                .deadLetterRoutingKey("agent.trace.collect.dead.it")
                .build();
        topologyAdmin.declareQueue(traceQueue);
        topologyAdmin.declareBinding(BindingBuilder.bind(traceQueue)
                .to(exchange).with("agent.trace.collect.it"));
        // ⚠️ 必须清空 .it 队列：否则上一轮遗留消息会让 ack 增量失真，
        //    「第二次投递被消费」的非空转证据就不可信。
        topologyAdmin.purgeQueue("agent.trace.collect.it");
    }

    @AfterEach
    void cleanup() {
        // ⚠️ 回收本用例造的数据：it- 租户下的 Run 必须清理，
        //    否则跨轮累积（与项目 IT 回收纪律一致）。
        // ⚠️ 顺序：平台域子表 → 租户域子表 → run；全部带 tenant_id / trace_id 谓词，
        //    FORCE RLS 下不绑租户的 DELETE 会静默 0 行（假清理）。
        if (traceId != null) {
            execAdminQuiet("DELETE FROM platform.agent_task_outcome WHERE trace_id = ?", traceId);
            execAdminQuiet("DELETE FROM platform.agent_eval_sample WHERE trace_id = ?", traceId);
            execAdminQuiet("DELETE FROM platform.review_task WHERE trace_id = ?", traceId);
            execAdminQuiet("DELETE FROM platform.agent_trace_tool_call WHERE trace_id = ?", traceId);
            execAdminQuiet("DELETE FROM platform.agent_trace_metric WHERE trace_id = ?", traceId);
        }
        if (tenantId != null) {
            execAdminQuiet("DELETE FROM public.agent_message_dead_letter WHERE tenant_id = ?::uuid",
                    tenantId);
            execAdminQuiet("DELETE FROM public.agent_message_queue WHERE tenant_id = ?::uuid",
                    tenantId);
            execAdminQuiet("DELETE FROM public.usage_record WHERE tenant_id = ?::uuid", tenantId);
            execAdminQuiet("DELETE FROM public.run_step WHERE tenant_id = ?::uuid", tenantId);
            execAdminQuiet("DELETE FROM public.run_message WHERE tenant_id = ?::uuid", tenantId);
            execAdminQuiet("DELETE FROM public.budget_reservation WHERE tenant_id = ?::uuid",
                    tenantId);
            execAdminQuiet("DELETE FROM public.tool_operation_attempt WHERE tenant_id = ?::uuid",
                    tenantId);
            execAdminQuiet("DELETE FROM public.tool_operation WHERE tenant_id = ?::uuid", tenantId);
            if (runId != null) {
                execAdminQuiet("DELETE FROM public.run WHERE tenant_id = ?::uuid "
                        + "AND run_id = ?::uuid", tenantId, runId.toString());
            }
        }
    }

    @Test
    @DisplayName("⚠️⚠️ 采集事件经真实 broker → 生产消费者 → 生产采集器 → agent_trace_metric 落一行；同一事件重投不重跑/不翻倍")
    void collectEventProducesMetricRowThroughRealBroker() throws Exception {
        // ── 1. 夹具：it- 租户 + 一个已收敛（SUCCEEDED）的 Run ──────────
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        snapshotId = ItTenantProvisioner.snapshotId(adminPool, tenantId);
        // ⚠️ 跨轮回收：本租户是本 IT 专用，上一轮若因进程崩溃留下 Run，
        //    会按轮次线性累积（挤占 claimTasks 批次窗口、让计数类断言失真）。
        purgeTenantRuns();
        runId = UUID.randomUUID();
        traceId = "trace-it-" + runId;
        insertConvergedRun();
        // ⚠️ 非零计费事实 + 非零步骤事实：否则「不二次计费 / 不重跑」只是在比 0 与 0。
        insertUsage();
        insertRunStep();

        ExecutionFacts baseline = captureExecutionFacts();
        assertThat(metricRowCount())
                .as("⚠️ 前置条件：采集前该 trace 不应存在指标行")
                .isZero();
        assertThat(captureSideEffectFacts())
                .as("⚠️ 前置条件：采集前不应有任何评估/工具副作用")
                .isEqualTo(new SideEffectFacts(0L, 0L, 0L, 0L));

        // 隔离性基线：生产执行队列深度（采集前后必须不变）
        int productionDepthBefore = productionExecuteQueueDepth();
        long acksBefore = traceCollectListener.ackedCount();
        long retriesBefore = traceCollectListener.retriedCount();
        long deadBefore = traceCollectListener.deadLetterCount();

        // ── 2. 用**生产发送器**发布采集事件（与 RunExecutionLoop 同款载荷）──
        OutboxMessage msg = new OutboxMessage(
                runId.toString(), tenantId, 0, 1L, Instant.now());
        traceCollectMessageSender.send("agent.trace.collect", msg);

        // ── 3. 等生产消费者真的 ack + 把指标写进表（真实 broker + 真实 PG）──
        awaitAckCount(acksBefore + 1);
        awaitMetricRowCount(1);
        Instant computedAfterFirst = metricComputedAt();
        assertThat(computedAfterFirst)
                .as("⚠️ 首次采集必须写 computed_at；后续用它证明第二次真的被消费")
                .isNotNull();
        assertThat(captureExecutionFacts())
                .as("⚠️ 首次采集只读事实、不执行 Run：Run 状态/计费/步骤不得变化")
                .isEqualTo(baseline);
        SideEffectFacts sideAfterFirst = captureSideEffectFacts();
        assertThat(sideAfterFirst.reviewTasks() + sideAfterFirst.taskOutcomes())
                .as("⚠️ 首次采集必须产生至少一条评估副作用（L1_GAP 或 TaskOutcome）—— "
                        + "否则「重投不重复副作用」是空转断言")
                .isPositive();

        // ── 4. 同一事件二次投递：必须真的被消费一次，且事实/副作用不重复 ──
        // ⚠️ 等一小会让 now() 必然前进，否则「computed_at 未变」无法区分
        //    「第二次没消费」与「两次落在同一时间精度内」。
        Thread.sleep(100);
        long acksAfterFirst = traceCollectListener.ackedCount();
        traceCollectMessageSender.send("agent.trace.collect", msg);
        awaitAckCount(acksAfterFirst + 1);
        awaitMetricRecomputedAfter(computedAfterFirst);

        assertThat(traceCollectListener.retriedCount())
                .as("⚠️ 重投不得触发 nack 重投循环（幂等覆盖成功即 ack）")
                .isEqualTo(retriesBefore);
        assertThat(traceCollectListener.deadLetterCount())
                .as("⚠️ 重投不得进死信")
                .isEqualTo(deadBefore);
        assertThat(metricRowCount())
                .as("⚠️ 同一 trace 重投不得新增指标行")
                .isEqualTo(1);
        assertThat(metricComputedAt())
                .as("⚠️⚠️ 第二次投递必须真的走到覆盖写 —— "
                        + "时间戳不前进说明消息根本没被消费，行数断言是假绿")
                .isAfter(computedAfterFirst);
        assertThat(captureExecutionFacts())
                .as("⚠️⚠️ 重投不得重跑已终态 Run：状态/fencing/attempt/计费/步骤必须逐项不变")
                .isEqualTo(baseline);
        assertThat(captureSideEffectFacts())
                .as("⚠️⚠️ 重投不得重复派生评估/告警/工具明细副作用")
                .isEqualTo(sideAfterFirst);
        assertThat(metricTerminalState()).isEqualTo("SUCCEEDED");
        assertThat(metricTraceId()).isEqualTo(traceId);
        assertThat(metricTenantId()).isEqualTo(tenantId);

        // ── 5. 隔离性断言：采集消息**不得**进生产执行队列 ────────────────────
        //    这是 P0 最核心的安全性质：采集事件若进 agent.run.execute，
        //    会被 RunDispatchConsumer 当成「唤醒执行」→ 已终态的 Run 被重跑（Sev-0）。
        assertThat(productionExecuteQueueDepth())
                .as("⚠️⚠️ 采集消息绝不能进生产执行队列 —— 进去了就会重跑已终态的 Run（Sev-0）")
                .isEqualTo(productionDepthBefore);
    }

    // ── 断言辅助 ──────────────────────────────────────────────────

    private int metricRowCount() {
        return queryInt("SELECT count(*) FROM platform.agent_trace_metric WHERE trace_id = ?",
                traceId);
    }

    private String metricTerminalState() {
        return queryString("SELECT terminal_state FROM platform.agent_trace_metric "
                + "WHERE trace_id = ?", traceId);
    }

    private String metricTraceId() {
        return queryString("SELECT trace_id FROM platform.agent_trace_metric WHERE trace_id = ?",
                traceId);
    }

    private String metricTenantId() {
        return queryString("SELECT tenant_id::text FROM platform.agent_trace_metric "
                + "WHERE trace_id = ?", traceId);
    }

    /** 生产执行队列深度（隔离性断言用）。 */
    private int productionExecuteQueueDepth() {
        try {
            // ⚠️ getQueueInfo 返回 QueueInformation（不是 Queue）：
            //    Queue 是**声明**用的描述对象，没有消息数；消息数在 QueueInformation 上。
            org.springframework.amqp.core.QueueInformation info =
                    topologyAdmin.getQueueInfo(PRODUCTION_EXECUTE_QUEUE);
            // 生产队列不存在 = 没有消息进去（更安全）
            return info == null ? 0 : (int) info.getMessageCount();
        } catch (Exception e) {
            // 查不到即视为 0：本类只关心「我的消息没进去」
            return 0;
        }
    }

    // ── 重投非空转证据 ──────────────────────────────────────────────

    /**
     * 租户域执行事实：重投不得改变 Run 状态/计费/步骤/消息/预算。
     * ⚠️ 用 owner 连接查询：{@code app_agent} 在 FORCE RLS 下未绑租户会静默读 0 行。
     */
    private record ExecutionFacts(String state, long fencingToken, int attempt,
                                  Instant completedAt, long usageRows, long inputTokens,
                                  long outputTokens, long stepRows, long messageRows,
                                  long reservationRows) {}

    /** 平台域派生副作用：重投不得让评估/告警/工具明细翻倍。 */
    private record SideEffectFacts(long reviewTasks, long taskOutcomes,
                                   long evalSamples, long toolCallRows) {}

    private ExecutionFacts captureExecutionFacts() {
        String sql = """
                SELECT r.state, r.fencing_token, r.attempt, r.completed_at,
                       (SELECT count(*) FROM public.usage_record u
                         WHERE u.tenant_id = r.tenant_id AND u.run_id = r.run_id),
                       (SELECT coalesce(sum(u.input_tokens), 0) FROM public.usage_record u
                         WHERE u.tenant_id = r.tenant_id AND u.run_id = r.run_id),
                       (SELECT coalesce(sum(u.output_tokens), 0) FROM public.usage_record u
                         WHERE u.tenant_id = r.tenant_id AND u.run_id = r.run_id),
                       (SELECT count(*) FROM public.run_step s
                         WHERE s.tenant_id = r.tenant_id AND s.run_id = r.run_id),
                       (SELECT count(*) FROM public.run_message m
                         WHERE m.tenant_id = r.tenant_id AND m.run_id = r.run_id),
                       (SELECT count(*) FROM public.budget_reservation b
                         WHERE b.tenant_id = r.tenant_id AND b.run_id = r.run_id)
                FROM public.run r
                WHERE r.tenant_id = ?::uuid AND r.run_id = ?::uuid
                """;
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("⚠️ Run 夹具必须存在，否则所有事实断言都在比空值")
                        .isTrue();
                Timestamp completed = rs.getTimestamp(4);
                return new ExecutionFacts(
                        rs.getString(1), rs.getLong(2), rs.getInt(3),
                        completed == null ? null : completed.toInstant(),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7),
                        rs.getLong(8), rs.getLong(9), rs.getLong(10));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Run 事实失败", e);
        }
    }

    private SideEffectFacts captureSideEffectFacts() {
        String sql = """
                SELECT
                  (SELECT count(*) FROM platform.review_task WHERE trace_id = ?),
                  (SELECT count(*) FROM platform.agent_task_outcome WHERE trace_id = ?),
                  (SELECT count(*) FROM platform.agent_eval_sample WHERE trace_id = ?),
                  (SELECT count(*) FROM platform.agent_trace_tool_call WHERE trace_id = ?)
                """;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) {
                ps.setString(i, traceId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return new SideEffectFacts(rs.getLong(1), rs.getLong(2),
                        rs.getLong(3), rs.getLong(4));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取采集副作用失败", e);
        }
    }

    private Instant metricComputedAt() {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT computed_at FROM platform.agent_trace_metric WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp(1).toInstant() : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取指标 computed_at 失败", e);
        }
    }

    private void awaitAckCount(long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline
                && traceCollectListener.ackedCount() < expected) {
            Thread.sleep(100);
        }
        assertThat(traceCollectListener.ackedCount())
                .as("⚠️ 生产消费者必须真的 ack 两次 —— 没有 ack 增量时"
                        + "「行数不增」无法区分「幂等成功」与「消息根本没消费」")
                .isEqualTo(expected);
    }

    private void awaitMetricRowCount(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline && metricRowCount() != expected) {
            Thread.sleep(200);
        }
        assertThat(metricRowCount()).isEqualTo(expected);
    }

    private void awaitMetricRecomputedAfter(Instant baseline) throws InterruptedException {
        long deadline = System.currentTimeMillis() + REPLAY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Instant current = metricComputedAt();
            if (current != null && current.isAfter(baseline)) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(metricComputedAt())
                .as("⚠️⚠️ 第二次投递必须真正走到 upsert 覆盖写（computed_at 前移）；"
                        + "否则「行数仍为 1」是「消息没被消费」的假绿")
                .isAfter(baseline);
    }

    /** owner 连接执行清理；失败只记异常，不掩盖主断言。 */
    private void execAdminQuiet(String sql, String... args) {
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException ignored) {
            // 清理失败不掩盖主断言结果
        }
    }

    // ── 造数 ──────────────────────────────────────────────────────

    /**
     * 回收本租户下的全部 Run（仅限本 IT 专用租户）。
     *
     * <p>⚠️ <b>必须带 {@code tenant_id} 谓词</b>：FORCE RLS 下不绑租户的 DELETE
     * 会<b>静默删 0 行</b>（不报错、不生效）——看起来「清理过了」，实际残留照旧。
     *
     * <p>⚠️ 用 <b>admin（owner）连接</b>：应用角色对 {@code public.run} 无删权。
     */
    private void purgeTenantRuns() throws SQLException {
        if (tenantId == null) {
            return;
        }
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM public.run WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    /**
     * 造一个已收敛的 Run（SUCCEEDED）。
     *
     * <p>⚠️ 用 <b>admin（owner）连接</b>写入：应用角色对 {@code public.run} 受 RLS 约束，
     * 且本用例要显式控制终态字段（trace_id / completed_at / result_payload），
     * 走业务端口反而更绕。
     */
    private void insertConvergedRun() throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.run (run_id, tenant_id, snapshot_id, idempotency_key,
                            request_digest, state, fencing_token, attempt, input_payload,
                            result_payload, correlation_id, created_at, updated_at,
                            completed_at, trace_id)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'SUCCEEDED', 1, 0,
                            '{}'::jsonb, '{"content":"ok"}'::jsonb, ?, now(), now(), now(), ?)
                    """)) {
                ps.setString(1, runId.toString());
                ps.setString(2, tenantId);
                ps.setString(3, snapshotId);
                ps.setString(4, "it-trace-" + runId);
                ps.setString(5, "digest-" + runId);
                ps.setString(6, "corr-" + runId);
                ps.setString(7, traceId);
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    /** 非零计费事实：让「重投不二次计费」不是 0 与 0 的弱断言。 */
    private void insertUsage() throws SQLException {
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO public.usage_record (tenant_id, run_id, model_id,
                                input_tokens, output_tokens, cost_amount, currency)
                        VALUES (?::uuid, ?::uuid, 'minimax-chat', 120, 45, 0.012345, 'CNY')
                        """)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId.toString());
            ps.executeUpdate();
        }
    }

    /**
     * 非零执行步骤事实：让「重投不重跑 Run」有可观察的计数基线。
     * ⚠️ 夹具与生产口径一致：state 恒写 'RUNNING'（已发生，非进行中），完成靠 completed_at。
     */
    private void insertRunStep() throws SQLException {
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO public.run_step (tenant_id, run_id, step_number, state,
                                step_type, fencing_token, started_at, completed_at)
                        VALUES (?::uuid, ?::uuid, 1, 'RUNNING'::run_state, 'THINK', 1, now(), now())
                        """)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId.toString());
            ps.executeUpdate();
        }
    }

    private int queryInt(String sql, String arg) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }

    private String queryString(String sql, String arg) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql, e);
        }
    }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
