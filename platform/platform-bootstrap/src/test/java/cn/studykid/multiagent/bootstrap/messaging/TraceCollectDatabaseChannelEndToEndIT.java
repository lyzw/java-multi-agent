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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.bootstrap.PlatformApplication;
import cn.studykid.multiagent.infrastructure.messaging.TenantRoutingMessageSender;
import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.messaging.TraceCollectEvent;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;

/**
 * P0 自动采集接线的 <b>DB（PostgreSQL 队列）通道</b>端到端验收。
 *
 * <p><b>为什么必须有这一类</b>：{@code agent.messaging.mode} 的<b>默认值是
 * {@code database}</b>（以 PG 表为队列，不依赖外部 broker），
 * 而 Rabbit 通道的采集消费端（{@code @RabbitListener}）在 DB 模式下<b>不会启动</b>
 * （监听容器的 autoStartup 与「Rabbit 是否活动」求与）。
 * <p>只做 Rabbit 侧的验收，会得到一个<b>「验收通过但生产不生效」</b>的结论：
 * 采集事件发到 Rabbit 却无人消费 → 宽表永远空白，
 * 而 API、Run、看板全部「看起来正常」。
 * <p>本类证明的是<b>默认部署形态</b>下这条链路真的闭合。
 *
 * <p><b>本类跑的是生产 Bean 本身</b>：生产发送器（{@code traceCollectRoutedMessageSender}）
 * → 生产 PG 队列 → 生产 {@code PgQueueListener} → 生产复合 handler
 * → 生产采集器 → 生产 JDBC 仓储 → {@code platform.agent_trace_metric} 落一行。
 *
 * <p>⚠️ <b>租户必须在上下文启动前存在</b>：{@code MessagingChannels.resolve} 在
 * <b>启动期</b>按 {@code agent.worker.tenant-slugs} 解析 slug → tenantId
 * （fail-closed，解析不到即拒绝启动）。故本类在<b>静态初始化块</b>里先建好
 * {@code it-trace-collect} 租户 —— 静态块先于 Spring 上下文创建。
 *
 * <p>⚠️ <b>不需要真实 broker</b>：本类全程走 PG 队列，
 * Rabbit 监听容器在 DB 模式下本就不启动。
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
        // ⚠️ 本类的核心：走 **database 通道**（生产默认）。
        "agent.messaging.mode=database",
        // ⚠️ 采集发送器与采集消费者都定义在 WorkerLoopConfig 里，
        //    该类带 @ConditionalOnProperty(agent.worker.enabled=true)。
        "agent.worker.enabled=true",
        // ⚠️ 中继关闭：本类不轮询共享库的 outbox_event（那会污染其它 IT 的断言）。
        "agent.worker.relay-enabled=false",
        // ⚠️ 本租户是本 IT 专用；启动期按 slug 解析 tenantId，故租户必须已存在。
        "agent.worker.tenant-slugs=it-trace-collect",
        // ⚠️ 轮询间隔调快，让断言在合理时间内完成（生产默认更慢）。
        "agent.messaging.poll-interval-ms=200"
})
@DisplayName("P0 自动采集接线端到端验收（DB/PG 队列通道 = 生产默认形态）")
class TraceCollectDatabaseChannelEndToEndIT {

    private static final String TENANT_SLUG = "it-trace-collect";

    /** ⚠️ 静态：Spring 上下文在测试类实例化前创建，故租户必须先建好。 */
    private static HikariDataSource adminPool;

    @Autowired
    private DataSource dataSource;

    /** ⚠️ 生产装配的**采集专用发送器**（按租户路由，DB 模式下走 PG 队列）。 */
    @Autowired
    @Qualifier("traceCollectRoutedMessageSender")
    private MessageSender traceCollectSender;

    private String tenantId;
    private String snapshotId;
    private UUID runId;
    private String traceId;

    @BeforeAll
    static void setup() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(env("IT_PG_URL", ""));
        cfg.setUsername(env("IT_PG_ADMIN_USER", "agent"));
        cfg.setPassword(env("IT_PG_ADMIN_PASSWORD", ""));
        cfg.setMaximumPoolSize(2);
        cfg.setPoolName("trace-db-it-admin");
        adminPool = new HikariDataSource(cfg);
        // ⚠️ 必须在此建租户：上下文启动期要按 slug 解析 tenantId。
        //    provision 是幂等的（重复调用返回同一租户）。
        try {
            ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        } catch (SQLException e) {
            throw new IllegalStateException("准备 it- 租户失败: " + e.getMessage(), e);
        }
    }

    @AfterEach
    void cleanup() {
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
    @DisplayName("⚠️⚠️ database 通道（生产默认）下：采集事件 → PG 队列 → 消费者 → 指标落库")
    void collectEventProducesMetricRowThroughDatabaseChannel() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        snapshotId = ItTenantProvisioner.snapshotId(adminPool, tenantId);
        runId = UUID.randomUUID();
        traceId = "trace-db-" + runId;
        insertConvergedRun();

        // 前置断言：此刻宽表还没有这行
        assertThat(metricRowCount())
                .as("⚠️ 前置条件：采集前该 trace 不应存在指标行")
                .isZero();

        // ⚠️ 发送侧必须是「按租户路由 + 默认走 PG 队列」的发送器：
        //    若它指向 Rabbit，采集事件会进 Rabbit 队列而 DB 模式下无人消费。
        assertThat(traceCollectSender)
                .as("⚠️ 采集发送器必须是按租户路由的发送器")
                .isInstanceOf(TenantRoutingMessageSender.class);
        TenantRoutingMessageSender routing = (TenantRoutingMessageSender) traceCollectSender;
        assertThat(routing.defaultSender())
                .as("⚠️⚠️ database 模式下采集发送器的默认通道必须是 PG 队列 —— "
                        + "指向 Rabbit 会导致「消息发出但无人消费」，宽表永远空白")
                .isNotInstanceOf(cn.studykid.multiagent.infrastructure.messaging.RabbitMessageSender.class);

        // ── 用生产发送器发布采集事件（与 RunExecutionLoop 同款载荷）──
        OutboxMessage msg = new OutboxMessage(
                runId.toString(), tenantId, 0, 1L, Instant.now());
        traceCollectSender.send(TraceCollectEvent.TYPE, msg);

        // ── 等生产消费者把指标写进表（PG 队列轮询，有真实延迟）──
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && metricRowCount() == 0) {
            Thread.sleep(200);
        }

        assertThat(metricRowCount())
                .as("⚠️⚠️ database 通道（生产默认形态）下，采集事件必须被消费并落一行 —— "
                        + "为 0 说明「入队了但消费者没分流 / 没消费」，看板就会长期空白")
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

    // ── 造数 ──────────────────────────────────────────────────────

    /**
     * 造一个已收敛的 Run（SUCCEEDED）。
     *
     * <p>⚠️ 用 <b>admin（owner）连接</b>写入：应用角色对 {@code public.run} 受 RLS 约束，
     * 且本用例要显式控制终态字段。
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
                ps.setString(4, "it-trace-db-" + runId);
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
