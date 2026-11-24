package cn.studykid.rag.core.embedding;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;

/**
 * Embedding 输入模板 v1（RAG-D28 P3-1 / D27 §6.1 / D29 DEC-P7 M5）。
 *
 * <p><b>模板正文</b>（doc 侧）：
 * <pre>
 * [教材: {publisher}-{grade}-{term}-{revisionCode}]
 * [章节: {sectionPath}]
 * [位置: P{pageStart}-P{pageEnd} / 题号 {exerciseNo}]
 * [类型: {blockType} / 角色: {role}]
 * [知识点: {canonicalName}; 别名: {aliases}]
 *
 * {chunkText}
 * {formulaNaturalLanguage}
 * {captionOrTableSummary}
 * </pre>
 *
 * <p><b>⚠️ query/doc 必须不同模板（D29 DEC-P7 M5）</b>：
 * {@code OpenAiCompatibleEmbeddingPort} 没有 instruction 入参，所以
 * <b>query 侧在文本里拼 instruction</b>、doc 侧<b>不加</b>。
 * 两侧若用同一模板，query 被当文档嵌入、doc 被当查询嵌入，
 * 向量空间错位且没有任何报错——评测表现为「召回莫名变差」。
 *
 * <p><b>⚠️ PII 门禁</b>：模板只接受 {@link EmbeddingInput} 白名单字段，
 * 结构上不含 studentId/姓名/班级/成绩入参；渲染后再对
 * 「8–12 位连续数字」（项目学号格式，见 D29）做 fail-closed 扫描。
 * 命中即抛 {@link EmbeddingPiiException}——PII 一旦进向量就无法撤回
 * （向量不可逆脱敏），必须在写入前拒绝。
 *
 * <p><b>⚠️ 长度纪律</b>（D27 §6.1）：总长度超出有效窗口时按「元数据头 +
 * 正文」截断，<b>不</b>静默截到前几段；截断点以可见标记
 * {@code [TRUNCATED]} 留痕，便于评测归因。
 *
 * <p>⚠️ 顶层类型（{@code NestedTypeImportGuardTest} 同款规则）。
 */
public final class EmbeddingInputTemplate {

    /** 模板版本——写入 {@code rag_ingest_job.chunk_policy_config} 留痕。 */
    public static final String VERSION = "embedding-input-v1";

    /** query 侧 instruction（D29 DEC-P7 M5：query 加、doc 不加）。 */
    static final String QUERY_INSTRUCTION = "为教辅知识检索查询生成嵌入表示：";

    /**
     * 学号形态：8–12 位连续数字（项目 STUDENT_ID 口径，D29）。
     *
     * <p>⚠️ 前后必须是非数字边界，避免把教材里的「2024 年」之类误判；
     * 但教材正文里合法的 8–12 位数字串（如大数例题）也会命中——
     * 这是 fail-closed 的刻意取舍：宁可拒收让人工复核，也不让 PII 进向量。
     */
    private static final Pattern STUDENT_ID_LIKE = Pattern.compile("(?<![0-9])[0-9]{8,12}(?![0-9])");

    /** 模板有效字数上限（D28 P3-1：chunk 200～400 字 + 元数据头，留足余量）。 */
    static final int MAX_TEMPLATE_CHARS = 4096;

    private EmbeddingInputTemplate() {
    }

    /** @return doc 侧模板文本（不加 instruction） */
    public static @NonNull String renderDocument(@NonNull EmbeddingInput input) {
        if (input == null) {
            throw new IllegalArgumentException("EmbeddingInput 不可为空");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[教材: ").append(joinAxes(input)).append("]\n");
        sb.append("[章节: ").append(input.sectionPath()).append("]\n");
        String location = "P" + input.pageStart() + "-P" + input.pageEnd();
        if (input.exerciseNo() != null && !input.exerciseNo().isBlank()) {
            location += " / 题号 " + input.exerciseNo();
        }
        sb.append("[位置: ").append(location).append("]\n");
        sb.append("[类型: ").append(nullToEmpty(input.blockType()))
                .append(" / 角色: ").append(input.role()).append("]\n");
        sb.append("[知识点: ").append(joinKnowledge(input)).append("]\n");
        sb.append('\n');
        sb.append(input.chunkText());
        if (input.formulaNaturalLanguage() != null && !input.formulaNaturalLanguage().isBlank()) {
            sb.append('\n').append(input.formulaNaturalLanguage());
        }
        if (input.captionOrTableSummary() != null && !input.captionOrTableSummary().isBlank()) {
            sb.append('\n').append(input.captionOrTableSummary());
        }
        String rendered = sb.toString();
        guardPii(rendered);
        return fit(rendered);
    }

    /**
     * query 侧模板：拼 instruction + 裸查询。
     *
     * <p>⚠️ 同样过 PII 门禁——学生可能把学号直接打进问题里，
     * PII 进查询向量与进文档向量一样不可逆。
     */
    public static @NonNull String renderQuery(@NonNull String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不可为空");
        }
        String rendered = QUERY_INSTRUCTION + query;
        guardPii(rendered);
        // query 通常很短；超长时同样按可见标记截断（防注入超长串打满窗口）。
        return fit(rendered);
    }

    /**
     * @return 写入 {@code chunk_policy_config} 的模板版本片段（确定性串）
     */
    public static @NonNull String configTrace() {
        return "embeddingInput=" + VERSION;
    }

    private static @NonNull String joinAxes(@NonNull EmbeddingInput input) {
        return input.publisher() + "-" + input.grade() + "-" + input.term()
                + "-" + input.revisionCode();
    }

    private static @NonNull String joinKnowledge(@NonNull EmbeddingInput input) {
        Set<String> names = new LinkedHashSet<>(input.knowledgePointNames());
        names.addAll(input.knowledgePointAliases());
        return names.isEmpty() ? "" : String.join("; ", names);
    }

    private static @NonNull String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * PII fail-closed 扫描：命中 8–12 位连续数字即拒收。
     *
     * <p>⚠️ 不做「排除 UUID / confidence 小数」的白名单——本项目 embedding 文本
     * 本就不应含这些结构性标识（它们是审计明细字段，不是内容语义），
     * 一旦出现说明上游拼错了字段，应显式失败而不是静默放行。
     */
    private static void guardPii(@NonNull String rendered) {
        var matcher = STUDENT_ID_LIKE.matcher(rendered);
        if (matcher.find()) {
            throw new EmbeddingPiiException(
                    "Embedding 输入疑似含学号（8–12 位连续数字），已拒绝写入向量："
                            + "命中位置 " + matcher.start() + "-" + matcher.end());
        }
    }

    /** 超长截断：保留可见标记，不静默丢尾。 */
    private static @NonNull String fit(@NonNull String rendered) {
        if (rendered.length() <= MAX_TEMPLATE_CHARS) {
            return rendered;
        }
        String marker = "\n[TRUNCATED]";
        int keep = MAX_TEMPLATE_CHARS - marker.length();
        return rendered.substring(0, keep) + marker;
    }
}
