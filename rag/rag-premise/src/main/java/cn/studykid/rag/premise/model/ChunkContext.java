package cn.studykid.rag.premise.model;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * 切分上下文（RAG-D26 §2.3 的实现修正项）。
 *
 * <p><b>⚠️ 为什么切分器需要它（设计文档 §2.3 签名的补充）</b>：
 * {@code Chunk} 的紧凑构造器要求 {@code edition} 非空（领域不变式：
 * 「引用必须携带完整坐标，缺任一轴的引用视为不可验证」）。
 * 版本坐标与知识点表是<b>入库上下文</b>（来自 source 行），
 * 不是解析产物——塞进 {@code PremiseDocument} 会污染「解析输出」的语义。
 * 因此两者作为显式上下文传入切分工序：
 * <ul>
 *   <li>{@code edition}：写入每个片段的版本坐标；</li>
 *   <li>{@code knowledgePoints}：知识点挂接的匹配表
 *       （确定性匹配，规则与既有管道同源——见 {@code KnowledgePoint.matches}）。</li>
 * </ul>
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code NestedTypeImportGuardTest}。
 *
 * @param edition        版本坐标（非空——{@code Chunk} 的领域不变式要求）
 * @param knowledgePoints 知识点匹配表（空表 = 不挂接，确定性输出）
 */
public record ChunkContext(
        @NonNull EditionCoordinate edition,
        @NonNull List<KnowledgePoint> knowledgePoints) {

    public ChunkContext {
        if (edition == null) {
            throw new IllegalArgumentException("edition 不能为空（Chunk 的领域不变式要求完整版本坐标）");
        }
        knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
    }

    /**
     * @param edition 版本坐标
     * @return 不挂接知识点的上下文（评测/对拍场景）
     */
    public static @NonNull ChunkContext withoutKnowledgePoints(@NonNull EditionCoordinate edition) {
        return new ChunkContext(edition, List.of());
    }
}
