package cn.studykid.rag.premise.model;

import org.jspecify.annotations.NonNull;

/**
 * 切分策略标识（RAG-D26 §2.3）。
 *
 * <p><b>⚠️ 为什么是枚举而不是自由字符串</b>：
 * 该值要落库（{@code rag_document.chunk_policy} / {@code rag_ingest_job.chunk_policy}），
 * 是「这批语料按哪套规则切的」的权威留痕。自由字符串会出现
 * {@code hybrid} / {@code HYBRID_CHUNKER} / {@code docling-hybrid} 并存，
 * 让「按策略筛选需重灌的册」这种运维查询失效。
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code NestedTypeImportGuardTest}。
 */
public enum ChunkPolicy {

    /**
     * 自建「标题层级分块」：标题开新节、题块自成单元、答案块自成单元，
     * 超长块按 token 预算拆分且带重叠（RAG-D04 §5.1 结构优先）。
     *
     * <p>纯 Java 实现（{@code HeadingHierarchyChunker}），无外部服务依赖。
     */
    HEADING_HIERARCHY,

    /**
     * Docling HybridChunker：按 token 先拆后并（{@code merge_peers}），
     * 表格跨块可重复表头（{@code repeat_table_header}）。
     *
     * <p>经 Python 分块桥调用（RAG-D26 §2.4：docling-serve 无 chunk 端点，
     * 这是唯一不丢失结构元信息的可行路径）。
     */
    HYBRID_CHUNKER;

    /**
     * @return 是否为自建标题层级方案（供留痕与评测代码做分支，语义自包含）
     */
    public boolean isHeadingHierarchy() {
        return this == HEADING_HIERARCHY;
    }

    /**
     * @return 是否依赖外部分块桥（装配方据此决定是否校验桥端点）
     */
    public boolean requiresChunkBridge() {
        return this == HYBRID_CHUNKER;
    }

    /**
     * 从配置值解析策略（fail-closed：未知值抛异常而非回落默认）。
     *
     * <p><b>⚠️ 为什么不静默回落</b>：
     * 策略值要落库留痕（{@code chunk_policy} 列）。配置拼错
     * （如 {@code hybrid} / {@code Hybrid}）时静默回落会让实际策略与
     * 留痕不一致——评测与重灌判断全部失真。启动期抛错是最早的失败点。
     *
     * @param configValue 配置值（空/null 视为 HEADING_HIERARCHY 默认；
     *                    大小写不敏感；接受 {@code heading} / {@code hybrid} 简写）
     * @return 对应策略
     */
    public static @NonNull ChunkPolicy fromConfigValue(String configValue) {
        if (configValue == null || configValue.isBlank()) {
            return HEADING_HIERARCHY;
        }
        String normalized = configValue.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "HEADING", "HEADING_HIERARCHY" -> HEADING_HIERARCHY;
            case "HYBRID", "HYBRID_CHUNKER" -> HYBRID_CHUNKER;
            default -> throw new IllegalArgumentException(
                    "未知切分策略配置值: " + configValue
                            + "（合法值: heading / heading_hierarchy / hybrid / hybrid_chunker）");
        };
    }
}
