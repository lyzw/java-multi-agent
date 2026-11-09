package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 工具 operationId 记录契约（2026-11-09 补 · 详细设计 §8.3 TOOL 下钻第一环）。
 *
 * <p><b>⚠️ 守的是什么</b>：{@code run_message.tool_operation_id} 此前恒为 NULL，
 * 审查者从对话回放无法跳到工具调用的原始参数/返回 —— 而这一列的值来自
 * {@code ToolInvocationResult.operationIdOf(tool)}。
 * 若这里记错工具名、丢 null、或返回了别的工具的值，下钻端点必然 404，
 * 且症状与「工具调用已被清理」无法区分。
 */
@DisplayName("工具调用 operationId 记录契约")
class ToolInvocationOperationIdTest {

    private static final String OP_1 = "11111111-1111-1111-1111-111111111111";
    private static final String OP_2 = "22222222-2222-2222-2222-222222222222";

    @Test
    @DisplayName("✅ 按工具名精确取回自己的 operationId（不串台）")
    void returnsOwnOperationId() {
        ToolInvocationGate.ToolInvocationResult r = new ToolInvocationGate.ToolInvocationResult();

        r.recordOperationId("get_student_profile", OP_1);
        r.recordOperationId("record_weakness", OP_2);

        assertThat(r.operationIdOf("get_student_profile")).contains(OP_1);
        assertThat(r.operationIdOf("record_weakness")).contains(OP_2);
        assertThat(r.operationIds()).containsEntry("get_student_profile", OP_1)
                .containsEntry("record_weakness", OP_2);
    }

    @Test
    @DisplayName("⚠️ 未记录 / null / blank 一律返回空，绝不猜一个 id")
    void blankInputsAreIgnored() {
        ToolInvocationGate.ToolInvocationResult r = new ToolInvocationGate.ToolInvocationResult();

        r.recordOperationId("tool-a", null);
        r.recordOperationId("tool-b", "   ");
        r.recordOperationId(null, OP_1);

        assertThat(r.operationIdOf("tool-a")).isEmpty();
        assertThat(r.operationIdOf("tool-b")).isEmpty();
        assertThat(r.operationIds()).isEmpty();
    }

    @Test
    @DisplayName("⚠️ 不可变副本：外部改不动内部映射（回放读到的 id 是事实源）")
    void operationIdsIsDefensiveCopy() {
        ToolInvocationGate.ToolInvocationResult r = new ToolInvocationGate.ToolInvocationResult();
        r.recordOperationId("tool-a", OP_1);

        assertThat(r.operationIds()).containsEntry("tool-a", OP_1);
        assertThat(r.operationIdOf("unknown-tool")).isEmpty();
    }
}
