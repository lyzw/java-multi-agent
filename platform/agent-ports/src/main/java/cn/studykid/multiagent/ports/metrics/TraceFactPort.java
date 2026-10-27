package cn.studykid.multiagent.ports.metrics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort.TraceIdSource;

/**
 * Trace 事实源读取端口（P7）。
 *
 * <p><b>⚠️ 为什么要有这个端口</b>：事实表（{@code public.run} / {@code usage_record} /
 * {@code tool_operation}）带 <b>FORCE RLS</b>，读取前必须绑定租户上下文；
 * 而「绑定租户 + 开连接」是基础设施职责，<b>应用层不得依赖基础设施层</b>
 * （分层铁律）。故把「取一条 Run 的全部事实」抽象成端口，由基础设施实现。
 *
 * <p>本端口<b>只读</b>，不写任何表。指标表的写入走 {@link AgentMetricPort}。
 *
 * <p><b>⚠️ 只返回已收敛 Run 的事实</b>：在途 Run 返回 {@link Optional#empty()}，
 * 由调用方直接放弃采集（计入会让「还没跑完」变成「没成功」）。
 */
public interface TraceFactPort {

    /** 读取一条 Run 的全部指标事实。Run 不存在或未收敛时返回 empty。 */
    @NonNull Optional<TraceFacts> load(@NonNull String tenantId, @NonNull UUID runId);

    /**
     * 解析一条 Run 的会话标识（{@code trace_id}）——<b>不要求 Run 已收敛</b>。
     *
     * <p><b>用途</b>：租户侧用户反馈（P7-3/P7-4）只持有 {@code runId}
     * （浏览器知道的是「这次辅导」，不是内部 trace 标识），
     * 故由服务端把 runId 解析成 trace_id，反馈才能落到对应的统计单元上。
     *
     * <p><b>⚠️ 租户边界是硬要求</b>：实现<b>必须</b>显式带租户谓词
     * （{@code WHERE run_id = ? AND tenant_id = ?}），<b>不得只依赖 RLS</b>——
     * RLS 依赖事务内的会话变量，跨进程 HTTP 入口未必建立该上下文；
     * 一旦漏掉谓词，租户 A 就能把反馈挂到租户 B 的 trace 上。
     *
     * <p><b>解析优先级</b>（与采集器同口径，保证反馈与指标落在同一单元）：
     * <ol>
     *   <li>{@code public.run.trace_id}（委派链路的真实会话标识）；</li>
     *   <li>{@code public.agent_invocation} 中 depth=1 的 trace_id；</li>
     *   <li>降级用 {@code correlation_id}（<b>必须留痕</b>，不冒充真 trace）。</li>
     * </ol>
     * 三者皆无时返回 empty——<b>不用假值</b>。
     */
    @NonNull Optional<String> resolveTraceId(@NonNull String tenantId, @NonNull UUID runId);

    /**
     * 一条 Run 的事实快照。
     *
     * @param run              Run 本体的收敛信息
     * @param usage            模型用量（可能为「一条记录都没有」，此时各字段为 null）
     * @param toolGroups       按 {@code (tool_name, request_digest)} 聚合的工具调用
     * @param knownToolNames   已定义工具名全集（用于识别模型幻觉出的工具）
     * @param steps            执行步骤聚合（P7-2 真实口径来源；未写入时为 {@code present=false}）
     */
    record TraceFacts(
            @NonNull RunFacts run,
            @NonNull UsageFacts usage,
            @NonNull List<ToolGroup> toolGroups,
            @NonNull Set<String> knownToolNames,
            @NonNull StepFacts steps) {
    }

    /**
     * 执行步骤聚合（P7-2）。
     *
     * <p>来自 {@code public.run_step}（按 {@code step_type} 分组计数、按 THINK 求时长）。
     * <b>未采集 = {@code present=false}</b>：当 {@code run_step} 没有任何行时，
     * {@code thinkMsTotal / toolStepCount / clarificationCount} 一律不可信，采集器据此
     * 把对应指标置 {@code null}（而不是退化近似或 0）。
     *
     * @param present            是否存在任何步骤行
     * @param thinkMsTotal       THINK 步骤的时长合计（毫秒）
     * @param toolStepCount      TOOL 步骤数（= 真实「工具轮次」）
     * @param clarificationCount ASK_USER 步骤数（当前执行循环无追问路径，恒 0）
     * @param stepCount          步骤总数
     * @param maxStepNumber      最大轮次号
     */
    record StepFacts(
            boolean present,
            long thinkMsTotal,
            long toolStepCount,
            long clarificationCount,
            long stepCount,
            int maxStepNumber) {

        /** 未采集（无任何步骤行）。 */
        public static final StepFacts ABSENT =
                new StepFacts(false, 0L, 0L, 0L, 0L, 0);
    }

    /** Run 本体。 */
    record RunFacts(
            @NonNull String runId,
            @NonNull String state,
            @Nullable String errorCode,
            @NonNull String traceId,
            @NonNull TraceIdSource traceIdSource,
            @NonNull Instant startedAt,
            @Nullable Instant endedAt,
            @Nullable String agentId,
            @Nullable Integer agentVersion) {

        /** 是否到达终态。在途（QUEUED / RUNNING）不采集。 */
        public boolean converged() {
            return switch (state) {
                case "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT" -> true;
                default -> false;
            };
        }

        public @Nullable Long durationMs() {
            return endedAt == null
                    ? null
                    : java.time.Duration.between(startedAt, endedAt).toMillis();
        }
    }

    /** 模型用量聚合。全为 null 表示该 Run 没有任何计量记录。 */
    record UsageFacts(
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable BigDecimal costAmount,
            @Nullable String currency,
            @Nullable String modelId) {

        /** 总 token；两侧皆空时为 null（⚠️ 不是 0 —— 没有计量记录 ≠ 消耗为 0）。 */
        public @Nullable Long totalTokens() {
            if (inputTokens == null && outputTokens == null) {
                return null;
            }
            return (inputTokens == null ? 0L : inputTokens)
                    + (outputTokens == null ? 0L : outputTokens);
        }
    }

    /**
     * 同一 {@code (tool_name, request_digest)} 的一组调用。
     *
     * <p>⚠️ 聚合键必须带 {@code request_digest}：只按工具名分组会把
     * 「同一工具、不同参数」的正常多轮调用误判成死循环。
     */
    record ToolGroup(
            @NonNull String toolName,
            int callCount,
            int failedCount,
            int invalidArgCount,
            boolean timeout,
            boolean upstreamError,
            @Nullable Long totalDurationMs,
            @Nullable Long maxDurationMs) {
    }
}
