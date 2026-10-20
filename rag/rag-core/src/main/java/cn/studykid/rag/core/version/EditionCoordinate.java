package cn.studykid.rag.core.version;

import org.jspecify.annotations.NonNull;

/**
 * 教材版本坐标系（RAG-D00 §2.2 决策 D-R03 / D-R04）。
 *
 * <p><b>⚠️ 为什么版本必须是一等坐标而不是一个字符串标签</b>：
 * 教辅语料里，<b>同一个知识点在不同版本中的页码、章节号、例题编号、
 * 甚至结论表述都可能不同</b>。若版本只是一个自由文本标签：
 * <ul>
 *   <li>「人教版七上」无法区分 2012 版与 2024 修订版；</li>
 *   <li>「人教版七上 第 3 章第 45 页」这种引用<b>无法被机器校验</b>——
 *       因为无法确定它指的是哪一版的第 45 页。</li>
 * </ul>
 * 因此版本必须拆成<b>可分别约束的坐标轴</b>，只有全部轴匹配，
 * 引用才可被验证。
 *
 * <p><b>坐标轴</b>：{@code 学科 × 学段/年级 × 册次 × 出版社 × 修订版次}。
 * 各轴的组合构成 {@link EditionCoordinate}，而
 * {@link EditionScope} 描述「本次检索允许哪些版本」。
 *
 * <p><b>不可妥协的规则</b>：
 * <ol>
 *   <li><b>引用必须携带完整坐标</b>。缺任一轴的引用视为不可验证 → 拒绝。</li>
 *   <li><b>版本不匹配时不得静默降级</b>。命中了其他版本的段落时，
 *       必须显式告知「该内容来自 X 版，与你正在学的 Y 版可能不同」，
 *       而不是当作同一版本给出。</li>
 * </ol>
 */
public record EditionCoordinate(
        @NonNull Subject subject,
        @NonNull GradeBand grade,
        @NonNull Term term,
        @NonNull Publisher publisher,
        @NonNull String revisionCode
) {

    public EditionCoordinate {
        if (subject == null) {
            throw new IllegalArgumentException("subject 不能为空");
        }
        if (grade == null) {
            throw new IllegalArgumentException("grade 不能为空");
        }
        if (term == null) {
            throw new IllegalArgumentException("term 不能为空");
        }
        if (publisher == null) {
            throw new IllegalArgumentException("publisher 不能为空");
        }
        if (revisionCode == null || revisionCode.isBlank()) {
            // ⚠️ 缺版次即无法区分同名教材的不同修订版 —— 引用不可验证。
            throw new IllegalArgumentException("revisionCode 不能为空");
        }
    }

    /**
     * @param other 另一个版本
     * @return 是否为<b>完全相同</b>的版本
     */
    public boolean sameAs(@NonNull EditionCoordinate other) {
        return subject == other.subject
                && grade == other.grade
                && term == other.term
                && publisher == other.publisher
                && revisionCode.equals(other.revisionCode);
    }

    /** @return 是否为同一「册」（同出版社同年级同册次，忽略版次）。 */
    public boolean sameBook(@NonNull EditionCoordinate other) {
        return subject == other.subject
                && grade == other.grade
                && term == other.term
                && publisher == other.publisher;
    }

    /**
     * @return 人类可读标识，用于展示与审计（如「人教版·七年级上册·2024修订」）。
     */
    public @NonNull String display() {
        return publisher.displayName() + "·" + grade.displayName() + term.displayName()
                + "·" + revisionCode;
    }

    /** 学科。 */
    public enum Subject {
        MATH("数学"),
        CHINESE("语文"),
        ENGLISH("英语"),
        PHYSICS("物理"),
        CHEMISTRY("化学"),
        BIOLOGY("生物"),
        HISTORY("历史"),
        GEOGRAPHY("地理"),
        POLITICS("道法");

        private final String displayName;

        Subject(String displayName) {
            this.displayName = displayName;
        }

        public @NonNull String displayName() {
            return displayName;
        }
    }

    /** 学段年级。 */
    public enum GradeBand {
        GRADE_1("一年级"), GRADE_2("二年级"), GRADE_3("三年级"),
        GRADE_4("四年级"), GRADE_5("五年级"), GRADE_6("六年级"),
        GRADE_7("七年级"), GRADE_8("八年级"), GRADE_9("九年级"),
        HIGH_1("高一"), HIGH_2("高二"), HIGH_3("高三");

        private final String displayName;

        GradeBand(String displayName) {
            this.displayName = displayName;
        }

        public @NonNull String displayName() {
            return displayName;
        }
    }

    /** 册次。 */
    public enum Term {
        FIRST("上册"), SECOND("下册"), FULL_YEAR("全一册");

        private final String displayName;

        Term(String displayName) {
            this.displayName = displayName;
        }

        public @NonNull String displayName() {
            return displayName;
        }
    }

    /**
     * 出版社/版本体系。
     *
     * <p>⚠️ 用<b>枚举</b>而非自由文本：版本轴必须可枚举、可比较，
     * 否则「人教版」「人教」「PEP」会被当成三个不同版本，
     * 导致本该命中的语料因字符串差异而漏召回。
     */
    public enum Publisher {
        PEP("人教版"),
        SUEP("苏教版"),
        BEISHIDA("北师大版"),
        HUAJIAO("华师大版"),
        LUJIAO("鲁教版"),
        HUBEI("鄂教版"),
        OTHER("其他");

        private final String displayName;

        Publisher(String displayName) {
            this.displayName = displayName;
        }

        public @NonNull String displayName() {
            return displayName;
        }
    }
}
