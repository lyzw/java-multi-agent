package cn.studykid.multiagent.application.cap;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.ReasonCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多级 Agent 发布期闭包校验测试（FR-023 · MA-T02～T04）。
 *
 * <p>⚠️ 这些用例断言的是<b>发布期</b>行为：配置非法必须在生效前被拒，
 * 而不是留到运行期让新 Run 集体失败。
 */
class AgentPublishClosureValidatorTest {

    private static final String TENANT = "tenant-1";

    @Test
    @DisplayName("子 Agent 引用已发布 → 校验通过")
    void publishedChildPasses() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "parent-agent", 1, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-agent","version":1}]}
                        """)
                .withAgent(TENANT, "child-agent", 1, List.of(), "{}", "{}");

        AgentPublishClosureValidator validator = new AgentPublishClosureValidator(
                fixture.agentDefs(), fixture.resultContracts());

        assertThatCode(() -> validator.validate(TENANT, draft("parent-agent", """
                {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-agent","version":1}]}
                """))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("子 Agent 引用未发布 → 拒绝（AGENT_REF_NOT_PUBLISHED · MA-T02）")
    void unpublishedChildIsRejected() {
        CapFixture fixture = new CapFixture()
                .withUnpublishedAgent(TENANT, "child-agent", 1, List.of(), "{}", "{}");

        AgentPublishClosureValidator validator = new AgentPublishClosureValidator(
                fixture.agentDefs(), fixture.resultContracts());

        assertThatThrownBy(() -> validator.validate(TENANT, draft("parent-agent", """
                {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-agent","version":1}]}
                """)))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.AGENT_REF_NOT_PUBLISHED));
    }

    @Test
    @DisplayName("子 Agent 引用不存在 → 拒绝且不泄漏存在性（RESOURCE_UNKNOWN · MA-T04）")
    void missingChildIsRejected() {
        CapFixture fixture = new CapFixture();

        AgentPublishClosureValidator validator = new AgentPublishClosureValidator(
                fixture.agentDefs(), fixture.resultContracts());

        assertThatThrownBy(() -> validator.validate(TENANT, draft("parent-agent", """
                {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-agent","version":1}]}
                """)))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.RESOURCE_UNKNOWN));
    }

    @Test
    @DisplayName("间接环 → 拒绝（AGENT_REF_CYCLE · MA-T03）")
    void indirectCycleIsRejected() {
        // ⚠️ 模拟真实发布事务的顺序：**版本行先落库**，再校验闭包。
        //    因此子 Agent 可以引用「正在发布的这个版本」，环得以被检出。
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "a-agent", 2, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"b-agent","version":1}]}
                        """)
                .withAgent(TENANT, "b-agent", 1, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"a-agent","version":2}]}
                        """);

        AgentPublishClosureValidator validator = new AgentPublishClosureValidator(
                fixture.agentDefs(), fixture.resultContracts());

        assertThatThrownBy(() -> validator.validate(TENANT, draft("a-agent", """
                {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"b-agent","version":1}]}
                """)))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.AGENT_REF_CYCLE));
    }

    @Test
    @DisplayName("沿版本递减的引用链不是环 → 校验通过")
    void descendingVersionChainIsNotCycle() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "a-agent", 2, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"b-agent","version":1}]}
                        """)
                .withAgent(TENANT, "b-agent", 1, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"a-agent","version":1}]}
                        """)
                .withAgent(TENANT, "a-agent", 1, List.of(), "{}", "{}");

        AgentPublishClosureValidator validator = new AgentPublishClosureValidator(
                fixture.agentDefs(), fixture.resultContracts());

        assertThatCode(() -> validator.validate(TENANT, draft("a-agent", """
                {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"b-agent","version":1}]}
                """))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("编排声明非法 → 拒绝（ORCHESTRATION_INVALID）")
    void invalidOrchestrationIsRejected() {
        CapFixture fixture = new CapFixture();

        AgentPublishClosureValidator validator = new AgentPublishClosureValidator(
                fixture.agentDefs(), fixture.resultContracts());

        assertThatThrownBy(() -> validator.validate(TENANT, draft("parent-agent",
                "{\"mode\":\"SEQUENTIAL\",\"childAgentRefs\":[{\"id\":\"c\",\"version\":\"latest\"}]}")))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.ORCHESTRATION_INVALID));
    }

    @Test
    @DisplayName("无编排声明（LEAF）→ 校验通过（不阻断单 Agent 发布）")
    void leafAgentPasses() {
        CapFixture fixture = new CapFixture();

        AgentPublishClosureValidator validator = new AgentPublishClosureValidator(
                fixture.agentDefs(), fixture.resultContracts());

        assertThatCode(() -> validator.validate(TENANT, draft("parent-agent", "{}")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("输出契约引用已发布 → 校验通过")
    void publishedResultContractPasses() {
        CapFixture fixture = new CapFixture()
                .withContract(TENANT, "answer-contract", 1,
                        "{\"type\":\"object\"}", true);

        AgentPublishClosureValidator validator = new AgentPublishClosureValidator(
                fixture.agentDefs(), fixture.resultContracts());

        assertThatCode(() -> validator.validate(TENANT, draft("parent-agent", """
                {"mode":"SEQUENTIAL","resultContractRef":"answer-contract@1"}
                """))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("输出契约引用未发布 → 拒绝（RESULT_CONTRACT_NOT_PUBLISHED）")
    void unpublishedResultContractIsRejected() {
        CapFixture fixture = new CapFixture()
                .withContract(TENANT, "answer-contract", 1,
                        "{\"type\":\"object\"}", false);

        AgentPublishClosureValidator validator = new AgentPublishClosureValidator(
                fixture.agentDefs(), fixture.resultContracts());

        assertThatThrownBy(() -> validator.validate(TENANT, draft("parent-agent", """
                {"mode":"SEQUENTIAL","resultContractRef":"answer-contract@1"}
                """)))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.RESULT_CONTRACT_NOT_PUBLISHED));
    }

    @Test
    @DisplayName("输出契约引用不存在 → 拒绝（RESOURCE_UNKNOWN）")
    void missingResultContractIsRejected() {
        CapFixture fixture = new CapFixture();

        AgentPublishClosureValidator validator = new AgentPublishClosureValidator(
                fixture.agentDefs(), fixture.resultContracts());

        assertThatThrownBy(() -> validator.validate(TENANT, draft("parent-agent", """
                {"mode":"SEQUENTIAL","resultContractRef":"answer-contract@1"}
                """)))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.RESOURCE_UNKNOWN));
    }

    @Test
    @DisplayName("输出契约引用不是固定版本 → 拒绝（ORCHESTRATION_INVALID）")
    void driftingResultContractRefIsRejected() {
        CapFixture fixture = new CapFixture();

        AgentPublishClosureValidator validator = new AgentPublishClosureValidator(
                fixture.agentDefs(), fixture.resultContracts());

        assertThatThrownBy(() -> validator.validate(TENANT, draft("parent-agent", """
                {"mode":"SEQUENTIAL","resultContractRef":"answer-contract@latest"}
                """)))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.ORCHESTRATION_INVALID));
    }

    private static CapAgentDef draft(String agentId, String orchestration) {
        return new CapAgentDef(TENANT, agentId, 2, agentId, agentId + " desc",
                List.of("TEACHER"), List.of(), List.of(), List.of(),
                "{}", orchestration, "fp-" + agentId, 1,
                java.time.Instant.EPOCH, null);
    }
}
