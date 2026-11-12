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
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.api.chat.ConsoleChatController.ChatCaller;
import cn.studykid.multiagent.bootstrap.PlatformApplication;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.infrastructure.security.PlatformJwtTokenService;
import cn.studykid.multiagent.ports.agentchat.ConsoleBudgetPort;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.model.ModelResponse.FinishReason;
import cn.studykid.multiagent.ports.model.ModelResponse.ModelUsage;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;

/**
 * S3-M15-c：<b>L3 ConsoleChat 真链路预算保护</b>（独立库真跑）。
 *
 * <p>堵的是「L3 预算只是装饰」——必须证明<b>唯一送模型的请求</b>确实被
 * {@link ConsoleBudgetPort} 改写过、且真省了 token。两条通道都走
 * {@code POST /api/chat/stream}（平台链）与 {@code /api/knowledge/chat/stream}（租户链），
 * 只替换两个网络出口（模型网关 / 限流），其余全真：真 Security + JWT + Controller +
 * 真 {@code ConsoleBudgetAdapter}（被 {@code @MockitoSpyBean} 包裹以捕获真实
 * {@code BudgetResult}）+ 真 {@code ContextAssembler} + 真水位判定。
 *
 * <p><b>为什么用 spy 而不是罐头断言</b>：端口返回 {@code BudgetResult.budgeted() / tier() /
 * beforeTokens() / afterTokens()} 是唯一权威口径。spy 让真实 adapter 跑完、再把结果记下来，
 * 既证明「预算真的改写过请求」又拿到 tier（L3 必须是 1 或 2，绝不 0/3/HARD）。
 * 再独立用捕获的真实 {@link ModelRequest} 现算一遍 {@code after < before} 做交叉验证，
 * 防止「端口数字对、但 controller 偷偷把 legacy 原样送模型」的失真。
 *
 * <p><b>挤出压力夹具</b>：50 对 user/assistant = 100 条 messages（L3 无 100 上限，
 * 仍用 50 对与 L2 同口径对齐），USER 短「必须C{i}」、ASSISTANT 长 ASCII 填充。
 * 按本地计算器在 window=4096 / trigger=0.70 / target=0.40 下取
 * {@code ASSISTANT_CHARS=144}：before≈3285（usage≈0.802，稳落 W2）、after≈1890、
 * 真省≈1395、不触发二次硬裁。L3 窗口来自 {@code ContextPolicy.defaults()}
 * （maxInputTokens=16000）与模型目录 maxInputTokens（4096）取小，全局 hardCeiling=128000
 * 不构成约束，故 window 恒 4096。
 *
 * <p><b>thinking 红线</b>：桩<b>总是</b>发 {@code onThinking}，平台链必须可见 thinking、
 * 租户链必须 {@code thinking=0}（控制台直接对话不回落到面向学生的通道，红线 R6）。
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
        "agent.context.state-hmac-key=" + ConsoleChatMessagesWithinBudgetIT.STATE_KEY,
        "agent.context.max-state-chars=8192",
        "agent.context.checkpoint-ttl-seconds=1800",
        "agent.memory.enabled=false"
})
@DisplayName("S3-M15-c：L3 ConsoleChat 真链路预算保护（W2 真裁剪 + tier∈{1,2} + 租户链 thinking=0）")
class ConsoleChatMessagesWithinBudgetIT {

    static final String STATE_KEY = "m15-chain-it-independent-state-hmac-key";

    private static final String SLUG = "it-m15-console";
    private static final String MODEL_ID = "m15-console-fake";
    private static final String MODEL_SCENARIO = "m15_console_it";

    private static final int WINDOW = 4_096;
    private static final int MODEL_CONTEXT_WINDOW = 8_192;
    private static final int HISTORY_PAIRS = 50;
    private static final int ASSISTANT_CHARS = 144;
    private static final int MAX_OUTPUT_TOKENS = 1_024;
    private static final String MESSAGE = "请继续回答下一个问题。";
    private static final String ANSWER = "控制台已按预算内上下文回答";
    /** 桩必须真的下发 thinking，否则「租户链 thinking=0」是空转断言。 */
    private static final String THINKING = "推理片段";

    private static HikariDataSource adminPool;
    private static String tenantId;

    /** 唯一送模型的请求（来自网关注入，恒等于 budgetResult.request()）。 */
    private static final AtomicReference<ModelRequest> sentRequest = new AtomicReference<>();
    private static final AtomicReference<ConsoleBudgetPort.BudgetInput> budgetInput =
            new AtomicReference<>();
    private static final AtomicReference<ConsoleBudgetPort.BudgetResult> budgetResult =
            new AtomicReference<>();

    @Autowired
    private RestTestClient rest;
    @Autowired
    private PlatformJwtTokenService platformTokens;
    @Autowired
    private JwtTokenService tenantTokens;

    @MockitoBean(name = "chatStreamGateway")
    private ChatStreamGateway chatGateway;
    @MockitoBean
    private ChatCaller chatCaller;
    @MockitoBean
    private RateLimitPort rateLimiter;

    /** 包裹真实 ConsoleBudgetAdapter：让它跑真实装配，同时记录 BudgetResult 供断言。 */
    @MockitoSpyBean
    private ConsoleBudgetPort budgetSpy;

    @BeforeAll
    static void setUp() throws Exception {
        adminPool = AgentChainItSupport.pool(
                System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"),
                System.getenv("IT_PG_ADMIN_PASSWORD"));
        tenantId = AgentChainItSupport.provision(adminPool, SLUG);
        // L3 无 Agent 定义：只需把模型目录钉成 maxInputTokens=4096，使 window 恒 4096。
        AgentChainItSupport.upsertModelCatalog(adminPool, MODEL_ID, MODEL_SCENARIO,
                MODEL_CONTEXT_WINDOW, WINDOW, "M15 L3 Console 预算 IT 夹具模型");
    }

    @AfterAll
    static void closePools() {
        if (adminPool != null) {
            adminPool.close();
        }
    }

    @BeforeEach
    void resetStubs() {
        sentRequest.set(null);
        budgetInput.set(null);
        budgetResult.set(null);
        doReturn(RateLimitPort.Decision.ALLOWED)
                .when(rateLimiter).check(anyString(), anyString());
        doReturn("11111111-1111-1111-1111-111111111111").when(chatCaller).rateLimitKey();
        doAnswer(inv -> {
            ModelRequest request = inv.getArgument(0);
            sentRequest.set(request);
            ChatStreamGateway.StreamDeltaConsumer consumer = inv.getArgument(1);
            consumer.onThinking(THINKING);
            consumer.onDelta(ANSWER);
            int inputTokens = AgentChainItSupport.tokens(request);
            return new ModelResponse(ANSWER, java.util.Map.of(),
                    new ModelUsage(inputTokens, 7), FinishReason.STOP);
        }).when(chatGateway).streamChat(any(), any());
        doAnswer(inv -> {
            ConsoleBudgetPort.BudgetInput in = inv.getArgument(0);
            budgetInput.set(in);
            ConsoleBudgetPort.BudgetResult r =
                    (ConsoleBudgetPort.BudgetResult) inv.callRealMethod();
            budgetResult.set(r);
            return r;
        }).when(budgetSpy).protect(any());
    }

    // ════════════════════════════════════════════════════════════════
    // ① 平台链：100 条 messages 在 W2 真裁剪，tier∈{1,2}、after<before、
    //    thinking 帧可见、SSE delta+done
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✅ 平台链 100 条 messages：W2 真裁剪、tier∈{1,2}、真省 token、thinking 可见")
    void platformChainBudgetTrimsLargeHistory() throws Exception {
        List<Map<String, String>> messages = consoleMessages();
        String body = AgentChainItSupport.consoleBody(MODEL_ID, MAX_OUTPUT_TOKENS, messages);
        AgentChainItSupport.SseFrames frames = AgentChainItSupport.postSse(rest,
                AgentChainItSupport.CONSOLE_PATH,
                platformTokens.issue(
                        new cn.studykid.multiagent.ports.security.PlatformPrincipal(
                                "it-m15-console", Set.of("PLATFORM_ADMIN"), 1L, true),
                        Instant.now()),
                body);

        assertThat(frames.order).contains("delta").endsWith("done");
        assertThat(frames.order).doesNotContain("error");
        assertThat(frames.count("thinking"))
                .as("平台链必须可见 thinking，否则租户链 suppression 断言无对照").isPositive();

        assertRealBudget(frames, "平台链");

        int before = AgentChainItSupport.tokens(budgetInput.get().legacyRequest());
        int after = AgentChainItSupport.tokens(sentRequest.get());
        assertThat(after)
                .as("独立按真实请求重算：裁剪后(%d) < 裁剪前(%d)——数字算对了，不是有一行", after, before)
                .isLessThan(before);
    }

    // ════════════════════════════════════════════════════════════════
    // ② 租户链：同样压力仍预算裁剪，但 thinking 帧必须=0（红线 R6）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✅ 租户链 100 条 messages：同样 W2 真裁剪、但 thinking=0")
    void tenantChainSuppressesThinkingButStillBudgets() throws Exception {
        List<Map<String, String>> messages = consoleMessages();
        String body = AgentChainItSupport.consoleBody(MODEL_ID, MAX_OUTPUT_TOKENS, messages);
        AgentChainItSupport.SseFrames frames = AgentChainItSupport.postSse(rest,
                AgentChainItSupport.CONSOLE_TENANT_PATH,
                AgentChainItSupport.tenantToken(tenantTokens, tenantId),
                body);

        assertThat(frames.order).contains("delta").endsWith("done");
        assertThat(frames.order).doesNotContain("error");
        assertThat(frames.count("thinking"))
                .as("租户链绝不下发 thinking 帧（红线 R6：推理草稿不回落到学生通道）")
                .isZero();

        assertRealBudget(frames, "租户链");

        int before = AgentChainItSupport.tokens(budgetInput.get().legacyRequest());
        int after = AgentChainItSupport.tokens(sentRequest.get());
        assertThat(after)
                .as("独立按真实请求重算：租户链裁剪后 < 裁剪前").isLessThan(before);
    }

    // ════════════════════════════════════════════════════════════════
    // ③ 门控反证：短 history（远低于触发线）走同一条真链，必须 W0 不裁剪、
    //    before==after、thinking 仍可见（证明裁剪是「压力触发」不是「无条件砍」）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✅ 短 history：W0 不裁剪、before==after、thinking 仍可见（门控反证）")
    void withinBudgetSmallHistoryUntouched() throws Exception {
        List<Map<String, String>> messages = List.of(
                AgentChainItSupport.turn("user", "你好，请介绍勾股定理。"),
                AgentChainItSupport.turn("assistant", "勾股定理：直角三角形两直角边平方和等于斜边平方。"));
        String body = AgentChainItSupport.consoleBody(MODEL_ID, MAX_OUTPUT_TOKENS, messages);
        AgentChainItSupport.SseFrames frames = AgentChainItSupport.postSse(rest,
                AgentChainItSupport.CONSOLE_PATH,
                platformTokens.issue(
                        new cn.studykid.multiagent.ports.security.PlatformPrincipal(
                                "it-m15-console", Set.of("PLATFORM_ADMIN"), 1L, true),
                        Instant.now()),
                body);

        assertThat(frames.order).contains("delta").endsWith("done").doesNotContain("error");
        assertThat(frames.count("thinking")).isPositive();

        ConsoleBudgetPort.BudgetResult r = budgetResult.get();
        assertThat(r).as("必须捕获到真实 BudgetResult").isNotNull();
        assertThat(r.budgeted()).as("开启态 assemble 必产出独立请求引用").isTrue();
        assertThat(r.tier())
                .as("无压力下不得触发 W1/W2（否则是「无条件砍消息」假绿）").isEqualTo(0);
        assertThat(r.afterTokens())
                .as("W0 不得悄悄膨胀：裁剪后 <= 裁剪前").isLessThanOrEqualTo(r.beforeTokens());
        assertThat(r.afterTokens())
                .as("W0 不得悄悄裁剪：裁剪后 == 裁剪前（内容逐位一致）")
                .isEqualTo(r.beforeTokens());

        int before = AgentChainItSupport.tokens(budgetInput.get().legacyRequest());
        int after = AgentChainItSupport.tokens(sentRequest.get());
        assertThat(after).as("独立重算：W0 内容未变").isEqualTo(before);
        assertThat(sentRequest.get().effectiveMessages())
                .as("W0 不得改动消息条数").hasSize(messages.size());
    }

    // ── 共享断言 ───────────────────────────────────────────────────

    private void assertRealBudget(AgentChainItSupport.SseFrames frames, String label) {
        ConsoleBudgetPort.BudgetResult r = budgetResult.get();
        assertThat(r).as(label + "：必须捕获到真实 BudgetResult，否则预算断言空转").isNotNull();
        assertThat(r.budgeted())
                .as(label + "：开启态必须真改写请求（引用身份分流判据）").isTrue();
        assertThat(r.tier())
                .as(label + "：L3 只跑 W1/W2，档位必须是 1 或 2（绝不 0/3/HARD）")
                .isIn(1, 2);
        assertThat(r.afterTokens())
                .as(label + "：裁剪后 token 必须为正（全窗口估算为 0 = 预算口径空转）")
                .isPositive();
        assertThat(r.afterTokens())
                .as(label + "：裁剪后必须真省 token（before=%d, after=%d）——功能空转反证",
                        r.beforeTokens(), r.afterTokens())
                .isLessThan(r.beforeTokens());

        // 交叉验证：捕获的唯一送模型请求必须等于预算后的请求，且不同于 legacy。
        assertThat(sentRequest.get())
                .as(label + "：送模型请求必须等于 BudgetResult.request()").isSameAs(r.request());
        assertThat(sentRequest.get())
                .as(label + "：预算后请求必须不同于 legacy 引用（行为分流唯一判据）")
                .isNotSameAs(budgetInput.get().legacyRequest());
    }

    /** 50 对短 USER + 长 ASSISTANT + 末条 USER 当前输入；L3 无 100 上限。 */
    private static List<Map<String, String>> consoleMessages() {
        List<Map<String, String>> m =
                new ArrayList<>(AgentChainItSupport.pressureDialogue(HISTORY_PAIRS, ASSISTANT_CHARS));
        m.add(AgentChainItSupport.turn("user", MESSAGE));
        return List.copyOf(m);
    }
}
