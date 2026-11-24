package cn.studykid.rag.core.embedding;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Embedding 输入模板的结构化入参（RAG-D28 P3-1 / D27 §6.1）。
 *
 * <p><b>⚠️ 为什么要结构化入参而不是直接传字符串</b>：D27 §6.1 要求模板只放
 * 「教材元数据 + 内容语义」，且<b>不得</b>把学生姓名/学号/班级/成绩拼进 embedding
 * 文本。若入口直接传拼好的字符串，就无法在构造期拒绝 PII 字段——
 * 把入参收口成白名单 record，等于从类型层面堵死「学生维度混进向量」的路径。
 *
 * <p><b>⚠️ 顶层类型</b>：本 record 与 {@link EmbeddingInputTemplate} 均为顶层，
 * 遵守 ports/premise 同款「嵌套类型守卫」规则（JDK 21 javac 符号解析缺陷规避）。
 *
 * @param publisher           出版社/版本体系（如「沪教版」）
 * @param grade               年级（如「六年级」）
 * @param term                册次（如「下册」）
 * @param revisionCode        修订版次
 * @param sectionPath         章节路径（SectionPathStack 的 {@code >} 分隔串）
 * @param pageStart           起始物理页码（1 起）
 * @param pageEnd             结束物理页码（跨页块 &gt; pageStart）
 * @param exerciseNo          题号（可空；正文类片段为 null）
 * @param blockType           版面块类型（如 FORMULA / TABLE / PARAGRAPH）
 * @param role                片段角色（{@code ChunkRole.name()}）
 * @param knowledgePointNames 命中的知识点规范名（可空列表）
 * @param knowledgePointAliases 命中的知识点别名（可空列表）
 * @param chunkText           片段正文（权威可回取原文）
 * @param formulaNaturalLanguage 公式自然语言描述（受控词典命中才有；可空）
 * @param captionOrTableSummary  图注/表格摘要（可空）
 */
public record EmbeddingInput(
        @NonNull String publisher,
        @NonNull String grade,
        @NonNull String term,
        @NonNull String revisionCode,
        @NonNull String sectionPath,
        int pageStart,
        int pageEnd,
        @Nullable String exerciseNo,
        @Nullable String blockType,
        @NonNull String role,
        @NonNull List<String> knowledgePointNames,
        @NonNull List<String> knowledgePointAliases,
        @NonNull String chunkText,
        @Nullable String formulaNaturalLanguage,
        @Nullable String captionOrTableSummary
) {

    public EmbeddingInput {
        if (publisher == null || grade == null || term == null || revisionCode == null) {
            throw new IllegalArgumentException("教材五轴（publisher/grade/term/revisionCode）不可为空");
        }
        if (sectionPath == null) {
            throw new IllegalArgumentException("sectionPath 不可为空（用空串表示无章节，不得传 null）");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role 不可为空");
        }
        if (chunkText == null || chunkText.isBlank()) {
            throw new IllegalArgumentException("chunkText 不可为空");
        }
        if (pageStart < 1) {
            throw new IllegalArgumentException("pageStart 必须 >= 1");
        }
        if (pageEnd < pageStart) {
            throw new IllegalArgumentException("pageEnd 不得小于 pageStart");
        }
        knowledgePointNames = knowledgePointNames == null ? List.of() : List.copyOf(knowledgePointNames);
        knowledgePointAliases = knowledgePointAliases == null ? List.of() : List.copyOf(knowledgePointAliases);
    }

    /**
     * 便捷构造：除正文与角色外均无元数据的降级输入。
     *
     * <p>⚠️ 仅用于「元数据确实不可得」的兼容路径（如未带 edition 的旧测试夹具）。
     * 生产摄取链必须传全五轴——否则模板退化成裸文本，评测无法归因。
     */
    public static @NonNull EmbeddingInput minimal(@NonNull String role, @NonNull String chunkText) {
        return new EmbeddingInput("", "", "", "", "", 1, 1,
                null, null, role, List.of(), List.of(), chunkText, null, null);
    }
}
