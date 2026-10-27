package cn.studykid.multiagent.application.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.TerminationReason;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.ToolCallRow;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.TraceMetric;
import cn.studykid.multiagent.ports.metrics.TraceFactPort;
import cn.studykid.multiagent.ports.metrics.TraceFactPort.StepFacts;
import cn.studykid.multiagent.ports.metrics.TraceFactPort.ToolGroup;
import cn.studykid.multiagent.ports.metrics.TraceFactPort.TraceFacts;

/**
 * 指标采集器单测（P7-1）。
 *
 * <p>重点验证两条纪律：
 * <ol>
 *   <li><b>终止原因映射</b>：9 值归一，未识别必须兜底 {@code OTHER} 而不是丢弃；</li>
 *   <li><b>未采集 = null</b>：无计量记录 / 无工具调用时指标字段必须是 {@code null}，
 *       绝不能是 0 —— 否则「没测过」会显示成「测得很好」。</li>
 * </ol>
 */
class AgentMetricCollectorTest {

    private final RecordingMetrics metrics = new RecordingMetrics();
    private final AgentMetricCollector collector =
            new AgentMetricCollector(new StubFacts(), metrics);

    // ── 终止原因映射 ──────────────────────────────────────────────

    @Test
    @DisplayName("SUCCEEDED → COMPLETED；CANCELLED → USER_CANCELLED")
    void terminateByState() {
        assertEquals(TerminationReason.COMPLETED,
                collector.deriveTerminationReason("SUCCEEDED", null, List.of()));
        assertEquals(TerminationReason.USER_CANCELLED,
                collector.deriveTerminationReason("CANCELLED", null, List.of()));
    }

    @Test
    @DisplayName("TIMED_OUT：有工具超时归 TOOL_TIMEOUT，否则归 OTHER（不丢弃）")
    void timeoutReason() {
        ToolGroup timedOut = new ToolGroup("t", 1, 1, 0, true, false, 100L, 100L);
        assertEquals(TerminationReason.TOOL_TIMEOUT,
                collector.deriveTerminationReason("TIMED_OUT", null, List.of(timedOut)));
        // ⚠️ 兜底必须是 OTHER —— 丢掉未识别原因会让分布合计小于总数
        assertEquals(TerminationReason.OTHER,
                collector.deriveTerminationReason("TIMED_OUT", null, List.of()));
    }

    @Test
    @DisplayName("FAILED 按 error_code 前缀分流，未知前缀兜底 OTHER")
    void failedReasonByErrorCode() {
        assertEquals(TerminationReason.SAFETY_BLOCK,
                collector.deriveTerminationReason("FAILED", "SAFETY_POLICY_DENY", List.of()));
        assertEquals(TerminationReason.TOKEN_BUDGET,
                collector.deriveTerminationReason("FAILED", "BUDGET_EXHAUSTED", List.of()));
        assertEquals(TerminationReason.MAX_ROUNDS,
                collector.deriveTerminationReason("FAILED", "MAX_STEPS_REACHED", List.of()));
        assertEquals(TerminationReason.MODEL_ERROR,
                collector.deriveTerminationReason("FAILED", "MODEL_UPSTREAM_500", List.of()));
        assertEquals(TerminationReason.OTHER,
                collector.deriveTerminationReason("FAILED", "SOMETHING_NEW", List.of()));
    }

    @Test
    @DisplayName("FAILED 无 error_code 时回看工具错误类：5xx → TOOL_5XX")
    void failedReasonFallsBackToToolError() {
        ToolGroup upstream = new ToolGroup("t", 1, 1, 0, false, true, 50L, 50L);
        assertEquals(TerminationReason.TOOL_5XX,
                collector.deriveTerminationReason("FAILED", null, List.of(upstream)));
    }

    @Test
    @DisplayName("未知状态一律 OTHER —— 绝不返回 null 让调用方丢行")
    void unknownStateIsOther() {
        assertEquals(TerminationReason.OTHER,
                collector.deriveTerminationReason("RUNNING", null, List.of()));
    }

    // ── 未采集 = null ─────────────────────────────────────────────

    @Test
    @DisplayName("无任何计量与工具调用时，成本/轮次类字段一律 null（不是 0）")
    void absentFactsAreNullNotZero() {
        StubFacts stub = new StubFacts();
        stub.facts = new TraceFacts(
                new TraceFactPort.RunFacts("run-1", "SUCCEEDED", null, "trace-1",
                        AgentMetricPort.TraceIdSource.RUN_TRACE_ID,
                        Instant.now(), Instant.now(), null, null),
                new TraceFactPort.UsageFacts(null, null, null, null, null),
                List.of(),
                Set.of(),
                StepFacts.ABSENT);
        AgentMetricCollector c = new AgentMetricCollector(stub, metrics);

        Optional<String> traceId = c.collect("tenant-1", UUID.randomUUID());

        assertTrue(traceId.isPresent());
        TraceMetric m = metrics.last;
        assertNotNull(m);
        assertNull(m.inputTokens(), "无计量记录时 input_tokens 必须是 null 而不是 0");
        assertNull(m.outputTokens());
        assertNull(m.toolRounds(), "无工具调用时轮次必须是 null 而不是 0");
        assertNull(m.toolCallTotal());
        assertNull(m.toolCallInvalid());
        // 效果 / 反馈 / 风险三类本期无采集源，必须全 null
        assertNull(m.autoVerified());
        assertNull(m.sampled());
        assertNull(m.humanVerdict());
        assertNull(m.feedbackUp());
        assertNull(m.abandoned());
        assertNull(m.safetyBlockedCount());
    }

    @Test
    @DisplayName("未收敛（在途）Run 不采集 —— 计入会把「没跑完」算成「没成功」")
    void inFlightRunIsSkipped() {
        StubFacts stub = new StubFacts();
        stub.facts = new TraceFacts(
                new TraceFactPort.RunFacts("run-1", "RUNNING", null, "trace-1",
                        AgentMetricPort.TraceIdSource.RUN_TRACE_ID,
                        Instant.now(), null, null, null),
                new TraceFactPort.UsageFacts(null, null, null, null, null),
                List.of(),
                Set.of(),
                StepFacts.ABSENT);
        AgentMetricCollector c = new AgentMetricCollector(stub, metrics);
        assertTrue(c.collect("tenant-1", UUID.randomUUID()).isEmpty());
        assertNull(metrics.last);
    }

    // ── 行为指标口径 ──────────────────────────────────────────────

    @Test
    @DisplayName("重复调用：第 1 次正常，多出来的才算重复；≥3 次记为循环")
    void duplicateAndLoopCounting() {
        StubFacts stub = new StubFacts();
        // 同工具同参数调用 3 次 → duplicate = 2，loop = 1 组
        ToolGroup group = new ToolGroup("search", 3, 0, 0, false, false, 300L, 120L);
        stub.facts = new TraceFacts(
                new TraceFactPort.RunFacts("run-1", "SUCCEEDED", null, "trace-1",
                        AgentMetricPort.TraceIdSource.RUN_TRACE_ID,
                        Instant.now(), Instant.now(), null, null),
                new TraceFactPort.UsageFacts(100L, 50L, BigDecimal.ONE, "CNY", "m1"),
                List.of(group),
                Set.of("search"),
                StepFacts.ABSENT);
        AgentMetricCollector c = new AgentMetricCollector(stub, metrics);
        c.collect("tenant-1", UUID.randomUUID());

        assertEquals(3, metrics.last.toolCallTotal());
        assertEquals(2, metrics.last.toolCallDuplicate(),
                "重复数应为超出第一次的次数，而不是整组调用数");
        assertEquals(1, metrics.last.loopSuspectedCount());
        assertEquals(2, metrics.last.toolCallInvalid(), "无未知工具/参数非法时，无效调用 = 重复数");
    }

    @Test
    @DisplayName("模型幻觉出的工具（不在工具定义表）计入无效调用")
    void unknownToolCountsAsInvalid() {
        StubFacts stub = new StubFacts();
        ToolGroup ghost = new ToolGroup("not_a_real_tool", 2, 0, 0, false, false, 20L, 12L);
        stub.facts = new TraceFacts(
                new TraceFactPort.RunFacts("run-1", "FAILED", "MODEL_HALLUCINATION", "trace-1",
                        AgentMetricPort.TraceIdSource.RUN_TRACE_ID,
                        Instant.now(), Instant.now(), null, null),
                new TraceFactPort.UsageFacts(null, null, null, null, null),
                List.of(ghost),
                Set.of("search"),
                StepFacts.ABSENT);
        AgentMetricCollector c = new AgentMetricCollector(stub, metrics);
        c.collect("tenant-1", UUID.randomUUID());

        // 2 次全是未知工具（2）+ 重复（1）= 3
        assertEquals(3, metrics.last.toolCallInvalid());
        assertEquals(TerminationReason.MODEL_ERROR, metrics.last.terminationReason());
    }

    @Test
    @DisplayName("P7-2 真实口径：run_step 存在时 tool_rounds/think_ms_total 取自步骤，不再近似")
    void realStepFactsDriveToolRoundsAndThink() {
        StubFacts stub = new StubFacts();
        ToolGroup group = new ToolGroup("search", 3, 0, 0, false, false, 300L, 120L);
        // 2 个 TOOL 步骤（真实轮次）、THINK 合计 500ms、0 次追问
        StepFacts steps = new StepFacts(true, 500L, 2L, 0L, 4L, 2);
        stub.facts = new TraceFacts(
                new TraceFactPort.RunFacts("run-1", "SUCCEEDED", null, "trace-1",
                        AgentMetricPort.TraceIdSource.RUN_TRACE_ID,
                        Instant.now(), Instant.now(), null, null),
                new TraceFactPort.UsageFacts(100L, 50L, BigDecimal.ONE, "CNY", "m1"),
                List.of(group),
                Set.of("search"),
                steps);
        AgentMetricCollector c = new AgentMetricCollector(stub, metrics);
        c.collect("tenant-1", UUID.randomUUID());

        assertEquals(2, metrics.last.toolRounds(), "tool_rounds 应取 TOOL 步骤数，而非工具组数");
        assertEquals(500L, metrics.last.thinkMsTotal(), "think_ms_total 应取 THINK 步骤时长合计");
        assertEquals(0, metrics.last.clarificationCount(), "无追问路径时澄清数为 0（已采集）");
    }

    @Test
    @DisplayName("P7-2 未埋点（无 run_step）时 tool_rounds/think_ms_total 必须为 null（非退化近似）")
    void absentStepsYieldNullToolRounds() {
        StubFacts stub = new StubFacts();
        ToolGroup group = new ToolGroup("search", 3, 0, 0, false, false, 300L, 120L);
        stub.facts = new TraceFacts(
                new TraceFactPort.RunFacts("run-1", "SUCCEEDED", null, "trace-1",
                        AgentMetricPort.TraceIdSource.RUN_TRACE_ID,
                        Instant.now(), Instant.now(), null, null),
                new TraceFactPort.UsageFacts(100L, 50L, BigDecimal.ONE, "CNY", "m1"),
                List.of(group),
                Set.of("search"),
                StepFacts.ABSENT);
        AgentMetricCollector c = new AgentMetricCollector(stub, metrics);
        c.collect("tenant-1", UUID.randomUUID());

        assertNull(metrics.last.toolRounds(), "历史未埋点 Run 的 tool_rounds 必须 null，绝不退回近似");
        assertNull(metrics.last.thinkMsTotal());
    }

    @Test
    @DisplayName("超大 token 阈值判定")
    void oversizedThreshold() {
        assertFalse(collector.isOversized(10_000L, 5_000L));
        assertTrue(collector.isOversized(15_000L, 6_000L));
    }

    @Test
    @DisplayName("样本量不足 30 时不得判定为「足以判断」")
    void sampleSufficiency() {
        AgentMetricPort.Overview small = overview(12L);
        assertFalse(small.sampleSufficient(), "n=12 不足以支撑百分比结论");
        assertTrue(overview(30L).sampleSufficient());
    }

    private static AgentMetricPort.Overview overview(Long sampled) {
        return new AgentMetricPort.Overview(
                100L, 90L, 0.9, 1000.0, 1000L, 500L, BigDecimal.TEN, 0.1,
                0.01, 3.0, 0.02, 0.05, 2L,
                null, null, null, null,
                sampled, null, null, null, null,
                null, null, null, null,
                0L, 0L, 0L, Instant.now());
    }

    // ── 测试替身 ──────────────────────────────────────────────────

    private static final class StubFacts implements TraceFactPort {
        private TraceFacts facts;

        @Override
        public Optional<TraceFacts> load(String tenantId, UUID runId) {
            return Optional.ofNullable(facts);
        }

        @Override
        public Optional<String> resolveTraceId(String tenantId, UUID runId) {
            // 采集器本身不解析 trace_id（由 readRun 内部完成），
            // 故这里回落到已装载事实里的 run.traceId()。
            return facts == null
                    ? Optional.empty()
                    : Optional.ofNullable(facts.run().traceId());
        }
    }

    private static final class RecordingMetrics implements AgentMetricPort {
        private TraceMetric last;

        @Override
        public void upsert(TraceMetric metric) {
            this.last = metric;
        }

        @Override
        public Optional<TraceMetric> findByTraceId(String traceId) {
            return Optional.ofNullable(last);
        }

        @Override
        public void replaceToolCalls(String traceId, List<ToolCallRow> rows) {
            // 无断言需求
        }

        @Override
        public Overview overview(MetricQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ReasonCount> terminationDistribution(MetricQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LatencyPercentiles latency(MetricQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ToolMetric> toolBreakdown(MetricQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AnomalyTrace> anomalies(MetricQuery query, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordEvalSample(EvalSample sample) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordFeedback(FeedbackEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordRiskEvent(RiskEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordTaskOutcome(TaskOutcome outcome) {
            throw new UnsupportedOperationException();
        }
    }
}
