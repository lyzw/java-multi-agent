package cn.studykid.multiagent.ports.observer;

import java.time.Duration;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 端到端追踪观测者（用 Trace ID 贯穿从 MQ 消费到最终提交的完整链路）。
 *
 * <p><b>注入点</b>：{@code RunDispatchConsumer.consume()} 入口和出口。
 * Trace ID 生成后注入 MDC，所有下游日志自动携带。
 *
 * <p><b>关键纪律</b>：
 * <ul>
 *   <li>观测者异常<b>不得影响主流程</b>——异常只记 WARN，不冒泡；</li>
 *   <li>Trace ID 必须唯一（建议：租户 + Run ID + 时间戳 + 随机后缀）；</li>
 *   <li>Trace 事件共享同一 Trace ID，便于聚合查询。</li>
 * </ul>
 *
 * <p><b>与现有端口的关系</b>：本观测者是"贯穿线"，不替代
 * {@code RunEventPort} / {@code RunStepPort} / {@code RiskEventPort}，
 * 而是把它们串联为一条可追踪的链路。
 */
public interface TraceObserver {

    /**
     * 追踪开始时触发（MQ 消息到达时）。
     *
     * @param traceId    追踪 ID（全局唯一）
     * @param tenantId   租户标识
     * @param runId      Run 标识
     * @param eventType  事件类型（如 "RUN_DISPATCH"）
     * @param payload    消息载荷（已脱敏）
     */
    void onTraceStart(@NonNull String traceId, @NonNull String tenantId,
                      @NonNull String runId, @NonNull String eventType,
                      @NonNull Map<String, Object> payload);

    /**
     * 追踪过程中触发（中间事件）。
     *
     * @param traceId 追踪 ID
     * @param event   事件名称（如 "STEP_STARTED", "MODEL_CALL_COMPLETE"）
     * @param data    事件数据（已脱敏，只含白名单字段）
     */
    void onTraceEvent(@NonNull String traceId, @NonNull String event,
                      @NonNull Map<String, Object> data);

    /**
     * 追踪结束时触发（执行完成时）。
     *
     * @param traceId      追踪 ID
     * @param outcome      执行结局（中性表示）
     * @param totalLatency 端到端总耗时
     */
    void onTraceEnd(@NonNull String traceId, @NonNull TraceOutcome outcome,
                    @NonNull Duration totalLatency);

    /**
     * 追踪因异常中断时触发。
     *
     * @param traceId      追踪 ID
     * @param error        异常类型（如 "INTERNAL_ERROR", "TIMEOUT"）
     * @param totalLatency 中断前的耗时
     */
    void onTraceAborted(@NonNull String traceId, @NonNull String error,
                        @NonNull Duration totalLatency);

    /**
     * 追踪结局（中性表示，与 RunDispatchConsumer.Outcome 对齐）。
     */
    enum TraceOutcome {
        SUCCEEDED,
        FAILED,
        CANCELLED,
        RETRYABLE_FAILURE,
        UNKNOWN_RESULT,
        STALE_GENERATION
    }
}
