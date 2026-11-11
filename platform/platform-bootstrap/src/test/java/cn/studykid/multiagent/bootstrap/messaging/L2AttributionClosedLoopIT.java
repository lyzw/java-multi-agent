package cn.studykid.multiagent.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.application.metrics.AgentMetricCollector;
import cn.studykid.multiagent.bootstrap.PlatformApplication;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.model.ModelResponse.FinishReason;
import cn.studykid.multiagent.ports.model.ModelResponse.ModelUsage;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;

/**
 * L2 归因判分的<b>真采集 → 真题库 → 真判定器 → 真库回写</b>闭环 IT。
 *
 * <p><b>与行为单测的分工</b>：{@code L2AttributionEvaluatorTest} 用替身端口验证「条件都满足才写」，
 * 但证明不了生产装配里「采集器真的把 questionId 传进评估链」「题库 bean 真的加载了」
 * 「判定器真的复用了生产模型网关」「机器结论真的只落宽表那一列」。
 * 任何一处断链的表象都是<b>看板显示未启用 / 样本不足，而不是报错</b>——
 * 这正是本项目反复出现的静默失真形状。本类从<b>采集编排入口</b>进，
 * 只替换唯一网络出口（模型网关），其余全部是生产 Bean。
 *
 * <p><b>为什么只替换 chatStreamGateway</b>：L2 判定器复用生产模型网关，
 * 但真模型需要凭据且不稳定；本类要验的是「结论有没有从判定器流回宽表」，
 * 不是模型说了什么。题库走 {@code classpath:} 测试文件源，仍然<b>真实</b>执行
 * 加载期校验 / 精确 questionId 查找，不是 mock 端口。
 *
 * <p><b>闭合链路</b>：
 * <ol>
 *   <li>{@code collector.collect(tenantId, runId)} 真读 Run / 回放 / 用量；</li>
 *   <li>事实端口从 {@code run.input_payload.questionId} 取显式题目标识；</li>
 *   <li>题库源按 questionId 精确取标准解析（测试题库 {@code Q-MATH-IT-001}）；</li>
 *   <li>{@code ModelAttributionJudge} 经生产 {@code chatStreamGateway} 跑 MiniMax 严格 rubric；</li>
 *   <li>结论经 {@code AgentMetricPort} 只写 {@code agent_trace_metric.attribution_correct}；
 *       {@code sampled} / {@code human_verdict} / {@code agent_eval_sample} / {@code usage_record}
 *       全部保持原状。</li>
 * </ol>
 *
 * <p><b>反向验证锚点</b>（每条都问过「撤掉修复点它会红吗」，2026-11-11 九项全红）：
 * <ul>
 *   <li>yml 用裸键 {@code MiniMax-M2.5} → Spring Binder 拆层，定价绑定守卫红；</li>
 *   <li>采集器把 {@code run.questionId()} 改成 {@code null} → {@code gradedTrueWritesOnlyAttributionColumn} 红；</li>
 *   <li>{@code recordAttributionGrade} 顺手写 {@code sampled = TRUE} → 同上用例的人审隔离断言红；</li>
 *   <li>采集器不传 questionId → {@code missingQuestionIdIsNeverGraded} 变红；</li>
 *   <li>题面原文被当题号 → {@code freeTextQuestionIdIsNeverGraded} 变红；</li>
 *   <li>题库按文本模糊匹配 → {@code unlistedQuestionIdIsNeverGraded} 变红；</li>
 *   <li>判定器拿不准仍硬判 → {@code lowConfidenceVerdictStaysUngraded} 变红；</li>
 *   <li>机器判分写人审表 / 业务成本表 → 真库双表计数断言变红；</li>
 *   <li>结论写死 true → {@code gradedFalseIsWrittenAsFalse} 变红。</li>
 * </ul>
 *
 * <p>⚠️ 必须 {@code source scripts/it-env.sh} 后跑（连独立 {@code agent_it} 库，
 * 绝不指回应用库）；无 {@code IT_PG_URL} 时整类跳过，避免 CI 假失败。
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
        "agent.worker.tenant-slugs=it-l2-attribution",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // L1 仍真判（提供采集后的 TaskOutcome），但关掉人审抽样噪声。
        "agent.quality.eval.enabled=true",
        "agent.quality.eval.sample-rate=0",
        // L2 双开关显式开启；抽样率 1.0 让每个用例都真的走判定器。
        "agent.quality.eval.llm-judge.enabled=true",
        "agent.quality.eval.llm-judge.sample-rate=1.0",
        "agent.quality.eval.llm-judge.model=MiniMax-M2.5",
        "agent.quality.eval.llm-judge.temperature=0",
        "agent.quality.eval.llm-judge.max-tokens=256",
        "agent.quality.eval.llm-judge.timeout-seconds=10",
        "agent.quality.eval.llm-judge.min-confidence=0.7",
        "agent.quality.eval.llm-judge.daily-cost-limit-yuan=10.00",
        "agent.quality.eval.reference-source.enabled=true",
        "agent.quality.eval.reference-source.location=classpath:agent-eval/reference-answers-it.json"
})
@DisplayName("L2 内容级归因判分闭环（真采集 → 真题库 → 真判定 → 真库回写）")
class L2AttributionClosedLoopIT {

    private static final String SLUG = "it-l2-attribution";
    private static final String QUESTION_ID = "Q-MATH-IT-001";
    private static final String UNLISTED_QUESTION_ID = "Q-MATH-IT-999";
    private static final String SCENARIO = "数学:一元二次方程";
    private static final String QUESTION_TEXT = "解方程 x^2 - 5x + 6 = 0，请说明错因";
    private static final String ATTRIBUTION_TEXT = "学生错在未先化为标准形式，移项时常数项未变号。";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static HikariDataSource adminPool;
    private static String tenantId;
    private static String snapshotId;

    /** 唯一网络出口：生产模型网关具名 Bean，替换它不影响其它同类型 Bean。 */
    @MockitoBean(name = "chatStreamGateway")
    private ChatStreamGateway chatGateway;

    /** 生产采集编排入口（不是 new 出来的，也不是直连 L2 端口）。 */
    @Autowired
    private AgentMetricCollector collector;

    @BeforeAll
    static void setUp() throws SQLException {
        adminPool = pool(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"),
                System.getenv("IT_PG_ADMIN_PASSWORD"));
        tenantId = ItTenantProvisioner.provision(adminPool, SLUG, "L2 判分闭环 IT 专用租户（勿删）");
        snapshotId = ItTenantProvisioner.snapshotId(adminPool, tenantId);
        purge();
    }

    @AfterAll
    static void closePool() throws SQLException {
        purge();
        if (adminPool != null) {
            adminPool.close();
        }
    }

    @BeforeEach
    void resetStubs() throws Exception {
        // ⚠️ 用 doReturn 而非 when(...).thenReturn(...)：后者在已有 answer 的 stub 上
        //    会用 null 实参触发旧 answer，是 Mockito 覆盖真实 Bean 的经典陷阱。
        doReturn(verdict(true, 0.99)).when(chatGateway)
                .streamChat(any(ModelRequest.class), any(ChatStreamGateway.StreamDeltaConsumer.class));
        // ⚠️ 清掉打桩本身留下的调用痕迹：反向用例要靠 verifyNoInteractions 证明
        //    「没到判定器」，不能被打桩动作污染成假红。
        clearInvocations(chatGateway);
        purge();
    }

    @AfterEach
    void cleanup() throws SQLException {
        purge();
    }

    @Test
    @DisplayName("✅ 闭环：questionId 精确命中题库 → 判分 true 只写 attribution_correct，人审/业务成本零污染")
    void gradedTrueWritesOnlyAttributionColumn() throws Exception {
        UUID runId = UUID.randomUUID();
        String traceId = "l2-it-true-" + runId;
        prepare(runId, traceId, payload(QUESTION_ID));
        long usageBefore = usageRows(runId);
        long samplesBefore = evalSampleRows(tenantId);

        assertThat(collector.collect(tenantId, runId))
                .as("采集必须成功，否则后面的判分断言全是空转")
                .contains(traceId);

        ArgumentCaptor<ModelRequest> request = ArgumentCaptor.forClass(ModelRequest.class);
        verify(chatGateway).streamChat(request.capture(),
                any(ChatStreamGateway.StreamDeltaConsumer.class));
        assertJudgeRequest(request.getValue());

        MetricRow row = metric(traceId);
        assertThat(row.attributionCorrect())
                .as("真题库 + 高置信判定 → 必须写出真布尔，不能停在「未评级」")
                .isTrue();
        assertThat(row.scenario()).isEqualTo(SCENARIO);
        assertThat(row.sampled())
                .as("机器判分一旦回写人审抽样列，人工抽样覆盖率就变成假指标")
                .isNull();
        assertThat(row.humanVerdict())
                .as("机器结论不是人工裁定，不能进 false_ok 的分母")
                .isNull();
        assertThat(evalSampleRows(tenantId))
                .as("机器判分绝不写 agent_eval_sample（verdict CHECK 也只收人工三值）")
                .isEqualTo(samplesBefore);
        assertThat(usageRows(runId))
                .as("judge 成本走独立评估科目，绝不写业务 usage_record")
                .isEqualTo(usageBefore);
    }

    @Test
    @DisplayName("✅ 闭环：判定结论为 false 时如实写 false，不发写死 true 的假绿灯")
    void gradedFalseIsWrittenAsFalse() throws Exception {
        doReturn(verdict(false, 0.96)).when(chatGateway)
                .streamChat(any(ModelRequest.class), any(ChatStreamGateway.StreamDeltaConsumer.class));
        clearInvocations(chatGateway);
        UUID runId = UUID.randomUUID();
        String traceId = "l2-it-false-" + runId;
        prepare(runId, traceId, payload(QUESTION_ID));

        assertThat(collector.collect(tenantId, runId)).contains(traceId);
        verify(chatGateway).streamChat(any(ModelRequest.class),
                any(ChatStreamGateway.StreamDeltaConsumer.class));

        assertThat(metric(traceId).attributionCorrect()).isFalse();
    }

    @Test
    @DisplayName("⛔ 缺 questionId：不评级、不请求模型、不写人审表（不猜默认题号）")
    void missingQuestionIdIsNeverGraded() throws Exception {
        UUID runId = UUID.randomUUID();
        String traceId = "l2-it-no-qid-" + runId;
        prepare(runId, traceId, payload(null));

        assertThat(collector.collect(tenantId, runId)).contains(traceId);
        verifyNoInteractions(chatGateway);

        MetricRow row = metric(traceId);
        assertThat(row.attributionCorrect()).isNull();
        assertThat(row.sampled()).isNull();
        assertThat(row.humanVerdict()).isNull();
        assertThat(evalSampleRows(tenantId)).isZero();
    }

    @Test
    @DisplayName("⛔ 题面原文冒充 questionId：按白名单拒绝，绝不退化成题面文本匹配")
    void freeTextQuestionIdIsNeverGraded() throws Exception {
        UUID runId = UUID.randomUUID();
        String traceId = "l2-it-free-text-" + runId;
        prepare(runId, traceId, payload(QUESTION_TEXT));

        assertThat(collector.collect(tenantId, runId)).contains(traceId);
        verifyNoInteractions(chatGateway);
        assertThat(metric(traceId).attributionCorrect()).isNull();
    }

    @Test
    @DisplayName("⛔ 非字符串 questionId（object）：SQL 类型闸门挡住，不把整段 JSON 当题号")
    void nonStringQuestionIdIsNeverGraded() throws Exception {
        UUID runId = UUID.randomUUID();
        String traceId = "l2-it-object-qid-" + runId;
        prepare(runId, traceId, payload(Map.of("nested", "not-an-id")));

        assertThat(collector.collect(tenantId, runId)).contains(traceId);
        verifyNoInteractions(chatGateway);
        assertThat(metric(traceId).attributionCorrect()).isNull();
    }

    @Test
    @DisplayName("⛔ 合法但未收录的 questionId：精确关联未命中，不评级也不请求模型")
    void unlistedQuestionIdIsNeverGraded() throws Exception {
        UUID runId = UUID.randomUUID();
        String traceId = "l2-it-unlisted-" + runId;
        prepare(runId, traceId, payload(UNLISTED_QUESTION_ID));

        assertThat(collector.collect(tenantId, runId)).contains(traceId);
        verifyNoInteractions(chatGateway);
        assertThat(metric(traceId).attributionCorrect()).isNull();
    }

    @Test
    @DisplayName("⛔ 判定器低置信：模型确实被调用，但仍记「待人工」，绝不硬猜 true/false")
    void lowConfidenceVerdictStaysUngraded() throws Exception {
        doReturn(verdict(true, 0.40)).when(chatGateway)
                .streamChat(any(ModelRequest.class), any(ChatStreamGateway.StreamDeltaConsumer.class));
        clearInvocations(chatGateway);
        UUID runId = UUID.randomUUID();
        String traceId = "l2-it-low-confidence-" + runId;
        prepare(runId, traceId, payload(QUESTION_ID));

        assertThat(collector.collect(tenantId, runId)).contains(traceId);
        verify(chatGateway).streamChat(any(ModelRequest.class),
                any(ChatStreamGateway.StreamDeltaConsumer.class));
        assertThat(metric(traceId).attributionCorrect()).isNull();
        assertThat(evalSampleRows(tenantId)).isZero();
    }

    // ── 判定器请求形状：确保真走 MiniMax rubric，且三段外部文本都围栏 ──

    private static void assertJudgeRequest(ModelRequest request) {
        assertThat(request.modelId()).isEqualTo("MiniMax-M2.5");
        assertThat(request.temperature()).isEqualTo(0.0);
        assertThat(request.maxTokens()).isEqualTo(256);
        assertThat(request.toolSchemas()).isEmpty();
        assertThat(request.systemPrompt()).contains("判分员");
        assertThat(request.userContent())
                .contains("【学生题面】", "【Agent 错因归因】", "【题库标准解析】")
                .contains("<tool_result trust=\"untrusted\" tool=\"question\">")
                .contains("<tool_result trust=\"untrusted\" tool=\"agent-attribution\">")
                .contains("<tool_result trust=\"untrusted\" tool=\"standard-analysis\">")
                .contains(QUESTION_TEXT)
                .contains(ATTRIBUTION_TEXT)
                .as("标准解析必须真的从本地题库流进判定器，不能是空串占位")
                .contains("因式分解");
    }

    private static ModelResponse verdict(boolean correct, double confidence) {
        String json = "{\"correct\":" + correct
                + ",\"reason\":\"归因与标准解析实质一致\",\"confidence\":" + confidence + "}";
        return new ModelResponse(json, Map.of(), new ModelUsage(120, 30), FinishReason.STOP);
    }

    // ── 造数 ────────────────────────────────────────────────────────

    private static String payload(Object questionId) throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", QUESTION_TEXT);
        input.put("scenario", SCENARIO);
        if (questionId != null) {
            input.put("questionId", questionId);
        }
        return JSON.writeValueAsString(input);
    }

    private void prepare(UUID runId, String traceId, String inputPayload) throws Exception {
        String userContent = JSON.writeValueAsString(Map.of("text", QUESTION_TEXT));
        String assistantContent = JSON.writeValueAsString(Map.of(
                "text", ATTRIBUTION_TEXT,
                "toolCalls", java.util.List.of(),
                "finishReason", "STOP"));
        try (Connection conn = adminPool.getConnection()) {
            conn.setAutoCommit(false);
            try {
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
                    ps.setString(4, "l2-it-idem-" + runId);
                    ps.setString(5, "l2-it-digest-" + runId);
                    ps.setString(6, inputPayload);
                    ps.setString(7, "l2-it-corr-" + runId);
                    ps.setString(8, traceId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO public.usage_record
                            (tenant_id, run_id, model_id, input_tokens, output_tokens, created_at)
                        VALUES (?::uuid, ?::uuid, 'MiniMax-M2.5', 100, 50, now())
                        """)) {
                    ps.setString(1, tenantId);
                    ps.setString(2, runId.toString());
                    ps.executeUpdate();
                }
                insertMessage(conn, runId, traceId, "USER", userContent, "l2-it-user-" + runId);
                insertMessage(conn, runId, traceId, "ASSISTANT", assistantContent,
                        "l2-it-assistant-" + runId);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static void insertMessage(Connection conn, UUID runId, String traceId,
                                      String role, String content, String idemKey)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
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

    // ── 真库断言 ────────────────────────────────────────────────────

    private record MetricRow(Boolean attributionCorrect, Boolean sampled,
                             String humanVerdict, String scenario) {
    }

    private static MetricRow metric(String traceId) throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT attribution_correct, sampled, human_verdict, scenario
                     FROM platform.agent_trace_metric
                     WHERE trace_id = ? AND tenant_id = ?::uuid
                     """)) {
            ps.setString(1, traceId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("采集后必须有该 trace 的宽表行").isTrue();
                return new MetricRow((Boolean) rs.getObject(1), (Boolean) rs.getObject(2),
                        rs.getString(3), rs.getString(4));
            }
        }
    }

    private static long usageRows(UUID runId) throws SQLException {
        return count("SELECT count(*) FROM public.usage_record "
                + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid", tenantId, runId.toString());
    }

    private static long evalSampleRows(String tenant) throws SQLException {
        return count("SELECT count(*) FROM platform.agent_eval_sample WHERE tenant_id = ?::uuid",
                tenant);
    }

    private static long count(String sql, String... args) throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 清理：FORCE RLS 下所有 owned 表删除都必须带 tenant_id ───────

    private static void purge() throws SQLException {
        exec("DELETE FROM platform.agent_eval_sample WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM platform.agent_task_outcome WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM platform.review_task WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM platform.agent_trace_metric WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_message WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.usage_record WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_event WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run_step WHERE tenant_id = ?::uuid", tenantId);
        exec("DELETE FROM public.run WHERE tenant_id = ?::uuid", tenantId);
    }

    private static void exec(String sql, String... args) throws SQLException {
        try (Connection conn = adminPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static HikariDataSource pool(String user, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(user);
        if (password != null && !password.isBlank()) {
            cfg.setPassword(password);
        }
        cfg.setMaximumPoolSize(3);
        cfg.setPoolName("l2-attribution-it-admin");
        return new HikariDataSource(cfg);
    }
}
