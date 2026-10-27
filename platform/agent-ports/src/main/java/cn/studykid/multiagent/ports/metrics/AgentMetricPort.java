package cn.studykid.multiagent.ports.metrics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Agent 效果指标端口（P7）。
 *
 * <p><b>⚠️ 头号纪律：未采集 = {@code null}，绝不等于 0。</b>
 * 本端口所有「当前尚无采集点」的字段一律返回 {@code null}。返回 0 会让
 * 「没测过幻觉」在下游变成「幻觉率 0%」——这是本项目反复出现的失真来源。
 *
 * <p><b>⚠️ 统计单元是 Trace</b>：一条 Trace = 一次用户 Agent 会话任务。
 * 只有 Run 到达终态的 Trace 才被采集；在途 Trace 计入会把「还没跑完」
 * 算成「没成功」，让成功率失真（与 {@code StatisticsPort} 同源铁律）。
 *
 * <p><b>⚠️ 指标是事实表的确定函数</b>：重复采集必须得到同一结果，
 * 故 {@link #upsert} 以 {@code trace_id} 主键做幂等覆盖，不做追加。
 */
public interface AgentMetricPort {

    /** 幂等写入 / 覆盖一条 Trace 的指标。 */
    void upsert(@NonNull TraceMetric metric);

    /** 按 {@code trace_id} 读取。 */
    @NonNull Optional<TraceMetric> findByTraceId(@NonNull String traceId);

    /**
     * 覆盖写入一条 Trace 的工具维度明细（幂等，主键 {@code trace_id + tool_name}）。
     *
     * <p>⚠️ 采集器必须<b>先删后插</b>（同一 Trace 内覆盖），不能追加——
     * 追加会让重复采集把计数翻倍。
     */
    void replaceToolCalls(@NonNull String traceId, @NonNull List<ToolCallRow> rows);

    // ── 聚合查询（读 platform.mv_agent_metric_hourly 或明细表）──

    /** 区间总览：六类核心计数与求和。 */
    @NonNull Overview overview(@NonNull MetricQuery query);

    /** 终止原因分布（9 值归一，合计必须等于总数）。 */
    @NonNull List<ReasonCount> terminationDistribution(@NonNull MetricQuery query);

    /** 端到端耗时分位数（查明细，不查物化视图）。 */
    @NonNull LatencyPercentiles latency(@NonNull MetricQuery query);

    /** 工具维度：调用次数 / 失败率 / 耗时分位。 */
    @NonNull List<ToolMetric> toolBreakdown(@NonNull MetricQuery query);

    /** 异常明细 Top-N：超大 token / 循环调用 / 触达最大轮次。 */
    @NonNull List<AnomalyTrace> anomalies(@NonNull MetricQuery query, int limit);

    // ── 回填录入（效果 / 反馈 / 风险）──

    /** 录入抽样标注，并回写该 Trace 的 {@code sampled / human_verdict / ...}。 */
    void recordEvalSample(@NonNull EvalSample sample);

    /** 录入用户反馈事件，并累计回写。 */
    void recordFeedback(@NonNull FeedbackEvent event);

    /** 录入风险事件（仅摘要，绝不落原文）。 */
    void recordRiskEvent(@NonNull RiskEvent event);

    /** 录入自动终态校验回执，并回写 {@code auto_checked / auto_verified}。 */
    void recordTaskOutcome(@NonNull TaskOutcome outcome);

    // ══════════════════════════════════════════════════════════════
    // 数据契约
    // ══════════════════════════════════════════════════════════════

    /** 归一化终止原因（9 值）。兜底是 {@code OTHER}，绝不丢弃未识别原因。 */
    enum TerminationReason {
        COMPLETED, USER_CANCELLED, MODEL_ERROR, TOOL_TIMEOUT, TOOL_5XX,
        MAX_ROUNDS, SAFETY_BLOCK, TOKEN_BUDGET, OTHER
    }

    /** {@code trace_id} 的来源 —— 降级必须留痕，不能冒充同源。 */
    enum TraceIdSource { RUN_TRACE_ID, INVOCATION_TRACE_ID, CORRELATION_FALLBACK }

    /**
     * 一条 Trace 的指标行。
     *
     * <p>⚠️ 所有 {@code Integer} / {@code Boolean} 字段均<b>可空</b>：
     * {@code null} 表示「未采集」，与「采了是 0」语义不同。
     */
    record TraceMetric(
            @NonNull String traceId,
            @NonNull String tenantId,
            @NonNull String runId,
            @Nullable String agentId,
            @Nullable Integer agentVersion,
            @Nullable String scenario,
            @Nullable String modelId,
            @NonNull Instant startedAt,
            @Nullable Instant endedAt,
            @Nullable Long durationMs,
            @NonNull String terminalState,
            @NonNull TerminationReason terminationReason,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable BigDecimal costAmount,
            @Nullable String currency,
            @Nullable Integer toolRounds,
            @Nullable Integer toolCallTotal,
            @Nullable Integer toolCallFailed,
            @Nullable Integer toolCallInvalid,
            @Nullable Integer toolCallDuplicate,
            @Nullable Integer loopSuspectedCount,
            @Nullable Boolean maxRoundsReached,
            @Nullable Integer clarificationCount,
            @Nullable Long thinkMsTotal,
            @Nullable Long toolMsTotal,
            @Nullable Boolean autoChecked,
            @Nullable Boolean autoVerified,
            @Nullable Boolean sampled,
            @Nullable String humanVerdict,
            @Nullable Boolean hallucination,
            @Nullable Boolean instructionFollowed,
            @Nullable Integer feedbackUp,
            @Nullable Integer feedbackDown,
            @Nullable Integer retryCount,
            @Nullable Boolean abandoned,
            @Nullable Integer safetyBlockedCount,
            @Nullable Integer unauthorizedAttemptCount,
            @Nullable Integer sensitiveOutputHits,
            @NonNull TraceIdSource traceIdSource) {
    }

    /** 一条 Trace 内某个工具的聚合行。 */
    record ToolCallRow(
            @NonNull String traceId,
            @NonNull String tenantId,
            @NonNull String toolName,
            long callCount,
            long failedCount,
            long invalidCount,
            long duplicateCount,
            @Nullable Long totalDurationMs,
            @Nullable Long maxDurationMs,
            @NonNull Instant startedAt) {
    }

    /** 查询条件。{@code tenantId} 为空表示平台视角跨租户。 */
    record MetricQuery(
            @NonNull Instant from,
            @NonNull Instant to,
            @Nullable String tenantId,
            @Nullable String agentId,
            @Nullable Integer agentVersion,
            @Nullable String scenario) {

        /** 区间跨度上限 90 天 —— 明细表扫全量会把库拖垮。 */
        public static final long MAX_RANGE_DAYS = 90;

        public @NonNull MetricQuery clamp() {
            long days = Duration.between(from, to).toDays();
            if (days <= MAX_RANGE_DAYS) {
                return this;
            }
            return new MetricQuery(from, from.plus(Duration.ofDays(MAX_RANGE_DAYS)), tenantId, agentId, agentVersion, scenario);
        }
    }

    /**
     * 六类总览。
     *
     * <p>⚠️ {@code linkSuccessRate} 是<b>链路成功率</b>（框架没崩），
     * <b>不等于任务成功率</b>。任务成功率看 {@code sampledSuccessRate}（L2）。
     * 两者在 UI 上必须分区隔离，不可互换标签。
     *
     * <p>⚠️ {@code autoCheckCoverage} / {@code sampledCount} 是指标的<b>组成部分</b>
     * 而不是补充信息：只校验了 3% 的任务时，「自动校验成功率 99%」本身即误导。
     */
    record Overview(
            long traceCount,
            long succeededCount,
            @Nullable Double linkSuccessRate,
            @Nullable Double avgDurationMs,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable BigDecimal costAmount,
            @Nullable Double avgCostPerTrace,
            @Nullable Double oversizedTokenRate,
            @Nullable Double avgToolRounds,
            @Nullable Double maxRoundsReachedRate,
            @Nullable Double invalidCallRate,
            long loopSuspectedTraces,
            @Nullable Long autoCheckedCount,
            @Nullable Long autoVerifiedCount,
            @Nullable Double autoVerifiedRate,
            @Nullable Double autoCheckCoverage,
            @Nullable Long sampledCount,
            @Nullable Double sampledSuccessRate,
            @Nullable Double falseSuccessRate,
            @Nullable Double hallucinationRate,
            @Nullable Double instructionFollowRate,
            @Nullable Long feedbackUpCount,
            @Nullable Long feedbackDownCount,
            @Nullable Double thumbUpRate,
            @Nullable Double abandonRate,
            long safetyBlockedTraces,
            long unauthorizedTraces,
            long sensitiveOutputTraces,
            @NonNull Instant refreshedAt) {

        /**
         * 抽样指标是否足以判断。
         *
         * <p>⚠️ {@code n < 30} 时一律判定不足 —— 用 2 条样本算出的 50% 幻觉率
         * 是噪声，不是指标。
         */
        public boolean sampleSufficient() {
            return sampledCount != null && sampledCount >= MIN_SAMPLE_SIZE;
        }

        public static final long MIN_SAMPLE_SIZE = 30;
    }

    /** 终止原因计数。9 值合计必须等于总数（兜底 OTHER 保证不丢）。 */
    record ReasonCount(@NonNull TerminationReason reason, long count) {
    }

    /**
     * 端到端耗时分位数（毫秒）。
     *
     * <p>⚠️ 分位数<b>必须查明细表</b>，不能从物化视图的 sum/count 还原。
     */
    record LatencyPercentiles(@Nullable Double p50, @Nullable Double p95, @Nullable Double p99) {
    }

    /**
     * 工具维度指标。
     *
     * <p>⚠️ <b>不提供 P95</b>：工具耗时按 {@code Trace × 工具名} 聚合存
     * {@code total / max}，分位数无法从计数与求和还原。用 max 冒充 P95 会让
     * 尾部延迟被高估——这里如实给均值与最大值。
     */
    record ToolMetric(
            @NonNull String toolName,
            long callCount,
            long failedCount,
            @Nullable Double failRate,
            @Nullable Double avgDurationMs,
            @Nullable Double maxDurationMs) {
    }

    /** 异常 Trace 明细（用于「死循环长什么样」的具象排查）。 */
    record AnomalyTrace(
            @NonNull String traceId,
            @NonNull AnomalyKind kind,
            @Nullable Long totalTokens,
            @Nullable Integer toolRounds,
            @Nullable Integer loopCount,
            @NonNull Instant startedAt) {

        public enum AnomalyKind { OVERSIZED_TOKEN, LOOP_SUSPECTED, MAX_ROUNDS }
    }

    /** 抽样标注录入。 */
    record EvalSample(
            @NonNull String traceId,
            @NonNull String tenantId,
            @NonNull String verdict,
            @Nullable Boolean hallucination,
            @Nullable Boolean instructionFollowed,
            @Nullable String note,
            @NonNull String sampler) {
    }

    /** 用户反馈事件（L3 有噪信号）。 */
    record FeedbackEvent(
            @NonNull String traceId,
            @NonNull String tenantId,
            @NonNull FeedbackKind kind) {

        public enum FeedbackKind { THUMB_UP, THUMB_DOWN, RETRY, ABANDON }
    }

    /** 风险事件（仅摘要）。 */
    record RiskEvent(
            @NonNull String traceId,
            @NonNull String tenantId,
            @NonNull RiskType eventType,
            @Nullable String detailDigest) {

        public enum RiskType { SAFETY_BLOCK, UNAUTHORIZED_CALL, SENSITIVE_OUTPUT }
    }

    /** 自动终态校验回执（L1）。 */
    record TaskOutcome(
            @NonNull String traceId,
            @NonNull String tenantId,
            @NonNull String checkerCode,
            @Nullable String externalRef,
            boolean verified,
            @Nullable String evidenceDigest) {
    }
}
