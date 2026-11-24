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
import cn.studykid.rag.ports.EmbeddingUnavailableException;
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
 *
 * <p><b>⚠️ MRL 维度分离（D29 DEC-P2）</b>：服务端原生维度（{@code nativeDimension}）
 * 与写入向量列的输出维度（{@code outputDimension}）是两个概念。当输出维度更小，
 * 本端口在拿到原生向量后经 {@link MrlProjector} 取前缀并<b>重新 L2 归一化</b>。
 * 旧构造两者相等（不截断），行为与改造前逐字一致；只有新构造显式给出更小的输出维度才启用截断。
 */
public class OpenAiCompatibleEmbeddingPort implements EmbeddingPort {

    /** 单次 HTTP 请求携带的文本条数上限；网关普遍对请求体大小敏感。 */
    public static final int DEFAULT_BATCH_SIZE = 16;

    /** 单次请求超时；本地大模型首 token 前的排队可能很慢，给足但不无限。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final String endpoint;
    private final String apiKey;
    private final String model;
    /** 服务端原生返回维度（请求不做 dimensions 截断时模型实际吐出的维数）。 */
    private final int nativeDimension;
    /** 写入向量列的输出维度；小于原生维度时启用 MRL 前缀截断 + L2 重归一。 */
    private final int outputDimension;
    private final int batchSize;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OpenAiCompatibleEmbeddingPort(String baseUrl, String apiKey, String model,
                                         int expectedDimension) {
        this(baseUrl, apiKey, model, expectedDimension, expectedDimension,
                DEFAULT_BATCH_SIZE, DEFAULT_TIMEOUT);
    }

    public OpenAiCompatibleEmbeddingPort(String baseUrl, String apiKey, String model,
                                         int expectedDimension, int batchSize, Duration timeout) {
        this(baseUrl, apiKey, model, expectedDimension, expectedDimension, batchSize, timeout);
    }

    /**
     * 支持 MRL 截断的构造（D29 DEC-P2）。
     *
     * @param nativeDimension 服务端原生返回维度（如 4096）
     * @param outputDimension 写入向量列的输出维度（如 1024）；
     *                        等于原生维度时不截断，小于时取前缀并重 L2 归一化，
     *                        大于原生维度直接拒绝（⛔ 禁止零填充伪造维度）
     */
    public OpenAiCompatibleEmbeddingPort(String baseUrl, String apiKey, String model,
                                         int nativeDimension, int outputDimension,
                                         int batchSize, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Embedding 服务地址（rag.embedding.base-url）不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Embedding 模型标识（rag.embedding.model）不能为空");
        }
        if (nativeDimension <= 0) {
            throw new IllegalArgumentException(
                    "Embedding 原生维度（rag.embedding.native-dimension）必须为正数，当前=" + nativeDimension);
        }
        if (outputDimension <= 0) {
            throw new IllegalArgumentException(
                    "Embedding 输出维度（rag.embedding.dimension）必须为正数，当前=" + outputDimension);
        }
        if (outputDimension > nativeDimension) {
            throw new IllegalArgumentException(
                    "Embedding 输出维度(" + outputDimension + ")不能大于原生维度(" + nativeDimension
                            + ")——MRL 只能截断，禁止零填充伪造维度（D29 DEC-P2 S3）");
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
        this.nativeDimension = nativeDimension;
        this.outputDimension = outputDimension;
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
        // 截断档位写进版本标记：同一模型的不同 MRL 档位是不同的向量空间，
        // 混用会让「4096 代际的查询向量打到 1024 代际的库」静默产生错分（D18 代际纪律）。
        return outputDimension == nativeDimension
                ? model
                : model + "@mrl" + outputDimension;
    }

    @Override
    public int dimension() {
        // 对外暴露的是写入向量列的维度，必须与 rag_chunk_embedding 的列维度一致。
        return outputDimension;
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
                // ⚠️ 上游不可用是「暂时故障」，不是代码/配置契约错误：
                //    单独一类让 HTTP 层映射成 503 + 可执行提示，而不是无名 500。
                //    ⚠️ 不把 response.body() 原文拼进消息（可能含密钥或语料）。
                throw new EmbeddingUnavailableException(
                        "Embedding 服务返回 HTTP %d：%s".formatted(response.statusCode(), endpoint));
            }
            root = mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingUnavailableException("Embedding 请求被中断：" + endpoint, e);
        } catch (EmbeddingUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // 连接拒绝 / 超时 / DNS 失败等：外部依赖没起是最常见的真实原因，
            // 必须让调用方看到「嵌入服务不可用」而不是一条泛化的 500。
            throw new EmbeddingUnavailableException("Embedding 请求失败：" + endpoint
                    + "（" + e.getClass().getSimpleName() + ": " + e.getMessage() + "）", e);
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
            // MRL：原生向量到手后取前 outputDimension 维并重新 L2 归一化（D29 DEC-P2 S1）。
            // 不截断时 project 是恒等（长度相等、原向量已归一或由服务端保证），
            // 为避免无谓开销与改变既有行为，仅在档位不同时才投影。
            vectors.add(outputDimension == nativeDimension
                    ? ordered[i]
                    : MrlProjector.project(ordered[i], outputDimension));
        }
        return vectors;
    }

    private float[] toVector(JsonNode embedding) {
        if (!embedding.isArray()) {
            throw new IllegalStateException("Embedding 响应的 embedding 不是数组");
        }
        // ⚠️ 校验的是服务端「原生」维度，不是输出维度：
        //    截断发生在投影阶段，服务端必须先如实返回 nativeDimension 维。
        if (embedding.size() != nativeDimension) {
            throw new IllegalStateException("""
                    Embedding 维度不匹配：模型 %s 返回 %d 维，原生维度期望 %d 维。
                    这通常是「换了嵌入模型但没改配置/没迁列」——
                    三者必须同时改：rag.embedding.native-dimension、
                    rag_chunk_embedding.embedding 的列类型、以及全量重建索引。"""
                    .formatted(model, embedding.size(), nativeDimension));
        }
        float[] vector = new float[nativeDimension];
        for (int i = 0; i < nativeDimension; i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
