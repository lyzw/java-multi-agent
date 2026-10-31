package cn.studykid.multiagent.application.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.TerminationReason;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.ToolCallRow;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.TraceMetric;
import cn.studykid.multiagent.ports.metrics.TraceFactPort;
import cn.studykid.multiagent.ports.metrics.TraceFactPort.ToolGroup;
import cn.studykid.multiagent.ports.metrics.TraceFactPort.TraceFacts;

/**
 * Trace 指标采集器（P7-1）：把执行事实派生为一条 {@code agent_trace_metric} 行。
 *
 * <p><b>⚠️ 分层</b>：本类只依赖端口。事实读取（含 RLS 租户绑定）由
 * {@link TraceFactPort} 承担，指标落库由 {@link AgentMetricPort} 承担。
 * 应用层<b>不碰 JDBC、不碰连接、不碰租户绑定</b>——那三个都是基础设施职责。
 *
 * <p><b>⚠️ 幂等</b>：指标是事实表的<b>确定函数</b>，重复采集必须得到同一结果。
 * metric 行按 {@code trace_id} 覆盖写、工具维度按 Trace 先删后插，绝不追加。
 *
 * <p><b>⚠️ 只采集已收敛的 Trace</b>：在途 Run 由事实端口直接返回空。
 * 计入在途会把「还没跑完」算成「没成功」，成功率必然失真。
 */
@Service
public class AgentMetricCollector {

    /** 循环调用判定阈值：同一 (工具, 参数摘要) 在单条 Trace 内出现 ≥3 次。 */
    static final int LOOP_THRESHOLD = 3;

    /** 超大 token 阈值（与仓储侧保持一致）。 */
    static final long OVERSIZED_TOKEN_THRESHOLD = 20_000L;

    private final TraceFactPort facts;
    private final AgentMetricPort metrics;

    public AgentMetricCollector(@NonNull TraceFactPort facts, @NonNull AgentMetricPort metrics) {
        this.facts = facts;
        this.metrics = metrics;
    }

    /**
     * 采集一条 Run 对应的 Trace 指标。
     *
     * @return 采集后的 {@code trace_id}；Run 不存在 / 未收敛 / 无可用会话标识时返回空
     */
    public @NonNull Optional<String> collect(@NonNull String tenantId, @NonNull UUID runId) {
        TraceFacts f = facts.load(tenantId, runId).orElse(null);
        if (f == null) {
            return Optional.empty();
        }
        var run = f.run();
        // ⚠️ 纵深防御：事实端口已约定「在途返回空」，但采集器这里再判一次。
        //    只依赖上游约定会在这条链路被复用/改写时静默采到在途数据——
        //    而「在途被计入」的表现是成功率异常偏低，极难反查。
        if (!run.converged()) {
            return Optional.empty();
        }
        var usage = f.usage();
        TerminationReason reason = deriveTerminationReason(run.state(), run.errorCode(), f.toolGroups());

        int callTotal = 0;
        int callFailed = 0;
        int unknownTool = 0;
        int invalidArg = 0;
        int duplicate = 0;
        int loopGroups = 0;
        long toolMsTotal = 0L;

        List<ToolCallRow> rows = new ArrayList<>();
        for (ToolGroup g : f.toolGroups()) {
            callTotal += g.callCount();
            callFailed += g.failedCount();
            if (g.totalDurationMs() != null) {
                toolMsTotal += g.totalDurationMs();
            }
            if (!f.knownToolNames().contains(g.toolName())) {
                unknownTool += g.callCount();
            }
            invalidArg += g.invalidArgCount();
            // 第一次调用是正常的，多出来的才算重复
            if (g.callCount() > 1) {
                duplicate += g.callCount() - 1;
            }
            if (g.callCount() >= LOOP_THRESHOLD) {
                loopGroups += 1;
            }
            rows.add(new ToolCallRow(run.traceId(), tenantId, g.toolName(), g.callCount(),
                    g.failedCount(), g.invalidArgCount(), Math.max(0, g.callCount() - 1),
                    g.totalDurationMs(), g.maxDurationMs(), run.startedAt()));
        }

        // ⚠️ 三类无效调用互不重叠，可直接相加：未知工具按整组算、参数非法按次算、
        //    重复按超出次数算。分子超过分母（无效调用率 > 100%）通常就是这里算重了。
        int invalidTotal = unknownTool + invalidArg + duplicate;

        boolean hasTools = !f.toolGroups().isEmpty();
        TraceMetric metric = new TraceMetric(
                run.traceId(),
                tenantId,
                run.runId(),
                run.agentId(),
                run.agentVersion(),
                null,                                   // scenario：暂无来源（P7-3）
                usage.modelId(),
                run.startedAt(),
                run.endedAt(),
                run.durationMs(),
                run.state(),
                reason,
                usage.inputTokens(),
                usage.outputTokens(),
                usage.costAmount(),
                usage.currency(),
                // ── P7-2 真实口径：from run_step（执行步骤事实）──
                // ⚠️ 未采集 = null：run_step 历史零写入方，旧 Run 查不到步骤行
                //    → 这些 Run 的步数类指标如实显示「未采集」，绝不退回退化近似。
                //    近似口径与真实口径不可混在同一条趋势线比较（设计文档 R-4）。
                f.steps().present() ? (int) f.steps().toolStepCount() : null,
                hasTools ? callTotal : null,
                hasTools ? callFailed : null,
                hasTools ? invalidTotal : null,
                hasTools ? duplicate : null,
                loopGroups,
                // max_rounds_reached：以终止原因为准（P7-2 补 error_code 写入后真实）
                reason == TerminationReason.MAX_ROUNDS,
                f.steps().present() ? (int) f.steps().clarificationCount() : null,
                f.steps().present() ? f.steps().thinkMsTotal() : null,
                hasTools ? toolMsTotal : null,
                null, null, null, null, null, null,     // 效果类：待 P7-3 接入
                null, null, null, null,                 // 反馈类：待 P7-4 接入
                null, null, null,                       // 风险类：待 P7-4 接入
                run.traceIdSource());

        metrics.upsert(metric);
        metrics.replaceToolCalls(run.traceId(), rows);
        return Optional.of(run.traceId());
    }

    /** 超大 token 判定（供告警复用）。 */
    public boolean isOversized(@Nullable Long inputTokens, @Nullable Long outputTokens) {
        long total = (inputTokens == null ? 0L : inputTokens)
                + (outputTokens == null ? 0L : outputTokens);
        return total > OVERSIZED_TOKEN_THRESHOLD;
    }

    // ══════════════════════════════════════════════════════════════
    // 终止原因派生（P7 §3.1 映射规则为唯一权威）
    // ══════════════════════════════════════════════════════════════

    /**
     * 归一化终止原因。
     *
     * <p>⚠️ <b>兜底必须是 {@code OTHER} 而不是丢弃</b>：丢弃未识别原因会让
     * 「终止原因分布」的合计小于总数，看板上出现「不知道哪去了」的黑洞。
     */
    TerminationReason deriveTerminationReason(@NonNull String state,
                                              @Nullable String errorCode,
                                              List<ToolGroup> tools) {
        return switch (state) {
            case "SUCCEEDED" -> TerminationReason.COMPLETED;
            case "CANCELLED" -> TerminationReason.USER_CANCELLED;
            case "TIMED_OUT" -> tools.stream().anyMatch(ToolGroup::timeout)
                    ? TerminationReason.TOOL_TIMEOUT
                    // 整体 deadline 超时暂归 OTHER —— P7-2 细化前不冒充更具体的原因
                    : TerminationReason.OTHER;
            case "FAILED" -> deriveFailedReason(errorCode, tools);
            default -> TerminationReason.OTHER;
        };
    }

    private TerminationReason deriveFailedReason(@Nullable String errorCode, List<ToolGroup> tools) {
        String code = errorCode == null ? "" : errorCode.toUpperCase();
        if (code.startsWith("SAFETY") || code.startsWith("BLOCKED")) {
            return TerminationReason.SAFETY_BLOCK;
        }
        if (code.startsWith("BUDGET") || code.startsWith("TOKEN")) {
            return TerminationReason.TOKEN_BUDGET;
        }
        if (code.startsWith("MAX_STEPS") || code.startsWith("MAX_ROUNDS")
                || code.startsWith("STEP_LIMIT")) {
            return TerminationReason.MAX_ROUNDS;
        }
        // ⚠️ P0-3a：模型调用次数上限（MAX_LLM_CALLS）。
        //    它与 MAX_STEPS 是**两个不同的闸门**（轮次 vs 模型调用次数），
        //    但终止原因同属「执行次数触顶」—— 归到 MAX_ROUNDS，
        //    否则看板会把它算进 OTHER（「不知道哪去了」的黑洞）。
        if (code.startsWith("MAX_LLM_CALLS")) {
            return TerminationReason.MAX_ROUNDS;
        }
        if (code.startsWith("MODEL") || code.startsWith("LLM")) {
            return TerminationReason.MODEL_ERROR;
        }
        if (tools.stream().anyMatch(ToolGroup::timeout)) {
            return TerminationReason.TOOL_TIMEOUT;
        }
        if (tools.stream().anyMatch(ToolGroup::upstreamError)) {
            return TerminationReason.TOOL_5XX;
        }
        return TerminationReason.OTHER;
    }
}
