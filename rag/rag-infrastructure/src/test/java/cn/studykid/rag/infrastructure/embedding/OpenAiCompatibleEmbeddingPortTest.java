package cn.studykid.rag.infrastructure.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.ports.EmbeddingPort;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link OpenAiCompatibleEmbeddingPort} 与 {@link EmbeddingPorts} 的单测。
 *
 * <p>用 JDK 自带的 {@link HttpServer} 当假网关，<b>不依赖任何真实模型服务</b>：
 * 验证的是协议适配（请求体、鉴权头、批切分、按 index 归位、维度校验、错误处理）
 * 与装配选择规则，不是某个模型的向量质量。
 */
class OpenAiCompatibleEmbeddingPortTest {

    private HttpServer server;
    private String baseUrl;
    /** 收到的请求体，用于断言批切分与请求内容。 */
    private final List<String> receivedBodies = new ArrayList<>();
    /** 最近一次请求的 Authorization 头。 */
    private volatile String lastAuthHeader;
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void register(java.util.function.BiConsumer<HttpExchange, String> handler) {
        server.createContext("/v1/embeddings", exchange -> {
            requestCount.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedBodies.add(body);
            lastAuthHeader = exchange.getRequestHeaders().getFirst("Authorization");
            handler.accept(exchange, body);
            exchange.close();
        });
    }

    /** 固定 JSON 响应。 */
    private void respond(String json) {
        register((exchange, body) -> write(exchange, 200, json));
    }

    /** 按请求的 input 条数回放对应数量、指定维度的零向量。 */
    private void respondEcho(int dimension) {
        register((exchange, body) -> write(exchange, 200, echo(body, dimension)));
    }

    private static void write(HttpExchange exchange, int status, String json) {
        try {
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String echo(String requestBody, int dimension) {
        int count;
        try {
            count = new ObjectMapper().readTree(requestBody).path("input").size();
        } catch (RuntimeException e) {
            return "{\"error\":\"bad request\"}";
        }
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(i).append(",\"embedding\":[");
            for (int d = 0; d < dimension; d++) {
                sb.append(d == 0 ? 1 : 0).append(d + 1 == dimension ? "]" : ",");
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    private OpenAiCompatibleEmbeddingPort port(int dimension, int batchSize) {
        return new OpenAiCompatibleEmbeddingPort(baseUrl, "test-key", "fake-model",
                dimension, batchSize, Duration.ofSeconds(5));
    }

    @Test
    void sendsOpenAiContractAndPreservesInputOrder() {
        // 故意乱序返回：适配器必须按 data[].index 归位，而不是按响应顺序
        respond("""
                {"data":[
                  {"index":1,"embedding":[0.1,0.2,0.3,0.4]},
                  {"index":0,"embedding":[1.0,0.0,0.0,0.0]}
                ]}""");
        List<float[]> vectors = port(4, 16).embed(List.of("消化 digest", "get along with"));

        assertEquals(2, vectors.size());
        assertEquals(1.0f, vectors.get(0)[0], 1e-6);
        assertEquals(0.2f, vectors.get(1)[1], 1e-6);
        assertEquals(1, requestCount.get());
        assertTrue(receivedBodies.get(0).contains("\"model\":\"fake-model\""));
        assertTrue(receivedBodies.get(0).contains("\"input\""));
    }

    @Test
    void sendsBearerToken() {
        respondEcho(4);
        port(4, 16).embed(List.of("x"));
        assertEquals("Bearer test-key", lastAuthHeader);
    }

    @Test
    void splitsIntoBatchesAndKeepsGlobalOrder() {
        respondEcho(4);
        List<float[]> vectors = port(4, 2).embed(List.of("a", "b", "c", "d", "e"));

        assertEquals(5, vectors.size());
        // 5 条 / 批 2 = 3 个请求，且请求体里的 input 条数依次为 2/2/1
        assertEquals(3, requestCount.get());
        assertEquals(List.of(2, 2, 1), receivedBodies.stream()
                .map(b -> new ObjectMapper().readTree(b).path("input").size())
                .toList());
        assertEquals(4, vectors.get(4).length);
    }

    @Test
    void rejectsDimensionMismatchWithActionableMessage() {
        respond("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> port(4096, 16).embed(List.of("沪教版 六年级 上册 Unit 5")));
        assertTrue(e.getMessage().contains("维度不匹配"), e.getMessage());
        assertTrue(e.getMessage().contains("返回 2 维"), e.getMessage());
        assertTrue(e.getMessage().contains("期望 4096 维"), e.getMessage());
    }

    @Test
    void surfacesHttpError() {
        register((exchange, body) -> write(exchange, 404, "{\"error\":\"model not found\"}"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> port(4, 16).embed(List.of("x")));
        assertTrue(e.getMessage().contains("HTTP 404"), e.getMessage());
    }

    @Test
    void rejectsNonOpenAiShape() {
        respond("{\"result\":[[0,0,0,0]]}");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> port(4, 16).embed(List.of("x")));
        assertTrue(e.getMessage().contains("缺少 data 数组"), e.getMessage());
    }

    @Test
    void emptyInputMakesNoRequest() {
        assertEquals(List.of(), port(4, 16).embed(List.of()));
        assertEquals(0, requestCount.get());
    }

    @Test
    void exposesModelAndDimension() {
        OpenAiCompatibleEmbeddingPort p = port(4096, 16);
        assertEquals("fake-model", p.modelVersion());
        assertEquals(4096, p.dimension());
    }

    @Test
    void normalizesTrailingSlashInBaseUrl() {
        respondEcho(4);
        new OpenAiCompatibleEmbeddingPort(baseUrl + "/", "k", "m", 4, 16, Duration.ofSeconds(5))
                .embed(List.of("x"));
        assertEquals(1, requestCount.get());
    }

    @Test
    void rejectsBlankBaseUrlAndModelAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleEmbeddingPort(" ", "k", "m", 4));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleEmbeddingPort(baseUrl, "k", " ", 4));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleEmbeddingPort(baseUrl, "k", "m", 0));
    }

    // ── 装配选择规则 ────────────────────────────────────────────

    @Test
    void selectsRealPortWhenBaseUrlConfigured() {
        EmbeddingPort port = EmbeddingPorts.create(false, baseUrl, "k", "m", 4, 16, 5, "test");
        assertInstanceOf(OpenAiCompatibleEmbeddingPort.class, port);
    }

    @Test
    void realBaseUrlWithoutModelFailsClosedInsteadOfSilentPlaceholder() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EmbeddingPorts.create(true, baseUrl, "k", "  ", 4, 16, 5, "test"));
        assertTrue(e.getMessage().contains("fail-closed"), e.getMessage());
    }

    @Test
    void blankBaseUrlFallsBackToPlaceholderBehindGate() {
        assertInstanceOf(LocalDeterministicEmbeddingPort.class,
                EmbeddingPorts.create(true, "", "", "", 1024, 16, 5, "test"));
        assertThrows(IllegalStateException.class,
                () -> EmbeddingPorts.create(false, null, null, null, 1024, 16, 5, "test"));
    }
}
