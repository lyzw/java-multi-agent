package cn.studykid.rag.core.embedding;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 从重索引扫描行的扁平元数据构造 {@link EmbeddingInput}（RAG-D28 P3-1）。
 *
 * <p>⚠️ 为什么要单独一个 builder：摄取侧（{@code EmbeddingInputFactory}）从内存对象构造，
 * 重索引侧从 JDBC 行构造，两处若各写一套「哪个字段放哪」的映射，
 * 迟早漂移成两种模板文本 → 同一片段两次嵌入向量不同。
 * 本 builder 钉死重索引侧的映射口径，并由单测断言与摄取侧的字段顺序同构。
 */
public final class EmbeddingInputBuilder {

    private EmbeddingInputBuilder() {
    }

    /**
     * @param publisher       出版社（无元数据时传空串，不传 null——record 契约要求非 null）
     * @param grade           年级
     * @param term            册次
     * @param revisionCode    版次
     * @param sectionPath     章节路径
     * @param pageNumber      页码
     * @param exerciseNo      题号（可空）
     * @param role            角色名（{@code ChunkRole.name()}）
     * @param chunkText       正文
     * @param kpNames         知识点规范名（已按 canonical_name 排序）
     * @param kpAliases       知识点别名串（与 names 同序，已按 canonical_name 排序）
     */
    public static @NonNull EmbeddingInput fromScanRow(
            @NonNull String publisher, @NonNull String grade, @NonNull String term,
            @NonNull String revisionCode, @NonNull String sectionPath, int pageNumber,
            @Nullable String exerciseNo, @Nullable String blockType, @NonNull String role,
            @NonNull String chunkText, @NonNull List<String> kpNames,
            @NonNull List<String> kpAliases) {
        return fromScanRow(publisher, grade, term, revisionCode, sectionPath, pageNumber,
                exerciseNo, blockType, role, chunkText, kpNames, kpAliases, null, null);
    }

    /**
     * 全参构造（P3-1 语义槽位）：重索引扫描 SQL 读到公式/摘要两列后走本重载。
     *
     * <p>⚠️ 与首次摄取的 {@code EmbeddingInputFactory.fromChunk} 逐字同构：两侧都把
     * 公式 embeddingView 放第 14 参、表格摘要放第 15 参。若一侧漏传（传 null），
     * 同一片段重算会丢失语义槽位 → 向量漂移且无报错，由同构测试钉死。
     */
    public static @NonNull EmbeddingInput fromScanRow(
            @NonNull String publisher, @NonNull String grade, @NonNull String term,
            @NonNull String revisionCode, @NonNull String sectionPath, int pageNumber,
            @Nullable String exerciseNo, @Nullable String blockType, @NonNull String role,
            @NonNull String chunkText, @NonNull List<String> kpNames,
            @NonNull List<String> kpAliases,
            @Nullable String formulaNaturalLanguage,
            @Nullable String captionOrTableSummary) {
        return new EmbeddingInput(
                publisher, grade, term, revisionCode, sectionPath,
                pageNumber, pageNumber, exerciseNo, blockType, role,
                kpNames, kpAliases, chunkText, formulaNaturalLanguage, captionOrTableSummary);
    }
}
