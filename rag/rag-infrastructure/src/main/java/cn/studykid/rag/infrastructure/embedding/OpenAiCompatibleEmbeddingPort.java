package cn.studykid.rag.infrastructure.embedding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.ports.EmbeddingPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容 {@code /embeddings} 接口的嵌入实现（RAG-D02 §3.5）。
 *
 * <p>对接任何暴露 OpenAI 语义嵌入端点的服务：BGE-M3 的推理网关、
 * Ollama（{@code http://host:11434/v1/embeddings}）、vLLM 的 {@code --task embed} 等。
 *
 * <p><b>⚠️ 维度是契约的一部分</b>：本类在构造期就要求显式给定 {@code expectedDimension}，
 * 并对<b>每一条</b>返回向量校验长度。原因不是洁癖——向量列的维度写死在 DDL 里
 * （{@code rag_chunk_embedding.embedding}），一条维度不符的向量写进去会让整个事务回滚，
 * 而在索引流水线里这意味着「静默少索引一批 chunk」；换模型带来的维度变化
 * 必须在这里<b>启动即失败</b>，而不是等到写库时才炸。
 *
 * <p><b>⚠️ 换模型必须重建索引</b>：{@link #modelVersion()} 会写进
 * {@code rag_chunk_embedding.embed_model}；不同模型的向量空间不可比，
 * 旧向量不会被新查询向量正确命中（RAG-D03 §5 的代际机制）。
 */
public class OpenAiCompatibleEmbeddingPort implements EmbeddingPort {

    /** 单次 HTTP 请求携带的文本条数上限；网关普遍对请求体大小敏感。 */
    public static final int DEFAULT_BATCH_SIZE = 16;

    /** 单次请求超时；本地大模型首 token 前的排队可能很慢，给足但不无限。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int expectedDimension;
    private final int batchSize;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OpenAiCompatibleEmbeddingPort(String baseUrl, String apiKey, String model,
                                         int expectedDimension) {
        this(baseUrl, apiKey, model, expectedDimension, DEFAULT_BATCH_SIZE, DEFAULT_TIMEOUT);
    }

    public OpenAiCompatibleEmbeddingPort(String baseUrl, String apiKey, String model,
                                         int expectedDimension, int batchSize, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Embedding 服务地址（rag.embedding.base-url）不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Embedding 模型标识（rag.embedding.model）不能为空");
        }
        if (expectedDimension <= 0) {
            throw new IllegalArgumentException(
                    "Embedding 维度（rag.embedding.dimension）必须为正数，当前=" + expectedDimension);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Embedding 批大小必须为正数，当前=" + batchSize);
        }
        // 容忍配置里写没写 /v1 或尾斜杠，统一归一到 <base>/embeddings
        this.endpoint = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                + "/embeddings";
        // Ollama 不校验 key，但 OpenAI 协议的 Authorization 头不能是空值；缺省用占位串
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? "empty" : apiKey;
        this.model = model;
        this.expectedDimension = expectedDimension;
        this.batchSize = batchSize;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public @NonNull List<float[]> embed(@NonNull List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
            result.addAll(embedBatch(batch));
        }
        return result;
    }

    @Override
    public @NonNull String modelVersion() {
        return model;
    }

    @Override
    public int dimension() {
        return expectedDimension;
    }

    private List<float[]> embedBatch(List<String> batch) {
        String body = mapper.writeValueAsString(Map.of("model", model, "input", batch));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        JsonNode root;
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "Embedding 服务返回 HTTP %d：%s".formatted(response.statusCode(), response.body()));
            }
            root = mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding 请求被中断：" + endpoint, e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 请求失败：" + endpoint + "（" + e + "）", e);
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new IllegalStateException("Embedding 响应缺少 data 数组，疑似非 OpenAI 兼容端点："
                    + endpoint + " → " + abbreviate(root.toString()));
        }
        // 按 data[].index 归位：网关不保证返回顺序与请求一致
        float[][] ordered = new float[batch.size()][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= batch.size()) {
                throw new IllegalStateException("Embedding 响应 index 越界：" + index
                        + "（本批 " + batch.size() + " 条）");
            }
            ordered[index] = toVector(item.path("embedding"));
        }
        List<float[]> vectors = new ArrayList<>(batch.size());
        for (int i = 0; i < ordered.length; i++) {
            if (ordered[i] == null) {
                throw new IllegalStateException("Embedding 响应缺少第 " + i + " 条向量，"
                        + "返回条数与请求条数不一致");
            }
            vectors.add(ordered[i]);
        }
        return vectors;
    }

    private float[] toVector(JsonNode embedding) {
        if (!embedding.isArray()) {
            throw new IllegalStateException("Embedding 响应的 embedding 不是数组");
        }
        if (embedding.size() != expectedDimension) {
            throw new IllegalStateException("""
                    Embedding 维度不匹配：模型 %s 返回 %d 维，配置与向量列期望 %d 维。
                    这通常是「换了嵌入模型但没改配置/没迁列」——
                    三者必须同时改：rag.embedding.dimension、
                    rag_chunk_embedding.embedding 的列类型、以及全量重建索引。"""
                    .formatted(model, embedding.size(), expectedDimension));
        }
        float[] vector = new float[expectedDimension];
        for (int i = 0; i < expectedDimension; i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
