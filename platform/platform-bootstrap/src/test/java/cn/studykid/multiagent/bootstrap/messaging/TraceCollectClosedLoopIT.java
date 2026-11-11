package cn.studykid.multiagent.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;
import cn.studykid.multiagent.worker.execute.RunExecutionLoop;

/**
 * P0 自动采集接线的<b>完整闭环</b>验收：<b>真实 Run 跑完 → 自动发采集事件 → 宽表落行</b>。
 *
 * <p><b>为什么这一类不能省</b>：另两个 E2E IT 验的是「链路的后半段」——
 * 手工造一条已终态的 Run、手工发一条采集消息，然后断言宽表有行。
 * 它们<b>没有验过</b>：<b>执行循环真的跑完一次 Run 时，会不会自动发出那条消息</b>。
 * <p>而这正是 P0 的核心：G2 缺口是「<b>采集器无自动触发</b>」。
 * 若只验后半段，就会出现「消息通道全对，但没人发消息」的空转 ——
 * 宽表照样空白，看板照样空白，而<b>全部测试全绿</b>。
 * 本类用<b>生产 {@link RunExecutionLoop} Bean</b> 真跑一次 Run，
 * 让「执行 → 终态 → 发事件 → 消费 → 落库」整条链在测试里自行贯通。
 *
 * <p><b>本类不 mock 任何东西</b>：执行循环、采集器、事实仓储、指标仓储、
 * 队列发送器、队列监听器全是生产 Bean。唯一被替换的是<b>模型网关</b>
 * （{@code FakeModelGateway}）——真实模型需要凭据且不稳定，
 * 而本类要验的是「执行完会不会发采集事件」，与模型说了什么无关。
 *
 * <p>⚠️ <b>租户在上下文启动前必须存在</b>（{@code MessagingChannels.resolve}
 * 启动期按 slug 解析 tenantId，fail-closed）→ 静态初始化块里先 provision。
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
        // ⚠️ 走 **database 通道**（生产默认形态）。
        "agent.messaging.mode=database",
        "agent.worker.enabled=true",
        // ⚠️ 中继关闭：本类不轮询共享库的 outbox_event。
        "agent.worker.relay-enabled=false",
        // ⚠️ 本租户是本 IT 专用；启动期按 slug 解析 tenantId，故租户必须已存在。
        "agent.worker.tenant-slugs=it-trace-closed-loop",
        "agent.messaging.poll-interval-ms=200"
})
@DisplayName("P0 完整闭环验收（真实 Run 跑完 → 自动采集 → 宽表落行）")
class TraceCollectClosedLoopIT {

    private static final String TENANT_SLUG = "it-trace-closed-loop";

    private static HikariDataSource adminPool;

    @Autowired
    private DataSource dataSource;

    /** ⚠️ **生产装配的执行循环**（不是 new 出来的）。 */
    @Autowired
    private RunExecutionLoop executionLoop;

    @Autowired
    private RunClaimPort claimPort;

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
        cfg.setPoolName("trace-loop-it-admin");
        adminPool = new HikariDataSource(cfg);
        try {
            ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        } catch (SQLException e) {
            throw new IllegalStateException("准备 it- 租户失败: " + e.getMessage(), e);
        }
    }

    @AfterEach
    void cleanup() {
        if (tenantId == null) {
            return;
        }
        // ⚠️ FORCE RLS 下不绑租户的 DELETE **静默删 0 行** → 必须带 tenant_id 谓词。
        exec("DELETE FROM public.tool_operation_attempt WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.tool_operation WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.usage_record WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_step WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_event WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run WHERE tenant_id = ?::uuid", tenantId);
        // ⚠️ 真实 Run 的质检信号会自动落 platform.review_task（L1_GAP / SAMPLE_RANDOM）；
        //    漏删会逐次 verify 累积，把 JdbcReviewTaskRepositoryIT 的待处理列表挤出 LIMIT 50
        //    （2026-11-11 事故）。按 tenant_id 谓词清理，绝不按种子租户字面量全表扫。
        exec("DELETE FROM platform.review_task WHERE tenant_id = ?::uuid", tenantId);
        if (traceId != null) {
            exec("DELETE FROM platform.agent_trace_tool_call WHERE trace_id = ?", traceId);
            exec("DELETE FROM platform.agent_trace_metric WHERE trace_id = ?", traceId);
        }
    }

    @Test
    @DisplayName("⚠️⚠️ 执行循环真跑完一次 Run 后，宽表必须自动出现该 Trace 的指标行")
    void realRunExecutionProducesMetricRowAutomatically() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        snapshotId = ItTenantProvisioner.snapshotId(adminPool, tenantId);
        runId = UUID.randomUUID();
        traceId = "trace-loop-" + runId;

        // ── 前置：宽表还没有这一行 ──
        assertThat(metricRowCount())
                .as("⚠️ 前置条件：Run 执行前该 trace 不应存在指标行")
                .isZero();

        // ── 造一条 QUEUED 的 Run（等待被领取）──
        insertQueuedRun();

        // ── 用**生产领取端口**领租约（与生产 Worker 完全同一条路径）──
        RunClaimPort.LeasedTask task = claimPort
                .claimSpecificRun(tenantId, runId.toString(), "it-worker-loop",
                        Duration.ofSeconds(30))
                .orElseThrow(() -> new AssertionError("⚠️ 未能领取 Run —— 领取路径与生产不一致"));

        // ── 用**生产执行循环**真跑一次（模型网关为 Fake，只影响模型说了什么）──
        RunConfigSnapshot snapshot = new RunConfigSnapshot(
                runId.toString(), new BigDecimal("2.00"), 12, 20,
                ApprovalRequirement.OPTIONAL, Instant.now());
        RunExecutionLoop.AgentModelConfig modelConfig = new RunExecutionLoop.AgentModelConfig(
                "fake-model", "你是辅导助手", 0.3, 512, Duration.ofSeconds(10), Map.of(),
                "test-agent", 1);

        executionLoop.execute(tenantId, task, modelConfig, snapshot, Map.of("query", "错题归因"));

        // ── 核心断言：Run 已到终态 ──
        assertThat(runState())
                .as("⚠️ 执行循环必须把 Run 推到终态（否则采集器按约定不采在途 Run）")
                .isEqualTo("SUCCEEDED");

        // ── 核心断言：宽表**自动**出现该 Trace 的指标行 ──
        //    这里没有手工发采集消息 —— 事件必须由执行循环自己发出。
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && metricRowCount() == 0) {
            Thread.sleep(200);
        }

        assertThat(metricRowCount())
                .as("⚠️⚠️ 真实 Run 执行完必须**自动**产生指标行 —— "
                        + "为 0 说明「执行循环跑完了但没发采集事件」，"
                        + "这是 G2 缺口未闭合的形态（而链路后半段全绿，"
                        + "看板照样空白）")
                .isEqualTo(1);
        assertThat(metricString("terminal_state"))
                .as("⚠️ 指标行必须反映 Run 的真实终态")
                .isEqualTo("SUCCEEDED");
        assertThat(metricString("trace_id"))
                .as("⚠️ 指标行的 trace_id 必须与 Run 一致")
                .isEqualTo(traceId);
        assertThat(metricString("tenant_id"))
                .as("⚠️ 指标行的租户必须来自 Run，不得串租户")
                .isEqualTo(tenantId);
        assertThat(metricString("termination_reason"))
                .as("⚠️ 终态原因必须归一为 COMPLETED（9 值枚举之一）")
                .isEqualTo("COMPLETED");
    }

    // ══════════════════════════════════════════════════════════════
    // 造数与断言辅助
    // ══════════════════════════════════════════════════════════════

    private void insertQueuedRun() throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.run (run_id, tenant_id, snapshot_id, idempotency_key,
                            request_digest, state, fencing_token, attempt, input_payload,
                            result_payload, correlation_id, created_at, updated_at, trace_id)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'QUEUED', 0, 0,
                            '{"query":"错题归因"}'::jsonb, '{}'::jsonb, ?, now(), now(), ?)
                    """)) {
                ps.setString(1, runId.toString());
                ps.setString(2, tenantId);
                ps.setString(3, snapshotId);
                ps.setString(4, "it-trace-loop-" + runId);
                ps.setString(5, "digest-" + runId);
                ps.setString(6, "corr-" + runId);
                ps.setString(7, traceId);
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    private String runState() {
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT state FROM public.run WHERE run_id = ?::uuid")) {
            ps.setString(1, runId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Run 状态失败", e);
        }
    }

    private int metricRowCount() {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT count(*) FROM platform.agent_trace_metric WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询指标行数失败", e);
        }
    }

    private String metricString(String column) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + column + " FROM platform.agent_trace_metric WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询指标列失败: " + column, e);
        }
    }

    private void exec(String sql, String... args) {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setString(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("执行失败: " + sql, e);
        }
    }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }
}
