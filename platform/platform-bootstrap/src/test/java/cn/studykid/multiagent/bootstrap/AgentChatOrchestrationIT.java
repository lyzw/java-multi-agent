package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
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

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.infrastructure.security.PlatformJwtTokenService;
import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort.RagResult;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort.RagUnavailableException;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelErrorKind;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.model.ModelResponse.FinishReason;
import cn.studykid.multiagent.ports.model.ModelResponse.ModelUsage;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;

/**
 * 请求级通用 Agent 编排对话的<b>真编排 + 真 HTTP + 真审计落库</b>闭环 IT。
 *
 * <p><b>与 {@link AgentChatEndpointIT} 的分工</b>：那个类 {@code @MockitoBean} 替换了
 * 整个编排器，只验证「真 Security 链 + 真 Controller + SSE 帧经 HTTP 写出 + ASYNC dispatch」。
 * 但它证明不了「真编排器的 RAG → Skill → 模型 → 落库 run 到底有没有一起跑通」——
 * 那正是「单测全绿、生产空转」最容易藏身的地方。本类把<b>真 Orchestrator</b>接进
 * 同一个 RANDOM_PORT 容器，只把两个<b>网络出口</b>（模型网关、rag 独立服务）替换成
 * 无凭据测试桩；其余全部真实：
 * <ol>
 *   <li>真 Spring Security 过滤器链 + 真 JWT 验签（平台链 aud=admin-console-api / 租户链 aud=agent-platform）；</li>
 *   <li>真 {@code AgentChatController}（SseEmitter + 虚拟线程 + 原生 SSE 帧）；</li>
 *   <li>真 {@code AgentChatOrchestrator}（路由 → RAG → Skill → 模型 → answer/usage → done）；</li>
 *   <li>真 {@code AgentQueryPort} / {@code ExecutionSnapshotPort} / {@code ClosureResolver} /
 *       {@code AgentModelConfigAssembler} / {@code ModelResolver}（读真库发布链路）；</li>
 *   <li>真 {@code JdbcAgentChatAuditRepository}（app_agent + FORCE RLS，两段式落
 *       {@code run}/{@code run_event}/{@code run_step}，<b>不写 outbox_event</b>）。</li>
 * </ol>
 *
 * <p><b>造数最小集</b>：{@code it-} 专用租户 + 1 个已发布 Agent（v1, published_at）
 * + execution_snapshot + PUBLISHED cap_agent_def（instructions / model_ref / generation_params，
 * <b>enabled_skills=[]</b>）。空闭包让 {@code PermissionResolver} 不必查任何
 * {@code cap_skill_def} / {@code cap_config_release} 指针——但路由、快照解析、模型配置装配、
 * 审计落库全部走真实代码。这是「用最小可控数据串起最多真实链路」，不是拿桩替代被测对象。
 *
 * <p><b>闭环断言（从编排入口进，不直连端口）</b>：HTTP SSE 帧里 {@code run.start} 的 runId
 * 必须能在真库查到一行 {@code SUCCEEDED} run（且 lease_owner 已清空、无 outbox），
 * 并附带 ROUTE / RAG_HITS / MODEL_ROUND / USAGE / DONE 事件与 MODEL_ROUND step。
 * 「有一行」不够——终态、租约释放、事件类型、token 计数都要算对。
 *
 * <p><b>反向验证锚点</b>：
 * <ul>
 *   <li>把模型桩改成抛 {@code TIMEOUT} → {@code modelFailureConvergesFailedRun} 观察到
 *       {@code run.state=FAILED} + {@code error} 帧（证明审计终态真随编排结果收敛）；</li>
 *   <li>把 RAG 桩改成抛 {@code RagUnavailableException} →
 *       {@code ragFailureIsComponentLevelInRealRun} 观察到 {@code run_event} 含
 *       {@code RAG_UNAVAILABLE} 且整轮仍 {@code SUCCEEDED}（证明组件级失败不伪造命中）；</li>
 *   <li>把平台上报 thinking 关掉 / 租户链放行 thinking →
 *       {@code thinkingOnlyInPlatformChain} 红（证明源头闸门在真 HTTP 链仍生效）。</li>
 * </ul>
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
@DisplayName("真编排 + 真 HTTP + 真审计落库闭环（it- 专用租户）")
class AgentChatOrchestrationIT {

    private static final String PLATFORM_PATH = "/api/agent/chat/stream";
    private static final String TENANT_PATH = "/api/knowledge/agent/chat/stream";
    private static final String SLUG = "it-agentchat-orch";
    private static final String AGENT_ID = "11111111-2222-3333-4444-555555555555";
    private static final String AGENT_NAME = "orchestration-it-tutor";
    private static final String ANSWER_TEXT = "先移项，再合并同类项";
    private static final String THINKING_TEXT = "推理草稿_仅平台可见";
    private static final String RAG_TEXT = "RAG_IT_MARKER_外部语料片段";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static DruidDataSource adminPool;
    private static DruidDataSource appPool;
    private static String tenantId;

    @Autowired
    private RestTestClient rest;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private PlatformJwtTokenService platformTokens;

    /**
     * 唯一网络出口①：模型网关。用具名 Bean 精确替换，避免影响 {@code ObservableModelGateway}
     * 等其它 {@code ChatStreamGateway} 类型 Bean 造成「两个同类型 Bean」启动失败。
     */
    @MockitoBean(name = "chatStreamGateway")
    private ChatStreamGateway chatGateway;

    /** 唯一网络出口②：platform → rag 独立服务检索。 */
    @MockitoBean
    private RagRetrievalPort rag;

    // ════════════════════════════════════════════════════════════
    // 夹具：it- 专用租户 + 最小发布骨架（全部 owner 连接写，绕开 FORCE RLS）
    // ════════════════════════════════════════════════════════════

    @BeforeAll
    static void setUp() throws Exception {
        adminPool = pool(System.getenv().getOrDefault("IT_PG_ADMIN_USER", "agent"),
                System.getenv("IT_PG_ADMIN_PASSWORD"));
        appPool = pool(System.getenv().getOrDefault("IT_PG_USER", "app_agent"),
                System.getenv("IT_PG_PASSWORD"));

        // ItTenantProvisioner 幂等建 tenant + agent(agent1) + v1 + execution_snapshot。
        // ⚠️ 它建的 agent 名固定为 agent1，agent_id 是随机的；本类自己建一个固定 id 的 Agent，
        //    避免依赖 provisioner 的随机 id（route 事件断言要精确 agentId）。
        tenantId = ItTenantProvisioner.provision(adminPool, SLUG, "真编排 IT 专用租户（勿删）");
        seedPublishedAgent(adminPool, tenantId);
        purgeRuns(adminPool, tenantId);
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
        // 每用例默认：RAG 一条命中、模型正常流式（含 thinking + delta）。
        // ⚠️ 必须用 doReturn/doAnswer 而非 when(...).thenReturn(...)：
        //    when(mock.method(...)) 会在「已有 answer 的 stub」上用 null 实参触发旧 answer，
        //    导致 consumer.onThinking(...) NPE（Fake 覆盖真实 Bean 的典型 Mockito 陷阱）。
        doReturn(ragWithHit()).when(rag).retrieve(anyString(), anyString(), anyString(), anyInt());
        doAnswer(inv -> {
            ChatStreamGateway.StreamDeltaConsumer consumer = inv.getArgument(1);
            consumer.onThinking(THINKING_TEXT);
            consumer.onDelta(ANSWER_TEXT);
            return new ModelResponse("整段正文-非增量", java.util.Map.of(),
                    new ModelUsage(11, 7), FinishReason.STOP);
        }).when(chatGateway).streamChat(any(), any());
        purgeRuns(adminPool, tenantId);
    }

    // ════════════════════════════════════════════════════════════
    // 闭环①：平台链真编排 → 真 HTTP SSE → 真库 SUCCEEDED run
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✅ 闭环：平台链真编排推全协议序，runId 在真库落成 SUCCEEDED 且租约已释放、无 Outbox")
    void platformChainRunsRealOrchestratorAndCommitsAudit() throws Exception {
        SseFrames frames = postSse(PLATFORM_PATH, platformToken(),
                "{\"message\":\"怎么移项\",\"agentId\":\"" + AGENT_ID
                        + "\",\"thinkingMode\":true,\"tenantId\":\"" + tenantId
                        + "\",\"topK\":5,\"scenario\":\"algebra\"}");

        // ① 协议序：真编排器从入口推全 13 类事件的子集（空闭包无 skill/tool）。
        //    平台链 thinkingMode=true → 模型首轮 onThinking 先于 onDelta。
        assertThat(frames.order).containsExactly(
                "run.start", "route", "rag.query", "rag.hits",
                "thinking", "delta", "answer", "usage", "done");
        assertThat(JSON.readTree(frames.of("done")).get("truncated").asBoolean()).isFalse();
        assertThat(frames.of("answer")).contains(ANSWER_TEXT);
        assertThat(frames.of("rag.hits")).contains(RAG_TEXT).contains("\"injected\":true");
        // 平台链允许推理草稿。
        assertThat(frames.of("thinking")).contains(THINKING_TEXT);
        // 原生 SSE 帧，无 data:event: 废帧。
        assertThat(frames.raw).doesNotContain("data:event:");

        // ② runId 必须来自真实落库行（不是凭空生成后补写）。
        String runId = JSON.readTree(frames.of("run.start")).get("runId").asString();
        assertThat(runId).as("run.start 必须是合法 UUID（真库 run_id）").isNotBlank();
        UUID.fromString(runId);

        // ③ 真库断言（app_agent + FORCE RLS，事务内先绑租户）。
        RunRow row = runRow(runId);
        assertThat(row.state).isEqualTo("SUCCEEDED");
        assertThat(row.leaseOwner)
                .as("finish 收敛终态时必须清空 lease_owner（正常释放租约，不是 bug）")
                .isNull();
        assertThat(row.leaseExpiresAt).isNull();
        assertThat(row.completedAt).isNotNull();
        // ⚠️ PG jsonb 会把文本规范化为 {"key": value}（冒号后带空格），
        //    不能用字符串 contains；必须 readTree 后按字段断言。
        JsonNode result = JSON.readTree(row.resultPayload);
        assertThat(result.get("finishReason").asString()).isEqualTo("STOP");
        assertThat(result.get("ragHits").asInt()).isEqualTo(1);
        assertThat(result.get("ragInjected").asBoolean()).isTrue();
        // 审计只存标量事实，绝不存问题正文 / 回答正文 / RAG 语料。
        JsonNode input = JSON.readTree(row.inputPayload);
        assertThat(input.get("channel").asString()).isEqualTo("agent-chat");
        assertThat(input.get("domain").asString()).isEqualTo("PLATFORM");
        assertThat(input.get("scenario").asString()).isEqualTo("algebra");
        assertThat(row.inputPayload).doesNotContain("怎么移项");
        assertThat(row.resultPayload).doesNotContain(ANSWER_TEXT).doesNotContain(RAG_TEXT);

        // ④ 事件 / 步骤落库：类型齐全 + 序号连续，token 计数算对。
        List<EventRow> events = eventRows(runId);
        assertThat(events).extracting(e -> e.eventType)
                .containsExactly("AGENT_CHAT_STARTED", "ROUTE", "RAG_QUERY", "RAG_HITS",
                        "MODEL_ROUND", "USAGE", "DONE");
        assertThat(events).extracting(e -> e.sequenceNo)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(stepRows(runId)).extracting(s -> s.stepType)
                .contains("RAG", "MODEL_ROUND_1");
        JsonNode usage = JSON.readTree(frames.of("usage"));
        assertThat(usage.get("inputTokens").asLong()).isEqualTo(11);
        assertThat(usage.get("outputTokens").asLong()).isEqualTo(7);

        // ⑤ 两条独立防重线：请求级 Run 绝不写 Outbox / 幂等记录（无 MQ 唤醒信号）。
        assertThat(countOutbox(runId)).isZero();
    }

    // ════════════════════════════════════════════════════════════
    // 闭环②：租户链真编排 → 推理草稿源头丢弃
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ 闭环：租户链真编排恒丢弃推理草稿，但仍落 run 审计 + 全组件过程")
    void tenantChainDropsThinkingButStillCommitsAudit() throws Exception {
        SseFrames frames = postSse(TENANT_PATH, tenantToken(),
                "{\"message\":\"怎么移项\",\"agentId\":\"" + AGENT_ID + "\",\"thinkingMode\":true}");

        assertThat(frames.order).doesNotContain("thinking");
        assertThat(frames.raw).doesNotContain(THINKING_TEXT);
        assertThat(frames.order).contains("rag.query", "rag.hits", "delta", "answer", "done");

        String runId = JSON.readTree(frames.of("run.start")).get("runId").asString();
        RunRow row = runRow(runId);
        assertThat(row.state).isEqualTo("SUCCEEDED");
        // 租户链身份来自已验签令牌，审计 domain 必须是 TENANT。
        assertThat(JSON.readTree(row.inputPayload).get("domain").asString()).isEqualTo("TENANT");
        assertThat(countOutbox(runId)).isZero();
    }

    // ════════════════════════════════════════════════════════════
    // 反向验证①：模型失败 → 审计收敛 FAILED（证明终态真随编排结果变）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ 反向验证：模型 TIMEOUT → error 帧 + 真库 run=FAILED（终态真收敛，非固定 SUCCEEDED）")
    void modelFailureConvergesFailedRun() throws Exception {
        doThrow(new ModelGatewayException(ModelErrorKind.TIMEOUT, "upstream timeout"))
                .when(chatGateway).streamChat(any(), any());

        SseFrames frames = postSse(PLATFORM_PATH, platformToken(),
                "{\"message\":\"怎么移项\",\"agentId\":\"" + AGENT_ID
                        + "\",\"thinkingMode\":false,\"tenantId\":\"" + tenantId + "\"}");

        assertThat(frames.order).contains("error").endsWith("done");
        assertThat(frames.of("error")).contains("TIMEOUT");
        String runId = JSON.readTree(frames.of("run.start")).get("runId").asString();
        RunRow row = runRow(runId);
        assertThat(row.state).isEqualTo("FAILED");
        assertThat(row.errorCode).isEqualTo("TIMEOUT");
        assertThat(row.leaseOwner).isNull();
        assertThat(JSON.readTree(row.resultPayload).get("finishReason").asString())
                .isEqualTo("FAILED");
        assertThat(eventRows(runId)).extracting(e -> e.eventType).contains("ERROR", "DONE");
    }

    // ════════════════════════════════════════════════════════════
    // 反向验证②：RAG 不可用 → 组件级失败可观测，整轮仍 SUCCEEDED
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ 反向验证：RAG 不可用 → run_event 含 RAG_UNAVAILABLE，不伪造命中，整轮仍成功")
    void ragFailureIsComponentLevelInRealRun() throws Exception {
        doThrow(new RagUnavailableException("RAG 服务不可达 503"))
                .when(rag).retrieve(anyString(), anyString(), anyString(), anyInt());

        SseFrames frames = postSse(PLATFORM_PATH, platformToken(),
                "{\"message\":\"怎么移项\",\"agentId\":\"" + AGENT_ID
                        + "\",\"thinkingMode\":false,\"tenantId\":\"" + tenantId + "\"}");

        assertThat(frames.order).doesNotContain("error").endsWith("done");
        assertThat(frames.of("rag.hits")).contains("\"hits\":[]").contains("\"injected\":false");
        // 组件级失败以 skill.phase=error 可见，绝不静默当零命中。
        assertThat(frames.raw).contains("\"skillId\":\"rag\"").contains("\"phase\":\"error\"");
        String runId = JSON.readTree(frames.of("run.start")).get("runId").asString();
        assertThat(runRow(runId).state).isEqualTo("SUCCEEDED");
        assertThat(eventRows(runId)).extracting(e -> e.eventType)
                .contains("RAG_UNAVAILABLE", "RAG_HITS", "DONE");
    }

    // ════════════════════════════════════════════════════════════
    // 反向验证③：thinking 源头闸门（平台可见 / 租户丢弃）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⛔ 反向验证：同一份 thinking 模型输出，平台链可见、租户链恒不可见")
    void thinkingOnlyInPlatformChain() throws Exception {
        SseFrames platform = postSse(PLATFORM_PATH, platformToken(),
                "{\"message\":\"怎么移项\",\"agentId\":\"" + AGENT_ID
                        + "\",\"thinkingMode\":true,\"tenantId\":\"" + tenantId + "\"}");
        SseFrames tenant = postSse(TENANT_PATH, tenantToken(),
                "{\"message\":\"怎么移项\",\"agentId\":\"" + AGENT_ID + "\",\"thinkingMode\":true}");

        assertThat(platform.order).contains("thinking");
        assertThat(platform.of("thinking")).contains(THINKING_TEXT);
        assertThat(tenant.order).doesNotContain("thinking");
        assertThat(tenant.raw).doesNotContain(THINKING_TEXT);
        // 但两条链的 run 都真落库了（租户链不是靠「不跑编排」来藏 thinking）。
        assertThat(runRow(JSON.readTree(platform.of("run.start")).get("runId").asString()).state)
                .isEqualTo("SUCCEEDED");
        assertThat(runRow(JSON.readTree(tenant.of("run.start")).get("runId").asString()).state)
                .isEqualTo("SUCCEEDED");
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    private SseFrames postSse(String path, String token, String body) {
        var result = rest.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .returnResult();
        return new SseFrames(result.getResponseBody() == null ? "" : result.getResponseBody());
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

    private static RagResult ragWithHit() {
        AgentChatEvent.Hit hit = new AgentChatEvent.Hit("it-c-1", RAG_TEXT, 0.91,
                "第3页", "数学", "example", "语义命中");
        return new RagResult(List.of(hit), false, false, "数学", 1);
    }

    /** SSE 原始文本 → 帧名序列 + 每个事件首帧 data。 */
    private static final class SseFrames {
        final String raw;
        final List<String> order = new ArrayList<>();
        final java.util.Map<String, String> first = new java.util.LinkedHashMap<>();

        SseFrames(String raw) {
            this.raw = raw;
            String currentName = null;
            for (String line : raw.split("\n")) {
                if (line.startsWith("event:")) {
                    currentName = line.substring(6).trim();
                    order.add(currentName);
                } else if (line.startsWith("data:") && currentName != null) {
                    first.putIfAbsent(currentName, line.substring(5).trim());
                }
            }
        }

        String of(String name) {
            String v = first.get(name);
            assertThat(v).as("缺少 SSE 事件 " + name + "，实际顺序=" + order).isNotNull();
            return v;
        }
    }

    // ── 真库访问 ────────────────────────────────────────────────────

    private record RunRow(String state, String leaseOwner, Timestamp leaseExpiresAt,
                          Timestamp completedAt, String resultPayload, String errorCode,
                          String inputPayload) {
    }

    private record EventRow(long sequenceNo, String eventType, String payload) {
    }

    private record StepRow(int stepNumber, String stepType) {
    }

    private static RunRow runRow(String runId) throws Exception {
        return inTenant(appPool, tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT state, lease_owner, lease_expires_at, completed_at, "
                            + "result_payload, error_code, input_payload "
                            + "FROM run WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("真库必须存在该 runId 的行").isTrue();
                    return new RunRow(rs.getString("state"), rs.getString("lease_owner"),
                            rs.getTimestamp("lease_expires_at"), rs.getTimestamp("completed_at"),
                            rs.getString("result_payload"), rs.getString("error_code"),
                            rs.getString("input_payload"));
                }
            }
        });
    }

    private static List<EventRow> eventRows(String runId) throws Exception {
        return inTenant(appPool, tenantId, conn -> {
            List<EventRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT sequence_no, event_type, payload FROM run_event "
                            + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid ORDER BY sequence_no")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new EventRow(rs.getLong("sequence_no"),
                                rs.getString("event_type"), rs.getString("payload")));
                    }
                }
            }
            return rows;
        });
    }

    private static List<StepRow> stepRows(String runId) throws Exception {
        return inTenant(appPool, tenantId, conn -> {
            List<StepRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT step_number, step_type FROM run_step "
                            + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid ORDER BY step_number")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new StepRow(rs.getInt("step_number"), rs.getString("step_type")));
                    }
                }
            }
            return rows;
        });
    }

    private static long countOutbox(String runId) throws Exception {
        return inTenant(appPool, tenantId, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM outbox_event "
                            + "WHERE tenant_id = ?::uuid AND run_id = ?::uuid")) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** 必须在事务内、任何业务 SQL 前绑定租户；自动提交会让 set_config(is_local) 立即失效。 */
    private static <T> T inTenant(DataSource pool, String tenant, TenantQuery<T> body)
            throws Exception {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                cn.studykid.multiagent.infrastructure.persistence.TenantSessionBinder.bind(conn,
                        new TenantContext(tenant, "it", Set.of(), 0L));
                T result = body.apply(conn);
                conn.rollback(); // 只读断言：回滚归还，避免测试自身留下提交
                return result;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @FunctionalInterface
    private interface TenantQuery<T> {
        T apply(Connection conn) throws Exception;
    }

    private static DruidDataSource pool(String user, String password) {
        DruidDataSource cfg = new DruidDataSource();
        cfg.setMaxWait(10_000L);
        cfg.setConnectTimeout(10_000);
        cfg.setUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(user);
        if (password != null && !password.isBlank()) {
            cfg.setPassword(password);
        }
        cfg.setMaxActive(2);
        return cfg;
    }

    /** 幂等建一条固定 id 的已发布 Agent + 快照 + PUBLISHED cap_agent_def（空 skills 闭包）。 */
    private static void seedPublishedAgent(DataSource admin, String tenant) throws Exception {
        try (Connection conn = admin.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // agent（固定 id，route 事件断言用）
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO agent (agent_id, name, description) "
                                + "VALUES (?::uuid, ?, ?) "
                                + "ON CONFLICT (name) DO NOTHING")) {
                    ps.setString(1, AGENT_ID);
                    ps.setString(2, AGENT_NAME);
                    ps.setString(3, "真编排 IT 固定 Agent");
                    ps.executeUpdate();
                }
                // 运行时租户可见性 = tenant_agent_binding 里该租户→agent 的 ACTIVE 行
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tenant_agent_binding (tenant_id, agent_id, status) "
                                + "VALUES (?::uuid, ?::uuid, 'ACTIVE') "
                                + "ON CONFLICT (tenant_id, agent_id) DO UPDATE SET status='ACTIVE'")) {
                    ps.setString(1, tenant);
                    ps.setString(2, AGENT_ID);
                    ps.executeUpdate();
                }
                // agent_version v1 + published_at（快照解析硬过滤 published_at IS NOT NULL）
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO agent_version (agent_id, version_number, definition, published_at) "
                                + "SELECT agent_id, 1, '{\"kind\":\"tutor\"}'::jsonb, now() "
                                + "FROM agent WHERE name = ? "
                                + "ON CONFLICT (agent_id, version_number) DO NOTHING")) {
                    ps.setString(1, AGENT_NAME);
                    ps.executeUpdate();
                }
                // execution_snapshot（不可变表：NOT EXISTS 幂等，绝不靠 ON CONFLICT）
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO execution_snapshot "
                                + "(version_id, agent_config, prompt_config, tool_contracts, run_config) "
                                + "SELECT v.version_id, '{}', '{}', '{}', "
                                + "'{\"maxCostPerRun\":\"2.00\",\"maxLlmCalls\":12,"
                                + "\"maxSteps\":20,\"approvalRequired\":\"OPTIONAL\"}' "
                                + "FROM agent_version v JOIN agent a "
                                + "  ON a.agent_id = v.agent_id "
                                + "WHERE a.name = ? AND v.version_number = 1 "
                                + "AND NOT EXISTS (SELECT 1 FROM execution_snapshot s "
                                + "  WHERE s.version_id = v.version_id)")) {
                    ps.setString(1, AGENT_NAME);
                    ps.executeUpdate();
                }
                // cap_agent_def：运行期真源（instructions / model_ref / generation_params），
                // enabled_skills=[] → 空闭包，无需任何 cap_skill_def / cap_config_release 指针。
                // align_cap_lifecycle_state 触发器会在 published_at 非空时自动置 lifecycle_state=PUBLISHED。
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO cap_agent_def "
                                + "(agent_id, version, name, description, roles, "
                                + " enabled_skills, skill_bundles, capability_tags, overrides, "
                                + " orchestration, instructions, model_ref, generation_params, "
                                + " config_fingerprint, schema_version, published_at) "
                                + "VALUES (?::uuid, 1, ?, ?, '[]'::jsonb, '[]'::jsonb, "
                                + " '[]'::jsonb, '[\"tutor\"]'::jsonb, '{}'::jsonb, '{}'::jsonb, "
                                + " ?::jsonb, ?, ?::jsonb, ?, 1, now()) "
                                + "ON CONFLICT (agent_id, version) DO NOTHING")) {
                    ps.setString(1, AGENT_ID);
                    ps.setString(2, AGENT_NAME);
                    ps.setString(3, "真编排 IT 固定 Agent");
                    ps.setString(4, "{\"inlineInstructions\":\"你是初中数学辅导助手，先给直觉再给定义。\"}");
                    ps.setString(5, "MiniMax-M2.5");
                    ps.setString(6, "{\"temperature\":0.3,\"maxOutputTokens\":2048}");
                    ps.setString(7, "it-fixed-agent-fp-v1");
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** 清理该 it- 租户的历史 Run（含级联子表）；execution_snapshot 不可删，保留。 */
    private static void purgeRuns(DataSource admin, String tenant) throws Exception {
        try (Connection conn = admin.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // FORCE RLS 下 owner(BYPASSRLS) 也必须带 tenant_id，绝不裸删。
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM run WHERE tenant_id = ?::uuid")) {
                    ps.setString(1, tenant);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }
}
