package cn.studykid.multiagent.application.evaluation;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.metrics.QuestionIdTag;

/**
 * 参考答案 / 标准解析的判分源（§13 第 3 项）。
 *
 * <p>⚠️ <b>这是「事实真相」的唯一来源</b>：LLM-as-judge 只能拿 Agent 的归因去比对它，
 *   不能凭空编造「正确率」。默认实现 {@link NoReferenceAnswerSource} 一律返回 empty ——
 *   此时 L2 不评级、不写 {@code attribution_correct}，<b>绝不</b>用 LLM 猜一个「归因正确率」。
 *
 * <p>⚠️⚠️ <b>关联键只有 {@code questionId}，且必须精确匹配</b>（已确认口径）：
 *   调用方在 {@code run.input_payload.questionId} 显式标注题库主键，经
 *   {@link QuestionIdTag} 白名单校验后原样传进来；实现方<b>不得</b>接题面文本、
 *   <b>不得</b>按对话内容模糊匹配、<b>不得</b>在缺失/非法时猜一个「最接近的题」。
 *   题库没这题就是没有参考答案 —— 明确不评级，而不是造一个看似正常的判分结果。
 *
 * <p>本端口是可替换点：当前默认实现是平台本地题库文件源
 *   （{@link LocalFileReferenceAnswerSource}）；后续换 DB / HTTP 题库只需换一个 bean，
 *   L2 编排与判定器都不用改。
 *
 * <p>⚠️ <b>合规红线</b>：实现方<b>不得</b>把学生原题 / 身份信息落进任何指标宽表；
 *   本接口只回传「标准解析文本」用于判定，调用方也只存布尔结论（attribution_correct）。
 */
public interface ReferenceAnswerSource {

    /** 按题目标识精确取参考答案；缺失 / 非法 / 题库未收录时返回 empty（fail-closed）。 */
    @NonNull Optional<ReferenceAnswer> resolve(@NonNull String questionId);

    /**
     * 参考答案 / 标准解析。
     *
     * @param questionId       题库主键（已规范化），供回显与日志定位，不是学生原文
     * @param standardAnalysis 标准解析文本；只进判定器 Prompt，不落指标宽表
     */
    record ReferenceAnswer(@NonNull String questionId, @NonNull String standardAnalysis) {
    }
}
