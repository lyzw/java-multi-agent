package cn.studykid.multiagent.application.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.MetricQuery;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.Overview;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.ReasonCount;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.TerminationReason;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.ToolMetric;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Priority;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.ReviewTaskSpec;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.Source;

/**
 * 比例类质量告警（§9.2）的行为守卫。
 *
 * <p><b>⚠️ 守的核心不是「阈值能触发」，而是「样本不足时绝不告警」</b>：
 * 用 2 条样本算出的「成功率 50%」是噪声，若据此建 ALERT 任务，
 * reviewer 会被噪声训练到忽略队列 —— 这比不告警更糟。
 * 本仓的看板已有 {@code n<30 不显示百分比} 纪律，告警侧必须同口径。
 */
class QualityAlertEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    private final AgentMetricPort metrics = mock(AgentMetricPort.class);
    private final ReviewTaskPort reviewTasks = mock(ReviewTaskPort.class);

    @BeforeEach
    void setUp() {
        when(reviewTasks.create(any())).thenReturn("task-1");
        when(metrics.terminationDistribution(any())).thenReturn(List.of());
        when(metrics.toolBreakdown(any())).thenReturn(List.of());
    }

    private QualityAlertEvaluator evaluator() {
        return new QualityAlertEvaluator(metrics, reviewTasks);
    }

    /**
     * 造 Overview。
     *
     * @param n                总样本数
     * @param successRate      链路成功率
     * @param sampled          人工抽样数（幻觉率的分母）
     * @param hallucinationRate 幻觉率
     */
    private static Overview ov(long n, Double successRate, Long sampled,
                               Double hallucinationRate) {
        return new Overview(
                n, (long) (n * (successRate == null ? 0.9 : successRate)), successRate,
                1000.0, 1000L, 500L, BigDecimal.TEN, 0.1,
                0.01, 3.0, 0.02, 0.05, 2L,
                null, null, null, null,
                sampled, null, null, hallucinationRate, null,
                null, null, null,
                null, null, null, null,
                0L, 0L, 0L, NOW);
    }

    @Test
    @DisplayName("⚠️⚠️ 当前窗口 n<30：所有比例都不判，且如实记「样本不足」")
    void insufficientSampleNeverAlerts() {
        when(metrics.overview(any())).thenReturn(ov(2, 0.5, 2L, 0.5));

        QualityAlertEvaluator.Result r = evaluator().evaluateOnce(NOW);

        assertThat(r.fired()).isZero();
        assertThat(r.insufficientSample()).isEqualTo(1);
        assertThat(r.reasons()).anySatisfy(s -> assertThat(s).contains("样本不足"));
        verify(reviewTasks, never()).create(any());
    }

    @Test
    @DisplayName("✅ SLA-3 错误率 > 10% 建 HIGH/ALERT，且注明 n")
    void errorRateFiresHighAlert() {
        when(metrics.overview(any())).thenReturn(ov(100, 0.9, 0L, null));
        when(metrics.terminationDistribution(any())).thenReturn(List.of(
                new ReasonCount(TerminationReason.MODEL_ERROR, 15),
                new ReasonCount(TerminationReason.TOOL_5XX, 5),
                new ReasonCount(TerminationReason.COMPLETED, 80)));
        // 基线窗样本不足 ⇒ SLA-2 不判，但 SLA-3 不受影响
        when(metrics.overview(any())).thenReturn(ov(100, 0.9, 0L, null));

        QualityAlertEvaluator.Result r = evaluator().evaluateOnce(NOW);

        assertThat(r.fired()).isGreaterThanOrEqualTo(1);
        ArgumentCaptor<ReviewTaskSpec> cap = ArgumentCaptor.forClass(ReviewTaskSpec.class);
        verify(reviewTasks, org.mockito.Mockito.atLeastOnce()).create(cap.capture());
        assertThat(cap.getAllValues()).anySatisfy(s -> {
            assertThat(s.source()).isEqualTo(Source.ALERT);
            assertThat(s.priority()).isEqualTo(Priority.HIGH);
            assertThat(s.note()).contains("SLA-3").contains("n=100");
        });
    }

    @Test
    @DisplayName("✅ SLA-7 单工具失败率 > 20% 且调用数足够时建 NORMAL/ALERT")
    void toolFailureFiresNormalAlert() {
        when(metrics.overview(any())).thenReturn(ov(100, 0.9, 0L, null));
        when(metrics.terminationDistribution(any())).thenReturn(List.of());
        when(metrics.toolBreakdown(any())).thenReturn(List.of(
                new ToolMetric("get_student_profile", 50L, 20L, 0.4, 100.0, 200.0)));

        QualityAlertEvaluator.Result r = evaluator().evaluateOnce(NOW);

        assertThat(r.fired()).isGreaterThanOrEqualTo(1);
        ArgumentCaptor<ReviewTaskSpec> cap = ArgumentCaptor.forClass(ReviewTaskSpec.class);
        verify(reviewTasks, org.mockito.Mockito.atLeastOnce()).create(cap.capture());
        assertThat(cap.getAllValues()).anySatisfy(s -> {
            assertThat(s.source()).isEqualTo(Source.ALERT);
            assertThat(s.priority()).isEqualTo(Priority.NORMAL);
            assertThat(s.note()).contains("SLA-7").contains("get_student_profile");
        });
    }

    @Test
    @DisplayName("⚠️ 工具调用数 < 30 时即便失败率 100% 也不告警（噪声不是指标）")
    void smallToolSampleNeverAlerts() {
        when(metrics.overview(any())).thenReturn(ov(100, 0.9, 0L, null));
        when(metrics.terminationDistribution(any())).thenReturn(List.of());
        when(metrics.toolBreakdown(any())).thenReturn(List.of(
                new ToolMetric("rare_tool", 2L, 2L, 1.0, 100.0, 200.0)));

        QualityAlertEvaluator.Result r = evaluator().evaluateOnce(NOW);

        assertThat(r.fired()).isZero();
        verify(reviewTasks, never()).create(any());
    }

    @Test
    @DisplayName("⚠️ 幻觉率 > 5% 但人工抽样 n<30 → 不告警（分母纪律）")
    void hallucinationRequiresSufficientHumanSamples() {
        when(metrics.overview(any())).thenReturn(ov(100, 0.9, 5L, 0.8));

        QualityAlertEvaluator.Result r = evaluator().evaluateOnce(NOW);

        assertThat(r.fired()).isZero();
        verify(reviewTasks, never()).create(any());
    }

    @Test
    @DisplayName("✅ 幻觉率 > 5% 且人工抽样 n≥30 → 建 HIGH/ALERT")
    void hallucinationFiresWithSufficientSamples() {
        when(metrics.overview(any())).thenReturn(ov(100, 0.9, 40L, 0.1));

        QualityAlertEvaluator.Result r = evaluator().evaluateOnce(NOW);

        assertThat(r.fired()).isGreaterThanOrEqualTo(1);
        ArgumentCaptor<ReviewTaskSpec> cap = ArgumentCaptor.forClass(ReviewTaskSpec.class);
        verify(reviewTasks, org.mockito.Mockito.atLeastOnce()).create(cap.capture());
        assertThat(cap.getAllValues()).anySatisfy(s ->
                assertThat(s.note()).contains("内容质量").contains("n=40"));
    }

    @Test
    @DisplayName("⚠️ SLA-2 历史基线样本不足 → 明确记「不判」，绝不硬编码 3σ")
    void successRateRequiresHistoricalBaseline() {
        // 当前窗 n=100；基线窗（第二次 overview 调用）返回 n=2
        when(metrics.overview(any()))
                .thenReturn(ov(100, 0.5, 0L, null))
                .thenReturn(ov(2, 0.99, 0L, null));

        QualityAlertEvaluator.Result r = evaluator().evaluateOnce(NOW);

        assertThat(r.reasons()).anySatisfy(s -> assertThat(s)
                .contains("SLA-2").contains("基线样本不足").contains("不判"));
    }
}
