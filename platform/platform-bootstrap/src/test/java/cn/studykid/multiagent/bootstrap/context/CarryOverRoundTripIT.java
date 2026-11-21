package cn.studykid.multiagent.bootstrap.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import cn.studykid.multiagent.context.ContextStateEnvelope;
import cn.studykid.multiagent.context.StructuredState;
import cn.studykid.multiagent.context.StructuredStateCodec;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.infrastructure.security.PlatformJwtTokenService;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.ContextPolicyVersionPort;
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
 * S3-M15-a：<b>L2 SSE client carry-over 真链路往返</b>（独立库真跑）。
 *
 * <p>本类要堵的不是「{@code ContextStateEnvelope.verify} 单测六项全绿」，而是
 * 「单测全绿、真 HTTP + 真编排链路上客户端回传的 {@code contextState} 却没有
 * 真正被接受/丢弃」这类静默失真。故从 {@code POST /api/agent/chat/stream}
 * 真入口进，只替换两个网络出口（模型网关 / rag 服务），其余真：
 * <ol>
 *   <li>真 Spring Security + JWT + 真 Controller + 原生 SSE 帧；</li>
 *   <li>真 Orchestrator：路由 → 六项不可信校验 → RAG → 统一装配 → 围栏注入 → 审计；</li>
 *   <li>真 {@code ContextPolicyVersionPort} 裁决策略指纹（正例 state 用它封包，
 *       ⛔ 不硬编码指纹——那会让「裁决与校验不同源」的失真测不出来）；</li>
 *   <li>真 HMAC 信封封/验包，独立密钥由 {@code @TestPropertySource} 注入；</li>
 *   <li>捕获真送模型的 {@code ModelRequest}，逐字断言围栏注入 / 全量历史降级。</li>
 * </ol>
 *
 * <p><b>六项静默丢弃的可观测口径</b>：{@code DropReason} 只进 {@code log.debug}，不进
 * SSE / 审计 / 库、不发 {@code error} 帧。因此每组反例只能断言<b>行为差异</b>：
 * 带 state 的请求与无 state 的请求在「捕获的 ModelRequest」上逐字等价（全量历史、
 * 无 {@code compacted_context} 围栏），且整轮 SUCCEEDED / outbox=0 / 无 error 帧。
 * 正例则必须出现恰好一条 {@code compacted_context trust="untrusted"} 围栏 USER 消息。
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
        "agent.worker.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // ⚠️ 本类核心：打开统一装配 + 注入独立 HMAC 密钥（与支撑类常量逐字一致）。
        "agent.context.enabled=true",
        "agent.context.state-hmac-key=" + CarryOverRoundTripIT.STATE_KEY,
        "agent.context.max-state-chars=8192",
        "agent.context.checkpoint-ttl-seconds=1800",
        "agent.memory.enabled=false"
})
@DisplayName("S3-M15-a：L2 SSE carry-over 真链路往返（六项静默丢弃 + 正例围栏）")
class CarryOverRoundTripIT {

    /** 与 @TestPropertySource 逐字一致；只验签，绝不打印。 */
    static final String STATE_KEY = "m15-chain-it-independent-state-hmac-key";

    private static final String SLUG = "it-m15-carryover";
    private static final String AGENT_ID = "a15c0000-0000-4000-8000-000000000001";
    private static final String AGENT_NAME = "m15-carryover-tutor";
    private static final String MODEL_ID = "m15-carryover-fake";
    private static final String MODEL_SCENARIO = "m15_carryover_it";
    private static final String ANSWER = "迁移项与同类项合并";
    private static final String RAG_MARKER = "RAG_M15_CARRYOVER_MARKER";
    /** 短历史：不制造装配挤出压力，隔离「carry-over 净化」与「压缩裁剪」两个变量。 */
    private static final int HISTORY_PAIRS = 3;
    private static final int MAX_OUTPUT_TOKENS = 512;

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
    @Autowired
    private ContextPolicyVersionPort policyVersions;

    @MockitoBean(name = "chatStreamGateway")
    private ChatStreamGateway chatGateway;
    @MockitoBean
    private RagRetrievalPort rag;

    /** 每个用例按真实裁决端口取一次策略指纹（实例字段，避免 @BeforeAll 静态注入顺序陷阱）。 */
    private String policyVersion;

    @BeforeAll
    static void setUp() throws Exception {
        adminPool = AgentChainItSupport.pool(
                System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"),
                System.getenv("IT_PG_ADMIN_PASSWORD"));
        appPool = AgentChainItSupport.pool(
                System.getenv().getOrDefault("IT_PG_USER", "app_agent"),
                System.getenv("IT_PG_PASSWORD"));
        tenantId = AgentChainItSupport.provision(adminPool, SLUG);
        String generationParams = AgentChainItSupport.policyJson(
                true, "TIERED", 16_000, 0.70, 0.40, 6);
        AgentChainItSupport.seedAgent(
                adminPool, tenantId, AGENT_ID, AGENT_NAME, MODEL_ID, generationParams);
        AgentChainItSupport.upsertModelCatalog(
                adminPool, MODEL_ID, MODEL_SCENARIO, 32_768, 16_000,
                "M15 L2 carry-over 往返 IT 夹具模型");
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
        // 与编排器完全相同的入参裁决策略指纹：证明正例 state 与接收侧同源。
        policyVersion = policyVersions.resolve(new ContextPolicyVersionPort.Input(
                tenantId, AGENT_ID, 1, MODEL_ID, MAX_OUTPUT_TOKENS));
        assertThat(policyVersion)
                .as("开启态必须裁决出非空 policyVersion，否则正例 state 无法构造（fail-closed）")
                .isNotBlank();
        doReturn(ragWithHit()).when(rag).retrieve(anyString(), anyString(), anyString(), anyInt());
        doAnswer(inv -> {
            ChatStreamGateway.StreamDeltaConsumer consumer = inv.getArgument(1);
            captured.add((ModelRequest) inv.getArgument(0));
            consumer.onDelta(ANSWER);
            return new ModelResponse(ANSWER, java.util.Map.of(),
                    new ModelUsage(11, 7), FinishReason.STOP);
        }).when(chatGateway).streamChat(any(), any());
    }

    // ════════════════════════════════════════════════════════════════
    // 正例：合法 state → 已验状态 JSON 作为不可信围栏 USER 块注入
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✅ 合法 carry-over：真 HMAC 信封被接受，模型请求出现且只出现一条 compacted_context 围栏")
    void validCarryOverInjectsCompactedFence() throws Exception {
        List<Map<String, String>> history = AgentChainItSupport.shortDialogue(HISTORY_PAIRS);
        String state = seal(policyVersion, tenantId, null,
                Instant.now().getEpochSecond(), STATE_KEY.getBytes(StandardCharsets.UTF_8));

        AgentChainItSupport.SseFrames frames = post(history, state, tenantId);

        assertCleanSuccess(frames);
        assertThat(frames.order).contains("answer", "usage", "done");
        AgentChainItSupport.assertCompactedFence("合法 carry-over", captured, true);
        // 围栏块带已验状态 JSON 的关键字段 + untrusted 声明（逐字 stateJson 片段）。
        String fenced = captured.get(0).effectiveMessages().stream()
                .map(ModelMessage::content)
                .filter(c -> c.contains(AgentChainItSupport.COMPACTED_FENCE))
                .findFirst().orElse("");
        assertThat(fenced)
                .as("围栏必须声明 untrusted 且带已验状态 JSON 的 policyVersion")
                .contains("untrusted", policyVersion, "\"goal\"");
    }

    // ════════════════════════════════════════════════════════════════
    // 六组静默丢弃：任一不可信校验失败 → 回落全量历史、无围栏、无 error 帧
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ HMAC 篡改：验签失败 → 静默回落全量历史，无围栏、无 error 帧、Run 成功")
    void hmacMismatchFallsBackToFullHistory() throws Exception {
        List<Map<String, String>> history = AgentChainItSupport.shortDialogue(HISTORY_PAIRS);
        String state = seal(policyVersion, tenantId, null,
                Instant.now().getEpochSecond(), STATE_KEY.getBytes(StandardCharsets.UTF_8));
        assertSilentDiscard("HMAC 篡改", history, tamperSignature(state), tenantId);
    }

    @Test
    @DisplayName("⛔ 过期：exp 已到 → 静默回落全量历史")
    void expiredFallsBackToFullHistory() throws Exception {
        List<Map<String, String>> history = AgentChainItSupport.shortDialogue(HISTORY_PAIRS);
        long longAgo = Instant.now().minusSeconds(3_600).getEpochSecond();
        String state = sealWithTtl(policyVersion, tenantId, null, longAgo, 1,
                STATE_KEY.getBytes(StandardCharsets.UTF_8));
        assertSilentDiscard("过期", history, state, tenantId);
    }

    @Test
    @DisplayName("⛔ policyVersion 不符：策略指纹漂移 → 静默回落全量历史")
    void policyVersionMismatchFallsBackToFullHistory() throws Exception {
        List<Map<String, String>> history = AgentChainItSupport.shortDialogue(HISTORY_PAIRS);
        // 信封 policyVersion 与 stateJson 内必须一致（否则 seal 自身拒绝），
        // 故用「另一个合法但错误的版本」封包，模拟客户端持有旧策略的 state。
        String state = seal("stale-policy-version", tenantId, null,
                Instant.now().getEpochSecond(), STATE_KEY.getBytes(StandardCharsets.UTF_8));
        assertSilentDiscard("policyVersion 不符", history, state, tenantId);
    }

    @Test
    @DisplayName("⛔ 超长：state 超过 max-state-chars → 静默回落全量历史")
    void tooLargeFallsBackToFullHistory() throws Exception {
        List<Map<String, String>> history = AgentChainItSupport.shortDialogue(HISTORY_PAIRS);
        String state = sealOversized(tenantId);
        assertThat(state.length())
                .as("夹具必须真的超长，否则本用例空转").isGreaterThan(AgentChainItSupport.MAX_STATE_CHARS);
        assertSilentDiscard("超长", history, state, tenantId);
    }

    @Test
    @DisplayName("⛔ upToTurn 越界：游标超过本轮历史轮数 → 静默回落全量历史")
    void turnCursorAheadFallsBackToFullHistory() throws Exception {
        List<Map<String, String>> history = AgentChainItSupport.shortDialogue(HISTORY_PAIRS);
        // 游标 = history+1，服务端 verify 时 historyTurns=history.size() → TURN_CURSOR_AHEAD。
        String state = seal(policyVersion, tenantId, history.size() + 1,
                Instant.now().getEpochSecond(), STATE_KEY.getBytes(StandardCharsets.UTF_8));
        assertSilentDiscard("upToTurn 越界", history, state, tenantId);
    }

    @Test
    @DisplayName("⛔ tenant 不匹配：租户摘要不符 → 静默回落全量历史（跨租户 state 不可复用）")
    void tenantMismatchFallsBackToFullHistory() throws Exception {
        List<Map<String, String>> history = AgentChainItSupport.shortDialogue(HISTORY_PAIRS);
        String otherTenant = UUID.randomUUID().toString();
        String state = seal(policyVersion, otherTenant, null,
                Instant.now().getEpochSecond(), STATE_KEY.getBytes(StandardCharsets.UTF_8));
        assertSilentDiscard("tenant 不匹配", history, state, tenantId);
    }

    // ════════════════════════════════════════════════════════════════
    // 封装：断言 + state 构造
    // ════════════════════════════════════════════════════════════════

    /** 六组丢弃的共同可观测行为：回落全量历史 + 无围栏 + 无 error 帧 + run 成功 + outbox=0。 */
    private void assertSilentDiscard(String label,
                                     List<Map<String, String>> history,
                                     String state, String requestTenant) throws Exception {
        AgentChainItSupport.SseFrames frames = post(history, state, requestTenant);
        assertCleanSuccess(frames);
        AgentChainItSupport.assertCompactedFence(label, captured, false);
        assertThat(captured)
                .as("%s：必须真的捕获到送模型请求，否则断言空转", label).isNotEmpty();
        AgentChainItSupport.assertFullHistory(label, captured.get(0), history);
    }

    private void assertCleanSuccess(AgentChainItSupport.SseFrames frames) throws Exception {
        assertThat(frames.order).as("静默丢弃不得发 error 帧").doesNotContain("error");
        assertThat(frames.order).endsWith("done");
        JsonNode done = frames.json("done");
        assertThat(done.get("truncated").asBoolean()).isFalse();
        String runId = frames.json("run.start").get("runId").asString();
        assertThat(AgentChainItSupport.runRow(appPool, tenantId, runId).state())
                .as("丢弃压缩态是可选增强，整轮仍须成功").isEqualTo("SUCCEEDED");
        assertThat(AgentChainItSupport.countOutbox(appPool, tenantId, runId))
                .as("请求级 SSE 对话绝不写 outbox").isZero();
    }

    private AgentChainItSupport.SseFrames post(List<Map<String, String>> history,
                                               String state, String requestTenant) {
        String body = AgentChainItSupport.agentChatBody(
                "请继续，保持刚才的约束", history, AGENT_ID, state, requestTenant);
        String token = platformTokens.issue(
                new PlatformPrincipal("it-m15-platform", Set.of("PLATFORM_ADMIN"), 1L, true),
                Instant.now());
        return AgentChainItSupport.postSse(rest, AgentChainItSupport.PLATFORM_PATH, token, body);
    }

    /** 用真实策略指纹封一个合法 state；policyVersion / tenant / upToTurn / 时刻可控。 */
    private static String seal(String policyVersion, String tenantId, Integer upToTurn,
                               long nowEpochSeconds, byte[] key) {
        return sealWithTtl(policyVersion, tenantId, upToTurn, nowEpochSeconds,
                AgentChainItSupport.CHECKPOINT_TTL_SECONDS, key);
    }

    private static String sealWithTtl(String policyVersion, String tenantId, Integer upToTurn,
                                      long nowEpochSeconds, int ttl, byte[] key) {
        StructuredState state = sampleState(policyVersion);
        String stateJson = StructuredStateCodec.toJson(state);
        String digest = StructuredStateCodec.digest(state);
        return ContextStateEnvelope.seal(
                new ContextStateEnvelope.SealInput(
                        stateJson, policyVersion, tenantId, upToTurn, digest, ttl),
                key, nowEpochSeconds, AgentChainItSupport.MAX_STATE_CHARS);
    }

    /** 超长 state：把 fact value 撑到远超 max-state-chars，封包侧放宽上限，交给服务端拒。 */
    private String sealOversized(String tenantId) {
        String big = "X".repeat(9_000);
        StructuredState state = new StructuredState(
                "目标", List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(new StructuredState.Fact("oversize", big)),
                List.of(), 0, 2, policyVersion);
        String stateJson = StructuredStateCodec.toJson(state);
        return ContextStateEnvelope.seal(
                new ContextStateEnvelope.SealInput(
                        stateJson, policyVersion, tenantId, null,
                        StructuredStateCodec.digest(state),
                        AgentChainItSupport.CHECKPOINT_TTL_SECONDS),
                STATE_KEY.getBytes(StandardCharsets.UTF_8),
                Instant.now().getEpochSecond(), 1_000_000);
    }

    /** 带一个「必须」约束 + 一个 fact，确保逐字抽取器口径下有真实可吸收状态。 */
    private static StructuredState sampleState(String policyVersion) {
        return new StructuredState(
                "把 carry-over 状态用于下一轮",
                List.of("必须保持单位一致"), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(new StructuredState.Fact("version-tag", "m15-roundtrip")),
                List.of(), 0, 2, policyVersion);
    }

    /** 篡改 HMAC 签名段最后一个字符（payload 不变，确保命中 HMAC_MISMATCH 而非 MALFORMED）。 */
    private static String tamperSignature(String envelope) {
        int dot = envelope.indexOf('.');
        String payload = envelope.substring(0, dot);
        String sig = envelope.substring(dot + 1);
        char last = sig.charAt(sig.length() - 1);
        char flipped = last == 'A' ? 'B' : 'A';
        return payload + "." + sig.substring(0, sig.length() - 1) + flipped;
    }

    private static RagResult ragWithHit() {
        AgentChatEvent.Hit hit = new AgentChatEvent.Hit("m15-c-1", RAG_MARKER, 0.9,
                "第1页", "数学", "example", "语义命中");
        return new RagResult(List.of(hit), false, false, "数学", 1);
    }
}
