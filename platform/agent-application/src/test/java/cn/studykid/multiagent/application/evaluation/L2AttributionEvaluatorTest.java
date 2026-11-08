package cn.studykid.multiagent.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.metrics.AgentMetricPort;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.EvalSample;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.MessageView;

/**
 * L2 内容级归因判分（§13 第 3 项）fail-closed 行为单测。
 *
 * <p>⚠️ 本类用 test-double 的参考源 / 判定器，验证<b>编排门禁</b>而非真实判分：
 *   真实判分源（题库标准解析）与 LLM 判定器仍待产品确认，否则测的是「猜的」。
 *   这里只证 —— <b>任一条件缺失时 L2 不评级、不写 attribution_correct</b>，
 *   以及全部满足时才写出真布尔。
 */
@DisplayName("P3 · L2 内容级归因判分（§13 第 3 项）")
class L2AttributionEvaluatorTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String RUN = "22222222-2222-2222-2222-222222222222";
    private static final String TRACE = "trace-l2-0001";
    private static final String SCENARIO = "数学:一元二次方程";

    private TraceReplayPort replay;
    private AgentMetricPort metrics;
    private ReferenceAnswerSource refWithAnswer;
    private ReferenceAnswerSource refEmpty;
    private AttributionJudge judgeTrue;
    private AttributionJudge judgeUnsure;

    @BeforeEach
    void setUp() {
        replay = mock(TraceReplayPort.class);
        metrics = mock(AgentMetricPort.class);
        refWithAnswer = mock(ReferenceAnswerSource.class);
        when(refWithAnswer.resolve(anyString(), anyString()))
                .thenReturn(Optional.of(new ReferenceAnswerSource.ReferenceAnswer(
                        SCENARIO, "求方程根", "标准解析：用求根公式")));
        refEmpty = new NoReferenceAnswerSource();
        judgeTrue = mock(AttributionJudge.class);
        when(judgeTrue.judgeCorrect(any())).thenReturn(Optional.of(true));
        judgeUnsure = mock(AttributionJudge.class);
        when(judgeUnsure.judgeCorrect(any())).thenReturn(Optional.empty());
    }

    private static List<MessageView> transcript() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("text", "求方程根");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("text", "根据韦达定理，根为 x=1 或 x=2");
        a.put("toolCalls", List.of());
        a.put("finishReason", "STOP");
        return List.of(
                new MessageView(1, "USER", null, q, null, null, null, null, null,
                        Instant.parse("2026-11-08T10:00:00Z")),
                new MessageView(2, "ASSISTANT", null, a, null, null, null, null, null,
                        Instant.parse("2026-11-08T10:00:01Z")));
    }

    private L2AttributionEvaluator enabled(ReferenceAnswerSource ref, AttributionJudge judge) {
        return new L2AttributionEvaluator(ref, judge, replay, metrics, true, true, 1.0);
    }

    @Test
    @DisplayName("L2-1 ✅ 全条件满足：写出 attribution_correct=true 且不伪造")
    void gradesWhenAllConditionsMet() {
        when(replay.messages(TENANT, RUN)).thenReturn(transcript());
        when(judgeTrue.judgeCorrect(any())).thenReturn(Optional.of(true));

        var r = enabled(refWithAnswer, judgeTrue).evaluate(TENANT, RUN, TRACE, SCENARIO);

        assertThat(r.outcome()).isEqualTo(L2AttributionEvaluator.Outcome.GRADED);
        assertThat(r.attributionCorrect()).isTrue();
        var cap = org.mockito.ArgumentCaptor.forClass(EvalSample.class);
        verify(metrics).recordEvalSample(cap.capture());
        assertThat(cap.getValue().verdict()).isEqualTo(L2AttributionEvaluator.VERDICT);
        assertThat(cap.getValue().attributionCorrect()).isTrue();
    }

    @Test
    @DisplayName("L2-2 ✅ 判定器判 false 也如实写出（不是永远 true）")
    void gradesFalseWhenJudgeSaysFalse() {
        when(replay.messages(TENANT, RUN)).thenReturn(transcript());
        when(judgeTrue.judgeCorrect(any())).thenReturn(Optional.of(false));

        var r = enabled(refWithAnswer, judgeTrue).evaluate(TENANT, RUN, TRACE, SCENARIO);

        assertThat(r.outcome()).isEqualTo(L2AttributionEvaluator.Outcome.GRADED);
        assertThat(r.attributionCorrect()).isFalse();
    }

    @Test
    @DisplayName("L2-3 ⚠️ 无参考源（默认 NoReferenceAnswerSource）：明确不评级，绝不编造")
    void ungradedWhenNoReference() {
        when(replay.messages(TENANT, RUN)).thenReturn(transcript());

        var r = enabled(refEmpty, judgeTrue).evaluate(TENANT, RUN, TRACE, SCENARIO);

        assertThat(r.outcome()).isEqualTo(L2AttributionEvaluator.Outcome.UNGRADED_NO_REFERENCE);
        assertThat(r.attributionCorrect()).isNull();
        verify(metrics, never()).recordEvalSample(any());
    }

    @Test
    @DisplayName("L2-4 ⚠️ 无判定器 bean（AttributionJudge 未装配）：明确不评级")
    void ungradedWhenNoJudge() {
        when(replay.messages(TENANT, RUN)).thenReturn(transcript());

        var r = new L2AttributionEvaluator(refWithAnswer, null, replay, metrics, true, true, 1.0)
                .evaluate(TENANT, RUN, TRACE, SCENARIO);

        assertThat(r.outcome()).isEqualTo(L2AttributionEvaluator.Outcome.UNGRADED_NO_JUDGE);
        verify(metrics, never()).recordEvalSample(any());
    }

    @Test
    @DisplayName("L2-5 ⚠️ 开关未开（llm-judge / reference-source 任一）：不评级")
    void ungradedWhenDisabled() {
        when(replay.messages(TENANT, RUN)).thenReturn(transcript());
        L2AttributionEvaluator off = new L2AttributionEvaluator(
                refWithAnswer, judgeTrue, replay, metrics, false, true, 1.0);
        L2AttributionEvaluator offBoth = new L2AttributionEvaluator(
                refWithAnswer, judgeTrue, replay, metrics, true, false, 1.0);

        assertThat(off.evaluate(TENANT, RUN, TRACE, SCENARIO).outcome())
                .isEqualTo(L2AttributionEvaluator.Outcome.UNGRADED_DISABLED);
        assertThat(offBoth.evaluate(TENANT, RUN, TRACE, SCENARIO).outcome())
                .isEqualTo(L2AttributionEvaluator.Outcome.UNGRADED_DISABLED);
        verify(metrics, never()).recordEvalSample(any());
    }

    @Test
    @DisplayName("L2-6 ⚠️ 回放无 USER / 无 ASSISTANT：事实不足，不评级")
    void ungradedWhenNoFacts() {
        when(replay.messages(TENANT, RUN)).thenReturn(List.of());

        var r = enabled(refWithAnswer, judgeTrue).evaluate(TENANT, RUN, TRACE, SCENARIO);

        assertThat(r.outcome()).isEqualTo(L2AttributionEvaluator.Outcome.UNGRADED_NO_FACTS);
        verify(metrics, never()).recordEvalSample(any());
    }

    @Test
    @DisplayName("L2-7 ⚠️ 抽样未命中：跳过但不算未评级，也不写")
    void skippedWhenSamplingMisses() {
        when(replay.messages(TENANT, RUN)).thenReturn(transcript());
        // sampleRate=0 → shouldSample 恒 false
        L2AttributionEvaluator sampled = new L2AttributionEvaluator(
                refWithAnswer, judgeTrue, replay, metrics, true, true, 0.0);

        var r = sampled.evaluate(TENANT, RUN, TRACE, SCENARIO);

        assertThat(r.outcome()).isEqualTo(L2AttributionEvaluator.Outcome.SKIPPED_SAMPLING);
        verify(metrics, never()).recordEvalSample(any());
    }

    @Test
    @DisplayName("L2-8 ⚠️ 判定器拿不准（返回 empty）：记为待人工，不二选一硬猜")
    void unsureIsNotGraded() {
        when(replay.messages(TENANT, RUN)).thenReturn(transcript());

        var r = enabled(refWithAnswer, judgeUnsure).evaluate(TENANT, RUN, TRACE, SCENARIO);

        assertThat(r.outcome()).isEqualTo(L2AttributionEvaluator.Outcome.UNGRADED_JUDGE_UNSURE);
        verify(metrics, never()).recordEvalSample(any());
    }
}
