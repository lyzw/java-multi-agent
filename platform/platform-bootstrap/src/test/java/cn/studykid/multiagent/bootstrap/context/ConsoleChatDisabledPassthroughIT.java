package cn.studykid.multiagent.bootstrap.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.model.ModelResponse.FinishReason;
import cn.studykid.multiagent.ports.model.ModelResponse.ModelUsage;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * S3-M15-c 关闭态对照：<b>{@code agent.context.enabled=false} 时 L3 必须逐位等价 legacy</b>。
 *
 * <p>这是 G1 A.5「关闭等价桥 [D11]」的 HTTP 层反证。若开启态装配被偷偷改成「关闭态也改字节」，
 * 或端口短路时仍返回新引用，本类立刻变红。断言链：
 * <ol>
 *   <li>真 HTTP 进 {@code /api/chat/stream}，同一个 spy 捕获真实 {@link ConsoleBudgetPort} 结果；</li>
 *   <li>{@code budgeted=false}、{@code tier=0}、{@code beforeTokens=afterTokens=0}
 *       （零预算、零计数，绝不带数字）；</li>
 *   <li>送模型的请求与 legacy <b>同一引用</b>、消息条数逐位不变——与「没有压缩功能」一致；</li>
 *   <li>SSE 仍 delta+done、无 error。</li>
 * </ol>
 *
 * <p>与 {@link ConsoleChatMessagesWithinBudgetIT}（开启态真裁剪）构成 A/B：同一夹具，
 * 一开一关，证明预算是「开关可控」而不是「永远改字节」或「开关都假绿」。
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
        "agent.context.enabled=false",
        "agent.context.state-hmac-key=" + ConsoleChatDisabledPassthroughIT.STATE_KEY,
        "agent.context.max-state-chars=8192",
        "agent.context.checkpoint-ttl-seconds=1800",
        "agent.memory.enabled=false"
})
@DisplayName("S3-M15-c 关闭态：L3 预算端口必须 passthrough 同一引用（逐位等价 legacy）")
class ConsoleChatDisabledPassthroughIT {

    static final String STATE_KEY = "m15-chain-it-independent-state-hmac-key";

    private static final String SLUG = "it-m15-console-off";
    private static final String MODEL_ID = "m15-console-fake";
    private static final String MODEL_SCENARIO = "m15_console_it";

    private static final int WINDOW = 4_096;
    private static final int MODEL_CONTEXT_WINDOW = 8_192;
    private static final int HISTORY_PAIRS = 50;
    private static final int ASSISTANT_CHARS = 144;
    private static final int MAX_OUTPUT_TOKENS = 1_024;
    private static final String MESSAGE = "请继续回答下一个问题。";
    private static final String ANSWER = "控制台已按原样上下文回答（关闭态 passthrough）";

    private static HikariDataSource adminPool;
    private static String tenantId;

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

    @MockitoSpyBean
    private ConsoleBudgetPort budgetSpy;

    @BeforeAll
    static void setUp() throws Exception {
        adminPool = AgentChainItSupport.pool(
                System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"),
                System.getenv("IT_PG_ADMIN_PASSWORD"));
        tenantId = AgentChainItSupport.provision(adminPool, SLUG);
        // 即便关闭态不会读目录（短路在 capabilitiesOf 之前），仍钉一份，保持与开启态夹具一致。
        AgentChainItSupport.upsertModelCatalog(adminPool, MODEL_ID, MODEL_SCENARIO,
                MODEL_CONTEXT_WINDOW, WINDOW, "M15 L3 Console 关闭态 IT 夹具模型");
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
        doReturn("22222222-2222-2222-2222-222222222222").when(chatCaller).rateLimitKey();
        doAnswer(inv -> {
            ModelRequest request = inv.getArgument(0);
            sentRequest.set(request);
            ChatStreamGateway.StreamDeltaConsumer consumer = inv.getArgument(1);
            consumer.onDelta(ANSWER);
            return new ModelResponse(ANSWER, java.util.Map.of(),
                    new ModelUsage(99, 7), FinishReason.STOP);
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

    @Test
    @DisplayName("✅ 关闭态 100 条 messages：passthrough 同一引用、零预算、内容逐位不变")
    void disabledPassthroughSameReferenceNoTrim() throws Exception {
        List<Map<String, String>> messages = consoleMessages();
        String body = AgentChainItSupport.consoleBody(MODEL_ID, MAX_OUTPUT_TOKENS, messages);
        AgentChainItSupport.SseFrames frames = AgentChainItSupport.postSse(rest,
                AgentChainItSupport.CONSOLE_PATH,
                platformTokens.issue(
                        new PlatformPrincipal("it-m15-console-off",
                                Set.of("PLATFORM_ADMIN"), 1L, true),
                        Instant.now()),
                body);

        assertThat(frames.order).contains("delta").endsWith("done");
        assertThat(frames.order).doesNotContain("error");

        ConsoleBudgetPort.BudgetResult r = budgetResult.get();
        assertThat(r).as("必须捕获到真实 BudgetResult").isNotNull();
        assertThat(r.budgeted())
                .as("关闭态不得预算改写，必须 passthrough 同一引用").isFalse();
        assertThat(r.tier())
                .as("关闭态档位必须 0（不得带任何预算行为数据）").isEqualTo(0);
        assertThat(r.beforeTokens())
                .as("[D11] 关闭态 beforeTokens 必须 0，不得多报数字").isEqualTo(0);
        assertThat(r.afterTokens())
                .as("[D11] 关闭态 afterTokens 必须 0，不得多报数字").isEqualTo(0);

        ModelRequest legacy = budgetInput.get().legacyRequest();
        assertThat(sentRequest.get())
                .as("关闭态 passthrough 必须返回 legacy 同一引用（与无压缩功能逐位一致 [D11]）")
                .isSameAs(legacy);
        assertThat(sentRequest.get().effectiveMessages())
                .as("关闭态不得改动消息条数（逐位等价 legacy）")
                .hasSize(messages.size());
    }

    @Test
    @DisplayName("✅ 关闭态租户链：同样 passthrough 同一引用（两链关闭行为一致）")
    void disabledTenantChainPassthroughSameReference() throws Exception {
        List<Map<String, String>> messages = consoleMessages();
        String body = AgentChainItSupport.consoleBody(MODEL_ID, MAX_OUTPUT_TOKENS, messages);
        AgentChainItSupport.SseFrames frames = AgentChainItSupport.postSse(rest,
                AgentChainItSupport.CONSOLE_TENANT_PATH,
                AgentChainItSupport.tenantToken(tenantTokens, tenantId),
                body);

        assertThat(frames.order).contains("delta").endsWith("done").doesNotContain("error");

        ConsoleBudgetPort.BudgetResult r = budgetResult.get();
        assertThat(r.budgeted()).isFalse();
        assertThat(r.tier()).isEqualTo(0);
        assertThat(r.beforeTokens()).isEqualTo(0);
        assertThat(r.afterTokens()).isEqualTo(0);
        assertThat(sentRequest.get())
                .as("租户链关闭态同样 passthrough 同一引用")
                .isSameAs(budgetInput.get().legacyRequest());
    }

    private static List<Map<String, String>> consoleMessages() {
        List<Map<String, String>> m =
                new ArrayList<>(AgentChainItSupport.pressureDialogue(HISTORY_PAIRS, ASSISTANT_CHARS));
        m.add(AgentChainItSupport.turn("user", MESSAGE));
        return List.copyOf(m);
    }
}
