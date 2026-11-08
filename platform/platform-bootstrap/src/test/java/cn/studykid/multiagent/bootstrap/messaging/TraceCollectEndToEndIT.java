package cn.studykid.multiagent.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    @Autowired
    private DataSource dataSource;

    /** ⚠️ 生产装配的**采集专用发送器**（不是重造的）。 */
    @Autowired
    @Qualifier("traceCollectMessageSender")
    private RabbitMessageSender traceCollectMessageSender;

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
    }

    @AfterEach
    void cleanup() {
        // ⚠️ 回收本用例造的数据：it- 租户下的 Run 必须清理，
        //    否则跨轮累积（与项目 IT 回收纪律一致）。
        if (runId != null && tenantId != null) {
            try (Connection conn = adminPool.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM public.run WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, runId.toString());
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException ignored) {
                // 清理失败不掩盖主断言结果
            }
        }
        if (traceId != null) {
            try (Connection conn = adminPool.getConnection();
                    PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM platform.agent_trace_metric WHERE trace_id = ?")) {
                ps.setString(1, traceId);
                ps.executeUpdate();
            } catch (SQLException ignored) {
                // 同上
            }
        }
    }

    @Test
    @DisplayName("⚠️⚠️ 采集事件经真实 broker → 生产消费者 → 生产采集器 → agent_trace_metric 落一行")
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

        // 前置断言：此刻宽表**还没有**这行（证明后面是「真的写进去」而非「本来就有」）
        assertThat(metricRowCount())
                .as("⚠️ 前置条件：采集前该 trace 不应存在指标行")
                .isZero();

        // 隔离性基线：生产执行队列深度（采集前后必须不变）
        int productionDepthBefore = productionExecuteQueueDepth();

        // ── 2. 用**生产发送器**发布采集事件（与 RunExecutionLoop 同款载荷）──
        OutboxMessage msg = new OutboxMessage(
                runId.toString(), tenantId, 0, 1L, Instant.now());
        traceCollectMessageSender.send("agent.trace.collect", msg);

        // ── 3. 等生产消费者把指标写进表（真实 broker + 真实 PG，有真实延迟）──
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && metricRowCount() == 0) {
            Thread.sleep(200);
        }

        // ── 4. 断言：宽表真的多了一行，且内容正确 ────────────────────────
        assertThat(metricRowCount())
                .as("⚠️⚠️ 采集消息经真实 broker 被生产消费者消费后，"
                        + "agent_trace_metric 必须落一行 —— 为 0 说明"
                        + "「消息发出去了但没人消费 / 消费了但写不进去」，"
                        + "看板就会长期空白（G2 缺口未闭合）")
                .isEqualTo(1);

        assertThat(metricTerminalState())
                .as("⚠️ 指标行必须反映真实终态")
                .isEqualTo("SUCCEEDED");
        assertThat(metricTraceId())
                .as("⚠️ 指标行的 trace_id 必须与 Run 的 trace_id 一致")
                .isEqualTo(traceId);
        assertThat(metricTenantId())
                .as("⚠️ 指标行的租户必须来自消息体（权威来源），不得串租户")
                .isEqualTo(tenantId);

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
