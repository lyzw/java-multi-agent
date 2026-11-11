package cn.studykid.multiagent.application.evaluation;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.QuestionIdTag;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.MessageView;

/**
 * L2 内容级归因判分（§13 第 3 项 · LLM-as-judge 兜底）。
 *
 * <p>⚠️ <b>fail-closed 是设计核心</b>：L2 只在校验条件<b>全部</b>满足时才评级，任一缺失即
 *   「不评级」并如实反映到看板（未启用 / 样本不足 / 无参考答案），<b>绝不</b>用 LLM 猜一个
 *   「归因正确率」：
 *   <ol>
 *     <li>开关未开（llm-judge.enabled / reference-source.enabled）→ 不评级；</li>
 *     <li>无判定器 bean（AttributionJudge 未装配）→ 不评级；</li>
 *     <li><b>无合法 {@code questionId}</b>（缺失 / 非法 / 非字符串）→ 不评级；
 *         不回退按题面文本匹配题库（已确认口径＝显式主键精确关联）；</li>
 *     <li>无可用参考答案（ReferenceAnswerSource 按 questionId 返回 empty）→ 不评级；</li>
 *     <li>回放无 USER / 无 ASSISTANT → 事实不足，不评级；</li>
 *     <li>抽样未命中 → 跳过（但不算「未评级」）。</li>
 *   </ol>
 *
 * <p>评出的结论<b>只</b>回填宽表 {@code agent_trace_metric.attribution_correct}
 *   （经 {@code AgentMetricPort.recordAttributionGrade}），使看板的「归因正确率(L2)」
 *   成为<b>真数字</b>。判定器返回 empty（拿不准）时记为「待人工」，不写布尔。
 *
 * <p>⚠️⚠️ <b>绝不写 {@code agent_eval_sample}</b>（2026-11-09 修正）：
 *   早先版本把机器结论写成 {@code verdict='ATTRIBUTION'} 塞进人工标注表，
 *   直接撞上两张表的 CHECK 约束（{@code verdict} / {@code human_verdict} 只收
 *   SUCCESS/PARTIAL/FAIL），且即便放宽约束也会污染「人工抽样覆盖率」——
 *   {@code recordEvalSample} 的回填会把 {@code sampled} 置 TRUE，
 *   机器判分就被人审统计计一遍（详细设计 §0.0 铁律第 2 条明令禁止）。
 *   现在机器结论与人工标注<b>物理分离</b>：机器写宽表列，人工走 eval_sample。
 */
@Service
public class L2AttributionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(L2AttributionEvaluator.class);

    /** 判分来源标识（写日志与证据摘要用；<b>不再</b>作为 eval_sample 的 verdict）。 */
    public static final String SOURCE = "ATTRIBUTION_JUDGE";

    private final ReferenceAnswerSource referenceSource;
    private final @Nullable AttributionJudge judge;
    private final TraceReplayPort replay;
    private final AgentMetricPort metrics;
    private final boolean llmJudgeEnabled;
    private final boolean referenceSourceEnabled;
    private final double sampleRate;

    public L2AttributionEvaluator(
            @NonNull ReferenceAnswerSource referenceSource,
            @Nullable AttributionJudge judge,
            @NonNull TraceReplayPort replay,
            @NonNull AgentMetricPort metrics,
            @Value("${agent.quality.eval.llm-judge.enabled:false}") boolean llmJudgeEnabled,
            @Value("${agent.quality.eval.reference-source.enabled:false}") boolean referenceSourceEnabled,
            @Value("${agent.quality.eval.llm-judge.sample-rate:0.05}") double sampleRate) {
        if (sampleRate < 0 || sampleRate > 1) {
            throw new IllegalArgumentException(
                    "agent.quality.eval.llm-judge.sample-rate 必须在 [0,1]，收到: " + sampleRate);
        }
        this.referenceSource = referenceSource;
        this.judge = judge;
        this.replay = replay;
        this.metrics = metrics;
        this.llmJudgeEnabled = llmJudgeEnabled;
        this.referenceSourceEnabled = referenceSourceEnabled;
        this.sampleRate = sampleRate;
    }

    public enum Outcome {
        /** 已评级并写入 attribution_correct。 */
        GRADED,
        /** 抽样未命中（已启用但未抽到）。 */
        SKIPPED_SAMPLING,
        /** 开关未开：llm-judge / reference-source 任一未启用。 */
        UNGRADED_DISABLED,
        /** 无判定器 bean（AttributionJudge 未装配）。 */
        UNGRADED_NO_JUDGE,
        /** 无合法题目标识：缺失 / 非法 / 非字符串，不做题面模糊匹配。 */
        UNGRADED_NO_QUESTION_ID,
        /** 回放无 USER / 无 ASSISTANT：事实不足。 */
        UNGRADED_NO_FACTS,
        /** 无可用参考答案（ReferenceAnswerSource 返回 empty）。 */
        UNGRADED_NO_REFERENCE,
        /** 判定器拿不准，返回 empty：记为待人工。 */
        UNGRADED_JUDGE_UNSURE
    }

    public record Result(@NonNull Outcome outcome, @Nullable Boolean attributionCorrect) {
        public static Result graded(boolean correct) {
            return new Result(Outcome.GRADED, correct);
        }

        public static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    /**
     * 对某次 Run 做 L2 归因正确率判分。
     *
     * @param questionId 来自 {@code run.input_payload.questionId} 的题目标识；
     *                   <b>缺失 / 非法一律不评级</b>，绝不退化成按题面文本匹配题库
     * @return 评级结果；不评级时 {@link Result#attributionCorrect()} 为 null
     */
    public @NonNull Result evaluate(@NonNull String tenantId, @NonNull String runId,
                                    @NonNull String traceId, @Nullable String scenario,
                                    @Nullable String questionId) {
        if (!llmJudgeEnabled || !referenceSourceEnabled) {
            return Result.of(Outcome.UNGRADED_DISABLED);
        }
        if (judge == null) {
            return Result.of(Outcome.UNGRADED_NO_JUDGE);
        }
        // ⚠️ 应用层再校验一次（事实仓储已用 QuestionIdTag 规范化）：
        //    纵深防御，防止本方法被别的调用方直接传入题面原文或畸形标识。
        String normalizedQuestionId = QuestionIdTag.normalize(questionId);
        if (normalizedQuestionId == null) {
            return Result.of(Outcome.UNGRADED_NO_QUESTION_ID);
        }
        List<MessageView> messages = replay.messages(tenantId, runId);
        String question = firstText(messages, "USER");
        String attribution = firstText(messages, "ASSISTANT");
        if (question == null || attribution == null) {
            return Result.of(Outcome.UNGRADED_NO_FACTS);
        }
        // ⚠️ 只按显式 questionId 精确关联；判分源实现不得接题面文本做模糊匹配。
        Optional<ReferenceAnswerSource.ReferenceAnswer> ref =
                referenceSource.resolve(normalizedQuestionId);
        if (ref.isEmpty() || !normalizedQuestionId.equals(ref.get().questionId())) {
            return Result.of(Outcome.UNGRADED_NO_REFERENCE);
        }
        if (!shouldSample(traceId)) {
            return Result.of(Outcome.SKIPPED_SAMPLING);
        }
        Optional<Boolean> correct = judge.judgeCorrect(new AttributionJudge.JudgeInput(
                normalizedQuestionId, scenario == null ? "" : scenario,
                question, attribution, ref.get()));
        if (correct.isEmpty()) {
            return Result.of(Outcome.UNGRADED_JUDGE_UNSURE);
        }
        // ⚠️ 只写宽表列（机器判分），不碰 agent_eval_sample / sampled / human_verdict。
        metrics.recordAttributionGrade(traceId, tenantId, correct.get());
        log.info("L2 归因判分已回写: trace={}, questionId={}, scenario={}, correct={}",
                traceId, normalizedQuestionId, scenario, correct.get());
        return Result.graded(correct.get());
    }

    /** 确定性抽样：与 L1 同算法，同一 traceId 永远同一结论。 */
    boolean shouldSample(@NonNull String traceId) {
        if (sampleRate <= 0) {
            return false;
        }
        if (sampleRate >= 1) {
            return true;
        }
        return Math.floorMod(traceId.hashCode(), 10_000) < (long) (sampleRate * 10_000);
    }

    private static String firstText(List<MessageView> messages, String role) {
        return messages.stream()
                .filter(m -> role.equals(m.role()))
                .map(m -> str(m.content().get("text")))
                .filter(t -> t != null && !t.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
