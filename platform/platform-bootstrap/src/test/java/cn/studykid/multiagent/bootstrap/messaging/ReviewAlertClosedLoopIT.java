package cn.studykid.multiagent.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
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

import cn.studykid.multiagent.application.metrics.AgentMetricCollector;
import cn.studykid.multiagent.bootstrap.PlatformApplication;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;

/**
 * P2 告警 → 审查队列的<b>完整闭环</b>验收：
 * <b>真实采集一次 Trace → 异常信号被判定 → review_task 自动落库 → 队列端点可见</b>。
 *
 * <p><b>为什么这一类不能省</b>：只验「{@code ReviewAlertService.judge} 能判出异常」
 * 与「{@code ReviewTaskPort} 能写能读」，中间那段
 * <b>「采集器真的调了告警服务 → 告警服务真的写进了队列」</b>仍是空的。
 * 那两段各自为绿时，实际形态是：
 * <b>指标表有数、队列永远为空</b>，而队列空是「没有异常」的正常表现，
 * <b>外部完全无法区分「没有异常」与「根本没接线」</b>。
 *
 * <p><b>本类不 mock 任何东西</b>：采集器、告警服务、队列仓储、队列端点
 * 全是生产 Bean。唯一被替换的是<b>模型网关</b>（{@code FakeModelGateway}）。
 *
 * <p>⚠️ <b>异常从哪来</b>：真实 Run 不会自然产生「疑似循环」——
 * 那要靠模型反复调同一个工具。本类直接写一条<b>带超大 token 的指标行</b>
 * （COST-4 规则的可判定信号），再让<b>生产采集器</b>重新采集同一条 Run。
 * 这样「采集 → 判定 → 建任务」三段全是生产代码，
 * 只有「让 Run 变得超大」这一步是人为造数。
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
        "agent.worker.enabled=false",
        "agent.worker.relay-enabled=false",
        "agent.worker.tenant-slugs=it-review-alert"
})
@DisplayName("P2 告警 → 审查队列完整闭环（真实采集 → 自动建任务 → 端点可见）")
class ReviewAlertClosedLoopIT {

    private static final String TENANT_SLUG = "it-review-alert";

    private static HikariDataSource adminPool;

    @Autowired
    private AgentMetricCollector collector;

    @Autowired
    private ReviewTaskPort reviewTasks;

    @Autowired
    private DataSource dataSource;

    private String tenantId;

    @BeforeAll
    static void setup() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(env("IT_PG_URL", ""));
        cfg.setUsername(env("IT_PG_ADMIN_USER", "agent"));
        cfg.setPassword(env("IT_PG_ADMIN_PASSWORD", ""));
        cfg.setMaximumPoolSize(2);
        cfg.setPoolName("review-alert-it-admin");
        adminPool = new HikariDataSource(cfg);
        try {
            ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        } catch (SQLException e) {
            throw new IllegalStateException("准备 it- 租户失败: " + e.getMessage(), e);
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        if (tenantId == null) {
            return;
        }
        // review_task 是平台域表（无 RLS），但仍按本 IT 的 tenant 谓词清理，不全表扫
        exec("DELETE FROM platform.review_task WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM platform.agent_task_outcome WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM platform.agent_trace_metric WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.usage_record WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_event WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_step WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run WHERE tenant_id = ?::uuid", tenantId);
    }

    @Test
    @DisplayName("⚠️⚠️ 真实采集一条超大 token 的 Run，必须自动产出待审任务且端点可见")
    void oversizedTokenRunAutoCreatesReviewTask() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        UUID runId = UUID.randomUUID();
        String traceId = "trace-review-" + runId;
        insertQueuedRun(runId, traceId);
        insertOversizedUsage(runId);

        // 前置：队列为空
        assertThat(adminTaskCount())
                .as("⚠️ 前置条件：采集前不应存在待审任务")
                .isZero();

        // ── 生产采集器真跑一次 ──
        var collected = collector.collect(tenantId, runId);

        assertThat(collected)
                .as("⚠️⚠️ 采集器必须采到这条 Run —— 采不到则后续断言全是空转")
                .isPresent();


        // ── 核心断言：异常信号被转成待审任务 ──
        assertThat(adminTaskCount())
                .as("⚠️⚠️⚠️ 超大 token 的 Run 采集后必须**自动**产生待审任务 —— "
                        + "为 0 说明「采集器没调告警服务」或「告警服务没写队列」，"
                        + "这是 P2 审查流程空转的形态（指标有数、队列恒空、无任何报错）")
                .isEqualTo(1);

        // ── 任务内容正确 ──
        List<ReviewTaskPort.ReviewTaskView> tasks = adminTasks();
        ReviewTaskPort.ReviewTaskView task = tasks.get(0);
        assertThat(task.traceId()).isEqualTo(traceId);
        assertThat(task.runId()).isEqualTo(runId.toString());
        assertThat(task.tenantId()).isEqualTo(tenantId);
        assertThat(task.source()).isEqualTo("ANOMALY");
        assertThat(task.priority()).isEqualTo("NORMAL");
        assertThat(task.status()).isEqualTo("PENDING");

        // ── 端点视角：队列端口能读到它（与 SQL 直查交叉验证）──
        List<ReviewTaskPort.ReviewTaskView> viaPort = reviewTasks.list(null, tenantId, 50);
        assertThat(viaPort)
                .as("⚠️⚠️ 队列端点（走生产端口）必须能看到这条任务 —— "
                        + "读不到说明「写进去了但读不出来」，reviewer 端仍是空的")
                .hasSize(1)
                .extracting(ReviewTaskPort.ReviewTaskView::taskId)
                .containsExactly(task.taskId());
    }

    @Test
    @DisplayName("⚠️⚠️ 重复采集不得产生重复任务（去重闸门）")
    void repeatedCollectMustNotDuplicateTasks() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        UUID runId = UUID.randomUUID();
        String traceId = "trace-review-dup-" + runId;
        insertQueuedRun(runId, traceId);
        insertOversizedUsage(runId);

        collector.collect(tenantId, runId);
        assertThat(adminTaskCount()).isEqualTo(1);

        // 第二次采集（生产里会因消息重投发生）
        collector.collect(tenantId, runId);

        assertThat(adminTaskCount())
                .as("⚠️⚠️ 重复采集**不得**堆出多条同源任务 —— "
                        + "队列里同一个问题躺 N 份，reviewer 挨个点开才发现是同一个，"
                        + "队列信噪比直接崩掉")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("⚠️ 正常 token 的 Run 不产生告警类任务（未命中不建是队列信噪比前提）")
    void normalRunCreatesNoAlertTask() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        UUID runId = UUID.randomUUID();
        String traceId = "trace-review-normal-" + runId;
        insertQueuedRun(runId, traceId);
        insertUsage(runId, 100L, 50L);

        collector.collect(tenantId, runId);

        // ⚠️ P3 起「正常 Run 队列恒为 0」这个旧口径不再成立：
        //    评估双轨会为回放数据不足的 Run 建 L1_GAP、或按抽样率建 SAMPLE_RANDOM
        //    （那是人审黄金基线，不是告警）。这里只断言**告警类**任务为零，
        //    否则会把正常的人审抽样误报成「告警误触发」。
        assertThat(adminTasks().stream()
                .map(ReviewTaskPort.ReviewTaskView::source)
                .filter(s -> "ANOMALY".equals(s) || "ALERT".equals(s))
                .count())
                .as("⚠️ 正常 Run 不得产生 ANOMALY / ALERT 任务 —— "
                        + "否则队列全是无关告警项。P3 的 L1_GAP / SAMPLE_RANDOM "
                        + "属于人审抽样，不在此断言范围内")
                .isZero();
    }

    // ══════════════════════════════════════════════════════════════
    // 造数与断言辅助
    // ══════════════════════════════════════════════════════════════

    /**
     * 造一条<b>已终态</b>的 Run。
     *
     * <p>⚠️ 必须是终态（SUCCEEDED）：采集器只采「已收敛」的 Trace
     * （在途 QUEUED / RUNNING 一律跳过 —— 计入会把「还没跑完」算成「没成功」，
     * 成功率必然失真）。本类要验的是「采集 → 判定 → 建任务」，
     * 而非「Run 怎么跑完」，故直接造终态事实。
     */
    private void insertQueuedRun(UUID runId, String traceId) throws SQLException {
        String snapshotId = ItTenantProvisioner.snapshotId(adminPool, tenantId);
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.run (run_id, tenant_id, snapshot_id, idempotency_key,
                            request_digest, state, fencing_token, attempt, input_payload,
                            result_payload, correlation_id, created_at, updated_at, trace_id,
                            completed_at)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'SUCCEEDED', 0, 0,
                            '{"query":"错题归因"}'::jsonb, '{}'::jsonb, ?,
                            now() - interval '3 seconds', now(), ?, now())
                    """)) {
                ps.setString(1, runId.toString());
                ps.setString(2, tenantId);
                ps.setString(3, snapshotId);
                ps.setString(4, "it-review-alert-" + runId);
                ps.setString(5, "digest-" + runId);
                ps.setString(6, "corr-" + runId);
                ps.setString(7, traceId);
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    /** 造一条「超大 token」的用量事实（COST-4 规则的可判定信号）。 */
    private void insertOversizedUsage(UUID runId) throws SQLException {
        insertUsage(runId, 25_000L, 1_000L);
    }

    private void insertUsage(UUID runId, Long in, Long out) throws SQLException {
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.usage_record
                        (tenant_id, run_id, model_id, input_tokens, output_tokens, created_at)
                    VALUES (?::uuid, ?::uuid, 'fake-model', ?, ?, now())
                    """)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId.toString());
                ps.setLong(3, in);
                ps.setLong(4, out);
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    private int adminTaskCount() throws SQLException {
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT count(*) FROM platform.review_task WHERE tenant_id = ?::uuid")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private List<ReviewTaskPort.ReviewTaskView> adminTasks() throws SQLException {
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement("""
                        SELECT task_id, trace_id, tenant_id, run_id, source, priority,
                               status, assigned_to, root_cause, created_at
                          FROM platform.review_task WHERE tenant_id = ?::uuid
                        """)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<ReviewTaskPort.ReviewTaskView> out = new java.util.ArrayList<>();
                while (rs.next()) {
                    out.add(new ReviewTaskPort.ReviewTaskView(
                            rs.getString("task_id"),
                            rs.getString("trace_id"),
                            rs.getObject("tenant_id", UUID.class).toString(),
                            rs.getObject("run_id", UUID.class).toString(),
                            rs.getString("source"),
                            rs.getString("priority"),
                            rs.getString("status"),
                            rs.getString("assigned_to"),
                            rs.getString("root_cause"),
                            String.valueOf(rs.getTimestamp("created_at").toInstant())));
                }
                return out;
            }
        }
    }

    private void exec(String sql, String... args) throws SQLException {
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
