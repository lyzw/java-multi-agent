package cn.studykid.multiagent.infrastructure.rag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort;

/**
 * RAG 检索适配器：platform 进程 → rag 独立服务 {@code POST /api/v1/retrieval}。
 *
 * <p><b>为什么必须新建</b>（2026-11-10 全仓核实）：两个 Maven reactor 彼此独立，
 * platform 侧原先<b>没有任何</b>指向 rag 的 HTTP 客户端；所有跨进程检索都发生在浏览器
 * 经 Vite/Nginx 代理直连 8081。本类是第一个服务端到服务端的真实检索通道。
 *
 * <p><b>端点校正</b>：真实端点是 {@code /api/v1/retrieval}（{@code @PostMapping} 无子路径），
 * <b>不是</b> {@code /api/v1/retrieval/retrieve}（后者 404）。
 *
 * <p><b>认证与租户</b>：透传调用方已验签的 Bearer JWT（租户链 = 租户令牌，
 * 平台链 = 平台令牌）+ {@code X-Tenant-Id}。rag 的 {@code RagJwtAuthFilter} 验签：
 * 租户域强制 {@code tenant_id claim == X-Tenant-Id}（403 防越权），平台域允许跨租户带该头。
 *
 * <p><b>检索请求必填项</b>（rag {@code RetrievalRequestDto}）：
 * <ul>
 *   <li>{@code query}：本轮用户输入；</li>
 *   <li>{@code mode}：{@code EXPLAIN}（讲解模式，泄题闸门最严）；</li>
 *   <li>{@code edition}：五轴展示名必填 → 用<b>中性占位坐标</b>
 *       （未指定/全学段/全一册/未指定/未指定），配 {@code scopeMode=CROSS_EDITION} 跨版本召回。
 *       ⚠️ {@code revisionCode} <b>必须非空</b>：rag 侧 {@code EditionCoordinate} 构造器与
 *       {@code EditionDto.revisionCode} 的 {@code @NotBlank} 都会拒绝空串 → 500；
 *       历史上这里写 {@code ""}，单测假 Server 直接 200 掩盖了契约失配，
 *       真实联调时每次检索都 500、被编排器降级成组件级 error + 空命中（2026-11-11 实测）；</li>
 *   <li>{@code topK}：1..50。</li>
 * </ul>
 *
 * <p><b>fail-closed</b>：非 2xx / 超时 / 非法 JSON / 缺少 hits 一律抛
 * {@link RagUnavailableException}，由编排器转成组件级 info，绝不伪造命中或静默当零命中。
 * 响应体只取白名单七字段，不把 rag 的额外字段（如 channel / funnel / 提示词）透给前端。
 */
public class HttpRagRetrievalGateway implements RagRetrievalPort {

    private static final Logger log = LoggerFactory.getLogger(HttpRagRetrievalGateway.class);

    /** 真实端点（rag RagRetrievalController @RequestMapping + @PostMapping 无 value）。 */
    static final String RETRIEVAL_PATH = "/api/v1/retrieval";

    /** 与服务端的连接 / 读取超时（检索是同步链路，不能挂住 SSE 全程）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** 响应体上限（防远端异常大包打爆内存）。 */
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final URI retrievalUri;

    /** 生产构造：baseUrl 来自配置（如 http://127.0.0.1:8081）。 */
    public HttpRagRetrievalGateway(@NonNull String baseUrl) {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER) // 服务间调用不接受重定向（SSRF 防线）
                .build(), baseUrl);
    }

    /** 测试构造：可注入 mock HttpClient / 本地 WireMock 地址。 */
    public HttpRagRetrievalGateway(@NonNull HttpClient client, @NonNull String baseUrl) {
        this.client = client;
        String normalized = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("rag.base-url 不能为空");
        }
        this.retrievalUri = URI.create(normalized + RETRIEVAL_PATH);
    }

    @Override
    public @NonNull RagResult retrieve(@NonNull String tenantId,
                                       @NonNull String bearerToken,
                                       @NonNull String query,
                                       int topK) {
        if (topK < 1 || topK > 50) {
            throw new RagUnavailableException("RAG topK 超出合法范围（1..50）: " + topK);
        }
        String body = buildRequest(query, topK);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(retrievalUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("X-Tenant-Id", tenantId)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            throw new RagUnavailableException("构造 RAG 检索请求失败", e);
        }

        HttpResponse<String> response;
        try {
            // ⚠️ Java 21 没有 ofString(Charset, int) 重载；用 buffering(...) 在**读取期**
            //    封顶，超限会抛 IOException 而不是先把整包读进内存（防远端异常大包）。
            response = client.send(request, HttpResponse.BodyHandlers.buffering(
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                    MAX_RESPONSE_BYTES));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagUnavailableException("RAG 检索被中断", e);
        } catch (java.io.IOException e) {
            // 两类来源：连接不可达 / 响应体超过 MAX_RESPONSE_BYTES（buffering 抛 IOException）。
            // 不回显远端正文，只给主机与原因类别，避免把语料内容带进日志。
            throw new RagUnavailableException("RAG 响应读取失败（不可达或超出大小上限）: host="
                    + retrievalUri.getHost(), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // ⚠️ 不回显响应正文（可能含租户语料），只给状态码供运维定位
            throw new RagUnavailableException("RAG 检索失败: HTTP " + status);
        }
        return parseResult(response.body());
    }

    /** 构造 rag 检索请求体（五轴占位 + CROSS_EDITION + 讲解模式）。 */
    private static @NonNull String buildRequest(@NonNull String query, int topK) {
        ObjectNode edition = MAPPER.createObjectNode();
        edition.put("subject", "未指定");       // Subject.UNSPECIFIED.displayName()
        edition.put("grade", "全学段");          // GradeBand.ALL_GRADES.displayName()
        edition.put("term", "全一册");           // Term.FULL_YEAR.displayName()
        edition.put("publisher", "未指定");     // Publisher.UNSPECIFIED.displayName()
        // ⚠️ 必须非空：rag 的 EditionCoordinate 构造器 + EditionDto.@NotBlank 拒绝空串（否则 500）。
        // CROSS_EDITION 下 revisionCode 不参与 admits() 过滤（恒 true），只作排序惩罚，
        // 故中性占位值不影响召回范围；与其余四轴的"未指定"语义保持一致。
        edition.put("revisionCode", "未指定");

        ObjectNode root = MAPPER.createObjectNode();
        root.put("query", query);
        root.put("mode", "EXPLAIN");            // 讲解模式：泄题闸门最严
        root.set("edition", edition);
        root.put("scopeMode", "CROSS_EDITION"); // 跨版本召回，避免占位坐标锁死 0 命中
        root.putNull("knowledgePointId");
        root.putNull("subject");
        root.putNull("grade");
        root.put("topK", topK);
        return MAPPER.writeValueAsString(root);
    }

    /** 解析 rag 响应（七字段白名单，不泄漏 channel/funnel/prompt 等额外字段）。 */
    private static @NonNull RagResult parseResult(@NonNull String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (RuntimeException e) {
            throw new RagUnavailableException("RAG 响应不是合法 JSON", e);
        }
        JsonNode hitsNode = root == null ? null : root.get("hits");
        if (hitsNode == null || !hitsNode.isArray()) {
            throw new RagUnavailableException("RAG 响应缺少 hits 数组（契约不匹配）");
        }
        List<AgentChatEvent.Hit> hits = new ArrayList<>();
        for (JsonNode n : hitsNode) {
            AgentChatEvent.Hit hit = parseHit(n);
            if (hit != null) {
                hits.add(hit);
            }
        }
        boolean versionMismatch = bool(root, "versionMismatchDetected");
        boolean suppressed = bool(root, "answerContentSuppressed");
        String primaryEdition = text(root, "primaryEdition");
        int returned = hitsNode.size();
        return new RagResult(List.copyOf(hits), versionMismatch, suppressed,
                primaryEdition, returned);
    }

    private static AgentChatEvent.Hit parseHit(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        String chunkId = text(n, "chunkId");
        String text = text(n, "text");
        // ⚠️ 与两前端 hits() 一致：chunkId / text 缺失**或空白**即丢弃该条。
        //    只判 null 会让 chunkId="" 的空命中混进不可信上下文，前端又会把空 id
        //    当成合法命中渲染，形成「有命中源」的假象；空白命中一律视为畸形。
        if (chunkId == null || chunkId.isBlank()
                || text == null || text.isBlank()) {
            return null;
        }
        double score = n.has("score") && n.get("score").isNumber()
                ? n.get("score").asDouble() : 0.0;
        return new AgentChatEvent.Hit(
                chunkId, text, score,
                orEmpty(text(n, "locator")), orEmpty(text(n, "edition")),
                orEmpty(text(n, "role")), orEmpty(text(n, "reason")));
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static boolean bool(JsonNode node, String field) {
        if (node == null) {
            return false;
        }
        JsonNode v = node.get(field);
        return v != null && v.isBoolean() && v.asBoolean();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 供测试断言默认请求体形状（避免测试引用实现私有常量）。 */
    static @NonNull Map<String, Object> describeDefaultEdition() {
        Map<String, Object> edition = new LinkedHashMap<>();
        edition.put("subject", "未指定");
        edition.put("grade", "全学段");
        edition.put("term", "全一册");
        edition.put("publisher", "未指定");
        edition.put("revisionCode", "");
        return edition;
    }

    /** 供守卫/测试读取真实端点路径（非被测实现私有常量的重复定义）。 */
    static @NonNull String retrievalPath() {
        return RETRIEVAL_PATH.toLowerCase(Locale.ROOT);
    }
}
