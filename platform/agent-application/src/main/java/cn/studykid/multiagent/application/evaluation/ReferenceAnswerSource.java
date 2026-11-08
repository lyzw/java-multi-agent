package cn.studykid.multiagent.application.evaluation;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

/**
 * 参考答案 / 标准解析的判分源（§13 第 3 项）。
 *
 * <p>⚠️ <b>这是「事实真相」的唯一来源</b>：LLM-as-judge 只能拿 Agent 的归因去比对它，
 *   不能凭空编造「正确率」。在拿到判分源前（产品确认题库连接方式与标准解析字段），
 *   默认实现 {@link NoReferenceAnswerSource} 一律返回 empty —— 此时 L2 不评级、不写
 *   {@code attribution_correct}，<b>绝不</b>用 LLM 猜一个「归因正确率」。
 *
 * <p>⚠️ <b>合规红线</b>：实现方<b>不得</b>把学生原题 / 参考答案的<b>原文</b>落进任何指标
 *   宽表；本接口只回传「标准解析文本」用于判定，调用方也只存布尔结论（attribution_correct）。
 */
public interface ReferenceAnswerSource {

    /** 某次问诊的参考答案 / 标准解析；无可用判分源时返回 empty（fail-closed）。 */
    @NonNull Optional<ReferenceAnswer> resolve(@NonNull String scenario, @NonNull String question);

    /** 参考答案 / 标准解析（只含标准内容文本，绝不含学生身份等敏感原文）。 */
    record ReferenceAnswer(@NonNull String scenario, @NonNull String question,
                           @NonNull String standardAnalysis) {
    }
}
