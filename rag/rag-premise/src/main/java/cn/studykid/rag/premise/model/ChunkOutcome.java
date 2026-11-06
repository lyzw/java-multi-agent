package cn.studykid.rag.premise.model;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;

/**
 * 切分工序的统一输出（RAG-D26 §1.3）。
 *
 * <p><b>⚠️ 与 {@link ChunkPolicy} 的关系</b>：
 * {@code trace.policy()} 告诉评测代码「这些片段是按哪套策略切出来的」——
 * 换策略后检索表现不可比，不留策略就无法避免把「切分差异」误归因为
 * 「模型变差」（RAG-D26 §4 的风险表）。
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code NestedTypeImportGuardTest}。
 *
 * @param documentId 文档 ID
 * @param policy     切分策略
 * @param config     切分参数（与 {@link ChunkTrace#config()} 同一份）
 * @param chunks     切分产物（顺序即阅读顺序）
 * @param trace      切分留痕
 */
public record ChunkOutcome(
        @NonNull String documentId,
        @NonNull ChunkPolicy policy,
        @NonNull ChunkPolicyConfig config,
        @NonNull List<Chunk> chunks,
        @NonNull ChunkTrace trace) {

    public ChunkOutcome {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy 不能为空");
        }
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        if (trace == null) {
            throw new IllegalArgumentException("trace 不能为空");
        }
    }
}
