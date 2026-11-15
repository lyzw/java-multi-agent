package cn.studykid.multiagent.infrastructure.observer.metrics;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;

import cn.studykid.multiagent.application.observability.AgentMetrics;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.observer.ExecutionObserver;

/**
 * 把执行循环的事实桥接到 {@link AgentMetrics}（P0 修复 · 2026-11-15）。
 *
 * <p><b>本类存在的理由</b>：{@code AgentMetrics} 有 Bean、有 {@code /metrics} 导出端点、
 * 有标签白名单测试，但 {@code increment} 在改动前<b>全仓 main 零调用</b>——
 * 端点永远只返回注释头，而所有单测测的都是纯函数，故全绿。
 * 这是本项目反复吃亏的「配了等于没配且静默」形态。
 *
 * <p><b>接线路径（不新增第二套执行链）</b>：本类实现 {@link ExecutionObserver}，
 * 由 {@code ObserverConfiguration} 注册为 Bean 后，自动进入 Spring 收集的
 * {@code List<ExecutionObserver>} → {@code executionObserverChain}；
 * 而该链<b>已经在生产装配里真实注入</b> {@code RunExecutionLoop}
 * （{@code WorkerExecutionConfig.runExecutionLoop}）。因此执行一次 Run 就会
 * 真实命中 {@code onLoopComplete}，{@code /metrics} 不再是空端点。
 *
 * <p><b>⚠️ 与 Micrometer 链路的分工</b>：
 * <ul>
 *   <li>{@code /actuator/prometheus}（Micrometer）→ 全量、带 tenant 等维度的运维指标；</li>
 *   <li>{@code /metrics}（{@link AgentMetrics}）→ <b>低基数白名单</b>强制的进程内聚合，
 *       供 FR-017「指标不得含高基数标签」的扫描证据使用。</li>
 * </ul>
 * 二者不是重复实现：本类只写<b>白名单内</b>标签，且全部由执行循环的真实事件驱动。
 *
 * <p><b>⚠️ 标签纪律</b>：只使用 {@link AgentMetrics#ALLOWED_LABEL_KEYS} 中的键，
 * 且值必须来自有限枚举或登记表。绝不把 tenantId / runId / traceId 作为标签
 * ——{@code requireSafeLabels} 会在第一次调用时 fail-fast。
 *
 * <p><b>⚠️ 不造数</b>：只对本类确实收到的事件计数；未收到即不写，
 * 绝不为了「让端点有数」而伪造任何指标。
 */
@Order(25)
public class AgentMetricsExecutionObserver implements ExecutionObserver {

    /** 执行循环结局计数（标签 outcome 取自有限枚举 {@link LoopOutcome.Kind}）。 */
    public static final String LOOPS_TOTAL = "agent_run_loops_total";

    /** 工具调用结局计数（标签 outcome 为有限枚举：SUCCEEDED/FAILED/REJECTED/UNKNOWN）。 */
    public static final String TOOL_CALLS_TOTAL = "agent_tool_calls_total";

    /** 模型调用完成计数（不带标签：provider 在本端口无可靠来源，不臆造）。 */
    public static final String MODEL_CALLS_TOTAL = "agent_model_calls_total";

    /** 上下文裁剪次数（不带标签：轮数/token 数是连续量，不能作标签）。 */
    public static final String CONTEXT_TRIMMED_TOTAL = "agent_context_trimmed_total";

    private final AgentMetrics metrics;

    public AgentMetricsExecutionObserver(@NonNull AgentMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void onLoopStart(@NonNull String tenantId, @NonNull String runId, int maxSteps) {
        // 循环开始不是结局，不计入 LOOPS_TOTAL（否则成功+失败会各被数两次）。
    }

    @Override
    public void onStepStart(@NonNull String tenantId, @NonNull String runId, int step) {
        // step 是连续量，不作标签；步数汇总由 LoopOutcome 携带的 totalSteps 表达，此处不造序列。
    }

    @Override
    public void onModelCallComplete(@NonNull String tenantId, @NonNull String runId, int step,
                                    @NonNull ModelResponse response, @NonNull Duration latency) {
        metrics.increment(MODEL_CALLS_TOTAL);
    }

    @Override
    public void onToolCallComplete(@NonNull String tenantId, @NonNull String runId, int step,
                                   @NonNull ToolCallSummary toolResult, @NonNull Duration latency) {
        // ⚠️ 按真实收到的分类计数；UNKNOWN 单独成序列，绝不并入 SUCCEEDED，
        //    否则「结果未知的副作用」会被伪装成成功（与 UNKNOWN 转对账的语义冲突）。
        incrementByCount("SUCCEEDED", toolResult.succeededTools().size());
        incrementByCount("FAILED", toolResult.failedTools().size());
        incrementByCount("REJECTED", toolResult.rejectedTools().size());
        if (toolResult.unknownResultOccurred()) {
            metrics.increment(TOOL_CALLS_TOTAL, Map.of("outcome", "UNKNOWN"));
        }
    }

    @Override
    public void onContextTrimmed(@NonNull String tenantId, @NonNull String runId,
                                 int droppedRounds, int droppedTokens,
                                 @NonNull List<String> droppedTools) {
        metrics.increment(CONTEXT_TRIMMED_TOTAL);
    }

    @Override
    public void onLoopComplete(@NonNull String tenantId, @NonNull String runId,
                               @NonNull LoopOutcome outcome, int totalSteps) {
        // 这是 /metrics 从「恒空」变为「有数」的承重调用点。
        metrics.increment(LOOPS_TOTAL, Map.of("outcome", outcome.kind().name()));
    }

    private void incrementByCount(String outcome, int count) {
        for (int i = 0; i < count; i++) {
            metrics.increment(TOOL_CALLS_TOTAL, Map.of("outcome", outcome));
        }
    }
}
