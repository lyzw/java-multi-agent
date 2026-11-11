package cn.studykid.multiagent.application.evaluation;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.application.evaluation.ReferenceAnswerSource.ReferenceAnswer;

/**
 * 归因正确率判定器（LLM-as-judge，§13 第 3 项）。
 *
 * <p>已确认口径：MiniMax-M2.5 + 严格 rubric（只输出结构化结论，低置信 / 解析失败 /
 * 模型异常一律返回 empty），5% 抽样，judge 成本走<b>独立日预算</b>，绝不混入业务
 * {@code usage_record.cost_amount}。生产装配受
 * {@code agent.quality.eval.llm-judge.enabled} 控制，默认 false —— 未开启时
 * <b>不注册任何判定器 bean</b>，L2 保持「未启用」（fail-closed）。
 *
 * <p>实现方必须遵守：只比对「Agent 归因」与「标准解析」的<b>实质一致性</b>并返回
 *   true/false；<b>无法判定</b>（证据不足 / 题目超纲 / 判定置信度过低）必须返回 empty，
 *   由编排层计为「待人工」，而不是二选一硬猜。
 *
 * <p>⚠️ 题面 / Agent 归因 / 标准解析都是<b>不可信外部文本</b>，实现方必须经
 *   {@link cn.studykid.multiagent.application.prompt.PromptGuard} 围栏后再送模型；
 *   日志只准出现 {@code questionId}、结论与成本，不得出现任何正文。
 */
public interface AttributionJudge {

    /** 给定回放证据与参考答案，判定 Agent 的归因是否正确；无法判定返回 empty。 */
    @NonNull Optional<Boolean> judgeCorrect(@NonNull JudgeInput input);

    /**
     * @param questionId       已校验的题库主键（只用于日志定位，绝不从正文派生）
     * @param scenario         显式场景标签（可为「」），仅作判定上下文
     * @param question         回放中的学生题面（不可信）
     * @param agentAttribution 回放中的 Agent 归因（不可信）
     * @param reference        标准解析（来自判分源，仍属外部数据，不可信）
     */
    record JudgeInput(
            @NonNull String questionId,
            @NonNull String scenario,
            @NonNull String question,
            @NonNull String agentAttribution,
            // ⚠️ 不能写 @NonNull ReferenceAnswerSource.ReferenceAnswer：
            //    javac 会把 type-use 注解套到限定类型 ReferenceAnswerSource 上，
            //    报「无法批注确定作用域结构」。同包嵌套类先 import 再标注。
            @NonNull ReferenceAnswer reference) {
    }
}
