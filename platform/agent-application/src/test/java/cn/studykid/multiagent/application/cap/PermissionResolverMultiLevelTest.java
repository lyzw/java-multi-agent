package cn.studykid.multiagent.application.cap;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ReasonCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多级 Agent 发布闭包的递归展开测试（多级 Agent v1.1 · FR-023 · MA-T02～T04 / MA-T15）。
 *
 * <p>覆盖 06「发布闭包算法」的四条硬约束：
 * <ol>
 *   <li>目标 release 必须<b>已发布</b>；</li>
 *   <li>DFS 访问栈拒绝<b>自环与间接环</b>；</li>
 *   <li>限制最大静态深度；</li>
 *   <li>后代变化必须传导到父闭包指纹。</li>
 * </ol>
 */
class PermissionResolverMultiLevelTest {

    private static final String TENANT = "tenant-1";

    private static final String SEQUENTIAL_TO_CHILD = """
            {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-agent","version":1}],
             "maxDelegationDepth":2,"maxChildInvocations":4,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    @Test
    @DisplayName("父 Agent 的闭包包含固定版本的子 Agent 边，且子能力不并入父工具集")
    void childAgentEdgeIsIncluded() {
        CapFixture fixture = new CapFixture()
                .withSkill("skill-a", 1, CapabilityRiskLevel.READ, List.of("tool-a"))
                .withTool("tool-a", 1, CapabilityRiskLevel.READ, "[]")
                .withAgent(TENANT, "parent-agent", 1, List.of(), "{}", SEQUENTIAL_TO_CHILD)
                .withAgent(TENANT, "child-agent", 1, List.of("skill-a"), "{}", "{}");

        EffectiveClosure closure = resolver(fixture)
                .resolve(TENANT, "parent-agent", 1);

        assertThat(closure.childAgents()).hasSize(1);
        EffectiveClosure.ChildAgentBinding child = closure.childAgents().get(0);
        assertThat(child.agentId()).isEqualTo("child-agent");
        assertThat(child.releaseVersion()).isEqualTo(1);
        assertThat(child.closureFingerprint()).startsWith(ClosureFingerprint.PREFIX);
        assertThat(child.depth()).isEqualTo(1);
        // ⚠️ 委派不是授权：子 Agent 的工具不得出现在父 Agent 的工具集里
        assertThat(closure.tools())
                .as("子 Agent 的能力不得并入父闭包的工具集")
                .isEmpty();
    }

    @Test
    @DisplayName("自环 → 拒绝发布并给稳定原因码 AGENT_REF_CYCLE")
    void selfCycleIsRejected() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "loop-agent", 1, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"loop-agent","version":1}],
                         "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                        """);

        assertThatThrownBy(() -> resolver(fixture).resolve(TENANT, "loop-agent", 1))
                .isInstanceOf(ClosureResolutionException.class)
                .satisfies(e -> assertThat(((ClosureResolutionException) e).reasonCode())
                        .isEqualTo(ReasonCode.AGENT_REF_CYCLE));
    }

    @Test
    @DisplayName("间接环 → 拒绝发布并给稳定原因码 AGENT_REF_CYCLE")
    void indirectCycleIsRejected() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "a-agent", 1, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"b-agent","version":1}],
                         "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                        """)
                .withAgent(TENANT, "b-agent", 1, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"a-agent","version":1}],
                         "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                        """);

        assertThatThrownBy(() -> resolver(fixture).resolve(TENANT, "a-agent", 1))
                .isInstanceOf(ClosureResolutionException.class)
                .satisfies(e -> assertThat(((ClosureResolutionException) e).reasonCode())
                        .isEqualTo(ReasonCode.AGENT_REF_CYCLE));
    }

    @Test
    @DisplayName("子 Agent 引用未发布版本 → 拒绝（AGENT_REF_NOT_PUBLISHED）")
    void unpublishedChildIsRejected() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "parent-agent", 1, List.of(), "{}", SEQUENTIAL_TO_CHILD)
                .withUnpublishedAgent(TENANT, "child-agent", 1, List.of(), "{}", "{}");

        assertThatThrownBy(() -> resolver(fixture).resolve(TENANT, "parent-agent", 1))
                .isInstanceOf(ClosureResolutionException.class)
                .satisfies(e -> assertThat(((ClosureResolutionException) e).reasonCode())
                        .isEqualTo(ReasonCode.AGENT_REF_NOT_PUBLISHED));
    }

    @Test
    @DisplayName("子 Agent 引用不存在 → 拒绝且不泄漏跨租户存在性（RESOURCE_UNKNOWN）")
    void missingChildIsRejectedWithoutLeaking() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "parent-agent", 1, List.of(), "{}", SEQUENTIAL_TO_CHILD);

        assertThatThrownBy(() -> resolver(fixture).resolve(TENANT, "parent-agent", 1))
                .isInstanceOf(ClosureResolutionException.class)
                .satisfies(e -> assertThat(((ClosureResolutionException) e).reasonCode())
                        .isEqualTo(ReasonCode.RESOURCE_UNKNOWN));
    }

    @Test
    @DisplayName("超过最大静态深度 → 拒绝（DELEGATION_DEPTH_EXCEEDED）")
    void exceedingStaticDepthIsRejected() {
        CapFixture fixture = new CapFixture();
        int depth = PermissionResolver.MAX_STATIC_CLOSURE_DEPTH + 2;
        for (int i = 0; i < depth; i++) {
            String childId = "chain-agent-" + (i + 1);
            fixture.withAgent(TENANT, "chain-agent-" + i, 1, List.of(), "{}", """
                    {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"%s","version":1}],
                     "maxDelegationDepth":8,"maxChildInvocations":8,"timeoutMs":30000}
                    """.formatted(childId));
        }
        fixture.withAgent(TENANT, "chain-agent-" + (depth - 1), 1, List.of(), "{}", "{}");

        assertThatThrownBy(() -> resolver(fixture).resolve(TENANT, "chain-agent-0", 1))
                .isInstanceOf(ClosureResolutionException.class)
                .satisfies(e -> assertThat(((ClosureResolutionException) e).reasonCode())
                        .isEqualTo(ReasonCode.DELEGATION_DEPTH_EXCEEDED));
    }

    @Test
    @DisplayName("后代的 Skill 变化 → 父闭包指纹随之变化（MA-T15）")
    void descendantChangePropagatesToParentFingerprint() {
        CapFixture before = new CapFixture()
                .withSkill("skill-a", 1, CapabilityRiskLevel.READ, List.of("tool-a"))
                .withTool("tool-a", 1, CapabilityRiskLevel.READ, "[]")
                .withAgent(TENANT, "parent-agent", 1, List.of(), "{}", SEQUENTIAL_TO_CHILD)
                .withAgent(TENANT, "child-agent", 1, List.of("skill-a"), "{}", "{}");

        CapFixture after = new CapFixture()
                .withSkill("skill-a", 1, CapabilityRiskLevel.READ, List.of("tool-b"))
                .withTool("tool-a", 1, CapabilityRiskLevel.READ, "[]")
                .withTool("tool-b", 1, CapabilityRiskLevel.READ, "[]")
                .withAgent(TENANT, "parent-agent", 1, List.of(), "{}", SEQUENTIAL_TO_CHILD)
                .withAgent(TENANT, "child-agent", 1, List.of("skill-a"), "{}", "{}");

        String beforeFingerprint = ClosureFingerprint.of(resolver(before)
                .resolve(TENANT, "parent-agent", 1));
        String afterFingerprint = ClosureFingerprint.of(resolver(after)
                .resolve(TENANT, "parent-agent", 1));

        assertThat(afterFingerprint)
                .as("子 Agent 的依赖变化必须传导到父闭包指纹")
                .isNotEqualTo(beforeFingerprint);
    }

    @Test
    @DisplayName("旧 Agent 无 orchestration 字段 → 按 LEAF 兼容解析（MA-T01）")
    void legacyAgentWithoutOrchestrationIsLeaf() {
        CapFixture fixture = new CapFixture()
                .withSkill("skill-a", 1, CapabilityRiskLevel.READ, List.of("tool-a"))
                .withTool("tool-a", 1, CapabilityRiskLevel.READ, "[]")
                .withAgent(TENANT, "legacy-agent", 1, List.of("skill-a"), "{}", "{}");

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "legacy-agent", 1);

        assertThat(closure.childAgents()).isEmpty();
        assertThat(closure.skills()).hasSize(1);
    }

    // ── 编排声明入闭包（MA-T15 / MA-T18）─────────────────────────────

    @Test
    @DisplayName("⛔ Agent 自身编排声明被改写 → 其闭包指纹必变（否则「指纹说没变，行为却变了」）")
    void ownOrchestrationChangeChangesFingerprint() {
        CapFixture before = new CapFixture()
                .withContract(TENANT, "contract", 1, "{}", true)
                .withAgent(TENANT, "router-agent", 1, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-agent","version":1}],
                         "resultContractRef":"contract@1",
                         "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                        """)
                .withAgent(TENANT, "child-agent", 1, List.of(), "{}", "{}");

        CapFixture after = new CapFixture()
                .withContract(TENANT, "contract", 2, "{}", true)
                .withAgent(TENANT, "router-agent", 1, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-agent","version":1}],
                         "resultContractRef":"contract@2",
                         "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                        """)
                .withAgent(TENANT, "child-agent", 1, List.of(), "{}", "{}");

        String beforeFingerprint = ClosureFingerprint.of(
                resolver(before).resolve(TENANT, "router-agent", 1));
        String afterFingerprint = ClosureFingerprint.of(
                resolver(after).resolve(TENANT, "router-agent", 1));

        assertThat(afterFingerprint)
                .as("⛔ 输出契约引用被改写却指纹不变：子输出校验口径漂移不会被察觉")
                .isNotEqualTo(beforeFingerprint);
    }

    @Test
    @DisplayName("⛔ 子 Agent 编排声明被改写 → 子闭包自身指纹必变（审批就绑在这个指纹上）")
    void childOrchestrationChangeChangesChildClosureFingerprint() {
        CapFixture before = new CapFixture()
                .withContract(TENANT, "contract", 1, "{}", true)
                .withAgent(TENANT, "child-agent", 1, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","resultContractRef":"contract@1",
                         "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                        """);
        CapFixture after = new CapFixture()
                .withContract(TENANT, "contract", 2, "{}", true)
                .withAgent(TENANT, "child-agent", 1, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","resultContractRef":"contract@2",
                         "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                        """);

        String beforeFingerprint = ClosureFingerprint.of(
                resolver(before).resolve(TENANT, "child-agent", 1));
        String afterFingerprint = ClosureFingerprint.of(
                resolver(after).resolve(TENANT, "child-agent", 1));

        assertThat(afterFingerprint)
                .as("⛔ 子 Agent 编排漂移却其闭包指纹不变：绑定了旧指纹的审批会被继续复用（MA-T18）")
                .isNotEqualTo(beforeFingerprint);
    }

    @Test
    @DisplayName("⛔ 子 Agent 编排声明被改写 → 父闭包指纹随之变化（MA-T15 传导）")
    void childOrchestrationChangePropagatesToParentFingerprint() {
        CapFixture before = new CapFixture()
                .withContract(TENANT, "contract", 1, "{}", true)
                .withAgent(TENANT, "parent-agent", 1, List.of(), "{}", SEQUENTIAL_TO_CHILD)
                .withAgent(TENANT, "child-agent", 1, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","resultContractRef":"contract@1",
                         "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                        """);
        CapFixture after = new CapFixture()
                .withContract(TENANT, "contract", 2, "{}", true)
                .withAgent(TENANT, "parent-agent", 1, List.of(), "{}", SEQUENTIAL_TO_CHILD)
                .withAgent(TENANT, "child-agent", 1, List.of(), "{}", """
                        {"mode":"SEQUENTIAL","resultContractRef":"contract@2",
                         "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                        """);

        String beforeFingerprint = ClosureFingerprint.of(
                resolver(before).resolve(TENANT, "parent-agent", 1));
        String afterFingerprint = ClosureFingerprint.of(
                resolver(after).resolve(TENANT, "parent-agent", 1));

        assertThat(afterFingerprint)
                .as("⛔ 后代的编排漂移未传导到父指纹：父 Run 不会失效")
                .isNotEqualTo(beforeFingerprint);
    }

    @Test
    @DisplayName("✅ 闭包里固化的编排绑定与声明一致（指纹固化的与实际展开的必须是同一份）")
    void closureCarriesOrchestrationBinding() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "parent-agent", 1, List.of(), "{}", SEQUENTIAL_TO_CHILD)
                .withAgent(TENANT, "child-agent", 1, List.of(), "{}", "{}");

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "parent-agent", 1);

        assertThat(closure.orchestration().mode().name()).isEqualTo("SEQUENTIAL");
        assertThat(closure.orchestration().timeoutMs()).isEqualTo(30_000L);
        assertThat(closure.orchestration().maxChildInvocations()).isEqualTo(4);
        assertThat(closure.childAgents().get(0).mode().name()).isEqualTo("LEAF");
    }

    private static PermissionResolver resolver(CapFixture fixture) {
        return new PermissionResolver(fixture.agentDefs(), fixture.skillDefs(),
                fixture.toolDefs(), fixture.serverDefs(), fixture.publishedVersions(),
                fixture.manifest(), fixture.referencedContent());
    }
}
