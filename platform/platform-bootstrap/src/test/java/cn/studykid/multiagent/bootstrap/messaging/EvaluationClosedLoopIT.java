package cn.studykid.multiagent.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSource;

import cn.studykid.multiagent.application.metrics.AgentMetricCollector;
import cn.studykid.multiagent.bootstrap.PlatformApplication;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;

/**
 * P3 评估双轨的<b>完整闭环</b>验收：
 * <b>真实采集一次 Trace → L1 自动判分 → agent_task_outcome 自动落库 → 宽表 auto_checked 被回填</b>。
 *
 * <p>⚠️ <b>为什么不能只靠单测</b>：{@code L1AttributionEvaluatorTest} 用替身回放端口、
 * {@code TraceEvaluationServiceTest} 用替身指标端口。三段各自为绿时，
 * 实际形态仍可能是「采集器没调评估服务 → 覆盖率恒 0、看板看起来正常」，
 * <b>没有任何报错</b>——本项目反复出现的「机制正确但从未接线」形状。
 * 本类全用生产 Bean，只把 Run / 回放消息作为造数。
 *
 * <p>⚠️ <b>抽样率置 0</b>：本类验的是 L1 覆盖率回写，
 * 随机抽样任务的干扰由 {@code TraceEvaluationServiceTest} 单测覆盖。
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
        "agent.worker.tenant-slugs=it-eval-closed-loop",
        "agent.quality.eval.sample-rate=0"
})
@DisplayName("P3 评估双轨完整闭环（真实采集 → L1 判分 → 覆盖率回填）")
class EvaluationClosedLoopIT {

    private static final String TENANT_SLUG = "it-eval-closed-loop";

    private static DruidDataSource adminPool;

    @Autowired
    private AgentMetricCollector collector;

    @Autowired
    private ReviewTaskPort reviewTasks;

    @Autowired
    private TraceReplayPort replayPort;

    private String tenantId;

    @BeforeAll
    static void setup() {
        DruidDataSource cfg = new DruidDataSource();
        cfg.setMaxWait(10_000L);
        cfg.setConnectTimeout(10_000);
        cfg.setUrl(env("IT_PG_URL", ""));
        cfg.setUsername(env("IT_PG_ADMIN_USER", "agent"));
        cfg.setPassword(env("IT_PG_ADMIN_PASSWORD", ""));
        cfg.setMaxActive(2);
        cfg.setName("eval-closed-loop-it-admin");
        adminPool = cfg;
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
        // ⚠️ 清理必带 tenant_id（FORCE RLS 下不绑租户会静默删 0 行）
        exec("DELETE FROM public.run_message WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM platform.review_task WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM platform.agent_task_outcome WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM platform.agent_trace_metric WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.usage_record WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_event WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_step WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run WHERE tenant_id = ?::uuid", tenantId);
    }

    @Test
    @DisplayName("⚠️⚠️ 完整回放的 Run 采集后必须自动留下 L1 回执并回填 auto_checked")
    void completeTranscriptAutoWritesL1Outcome() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        UUID runId = UUID.randomUUID();
        String traceId = "trace-eval-pass-" + runId;
        prepare(runId, traceId);
        insertMessage(runId, traceId, "USER", "{\"text\":\"解方程\"}", "user-0");
        insertMessage(runId, traceId, "ASSISTANT",
                "{\"text\":\"解：x=2 或 x=3\",\"toolCalls\":[],\"finishReason\":\"STOP\"}", "assistant-1");

        assertThat(outcomeCount(traceId)).isZero();

        var collected = collector.collect(tenantId, runId);
        assertThat(collected).as("⚠️ 采集器必须采到这条 Run，否则后续断言全是空转").isPresent();

        assertThat(outcomeCount(traceId))
                .as("⚠️⚠️⚠️ 真实采集后必须**自动**写下 L1 回执 —— "
                        + "为 0 说明评估服务没接进采集链路，覆盖率恒 0 而看板毫无异常")
                .isEqualTo(1);
        assertThat(outcomeVerified(traceId))
                .as("完整链路（非空回答 / 未截断 / 无工具失败）应判 linkComplete=true")
                .isTrue();
        assertThat(text("SELECT auto_checked::text FROM platform.agent_trace_metric "
                + "WHERE trace_id = '" + traceId + "'"))
                .as("⚠️ 宽表 auto_checked 必须由 recordTaskOutcome 回填 —— "
                        + "否则「自动校验覆盖率」看板永远显示 0")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("⚠️ 回答被截断（finishReason=LENGTH）时 L1 不得判为通过")
    void truncatedTranscriptIsNotVerified() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        UUID runId = UUID.randomUUID();
        String traceId = "trace-eval-trunc-" + runId;
        prepare(runId, traceId);
        insertMessage(runId, traceId, "USER", "{\"text\":\"解方程\"}", "user-0");
        insertMessage(runId, traceId, "ASSISTANT",
                "{\"text\":\"解：先移项……\",\"toolCalls\":[],\"finishReason\":\"LENGTH\"}", "assistant-1");

        collector.collect(tenantId, runId);

        assertThat(outcomeCount(traceId)).isEqualTo(1);
        assertThat(outcomeVerified(traceId))
                .as("⚠️ 真实实跑抓到过 maxOutputTokens=1024 导致回答被腰斩；"
                        + "把它算成「自动校验通过」等于掩盖降级产出")
                .isFalse();
    }

    @Test
    @DisplayName("⚠️ 回放数据不足时绝不伪造覆盖率，改走 L1_GAP 人审")
    void noTranscriptCreatesGapTaskNotFakeCoverage() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        UUID runId = UUID.randomUUID();
        String traceId = "trace-eval-gap-" + runId;
        prepare(runId, traceId);
        // 刻意不插 run_message

        collector.collect(tenantId, runId);

        assertThat(outcomeCount(traceId))
                .as("⚠️ 事实不足却写了 L1 回执 = 把「没埋点」算成「判过了」，"
                        + "覆盖率数字从此失真")
                .isZero();
        assertThat(text("SELECT source FROM platform.review_task WHERE trace_id = '"
                + traceId + "'"))
                .as("无回放事实应转为 L1_GAP 人审任务，交人补齐而不是机器硬判")
                .isEqualTo("L1_GAP");
        assertThat(reviewTasks.list(null, tenantId, 50))
                .as("队列端口（生产读路径）必须能看到这条缺口任务")
                .hasSize(1);
    }

    @Test
    @DisplayName("重复采集不得堆出多条回执（ON CONFLICT 幂等）")
    void repeatedCollectIsIdempotent() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        UUID runId = UUID.randomUUID();
        String traceId = "trace-eval-dup-" + runId;
        prepare(runId, traceId);
        insertMessage(runId, traceId, "USER", "{\"text\":\"解方程\"}", "user-0");
        insertMessage(runId, traceId, "ASSISTANT",
                "{\"text\":\"解：x=2\",\"toolCalls\":[],\"finishReason\":\"STOP\"}", "assistant-1");

        collector.collect(tenantId, runId);
        collector.collect(tenantId, runId);

        assertThat(outcomeCount(traceId)).isEqualTo(1);
    }

    @Test
    @DisplayName("⚠️⚠️ 平台侧仅凭 runId 必须能反查到租户（查 FORCE RLS 表会恒 404）")
    void platformReplayResolvesTenantForRealRun() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        UUID runId = UUID.randomUUID();
        String traceId = "trace-replay-resolve-" + runId;
        prepare(runId, traceId);
        insertMessage(runId, traceId, "USER", "{\"text\":\"解方程\"}", "user-0");
        insertMessage(runId, traceId, "ASSISTANT",
                "{\"text\":\"解：x=2\",\"toolCalls\":[],\"finishReason\":\"STOP\"}", "assistant-1");

        assertThat(collector.collect(tenantId, runId))
                .as("先真实采集一次，指标宽表才有 (run_id, tenant_id) 反查事实")
                .isPresent();

        assertThat(replayPort.resolveTenantId(runId.toString()))
                .as("⚠️⚠️ 平台回放端点靠这一步定位租户；返回 empty = 对所有 Run 恒 404。"
                        + "旧实现查 FORCE RLS 的 public.run 且未绑 app.tenant_id —— "
                        + "app_agent 是 NOBYPASSRLS，SELECT 静默 0 行，"
                        + "端点单测用 mock 端口全绿也照不出来")
                .contains(tenantId);
    }

    // ── 场景维度（scenario）真实库闭环 ─────────────────────────────

    @Test
    @DisplayName("⚠️⚠️ 显式场景标签必须经真实采集写进宽表 —— 否则「按场景拆分」全程空转")
    void explicitScenarioFlowsIntoWideTable() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        UUID runId = UUID.randomUUID();
        String traceId = "trace-scenario-ok-" + runId;
        prepare(runId, traceId,
                "{\"query\":\"错题归因\",\"scenario\":\"数学:一元二次方程\"}");

        assertThat(collector.collect(tenantId, runId))
                .as("采集必须成功，否则后面的宽表断言全是空转").isPresent();

        assertThat(scenarioOf(traceId))
                .as("⚠️⚠️ 采集器仍把 scenario 硬编码为 null 时，"
                        + "宽表这一列恒空、看板按场景拆分永远只有「未标注」一组，且毫无报错")
                .isEqualTo("数学:一元二次方程");
    }

    @Test
    @DisplayName("⚠️ 未标注场景的 Run 采集后必须保持 NULL，绝不填默认分组")
    void unlabelledRunKeepsScenarioNull() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        UUID runId = UUID.randomUUID();
        String traceId = "trace-scenario-absent-" + runId;
        prepare(runId, traceId);

        collector.collect(tenantId, runId);

        assertThat(scenarioOf(traceId))
                .as("存量 Run 没有标签就是没有标签；编造分组会稀释真实覆盖率口径")
                .isNull();
    }

    @Test
    @DisplayName("⚠️⚠️ 自由文本不得被当标签复制进宽表（合规红线 + 分组基数爆炸）")
    void freeTextScenarioIsRejectedNotCopied() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        UUID runId = UUID.randomUUID();
        String traceId = "trace-scenario-freetext-" + runId;
        prepare(runId, traceId,
                "{\"query\":\"错题归因\",\"scenario\":\"请帮我解方程 x^2 - 5x + 6 = 0，我不确定对不对\"}");

        collector.collect(tenantId, runId);

        assertThat(scenarioOf(traceId))
                .as("把整段学生提问塞进 scenario 等于在指标宽表多存一份对话原文，"
                        + "并让看板一 Run 一个分组；必须降级为「未标注」")
                .isNull();
    }

    @Test
    @DisplayName("⚠️ 非字符串 scenario（object/array）必须被 SQL 类型闸门挡住")
    void nonStringScenarioIsRejected() throws Exception {
        tenantId = ItTenantProvisioner.provision(adminPool, TENANT_SLUG);
        UUID runId = UUID.randomUUID();
        String traceId = "trace-scenario-object-" + runId;
        prepare(runId, traceId,
                "{\"query\":\"错题归因\",\"scenario\":{\"subject\":\"数学\"}}");

        collector.collect(tenantId, runId);

        assertThat(scenarioOf(traceId))
                .as("jsonb ->> 会把 object 转成整段 JSON 文本；不先判 typeof 就会把结构带进宽表")
                .isNull();
    }

    // ══════════════════════════════════════════════════════════════
    // 造数与断言辅助
    // ══════════════════════════════════════════════════════════════

    private void prepare(UUID runId, String traceId) throws SQLException {
        prepare(runId, traceId, "{\"query\":\"错题归因\"}");
    }

    /**
     * 可指定 {@code input_payload} 的造数重载 —— 场景标签链路的验收靠它注入
     * 显式 {@code scenario} 键 / 自由文本 / 非字符串值，验证真实采集后的宽表结果。
     */
    private void prepare(UUID runId, String traceId, String inputPayloadJson)
            throws SQLException {
        String snapshotId = ItTenantProvisioner.snapshotId(adminPool, tenantId);
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.run (run_id, tenant_id, snapshot_id, idempotency_key,
                            request_digest, state, fencing_token, attempt, input_payload,
                            result_payload, correlation_id, created_at, updated_at, trace_id,
                            completed_at)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'SUCCEEDED', 0, 0,
                            ?::jsonb, '{}'::jsonb, ?,
                            now() - interval '3 seconds', now(), ?, now())
                    """)) {
                ps.setString(1, runId.toString());
                ps.setString(2, tenantId);
                ps.setString(3, snapshotId);
                ps.setString(4, "it-eval-" + runId);
                ps.setString(5, "digest-" + runId);
                ps.setString(6, inputPayloadJson);
                ps.setString(7, "corr-" + runId);
                ps.setString(8, traceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO public.usage_record
                        (tenant_id, run_id, model_id, input_tokens, output_tokens, created_at)
                    VALUES (?::uuid, ?::uuid, 'fake-model', 100, 50, now())
                    """)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId.toString());
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    private void insertMessage(UUID runId, String traceId, String role, String content,
                               String idemKey) throws SQLException {
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO public.run_message
                            (tenant_id, run_id, trace_id, role, content, fencing_token, idem_key)
                        VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb, 0, ?)
                        """)) {
            ps.setString(1, tenantId);
            ps.setString(2, runId.toString());
            ps.setString(3, traceId);
            ps.setString(4, role);
            ps.setString(5, content);
            ps.setString(6, idemKey);
            ps.executeUpdate();
        }
    }

    private int outcomeCount(String traceId) throws SQLException {
        return Integer.parseInt(text("SELECT count(*) FROM platform.agent_task_outcome "
                + "WHERE trace_id = '" + traceId + "' AND checker_code = 'TRACE_LINK_L1'"));
    }

    private boolean outcomeVerified(String traceId) throws SQLException {
        return "true".equalsIgnoreCase(text("SELECT verified::text FROM platform.agent_task_outcome "
                + "WHERE trace_id = '" + traceId + "' AND checker_code = 'TRACE_LINK_L1'"));
    }

    /** 读取宽表里的场景标签；SQL NULL 如实返回 null。 */
    private String scenarioOf(String traceId) throws SQLException {
        return nullableText("SELECT scenario FROM platform.agent_trace_metric "
                + "WHERE trace_id = '" + traceId + "'");
    }

    /**
     * 与 {@link #text} 的差异：{@code text()} 用 {@code String.valueOf(rs.getObject(1))}，
     * 会把 SQL NULL 变成字符串 {@code "null"}；场景标签要区分「未标注」与字面量，
     * 必须用 {@code getString} 保留 null。
     */
    private String nullableText(String sql) throws SQLException {
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private String text(String sql) throws SQLException {
        try (Connection conn = adminPool.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? String.valueOf(rs.getObject(1)) : "<none>";
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
