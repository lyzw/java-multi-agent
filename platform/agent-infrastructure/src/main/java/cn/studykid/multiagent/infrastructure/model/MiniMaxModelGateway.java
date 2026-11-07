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
 * MiniMax OpenAI 兼容 Chat Completions 适配器（真实模型调用）。
 *
 * <p><b>与 {@link AbstractModelGateway} 的分工</b>：归一化（超时/限流/鉴权 →
 * {@link ModelErrorKind}）与凭据脱敏由基类统一兜底，本类只负责
 * 请求组装、HTTP 调用、响应解析，并把非 2xx 响应归一化为带状态码的
 * {@link ProviderStatusException} 供 {@link #classify} 识别。
 *
 * <p><b>⚠️ API Key 只出现在请求头</b>：绝不写入日志、异常消息或响应对象；
 * 供应商响应体（可能回显请求头）在入日志前一律过
 * {@link CredentialRedactor}。
 *
 * <p><b>工具回灌的多轮口径</b>：{@link ModelMessage} 携带
 * {@code toolCallId}（tool 消息）与 {@code toolCalls}（assistant 消息），
 * 本适配器原样组成 OpenAI 的 {@code tool_calls} / {@code tool_call_id} 成对结构。
 * ⚠️ 旧实现按「工具名即 call id」重组且只发 tool 消息，
 * 供应商在自己的 assistant 里找不到该标识即以
 * {@code 400 invalid params, tool result's tool id(...) not found (2013)}
 * 拒收<b>整次请求</b>（2026-10-28 真实 MiniMax 端到端实测）。
 */
public final class MiniMaxModelGateway extends AbstractModelGateway implements ChatStreamGateway {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxModelGateway.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 连接超时：与请求体超时分离，避免 DNS/握手卡死占满调用线程。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 推理草稿标签（拼接而成：源码里不写字面标签，避免被文本处理链吞掉）。 */
    private static final String THINK_OPEN = "<" + "think" + ">";
    private static final String THINK_CLOSE = "<" + "/think" + ">";

    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String defaultModel;

    public MiniMaxModelGateway(@NonNull String baseUrl,
                               @NonNull String apiKey,
                               @NonNull String defaultModel) {
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("MiniMax apiKey 不能为空白（空白应装配 Fake 网关）");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(base + "/chat/completions");
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        log.info("装配 MiniMaxModelGateway: endpoint={}, defaultModel={}", endpoint, defaultModel);
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
     * <p>与 {@link #invokeGuarded} 共享请求组装与错误归一化纪律，区别仅在：
     * <ul>
     *   <li>请求体 {@code stream=true}；</li>
     *   <li>响应体按 SSE 逐行读取，每个内容分片经
     *       {@link #ThinkFilter} 分流——答案正文实时回吐给 {@code consumer.onDelta}，
     *       推理草稿（思考过程）实时回吐给 {@code consumer.onThinking}；</li>
     *   <li>归整为 {@link ModelResponse}（含完整展示正文与用量）。</li>
     * </ul>
     *
     * <p>⚠️ <b>思考过程现在会展示给管理员</b>：MiniMax-M2.5 把思考混在 {@code content} 里
     * （{@code <think>…</think>}）。本方法用 {@link #ThinkFilter} 把思考与答案<b>分流</b>——
     * 答案走 {@code onDelta}（最终呈现），思考走 {@code onThinking}（仅控制台工具展示，
     * 不可回落到面向学生的通道）。思考内容未经答案级安全审核，仅对平台管理员可见，
     * 与红线 R6「平台控制台不展示<b>运行输出</b>」不冲突（这里是管理员本人对话，
     * 不是回放租户 Run 输出）。
     */
    @Override
    public @NonNull ModelResponse streamChat(@NonNull ModelRequest request,
                                            ChatStreamGateway.StreamDeltaConsumer consumer) {
        Map<String, Object> payload = buildPayload(request);
        // ⚠️ 覆盖 buildPayload 里写死的 stream=false
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
     * 读取 SSE 流并逐片解析。
     *
     * <p>⚠️ 复用与 {@link #sendAndParse} 相同的状态码与业务错误判定
     * （HTTP >= 300 与 {@code base_resp.status_code != 0} 均归一为异常），
     * 保证流式与一次性调用<b>错误语义一致</b>。
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
                log.warn("MiniMax 流式调用失败: status={}, body={}",
                        status, CredentialRedactor.redact(truncate(errBody)));
                // ⚠️ 归一为 ProviderStatusException，交外层 catch 转 ModelGatewayException
                throw new ProviderStatusException(status, extractProviderError(errBody));
            }

            // 流式累计状态：状态机式过滤推理草稿，增量单调、不回撤已发文本
            ThinkFilter filter = new ThinkFilter();
            long promptTokens = 0;
            long completionTokens = 0;
            String finishReason = "stop";

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        // SSE 帧以空行结束：处理已累积的 data
                        if (data.length() > 0) {
                            String frame = data.toString();
                            data.setLength(0);
                            if (frame.equals("[DONE]")) {
                                break;
                            }
                            JsonNode root = MAPPER.readTree(frame);
                            long bizStatus = root.path("base_resp").path("status_code").asLong(0);
                            if (bizStatus != 0) {
                                throw new ProviderStatusException(200,
                                        "base_resp.status_code=" + bizStatus + " "
                                                + root.path("base_resp").path("status_msg").asString(""));
                            }
                            JsonNode choices = root.path("choices");
                            if (choices.isArray() && !choices.isEmpty()) {
                                JsonNode delta = choices.path(0).path("delta");
                                // ⚠️ 只消费 content 增量：MiniMax-M2.5 把思考混在 content 里，
                                //    由 ThinkFilter 拆成「答案」(onDelta) 与「思考」(onThinking)。
                                String content = delta.path("content").asString("");
                                if (!content.isEmpty()) {
                                    filter.consume(
                                            content,
                                            piece -> {
                                                if (!piece.isEmpty()) {
                                                    consumer.onDelta(piece);
                                                }
                                            },
                                            piece -> {
                                                if (!piece.isEmpty()) {
                                                    consumer.onThinking(piece);
                                                }
                                            });
                                }
                                String fr = choices.path(0).path("finish_reason").asString("");
                                if (!fr.isEmpty()) {
                                    finishReason = fr;
                                }
                            }
                            JsonNode usage = root.path("usage");
                            if (!usage.isMissingNode()) {
                                promptTokens = usage.path("prompt_tokens").asLong(promptTokens);
                                completionTokens = usage.path("completion_tokens").asLong(completionTokens);
                            }
                        }
                        continue;
                    }
                    if (line.startsWith(":")) {
                        // 注释行：SSE keep-alive / 终止信号，忽略
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        String v = line.substring(5);
                        if (v.startsWith(" ")) {
                            v = v.substring(1);
                        }
                        data.append(v);
                    }
                    // event:/id: 等字段在本场景忽略
                }
            }

            String finalContent = filter.finishDisplay();
            return new ModelResponse(finalContent, Map.of(),
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
            // ⚠️ 所有非 2xx / 网络 / 业务错误统一归一为 ModelGatewayException（含 kind）
            throw toModelException(e);
        }
    }

    /**
     * 流式推理草稿状态机过滤器（答案 / 思考分流）。
     *
     * <p>把累计原始正文里的 {@code <think>…</think>} 拆成两路：
     * <ul>
     *   <li><b>块外文本</b> → 答案，交给 {@code displayOut}（经 {@code consumer.onDelta} 回吐）；</li>
     *   <li><b>块内文本</b> → 思考过程，交给 {@code thinkOut}（经 {@code consumer.onThinking} 回吐）。</li>
     * </ul>
     * 增量消费时实时回吐「本次新增且已确认安全」的片段，保证：
     * <ul>
     *   <li>已发出的答案文本<b>绝不被撤回</b>（即便后续出现推理块）；</li>
     *   <li>跨分片拆分的半截标签（如 {@code <thi}、{@code </think}）不会泄漏到前端；</li>
     *   <li>未闭合的推理块（流式被截断）<b>整段丢弃</b>，绝不外泄不完整的草稿。</li>
     * </ul>
     *
     * <p>与一次性 {@link #stripReasoning} 的「只保留最后一个 {@code </think>} 之后」语义不同：
     * 流式里推理块<b>之前</b>已发出的正文（如本例的「你好」）是真实答案，若因后续出现
     * 推理块而整体丢弃会造成已显文本「消失」，故此处保留块外全部文本、只把块内文本转交思考流。
     */
    private static final class ThinkFilter {
        private boolean inThink = false;
        private final StringBuilder display = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder pending = new StringBuilder();
        private int displayEmitted = 0;
        private int thinkingEmitted = 0;

        /**
         * 消费一段原始增量：答案片段交 {@code displayOut}，推理草稿交 {@code thinkOut}。
         * 两个回调可能被多次调用（逐片回吐），也可能都不调用（本片仅含标签/空白）。
         */
        void consume(String chunk, Consumer<String> displayOut, Consumer<String> thinkOut) {
            if (chunk.isEmpty()) {
                return;
            }
            pending.append(chunk);
            int i = 0;
            int n = pending.length();
            while (i < n) {
                if (inThink) {
                    int close = pending.indexOf(THINK_CLOSE, i);
                    if (close < 0) {
                        // 暂无完整闭合：尾部可能含半截闭合标签，保留 pending 待续；
                        // 此前已确认安全的部分作为思考片段实时回吐。
                        int safe = safePrefixEndIndex(pending, i, THINK_CLOSE);
                        if (safe > i) {
                            String piece = pending.substring(i, safe);
                            thinking.append(piece);
                            thinkOut.accept(piece);
                            thinkingEmitted = thinking.length();
                        }
                        i = safe;
                        break;
                    }
                    if (close > i) {
                        String piece = pending.substring(i, close);
                        thinking.append(piece);
                        thinkOut.accept(piece);
                        thinkingEmitted = thinking.length();
                    }
                    inThink = false;
                    i = close + THINK_CLOSE.length();
                } else {
                    int open = pending.indexOf(THINK_OPEN, i);
                    if (open < 0) {
                        // 暂无完整开启：尾部可能含半截开启标签，提交其前的安全文本
                        int safe = safePrefixEndIndex(pending, i, THINK_OPEN);
                        if (safe > i) {
                            String piece = pending.substring(i, safe);
                            display.append(piece);
                            displayOut.accept(piece);
                            displayEmitted = display.length();
                        }
                        i = safe;
                        break;
                    }
                    if (open > i) {
                        String piece = pending.substring(i, open);
                        display.append(piece);
                        displayOut.accept(piece);
                        displayEmitted = display.length();
                    }
                    inThink = true;
                    i = open + THINK_OPEN.length();
                }
            }
            pending.delete(0, i);
        }

        /** 终态：返回完整展示正文（丢弃仍处推理块内的内容以及任何半截标签）。 */
        String finishDisplay() {
            if (!inThink) {
                int safe = safePrefixEndIndex(pending, 0, THINK_OPEN);
                if (safe > 0) {
                    String piece = pending.substring(0, safe);
                    display.append(piece);
                    displayEmitted = display.length();
                }
            }
            pending.setLength(0);
            return display.toString().strip();
        }

        /**
         * 返回 {@code [start, n)} 中可安全提交的最大右端索引：即不落在任何
         * {@code tag} 真前缀（作为尾缀出现）之内的位置——半截标签留待后续分片补全。
         */
        private static int safePrefixEndIndex(CharSequence s, int start, String tag) {
            int n = s.length();
            for (int len = 1; len < tag.length(); len++) {
                String prefix = tag.substring(0, len);
                if (n - prefix.length() >= start
                        && s.subSequence(n - prefix.length(), n).toString().equals(prefix)) {
                    return n - prefix.length();
                }
            }
            return n;
        }
    }

    private static String readAll(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** 把流式解析期的异常归一成 {@link ModelGatewayException}（保留已归一化的 kind）。 */
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
                    // ⚠️ 标识必须来自模型上一轮下发的 callId（2026-10-28 真实实测）：
                    //    此前填的是**工具名**，对方在自己的 assistant 里
                    //    找不到同名标识 → 整次请求 400
                    //    （invalid params, tool result's tool id(...) not found）。
                    //    工具名兜底只适用于没有调用标识的历史消息，
                    //    且此时上一条 assistant 也没带 tool_calls，双方仍自洽。
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

    /**
     * assistant 消息：纯文本，或<b>带 {@code tool_calls}</b> 的调用回显。
     *
     * <p>⚠️ {@code tool_calls} 不是可选项：OpenAI 兼容协议要求
     * 「发起调用的 assistant 消息」与「{@code tool_call_id} 指向它的 tool 消息」
     * 成对出现。只发 tool 消息时，供应商按标识去上一条 assistant 里找调用，
     * 找不到便拒收整个请求（MiniMax 2013 / HTTP 400）。
     */
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

    /**
     * 领域工具登记表 {@code id -> {name, description, inputSchema}} →
     * OpenAI {@code {type:function, function:{...}}} 列表。
     */
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
                // ⚠️ 降级必须**报出来**（2026-10-28 真实模型端到端实测后补）：
                //    装配侧给出非 Map 形态时这里会静默变成「无参数工具」，
                //    模型照样照提示词喊出工具名、却交不出任何参数，
                //    而现场只剩一句「缺少必填参数」，查不到是哪一环把 Schema 弄丢的。
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
            // 响应体可能回显请求内容，入日志前必须脱敏
            log.warn("MiniMax 调用失败: status={}, body={}",
                    status, CredentialRedactor.redact(truncate(response.body())));
            throw new ProviderStatusException(status, extractProviderError(response.body()));
        }
        return parseCompletion(response.body());
    }

    ModelResponse parseCompletion(String responseBody) {
        JsonNode root = MAPPER.readTree(responseBody);
        // MiniMax 鉴权/配额失败也返回 HTTP 200，真错藏在 base_resp.status_code
        long bizStatus = root.path("base_resp").path("status_code").asLong(0);
        if (bizStatus != 0) {
            throw new ProviderStatusException(200,
                    "base_resp.status_code=" + bizStatus + " "
                            + root.path("base_resp").path("status_msg").asString(""));
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ProviderStatusException(200, "响应缺少 choices");
        }
        JsonNode choice = choices.path(0);
        JsonNode message = choice.path("message");

        // MiniMax-M2.5 是思考型模型：推理草稿以 think 标签段**混在 content 里**返回。
        // 不剥离就会把草稿原文落进 run.result 给学生看到，
        // 且让「编造出处 / 敏感信息」检测读到一段没人审过的中间文本。
        String content = stripReasoning(message.path("content").asString(""));
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

    /**
     * 剥离 MiniMax-M2.5 混在 {@code content} 里的推理草稿。
     *
     * <p>规则：<b>只保留最后一个闭合标签之后的正文</b>。思考型模型可能产出多段草稿，
     * 正文永远在最后一段之后；无闭合标签（草稿被长度截断）时<b>原样返回</b>，
     * 宁可可读地多发一段草稿，也不把可能被误判成正文的内容静默丢掉。
     */
    static @NonNull String stripReasoning(@NonNull String content) {
        if (content.isEmpty() || (!content.contains(THINK_OPEN)
                && !content.contains(THINK_CLOSE))) {
            return content;
        }
        int lastClose = content.lastIndexOf(THINK_CLOSE);
        if (lastClose < 0) {
            log.warn("模型返回的推理草稿未闭合，正文无法可靠切分，按原文保留");
            return content;
        }
        return content.substring(lastClose + THINK_CLOSE.length()).strip();
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
            // 参数非法不炸整次调用：闸门侧会按 Schema 拒绝这次工具调用
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
            String baseMsg = root.path("base_resp").path("status_msg").asString("");
            if (!baseMsg.isEmpty()) {
                return baseMsg;
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 错误体：截断原文，避免把整页 HTML 带进日志
        }
        return truncate(CredentialRedactor.redact(body));
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 512 ? text : text.substring(0, 512) + "...";
    }

    /**
     * 非 2xx 归一化：HTTP 状态码随消息带出，供 {@link #classify} 与
     * MiniMax 业务错误码（{@code status_code}）共同判定。
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
