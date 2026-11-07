package cn.studykid.multiagent.infrastructure.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * OpenAI 兼容 Chat Completions 适配器（真实模型调用，覆盖 openai / deepseek /
 * workbuddy / ollama 等标准协议供应商）。
 *
 * <p><b>与 {@link MiniMaxModelGateway} 的关系</b>：MiniMax 是「OpenAI 兼容 +
 * 思考型草稿（{@code <think>}）+ 业务错误体（{@code base_resp}）」的特殊形态，
 * 故保留为独立的专用适配器；本类覆盖<b>标准</b> OpenAI 协议——
 * 请求组装、响应解析、错误归一化与之完全一致，仅不处理思考草稿与 base_resp。
 * 二者共享 {@link AbstractModelGateway} 的超时/限流/脱敏归一化底座。
 *
 * <p><b>⚠️ API Key 只出现在请求头</b>：绝不写入日志、异常消息或响应对象；
 * 供应商响应体（可能回显请求头）在入日志前一律过 {@link CredentialRedactor}。
 *
 * <p><b>工具回灌的多轮口径</b>：{@link ModelMessage} 携带 {@code toolCallId}
 * （tool 消息）与 {@code toolCalls}（assistant 消息），本适配器原样组成 OpenAI 的
 * {@code tool_calls} / {@code tool_call_id} 成对结构（与 MiniMax 同口径，2026-10-28 实测修正）。
 */
public final class OpenAiCompatibleModelGateway extends AbstractModelGateway
        implements ChatStreamGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleModelGateway.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 连接超时：与请求体超时分离，避免 DNS/握手卡死占满调用线程。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String defaultModel;

    public OpenAiCompatibleModelGateway(@NonNull String baseUrl,
                                        @NonNull String apiKey,
                                        @NonNull String defaultModel) {
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey 不能为空白（空白应装配 Fake 网关）");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(base + "/chat/completions");
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        log.info("装配 OpenAiCompatibleModelGateway: endpoint={}, defaultModel={}",
                endpoint, defaultModel);
    }

    @Override
    protected @NonNull ModelResponse invokeGuarded(@NonNull ModelRequest request) {
        String body = MAPPER.writeValueAsString(buildPayload(request));
        Duration timeout = request.timeout();
        HttpRequest http = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return callWithTimeout(() -> sendAndParse(http), timeout);
    }

    /**
     * 流式对话（真·LLM 多轮流式）。
     *
     * <p>与 {@link #invokeGuarded} 共享请求组装与错误归一化纪律；请求体 {@code stream=true}，
     * 响应体按 SSE 逐行读取，每个内容分片实时回吐给 {@code consumer.onDelta}，
     * 归整为 {@link ModelResponse}（含完整展示正文与用量）。
     */
    @Override
    public @NonNull ModelResponse streamChat(@NonNull ModelRequest request,
                                            ChatStreamGateway.StreamDeltaConsumer consumer) {
        Map<String, Object> payload = buildPayload(request);
        payload.put("stream", true);
        String body = MAPPER.writeValueAsString(payload);
        Duration timeout = request.timeout();
        HttpRequest http = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return streamAndParse(http, consumer);
    }

    /**
     * 读取 SSE 流并逐片解析（标准 OpenAI 协议，无思考草稿、无 base_resp）。
     */
    private @NonNull ModelResponse streamAndParse(
            @NonNull HttpRequest http,
            ChatStreamGateway.StreamDeltaConsumer consumer) {
        try {
            HttpResponse<InputStream> response =
                    client.send(http, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300) {
                String errBody = readAll(response.body());
                log.warn("模型流式调用失败: status={}, body={}",
                        status, CredentialRedactor.redact(truncate(errBody)));
                throw new ProviderStatusException(status, extractProviderError(errBody));
            }

            long promptTokens = 0;
            long completionTokens = 0;
            String finishReason = "stop";
            StringBuilder content = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            String frame = data.toString();
                            data.setLength(0);
                            if (frame.equals("[DONE]")) {
                                break;
                            }
                            JsonNode root = MAPPER.readTree(frame);
                            JsonNode choices = root.path("choices");
                            if (choices.isArray() && !choices.isEmpty()) {
                                JsonNode delta = choices.path(0).path("delta");
                                String piece = delta.path("content").asString("");
                                if (!piece.isEmpty()) {
                                    content.append(piece);
                                    consumer.onDelta(piece);
                                }
                                String fr = choices.path(0).path("finish_reason").asString("");
                                if (!fr.isEmpty()) {
                                    finishReason = fr;
                                }
                            }
                            JsonNode usage = root.path("usage");
                            if (!usage.isMissingNode()) {
                                promptTokens = usage.path("prompt_tokens").asLong(promptTokens);
                                completionTokens = usage.path("completion_tokens")
                                        .asLong(completionTokens);
                            }
                        }
                        continue;
                    }
                    if (line.startsWith(":")) {
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        String v = line.substring(5);
                        if (v.startsWith(" ")) {
                            v = v.substring(1);
                        }
                        data.append(v);
                    }
                }
            }

            return new ModelResponse(content.toString().strip(), Map.of(),
                    new ModelResponse.ModelUsage(promptTokens, completionTokens),
                    mapFinishReason(finishReason));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ModelGatewayException(ModelErrorKind.TIMEOUT, "模型流式调用超时");
        } catch (java.io.InterruptedIOException e) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException(ModelErrorKind.PROVIDER_ERROR, "模型流式调用被中断");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException(ModelErrorKind.PROVIDER_ERROR, "模型流式调用被中断");
        } catch (Exception e) {
            throw toModelException(e);
        }
    }

    private ModelGatewayException toModelException(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof ModelGatewayException mge) {
                return mge;
            }
            if (cursor instanceof ProviderStatusException pse) {
                return new ModelGatewayException(classify(pse), pse.getMessage());
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return new ModelGatewayException(ModelErrorKind.PROVIDER_ERROR,
                CredentialRedactor.safeMessage("模型流式解析异常", error));
    }

    /**
     * 组装 OpenAI 兼容请求体（包私有，供测试断言「无凭据字段、工具 Schema 形态」）。
     */
    Map<String, Object> buildPayload(ModelRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (!request.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        for (ModelMessage m : request.effectiveMessages()) {
            switch (m.role()) {
                case USER -> messages.add(Map.of("role", "user", "content", m.content()));
                case ASSISTANT -> messages.add(assistantMessage(m));
                case TOOL -> {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("role", "tool");
                    tool.put("tool_call_id", m.toolCallId() != null ? m.toolCallId()
                            : (m.toolName() == null ? "tool" : m.toolName()));
                    tool.put("content", m.content());
                    messages.add(tool);
                }
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", resolveModelName(request.modelId()));
        payload.put("messages", messages);
        payload.put("temperature", request.temperature());
        payload.put("max_tokens", request.maxTokens());
        payload.put("stream", false);
        List<Map<String, Object>> tools = translateToolSchemas(request.toolSchemas());
        if (!tools.isEmpty()) {
            payload.put("tools", tools);
        }
        return payload;
    }

    private static Map<String, Object> assistantMessage(ModelMessage m) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", m.content());
        if (!m.toolCalls().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ModelMessage.ToolCallEcho echo : m.toolCalls()) {
                calls.add(Map.of(
                        "id", echo.callId(),
                        "type", "function",
                        "function", Map.of(
                                "name", echo.toolName(),
                                "arguments", echo.argumentsJson())));
            }
            message.put("tool_calls", calls);
        }
        return message;
    }

    private static List<Map<String, Object>> translateToolSchemas(Map<String, Object> registry) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Object entry : registry.values()) {
            if (!(entry instanceof Map<?, ?> schema)) {
                continue;
            }
            Object name = schema.get("name");
            if (name == null) {
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", String.valueOf(name));
            Object description = schema.get("description");
            function.put("description", description == null ? "" : String.valueOf(description));
            Object inputSchema = schema.get("inputSchema");
            if (inputSchema instanceof Map<?, ?> params) {
                function.put("parameters", new LinkedHashMap<>(params));
            } else {
                log.warn("工具 inputSchema 形态非法，参数表按空声明: tool={}, 实际类型={}",
                        name, inputSchema == null ? "null" : inputSchema.getClass().getName());
                function.put("parameters", Map.of("type", "object", "properties", Map.of()));
            }
            tools.add(Map.of("type", "function", "function", function));
        }
        return tools;
    }

    /**
     * Agent 定义里的 modelRef 允许 {@code provider:model} 形态；
     * 带提供空前缀时剥掉前缀，纯名称直接使用。
     */
    private String resolveModelName(String modelId) {
        int idx = modelId.indexOf(':');
        String name = idx >= 0 ? modelId.substring(idx + 1) : modelId;
        return name.isBlank() ? defaultModel : name;
    }

    private ModelResponse sendAndParse(HttpRequest http) {
        HttpResponse<String> response;
        try {
            response = client.send(http, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ModelGatewayException(ModelErrorKind.TIMEOUT, "模型调用超时");
        } catch (java.io.InterruptedIOException e) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException(ModelErrorKind.PROVIDER_ERROR, "模型调用被中断");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException(ModelErrorKind.PROVIDER_ERROR, "模型调用被中断");
        } catch (java.io.IOException e) {
            throw new ProviderStatusException(-1, "网络失败: " + e.getClass().getSimpleName());
        }
        int status = response.statusCode();
        if (status >= 300) {
            log.warn("模型调用失败: status={}, body={}",
                    status, CredentialRedactor.redact(truncate(response.body())));
            throw new ProviderStatusException(status, extractProviderError(response.body()));
        }
        return parseCompletion(response.body());
    }

    ModelResponse parseCompletion(String responseBody) {
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ProviderStatusException(200, "响应缺少 choices");
        }
        JsonNode choice = choices.path(0);
        JsonNode message = choice.path("message");

        String content = message.path("content").asString("");
        List<JsonNode> toolCalls = new ArrayList<>();
        JsonNode callsNode = message.path("tool_calls");
        if (callsNode.isArray()) {
            callsNode.forEach(toolCalls::add);
        }
        if (content.isEmpty() && !toolCalls.isEmpty()) {
            content = toolCalls.get(0).path("function").path("name").asString("");
        }

        Map<String, ModelResponse.ToolCallRequest> parsed = new LinkedHashMap<>();
        for (JsonNode call : toolCalls) {
            String name = call.path("function").path("name").asString("");
            if (name.isEmpty()) {
                continue;
            }
            Map<String, Object> arguments = parseArguments(
                    call.path("function").path("arguments").asString(""));
            String key = call.hasNonNull("id") ? call.get("id").asString() : name;
            parsed.put(key, new ModelResponse.ToolCallRequest(name, arguments, key));
        }

        JsonNode usage = root.path("usage");
        ModelResponse.ModelUsage modelUsage = new ModelResponse.ModelUsage(
                usage.path("prompt_tokens").asLong(0),
                usage.path("completion_tokens").asLong(0));

        return new ModelResponse(content, parsed, modelUsage,
                mapFinishReason(choice.path("finish_reason").asString("stop")));
    }

    private static Map<String, Object> parseArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw,
                    new TypeReference<Map<String, Object>>() {
                    });
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            log.warn("工具参数非法 JSON，按空参数交闸门校验: {}", e.getMessage());
            return Map.of();
        }
    }

    private static ModelResponse.FinishReason mapFinishReason(String raw) {
        return switch (raw) {
            case "length" -> ModelResponse.FinishReason.LENGTH;
            case "tool_calls", "function_call" -> ModelResponse.FinishReason.TOOL_CALLS;
            case "content_filter" -> ModelResponse.FinishReason.CONTENT_FILTER;
            default -> ModelResponse.FinishReason.STOP;
        };
    }

    private static String extractProviderError(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            String msg = root.path("error").path("message").asString("");
            if (!msg.isEmpty()) {
                return msg;
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 错误体：截断原文
        }
        return truncate(CredentialRedactor.redact(body));
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 512 ? text : text.substring(0, 512) + "...";
    }

    private static String readAll(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 非 2xx 归一化：HTTP 状态码随消息带出，供 {@link #classify} 与供应商错误码共同判定。
     */
    static final class ProviderStatusException extends RuntimeException {
        private final int statusCode;

        ProviderStatusException(int statusCode, String message) {
            super("HTTP " + statusCode + ": " + message);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }

    @Override
    protected ModelErrorKind classify(@NonNull Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof ProviderStatusException e) {
                return switch (e.statusCode()) {
                    case 401, 403 -> ModelErrorKind.UNAUTHORIZED;
                    case 429 -> ModelErrorKind.RATE_LIMITED;
                    case 400 -> ModelErrorKind.INVALID_REQUEST;
                    default -> ModelErrorKind.PROVIDER_ERROR;
                };
            }
            if (cursor instanceof java.net.http.HttpTimeoutException
                    || cursor instanceof java.util.concurrent.TimeoutException) {
                return ModelErrorKind.TIMEOUT;
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return super.classify(error);
    }
}
