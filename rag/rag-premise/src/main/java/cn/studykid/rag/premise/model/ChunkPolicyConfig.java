package cn.studykid.rag.premise.model;

import org.jspecify.annotations.NonNull;

/**
 * 切分参数（RAG-D26 §2.3）。
 *
 * <p><b>⚠️ 参数必须落库留痕</b>：同一册换 {@code maxTokens} 重切后，
 * 检索表现会变——不留参数就无法解释「为什么这批语料的召回变了」。
 * 本类型的 {@link #stableString()} 提供确定性的参数序列化
 * （供 {@code rag_ingest_job.chunk_policy_config} 写入）。
 *
 * <p><b>默认值的依据</b>：
 * <ul>
 *   <li>{@code maxTokens=512} / {@code overlapRatio=0.15}：调研结论的常用经验值，
 *       且 512 须与嵌入模型对齐（D-26 §2.3）;</li>
 *   <li>{@code overlapRatio > 0} 是<b>硬约束</b>（D04 §5.1：教辅的定义与
 *       适用条件常被窗口切开，重叠为 0 会重演「三角形内角和 180°」漏掉
 *       「平面内」前提的事故）。校验在紧凑构造器里，违反即抛异常。</li>
 * </ul>
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code NestedTypeImportGuardTest}。
 *
 * @param maxTokens          单块 token 预算（须与嵌入模型对齐）
 * @param overlapRatio       重叠比例（0 < r < 1；HEADING_HIERARCHY 用于超长块拆分）
 * @param mergePeers         是否合并同标题相邻小块（HybridChunker 的 merge_peers）
 * @param repeatTableHeader  表格跨块是否重复表头（HybridChunker 的 repeat_table_header）
 */
public record ChunkPolicyConfig(
        int maxTokens,
        double overlapRatio,
        boolean mergePeers,
        boolean repeatTableHeader) {

    /** 默认 token 预算。 */
    public static final int DEFAULT_MAX_TOKENS = 512;

    /** 默认重叠比例（D04 §5.1：必须大于 0）。 */
    public static final double DEFAULT_OVERLAP_RATIO = 0.15;

    /** 生产默认参数。 */
    public static @NonNull ChunkPolicyConfig productionDefault() {
        return new ChunkPolicyConfig(DEFAULT_MAX_TOKENS, DEFAULT_OVERLAP_RATIO, true, true);
    }

    public ChunkPolicyConfig {
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens 必须 >= 1");
        }
        if (overlapRatio <= 0 || overlapRatio >= 1) {
            throw new IllegalArgumentException(
                    "overlapRatio 必须在 (0,1) 开区间内——重叠为 0 会切开定义与适用条件（D04 §5.1），实际值 "
                            + overlapRatio);
        }
    }

    /**
     * @return 确定性的参数串（字段名=值，顺序固定），供留痕列直接写入
     */
    public @NonNull String stableString() {
        return "maxTokens=" + maxTokens
                + ",overlapRatio=" + overlapRatio
                + ",mergePeers=" + mergePeers
                + ",repeatTableHeader=" + repeatTableHeader;
    }
}
