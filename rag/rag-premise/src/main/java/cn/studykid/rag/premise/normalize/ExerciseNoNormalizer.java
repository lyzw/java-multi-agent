package cn.studykid.rag.premise.normalize;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;

/**
 * 题号规范化器（RAG-D28 §2.2 / §3.3 P2-2）。
 *
 * <p>把解析器抽出的题号串（{@code 1.} / {@code （2）} / {@code Exercise 3} /
 * {@code 3.(2)} / {@code 5-7}）收敛为三种<b>互斥</b>规范键：
 * {@code Q003}（单级）、{@code Q003.S02}（多级子问）、{@code GRP:Q005-Q007}（题组）。
 * 不能可靠识别时返回显式 {@code UNMATCHED}，<b>不猜、不模糊匹配、不套最接近的题号</b>。
 *
 * <p><b>⚠️ 输入必须是题号字段，不是任意正文</b>。本类不负责从整段文本找题号
 * （那是 {@code LayoutPredicates.extractExerciseNo} 的职责）；把章节号 {@code 3.2}
 * 当题号传进来会被规范化为 {@code Q003.S02}——这是调用方的口径错误，
 * 由上游「有效题号先算、再判角色」的安全顺序保证（见 {@code BlockRoleResolver}）。
 *
 * <p><b>⚠️ 规范化幂等</b>：{@code Q003} / {@code Q003.S02} / {@code GRP:Q005-Q007}
 * 再次输入得到同一键，配对两侧可安全重复归一化。
 *
 * <p><b>⚠️ 答案多问拆键</b>（{@link #normalizeAnswerVariants}）：答案册里
 * 「{@code (1) A (2) B}」必须拆成 {@code Q003.S01} / {@code Q003.S02}，
 * 否则两个小问共用父题号 → 回指到同一道题，语义错误。
 */
public final class ExerciseNoNormalizer {

    /** 题组最大跨度：超过此值视为范围误识别（如年份 1998-2020），不猜题。 */
    static final int MAX_GROUP_SPAN = 20;

    private static final Pattern GROUP_CANONICAL =
            Pattern.compile("^GRP:Q(\\d{3})-Q(\\d{3})$");
    private static final Pattern GROUP_RANGE = Pattern.compile(
            "^(?:Questions?\\s*|第\\s*)?(\\d{1,3})\\s*(?:[-–—~～]|至|到)\\s*(\\d{1,3})\\s*(?:题)?$");

    private static final Pattern SUB_CANONICAL = Pattern.compile("^Q(\\d{3})\\.S(\\d{2})$");
    private static final Pattern SUB_DOTTED =
            Pattern.compile("^(?:Q)?(\\d{1,3})\\s*[.．]\\s*(?:S\\s*)?(\\d{1,2})$");
    private static final Pattern SUB_DOT_PAREN =
            Pattern.compile("^(\\d{1,3})\\s*[.．]\\s*[(（]\\s*(\\d{1,2})\\s*[)）]$");
    private static final Pattern SUB_PAREN = Pattern.compile(
            "^(?:第\\s*)?(\\d{1,3})\\s*题?\\s*[(（]\\s*(\\d{1,2})\\s*[)）]\\s*(?:问|小题)?$");

    private static final Pattern SINGLE_CANONICAL = Pattern.compile("^Q(\\d{3})$");
    private static final Pattern SINGLE_PAREN = Pattern.compile("^[(（]\\s*(\\d{1,3})\\s*[)）]$");
    private static final Pattern SINGLE = Pattern.compile(
            "^(?:Exercise\\s*|Questions?\\s*|第\\s*)?(\\d{1,3})\\s*(?:[.、)）]|题|问)?$");

    /** 答案多问的小问标记：行首「1.」或括号内「(2)」，避免把「2cm」当小问。 */
    private static final Pattern ANSWER_SUB_MARK =
            Pattern.compile("(?m)(?:^|[(（])\\s*(\\d{1,2})\\s*(?:[)）]|[.、])");

    // ---------- 块首题号提取（供配对链使用，不改上游解析器） ----------
    // ⚠️ LayoutPredicates.extractExerciseNo 的正则只认主编号："3.(2)" 会提取成 "3"。
    //    这里从块首文本补出多级/题组形态，只用于配对，不改 effectiveExerciseNo，
    //    避免把角色判定（BlockRoleResolver）的输入口径一并改动导致泄题闸门漂移。

    private static final Pattern LEADING_SUB = Pattern.compile(
            "^\\s*(?:第\\s*)?(\\d{1,3})\\s*[.．]\\s*[(（]\\s*(\\d{1,2})\\s*[)）]");
    private static final Pattern LEADING_GROUP = Pattern.compile(
            "^\\s*(?:Questions?\\s*|第\\s*)?(\\d{1,3})\\s*(?:[-–—~～]|至|到)\\s*(\\d{1,3})\\s*(?:题)?");
    private static final Pattern LEADING_SINGLE = Pattern.compile(
            "^\\s*(?:Exercise\\s*|Questions?\\s*|第\\s*)?(\\d{1,3})\\s*[.、)）]");
    /** 英文题干形态没有编号标点（{@code Exercise 3 Read…}），单列一条。 */
    private static final Pattern LEADING_ENGLISH_SINGLE = Pattern.compile(
            "^\\s*Exercise\\s+(\\d{1,3})\\b");

    private ExerciseNoNormalizer() {
    }

    /**
     * 规范化单个题号串。
     *
     * @param raw 原始题号（可为空）
     * @return 规范键；无法可靠识别时 {@code UNMATCHED}
     */
    public static @NonNull ExerciseKey normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return ExerciseKey.unmatched(raw);
        }
        String text = raw.trim();

        Matcher canonicalGroup = GROUP_CANONICAL.matcher(text);
        if (canonicalGroup.matches()) {
            return groupKey(canonicalGroup.group(1), canonicalGroup.group(2), text);
        }
        Matcher range = GROUP_RANGE.matcher(text);
        if (range.matches()) {
            return groupKey(range.group(1), range.group(2), text);
        }

        Matcher canonicalSub = SUB_CANONICAL.matcher(text);
        if (canonicalSub.matches()) {
            return subKey(canonicalSub.group(1), canonicalSub.group(2), text);
        }
        Matcher dotted = SUB_DOTTED.matcher(text);
        if (dotted.matches()) {
            return subKey(dotted.group(1), dotted.group(2), text);
        }
        Matcher dotParen = SUB_DOT_PAREN.matcher(text);
        if (dotParen.matches()) {
            return subKey(dotParen.group(1), dotParen.group(2), text);
        }
        Matcher paren = SUB_PAREN.matcher(text);
        if (paren.matches()) {
            return subKey(paren.group(1), paren.group(2), text);
        }

        Matcher canonicalSingle = SINGLE_CANONICAL.matcher(text);
        if (canonicalSingle.matches()) {
            return singleKey(canonicalSingle.group(1), text);
        }
        Matcher singleParen = SINGLE_PAREN.matcher(text);
        if (singleParen.matches()) {
            return singleKey(singleParen.group(1), text);
        }
        Matcher single = SINGLE.matcher(text);
        if (single.matches()) {
            return singleKey(single.group(1), text);
        }
        return ExerciseKey.unmatched(text);
    }

    /**
     * 从块首文本提取完整题号（含多级/题组），供题答配对使用。
     *
     * <p><b>⚠️ 与 {@link #normalize(String)} 的分工</b>：上游解析器的
     * {@code extractExerciseNo} 只认主编号（{@code 3.(2)} 提取成 {@code 3}），
     * 直接用它会把多级题压平。本方法只读<b>块首</b>（不是全文扫描），
     * 命中题号标点才认，用于配对链恢复完整题号；<b>不</b>改动角色判定输入。
     *
     * @param text 块文本（可为空）
     * @return 规范键；块首无题号时 UNMATCHED
     */
    public static @NonNull ExerciseKey normalizeFromText(String text) {
        if (text == null || text.isBlank()) {
            return ExerciseKey.unmatched(text);
        }
        Matcher sub = LEADING_SUB.matcher(text);
        if (sub.find()) {
            return subKey(sub.group(1), sub.group(2), text);
        }
        Matcher group = LEADING_GROUP.matcher(text);
        if (group.find()) {
            return groupKey(group.group(1), group.group(2), text);
        }
        Matcher single = LEADING_SINGLE.matcher(text);
        if (single.find()) {
            return singleKey(single.group(1), text);
        }
        Matcher english = LEADING_ENGLISH_SINGLE.matcher(text);
        if (english.find()) {
            return singleKey(english.group(1), text);
        }
        return ExerciseKey.unmatched(text);
    }

    /**
     * 从答案文本拆出多个小问键：父题号必须已经是单级题，且文本里出现明确的小问标记。
     *
     * <p>⚠️ 找不到小问标记时返回空列表，<b>不回退成父题号</b>——
     * 「这段答案没有可辨识的小问」与「这段答案属于父题」不是一回事，
     * 强绑会让检索把两个小问的答案混成一条。
     *
     * @param parentNo   父题号原始串
     * @param answerText 答案正文
     * @return 小问规范键列表（按出现顺序，去重）
     */
    public static @NonNull List<ExerciseKey> normalizeAnswerVariants(String parentNo,
                                                                      String answerText) {
        ExerciseKey parent = normalize(parentNo);
        if (parent.kind() != ExerciseKeyKind.SINGLE || answerText == null) {
            return List.of();
        }
        // canonical 形如 Q003；父题已在 [1,999]，substring 安全。
        String parentDigits = parent.canonical().substring(1);
        Matcher m = ANSWER_SUB_MARK.matcher(answerText);
        List<ExerciseKey> keys = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        while (m.find()) {
            int sub = Integer.parseInt(m.group(1));
            if (sub < 1 || sub > 99 || !seen.add(sub)) {
                continue;
            }
            keys.add(new ExerciseKey(
                    "Q" + parentDigits + ".S" + String.format("%02d", sub),
                    ExerciseKeyKind.SUB, answerText, null, null));
        }
        return List.copyOf(keys);
    }

    private static ExerciseKey singleKey(String digits, String raw) {
        int no = Integer.parseInt(digits);
        if (no < 1 || no > 999) {
            return ExerciseKey.unmatched(raw);
        }
        return new ExerciseKey("Q" + String.format("%03d", no),
                ExerciseKeyKind.SINGLE, raw, null, null);
    }

    private static ExerciseKey subKey(String parentDigits, String subDigits, String raw) {
        int parent = Integer.parseInt(parentDigits);
        int sub = Integer.parseInt(subDigits);
        if (parent < 1 || parent > 999 || sub < 1 || sub > 99) {
            return ExerciseKey.unmatched(raw);
        }
        return new ExerciseKey(
                "Q" + String.format("%03d", parent) + ".S" + String.format("%02d", sub),
                ExerciseKeyKind.SUB, raw, null, null);
    }

    private static ExerciseKey groupKey(String startDigits, String endDigits, String raw) {
        int start = Integer.parseInt(startDigits);
        int end = Integer.parseInt(endDigits);
        if (start < 1 || end < 1 || start > 999 || end > 999
                || end < start || end - start > MAX_GROUP_SPAN) {
            return ExerciseKey.unmatched(raw);
        }
        return new ExerciseKey(
                "GRP:Q" + String.format("%03d", start) + "-Q" + String.format("%03d", end),
                ExerciseKeyKind.GROUP, raw, start, end);
    }
}
