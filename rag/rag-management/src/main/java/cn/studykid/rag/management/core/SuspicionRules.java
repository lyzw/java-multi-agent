package cn.studykid.rag.management.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 抽检疑点判定规则（交互文档 §4.6「看红灯」）。
 *
 * <p><b>为什么把规则做成纯函数</b>：这些判据是抽检质量的地基，
 * 必须能被穷举单测；放进 JDBC 实现就会被「要起库才能测」拖住，
 * 实践中必然退化成没人敢改的黑盒。
 *
 * <p><b>⚠️ 宁可多报，不可漏报</b>：本类只负责**标出可疑**，
 * 由人做最终裁决。故判据刻意偏宽——多标一条的代价是多看一眼，
 * 漏标一条的代价是泄题。
 *
 * <p><b>⚠️ 与 {@link InspectionRules#evaluate} 的关系</b>：
 * 本类**不参与**抽检结论聚合。人提交的 {@link InspectionRecord}
 * 才是判定依据；本类只决定「先给人看哪几条」。
 */
public final class SuspicionRules {

    private SuspicionRules() {
    }

    /** 片段长度超过此值即视为「可能切分边界有误」。 */
    public static final int OVERSIZED_THRESHOLD = 2000;

    /**
     * 片段去空白后为空才算「残渣」。
     *
     * ⚠️ 不再用「短于 N 字」判：真实教材里有大量 2~8 字的有效页眉/图注片段
     *    （实测 1463 条中 803 条短于 10 字），按长度判会把疑点清单淹没。
     */
    public static final int NEARLY_EMPTY_THRESHOLD = 0;

    /**
     * 答案区特征词。
     *
     * ⚠️ 刻意包含多种写法：教材答案区的排版五花八门，
     * 只认一种写法会让漏判率虚高。
     */
    private static final Pattern ANSWER_MARKERS = Pattern.compile(
            "参考答案|答案[:：]|答案与解析|解析[:：]|解答[:：]|"
                    + "练习答案|习题答案|本章答案|Self-check\\s*answers?|Answer\\s*key|Answers?[:：]",
            Pattern.CASE_INSENSITIVE);

    /**
     * 「题号-选项序列」形态：如 {@code 1-5: B A C D A}、{@code 1．A  2．B}、{@code 1.A 2.B}。
     *
     * ⚠️ 这是**答案区最强特征**：正文讲解里不会出现成串的「题号 + 单个字母」。
     */
    private static final Pattern ANSWER_SEQUENCE = Pattern.compile(
            "(?:\\d{1,3}\\s*[-－~～.]\\s*\\d{1,3}\\s*[:：]\\s*[A-Da-d][\\s,，;；]*)"
                    + "|(?:(?:\\d{1,3}\\s*[.．、]\\s*[A-Da-d]\\b[\\s,，;；]*){2,})");

    /**
     * 判定一条片段是否可疑。
     *
     * @param chunk 片段事实
     * @return 命中若干成因时返回对应的疑点（按严重度排序）；完全正常返回空列表
     */
    @NonNull
    public static List<InspectionSuspicion> inspect(@NonNull ChunkFacts chunk) {
        List<InspectionSuspicion> findings = new ArrayList<>(2);
        String text = chunk.text() == null ? "" : chunk.text();
        boolean answerBearing = isAnswerBearing(chunk.role());

        // ① 疑似答案漏判（最高优先级：漏判即泄题）
        if (!answerBearing && looksLikeAnswerZone(text)) {
            findings.add(build(chunk, InspectionSuspicion.Reason.SUSPECT_ANSWER_MISSED));
        }

        // ② 答案类片段缺题号
        if (answerBearing && isBlank(chunk.exerciseNo())) {
            findings.add(build(chunk, InspectionSuspicion.Reason.ANSWER_WITHOUT_EXERCISE_NO));
        }

        // ③ 习题片段缺题号（题号保留率要求 100%）
        if ("EXERCISE".equalsIgnoreCase(chunk.role()) && isBlank(chunk.exerciseNo())) {
            findings.add(build(chunk, InspectionSuspicion.Reason.EXERCISE_WITHOUT_NO));
        }

        // ④ 片段过长
        if (text.length() > OVERSIZED_THRESHOLD) {
            findings.add(build(chunk, InspectionSuspicion.Reason.OVERSIZED_CHUNK));
        }

        // ⑤ 近乎为空
        //
        // ⚠️⚠️ **这条判据只在「什么都没有」时才报**（实测修正）：
        //    真实教材 PDF 里页眉页脚、封面、图注会被切出大量**极短但有意义**的片段
        //    （「[图]」「六年级 上册」「义务教育教科书」）。
        //    实测一册 1463 条里有 803 条短于 10 字——若全标为疑点，
        //    HIGH 级的「疑似答案漏判」会被彻底淹没，人就又回到逐条翻的境地，
        //    抽检优化等于白做。
        //    故收窄为「去空白后完全为空」才报：那是真正的解析残渣，
        //    有字的短片段属于「内容偏少」而非「疑点」。
        if (text.strip().isEmpty()) {
            findings.add(build(chunk, InspectionSuspicion.Reason.NEARLY_EMPTY_CHUNK));
        }

        // ⑥ 答案类缺页码
        if (answerBearing && chunk.pageNumber() == null) {
            findings.add(build(chunk, InspectionSuspicion.Reason.NO_LOCATOR));
        }

        findings.sort(Comparator.comparingInt((InspectionSuspicion s) -> s.severity().order())
                .thenComparing(s -> s.chunkId()));
        return findings;
    }

    /**
     * 正文是否像答案区。
     *
     * <p>⚠️ 只看**结构特征**，不做语义理解：特征是「答案键」「题号序列」这类
     * 正文讲解中不会出现的形态。语义误判留给人工确认。
     */
    public static boolean looksLikeAnswerZone(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (ANSWER_MARKERS.matcher(text).find()) {
            return true;
        }
        return ANSWER_SEQUENCE.matcher(text).find();
    }

    /** 角色是否属于答案类（与 {@code ChunkRole.isAnswerBearing} 同口径）。 */
    public static boolean isAnswerBearing(@Nullable String role) {
        return "EXAMPLE_SOLUTION".equalsIgnoreCase(role)
                || "EXERCISE_ANSWER".equalsIgnoreCase(role);
    }

    private static InspectionSuspicion build(ChunkFacts chunk,
                                             InspectionSuspicion.Reason reason) {
        return InspectionSuspicion.of(
                chunk.chunkId(), chunk.documentId(), chunk.role(), chunk.text(),
                chunk.pageNumber(), chunk.sectionPath(), chunk.exerciseNo(), reason);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /**
     * 判定所需的片段事实。
     *
     * <p>⚠️ 刻意只带判定需要的字段：把整张表映射进来会让本类
     * 与存储结构强耦合，进而无法单测。
     *
     * @param chunkId     片段 ID
     * @param documentId  所属载体
     * @param role        当前角色
     * @param text        正文
     * @param pageNumber  页码（可空）
     * @param sectionPath 章节路径（可空）
     * @param exerciseNo  题号（可空）
     */
    public record ChunkFacts(
            @NonNull String chunkId,
            @NonNull String documentId,
            @NonNull String role,
            @Nullable String text,
            @Nullable Integer pageNumber,
            @Nullable String sectionPath,
            @Nullable String exerciseNo
    ) {
    }
}
