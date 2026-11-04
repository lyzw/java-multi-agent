package cn.studykid.rag.management.core;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 抽检疑点（交互文档 §4.6「看红灯」/ V1.0.2 §5 R2）。
 *
 * <p><b>它解决什么</b>：当前抽检台把一册教材的上千条片段**逐条**摆出来让人裁决
 * （实测 1463 条、翻 159 页），而抽检真正要找的是「哪一条可能出了错」。
 * 本类型把「系统已经觉得可疑的片段」挑出来并**附上可疑原因**，
 * 人只需要确认这十几条，而不是裁决全部。
 *
 * <p><b>⚠️ 疑点只减负，不替代判断</b>：预筛漏掉的片段仍可能有问题。
 * 故抽检台必须同时提供「查看全部片段」的入口，
 * 且最终判定仍以人提交的 {@link InspectionRecord} 为准——
 * 预筛结果**不参与** {@link InspectionRules#evaluate} 的聚合。
 *
 * @param chunkId      片段 ID
 * @param documentId   所属载体
 * @param role         当前被判定的角色
 * @param text         片段正文（**必须带正文**：判「是不是答案」必须看到字）
 * @param pageNumber   页码
 * @param sectionPath  章节路径
 * @param exerciseNo   题号（可空）
 * @param reason       为什么被标为疑点（人话）
 * @param severity     严重度（决定排序与呈现：HIGH 置顶）
 */
public record InspectionSuspicion(
        @NonNull String chunkId,
        @NonNull String documentId,
        @NonNull String role,
        @NonNull String text,
        @Nullable Integer pageNumber,
        @Nullable String sectionPath,
        @Nullable String exerciseNo,
        @NonNull Reason reason,
        @NonNull Severity severity
) {

    /**
     * 疑点成因。
     *
     * <p>⚠️ 这些成因不是「随机抽样」，而是**已知的高风险模式**：
     * 每一条都对应一次真实可能发生的质量事故。
     */
    public enum Reason {
        /**
         * 疑似答案区被判成非答案类（**漏判 = 泄题，单点否决**）。
         *
         * <p>判据：正文含答案特征词（「参考答案」「答案：」「解析：」等）
         * 或形如题号-选项序列（{@code 1-5: B A C D A}），
         * 但角色却被判为 {@code EXPLANATION} 等非答案类。
         */
        SUSPECT_ANSWER_MISSED("疑似答案区被判成正文（漏判即泄题）", Severity.HIGH),

        /**
         * 承载答案的片段没有题号：核对时无法定位到具体题目。
         */
        ANSWER_WITHOUT_EXERCISE_NO("答案片段缺题号，无法定位到题目", Severity.HIGH),

        /**
         * 习题片段没有题号：题号保留率要求 100%。
         */
        EXERCISE_WITHOUT_NO("习题片段缺题号", Severity.MEDIUM),

        /**
         * 片段过长：切分边界可能错，一个片段塞了多个语义单元。
         */
        OVERSIZED_CHUNK("片段过长，可能切分边界有误", Severity.MEDIUM),

        /**
         * 片段为空或几乎为空：清洗过度或解析失败留下的残渣。
         */
        NEARLY_EMPTY_CHUNK("片段近乎为空，可能是解析残渣", Severity.MEDIUM),

        /**
         * 答案类片段却缺少页码：无法回取原文核对。
         */
        NO_LOCATOR("片段缺页码，无法回取原文核对", Severity.LOW);

        private final String description;
        private final Severity severity;

        Reason(String description, Severity severity) {
            this.description = description;
            this.severity = severity;
        }

        /** 面向用户的人话说明。 */
        public String description() {
            return description;
        }

        /** 该成因的默认严重度。 */
        public Severity defaultSeverity() {
            return severity;
        }
    }

    /** 疑点严重度（决定排序：HIGH 在最前，因为它是「可能泄题」）。 */
    public enum Severity {
        HIGH(0),
        MEDIUM(1),
        LOW(2);

        private final int order;

        Severity(int order) {
            this.order = order;
        }

        /** 排序权重（数值越小越靠前）。 */
        public int order() {
            return order;
        }
    }

    /** 片段正文超出此长度时截断展示（避免单条撑爆界面）。 */
    public static final int TEXT_PREVIEW_LIMIT = 400;

    public InspectionSuspicion {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId 不能为空");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role 不能为空");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity 不能为空");
        }
        // ⚠️ 正文统一截断：一条片段可能有几千字，原样下发会让抽检台卡死，
        //    而判「是不是答案」只需要看开头。
        text = text == null ? "" : text;
        if (text.length() > TEXT_PREVIEW_LIMIT) {
            text = text.substring(0, TEXT_PREVIEW_LIMIT) + "…";
        }
    }

    /** 构造时自动继承成因的默认严重度。 */
    public static InspectionSuspicion of(@NonNull String chunkId,
                                         @NonNull String documentId,
                                         @NonNull String role,
                                         @Nullable String text,
                                         @Nullable Integer pageNumber,
                                         @Nullable String sectionPath,
                                         @Nullable String exerciseNo,
                                         @NonNull Reason reason) {
        return new InspectionSuspicion(chunkId, documentId, role, text == null ? "" : text,
                pageNumber, sectionPath, exerciseNo, reason, reason.defaultSeverity());
    }
}
