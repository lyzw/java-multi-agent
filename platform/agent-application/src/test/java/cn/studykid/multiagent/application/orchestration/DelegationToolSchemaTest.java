package cn.studykid.multiagent.application.orchestration;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.orchestration.ChildAgentRef;
import cn.studykid.multiagent.core.orchestration.DelegationFailurePolicy;
import cn.studykid.multiagent.core.orchestration.DelegationToolSchema;
import cn.studykid.multiagent.core.orchestration.OrchestrationMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 委派工具 Schema 与闸门口径的**跨模块一致性**测试（第二十批 · FR-035）。
 *
 * <p>⚠️ <b>本类存在的唯一理由：钉死「两处取值相同」</b>。
 * 「模型看得见的委派工具」与「闸门认得的委派调用」必须<b>同名</b> ——
 * 两处各写一遍必然漂移，而漂移的后果是：
 * 模型发起的委派被当成「**未登记的工具**」拒绝，
 * 表现为「多级 Agent 的委派在生产上莫名其妙不工作」。
 */
class DelegationToolSchemaTest {

    @Test
    @DisplayName("⚠️⚠️ 闸门认得的工具名 == 模型看得见的工具名")
    void toolNameMatchesGateConstant() {
        assertThat(DelegationExecutor.DELEGATION_TOOL_NAME)
                .as("⚠️⚠️ 闸门分支与模型 Schema 的工具名必须一致 —— "
                        + "不一致时模型发起的委派会被当成「未登记的工具」拒绝")
                .isEqualTo(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⚠️⚠️ Schema 声明的参数必须能被闸门的参数解析器接受")
    void schemaParametersAreAcceptedByGateParser() {
        // ⚠️⚠️ **协议两套语义**是这里最危险的一种不一致：
        //    模型按 Schema 合法发出的参数，闸门却拒收（PARAM_SCHEMA_FAIL），
        //    那么模型永远学不会正确调用 —— 每轮都在试，每轮都被拒。
        //
        // ⚠️ 本用例按**语义**断言：Schema 声明的字段集
        //    必须恰好等于参数解析器接受的字段集。
        var orchestration = new cn.studykid.multiagent.core.orchestration.AgentOrchestration(
                OrchestrationMode.SEQUENTIAL,
                List.of(ChildAgentRef.required("child-a", 1)),
                null, 2, 3, 30_000L, DelegationFailurePolicy.FAIL_FAST, null);
        var rendered = DelegationToolSchema.forOrchestration(orchestration)
                .orElseThrow().render();
        @SuppressWarnings("unchecked")
        var schema = (java.util.Map<String, Object>) rendered.get(
                DelegationToolSchema.TOOL_NAME);
        @SuppressWarnings("unchecked")
        var inputSchema = (java.util.Map<String, Object>) schema.get("inputSchema");
        @SuppressWarnings("unchecked")
        var properties = (java.util.Map<String, Object>) inputSchema.get("properties");

        // 闸门接受这些参数（多传即拒）
        assertThat(DelegationArguments.parse(
                        java.util.Map.of("targetAgentId", "child-a")))
                .isNotNull();
        assertThat(DelegationArguments.parse(
                        java.util.Map.of("targetAgentId", "child-a",
                                "input", java.util.Map.of("q", "x"))))
                .isNotNull();

        assertThat(properties.keySet())
                .as("⚠️⚠️ Schema 声明的字段集必须与闸门接受的字段集逐字一致")
                .containsExactlyInAnyOrder("targetAgentId", "input");

        // ⚠️ 反向：Schema 说不允许的字段，闸门也必须拒
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> DelegationArguments.parse(java.util.Map.of(
                        "targetAgentId", "child-a", "extra", "x"))))
                .as("⚠️ Schema 的 additionalProperties=false 必须与闸门的「多传即拒」一致")
                .isNotNull();
    }
}
