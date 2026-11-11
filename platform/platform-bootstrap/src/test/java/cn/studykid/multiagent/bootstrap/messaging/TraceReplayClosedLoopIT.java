package cn.studykid.multiagent.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.application.replay.TraceReplayService;
import cn.studykid.multiagent.bootstrap.PlatformApplication;
import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TenantSessionBinder;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;
import cn.studykid.multiagent.worker.execute.RunExecutionLoop;

/**
 * P1 对话回放底座（G1）的<b>完整闭环</b>验收：
 * <b>真实 Run 跑完 → 自动落对话消息 → 回放服务组装出完整时间线</b>。
 *
 * <p><b>为什么这一类不能省</b>：P1 之前，
 * {@code run_message} <b>根本不存在</b>——系统里没有任何一处持久化对话内容。
 * 若只验「仓储能写能读」，就会出现「表和仓储都在，但<b>执行循环从来没调用过它</b>」的空转：
 * 回放端点永远返回空，而<b>全部测试全绿</b>。
 * 本类用<b>生产 {@link RunExecutionLoop} Bean</b> 真跑一次 Run，
 * 让「执行 → 埋点 → 落库 → 回放组装」整条链在测试里自行贯通。
 *
 * <p><b>本类不 mock 任何东西</b>：执行循环、消息写入仓储、回放仓储、回放服务
 * 全是生产 Bean。唯一被替换的是<b>模型网关</b>（{@code FakeModelGateway}）——
 * 真实模型需要凭据且不稳定，而本类要验的是「对话有没有被记下来」，与模型说了什么无关。
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
        "agent.messaging.mode=database",
        "agent.worker.enabled=true",
        // ⚠️ 中继关闭：本类不轮询共享库的 outbox_event。
        "agent.worker.relay-enabled=false",
        "agent.worker.tenant-slugs=it-trace-replay",
        "agent.messaging.poll-interval-ms=200"
})
@DisplayName("P1 回放底座完整闭环（真实 Run → 对话消息自动落库 → 回放时间线）")
class TraceReplayClosedLoopIT {

    private static final String TENANT_SLUG = "it-trace-replay";

    private static HikariDataSource adminPool;

    @Autowired
    private DataSource dataSource;

    /** ⚠️ **生产装配的执行循环**（不是 new 出来的）。 */
    @Autowired
    private RunExecutionLoop executionLoop;

    @Autowired
    private RunClaimPort claimPort;

    /** ⚠️ **生产装配的回放服务**。 */
    @Autowired
    private TraceReplayService replayService;

    /** ⚠️ **生产装配的回放仓储**（跨租户反查用）。 */
    @Autowired
    private TraceReplayPort replayPort;

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
        cfg.setPoolName("trace-replay-it-admin");
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
        // ⚠️ run_message 必须**先删**：它是本次新增的表，不删会在下一轮残留。
        exec("DELETE FROM public.run_message WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.tool_operation_attempt WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.tool_operation WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.usage_record WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_step WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_event WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run WHERE tenant_id = ?::uuid", tenantId);
    }

    @Test
    @DisplayName("⚠️⚠️ 真实 Run 执行完必须自动留下对话消息，且回放能组装出完整时间线")
    void realRunExecutionProducesReplayableTranscript() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        snapshotId = ItTenantProvisioner.snapshotId(adminPool, tenantId);
        runId = UUID.randomUUID();
        traceId = "trace-replay-" + runId;

        // ── 前置：还没有任何对话消息 ──
        assertThat(messageCount())
                .as("⚠️ 前置条件：Run 执行前不应存在对话消息")
                .isZero();

        insertQueuedRun();

        RunClaimPort.LeasedTask task = claimPort
                .claimSpecificRun(tenantId, runId.toString(), "it-worker-replay",
                        Duration.ofSeconds(30))
                .orElseThrow(() -> new AssertionError("⚠️ 未能领取 Run —— 领取路径与生产不一致"));

        RunConfigSnapshot snapshot = new RunConfigSnapshot(
                runId.toString(), new BigDecimal("2.00"), 12, 20,
                ApprovalRequirement.OPTIONAL, Instant.now());
        RunExecutionLoop.AgentModelConfig modelConfig = new RunExecutionLoop.AgentModelConfig(
                "fake-model", "你是辅导助手", 0.3, 512, Duration.ofSeconds(10), Map.of(),
                "test-agent", 1);

        executionLoop.execute(tenantId, task, modelConfig, snapshot, Map.of("query", "错题归因"));

        assertThat(runState())
                .as("⚠️ 执行循环必须把 Run 推到终态")
                .isEqualTo("SUCCEEDED");

        // ── 核心断言：对话消息**自动**落库 ──
        //    这里没有手工调用写入端口 —— 埋点必须由执行循环自己触发。
        // ⚠️ 两个视角交叉验证：管理视角 >0 而应用视角为 0 ⇒ 「读侧没绑租户」，
        //    不是「没埋点」；两者都为 0 才是真正的功能空转。
        assertThat(adminMessageCount())
                .as("⚠️⚠️ 管理视角必须看到落库的对话消息（区分「没埋点」与「读侧 RLS 没绑」）")
                .isGreaterThanOrEqualTo(2);
        assertThat(messageCount())
                .as("⚠️⚠️ 真实 Run 执行完必须**自动**留下对话消息 —— "
                        + "为 0 说明「执行循环跑完了但没埋点」，"
                        + "这是 G1 缺口未闭合的形态（回放端点永远为空，"
                        + "而仓储与端点全绿）")
                .isGreaterThanOrEqualTo(2)
                .isEqualTo(adminMessageCount());

        // ── 核心断言：回放服务能组装出完整时间线 ──
        TraceReplayService.Transcript transcript =
                replayService.buildTranscript(tenantId, runId.toString());
        assertThat(transcript)
                .as("⚠️ 回放必须能组装出记录（否则端点恒 404）")
                .isNotNull();

        List<TraceReplayPort.MessageView> messages = transcript.messages();
        assertThat(messages)
                .as("⚠️ 回放的消息条数必须与落库一致")
                .hasSize(messageCount());

        // ── 首条必须是 USER（学生输入）──
        assertThat(messages.get(0).role())
                .as("⚠️ 回放首条必须是学生输入（否则对话顺序错乱）")
                .isEqualTo("USER");
        assertThat(messages.get(0).content().get("text"))
                .as("⚠️ 用户消息必须带文本（否则回放看不到学生问了什么）")
                .isNotNull();

        // ── 必须有 ASSISTANT（模型输出）──
        assertThat(messages)
                .as("⚠️⚠️ 必须存在 ASSISTANT 消息 —— "
                        + "没有它就意味着「模型说了什么」根本没被记录，"
                        + "回放只剩学生单方面的话，复盘无意义")
                .anyMatch(m -> "ASSISTANT".equals(m.role()));

        // ── 消息按 seq 升序（回放顺序确定性）──
        long previous = Long.MIN_VALUE;
        for (TraceReplayPort.MessageView m : messages) {
            assertThat(m.seq())
                    .as("⚠️ 回放必须按 seq 升序（否则对话顺序不确定）")
                    .isGreaterThan(previous);
            previous = m.seq();
        }

        // ── Run 元信息必须正确 ──
        assertThat(transcript.run().runId()).isEqualTo(runId.toString());
        assertThat(transcript.run().state()).isEqualTo("SUCCEEDED");
        assertThat(transcript.run().traceId()).isEqualTo(traceId);
    }

    @Test
    @DisplayName("⚠️⚠️ 跨租户读取必须读不到（不得泄露别的租户的对话）")
    void crossTenantReadMustNotLeak() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        snapshotId = ItTenantProvisioner.snapshotId(adminPool, tenantId);
        runId = UUID.randomUUID();
        traceId = "trace-replay-cross-" + runId;

        insertQueuedRun();

        RunClaimPort.LeasedTask task = claimPort
                .claimSpecificRun(tenantId, runId.toString(), "it-worker-replay",
                        Duration.ofSeconds(30))
                .orElseThrow(() -> new AssertionError("⚠️ 未能领取 Run"));

        RunConfigSnapshot snapshot = new RunConfigSnapshot(
                runId.toString(), new BigDecimal("2.00"), 12, 20,
                ApprovalRequirement.OPTIONAL, Instant.now());
        RunExecutionLoop.AgentModelConfig modelConfig = new RunExecutionLoop.AgentModelConfig(
                "fake-model", "你是辅导助手", 0.3, 512, Duration.ofSeconds(10), Map.of(),
                "test-agent", 1);

        executionLoop.execute(tenantId, task, modelConfig, snapshot, Map.of("query", "错题归因"));

        assertThat(messageCount()).isGreaterThanOrEqualTo(2);

        // ── 换一个租户读：必须读不到任何东西 ──
        String otherTenant = ItTenantProvisioner.provision(adminPool, "it-trace-replay-other");
        try {
            List<TraceReplayPort.MessageView> leaked =
                    replayService.buildTranscript(otherTenant, runId.toString()) == null
                            ? List.of()
                            : replayService.buildTranscript(otherTenant, runId.toString()).messages();

            assertThat(leaked)
                    .as("⚠️⚠️ 跨租户回放必须**读不到任何消息** —— "
                            + "读到即越权，学生对话内容泄露")
                    .isEmpty();

            assertThat(replayService.buildTranscript(otherTenant, runId.toString()))
                    .as("⚠️ 跨租户的 runId 必须表现为「不存在」（404 口径，不泄露归属）")
                    .isNull();
        } finally {
            exec("DELETE FROM public.run_message WHERE tenant_id = ?::uuid", otherTenant);
            exec("DELETE FROM public.run WHERE tenant_id = ?::uuid", otherTenant);
        }
    }

    @Test
    @DisplayName("⚠️ 幂等：同一 Run 重放执行不应产生重复消息")
    void replayExecutionMustNotDuplicateMessages() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        snapshotId = ItTenantProvisioner.snapshotId(adminPool, tenantId);
        runId = UUID.randomUUID();
        traceId = "trace-replay-idem-" + runId;

        insertQueuedRun();

        RunConfigSnapshot snapshot = new RunConfigSnapshot(
                runId.toString(), new BigDecimal("2.00"), 12, 20,
                ApprovalRequirement.OPTIONAL, Instant.now());
        RunExecutionLoop.AgentModelConfig modelConfig = new RunExecutionLoop.AgentModelConfig(
                "fake-model", "你是辅导助手", 0.3, 512, Duration.ofSeconds(10), Map.of(),
                "test-agent", 1);

        // 第一次执行
        RunClaimPort.LeasedTask first = claimPort
                .claimSpecificRun(tenantId, runId.toString(), "it-worker-replay",
                        Duration.ofSeconds(30))
                .orElseThrow(() -> new AssertionError("⚠️ 未能领取 Run"));
        executionLoop.execute(tenantId, first, modelConfig, snapshot, Map.of("query", "错题归因"));
        int afterFirst = messageCount();
        assertThat(afterFirst).isGreaterThanOrEqualTo(2);

        // ⚠️⚠️ **终态 Run 在生产领取路径里根本领不到**：
        //    {@code claimSpecificRun} 只收 QUEUED / 过期的 RUNNING，
        //    所以「再 claim 一次」既不是真实的重放形态，也必然失败。
        //    真实会重入的是<b>同一个 LeasedTask 被执行循环调用两次</b>
        //    （消息重投 / Worker 崩溃恢复后旧执行体残留），故此处复用 first。
        //
        // ⚠️ 反向验证：先删掉 USER 那条，确认第二次执行<b>真的又埋了一次点</b>
        //    （USER 被补回）。否则「消息数没变」可能只是「第二次压根没跑」的假绿。
        exec("DELETE FROM public.run_message "
                        + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid AND idem_key = ?",
                tenantId, runId.toString(), "USER:0");
        assertThat(adminMessageCount())
                .as("⚠️ 反向验证前置：删掉 USER 消息后必须恰好少一条")
                .isEqualTo(afterFirst - 1);

        executionLoop.execute(tenantId, first, modelConfig, snapshot, Map.of("query", "错题归因"));

        assertThat(messageCount())
                .as("⚠️⚠️ 重放执行**不得**产生重复消息 —— "
                        + "重复会让回放里同一句话出现两次，审查者会误判为模型反复改口；"
                        + "同时 USER 必须被补回（证明第二次埋点确实执行了，而非假绿）")
                .isEqualTo(afterFirst);
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
                ps.setString(4, "it-trace-replay-" + runId);
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

    /**
     * ⚠️⚠️ <b>必须在事务内绑定租户后再查</b>：
     * {@code public.run_message} 带 FORCE RLS（{@code tenant_id = current_tenant_id()}），
     * 而应用角色 {@code app_agent} 是 NOBYPASSRLS。直接拿 {@link #dataSource}
     * 的连接执行 {@code SELECT count(*)} 且不绑租户 → <b>恒为 0</b>，
     * 会把「消息已落库」误判成「埋点没执行」（P1-E 的真实踩坑）。
     *
     * <p>⚠️ 还必须显式 {@code setAutoCommit(false)}：
     * {@code set_config('app.tenant_id', ?, true)} 是<b>事务局部</b>的，
     * autocommit 下绑定在语句提交那一瞬就失效，后续查询仍看不到任何行。
     */
    private int messageCount() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            TenantSessionBinder.bind(conn,
                    new TenantContext(tenantId, "trace-replay-it-reader", Set.of(), 0L));
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM public.run_message "
                            + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询对话消息数失败", e);
        }
    }

    /**
     * 管理视角直查（BYPASSRLS）——与 {@link #messageCount()} 互为交叉验证：
     * 它 {@code >0} 而 {@code messageCount()} 为 0 ⇒ 「读侧 RLS 没绑」；
     * 两者都为 0 ⇒ 才是真正的「写侧没埋点」空转。
     */
    private int adminMessageCount() {
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT count(*) FROM public.run_message "
                                + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
            ps.setString(1, tenantId);
            ps.setString(2, runId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("管理视角查询对话消息数失败", e);
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
