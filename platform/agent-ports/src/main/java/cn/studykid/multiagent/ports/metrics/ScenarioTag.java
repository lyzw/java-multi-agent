package cn.studykid.multiagent.ports.metrics;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * 场景标签规范化器（{@code agent_trace_metric.scenario} 的唯一准入规则）。
 *
 * <p><b>⚠️ 为什么必须有这一层</b>：{@code run.input_payload} 是调用方可控的开放 Map，
 * 而宽表 {@code scenario} 会被平台侧按维度分组展示。若不设准入，
 * 调用方把整段学生提问塞进 {@code scenario}，就会把<b>对话原文</b>复制进指标宽表
 * （合规红线），同时让看板出现一个「一 Run 一个分组」的基数爆炸，
 * 「按场景拆分」从此不可用。本项目反复出现的失效形态正是
 * <b>不报错、只是数字悄悄错 / 数据悄悄越界</b>。
 *
 * <p><b>⚠️ 只接受显式标签，绝不从自由文本派生</b>：本类只校验一个已经由调用方
 * 明确给出的分类码（如 {@code 数学:一元二次方程} / {@code MATH_ALGEBRA}），
 * 不做任何「猜学科」「抽取知识点」的推断。自动派生规则需要参考答案 / 题库标准解析
 * 与产品口径（详见设计文档 §13 第 3 项），在那之前宁可保持「未标注」。
 *
 * <p><b>未标注 = {@code null}</b>：与全项目「未采集 ≠ 0 / 不编造」纪律一致。
 * 非法值一律降级为 {@code null}，而不是截断、转义或猜一个默认分组 ——
 * 后者会让「调用方传错了」变成「看板显示一个看似正常的场景」。
 */
public final class ScenarioTag {

    /** 标签最大长度（Unicode code point）。分类码不需要一整句话。 */
    private static final int MAX_LENGTH = 64;

    /**
     * 白名单：Unicode 字母（含中文）+ 数字 + 分层/连接符号。
     *
     * <p>刻意<b>不含空白与句读标点</b>：出现空格、逗号、句号、问号通常意味着
     * 传进来的是一句自然语言，而不是分类码。
     */
    private static final Pattern ALLOWED = Pattern.compile("[\\p{L}\\p{N}_.:/+\\-]+");

    private ScenarioTag() {
    }

    /**
     * 规范化一个原始标签值。
     *
     * @param raw {@code input_payload.scenario} 的原始文本；{@code null} = 未标注
     * @return 合法的去空白标签，或 {@code null}（未标注 / 非法）
     */
    public static @Nullable String normalize(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > MAX_LENGTH) {
            return null;
        }
        return ALLOWED.matcher(value).matches() ? value : null;
    }
}
