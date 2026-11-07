package cn.studykid.rag.infrastructure.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.ports.GenerationModelException;

/**
 * {@link OpenAiCompatibleGenerationAdapter} 的不变式测试。
 *
 * <p><b>锁死三条规则</b>：
 * <ol>
 *   <li><b>接地指令与逐字片段必须进请求体</b>——模型拿到的用户消息必须含
 *       {@code <依据原文>} 包裹的逐字片段，否则「只能基于给定原文」的铁律落空；</li>
 *   <li><b>api-key 绝不进请求体</b>——只走 {@code Authorization: Bearer} 头；</li>
 *   <li><b>任何失败（非 2xx / 超时 / 空内容 / 非法响应）统一归一为
 *       {@link GenerationModelException}</b>，且异常消息不含密钥与响应原文。</li>
 * </ol>
 */
class OpenAiCompatibleGenerationAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final EditionCoordinate PEP_2024 = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static final String API_KEY = "sk-secret-12345";

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<Integer> nextStatus = new AtomicReference<>(200);

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] req = exchange.getRequestBody().readAllBytes();
            lastRequestBody.set(new String(req, StandardCharsets.UTF_8));
            int status = nextStatus.get();
            String body;
            if (status == 200) {
                body = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"见依据 1，顶点横坐标为 -b/2a。\"}}]}";
            } else {
                body = "{\"error\":{\"message\":\"model overloaded\"}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static Chunk chunk(String id, String text) {
        return new Chunk(id, "doc-1", PEP_2024, ChunkRole.EXPLANATION, text,
                new Chunk.Locator(45, "第3章 > 3.2 二次函数的图像", "12", 0, text.length()),
                List.of(), null, "h-" + id);
    }

    @Test
    @DisplayName("⛔ 空白 api-key 直接拒绝（空白应回落逐字摘录，不应构造适配器）")
    void blankApiKeyRejected() {
        assertThatThrownBy(() -> new OpenAiCompatibleGenerationAdapter(
                baseUrl, "  ", "gpt-x", 0.3, 1500, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("⛔ 空白 model 直接拒绝")
    void blankModelRejected() {
        assertThatThrownBy(() -> new OpenAiCompatibleGenerationAdapter(
                baseUrl, API_KEY, "  ", 0.3, 1500, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ 请求体含接地指令 + 逐字依据，且不含 api-key")
    void payloadContainsGroundingAndVerbatimBasisWithoutKey() {
        OpenAiCompatibleGenerationAdapter adapter = new OpenAiCompatibleGenerationAdapter(
                baseUrl, API_KEY, "gpt-x", 0.3, 1500, 5);

        List<Chunk> basis = List.of(
                chunk("11111111-1111-1111-1111-111111111111",
                        "二次函数 y=ax²+bx+c 的顶点坐标为 (-b/2a, (4ac-b²)/4a)。"),
                chunk("22222222-2222-2222-2222-222222222222",
                        "顶点横坐标为 x = -b/2a。"));

        adapter.synthesize("二次函数的顶点怎么求", "讲解", basis);

        String body = lastRequestBody.get();
        // 接地指令 + 逐字依据
        assertThat(body).contains("<依据原文>");
        assertThat(body).contains("只能使用");
        assertThat(body).contains(
                "二次函数 y=ax²+bx+c 的顶点坐标为 (-b/2a, (4ac-b²)/4a)。");
        assertThat(body).contains("顶点横坐标为 x = -b/2a。");
        assertThat(body).contains("二次函数的顶点怎么求");
        assertThat(body).contains("讲解");
        // 安全：api-key 绝不进请求体
        assertThat(body).doesNotContain(API_KEY);
    }

    @Test
    @DisplayName("✅ buildPayload 不泄露密钥字段，且结构正确")
    void buildPayloadNoKeyLeakage() throws Exception {
        OpenAiCompatibleGenerationAdapter adapter = new OpenAiCompatibleGenerationAdapter(
                baseUrl, API_KEY, "gpt-x", 0.3, 1500, 5);

        List<Chunk> basis = List.of(
                chunk("11111111-1111-1111-1111-111111111111", "顶点坐标公式"));

        Map<String, Object> payload = adapter.buildPayload("q", "讲解", basis);
        String serialized = MAPPER.writeValueAsString(payload);

        assertThat(payload).containsKeys("model", "messages", "temperature", "max_tokens", "stream");
        assertThat(payload.get("model")).isEqualTo("gpt-x");
        assertThat(payload.get("stream")).isEqualTo(false);
        assertThat(serialized).doesNotContain(API_KEY);
    }

    @Test
    @DisplayName("✅ 2xx 正常响应被解析为讲解正文")
    void happyPathParsesContent() {
        OpenAiCompatibleGenerationAdapter adapter = new OpenAiCompatibleGenerationAdapter(
                baseUrl, API_KEY, "gpt-x", 0.3, 1500, 5);

        List<Chunk> basis = List.of(
                chunk("11111111-1111-1111-1111-111111111111", "顶点坐标公式"));

        String result = adapter.synthesize("二次函数的顶点怎么求", "讲解", basis);

        assertThat(result).isEqualTo("见依据 1，顶点横坐标为 -b/2a。");
    }

    @Test
    @DisplayName("⛔ 非 2xx 响应归一为 GenerationModelException")
    void non2xxNormalized() {
        nextStatus.set(500);
        OpenAiCompatibleGenerationAdapter adapter = new OpenAiCompatibleGenerationAdapter(
                baseUrl, API_KEY, "gpt-x", 0.3, 1500, 5);

        List<Chunk> basis = List.of(
                chunk("11111111-1111-1111-1111-111111111111", "顶点坐标公式"));

        assertThatThrownBy(() -> adapter.synthesize("q", "讲解", basis))
                .isInstanceOf(GenerationModelException.class)
                .hasMessageNotContaining(API_KEY);
    }

    @Test
    @DisplayName("⛔ 响应缺 choices / 空 choices → GenerationModelException")
    void missingChoicesThrows() {
        OpenAiCompatibleGenerationAdapter adapter = new OpenAiCompatibleGenerationAdapter(
                baseUrl, API_KEY, "gpt-x", 0.3, 1500, 5);

        assertThatThrownBy(() -> adapter.parse("{\"choices\":[]}"))
                .isInstanceOf(GenerationModelException.class);
        assertThatThrownBy(() -> adapter.parse("{\"foo\":1}"))
                .isInstanceOf(GenerationModelException.class);
    }

    @Test
    @DisplayName("⛔ 响应内容空白 → GenerationModelException（避免回落 blank）")
    void blankContentThrows() {
        OpenAiCompatibleGenerationAdapter adapter = new OpenAiCompatibleGenerationAdapter(
                baseUrl, API_KEY, "gpt-x", 0.3, 1500, 5);

        assertThatThrownBy(() -> adapter.parse(
                "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}"))
                .isInstanceOf(GenerationModelException.class);
    }

    @Test
    @DisplayName("⛔ 非法 JSON 响应 → GenerationModelException")
    void malformedJsonThrows() {
        OpenAiCompatibleGenerationAdapter adapter = new OpenAiCompatibleGenerationAdapter(
                baseUrl, API_KEY, "gpt-x", 0.3, 1500, 5);

        assertThatThrownBy(() -> adapter.parse("not-json"))
                .isInstanceOf(GenerationModelException.class);
    }

    @Test
    @DisplayName("✅ parse 抽取 choices[0].message.content 并 strip")
    void parseExtractsContent() {
        OpenAiCompatibleGenerationAdapter adapter = new OpenAiCompatibleGenerationAdapter(
                baseUrl, API_KEY, "gpt-x", 0.3, 1500, 5);

        String content = adapter.parse(
                "{\"choices\":[{\"message\":{\"content\":\"  讲解正文  \"}}]}");
        assertThat(content).isEqualTo("讲解正文");
    }

    @Test
    @DisplayName("⛔ 超时（服务端休眠超适配器超时）→ GenerationModelException")
    void timeoutNormalized() throws Exception {
        // 用独立 server：收到请求后先睡 2s，适配器超时设为 1s
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/chat/completions", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"晚\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        slow.start();
        String slowUrl = "http://127.0.0.1:" + slow.getAddress().getPort();
        try {
            OpenAiCompatibleGenerationAdapter adapter = new OpenAiCompatibleGenerationAdapter(
                    slowUrl, API_KEY, "gpt-x", 0.3, 1500, 1);
            List<Chunk> basis = List.of(
                    chunk("11111111-1111-1111-1111-111111111111", "顶点坐标公式"));
            assertThatThrownBy(() -> adapter.synthesize("q", "讲解", basis))
                    .isInstanceOf(GenerationModelException.class);
        } finally {
            slow.stop(0);
        }
    }
}
