package cn.studykid.multiagent.bootstrap.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.context.HeuristicTokenCounter;
import cn.studykid.multiagent.context.RequestContextProjection;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TenantSessionBinder;
import cn.studykid.multiagent.infrastructure.security.JwtTokenService;
import cn.studykid.multiagent.infrastructure.security.PlatformJwtTokenService;
import cn.studykid.multiagent.ports.context.TokenCounter;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.modelcatalog.ModelScenario;
import cn.studykid.multiagent.ports.security.PlatformPrincipal;
import cn.studykid.multiagent.testsupport.db.ItTenantProvisioner;

/**
 * S3-M15 四条链路 IT 的共享夹具 / 真库断言 / SSE 解析骨架。
 *
 * <p>⚠️ 这是<b>测试支撑</b>，不是被测对象：刻意不在这里写任何业务判断，只把
 * {@code AgentChatOrchestrationIT} / {@code ContextCompactionL1RealRunIT} 已验证过的
 * 「独立库 + it- 租户 + owner 造数 / app_agent 只读断言 + 原生 SSE 帧解析」收敛成一处，
 * 让四个 IT 的断言口径一致，不出现「一类 IT 用罐头 usage、另一类用真请求计费」的分叉。
 *
 * <p>⚠️ 铁律：① 业务 SQL 一律 {@code app_agent} + 事务内先绑租户；② 造数用 owner
 * 连接对仍按租户隔离的表必须带 {@code tenant_id} 谓词，全局 Agent 表则靠
 * {@code tenant_agent_binding} 的 ACTIVE 绑定表达可见性；③ {@code execution_snapshot} 不可删，只能
 * {@code NOT EXISTS} 幂等插入；④ 每个 IT 用独立 slug，避免限流桶与 Run 夹具互相污染。
 */
public final class AgentChainItSupport {

    public static final ObjectMapper JSON = new ObjectMapper();
    public static final TokenCounter TOKEN_COUNTER = new HeuristicTokenCounter();

    public static final String PLATFORM_PATH = "/api/agent/chat/stream";
    public static final String TENANT_PATH = "/api/knowledge/agent/chat/stream";
    public static final String CONSOLE_PATH = "/api/chat/stream";
    public static final String CONSOLE_TENANT_PATH = "/api/knowledge/chat/stream";

    /** 与 application.yml 的测试覆盖值逐字一致；⛔ 绝不复用 JWT 私钥。 */
    public static final String STATE_HMAC_KEY = "m15-chain-it-independent-state-hmac-key";
    public static final int MAX_STATE_CHARS = 8192;
    public static final int CHECKPOINT_TTL_SECONDS = 1_800;

    public static final String COMPACTED_FENCE = "<compacted_context trust=\"untrusted\">";
    public static final String TOOL_FENCE = "<tool_result trust=\"untrusted\"";

    /** seedAgent 的系统提示词唯一真源：IT 侧独立重算关闭态基线时必须逐字一致。 */
    public static final String SYSTEM_PROMPT = "你是初中数学辅导助手，先给直觉再给定义。";
    public static final String INSTRUCTIONS =
            "{\"inlineInstructions\":\"" + SYSTEM_PROMPT + "\"}";

    private AgentChainItSupport() {
    }

    // ── 数据源 / 租户夹具 ────────────────────────────────────────────

    public static HikariDataSource pool(String user, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("IT_PG_URL"));
        cfg.setUsername(user);
        if (password != null && !password.isBlank()) {
            cfg.setPassword(password);
        }
        cfg.setMaximumPoolSize(3);
        cfg.setPoolName("m15-chain-it-" + user);
        return new HikariDataSource(cfg);
    }

    public static String provision(HikariDataSource admin, String slug) throws SQLException {
        return ItTenantProvisioner.provision(admin, slug, "S3-M15 链路 IT 专用租户（勿删）");
    }

    /**
     * 幂等建「固定 id 的已发布 Agent + v1 + execution_snapshot + cap_agent_def」最小骨架。
     * cap_agent_def 是发布不可变行：IT 用 agentId 谓词删自有行后重建，保证夹具确定性。
     */
    public static void seedAgent(DataSource admin, String tenant, String agentId,
                                 String agentName, String modelId, String generationParams)
            throws Exception {
        try (Connection conn = admin.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO agent (agent_id, name, description)
                        VALUES (?::uuid, ?, ?)
                        ON CONFLICT (name) DO NOTHING
                        """)) {
                    ps.setString(1, agentId);
                    ps.setString(2, agentName);
                    ps.setString(3, "S3-M15 链路 IT 固定 Agent");
                    ps.executeUpdate();
                }
                // 运行时租户可见性 = tenant_agent_binding 里该租户→agent 的 ACTIVE 行
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO tenant_agent_binding (tenant_id, agent_id, status)
                        VALUES (?::uuid, ?::uuid, 'ACTIVE')
                        ON CONFLICT (tenant_id, agent_id) DO UPDATE SET status='ACTIVE'
                        """)) {
                    ps.setString(1, tenant);
                    ps.setString(2, agentId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO agent_version
                            (agent_id, version_number, definition, published_at)
                        SELECT agent_id, 1, '{"kind":"tutor"}'::jsonb, now()
                        FROM agent WHERE name = ?
                        ON CONFLICT (agent_id, version_number) DO NOTHING
                        """)) {
                    ps.setString(1, agentName);
                    ps.executeUpdate();
                }
                // ⚠️ execution_snapshot 不可删：NOT EXISTS 幂等，绝不 ON CONFLICT。
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO execution_snapshot
                            (version_id, agent_config, prompt_config,
                             tool_contracts, run_config)
                        SELECT v.version_id, '{}', '{}', '{}',
                               '{"maxCostPerRun":"2.00","maxLlmCalls":12,
                                 "maxSteps":20,"approvalRequired":"OPTIONAL"}'
                        FROM agent_version v JOIN agent a
                          ON a.agent_id = v.agent_id
                        WHERE a.name = ? AND v.version_number = 1
                          AND NOT EXISTS (SELECT 1 FROM execution_snapshot s
                              WHERE s.version_id = v.version_id)
                        """)) {
                    ps.setString(1, agentName);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        DELETE FROM public.cap_agent_def
                        WHERE agent_id = ?::uuid
                        """)) {
                    ps.setString(1, agentId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO cap_agent_def
                            (agent_id, version, name, description, roles,
                             enabled_skills, skill_bundles, capability_tags, overrides,
                             orchestration, instructions, model_ref, generation_params,
                             config_fingerprint, schema_version, published_at)
                        VALUES (?::uuid, 1, ?, ?, '[]'::jsonb, '[]'::jsonb,
                                '[]'::jsonb, '["tutor"]'::jsonb, '{}'::jsonb, '{}'::jsonb,
                                ?::jsonb, ?, ?::jsonb, ?, 1, now())
                        """)) {
                    ps.setString(1, agentId);
                    ps.setString(2, agentName);
                    ps.setString(3, "M15 真编排 + 真压缩夹具");
                    ps.setString(4, INSTRUCTIONS);
                    ps.setString(5, modelId);
                    ps.setString(6, generationParams);
                    ps.setString(7, "it-m15-fp-" + agentName);
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

    /**
     * 冻结一条专用模型目录行：启用、非默认、窗口/输入硬顶可控。
     * 用固定 UUID 派生 id，重跑幂等；⛔ 不改生产默认模型位。
     */
    public static void upsertModelCatalog(DataSource admin, String modelId, String scenario,
                                          int contextWindowTokens, int maxInputTokens,
                                          String description) throws SQLException {
        String id = UUID.nameUUIDFromBytes(("m15-" + scenario + "-" + modelId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String sql = """
                INSERT INTO model_catalog (
                    id, provider, scenario, model_name, display_name, base_url,
                    is_default, supports_tools, supports_streaming, supports_vision,
                    dimension, is_enabled, sort_order, description,
                    context_window_tokens, max_input_tokens, tokenizer_id,
                    supports_prompt_caching, supports_tokenize_api, updated_at)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, now())
                ON CONFLICT (scenario, model_name) DO UPDATE SET
                    provider=EXCLUDED.provider, display_name=EXCLUDED.display_name,
                    base_url=EXCLUDED.base_url, is_default=EXCLUDED.is_default,
                    supports_tools=EXCLUDED.supports_tools,
                    supports_streaming=EXCLUDED.supports_streaming,
                    supports_vision=EXCLUDED.supports_vision,
                    dimension=EXCLUDED.dimension, is_enabled=EXCLUDED.is_enabled,
                    sort_order=EXCLUDED.sort_order, description=EXCLUDED.description,
                    context_window_tokens=EXCLUDED.context_window_tokens,
                    max_input_tokens=EXCLUDED.max_input_tokens,
                    tokenizer_id=EXCLUDED.tokenizer_id,
                    supports_prompt_caching=EXCLUDED.supports_prompt_caching,
                    supports_tokenize_api=EXCLUDED.supports_tokenize_api,
                    updated_at=now()
                """;
        try (Connection conn = admin.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            try {
                ps.setString(1, id);
                ps.setString(2, "fake");
                ps.setString(3, scenario);
                ps.setString(4, modelId);
                ps.setString(5, "M15 链路 IT 夹具模型");
                ps.setString(6, "http://model.invalid");
                ps.setBoolean(7, false);
                ps.setBoolean(8, true);
                ps.setBoolean(9, false);
                ps.setBoolean(10, false);
                ps.setNull(11, java.sql.Types.INTEGER);
                ps.setBoolean(12, true);
                ps.setInt(13, 9_999);
                ps.setString(14, description);
                ps.setInt(15, contextWindowTokens);
                ps.setInt(16, maxInputTokens);
                ps.setString(17, "heuristic");
                ps.setBoolean(18, false);
                ps.setBoolean(19, false);
                ps.executeUpdate();
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** 每个用例前清空本 it- 租户的 Run 与限流桶；execution_snapshot 保留。 */
    public static void resetTenantRuns(DataSource admin, String tenant) throws SQLException {
        exec(admin, "DELETE FROM public.run WHERE tenant_id = ?::uuid", tenant);
        exec(admin, "DELETE FROM public.rate_limit_bucket WHERE tenant_id = ?::uuid", tenant);
    }

    public static void exec(DataSource admin, String sql, String... args) throws SQLException {
        try (Connection conn = admin.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            try {
                for (int i = 0; i < args.length; i++) {
                    ps.setString(i + 1, args[i]);
                }
                ps.executeUpdate();
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    // ── HTTP / SSE ───────────────────────────────────────────────────

    public static SseFrames postSse(RestTestClient rest, String path, String token, String body) {
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

    public static String platformToken(PlatformJwtTokenService tokens) {
        return tokens.issue(
                new PlatformPrincipal("it-m15-platform-admin", Set.of("PLATFORM_ADMIN"), 1L, true),
                Instant.now());
    }

    public static String tenantToken(JwtTokenService tokens, String tenantId) {
        return tokens.issue(new TenantContext(tenantId, "it-m15-principal", Set.of("STUDENT"), 1L),
                Instant.now());
    }

    /** 原生 SSE 文本 → 帧名序列 + 每个事件的首帧 / 全帧 data。 */
    public static final class SseFrames {
        public final String raw;
        public final List<String> order = new ArrayList<>();
        private final Map<String, String> first = new LinkedHashMap<>();
        private final Map<String, List<String>> all = new LinkedHashMap<>();

        public SseFrames(String raw) {
            this.raw = raw;
            String current = null;
            for (String line : raw.split("\n")) {
                if (line.startsWith("event:")) {
                    current = line.substring(6).trim();
                    order.add(current);
                } else if (line.startsWith("data:") && current != null) {
                    String data = line.substring(5).trim();
                    first.putIfAbsent(current, data);
                    all.computeIfAbsent(current, unused -> new ArrayList<>()).add(data);
                }
            }
        }

        public String of(String name) {
            String value = first.get(name);
            assertThat(value).as("缺少 SSE 事件 %s，实际顺序=%s", name, order).isNotNull();
            return value;
        }

        public List<String> all(String name) {
            return List.copyOf(all.getOrDefault(name, List.of()));
        }

        public int count(String name) {
            return all(name).size();
        }

        public JsonNode json(String name) {
            return JSON.readTree(of(name));
        }
    }

    // ── 请求体夹具 ───────────────────────────────────────────────────

    public static Map<String, String> turn(String role, String content) {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("role", role);
        t.put("content", content);
        return t;
    }

    /**
     * 造 {@code pairs} 对 user/assistant（总轮次 = 2×pairs ≤ 100，硬上限来自编排器 validate）。
     * USER 句首带「必须保留约束…」短句，供确定性逐字抽取器产出真实 checkpoint；
     * 其余为 ASCII 填充，按 3 字符/token 稳定放大挤出压力。
     */
    public static List<Map<String, String>> dialogue(int pairs, int asciiChars) {
        List<Map<String, String>> history = new ArrayList<>(pairs * 2);
        for (int i = 0; i < pairs; i++) {
            history.add(turn("user", "必须保留约束C" + i + "。" + ascii(i, asciiChars)));
            history.add(turn("assistant", ascii(10_000 + i, asciiChars)));
        }
        return List.copyOf(history);
    }

    /** 短历史：只用于「验签正例 / 六项丢弃」的 A/B 对照，不制造装配压力。 */
    public static List<Map<String, String>> shortDialogue(int pairs) {
        List<Map<String, String>> history = new ArrayList<>(pairs * 2);
        for (int i = 0; i < pairs; i++) {
            history.add(turn("user", "必须保留短约束S" + i + "。"));
            history.add(turn("assistant", "短回答" + i));
        }
        return List.copyOf(history);
    }

    /**
     * <b>W2 挤出压力夹具</b>：{@code pairs} 对 user/assistant（50 对 = 100 条 history，
     * 恰为编排器硬上限），刻意让 USER 短、ASSISTANT 长：
     * <ul>
     *   <li>{@code RoundSplitter} 把 USER 判为 ANCHOR 永不裁，故 USER 只放「必须C{i}」
     *       短约束——既压低不可裁固定成本，又带确定性逐字抽取器可吸收的「必须」标记，
     *       让轮末 {@code context.checkpoint} 真的能发射（不是空转/v1 Done）；</li>
     *   <li>ASSISTANT 是唯一可整轮裁剪的 TURN，用 ASCII 填充按 3 字符/token 稳定放大，
     *       使 before/window 稳定落在 W2（非 W0/W1 空转、非 W3/HARD fail-closed）。</li>
     * </ul>
     * ⚠️ L2 SSE 的 history 经控制器白名单只接受 user/assistant，故 TOOL 不进历史；
     * 压力全部由长 ASSISTANT 提供（工具轮裁剪由 #104 的 Worker 链路单独覆盖）。
     */
    public static List<Map<String, String>> pressureDialogue(int pairs, int assistantChars) {
        List<Map<String, String>> history = new ArrayList<>(pairs * 2);
        for (int i = 0; i < pairs; i++) {
            history.add(turn("user", "必须C" + i));
            history.add(turn("assistant", ascii(20_000 + i, assistantChars)));
        }
        return List.copyOf(history);
    }

    /**
     * 按编排器 {@code buildMessages} 的口径，构造「无 RAG / 无 carry-over」时的
     * <b>关闭态基线消息序列</b>：每条 history 逐字映射为 user/assistant，再追加当前输入。
     * ⚠️ 这是被测行为之外的独立重算（不调用被测实现），供 IT 断言
     * 「送模型请求确实比基线少」；若编排器偷偷改了 buildMessages 口径，本断言即失配变红。
     */
    public static List<ModelMessage> fullDialogueMessages(
            List<Map<String, String>> history, String currentInput) {
        List<ModelMessage> messages = new ArrayList<>(history.size() + 1);
        for (Map<String, String> t : history) {
            messages.add("assistant".equals(t.get("role"))
                    ? ModelMessage.assistant(t.get("content"))
                    : ModelMessage.user(t.get("content")));
        }
        messages.add(ModelMessage.user(currentInput));
        return List.copyOf(messages);
    }

    /**
     * 用捕获到的真实请求的<b>非上下文字段</b>（同一引用口径）换上另一组 messages，
     * 得到「如果没装配压缩会送出的那条 legacy 请求」——供 IT 独立重算 beforeTokens。
     * ⛔ 不重算 systemPrompt / temperature / toolSchemas，直接沿用捕获值，
     *    这样「装配只切上下文字段」也同时被引用级断言覆盖。
     */
    public static ModelRequest withMessages(ModelRequest base, List<ModelMessage> messages) {
        return new ModelRequest(base.modelId(), base.systemPrompt(), base.userContent(),
                base.temperature(), base.maxTokens(), base.timeout(),
                base.toolSchemas(), List.copyOf(messages));
    }

    private static String ascii(int index, int length) {
        StringBuilder b = new StringBuilder(length + 16);
        while (b.length() < length) {
            b.append("kp").append(index).append("-abcdefghij");
        }
        return b.substring(0, length);
    }

    public static String agentChatBody(String message, List<Map<String, String>> history,
                                       String agentId, String contextState, String tenantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("history", history);
        body.put("agentId", agentId);
        body.put("topK", 5);
        body.put("thinkingMode", true);
        if (tenantId != null) {
            body.put("tenantId", tenantId);
        }
        if (contextState != null) {
            body.put("contextState", contextState);
        }
        return JSON.writeValueAsString(body);
    }

    public static String consoleBody(String modelId, int maxTokens,
                                     List<Map<String, String>> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelId", modelId);
        body.put("systemPrompt", "你是平台控制台助手，按事实回答。");
        body.put("temperature", 0.2);
        body.put("maxTokens", maxTokens);
        body.put("messages", messages);
        return JSON.writeValueAsString(body);
    }

    /** 冻结策略 JSON；键名逐字对齐 ContextPolicyValidator.ALLOWED_KEYS。 */
    public static String policyJson(boolean enabled, String strategy, int maxInputTokens,
                                    double triggerRatio, double targetRatio, int recentTurns) {
        return """
                {
                  "temperature":0.3,
                  "maxOutputTokens":512,
                  "context":{
                    "enabled":%s,
                    "strategy":"%s",
                    "maxInputTokens":%d,
                    "triggerRatio":%s,
                    "targetRatio":%s,
                    "reserveRatio":0.10,
                    "recentTurns":%d
                  }
                }
                """.formatted(enabled, strategy, maxInputTokens,
                Double.toString(triggerRatio), Double.toString(targetRatio), recentTurns);
    }

    public static String agentChatScenario() {
        return ModelScenario.AGENT_CHAT;
    }

    public static String consoleScenario() {
        return ModelScenario.CONSOLE_CHAT;
    }

    // ── 请求级断言 ───────────────────────────────────────────────────

    public static int tokens(ModelRequest request) {
        return TOKEN_COUNTER.count(RequestContextProjection.project(request));
    }

    /** 逐条请求检查 assistant(tool_calls) → tool(call_id) 双向闭合。 */
    public static void assertToolCallPairs(String label, List<ModelRequest> requests) {
        for (int i = 0; i < requests.size(); i++) {
            ModelRequest request = requests.get(i);
            Set<String> pending = new LinkedHashSet<>();
            for (ModelMessage message : request.effectiveMessages()) {
                if (message.role() == ModelMessage.Role.ASSISTANT
                        && !message.toolCalls().isEmpty()) {
                    for (ModelMessage.ToolCallEcho echo : message.toolCalls()) {
                        pending.add(echo.callId());
                    }
                } else if (message.role() == ModelMessage.Role.TOOL) {
                    assertThat(message.toolCallId())
                            .as("%s 第 %d 轮 TOOL 消息缺 callId", label, i + 1).isNotNull();
                    assertThat(pending)
                            .as("⛔ %s 第 %d 轮 tool_call_id=%s 找不到对应 assistant tool_calls",
                                    label, i + 1, message.toolCallId())
                            .contains(message.toolCallId());
                    pending.remove(message.toolCallId());
                }
            }
            assertThat(pending)
                    .as("⛔ %s 第 %d 轮 assistant tool_calls 没有对应 TOOL 回灌：%s",
                            label, i + 1, pending)
                    .isEmpty();
        }
    }

    /** carry-over 围栏必须（正例）/ 必须不（丢弃例）出现在送模型请求里。 */
    public static void assertCompactedFence(String label, List<ModelRequest> requests,
                                            boolean expected) {
        for (int i = 0; i < requests.size(); i++) {
            long hits = requests.get(i).effectiveMessages().stream()
                    .filter(m -> m.content().contains(COMPACTED_FENCE))
                    .count();
            assertThat(hits)
                    .as("⛔ %s 第 %d 轮 compacted_context 围栏命中数=%d，期望=%s",
                            label, i + 1, hits, expected)
                    .isEqualTo(expected ? 1L : 0L);
        }
    }

    /** 静默丢弃后必须是「全量历史」：每条历史原文都逐字出现在送模型请求里。 */
    public static void assertFullHistory(String label, ModelRequest request,
                                         List<Map<String, String>> history) {
        List<String> contents = request.effectiveMessages().stream()
                .map(ModelMessage::content).toList();
        for (Map<String, String> t : history) {
            assertThat(contents)
                    .as("⛔ %s 丢弃压缩态后必须回落全量历史，缺历史原文=%s", label, t.get("content"))
                    .contains(t.get("content"));
        }
    }

    public static void assertToolOrCompactedFenceAbsent(String label, ModelRequest request) {
        assertThat(request.effectiveMessages())
                .as("⛔ %s 请求不得出现不可信围栏裸拼", label)
                .noneMatch(m -> m.content().contains(COMPACTED_FENCE)
                        || m.content().contains(TOOL_FENCE + " tool=\"carryOver\""));
    }

    // ── 真库只读断言（app_agent + 事务内绑租户） ──────────────────────

    public record RunRow(String state, String leaseOwner, Timestamp leaseExpiresAt,
                         Timestamp completedAt, String resultPayload, String errorCode,
                         String inputPayload) {
    }

    public record EventRow(long sequenceNo, String eventType, String payload) {
    }

    public static RunRow runRow(DataSource appPool, String tenant, String runId) throws Exception {
        return inTenant(appPool, tenant, conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT state, lease_owner, lease_expires_at, completed_at,
                           result_payload, error_code, input_payload
                    FROM run WHERE tenant_id = ?::uuid AND run_id = ?::uuid
                    """)) {
                ps.setString(1, tenant);
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

    public static List<EventRow> eventRows(DataSource appPool, String tenant, String runId)
            throws Exception {
        return inTenant(appPool, tenant, conn -> {
            List<EventRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT sequence_no, event_type, payload FROM run_event
                    WHERE tenant_id = ?::uuid AND run_id = ?::uuid ORDER BY sequence_no
                    """)) {
                ps.setString(1, tenant);
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

    public static long countOutbox(DataSource appPool, String tenant, String runId)
            throws Exception {
        return inTenant(appPool, tenant, conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT count(*) FROM outbox_event
                    WHERE tenant_id = ?::uuid AND run_id = ?::uuid
                    """)) {
                ps.setString(1, tenant);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** 必须在事务内、任何业务 SQL 前绑定租户；自动提交会让 set_config(is_local) 立即失效。 */
    public static <T> T inTenant(DataSource pool, String tenant, TenantQuery<T> body)
            throws Exception {
        try (Connection conn = pool.getConnection()) {
            conn.setAutoCommit(false);
            try {
                TenantSessionBinder.bind(conn,
                        new TenantContext(tenant, "it-m15", Set.of(), 0L));
                T result = body.apply(conn);
                conn.rollback();
                return result;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @FunctionalInterface
    public interface TenantQuery<T> {
        T apply(Connection conn) throws Exception;
    }
}
