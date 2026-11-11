package cn.studykid.multiagent.infrastructure.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort.RagResult;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort.RagUnavailableException;

/**
 * platform → rag 真实 HTTP 检索适配器的线协议守卫。
 *
 * <p><b>为什么用本地 HttpServer 而不是 mock HttpClient</b>：本测试要钉死的是
 * <b>真实端点路径</b>、<b>真实请求头</b>与<b>真实请求体形状</b>——
 * mock 掉 HttpClient 会让「打错端点」这类事故完全测不出来
 * （2026-11-10 已确认真实端点是 {@code /api/v1/retrieval}，不是 {@code /retrieve}）。
 *
 * <p><b>反向验证锚点</b>：把 {@code RETRIEVAL_PATH} 改成任意其它路径，
 * {@link #usesRealRetrievalEndpointAndPassesVerifiedIdentity()} 立即红。
 */
class HttpRagRetrievalGatewayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String TOKEN = "signed-platform-jwt";

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> tenantHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = "{\"hits\":[]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        path.set(exchange.getRequestURI().getPath());
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        tenantHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
        byte[] out = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        try (var os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private HttpRagRetrievalGateway gateway() {
        return new HttpRagRetrievalGateway("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    @DisplayName("⛔ 真实端点 + 已验签身份透传（端点写错即红）")
    void usesRealRetrievalEndpointAndPassesVerifiedIdentity() {
        gateway().retrieve(TENANT, TOKEN, "怎么解方程", 5);

        assertThat(path.get()).isEqualTo("/api/v1/retrieval");
        assertThat(HttpRagRetrievalGateway.retrievalPath()).isEqualTo("/api/v1/retrieval");
        assertThat(authorization.get()).isEqualTo("Bearer " + TOKEN);
        assertThat(tenantHeader.get()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("五轴中性占位 + CROSS_EDITION + EXPLAIN，topK 原样下发")
    void requestBodyUsesNeutralEditionAndExplainMode() throws Exception {
        gateway().retrieve(TENANT, TOKEN, "怎么解方程", 7);

        JsonNode root = JSON.readTree(body.get());
        assertThat(root.get("query").asString()).isEqualTo("怎么解方程");
        assertThat(root.get("mode").asString()).isEqualTo("EXPLAIN");
        assertThat(root.get("scopeMode").asString()).isEqualTo("CROSS_EDITION");
        assertThat(root.get("topK").asInt()).isEqualTo(7);
        JsonNode edition = root.get("edition");
        assertThat(edition.get("subject").asString()).isEqualTo("未指定");
        assertThat(edition.get("grade").asString()).isEqualTo("全学段");
        assertThat(edition.get("term").asString()).isEqualTo("全一册");
        assertThat(edition.get("publisher").asString()).isEqualTo("未指定");
        // ⚠️ 必须非空：rag 侧 EditionCoordinate 构造器 + EditionDto.@NotBlank 拒绝空串 → 500。
        // 真实联调 2026-11-11 曾因这里写 "" 导致每次检索 500 被降级成空命中（功能空转）。
        // 断言锁定为具体中性值，而非仅 notBlank —— 防止改成任意非空串掩盖语义漂移。
        assertThat(edition.get("revisionCode").asString()).isEqualTo("未指定");
        assertThat(root.get("knowledgePointId").isNull()).isTrue();
    }

    @Test
    @DisplayName("七字段白名单：丢弃缺/空/纯空白 chunkId/text 的命中，不透传额外字段")
    void hitsAreWhitelistedAndMalformedOnesDropped() {
        response = """
                {
                  "hits": [
                    {"chunkId":"c-1","text":"先移项","score":0.87,
                     "locator":"第10页","edition":"数学","role":"example","reason":"语义命中",
                     "channel":"SHOULD_NOT_LEAK","prompt":"SHOULD_NOT_LEAK"},
                    {"chunkId":"","text":"空 id 应丢弃"},
                    {"chunkId":"   ","text":"纯空白 id 应丢弃"},
                    {"chunkId":"c-3","text":"   "},
                    {"chunkId":"c-4","text":""},
                    {"chunkId":null,"text":"null id 应丢弃"},
                    {"text":"无 id 字段应丢弃"},
                    {"chunkId":"c-5"}
                  ],
                  "versionMismatchDetected": true,
                  "answerContentSuppressed": false,
                  "primaryEdition":"数学",
                  "channel":"SHOULD_NOT_LEAK"
                }
                """;
        RagResult result = gateway().retrieve(TENANT, TOKEN, "q", 5);

        // ⛔ 反向验证锚点：把 parseHit 改回只判 null（或删掉 isBlank），
        //    空 id / 纯空白 id / 空白 text 会漏进来，本断言立刻红。
        assertThat(result.hits()).hasSize(1);
        AgentChatEvent.Hit hit = result.hits().get(0);
        assertThat(hit.chunkId()).isEqualTo("c-1");
        assertThat(hit.text()).isEqualTo("先移项");
        assertThat(hit.score()).isEqualTo(0.87);
        assertThat(hit.locator()).isEqualTo("第10页");
        assertThat(hit.edition()).isEqualTo("数学");
        assertThat(hit.role()).isEqualTo("example");
        assertThat(hit.reason()).isEqualTo("语义命中");
        assertThat(result.versionMismatchDetected()).isTrue();
        assertThat(result.answerContentSuppressed()).isFalse();
        // returnedCount 是 rag 原始返回条数（诚实上报），不是通过白名单的条数。
        assertThat(result.returnedCount()).isEqualTo(8);
        // 七字段白名单的结果对象里不得夹带远端额外字段（record 结构本身即防线）。
        assertThat(result.toString()).doesNotContain("SHOULD_NOT_LEAK");
    }

    @Test
    @DisplayName("零命中是合法业务结果，绝不抛异常（fail-closed 只针对契约破坏）")
    void zeroHitsIsLegal() {
        response = "{\"hits\":[],\"returnedCount\":0}";
        RagResult result = gateway().retrieve(TENANT, TOKEN, "q", 1);
        assertThat(result.hits()).isEmpty();
        assertThat(result.returnedCount()).isZero();
    }

    @Test
    @DisplayName("⛔ 非 2xx / 坏 JSON / 缺 hits 一律 fail-closed，且不回显远端正文")
    void brokenUpstreamFailsClosedWithoutEchoingBody() {
        status = 503;
        response = "内部语料原文不应出现在异常里";
        assertThatThrownBy(() -> gateway().retrieve(TENANT, TOKEN, "q", 5))
                .isInstanceOf(RagUnavailableException.class)
                .hasMessageContaining("503")
                .hasMessageNotContaining("内部语料原文");

        status = 200;
        response = "not-json";
        assertThatThrownBy(() -> gateway().retrieve(TENANT, TOKEN, "q", 5))
                .isInstanceOf(RagUnavailableException.class);

        response = "{\"returnedCount\":0}";
        assertThatThrownBy(() -> gateway().retrieve(TENANT, TOKEN, "q", 5))
                .isInstanceOf(RagUnavailableException.class)
                .hasMessageContaining("hits");
    }

    @Test
    @DisplayName("⛔ topK 越界在发请求之前就拒（不发一个字节给 rag）")
    void invalidTopKIsRejectedBeforeAnyRequest() {
        assertThatThrownBy(() -> gateway().retrieve(TENANT, TOKEN, "q", 0))
                .isInstanceOf(RagUnavailableException.class);
        assertThat(path.get()).isNull();
    }
}
