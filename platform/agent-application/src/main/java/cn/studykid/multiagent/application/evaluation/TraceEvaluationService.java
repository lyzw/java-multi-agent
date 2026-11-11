package cn.studykid.multiagent.application.evaluation;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.TaskOutcome;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Priority;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.ReviewTaskSpec;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Source;

/**
 * 质量评估双轨编排（P3 · G3）：<b>L1 规则判分 → 覆盖率回写 → 分层抽样入人审</b>。
 *
 * <p>⚠️ <b>双轨在这里的具体含义</b>：
 * <ol>
 *   <li><b>自动轨（L1）</b>：{@link L1AttributionEvaluator} 对回放时间线做确定性规则判分，
 *       结论回写 {@code TaskOutcome} → 宽表 {@code auto_checked/auto_verified}，
 *       看板因此有「自动校验覆盖率」这个真数字。</li>
 *   <li><b>人审轨（L2）</b>：按确定性哈希抽样，把 Trace 推进
 *       {@code review_task(source='SAMPLE_RANDOM')} 形成黄金基线；
 *       L1 事实不足的推 {@code source='L1_GAP'} 优先人审。
 *       <b>绝不把规则结论写进 {@code agent_eval_sample}</b>（那是人工/标注表，
 *       机器结论混进去会让「人工抽样覆盖率」变成假指标）。</li>
 * </ol>
 *
 * <p>⚠️ <b>L2 内容级归因判分（§13 第 3 项）已接骨架，但默认 fail-closed</b>：
 *   {@link L2AttributionEvaluator} 已接入本编排，在 L1 之后执行。骨架包含平台本地题库
 *   文件源、显式 {@code questionId} 精确关联、MiniMax 严格 rubric 判定器与<b>独立日预算</b>；
 *   但它只在<b>全部</b>满足时才评级 —— 两个开关启用、题库收录该 questionId、判定器给出
 *   足够置信的结论、回放事实充足、抽样命中。
 *   生产默认 {@code NoReferenceAnswerSource}（返回 empty）+ 不注册 {@code AttributionJudge}，
 *   L2 明确「未启用」—— 看板显示「未启用 / 样本不足」，<b>绝不</b>编造归因正确率。
 *   机器结论只写 {@code agent_trace_metric.attribution_correct}，绝不写 {@code agent_eval_sample}。
 *
 * <p><b>确定性抽样</b>：用 {@code traceId.hashCode()} 取模而非随机数，
 * 同一 Trace 重放时结论一致（不会因重采一次就多出一条待审任务）。
 */
@Service
public class TraceEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(TraceEvaluationService.class);

    private final L1AttributionEvaluator l1;
    private final L2AttributionEvaluator l2;
    private final AgentMetricPort metrics;
    private final ReviewTaskPort reviewTasks;
    private final boolean enabled;
    private final double sampleRate;

    public TraceEvaluationService(
            @NonNull L1AttributionEvaluator l1,
            @NonNull L2AttributionEvaluator l2,
            @NonNull AgentMetricPort metrics,
            @NonNull ReviewTaskPort reviewTasks,
            @Value("${agent.quality.eval.enabled:true}") boolean enabled,
            @Value("${agent.quality.eval.sample-rate:0.05}") double sampleRate) {
        if (sampleRate < 0 || sampleRate > 1) {
            throw new IllegalArgumentException("agent.quality.eval.sample-rate 必须在 [0,1]，收到: "
                    + sampleRate);
        }
        this.l1 = l1;
        this.l2 = l2;
        this.metrics = metrics;
        this.reviewTasks = reviewTasks;
        this.enabled = enabled;
        this.sampleRate = sampleRate;
    }

    /**
     * 采集完成后的一次评估（L1 规则判分 → L2 内容级归因判分 → 分层抽样入人审）。
     *
     * @param scenario    显式场景标签（可为 null）；仅作为 L2 判定器的上下文，不派生。
     * @param questionId  显式题目标识（可为 null）；来自事实端口，只喂给 L2 判分源精确关联，
     *                    <b>不写入指标宽表</b>，也与 L1 的规则判分无关。
     * @return 本轮新建的待审任务 id（无则 empty）
     */
    public @NonNull Optional<String> evaluate(@NonNull String tenantId,
                                              @NonNull String runId,
                                              @NonNull String traceId,
                                              @Nullable String scenario,
                                              @Nullable String questionId) {
        if (!enabled) {
            return Optional.empty();
        }

        Optional<L1AttributionEvaluator.Verdict> verdict;
        try {
            verdict = l1.judge(tenantId, runId);
        } catch (RuntimeException e) {
            // 回放读失败不能拖垮采集；但也绝不能「当作判过了」。
            log.warn("L1 判分失败（已忽略，不计入自动校验覆盖）: trace={}, cause={}",
                    traceId, e.toString());
            return Optional.empty();
        }

        if (verdict.isEmpty()) {
            // 事实不足 → L1 覆盖缺口，交人审；不回写 TaskOutcome（否则 auto_checked 是假的）
            return createTask(traceId, tenantId, runId, Source.L1_GAP, Priority.HIGH,
                    "L1 判分事实不足（回放无消息或无 USER），需人工复盘");
        }

        L1AttributionEvaluator.Verdict v = verdict.get();
        metrics.recordTaskOutcome(new TaskOutcome(
                traceId, tenantId, L1AttributionEvaluator.CHECKER_CODE,
                null, v.linkComplete(), v.evidenceDigest()));

        // L2 内容级归因判分（§13 第 3 项）：fail-closed，
        // 无合法 questionId / 参考源 / 判定器 / 未启用则不写。
        try {
            l2.evaluate(tenantId, runId, traceId, scenario, questionId);
        } catch (RuntimeException e) {
            // 与 L1 同纪律：L2 异常绝不影响采集与 L1 结论，也不假装判过。
            log.warn("L2 归因判分异常（已忽略，不影响采集与 L1 结论）: trace={}, cause={}",
                    traceId, e.toString());
        }

        if (shouldSample(traceId)) {
            return createTask(traceId, tenantId, runId, Source.SAMPLE_RANDOM, Priority.NORMAL,
                    "黄金基线抽样 | L1=" + v.outcome() + "/" + v.signal());
        }
        return Optional.empty();
    }

    /** 确定性抽样：同一 traceId 永远得到同一结论。 */
    boolean shouldSample(@NonNull String traceId) {
        if (sampleRate <= 0) {
            return false;
        }
        if (sampleRate >= 1) {
            return true;
        }
        return Math.floorMod(traceId.hashCode(), 10_000) < (long) (sampleRate * 10_000);
    }

    private Optional<String> createTask(String traceId, String tenantId, String runId,
                                        Source source, Priority priority, String note) {
        if (reviewTasks.hasOpenTask(traceId)) {
            // ⚠️ P2 的异常联动可能已为同一 Trace 建了 ANOMALY/HIGH 任务；
            //    不重复排队，否则信噪比崩掉。
            return Optional.empty();
        }
        return Optional.of(reviewTasks.create(new ReviewTaskSpec(
                traceId, tenantId, runId, source, priority, note)));
    }
}
