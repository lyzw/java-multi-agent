package cn.studykid.rag.infrastructure.modelcatalog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG 侧的「统一模型目录」消费客户端（RAG 是独立 reactor / 独立库，不得直连平台 DB）。
 *
 * <p><b>⚠️ 为什么走 HTTP 而非直连</b>：模型目录属于平台库（{@code agent_dev}），
 * 与 RAG 库（{@code study_rag}）是两个独立 reactor、两个独立库（ADR-011 禁止互相塞代码）。
 * RAG 经平台暴露的<b>内部解析端点</b> {@code GET /api/internal/models/resolve?scenario=embedding}
 * 取模型元数据——密钥仍由 RAG 自身环境变量按 provider 解析（与平台侧同约定），
 * 本客户端<b>不接触任何 API Key</b>。
 *
 * <p><b>⚠️ 安全回落（fail-soft）</b>：下列任一情况都返回 {@link Optional#empty()}，
 * 由调用方回落 yml 配置，绝不因目录不可达而让 RAG 起不来：
 * <ul>
 *   <li>未配置 {@code rag.model-catalog.platform-base-url}（即未启用目录）；</li>
 *   <li>HTTP 调用失败 / 非 2xx / 解析失败；</li>
 *   <li>返回体缺少 {@code modelName}。</li>
 * </ul>
 *
 * <p><b>⚠️ 轻量缓存</b>：解析结果按 TTL 缓存（默认 5 分钟），避免每条索引批都打平台。
 * 缓存只在<b>成功</b>时写入；失败不污染缓存（下次重试）。
 */
public final class ModelCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogClient.class);

    /** 经目录解析出的嵌入模型（不含密钥）。 */
    public record ResolvedEmbeddingModel(
            @NonNull String provider,
            @NonNull String modelName,
            @Nullable String baseUrl,
            @Nullable Integer dimension) {
    }

    private final String baseUrl;
    private final String token;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final long ttlMillis;

    /** 缓存：{@code value} + 过期时间戳；volatile 保证可见性（单例 bean）。 */
    private volatile CacheEntry cache;

    public ModelCatalogClient(@Nullable String baseUrl, @Nullable String token, long cacheTtlSeconds) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.token = token == null ? "" : token;
        this.ttlMillis = Math.max(0, cacheTtlSeconds) * 1000L;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 解析 embedding 场景的启用模型。
     *
     * @return 解析结果；任何不可用情况返回 {@link Optional#empty()}（调用方回落 yml）。
     */
    public @NonNull Optional<ResolvedEmbeddingModel> resolveEmbeddingModel() {
        if (baseUrl.isEmpty()) {
            return Optional.empty();
        }
        CacheEntry cached = cache;
        if (cached != null && cached.expiryMillis > System.currentTimeMillis()) {
            return Optional.of(cached.value);
        }
        try {
            String json = fetch("embedding", null);
            ResolvedEmbeddingModel model = parse(json);
            cache = new CacheEntry(model, System.currentTimeMillis() + ttlMillis);
            return Optional.of(model);
        } catch (RuntimeException e) {
            log.warn("统一模型目录解析失败，RAG 回落 yml 嵌入配置: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 调用平台内部解析端点；非 2xx 抛异常（由 {@link #resolveEmbeddingModel()} 吞掉并回落）。 */
    private String fetch(String scenario, @Nullable String model) {
        StringBuilder uri = new StringBuilder(baseUrl);
        if (!uri.toString().endsWith("/")) {
            uri.append('/');
        }
        uri.append("api/internal/models/resolve?scenario=").append(scenario);
        if (model != null && !model.isBlank()) {
            uri.append("&model=").append(model);
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(uri.toString()))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json");
        if (!token.isEmpty()) {
            req.header("X-Platform-Token", token);
        }
        try {
            HttpResponse<String> resp = http.send(req.GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("平台模型目录返回 HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("解析模型目录被中断", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("解析模型目录网络失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析平台响应 JSON → 嵌入模型。
     *
     * <p>⚠️ 必须有 {@code modelName}；{@code baseUrl}/{@code dimension} 缺失不致命
     * （调用方自有兜底）。解析失败抛异常，交由 {@link #resolveEmbeddingModel()} 回落。
     */
    static @NonNull ResolvedEmbeddingModel parse(@NonNull String json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            String modelName = root.path("modelName").asText(null);
            if (modelName == null || modelName.isBlank()) {
                throw new IllegalStateException("响应缺少 modelName");
            }
            JsonNode dim = root.path("dimension");
            Integer dimension = dim.isMissingNode() || dim.isNull() ? null : dim.asInt();
            return new ResolvedEmbeddingModel(
                    root.path("provider").asText(""),
                    modelName,
                    root.path("baseUrl").asText(null),
                    dimension);
        } catch (RuntimeException e) {
            throw new IllegalStateException("模型目录响应解析失败: " + e.getMessage(), e);
        }
    }

    private static final class CacheEntry {
        final ResolvedEmbeddingModel value;
        final long expiryMillis;

        CacheEntry(ResolvedEmbeddingModel value, long expiryMillis) {
            this.value = value;
            this.expiryMillis = expiryMillis;
        }
    }
}
