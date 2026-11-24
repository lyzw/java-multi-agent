package cn.studykid.rag.premise.normalize;

import org.jspecify.annotations.NonNull;

/**
 * 规范化题号键（RAG-D28 §2.2 / §3.3 P2-2）。
 *
 * <p>三种键形态严格区分（不可互相转换）：
 * <ul>
 *   <li>单级：{@code Q003}</li>
 *   <li>多级子问：{@code Q003.S02}</li>
 *   <li>题组：{@code GRP:Q005-Q007}</li>
 * </ul>
 *
 * <p><b>⚠️ 分组键与单级键不兼容</b>：答案标「第 5-7 题」时不能回指「第 5 题」——
 * 那是猜题强绑。配对只在 canonical <b>完全相等</b>时进行（见 {@code QuestionAnswerPairer}）。
 *
 * <p><b>⚠️ UNMATCHED 是显式状态</b>，不是 null、不是空串：
 * 无法规范化时必须留下「没认出来」的证据，供人工抽检与门禁统计，
 * 禁止用「看起来最接近」的题号顶替。
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code PremiseTopLevelTypeGuardTest}。
 *
 * @param canonical  规范化键（Q003 / Q003.S02 / GRP:Q005-Q007 / UNMATCHED）
 * @param kind       形态
 * @param raw        原始题号串（未识别可为空）
 * @param groupStart 题组起始题号（仅 GROUP 有值）
 * @param groupEnd   题组结束题号（仅 GROUP 有值）
 */
public record ExerciseKey(
        @NonNull String canonical,
        @NonNull ExerciseKeyKind kind,
        String raw,
        Integer groupStart,
        Integer groupEnd) {

    /** UNMATCHED 的唯一 canonical 值。 */
    public static final String UNMATCHED = "UNMATCHED";

    public ExerciseKey {
        if (canonical == null || canonical.isBlank()) {
            throw new IllegalArgumentException("canonical 不能为空");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind 不能为空");
        }
    }

    /** @return 是否被可靠识别（UNMATCHED 恒 false） */
    public boolean matched() {
        return kind != ExerciseKeyKind.UNMATCHED;
    }

    /** @return UNMATCHED 键（raw 原样保留以便抽检定位） */
    public static @NonNull ExerciseKey unmatched(String raw) {
        return new ExerciseKey(UNMATCHED, ExerciseKeyKind.UNMATCHED, raw, null, null);
    }
}
