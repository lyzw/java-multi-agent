package cn.studykid.multiagent.application.evaluation;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

/**
 * 归因正确率判定器（LLM-as-judge，§13 第 3 项）。
 *
 * <p>⚠️ <b>本接口是「判定机制」的契约，不是实现</b>。平台当前<b>没有</b>可供评估链路
 *   调用的 LLM 客户端（agent 自身的模型调用发生在 worker，不暴露给平台评估进程），
 *   且「判定 rubric / 模型判分成本口径」仍待产品确认。因此本仓库<b>不提供</b>任何具体
 *   实现 —— 在装配一个真正的 {@code AttributionJudge} bean 之前，L2 判定器保持
 *   「无判定器」状态，L2 不评级（fail-closed），<b>不会</b>用占位实现冒充已接线。
 *
 * <p>实现方必须遵守：只比对「Agent 归因」与「标准解析」的<b>实质一致性</b>并返回
 *   true/false；<b>无法判定</b>（证据不足 / 题目超纲 / 判定置信度过低）必须返回 empty，
 *   由编排层计为「待人工」，而不是二选一硬猜。
 */
public interface AttributionJudge {

    /** 给定回放证据与参考答案，判定 Agent 的归因是否正确；无法判定返回 empty。 */
    @NonNull Optional<Boolean> judgeCorrect(@NonNull JudgeInput input);

    record JudgeInput(
            @NonNull String scenario,
            @NonNull String question,
            @NonNull String agentAttribution,
            ReferenceAnswerSource.ReferenceAnswer reference) {
    }
}
