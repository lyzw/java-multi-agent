package cn.studykid.multiagent.infrastructure.model;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAiCompatibleModelGateway 行为验证（标准 OpenAI Chat Completions 协议）。
 *
 * <p>覆盖：请求体形态、标准响应解析、finish_reason 映射、错误归一化（401 → UNAUTHORIZED）、
 * 真实 HTTP 往返（一次性调用 + 流式）。
 */
class OpenAiCompatibleModelGatewayTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            String reqBody = new String(reqBytes, StandardCharsets.UTF_8);
            lastBody.set(reqBody);
            byte[] bytes;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (reqBody.contains("\"stream\":true") || reqBody.contains("\"stream\": true")) {
                // SSE 流式响应：逐帧回吐增量，以 [DONE] 结束
                String frame = "data: {\"choices\":[{\"delta\":{\"content\":\"2+3=5\"},"
                        + "\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4}}\n\n"
                        + "data: [DONE]\n\n";
                bytes = frame.getBytes(StandardCharsets.UTF_8);
            } else {
                String response = """
                        {
                          "choices": [{
                            "index": 0,
                            "message": {"role": "assistant", "content": "2+3=5"},
                            "finish_reason": "stop"
                          }],
                          "usage": {"prompt_tokens": 10, "completion_tokens": 4}
                        }
                        """;
                bytes = response.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, bytes.length);
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

    private @NonNull ModelRequest sampleRequest() {
        return new ModelRequest("gpt-4o", "你是一个数学老师", "1+1=?", 0.7, 512,
                Duration.ofSeconds(30), Map.of());
    }

    @Test
    @DisplayName("✅ 请求体为标准 OpenAI 形态：model / messages / temperature / max_tokens / stream=false")
    void buildsStandardOpenAiPayload() {
        OpenAiCompatibleModelGateway gw =
                new OpenAiCompatibleModelGateway("https://api.openai.com/v1", "sk-test", "gpt-4o");
        Map<String, Object> payload = gw.buildPayload(sampleRequest());

        assertThat(payload.get("model")).isEqualTo("gpt-4o");
        assertThat(payload.get("stream")).isEqualTo(false);
        assertThat(payload.get("temperature")).isEqualTo(0.7);
        assertThat(payload.get("max_tokens")).isEqualTo(512);
        List<?> messages = (List<?>) payload.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(((Map<?, ?>) messages.get(0)).get("role")).isEqualTo("system");
        assertThat(((Map<?, ?>) messages.get(1)).get("role")).isEqualTo("user");
    }

    @Test
    @DisplayName("✅ 解析标准 OpenAI 响应：content / tool_calls / usage")
    void parsesStandardOpenAiResponse() {
        OpenAiCompatibleModelGateway gw =
                new OpenAiCompatibleModelGateway("https://api.openai.com/v1", "sk-test", "gpt-4o");
        String body = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": "answer",
                      "tool_calls": [{
                        "id": "call_1",
                        "type": "function",
                        "function": {"name": "calc", "arguments": "{\\"x\\":1}"}
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {"prompt_tokens": 7, "completion_tokens": 9}
                }
                """;
        ModelResponse resp = gw.parseCompletion(body);

        assertThat(resp.content()).isEqualTo("answer");
        assertThat(resp.toolCalls()).containsKey("call_1");
        assertThat(resp.toolCalls().get("call_1").toolName()).isEqualTo("calc");
        assertThat(resp.usage().inputTokens()).isEqualTo(7);
        assertThat(resp.usage().outputTokens()).isEqualTo(9);
        assertThat(resp.finishReason()).isEqualTo(ModelResponse.FinishReason.TOOL_CALLS);
    }

    @Test
    @DisplayName("✅ 真实 HTTP 往返：complete() 拿到模型回复")
    void completeRoundTrip() {
        OpenAiCompatibleModelGateway gw = new OpenAiCompatibleModelGateway(baseUrl, "sk-test", "gpt-4o");
        ModelResponse resp = gw.complete(sampleRequest());

        assertThat(resp.content()).isEqualTo("2+3=5");
        assertThat(resp.usage().inputTokens()).isEqualTo(10);
        assertThat(resp.usage().outputTokens()).isEqualTo(4);
        // 入站请求体确实打到了约定的端点且形态正确
        assertThat(lastBody.get()).contains("\"model\":\"gpt-4o\"");
        assertThat(lastBody.get()).contains("\"content\":\"1+1=?\"");
    }

    @Test
    @DisplayName("✅ 401 响应归一为 UNAUTHORIZED（不丢失分类 → 不误判可重试）")
    void unauthorizedNormalized() throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String err = "{\"error\":{\"message\":\"invalid api key\"}}";
            byte[] bytes = err.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        OpenAiCompatibleModelGateway gw = new OpenAiCompatibleModelGateway(baseUrl, "bad", "gpt-4o");
        assertThatThrownBy(() -> gw.complete(sampleRequest()))
                .isInstanceOf(ModelGatewayException.class)
                .satisfies(e -> assertThat(((ModelGatewayException) e).kind())
                        .isEqualTo(ModelGateway.ModelErrorKind.UNAUTHORIZED));
    }

    @Test
    @DisplayName("✅ 空 apiKey 拒绝装配（回落 Fake 由调用方决定，不在此构造占位）")
    void rejectsBlankKey() {
        assertThatThrownBy(() -> new OpenAiCompatibleModelGateway(baseUrl, "  ", "gpt-4o"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ 流式 chat 实时回吐增量并归整")
    void streamRoundTrip() {
        OpenAiCompatibleModelGateway gw = new OpenAiCompatibleModelGateway(baseUrl, "sk-test", "gpt-4o");
        StringBuilder delta = new StringBuilder();
        ModelResponse resp = gw.streamChat(sampleRequest(), new ChatSink(delta));

        assertThat(delta.toString()).isEqualTo("2+3=5");
        assertThat(resp.content()).isEqualTo("2+3=5");
        assertThat(resp.usage().inputTokens()).isEqualTo(10);
    }

    private static final class ChatSink
            implements cn.studykid.multiagent.ports.model.ChatStreamGateway.StreamDeltaConsumer {
        private final StringBuilder sink;

        ChatSink(StringBuilder sink) {
            this.sink = sink;
        }

        @Override
        public void onDelta(@NonNull String text) {
            sink.append(text);
        }

        @Override
        public void onThinking(@NonNull String text) {
            // 标准 OpenAI 无思考流，忽略
        }
    }
}
