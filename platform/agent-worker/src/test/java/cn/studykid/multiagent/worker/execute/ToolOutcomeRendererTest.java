package cn.studykid.multiagent.worker.execute;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具结果回灌渲染器单测。
 *
 * <p><b>本类锁的是「成对」而不是「文案」</b>：2026-10-28 真实 MiniMax 端到端实测
 * 发现旧渲染只发 tool 消息、并把 {@code tool_call_id} 填成工具名，
 * 上一条 assistant 里并没有同名调用 —— 供应商以
 * {@code 400 invalid params, tool result's tool id(...) not found}
 * 拒收<b>整次请求</b>。症状极具误导性：工具在本地台账里是<b>执行成功</b>的，
 * Run 却在下一轮判成 MODEL_INVALID_REQUEST。
 */
@DisplayName("ToolOutcomeRenderer · 调用与结果的标识配对")
class ToolOutcomeRendererTest {

    private static final String TOOL = "get_student_profile";

    private static Map<String, ModelResponse.ToolCallRequest> calls(String callId) {
        return Map.of(callId, new ModelResponse.ToolCallRequest(
                TOOL, Map.of("studentId", "stu-1"), callId));
    }

    @Test
    @DisplayName("⛔ 首条 assistant 带 tool_calls，其后每个调用各有一条标识一致的 tool 消息")
    void emitsPairedAssistantAndToolMessages() {
        ToolInvocationGate.ToolInvocationResult result =
                new ToolInvocationGate.ToolInvocationResult();
        result.recordSucceeded(TOOL, "数学 78 分");

        List<ModelMessage> messages = ToolOutcomeRenderer.renderRound(calls("call_1"), result);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(ModelMessage.Role.ASSISTANT);
        assertThat(messages.get(0).toolCalls())
                .as("调用标识必须由 assistant 侧带回，否则 tool 消息无的放矢")
                .singleElement()
                .satisfies(echo -> {
                    assertThat(echo.callId()).isEqualTo("call_1");
                    assertThat(echo.toolName()).isEqualTo(TOOL);
                    assertThat(echo.argumentsJson()).contains("stu-1");
                });
        assertThat(messages.get(1).role()).isEqualTo(ModelMessage.Role.TOOL);
        assertThat(messages.get(1).toolCallId()).isEqualTo("call_1");
    }

    @Test
    @DisplayName("被拒 / 失败 / 成功无输出同样各占一条（缺一条就是对方的 400）")
    void everyCallGetsExactlyOneToolMessage() {
        ToolInvocationGate.ToolInvocationResult rejected =
                new ToolInvocationGate.ToolInvocationResult();
        rejected.recordRejected(TOOL, "Schema 校验失败");

        assertThat(ToolOutcomeRenderer.renderRound(calls("call_2"), rejected))
                .element(1)
                .satisfies(message -> {
                    assertThat(message.toolCallId()).isEqualTo("call_2");
                    assertThat(message.content())
                            .as("不可信边界文案不得因分支改变而丢失")
                            .contains(TOOL).contains("被拒绝");
                });

        ToolInvocationGate.ToolInvocationResult succeededSilent =
                new ToolInvocationGate.ToolInvocationResult();
        succeededSilent.recordSucceeded(TOOL);

        assertThat(ToolOutcomeRenderer.renderRound(calls("call_3"), succeededSilent))
                .element(1)
                .satisfies(message -> assertThat(message.toolCallId()).isEqualTo("call_3"));
    }

    @Test
    @DisplayName("供应商未下发 callId 时补稳定占位，仍保持两侧一致")
    void synthesisesCallIdWhenProviderOmitsIt() {
        ToolInvocationGate.ToolInvocationResult result =
                new ToolInvocationGate.ToolInvocationResult();
        result.recordSucceeded(TOOL, "输出");
        Map<String, ModelResponse.ToolCallRequest> noId = Map.of(TOOL,
                new ModelResponse.ToolCallRequest(TOOL, Map.of(), null));

        List<ModelMessage> messages = ToolOutcomeRenderer.renderRound(noId, result);

        String echoId = messages.get(0).toolCalls().get(0).callId();
        assertThat(echoId).isNotBlank();
        assertThat(messages.get(1).toolCallId())
                .as("两侧标识必须一模一样")
                .isEqualTo(echoId);
    }
}
