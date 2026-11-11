package cn.studykid.multiagent.ports.metrics;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * 题目标识（{@code run.input_payload.questionId}）的唯一准入规则。
 *
 * <p><b>⚠️ 为什么单独于 {@link ScenarioTag}</b>：{@code scenario} 是给人看的分类码，
 * 允许 Unicode 中文/字母与 {@code : / + -}；而 {@code questionId} 是<b>题库主键</b>，
 * 必须与文件的标准解析<b>精确关联</b>。它绝不能是自然语言的一句题面 ——
 * 否则「按 questionId 关联」就退化成「按题面文本模糊匹配」，
 * 同一道题换个标点就找不到参考答案，L2 静默不评级且没有任何报错。
 *
 * <p><b>规则</b>：ASCII 字母/数字 + {@code _ . : -}，长度 ≤128，
 * 不含空白与句读标点。这是稳定标识的典型形态（如 {@code Q-MATH-2026-001}、
 * {@code 9f1c...-uuid}），足以覆盖题库主键，又不可能把整段题面原文带进判分链路。
 *
 * <p><b>未标注 / 非法一律 {@code null}</b>：与全项目「未采集 ≠ 0 / 不编造」纪律一致。
 * 绝不截断、转义或猜一个默认题干 —— 非法值进入判分源只会产生一个看似正常的
 * 「无参考答案」，把「调用方传错了」掩盖成「题库没这题」。
 */
public final class QuestionIdTag {

    /** 输入载荷里题目标识的键名（唯一权威，读写两端共用）。 */
    public static final String INPUT_FIELD = "questionId";

    /** 题目标识最大长度（ASCII 字符；题库主键不需要一整句话）。 */
    private static final int MAX_LENGTH = 128;

    /** 比 scenario 更严：只允许 ASCII，不允许中文/空白/句读。 */
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_.:-]+");

    private QuestionIdTag() {
    }

    /**
     * 规范化一个题目标识。
     *
     * @param raw {@code input_payload.questionId} 的原始文本；{@code null} = 未标注
     * @return 合法的标识，或 {@code null}（未标注 / 非法）
     */
    public static @Nullable String normalize(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return null;
        }
        return ALLOWED.matcher(value).matches() ? value : null;
    }
}
