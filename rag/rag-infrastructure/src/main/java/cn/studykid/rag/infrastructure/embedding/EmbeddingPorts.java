package cn.studykid.rag.infrastructure.embedding;

import java.time.Duration;

import cn.studykid.rag.infrastructure.LocalPortGate;
import cn.studykid.rag.ports.EmbeddingPort;

/**
 * 嵌入端口的装配选择（rag-bootstrap 与 rag-worker 共用，避免两处漂移）。
 *
 * <p><b>为什么只有一处选择逻辑</b>：检索侧把查询文本转向量、索引侧把 chunk 文本转向量，
 * 两者<b>必须</b>是同一个模型、同一个维度。若两个装配类各写一遍开关判断，
 * 一次配置漏改就会出现「索引用的是 A 模型、查询用的是 B 模型」——
 * 表现为检索恒低分，且没有任何报错，是最难排查的一类故障。
 *
 * <p><b>选择规则</b>：
 * <ul>
 *   <li>{@code rag.embedding.base-url} 非空 → {@link OpenAiCompatibleEmbeddingPort}（真实模型）；
 *       此时 {@code rag.embedding.model} 也必须非空，否则装配期直接失败，
 *       不会退回占位（退回是静默降级，正是本地占位闸门要防的事）。</li>
 *   <li>{@code base-url} 为空 → {@link LocalDeterministicEmbeddingPort}，
 *       仍受 {@link LocalPortGate} 的 fail-closed 闸门约束。</li>
 * </ul>
 */
public final class EmbeddingPorts {

    private EmbeddingPorts() {
    }

    /**
     * @param localPortsEnabled {@code rag.local-ports.enabled}（占位端口显式放行开关）
     * @param baseUrl {@code rag.embedding.base-url}；空 = 未配置真实模型
     * @param apiKey {@code rag.embedding.api-key}
     * @param model {@code rag.embedding.model}
     * @param dimension {@code rag.embedding.dimension}，必须与向量列的维度一致
     * @param batchSize {@code rag.embedding.batch-size}
     * @param timeoutSeconds {@code rag.embedding.timeout-seconds}
     * @param assembler 装配位置描述（仅用于占位闸门报错定位）
     * @return 嵌入端口实现
     */
    public static EmbeddingPort create(boolean localPortsEnabled, String baseUrl, String apiKey,
                                       String model, int dimension, int batchSize,
                                       int timeoutSeconds, String assembler) {
        boolean realConfigured = baseUrl != null && !baseUrl.isBlank();
        if (!realConfigured) {
            LocalPortGate.requireEnabled(localPortsEnabled,
                    "EmbeddingPort → LocalDeterministicEmbeddingPort (" + assembler + ")");
            return new LocalDeterministicEmbeddingPort();
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("""
                    [RAG fail-closed] 配置了 rag.embedding.base-url=%s，却没有配置 rag.embedding.model。

                    已配置真实嵌入服务地址时绝不退回本地占位实现——
                    静默降级会产出「维度对得上但语义是假的」向量，
                    比启动失败危险得多（评审 P0-6 同一口径）。

                    请补上 rag.embedding.model（或环境变量 RAG_EMBEDDING_MODEL），
                    或清空 rag.embedding.base-url 并显式设置 RAG_LOCAL_PORTS_ENABLED=true。"""
                    .formatted(baseUrl));
        }
        return new OpenAiCompatibleEmbeddingPort(baseUrl, apiKey, model, dimension, batchSize,
                Duration.ofSeconds(timeoutSeconds));
    }
}
