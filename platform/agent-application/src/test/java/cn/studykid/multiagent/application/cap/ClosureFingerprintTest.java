package cn.studykid.multiagent.application.cap;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.orchestration.DelegationFailurePolicy;
import cn.studykid.multiagent.core.orchestration.OrchestrationBinding;
import cn.studykid.multiagent.core.orchestration.OrchestrationMode;
import cn.studykid.multiagent.core.orchestration.RoutingContract;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闭包指纹单测（P1-T2 · §6.4）。
 *
 * <p>§6.4 的三条硬约束逐条断言：
 * <ol>
 *   <li>无关对象变更不触发本 Run 失效；</li>
 *   <li>凭据材料永不入指纹；</li>
 *   <li>规范化后确定性（键序/集合序无关）。</li>
 * </ol>
 */
class ClosureFingerprintTest {

    private static EffectiveClosure.SkillBinding skill(String id, int version,
                                                         CapabilityRiskLevel ceiling,
                                                         List<String> tools,
                                                         String paramPolicies) {
        return new EffectiveClosure.SkillBinding(id, version, ceiling, "fp-" + id, tools,
                paramPolicies);
    }

    private static EffectiveClosure.ToolBinding tool(String id, int version,
                                                       CapabilityRiskLevel risk,
                                                       String paramPolicies) {
        return new EffectiveClosure.ToolBinding(id, version, CapToolKind.NATIVE, risk,
                "fp-" + id, paramPolicies, null, null);
    }

    private static EffectiveClosure closure(List<EffectiveClosure.SkillBinding> skills,
                                            List<EffectiveClosure.ToolBinding> tools) {
        return new EffectiveClosure("agent-1", 1, skills, tools, List.of());
    }

    @Test
    @DisplayName("✅ 确定性：同一闭包恒得同一指纹")
    void sameClosureSameFingerprint() {
        EffectiveClosure closure = closure(
                List.of(skill("skill-a", 1, CapabilityRiskLevel.READ, List.of("tool-a"), "[]")),
                List.of(tool("tool-a", 1, CapabilityRiskLevel.READ, "[]")));

        assertThat(ClosureFingerprint.of(closure)).isEqualTo(ClosureFingerprint.of(closure));
        assertThat(ClosureFingerprint.of(closure)).startsWith(ClosureFingerprint.PREFIX);
    }

    @Test
    @DisplayName("✅ 集合顺序无关：同一依赖集合换书写顺序 → 同指纹")
    void setOrderDoesNotAffectFingerprint() {
        EffectiveClosure a = closure(
                List.of(skill("skill-a", 1, CapabilityRiskLevel.READ, List.of("tool-a"), "[]")),
                List.of(tool("tool-a", 1, CapabilityRiskLevel.READ, "[]")));
        EffectiveClosure b = closure(
                List.of(skill("skill-a", 1, CapabilityRiskLevel.READ, List.of(), "[]")),
                List.of(tool("tool-a", 1, CapabilityRiskLevel.READ, "[]")));
        // 把声明顺序颠倒
        EffectiveClosure reordered = closure(
                List.of(skill("skill-a", 1, CapabilityRiskLevel.READ, List.of("tool-a"), "[]")),
                List.of(tool("tool-a", 1, CapabilityRiskLevel.READ, "[]")));

        assertThat(ClosureFingerprint.of(a)).isEqualTo(ClosureFingerprint.of(reordered));
        assertThat(ClosureFingerprint.of(a)).isNotEqualTo(ClosureFingerprint.of(b));
    }

    @Test
    @DisplayName("✅ 键序无关：参数策略 JSON 键序变化 → 同指纹")
    void jsonKeyOrderDoesNotAffectFingerprint() {
        EffectiveClosure a = closure(
                List.of(skill("skill-a", 1, CapabilityRiskLevel.READ, List.of(), "[]")),
                List.of(tool("tool-a", 1, CapabilityRiskLevel.READ,
                        "[{\"param\":\"p\",\"op\":\"in_allowlist\",\"value\":[\"x\"]}]")));
        EffectiveClosure b = closure(
                List.of(skill("skill-a", 1, CapabilityRiskLevel.READ, List.of(), "[]")),
                List.of(tool("tool-a", 1, CapabilityRiskLevel.READ,
                        "[{\"value\":[\"x\"],\"op\":\"in_allowlist\",\"param\":\"p\"}]")));

        assertThat(ClosureFingerprint.of(a))
                .as("⛔ 策略 JSON 的键序变化导致指纹变化：内容未变却被判为失效")
                .isEqualTo(ClosureFingerprint.of(b));
    }

    @Test
    @DisplayName("✅ 策略数组顺序无关：同参数策略换序 → 同指纹")
    void policyArrayOrderDoesNotAffectFingerprint() {
        EffectiveClosure a = closure(
                List.of(skill("skill-a", 1, CapabilityRiskLevel.READ, List.of(), "[]")),
                List.of(tool("tool-a", 1, CapabilityRiskLevel.READ,
                        "[{\"param\":\"a\",\"op\":\"lte\",\"value\":1},"
                                + "{\"param\":\"b\",\"op\":\"lte\",\"value\":2}]")));
        EffectiveClosure b = closure(
                List.of(skill("skill-a", 1, CapabilityRiskLevel.READ, List.of(), "[]")),
                List.of(tool("tool-a", 1, CapabilityRiskLevel.READ,
                        "[{\"param\":\"b\",\"op\":\"lte\",\"value\":2},"
                                + "{\"param\":\"a\",\"op\":\"lte\",\"value\":1}]")));

        assertThat(ClosureFingerprint.of(a)).isEqualTo(ClosureFingerprint.of(b));
    }

    @Test
    @DisplayName("⛔ 依赖对象内容变化 → 指纹必变（否则失效检测彻底失效）")
    void dependencyChangeChangesFingerprint() {
        EffectiveClosure before = closure(
                List.of(skill("skill-a", 1, CapabilityRiskLevel.READ, List.of("tool-a"), "[]")),
                List.of(tool("tool-a", 1, CapabilityRiskLevel.READ, "[]")));
        EffectiveClosure after = closure(
                List.of(skill("skill-a", 1, CapabilityRiskLevel.READ, List.of("tool-a"), "[]")),
                List.of(tool("tool-a", 2, CapabilityRiskLevel.READ, "[]")));

        assertThat(ClosureFingerprint.of(before))
                .as("⛔ 依赖对象版本变化却指纹不变：重新发布不会被感知")
                .isNotEqualTo(ClosureFingerprint.of(after));
    }

    @Test
    @DisplayName("⛔ 凭据材料永不入指纹")
    void credentialsNeverEnterFingerprint() {
        EffectiveClosure closure = new EffectiveClosure("agent-1", 1,
                List.of(skill("skill-a", 1, CapabilityRiskLevel.READ, List.of(), "[]")),
                List.of(tool("tool-a", 1, CapabilityRiskLevel.READ, "[]")),
                List.of(new EffectiveClosure.McpBinding("mcp-1", 1, "skill-a",
                        cn.studykid.multiagent.core.cap.McpTrustTier.INTERNAL,
                        CapabilityRiskLevel.WRITE_EXTERNAL,
                        Set.of("op-a"), Set.of("op-a"))));

        String canonical = ClosureFingerprint.canonicalJson(closure);

        assertThat(canonical)
                .as("⛔ 规范化闭包里出现了凭据相关字段")
                .doesNotContain("credential")
                .doesNotContain("cred-ref");
    }

    @Test
    @DisplayName("⛔ 全局发布序号不入指纹（§7.3：仅全局序号变不得视为授权失败）")
    void globalSequenceDoesNotEnterFingerprint() {
        String canonical = ClosureFingerprint.canonicalJson(
                closure(List.of(), List.of()));

        assertThat(canonical)
                .as("⛔ 指纹包含全局序号：仅全局序号变化会让所有在途 Run 集体失效")
                .doesNotContain("configVersion")
                .doesNotContain("configPublishSeq");
    }

    @Test
    @DisplayName("✅ ForcedSafety 策略版本入指纹")
    void forcedSafetyPolicyVersionEntersFingerprint() {
        String canonical = ClosureFingerprint.canonicalJson(closure(List.of(), List.of()));

        assertThat(canonical).contains(ClosureFingerprint.FORCED_SAFETY_POLICY_VERSION);
    }

    @Test
    @DisplayName("✅ 占位指纹可被识别（P0 的 pending_v1: 不参与失效判定）")
    void provisionalFingerprintIsDetectable() {
        assertThat(ClosureFingerprint.isProvisional("pending_v1:abc")).isTrue();
        assertThat(ClosureFingerprint.isProvisional(ClosureFingerprint.PREFIX + "abc")).isFalse();
        assertThat(ClosureFingerprint.isProvisional(null)).isFalse();
    }

    // ── 编排声明入指纹（多级 Agent v1.1 · MA-T15 / MA-T18）────────────

    @Test
    @DisplayName("✅ 编排声明入规范化 JSON（路由策略 / 输出契约 / 上限 / 失败策略）")
    void orchestrationBindingEntersCanonicalJson() {
        String canonical = ClosureFingerprint.canonicalJson(
                new EffectiveClosure("agent-1", 1, List.of(), List.of(), List.of(), List.of(),
                        new OrchestrationBinding(OrchestrationMode.ROUTER, "policy@1",
                                "contract@1", 3, 5, 45_000L, DelegationFailurePolicy.CONTINUE_ON_ERROR)));

        assertThat(canonical)
                .as("⛔ 编排声明不在规范化闭包里：编排漂移将无法被指纹察觉")
                .contains("\"orchestration\"")
                .contains("policy@1")
                .contains("contract@1")
                .contains("ROUTER")
                .contains("CONTINUE_ON_ERROR")
                .contains(ClosureFingerprint.ORCHESTRATION_BINDING_VERSION);
    }

    @Test
    @DisplayName("⛔ 编排声明任一字段变化 → 指纹必变（否则「指纹说没变，行为却变了」）")
    void orchestrationChangeChangesFingerprint() {
        OrchestrationBinding base = new OrchestrationBinding(OrchestrationMode.ROUTER,
                "policy@1", "contract@1", 3, 5, 45_000L, DelegationFailurePolicy.FAIL_FAST);

        assertThat(fingerprintOf(base))
                .as("⛔ 路由策略引用变化却指纹不变：换策略不会被感知")
                .isNotEqualTo(fingerprintOf(new OrchestrationBinding(OrchestrationMode.ROUTER,
                        "policy@2", "contract@1", 3, 5, 45_000L, DelegationFailurePolicy.FAIL_FAST)));
        assertThat(fingerprintOf(base))
                .as("⛔ 输出契约引用变化却指纹不变：换 Schema 不会被感知")
                .isNotEqualTo(fingerprintOf(new OrchestrationBinding(OrchestrationMode.ROUTER,
                        "policy@1", "contract@2", 3, 5, 45_000L, DelegationFailurePolicy.FAIL_FAST)));
        assertThat(fingerprintOf(base))
                .as("⛔ 超时预算变化却指纹不变：共享超时上限被放宽不会被感知")
                .isNotEqualTo(fingerprintOf(new OrchestrationBinding(OrchestrationMode.ROUTER,
                        "policy@1", "contract@1", 3, 5, 90_000L, DelegationFailurePolicy.FAIL_FAST)));
        assertThat(fingerprintOf(base))
                .as("⛔ 最大委派深度变化却指纹不变：共享深度上限被放宽不会被感知")
                .isNotEqualTo(fingerprintOf(new OrchestrationBinding(OrchestrationMode.ROUTER,
                        "policy@1", "contract@1", 9, 5, 45_000L, DelegationFailurePolicy.FAIL_FAST)));
        assertThat(fingerprintOf(base))
                .as("⛔ 最大子调用次数变化却指纹不变：共享次数上限被放宽不会被感知")
                .isNotEqualTo(fingerprintOf(new OrchestrationBinding(OrchestrationMode.ROUTER,
                        "policy@1", "contract@1", 3, 99, 45_000L, DelegationFailurePolicy.FAIL_FAST)));
        assertThat(fingerprintOf(base))
                .as("⛔ 失败策略变化却指纹不变：失败后行为改变不会被感知")
                .isNotEqualTo(fingerprintOf(new OrchestrationBinding(OrchestrationMode.ROUTER,
                        "policy@1", "contract@1", 3, 5, 45_000L,
                        DelegationFailurePolicy.CONTINUE_ON_ERROR)));
        assertThat(fingerprintOf(base))
                .as("⛔ 编排模式变化却指纹不变：LEAF ↔ ROUTER 的切换不会被感知")
                .isNotEqualTo(fingerprintOf(new OrchestrationBinding(OrchestrationMode.SEQUENTIAL,
                        "policy@1", "contract@1", 3, 5, 45_000L, DelegationFailurePolicy.FAIL_FAST)));
    }

    @Test
    @DisplayName("✅ 缺省 LEAF 与显式 LEAF 同指纹（MA-T01 兼容读不得制造无谓失效）")
    void defaultLeafMatchesExplicitLeaf() {
        EffectiveClosure defaulted = new EffectiveClosure("agent-1", 1, List.of(), List.of(),
                List.of());
        EffectiveClosure explicitLeaf = new EffectiveClosure("agent-1", 1, List.of(), List.of(),
                List.of(), List.of(), OrchestrationBinding.leaf());

        assertThat(ClosureFingerprint.of(defaulted))
                .as("⛔ 缺省闭包与显式 LEAF 闭包指纹不同：历史行会被误判为失效")
                .isEqualTo(ClosureFingerprint.of(explicitLeaf));
    }

    private static String fingerprintOf(OrchestrationBinding binding) {
        return ClosureFingerprint.of(new EffectiveClosure("agent-1", 1, List.of(), List.of(),
                List.of(), List.of(), binding));
    }

    // ── 子 Agent 路由契约入指纹（多级 Agent v1.1 · 10 号设计 S1）───────

    @Test
    @DisplayName("✅ 未声明契约的委派边：规范化闭包里没有 routingContract 段（旧指纹不受扰动）")
    void edgeWithoutContractKeepsCanonicalShape() {
        String canonical = ClosureFingerprint.canonicalJson(closureWithChild(null));

        assertThat(canonical)
                .as("⛔ 没有契约的闭包也被写入了契约段：所有多级闭包指纹集体变化，"
                        + "在途 Run 无谓失效")
                .doesNotContain("routingContract")
                .doesNotContain(ClosureFingerprint.CHILD_CONTRACT_BINDING_VERSION);
    }

    @Test
    @DisplayName("⛔ 契约任一字段变化 → 指纹必变（否则 Router 的选型依据变了却不被察觉）")
    void contractChangeChangesFingerprint() {
        RoutingContract base = contract("解数学题");

        assertThat(fingerprintWith(base))
                .as("⛔ intent 变化却指纹不变")
                .isNotEqualTo(fingerprintWith(contract("解几何题")));
        assertThat(fingerprintWith(base))
                .as("⛔ useWhen 变化却指纹不变")
                .isNotEqualTo(fingerprintWith(withUseWhen(List.of("学生问计算题"))));
        assertThat(fingerprintWith(base))
                .as("⛔ avoidWhen 变化却指纹不变")
                .isNotEqualTo(fingerprintWith(withAvoidWhen(List.of("非题目类闲聊"))));
        assertThat(fingerprintWith(base))
                .as("⛔ capabilities 变化却指纹不变")
                .isNotEqualTo(fingerprintWith(withCapabilities(List.of("geometry"))));
        assertThat(fingerprintWith(base))
                .as("⛔ inputContract 变化却指纹不变")
                .isNotEqualTo(fingerprintWith(withInputContract(
                        Map.of("type", "object", "required", List.of("question")))));
        assertThat(fingerprintWith(base))
                .as("⛔ priority 变化却指纹不变：同分决胜顺序被改动不会被感知")
                .isNotEqualTo(fingerprintWith(withPriority(3)));
        assertThat(fingerprintWith(base))
                .as("⛔ source 变化却指纹不变：继承底稿被改成父覆盖不会被感知")
                .isNotEqualTo(fingerprintWith(withSource(RoutingContract.Source.OVERRIDE)));
    }

    @Test
    @DisplayName("⛔ 声明契约与不声明契约必须可区分（空声明 ≠ 没声明）")
    void declaredEmptyContractDiffersFromNoContract() {
        RoutingContract bare = new RoutingContract("解数学题", List.of(), List.of(), List.of(),
                Map.of(), RoutingContract.MIN_PRIORITY, RoutingContract.Source.INHERITED);

        assertThat(fingerprintWith(bare))
                .as("⛔ 「声明了一个不加约束的契约」与「压根没声明」指纹相同："
                        + "撤掉契约会被误认为从未有过契约")
                .isNotEqualTo(fingerprintWith(null));
    }

    @Test
    @DisplayName("✅ 契约的表述差异不改指纹：Schema 键序、场景条目书写顺序无关")
    void contractWordingOrderDoesNotAffectFingerprint() {
        RoutingContract a = withInputContract(Map.of(
                "type", "object",
                "required", List.of("stem", "grade"),
                "properties", Map.of("stem", Map.of("type", "string"),
                        "grade", Map.of("type", "string"))));
        // 同样的 Schema，键序与数组序全部颠倒（Map.of 的迭代序本就不可依赖，
        // 这里断言的正是「指纹不靠输入顺序」）
        RoutingContract b = withInputContract(Map.of(
                "properties", Map.of("grade", Map.of("type", "string"),
                        "stem", Map.of("type", "string")),
                "required", List.of("grade", "stem"),
                "type", "object"));

        assertThat(fingerprintWith(a))
                .as("⛔ 契约内容没变、只是写法顺序不同就改了指纹：在途 Run 无故失效")
                .isEqualTo(fingerprintWith(b));
        assertThat(fingerprintWith(withUseWhen(List.of("甲", "乙"))))
                .isEqualTo(fingerprintWith(withUseWhen(List.of("乙", "甲"))));
    }

    private static EffectiveClosure closureWithChild(RoutingContract contract) {
        return new EffectiveClosure("agent-1", 1, List.of(), List.of(), List.of(),
                List.of(new EffectiveClosure.ChildAgentBinding("child-1", 1, "fp-child",
                        ClosureFingerprint.PREFIX + "child", OrchestrationMode.LEAF, 1, contract)));
    }

    private static String fingerprintWith(RoutingContract contract) {
        return ClosureFingerprint.of(closureWithChild(contract));
    }

    private static RoutingContract contract(String intent) {
        return new RoutingContract(intent, List.of("学生提出题目"), List.of("纯闲聊"),
                List.of("math"), Map.of("type", "object", "required", List.of("stem")),
                RoutingContract.MIN_PRIORITY, RoutingContract.Source.INHERITED);
    }

    private static RoutingContract withUseWhen(List<String> useWhen) {
        return new RoutingContract("解数学题", useWhen, List.of("纯闲聊"),
                List.of("math"), Map.of("type", "object", "required", List.of("stem")),
                RoutingContract.MIN_PRIORITY, RoutingContract.Source.INHERITED);
    }

    private static RoutingContract withAvoidWhen(List<String> avoidWhen) {
        return new RoutingContract("解数学题", List.of("学生提出题目"), avoidWhen,
                List.of("math"), Map.of("type", "object", "required", List.of("stem")),
                RoutingContract.MIN_PRIORITY, RoutingContract.Source.INHERITED);
    }

    private static RoutingContract withCapabilities(List<String> capabilities) {
        return new RoutingContract("解数学题", List.of("学生提出题目"), List.of("纯闲聊"),
                capabilities, Map.of("type", "object", "required", List.of("stem")),
                RoutingContract.MIN_PRIORITY, RoutingContract.Source.INHERITED);
    }

    private static RoutingContract withInputContract(Map<String, Object> inputContract) {
        return new RoutingContract("解数学题", List.of("学生提出题目"), List.of("纯闲聊"),
                List.of("math"), inputContract,
                RoutingContract.MIN_PRIORITY, RoutingContract.Source.INHERITED);
    }

    private static RoutingContract withPriority(int priority) {
        return new RoutingContract("解数学题", List.of("学生提出题目"), List.of("纯闲聊"),
                List.of("math"), Map.of("type", "object", "required", List.of("stem")),
                priority, RoutingContract.Source.INHERITED);
    }

    private static RoutingContract withSource(RoutingContract.Source source) {
        return new RoutingContract("解数学题", List.of("学生提出题目"), List.of("纯闲聊"),
                List.of("math"), Map.of("type", "object", "required", List.of("stem")),
                RoutingContract.MIN_PRIORITY, source);
    }
}
