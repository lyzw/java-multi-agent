package cn.studykid.multiagent.application.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.orchestration.AgentOrchestration;
import cn.studykid.multiagent.core.orchestration.DelegationFailurePolicy;
import cn.studykid.multiagent.core.orchestration.OrchestrationMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编排声明解析与规范化测试（多级 Agent v1.1 · P1-MA-01 · MA-T01 / MA-T02）。
 */
class AgentOrchestrationParserTest {

    @Test
    @DisplayName("缺省（null / 空 / {}）→ 按 LEAF 兼容解析（MA-T01）")
    void missingOrchestrationIsLeaf() {
        assertThat(AgentOrchestrationParser.parse(null).isLeaf()).isTrue();
        assertThat(AgentOrchestrationParser.parse("   ").isLeaf()).isTrue();
        assertThat(AgentOrchestrationParser.parse("{}").isLeaf()).isTrue();
    }

    @Test
    @DisplayName("子 Agent 引用 latest → 配置期拒绝（MA-T02）")
    void latestChildRefIsRejected() {
        assertThatThrownBy(() -> AgentOrchestrationParser.parse("""
                {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child","version":"latest"}]}
                """))
                .isInstanceOf(OrchestrationConfigException.class)
                .hasMessageContaining("latest");
    }

    @Test
    @DisplayName("ROUTER 模式缺路由策略 → 配置期拒绝")
    void routerWithoutPolicyIsRejected() {
        assertThatThrownBy(() -> AgentOrchestrationParser.parse("""
                {"mode":"ROUTER"}
                """))
                .isInstanceOf(OrchestrationConfigException.class)
                .hasMessageContaining("路由策略");
    }

    @Test
    @DisplayName("LEAF 携带子引用 → 配置期拒绝（不得静默忽略）")
    void leafWithChildrenIsRejected() {
        assertThatThrownBy(() -> AgentOrchestrationParser.parse("""
                {"mode":"LEAF","childAgentRefs":[{"id":"child","version":1}]}
                """))
                .isInstanceOf(OrchestrationConfigException.class);
    }

    @Test
    @DisplayName("未知模式 → 配置期拒绝")
    void unknownModeIsRejected() {
        assertThatThrownBy(() -> AgentOrchestrationParser.parse("""
                {"mode":"PARALLEL"}
                """))
                .isInstanceOf(OrchestrationConfigException.class)
                .hasMessageContaining("未知");
    }

    @Test
    @DisplayName("路由策略引用必须是固定版本（id@version），禁止 latest")
    void routingPolicyRefMustBePinned() {
        assertThatThrownBy(() -> AgentOrchestrationParser.parse("""
                {"mode":"ROUTER","routingPolicyRef":"policy@latest"}
                """))
                .isInstanceOf(OrchestrationConfigException.class)
                .hasMessageContaining("latest");

        assertThatThrownBy(() -> AgentOrchestrationParser.parse("""
                {"mode":"ROUTER","routingPolicyRef":"policy"}
                """))
                .isInstanceOf(OrchestrationConfigException.class)
                .hasMessageContaining("id@version");
    }

    @Test
    @DisplayName("SEQUENTIAL 正常解析：模式 / 子引用 / 边界默认值")
    void sequentialIsParsed() {
        AgentOrchestration orchestration = AgentOrchestrationParser.parse("""
                {"mode":"SEQUENTIAL",
                 "childAgentRefs":[{"id":"child-a","version":2},{"id":"child-b","version":1,"required":false}],
                 "maxDelegationDepth":3,"maxChildInvocations":5,"timeoutMs":15000,
                 "failurePolicy":"CONTINUE_ON_ERROR"}
                """);

        assertThat(orchestration.mode()).isEqualTo(OrchestrationMode.SEQUENTIAL);
        assertThat(orchestration.childAgentRefs()).hasSize(2);
        assertThat(orchestration.child("child-a")).isPresent();
        assertThat(orchestration.child("child-a").orElseThrow().releaseVersion()).isEqualTo(2);
        assertThat(orchestration.child("child-a").orElseThrow().required()).isTrue();
        assertThat(orchestration.child("child-b").orElseThrow().required()).isFalse();
        assertThat(orchestration.maxDelegationDepth()).isEqualTo(3);
        assertThat(orchestration.maxChildInvocations()).isEqualTo(5);
        assertThat(orchestration.timeoutMs()).isEqualTo(15000L);
        assertThat(orchestration.failurePolicy()).isEqualTo(DelegationFailurePolicy.CONTINUE_ON_ERROR);
    }

    @Test
    @DisplayName("缺省边界取平台默认值（NFR-012）")
    void defaultsAreApplied() {
        AgentOrchestration orchestration = AgentOrchestrationParser.parse("""
                {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child","version":1}]}
                """);

        assertThat(orchestration.maxDelegationDepth())
                .isEqualTo(AgentOrchestration.DEFAULT_MAX_DELEGATION_DEPTH);
        assertThat(orchestration.maxChildInvocations())
                .isEqualTo(AgentOrchestration.DEFAULT_MAX_CHILD_INVOCATIONS);
        assertThat(orchestration.timeoutMs()).isEqualTo(AgentOrchestration.DEFAULT_TIMEOUT_MS);
        assertThat(orchestration.failurePolicy()).isEqualTo(DelegationFailurePolicy.FAIL_FAST);
    }

    @Test
    @DisplayName("序列化 → 再解析保持等价（往返稳定）")
    void roundTripIsStable() {
        String json = """
                {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-a","version":2}],
                 "maxDelegationDepth":3,"maxChildInvocations":5,"timeoutMs":15000,
                 "failurePolicy":"FAIL_FAST"}
                """;

        AgentOrchestration parsed = AgentOrchestrationParser.parse(json);
        AgentOrchestration reparsed = AgentOrchestrationParser.parse(
                AgentOrchestrationParser.toJson(parsed));

        assertThat(reparsed).isEqualTo(parsed);
    }
}
