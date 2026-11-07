package cn.studykid.multiagent.infrastructure.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * MiniMax 流式解析真实集成测试（不走网络到真实供应商，用本地 HttpServer 模拟 SSE）。
 *
 * <p><b>验证命题</b>：
 * <ol>
 *   <li>OpenAI 兼容 {@code stream:true} 的 SSE 帧能被逐片解析；</li>
 *   <li>答案增量经 {@link ChatStreamGateway.StreamDeltaConsumer#onDelta} 实时回吐；</li>
 *   <li>{@code <think>…</think>} 推理草稿被<b>分流</b>到
 *       {@link ChatStreamGateway.StreamDeltaConsumer#onThinking}（展示给管理员），
 *       既不进入答案增量，也不被丢弃；</li>
 *   <li>终态 {@link ModelResponse} 含完整展示正文与用量。</li>
 * </ol>
 */
class MiniMaxModelGatewayStreamTest {

    private static final String SSE_BODY = String.join("\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"content\":\"<think>我在推算步骤</think>世界\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}",
            "",
            "data: [DONE]",
            "",
            "");

    @Test
    void streamParsesDeltasAndStripsReasoning() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = SSE_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            MiniMaxModelGateway gateway = new MiniMaxModelGateway(
                    "http://localhost:" + port + "/v1", "sk-test", "MiniMax-M2.5");

            List<String> deltas = new ArrayList<>();
            List<String> thinking = new ArrayList<>();
            ModelRequest request = new ModelRequest("MiniMax-M2.5", "", "hi", 0.7, 100,
                    Duration.ofSeconds(30), Map.of(), List.of(ModelMessage.user("hi")));

            ModelResponse response = gateway.streamChat(request, new ChatStreamGateway.StreamDeltaConsumer() {
                @Override
                public void onDelta(String token) {
                    deltas.add(token);
                }

                @Override
                public void onThinking(String token) {
                    thinking.add(token);
                }
            });

            // ⚠️ 推理草稿 "<think>我在推算步骤</think>" 必须被分流到 onThinking，
            // 既不进答案增量，也不被丢弃：增量只有 ["你好", "世界"]。
            assertThat(deltas).containsExactly("你好", "世界");
            assertThat(thinking).containsExactly("我在推算步骤");
            // 终态正文同样不含推理段，但保留推理块之前的真实答案 "你好"
            assertThat(response.content()).isEqualTo("你好世界");
            assertThat(response.usage().inputTokens()).isEqualTo(5);
            assertThat(response.usage().outputTokens()).isEqualTo(3);
            assertThat(response.finishReason()).isEqualTo(ModelResponse.FinishReason.STOP);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamPropagatesNormalizedError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "unauthorized".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            MiniMaxModelGateway gateway = new MiniMaxModelGateway(
                    "http://localhost:" + port + "/v1", "sk-test", "MiniMax-M2.5");
            ModelRequest request = new ModelRequest("MiniMax-M2.5", "", "hi", 0.7, 100,
                    Duration.ofSeconds(30), Map.of(), List.of(ModelMessage.user("hi")));

            AtomicInteger calls = new AtomicInteger();
            try {
                gateway.streamChat(request, t -> calls.incrementAndGet());
                org.assertj.core.api.Assertions.fail("应抛出归一化异常");
            } catch (cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException e) {
                // ⚠️ 401 必须归一为 UNAUTHORIZED（与 complete() 同源）
                assertThat(e.kind())
                        .isEqualTo(cn.studykid.multiagent.ports.model.ModelGateway.ModelErrorKind.UNAUTHORIZED);
            }
            assertThat(calls.get()).isZero();
        } finally {
            server.stop(0);
        }
    }
}
