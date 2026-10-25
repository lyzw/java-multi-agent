package cn.studykid.multiagent.core.orchestration;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 委派工具 Schema 的形态测试（多级 Agent v1.1 · 第二十批 · FR-035）。
 *
 * <p>⚠️ <b>本类验证的是「模型看得见的东西」</b>：委派工具在修复前
 * <b>从未进入任何模型的工具表</b> → 运行期模型<b>物理上不可能</b>发起委派
 * （守卫、预算、账本、契约全都齐备，却没有触发点）。
 *
 * <p>⚠️ <b>Schema 不是授权</b>：它只<b>提示</b>模型可以委派给谁；
 * 真正的授权判定在闸门（{@code DelegationGuard}）。
 * 两者都成立才算合法 —— <b>委派关系 ≠ 授权关系</b>。
 */
class DelegationToolSchemaTest {

    private static AgentOrchestration sequential(String... childAgentIds) {
        List<ChildAgentRef> refs = new java.util.ArrayList<>();
        int version = 1;
        for (String id : childAgentIds) {
            refs.add(ChildAgentRef.required(id, version++));
        }
        return new AgentOrchestration(OrchestrationMode.SEQUENTIAL, refs, null,
                2, 3, 30_000L, DelegationFailurePolicy.FAIL_FAST, null);
    }

    @Test
    @DisplayName("⚠️⚠️ 有子声明的 Agent → 委派工具出现在模型可见的工具表里")
    void delegationToolAppearsForAgentWithChildren() {
        var schema = DelegationToolSchema.forOrchestration(sequential("child-a", "child-b"));

        assertThat(schema).isPresent();
        Map<String, Object> rendered = schema.orElseThrow().render();
        assertThat(rendered)
                .as("⚠️⚠️ 模型必须看得见委派工具 —— 否则运行期不可能发起委派")
                .containsKey(DelegationToolSchema.TOOL_NAME);
    }

    @Test
    @DisplayName("⛔ LEAF Agent → 不给委派工具（无委派能力就别发委派枪）")
    void leafAgentGetsNoDelegationTool() {
        // ⚠️ 编排声明缺省即 LEAF（MA-T01）——
        //    给没有委派能力的 Agent 发委派工具，
        //    等于让模型反复尝试必然被拒的调用（白烧模型调用）。
        assertThat(DelegationToolSchema.forOrchestration(null)).isEmpty();
        assertThat(DelegationToolSchema.forOrchestration(AgentOrchestration.leaf())).isEmpty();
    }

    @Test
    @DisplayName("⚠️ 目标必须枚举在自身声明的子 Agent 内（不给模型越界试探的机会）")
    void targetsAreEnumeratedFromOwnDeclaration() {
        var schema = DelegationToolSchema.forOrchestration(sequential("child-a", "child-b"))
                .orElseThrow();

        assertThat(schema.allowedTargets())
                .as("⚠️ 目标只能来自该 Agent 自己的声明")
                .containsExactly("child-a", "child-b");
    }

    @Test
    @DisplayName("⚠️⚠️ 参数口径必须与闸门一致：只允许 targetAgentId + input，多传字段拒绝")
    void parameterContractMatchesGate() {
        Map<String, Object> rendered = DelegationToolSchema
                .forOrchestration(sequential("child-a")).orElseThrow().render();
        Map<String, Object> schema = (Map<String, Object>) rendered.get(
                DelegationToolSchema.TOOL_NAME);
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) schema.get("inputSchema");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) inputSchema.get("properties");

        assertThat(properties)
                .as("⚠️⚠️ 必须与 DelegationArguments 的口径逐字一致："
                        + "只接受 targetAgentId + input —— 多传字段闸门会拒")
                .containsOnlyKeys("targetAgentId", "input");
        assertThat(inputSchema.get("additionalProperties"))
                .as("⚠️ additionalProperties=false：模型必须自己看见「多传不被允许」，"
                        + "否则模型按 Schema 合法发出的字段被闸门拒 —— 那是两套语义")
                .isEqualTo(false);
        assertThat(inputSchema.get("required"))
                .as("targetAgentId 是必填（缺失即 PARAM_SCHEMA_FAIL）")
                .isEqualTo(List.of("targetAgentId"));
    }

    @Test
    @DisplayName("⚠️ 版本不得出现在 Schema 里（目标版本由服务端按父声明解析）")
    void schemaMustNotExposeTargetVersion() {
        // ⚠️ 模型若能指定版本，就能要求漂移到别的 release ——
        //    与「委派目标固定 release」直接冲突。
        Map<String, Object> rendered = DelegationToolSchema
                .forOrchestration(sequential("child-a")).orElseThrow().render();
        String text = rendered.toString();

        assertThat(text)
                .as("⚠️⚠️ Schema 里不得出现版本字段（模型无权指定 release）")
                .doesNotContain("releaseVersion", "version", "agentVersion");
    }

    @Test
    @DisplayName("⚠️ 工具名是稳定契约（模型可见的 Schema 与闸门认得的调用必须同名）")
    void toolNameIsStableContract() {
        // ⚠️ 两处各写一遍必然漂移，而漂移的后果是
        //    「模型看得见的委派工具」与「闸门认得的委派调用」对不上，
        //    于是模型发起的委派被当成「未登记的工具」拒绝。
        //    ⚠️ 「两处取值相同」由 application 层的
        //    DelegationToolSchemaTest#toolNameMatchesGateConstant 验证
        //    （agent-core 不能引用 agent-application）。
        assertThat(DelegationToolSchema.TOOL_NAME).isEqualTo("delegate_to_agent");
    }
}
