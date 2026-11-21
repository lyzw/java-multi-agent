package cn.studykid.multiagent.bootstrap.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.alibaba.druid.pool.DruidDataSource;

import tools.jackson.databind.JsonNode;

import cn.studykid.multiagent.bootstrap.PlatformApplication;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.infrastructure.security.PlatformJwtTokenService;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort.RagResult;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.model.ModelResponse.FinishReason;
import cn.studykid.multiagent.ports.model.ModelResponse.ModelUsage;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * S3-M15-b：<b>L2 SSE 100 条 history 真链路预算内</b>（独立库真跑）。
 *
 * <p>本类堵的不是「{@code CompactionPipeline} 单测 before>after」，而是
 * 「算法单测全绿、真 HTTP + 真编排却把 100 轮全量历史原样送模型 / 用罐头 usage 假装在预算内」。
 * 故从 {@code POST /api/agent/chat/stream} 真入口进，只替换两个网络出口
 * （模型网关 / rag 服务），其余全真：真 Security + JWT + Controller + 原生 SSE 帧 +
 * 真 Orchestrator + 真统一装配 + 真水位判定 + 真 HMAC 轮末封包。
 *
 * <p><b>为什么不能靠查表证明「预算内」</b>：L2 SSE 不写 {@code usage_record} /
 * {@code run_message}，所以本类把模型桩的 {@code usage.inputTokens} 改成
 * <b>用真实 {@link ModelRequest} 经 {@code HeuristicTokenCounter} 现算</b>（不是固定 11），
 * 再与「关闭态基线请求」的独立重算值比对。断言链是：
 * <ol>
 *   <li>捕获真送模型的 {@code ModelRequest}；</li>
 *   <li>用 {@code AgentChainItSupport.withMessages(捕获请求, 全量历史)} 重造「如果没装配压缩
 *       会送出的那条 legacy 请求」，独立算出 {@code before}（不调用被测实现）；</li>
 *   <li>断言审计 {@code CONTEXT_ASSEMBLED.beforeTokens == before}、
 *       {@code afterTokens == after(捕获请求)}——<b>数字算对了</b>，不只是「有一行」；</li>
 *   <li>断言 tier=2（真命中 W2）、真实净省 ≥20%、SSE {@code usage} 帧按裁剪后请求计费。</li>
 * </ol>
 *
 * <p><b>挤出压力夹具</b>：50 对 user/assistant = 100 条 history（恰为编排器硬上限）。
 * {@code RoundSplitter} 把 USER 判为 ANCHOR 永不裁，故 USER 只放短「必须C{i}」——
 * 既压低不可裁成本，又带确定性逐字抽取器可吸收的「必须」标记，让轮末
 * {@code context.checkpoint} 真能发射；ASSISTANT 用 ASCII 填充，是唯一可整轮裁剪的 TURN。
 * 按本地计算器（逐字复刻 {@code HeuristicTokenCounter} + {@code ContextLayout.countRendered}
 * + {@code pruneRounds}）在 window=4096 / trigger=0.70 / target=0.40 / reserve=0.10 下
 * 取 {@code ASSISTANT_CHARS=144}：before≈3289（usage≈0.803，稳落 W2：
 * 离 W2 线 2867 有 +422、离 W3 线 3686 有 −397）、after≈1894（含 notice 围栏）、
 * 真省≈1395（约 42%）、不触发二次硬裁（after 远低于 hardLimit=3686）。
 *
 * <p><b>压力门控反证</b>：第三个用例用短 history（远低于触发线）走同一条真链，断言
 * W0 不裁剪、before==after、不发第 14 事件、Done 退回 v1——证明 W2 行为是<b>压力触发</b>的，
 * 不是「无条件砍消息」或「固定返回一条裁剪审计」的假绿。
 *
 * <p>⚠️ 必须 {@code source scripts/it-env.sh} 后跑；无 {@code IT_PG_URL} 整类跳过。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(
        classes = PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        "auth.platform-jwt.issuer=https://auth.test",
        "auth.platform-jwt.audience=admin-console-api",
        "auth.platform-jwt.token-ttl-seconds=900",
        "agent.worker.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "agent.context.enabled=true",
        "agent.context.state-hmac-key=" + SseHistory100TurnsWithinBudgetIT.STATE_KEY,
        "agent.context.max-state-chars=8192",
        "agent.context.checkpoint-ttl-seconds=1800",
        "agent.memory.enabled=false"
})
@DisplayName("S3-M15-b：L2 SSE 100 条 history 真链路预算内（W2 真裁剪 + 线序 + 租户链 thinking=0 + W0 门控）")
class SseHistory100TurnsWithinBudgetIT {

    /** 与 @TestPropertySource / 支撑类逐字一致；只验签，绝不打印。 */
    static final String STATE_KEY = "m15-chain-it-independent-state-hmac-key";

    private static final String SLUG = "it-m15-sse-100";
    private static final String AGENT_ID = "a15c0000-0000-4000-8000-000000000002";
    private static final String AGENT_NAME = "m15-sse-100-tutor";
    private static final String MODEL_ID = "m15-sse-100-fake";
    private static final String MODEL_SCENARIO = "m15_sse_100_it";

    /**
     * 有效输入窗：agent 策略 / 模型目录 / 全局硬顶三者取最小，此处三者均 ≥4096。
     * ⚠️ 下界 2048 来自 {@code ContextPolicyValidator}，低于它会被 fail-closed 拒绝；
     *    取 4096 让 W2 触发线（0.70×4096=2867）与 W3 线（0.90×4096=3686）有充足夹具带宽。
     */
    private static final int WINDOW = 4_096;
    /** 模型目录契约：总窗口 ≥ 4096 且输入硬顶 ≤ 总窗口 − 4096（输出预留）。 */
    private static final int MODEL_CONTEXT_WINDOW = 12_288;
    private static final double TRIGGER_RATIO = 0.70;
    private static final double TARGET_RATIO = 0.40;
    private static final double RESERVE_RATIO = 0.10;
    private static final int HARD_LIMIT = WINDOW - (int) Math.ceil(WINDOW * RESERVE_RATIO);

    private static final int HISTORY_PAIRS = 50;
    private static final int HISTORY_TURNS = HISTORY_PAIRS * 2;
    private static final int ASSISTANT_CHARS = 144;
    private static final int MAX_OUTPUT_TOKENS = 512;
    private static final String MESSAGE = "请继续回答";
    private static final String ANSWER = "已按预算内上下文回答";
    /** 桩必须真的下发 thinking，否则「租户链 thinking=0」是空转断言。 */
    private static final String THINKING = "推理片段";

    private static DruidDataSource adminPool;
    private static DruidDataSource appPool;
    private static String tenantId;

    private static final List<ModelRequest> captured = new CopyOnWriteArrayList<>();

    @Autowired
    private RestTestClient rest;
    @Autowired
    private PlatformJwtTokenService platformTokens;
    @Autowired
    private JwtTokenService tenantTokens;

    @MockitoBean(name = "chatStreamGateway")
    private ChatStreamGateway chatGateway;
    @MockitoBean
    private RagRetrievalPort rag;

    @BeforeAll
    static void setUp() throws Exception {
        adminPool = AgentChainItSupport.pool(
                System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"),
                System.getenv("IT_PG_ADMIN_PASSWORD"));
        appPool = AgentChainItSupport.pool(
                System.getenv().getOrDefault("IT_PG_USER", "app_agent"),
                System.getenv("IT_PG_PASSWORD"));
        tenantId = AgentChainItSupport.provision(adminPool, SLUG);
        AgentChainItSupport.seedAgent(adminPool, tenantId, AGENT_ID, AGENT_NAME, MODEL_ID,
                AgentChainItSupport.policyJson(true, "TIERED", WINDOW,
                        TRIGGER_RATIO, TARGET_RATIO, 6));
        AgentChainItSupport.upsertModelCatalog(adminPool, MODEL_ID, MODEL_SCENARIO,
                MODEL_CONTEXT_WINDOW, WINDOW, "M15 L2 SSE 100 轮预算 IT 夹具模型");
    }

    @AfterAll
    static void closePools() {
        if (adminPool != null) {
            adminPool.close();
        }
        if (appPool != null) {
            appPool.close();
        }
    }

    @BeforeEach
    void resetStubs() throws Exception {
        captured.clear();
        AgentChainItSupport.resetTenantRuns(adminPool, tenantId);
        // 零命中是合法 info：隔离「RAG 围栏块」变量，让挤出压力只来自 history。
        doReturn(noHits()).when(rag).retrieve(anyString(), anyString(), anyString(), anyInt());
        doAnswer(inv -> {
            ChatStreamGateway.StreamDeltaConsumer consumer = inv.getArgument(1);
            ModelRequest request = (ModelRequest) inv.getArgument(0);
            captured.add(request);
            // ⚠️ 按真实请求计费（不是罐头 usage）：usage 帧 = 裁剪后请求的全窗口 token。
            int inputTokens = AgentChainItSupport.tokens(request);
            consumer.onThinking(THINKING);
            consumer.onDelta(ANSWER);
            return new ModelResponse(ANSWER, Map.of(),
                    new ModelUsage(inputTokens, 7), FinishReason.STOP);
        }).when(chatGateway).streamChat(any(), any());
    }

    // ════════════════════════════════════════════════════════════════
    // ① 主链路：100 条 history 在 W2 真裁剪，before/after 数字独立复算一致
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✅ 100 条 history：真命中 W2、真省 token、usage 按裁剪后请求计费、第14事件+Done 版本线序")
    void hundredTurnHistoryIsTrimmedWithinBudget() throws Exception {
        List<Map<String, String>> history =
                AgentChainItSupport.pressureDialogue(HISTORY_PAIRS, ASSISTANT_CHARS);

        AgentChainItSupport.SseFrames frames = post(AgentChainItSupport.PLATFORM_PATH,
                platformTokens.issue(new PlatformPrincipal("it-m15-sse-100",
                        Set.of("PLATFORM_ADMIN"), 1L, true), Instant.now()),
                history, tenantId);

        assertCleanSuccess(frames);
        // 平台链桩必须真的下发 thinking（否则租户链 thinking=0 是空转断言）。
        assertThat(frames.count("thinking"))
                .as("平台链必须可见 thinking，否则租户链 suppression 断言无对照")
                .isPositive();
        assertThat(frames.order).contains("run.start", "route", "rag.query", "rag.hits",
                "answer", "usage", "context.checkpoint", "done");
        assertThat(frames.order.subList(frames.order.size() - 4, frames.order.size()))
                .as("D49 线序：answer → usage → context.checkpoint → done（done 仍是唯一终帧）")
                .containsExactly("answer", "usage", "context.checkpoint", "done");

        assertThat(captured).as("必须真的捕获到送模型请求，否则所有 token 断言空转").hasSize(1);
        ModelRequest assembled = captured.get(0);
        List<ModelMessage> full = AgentChainItSupport.fullDialogueMessages(history, MESSAGE);
        ModelRequest legacy = AgentChainItSupport.withMessages(assembled, full);
        int before = AgentChainItSupport.tokens(legacy);
        int after = AgentChainItSupport.tokens(assembled);

        // ── 挤出压力确实落在 W2 区间（不是 W0/W1 空转，也不是 W3/HARD fail-closed）──
        assertThat(before)
                .as("夹具必须真的挤出 W2 压力：before/window 应 ≥ trigger 且 < W3 触发线")
                .isGreaterThanOrEqualTo((int) (WINDOW * TRIGGER_RATIO))
                .isLessThan((int) (WINDOW * 0.90));
        assertThat(after)
                .as("真裁剪必须净省 ≥20% token；相等/微降说明装配空转或桩固定 usage")
                .isLessThan(before * 4 / 5)
                .as("裁剪后仍在输入硬顶内（预算内）")
                .isLessThanOrEqualTo(HARD_LIMIT);

        // ── 只切上下文字段：固定块沿用夹具真值，USER 锚逐字保留，ASSISTANT 整轮被裁 ──
        assertThat(assembled.systemPrompt()).isEqualTo(AgentChainItSupport.SYSTEM_PROMPT);
        assertThat(assembled.maxTokens()).isEqualTo(MAX_OUTPUT_TOKENS);
        assertThat(assembled.toolSchemas()).isEmpty();
        assertThat(assembled.userContent()).isEmpty();
        List<String> contents = assembled.effectiveMessages().stream()
                .map(ModelMessage::content).toList();
        for (int i = 0; i < HISTORY_PAIRS; i++) {
            assertThat(contents).as("USER 是 ANCHOR，第 %d 条必须逐字保留", i)
                    .contains("必须C" + i);
        }
        assertThat(contents).contains(MESSAGE);
        long keptAssistant = assembled.effectiveMessages().stream()
                .filter(m -> m.role() == ModelMessage.Role.ASSISTANT).count();
        assertThat(keptAssistant)
                .as("必须有整轮 ASSISTANT 被裁（保留少数近期轮），否则不是 W2 整轮裁剪")
                .isBetween(1L, (long) HISTORY_PAIRS - 1);
        AgentChainItSupport.assertCompactedFence("W2 裁剪说明", captured, true);
        assertThat(contents.stream().filter(c -> c.contains("[trim_notice]")).count())
                .as("裁剪说明必须作为不可信 USER 围栏块出现，不进 systemPrompt")
                .isEqualTo(1);

        // ── SSE usage 帧按真实裁剪后请求计费（不是固定桩值）──
        JsonNode usage = frames.json("usage");
        assertThat(usage.get("inputTokens").asLong())
                .as("usage.inputTokens 必须等于捕获请求的重算 token（按真实请求计费）")
                .isEqualTo(after);
        assertThat(usage.get("outputTokens").asLong()).isEqualTo(7);

        // ── 审计数字独立复算：before/after 不是「有一行」，而是「算对了」──
        String runId = frames.json("run.start").get("runId").asString();
        JsonNode input = AgentChainItSupport.JSON.readTree(
                AgentChainItSupport.runRow(appPool, tenantId, runId).inputPayload());
        assertThat(input.get("historyTurns").asInt()).isEqualTo(HISTORY_TURNS);
        List<AgentChainItSupport.EventRow> events =
                AgentChainItSupport.eventRows(appPool, tenantId, runId);
        JsonNode a = audit(events, "CONTEXT_ASSEMBLED");
        assertThat(a.get("tier").asInt()).as("真命中 W2").isEqualTo(2);
        assertThat(a.get("fallback").asBoolean()).isFalse();
        assertThat(a.get("beforeTokens").asInt()).as("审计 before 必须等于独立重算的关闭态基线")
                .isEqualTo(before);
        assertThat(a.get("afterTokens").asInt()).as("审计 after 必须等于捕获请求的重算值")
                .isEqualTo(after);
        assertThat(a.get("compactionCount").asInt()).isGreaterThanOrEqualTo(1);

        // ── 第 14 事件 / Done 版本同源 / 不透明 / 审计不写正文 ──
        JsonNode cp = frames.json("context.checkpoint");
        JsonNode done = frames.json("done");
        assertThat(cp.get("tier").asInt()).isEqualTo(2);
        assertThat(cp.get("fallback").asBoolean()).isFalse();
        assertThat(cp.get("upToTurn").asInt()).isEqualTo(HISTORY_TURNS);
        assertThat(cp.get("state").asString()).isNotBlank()
                .hasSizeLessThanOrEqualTo(AgentChainItSupport.MAX_STATE_CHARS);
        assertThat(done.get("contextStateVersion").asString())
                .as("Done 版本必须与 checkpoint digest 同源")
                .isEqualTo(cp.get("digest").asString());
        assertThat(frames.raw).as("state 是不透明信封，SSE 不得泄漏逐字历史")
                .doesNotContain("必须C");
        JsonNode cpAudit = audit(events, "CONTEXT_CHECKPOINT");
        assertThat(cpAudit.get("digest").asString()).isEqualTo(cp.get("digest").asString());
        assertThat(cpAudit.toString()).as("审计只记标量事实，绝不写 state 正文 / 逐字历史")
                .doesNotContain("必须C", cp.get("state").asString());
    }

    // ════════════════════════════════════════════════════════════════
    // ② 租户链：同一预算保护下 thinking 恒为 0（平台链 ① 已证桩真下发）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ 租户链 thinking=0：同一 100 轮挤出压力下仍真裁剪，但推理草稿从源头丢弃")
    void tenantChainSuppressesThinkingUnderSameBudget() throws Exception {
        List<Map<String, String>> history =
                AgentChainItSupport.pressureDialogue(HISTORY_PAIRS, ASSISTANT_CHARS);

        AgentChainItSupport.SseFrames frames = post(AgentChainItSupport.TENANT_PATH,
                AgentChainItSupport.tenantToken(tenantTokens, tenantId), history, null);

        assertCleanSuccess(frames);
        assertThat(frames.count("thinking"))
                .as("租户通道恒不下发推理草稿（平台链 ① 已断言桩真下发 thinking）")
                .isZero();
        assertThat(captured).as("租户链也必须真捕获送模型请求").hasSize(1);
        ModelRequest assembled = captured.get(0);
        List<ModelMessage> full = AgentChainItSupport.fullDialogueMessages(history, MESSAGE);
        int before = AgentChainItSupport.tokens(AgentChainItSupport.withMessages(assembled, full));
        int after = AgentChainItSupport.tokens(assembled);
        assertThat(after).as("租户链的预算保护同样必须真省 token").isLessThan(before * 4 / 5);
        assertThat(frames.json("usage").get("inputTokens").asLong()).isEqualTo(after);
        String runId = frames.json("run.start").get("runId").asString();
        JsonNode a = audit(AgentChainItSupport.eventRows(appPool, tenantId, runId),
                "CONTEXT_ASSEMBLED");
        assertThat(a.get("tier").asInt()).isEqualTo(2);
        assertThat(a.get("beforeTokens").asInt()).isEqualTo(before);
        assertThat(a.get("afterTokens").asInt()).isEqualTo(after);
    }

    // ════════════════════════════════════════════════════════════════
    // ③ 压力门控对照：短历史 W0 不裁剪、before==after、不发第14事件、Done=v1
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✅ 无挤出压力：短历史 W0 逐字等价，不裁剪 / 不发第14事件 / Done 退回 v1")
    void shortHistoryStaysUntrimmedWithoutCheckpoint() throws Exception {
        List<Map<String, String>> history = AgentChainItSupport.shortDialogue(3);

        AgentChainItSupport.SseFrames frames = post(AgentChainItSupport.PLATFORM_PATH,
                platformTokens.issue(new PlatformPrincipal("it-m15-sse-100",
                        Set.of("PLATFORM_ADMIN"), 1L, true), Instant.now()),
                history, tenantId);

        assertCleanSuccess(frames);
        assertThat(frames.order).doesNotContain("context.checkpoint");
        assertThat(frames.json("done").has("contextStateVersion"))
                .as("W0 无 checkpoint，Done 必须退回 v1 字节")
                .isFalse();

        assertThat(captured).hasSize(1);
        ModelRequest assembled = captured.get(0);
        List<ModelMessage> full = AgentChainItSupport.fullDialogueMessages(history, MESSAGE);
        assertThat(assembled.effectiveMessages().stream().map(ModelMessage::content).toList())
                .as("W0 不裁剪：送模型消息逐字等于关闭态基线")
                .containsExactlyElementsOf(full.stream().map(ModelMessage::content).toList());
        AgentChainItSupport.assertCompactedFence("W0 不裁剪", captured, false);
        int before = AgentChainItSupport.tokens(AgentChainItSupport.withMessages(assembled, full));
        int after = AgentChainItSupport.tokens(assembled);
        assertThat(after).as("W0 不省也不增 token").isEqualTo(before);

        String runId = frames.json("run.start").get("runId").asString();
        JsonNode a = audit(AgentChainItSupport.eventRows(appPool, tenantId, runId),
                "CONTEXT_ASSEMBLED");
        assertThat(a.get("tier").asInt()).isZero();
        assertThat(a.get("beforeTokens").asInt()).isEqualTo(a.get("afterTokens").asInt());
        assertThat(a.get("beforeTokens").asInt()).isEqualTo(before);
    }

    // ── 封装：HTTP / 断言 / 夹具 ────────────────────────────────────

    private AgentChainItSupport.SseFrames post(String path, String token,
                                               List<Map<String, String>> history,
                                               String bodyTenant) {
        String body = AgentChainItSupport.agentChatBody(
                MESSAGE, history, AGENT_ID, null, bodyTenant);
        return AgentChainItSupport.postSse(rest, path, token, body);
    }

    private void assertCleanSuccess(AgentChainItSupport.SseFrames frames) throws Exception {
        assertThat(frames.order).as("预算保护不得发 error 帧").doesNotContain("error");
        assertThat(frames.order).endsWith("done");
        assertThat(frames.count("done")).as("done 是唯一应用层终帧").isEqualTo(1);
        JsonNode done = frames.json("done");
        assertThat(done.get("truncated").asBoolean()).isFalse();
        String runId = frames.json("run.start").get("runId").asString();
        var run = AgentChainItSupport.runRow(appPool, tenantId, runId);
        assertThat(run.state()).as("裁剪/不裁剪都必须整轮成功").isEqualTo("SUCCEEDED");
        assertThat(run.errorCode()).isNull();
        assertThat(AgentChainItSupport.countOutbox(appPool, tenantId, runId))
                .as("请求级 SSE 对话绝不写 outbox").isZero();
    }

    private static JsonNode audit(List<AgentChainItSupport.EventRow> events, String type) {
        List<AgentChainItSupport.EventRow> hits = new ArrayList<>();
        for (AgentChainItSupport.EventRow row : events) {
            if (type.equals(row.eventType())) {
                hits.add(row);
            }
        }
        assertThat(hits).as("审计事件 %s 必须恰好 1 条", type).hasSize(1);
        return AgentChainItSupport.JSON.readTree(hits.get(0).payload());
    }

    private static RagResult noHits() {
        return new RagResult(List.of(), false, false, "数学", 0);
    }
}
