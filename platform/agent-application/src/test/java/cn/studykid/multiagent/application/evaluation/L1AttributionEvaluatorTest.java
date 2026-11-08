package cn.studykid.multiagent.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.persistence.TraceReplayPort;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.MessageView;

/**
 * L1 规则判分（P3）行为单测。
 *
 * <p>⚠️ 本类刻意不验「答案对不对」——没有参考答案 / 题库标准解析时
 * 那属于编造。这里验的是<b>链路完整性信号的判别力</b>，
 * 以及「事实不足时绝不硬判」的 fail-closed 边界。
 */
@DisplayName("P3 · L1 链路完整性判分")
class L1AttributionEvaluatorTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String RUN = "22222222-2222-2222-2222-222222222222";

    private TraceReplayPort replay;
    private L1AttributionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        replay = mock(TraceReplayPort.class);
        evaluator = new L1AttributionEvaluator(replay);
    }

    private void given(List<MessageView> messages) {
        when(replay.messages(anyString(), anyString())).thenReturn(messages);
    }

    @Test
    @DisplayName("L-1a ⚠️⚠️ 无回放消息时不得硬判（否则把没埋点算成判过了）")
    void noMessagesReturnsEmpty() {
        given(List.of());
        assertThat(evaluator.judge(TENANT, RUN)).isEmpty();
    }

    @Test
    @DisplayName("L-1b 只有 ASSISTANT 没 USER 时不判（对话不完整）")
    void noUserReturnsEmpty() {
        given(List.of(msg(1, "ASSISTANT", assistant("这是一段回答", "STOP"))));
        assertThat(evaluator.judge(TENANT, RUN)).isEmpty();
    }

    @Test
    @DisplayName("L-2a 有 USER 但没有任何 ASSISTANT → FAIL")
    void noAssistantFails() {
        given(List.of(msg(1, "USER", Map.of("text", "解方程"))));
        var v = evaluator.judge(TENANT, RUN).orElseThrow();
        assertThat(v.outcome()).isEqualTo(L1AttributionEvaluator.Outcome.FAIL);
        assertThat(v.signal()).isEqualTo("NO_ASSISTANT_OUTPUT");
        assertThat(v.linkComplete()).isFalse();
    }

    @Test
    @DisplayName("L-2b ASSISTANT 空文本且无工具调用 → FAIL")
    void emptyAssistantFails() {
        given(List.of(msg(1, "USER", Map.of("text", "解方程")),
                msg(2, "ASSISTANT", assistant("", "STOP"))));
        var v = evaluator.judge(TENANT, RUN).orElseThrow();
        assertThat(v.signal()).isEqualTo("EMPTY_ASSISTANT_OUTPUT");
    }

    @Test
    @DisplayName("L-3 ⚠️ finishReason=LENGTH（真实实跑抓到过）必须判 PARTIAL，不能算通过")
    void truncatedIsPartial() {
        given(List.of(msg(1, "USER", Map.of("text", "解方程")),
                msg(2, "ASSISTANT", assistant("解：x=2 或 x=3，验证方法是……", "LENGTH"))));
        var v = evaluator.judge(TENANT, RUN).orElseThrow();
        assertThat(v.outcome()).isEqualTo(L1AttributionEvaluator.Outcome.PARTIAL);
        assertThat(v.signal()).isEqualTo("TRUNCATED_OUTPUT");
        assertThat(v.linkComplete()).isFalse();
    }

    @Test
    @DisplayName("L-4 TOOL 消息 state != SUCCEEDED → FAIL")
    void toolFailureFails() {
        given(List.of(msg(1, "USER", Map.of("text", "解方程")),
                msg(2, "TOOL", tool("get_student_profile", "FAILED")),
                msg(3, "ASSISTANT", assistant("我查不到资料", "STOP"))));
        var v = evaluator.judge(TENANT, RUN).orElseThrow();
        assertThat(v.signal()).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(v.linkComplete()).isFalse();
    }

    @Test
    @DisplayName("L-5 完整链路 → PASS（只表示链路完整，不表示答案正确）")
    void completeTracePasses() {
        given(List.of(msg(1, "USER", Map.of("text", "解方程")),
                msg(2, "TOOL", tool("get_student_profile", "SUCCEEDED")),
                msg(3, "ASSISTANT", assistant("解：x=2 或 x=3", "STOP"))));
        var v = evaluator.judge(TENANT, RUN).orElseThrow();
        assertThat(v.outcome()).isEqualTo(L1AttributionEvaluator.Outcome.PASS);
        assertThat(v.signal()).isEqualTo("COMPLETE_TRACE");
        assertThat(v.linkComplete()).isTrue();
    }

    @Test
    @DisplayName("L-6 判分确定性 + 证据摘要不含对话原文")
    void deterministicAndNoRawTextInDigest() {
        List<MessageView> a = List.of(msg(1, "USER", Map.of("text", "甲")),
                msg(2, "ASSISTANT", assistant("回答甲", "STOP")));
        List<MessageView> b = List.of(msg(1, "USER", Map.of("text", "乙")),
                msg(2, "ASSISTANT", assistant("回答乙", "STOP")));
        given(a);
        var first = evaluator.judge(TENANT, RUN).orElseThrow();
        var second = evaluator.judge(TENANT, RUN).orElseThrow();
        assertThat(first).isEqualTo(second);

        given(b);
        var other = evaluator.judge(TENANT, RUN).orElseThrow();
        assertThat(other.evidenceDigest())
                .as("证据摘要只由结构性事实派生，不同对话文本不应改变 digest")
                .isEqualTo(first.evidenceDigest());
        assertThat(first.evidenceDigest()).hasSize(64).doesNotContain("回答甲");
    }

    private static Map<String, Object> assistant(String text, String finishReason) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("text", text);
        c.put("toolCalls", List.of());
        c.put("finishReason", finishReason);
        return c;
    }

    private static Map<String, Object> tool(String name, String state) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("toolName", name);
        c.put("state", state);
        c.put("output", "");
        c.put("reason", "");
        return c;
    }

    private static MessageView msg(long seq, String role, Map<String, Object> content) {
        return new MessageView(seq, role, null, content, null, null, null, null, null,
                Instant.parse("2026-11-08T10:00:00Z"));
    }
}
