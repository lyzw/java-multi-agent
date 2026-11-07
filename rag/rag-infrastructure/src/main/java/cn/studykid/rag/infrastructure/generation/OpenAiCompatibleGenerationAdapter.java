package cn.studykid.rag.infrastructure.generation;

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

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.ports.GenerationModelException;
import cn.studykid.rag.ports.GenerationModelPort;

/**
 * OpenAI 兼容 Chat Completions 生成适配器（真实模型调用）。
 *
 * <p><b>覆盖标准 OpenAI 协议</b>（openai / deepseek / workbuddy / ollama / 本地 vLLM 等），
 * 与平台侧 {@code OpenAiCompatibleModelGateway} 同口径、但<b>完全独立</b>——
 * RAG 是独立 reactor、独立库，按用户定案「RAG 自带配置」自包含，不跨进程依赖平台。
 *
 * <p><b>⚠️ 接地铁律</b>：系统提示要求模型<b>只能使用【依据原文】中的逐字片段作答</b>，
 * 禁止引入片段之外的事实 / 公式 / 结论；不得抄写原文本身，只给讲解并自然引用编号。
 * 端口不持有任何依据校验逻辑——传入的 {@code basis} 已由调用方滤净。
 *
 * <p><b>⚠️ 失败归一化</b>：超时 / 非 2xx / 空内容 / 响应非法，统一抛
 * {@link GenerationModelException}（不携带密钥与响应体原文）。
 *
 * <p><b>⚠️ 密钥只进请求头</b>：api-key 经 {@code Authorization: Bearer} 发送，
 * 绝不写入日志或异常消息。
 */
public final class OpenAiCompatibleGenerationAdapter implements GenerationModelPort {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleGenerationAdapter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 系统提示：把「只能基于给定原文」的铁律写死，杜绝模型自由发挥。 */
    private static final String SYSTEM_PROMPT = """
            你是一名初中数学、物理、化学的错题归因辅导老师。下面【依据原文】中的内容\
            是从教材中逐字摘录的片段。请严格遵循以下规则：
            1. 只能使用【依据原文】中的内容作答，禁止引入原文之外的任何公式、结论、数据或常识性陈述；
            2. 用学生能理解的语言解释，自然地引用依据编号（如「见依据 1」）；
            3. 如果依据不足以回答学生的问题，直接如实说明「依据不足以回答这个问题」，不要编造；
            4. 不要重复抄写【依据原文】本身，只给出讲解。
            """;

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;
    private final HttpClient client;

    public OpenAiCompatibleGenerationAdapter(@NonNull String baseUrl, @NonNull String apiKey,
                                            @NonNull String model, double temperature,
                                            int maxTokens, int timeoutSeconds) {
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey 不能为空白（空白应回落逐字摘录）");
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空白");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(base + "/chat/completions");
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        log.info("装配 OpenAiCompatibleGenerationAdapter: endpoint={}, model={}", endpoint, model);
    }

    @Override
    public @NonNull String synthesize(@NonNull String query, @NonNull String modeLabel,
                                     @NonNull List<Chunk> basis) {
        String body = MAPPER.writeValueAsString(buildPayload(query, modeLabel, basis));
        HttpRequest http = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 300) {
                throw new GenerationModelException(
                        "模型调用失败 HTTP " + status + ": " + truncate(response.body()));
            }
            return parse(response.body());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new GenerationModelException("模型调用超时", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GenerationModelException("模型调用被中断", e);
        } catch (java.io.IOException e) {
            throw new GenerationModelException("模型调用网络失败: " + e.getMessage(), e);
        }
    }

    /** 解析 OpenAI Chat Completions 响应，抽出讲解正文。 */
    String parse(String responseBody) {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (RuntimeException e) {
            // ⚠️ 归一化：Jackson 3 的解析异常是 unchecked，
            //    必须收敛成 GenerationModelException 让调用方回落逐字摘录。
            throw new GenerationModelException("响应 JSON 非法: " + e.getMessage(), e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new GenerationModelException("响应缺少 choices");
        }
        String content = choices.path(0).path("message").path("content").asString("");
        if (content.isBlank()) {
            throw new GenerationModelException("模型返回空内容");
        }
        return content.strip();
    }

    /** 组装请求体（包私有，供测试断言「无凭据字段、含逐字片段与接地指令」）。 */
    Map<String, Object> buildPayload(String query, String modeLabel, List<Chunk> basis) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", buildUserContent(query, modeLabel, basis)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        payload.put("stream", false);
        return payload;
    }

    /** 构造用户消息：学生问题 + 模式 + 编号逐字依据。 */
    private static String buildUserContent(String query, String modeLabel, List<Chunk> basis) {
        StringBuilder sb = new StringBuilder();
        sb.append("<学生问题>\n").append(query.strip()).append("\n</学生问题>\n\n");
        sb.append("<讲解模式>").append(modeLabel).append("</讲解模式>\n\n");
        sb.append("<依据原文>\n");
        for (int i = 0; i < basis.size(); i++) {
            Chunk c = basis.get(i);
            sb.append("【依据 ").append(i + 1).append("】")
                    .append(c.edition().display()).append(" · ").append(c.locator().display())
                    .append('\n');
            sb.append(c.text().strip()).append('\n');
            if (i < basis.size() - 1) {
                sb.append('\n');
            }
        }
        sb.append("</依据原文>\n\n");
        sb.append("请仅依据上述【依据原文】给出讲解，并自然地引用依据编号。"
                + "若依据不足以回答，请如实说明。不要重复抄写原文。");
        return sb.toString();
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 512 ? text : text.substring(0, 512) + "...";
    }
}
