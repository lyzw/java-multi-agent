package cn.studykid.multiagent.application.cap;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.EffectiveClosure;

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
}
