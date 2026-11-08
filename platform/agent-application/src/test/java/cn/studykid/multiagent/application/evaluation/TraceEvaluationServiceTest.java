package cn.studykid.multiagent.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.TaskOutcome;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort;
import cn.studykid.multiagent.ports.persistence.ReviewTaskPort.ReviewTaskSpec;

/** 评估双轨编排（P3）行为单测：覆盖率回写 + 分层抽样 + fail-closed 边界。 */
@DisplayName("P3 · 评估双轨编排")
class TraceEvaluationServiceTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String RUN = "22222222-2222-2222-2222-222222222222";
    private static final String TRACE = "trace-eval-0001";

    private final L1AttributionEvaluator l1 = mock(L1AttributionEvaluator.class);
    private final L2AttributionEvaluator l2 = mock(L2AttributionEvaluator.class);
    private final AgentMetricPort metrics = mock(AgentMetricPort.class);
    private final ReviewTaskPort tasks = mock(ReviewTaskPort.class);

    private TraceEvaluationService service(boolean enabled, double sampleRate) {
        return new TraceEvaluationService(l1, l2, metrics, tasks, enabled, sampleRate);
    }

    private static L1AttributionEvaluator.Verdict pass() {
        return new L1AttributionEvaluator.Verdict(
                L1AttributionEvaluator.Outcome.PASS, true, "COMPLETE_TRACE", "digest-1");
    }

    @Test
    @DisplayName("E-1 ⚠️ L1 有结论必须回写 TaskOutcome（否则 auto_checked 覆盖率恒为 0）")
    void writesTaskOutcome() {
        when(l1.judge(TENANT, RUN)).thenReturn(Optional.of(pass()));
        when(tasks.hasOpenTask(TRACE)).thenReturn(false);

        service(true, 0).evaluate(TENANT, RUN, TRACE, null);

        ArgumentCaptor<TaskOutcome> cap = ArgumentCaptor.forClass(TaskOutcome.class);
        verify(metrics).recordTaskOutcome(cap.capture());
        assertThat(cap.getValue().checkerCode()).isEqualTo(L1AttributionEvaluator.CHECKER_CODE);
        assertThat(cap.getValue().verified()).isTrue();
        assertThat(cap.getValue().traceId()).isEqualTo(TRACE);
        // 编排已接 L2（§13 第 3 项），但默认 fail-closed：L2 被调用但不写（无参考源/判定器）
        verify(l2).evaluate(TENANT, RUN, TRACE, null);
    }

    @Test
    @DisplayName("E-2 ⚠️ 事实不足时绝不回写 TaskOutcome，只建 L1_GAP 人审任务")
    void gapDoesNotFakeCoverage() {
        when(l1.judge(TENANT, RUN)).thenReturn(Optional.empty());
        when(tasks.hasOpenTask(TRACE)).thenReturn(false);
        when(tasks.create(any())).thenReturn("task-gap");

        var result = service(true, 0).evaluate(TENANT, RUN, TRACE, null);

        assertThat(result).contains("task-gap");
        verify(metrics, never()).recordTaskOutcome(any());
        ArgumentCaptor<ReviewTaskSpec> cap = ArgumentCaptor.forClass(ReviewTaskSpec.class);
        verify(tasks).create(cap.capture());
        assertThat(cap.getValue().source()).isEqualTo(ReviewTaskPort.Source.L1_GAP);
        assertThat(cap.getValue().priority()).isEqualTo(ReviewTaskPort.Priority.HIGH);
    }

    @Test
    @DisplayName("E-3 抽样率 100% 时必须产出 SAMPLE_RANDOM 黄金基线任务")
    void samplesGoldenBaseline() {
        when(l1.judge(TENANT, RUN)).thenReturn(Optional.of(pass()));
        when(tasks.hasOpenTask(TRACE)).thenReturn(false);
        when(tasks.create(any())).thenReturn("task-sample");

        var result = service(true, 1.0).evaluate(TENANT, RUN, TRACE, null);

        assertThat(result).contains("task-sample");
        ArgumentCaptor<ReviewTaskSpec> cap = ArgumentCaptor.forClass(ReviewTaskSpec.class);
        verify(tasks).create(cap.capture());
        assertThat(cap.getValue().source()).isEqualTo(ReviewTaskPort.Source.SAMPLE_RANDOM);
    }

    @Test
    @DisplayName("E-4 抽样率 0 时不建随机任务（但 L1 覆盖率照样回写）")
    void zeroSampleRateStillWritesL1() {
        when(l1.judge(TENANT, RUN)).thenReturn(Optional.of(pass()));

        var result = service(true, 0).evaluate(TENANT, RUN, TRACE, null);

        assertThat(result).isEmpty();
        verify(metrics).recordTaskOutcome(any());
        verify(tasks, never()).create(any());
    }

    @Test
    @DisplayName("E-5 开关关闭时整条评估链不触发")
    void disabledDoesNothing() {
        service(false, 1.0).evaluate(TENANT, RUN, TRACE, null);
        verify(metrics, never()).recordTaskOutcome(any());
        verify(tasks, never()).create(any());
        verify(tasks, never()).hasOpenTask(anyString());
    }

    @Test
    @DisplayName("E-6 已有未终结任务时不重复排队")
    void deduplicates() {
        when(l1.judge(TENANT, RUN)).thenReturn(Optional.empty());
        when(tasks.hasOpenTask(TRACE)).thenReturn(true);

        assertThat(service(true, 1.0).evaluate(TENANT, RUN, TRACE, null)).isEmpty();
        verify(tasks, never()).create(any());
    }

    @Test
    @DisplayName("E-7 抽样是确定性的（同一 traceId 重放不产生新任务）")
    void samplingIsDeterministic() {
        var s = service(true, 0.05);
        for (int i = 0; i < 20; i++) {
            assertThat(s.shouldSample(TRACE)).isEqualTo(s.shouldSample(TRACE));
        }
        assertThat(s.shouldSample(TRACE)).isEqualTo(s.shouldSample(TRACE));
    }

    @Test
    @DisplayName("E-8 ⚠️ L1 读失败时既不回写也不建任务（不把失败算成判过了）")
    void l1FailureIsFailSoft() {
        when(l1.judge(TENANT, RUN)).thenThrow(new IllegalStateException("回放读失败"));

        assertThat(service(true, 1.0).evaluate(TENANT, RUN, TRACE, null)).isEmpty();
        verify(metrics, never()).recordTaskOutcome(any());
        verify(tasks, never()).create(any());
    }
}
