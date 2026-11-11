package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.infrastructure.security.PlatformJwtTokenService;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.AgentChatOrchestratorPort;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;

/**
 * 通用 Agent 编排对话双端点的<b>真实 HTTP + 真实 Spring Security 链</b>集成测试。
 *
 * <p><b>为什么必须有这一层</b>：{@code AgentChatControllerTest} 直接构造控制器（不启容器），
 * 无法发现「端点落错安全链 → 平台令牌打平台链 401 / 租户令牌打租户链 401」这类
 * <b>单测全绿但生产全线不可用</b>的静默事故——这与 {@code /api/chat/**} 历史 401 事故同源。
 * 本类在 {@code RANDOM_PORT} 下走完整过滤器链 + 真实 JWT 验签，是唯一能证伪端点身份域错配的层。
 *
 * <p><b>两种验证策略</b>：
 * <ol>
 *   <li><b>身份域四象限 + 参数 fail-closed</b>：用"空消息"让控制器在建 SSE 连接、调编排器
 *       <b>之前</b>返回 400。于是「400 = 穿过正确安全链进入控制器」「401 = 身份域错配」，
 *       零编排 / 零 RAG / 零配额副作用（照搬 {@code PlatformSecurityE2ETest} E2E-9 范式）。</li>
 *   <li><b>真实 SSE 端到端</b>：{@code @MockitoBean} 替换编排器推脚本帧，有效租户 + 有效消息，
 *       验证帧经真实 HTTP 写出且 {@code SseEmitter.complete()} 触发的 ASYNC dispatch
 *       不被二次授权拦下（Controller 层 RecordingEmitter 测不到容器级 dispatch）。</li>
 * </ol>
 *
 * <p><b>反向验证锚点</b>：把 {@code AGENT_CHAT_PREFIX} 从平台链 matcher 删掉 →
 * 平台令牌打平台链得 401（用例 3/6 红）；把租户链端点误收进平台链 →
 * 租户令牌打租户链得 401（用例 4/7 红）；删掉任一 ASYNC dispatch 放行 → SSE 用例挂起/截断（用例 6/7 红）。
 *
 * <p>⚠️ 必须 {@code source scripts/it-env.sh} 后跑（连独立 {@code agent_it} 库，
 * 绝不指回应用库 {@code agent_dev}）；无 {@code IT_PG_URL} 时整类跳过，避免 CI 假失败。
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
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@DisplayName("通用 Agent 编排对话双端点 HTTP 身份域隔离与 SSE 端到端")
class AgentChatEndpointIT {

    private static final String PLATFORM_PATH = "/api/agent/chat/stream";
    private static final String TENANT_PATH = "/api/knowledge/agent/chat/stream";

    private static String tenantId;

    @Autowired
    private RestTestClient rest;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private PlatformJwtTokenService platformTokens;

    /**
     * 编排器替身：只推脚本帧，绝不碰真实 RAG / 模型 / 工具 / 审计。
     *
     * <p>⚠️ 只覆盖编排器端口；<b>保留</b>真实 {@code RequestAgentChatIdentityResolver}
     * 与真实 Security 过滤器链——那正是本类要验证的对象，mock 掉等于白测。
     */
    @MockitoBean
    private AgentChatOrchestratorPort orchestrator;

    @BeforeAll
    static void resolveTenant() {
        // 种子租户 t-a（与 PlatformSecurityE2ETest 同款），由 init-db 保证 ACTIVE。
        try (var conn = java.sql.DriverManager.getConnection(
                     System.getenv("IT_PG_URL"),
                     System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"),
                     System.getenv().getOrDefault("IT_PG_ADMIN_PASSWORD", ""));
             var ps = conn.prepareStatement(
                     "SELECT tenant_id::text FROM tenant WHERE slug = 't-a' LIMIT 1");
             var rs = ps.executeQuery()) {
            assertThat(rs.next()).as("种子租户 t-a 必须存在（先跑 init-db.sh）").isTrue();
            tenantId = rs.getString(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("读取测试租户失败", e);
        }
    }

    @BeforeEach
    void defaultOrchestratorDoesNothing() {
        // 身份域/参数用例不该走到编排器；这里给个空实现，若被误调则不推任何帧。
        doAnswer(inv -> null).when(orchestrator).run(any(), any(), any());
    }

    // ════════════════════════════════════════════════════════════
    // 身份域四象限（真实 Security 链；空消息 → 400/401，零编排副作用）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ 租户令牌不得穿过 /api/agent/chat/stream（平台链）")
    void tenantTokenCannotReachPlatformChain() {
        int status = postEmptyMessage(PLATFORM_PATH, tenantToken());
        assertThat(status)
                .as("平台链只认 aud=admin-console-api；租户令牌落到这里说明 AGENT_CHAT_PREFIX "
                        + "未挂上平台链，控制台持平台令牌点发送会 401")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("⛔ 平台令牌不得穿过 /api/knowledge/agent/chat/stream（租户链）")
    void platformTokenCannotReachTenantChain() {
        int status = postEmptyMessage(TENANT_PATH, platformToken());
        assertThat(status)
                .as("租户链只认 aud=agent-platform；平台令牌到这里必须是 401，"
                        + "否则两条身份域被打通")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("✅ 平台令牌可穿过平台链进入控制器（空消息 400，非 401）")
    void platformTokenReachesPlatformChainController() {
        int status = postEmptyMessage(PLATFORM_PATH, platformToken());
        assertThat(status)
                .as("400 = 平台链验签通过 + 请求已进入 AgentChatController 参数校验；"
                        + "401 = 身份域错配未修复")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("✅ 租户令牌可穿过租户链进入控制器（空消息 400，非 401）")
    void tenantTokenReachesTenantChainController() {
        int status = postEmptyMessage(TENANT_PATH, tenantToken());
        assertThat(status)
                .as("400 = 租户链验签通过 + 已进入控制器；401 = 新端点被误收进平台链")
                .isEqualTo(400);
    }

    // ════════════════════════════════════════════════════════════
    // 参数 fail-closed（真实链 + 真实身份解析器）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ 租户链请求体带 tenantId → 400（租户身份只能来自已验签令牌）")
    void tenantChainRejectsBodyTenantId() {
        String body = "{\"message\":\"q\",\"thinkingMode\":false,\"tenantId\":\""
                + tenantId + "\"}";
        int status = postRaw(TENANT_PATH, tenantToken(), body);
        assertThat(status)
                .as("租户链一旦接受请求体 tenantId，攻击者即可用合法租户令牌越权指定别的租户")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("⛔ 无令牌访问两条链 → 401（新增端点默认受保护）")
    void noTokenRejectedOnBothChains() {
        assertThat(postRaw(PLATFORM_PATH, null, "{\"message\":\"q\",\"thinkingMode\":false,"
                + "\"tenantId\":\"" + tenantId + "\"}")).isEqualTo(401);
        assertThat(postRaw(TENANT_PATH, null,
                "{\"message\":\"q\",\"thinkingMode\":false}")).isEqualTo(401);
    }

    @Test
    @DisplayName("⛔ 平台链缺目标租户 → 400（不会拿默认租户静默越权）")
    void platformChainRequiresExplicitTargetTenant() {
        // 非空消息 → 穿过控制器的空消息校验 → 身份解析器要求显式目标租户 → 400。
        int status = postRaw(PLATFORM_PATH, platformToken(),
                "{\"message\":\"q\",\"thinkingMode\":false}");
        assertThat(status)
                .as("平台主体自身不持租户；缺目标租户必须 400，绝不猜一个默认租户")
                .isEqualTo(400);
    }

    // ════════════════════════════════════════════════════════════
    // 真实 SSE 端到端（脚本帧经 HTTP 写出 + ASYNC dispatch 放行）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✅ 真实 SSE：平台链收到完整事件帧且命中 done 终帧（ASYNC dispatch 后不被二次授权拦下）")
    void platformChainStreamsCompleteSseFrames() {
        scriptFrames("run-1");

        var result = rest.post().uri(PLATFORM_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken())
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"怎么移项\",\"thinkingMode\":true,\"tenantId\":\""
                        + tenantId + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .returnResult();

        String frames = result.getResponseBody() == null ? "" : result.getResponseBody();
        assertThat(frames)
                .as("SseEmitter.complete() 触发的 ASYNC dispatch 若未放行，"
                        + "chunked 流写不出终止块，这里会拿到截断/空响应")
                .contains("event:run.start")
                .contains("event:thinking")
                .contains("event:delta")
                .contains("event:done")
                .doesNotContain("data:event:")
                .contains("\"runId\":\"run-1\"");
    }

    @Test
    @DisplayName("✅ 真实 SSE：租户链也能收到完整帧（思考草稿由编排器在源头丢弃，不在此层下发）")
    void tenantChainStreamsCompleteSseFrames() {
        scriptFrames("run-2");

        var result = rest.post().uri(TENANT_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantToken())
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"怎么移项\",\"thinkingMode\":false}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .returnResult();

        String frames = result.getResponseBody() == null ? "" : result.getResponseBody();
        assertThat(frames)
                .contains("event:run.start")
                .contains("event:delta")
                .contains("event:done")
                .doesNotContain("data:event:");
        // 编排器替身推的 thinking 帧在 Controller 层会被照常写出（Controller 无身份逻辑）；
        // 真正的「租户通道丢弃 thinking」由 AgentChatOrchestratorTest#tenantChainNeverEmitsThinking
        // 在编排源头守卫——那是唯一正确的防守位置。
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    /** 让编排器推一段脚本帧后由控制器收口。 */
    private void scriptFrames(String runId) {
        doAnswer(inv -> {
            AgentChatOrchestratorPort.Sink sink = inv.getArgument(2);
            sink.accept(new AgentChatEvent.RunStart(runId));
            sink.accept(new AgentChatEvent.Thinking("推理草稿"));
            sink.accept(new AgentChatEvent.Delta("先移项"));
            sink.accept(new AgentChatEvent.Done(false));
            return null;
        }).when(orchestrator).run(any(), any(), any());
    }

    private int postEmptyMessage(String path, String token) {
        // thinkingMode 是 primitive boolean，必须显式给值（否则 Jackson 反序列化 null 失败 → 400，
        // 那样测到的就是「参数解析」而非「身份域」，会制造假绿）。
        return postRaw(path, token, "{\"message\":\"\",\"thinkingMode\":false}");
    }

    private int postRaw(String path, String token, String body) {
        var spec = rest.post().uri(path)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return spec.body(body)
                .exchange()
                .expectBody(String.class)
                .returnResult()
                .getStatus()
                .value();
    }

    private String platformToken() {
        return platformTokens.issue(
                new PlatformPrincipal("it-platform-admin", Set.of("PLATFORM_ADMIN"), 1L, true),
                Instant.now());
    }

    private String tenantToken() {
        return tokens.issue(new TenantContext(tenantId, "it-principal", Set.of("STUDENT"), 1L),
                Instant.now());
    }
}
