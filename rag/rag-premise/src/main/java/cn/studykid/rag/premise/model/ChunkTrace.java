package cn.studykid.rag.premise.model;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 切分工序留痕（RAG-D26 §1.3 / §2.5）。
 *
 * <p><b>⚠️ 为什么切分也要留痕</b>：
 * 「换切分策略后检索表现变化」是评测中最容易被误归因的地方——
 * 表现变差可能是切分变了而不是模型变了。{@link ChunkTrace} 让每一次
 * 切分都携带<b>策略 + 参数 + 噪声丢弃计数 + 超长拆分计数</b>，
 * 评测报告才能把变量拆开。
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code NestedTypeImportGuardTest}。
 *
 * @param policy           切分策略
 * @param configString     参数的确定性串（{@link ChunkPolicyConfig#stableString()}）
 * @param inputBlockCount  输入块数（归一化后）
 * @param chunkCount       产出片段数
 * @param droppedNoiseBlocks 判为噪声丢弃的块数（页眉页脚 / 空白）
 * @param splitOversizeCount 超预算被拆分的块数（HEADING_HIERARCHY 语义；
 *                           HYBRID_CHUNKER 无此概念记 0）
 */
public record ChunkTrace(
        @NonNull ChunkPolicy policy,
        @NonNull String configString,
        int inputBlockCount,
        int chunkCount,
        int droppedNoiseBlocks,
        int splitOversizeCount) {

    public ChunkTrace {
        if (policy == null) {
            throw new IllegalArgumentException("policy 不能为空");
        }
        configString = configString == null ? "" : configString;
        if (inputBlockCount < 0) {
            throw new IllegalArgumentException("inputBlockCount 必须 >= 0");
        }
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount 必须 >= 0");
        }
        if (droppedNoiseBlocks < 0) {
            throw new IllegalArgumentException("droppedNoiseBlocks 必须 >= 0");
        }
        if (splitOversizeCount < 0) {
            throw new IllegalArgumentException("splitOversizeCount 必须 >= 0");
        }
    }

    /**
     * @param policy        切分策略
     * @param config        切分参数（configString 取其 stableString）
     * @param inputBlocks   输入块数
     * @return 全零计数的初始留痕
     */
    public static @NonNull ChunkTrace initial(@NonNull ChunkPolicy policy,
                                              @NonNull ChunkPolicyConfig config,
                                              int inputBlocks) {
        return new ChunkTrace(policy, config.stableString(), inputBlocks, 0, 0, 0);
    }
}
