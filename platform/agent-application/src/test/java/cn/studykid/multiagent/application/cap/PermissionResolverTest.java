package cn.studykid.multiagent.application.cap;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ReasonCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 闭包解析单测（P1-T1 · §6.2 / §4.4 / §4.2）。
 */
class PermissionResolverTest {

    private static final String TENANT = "t-a";

    private PermissionResolver resolver(CapFixture fixture) {
        return new PermissionResolver(fixture.agentDefs(), fixture.skillDefs(), fixture.toolDefs(),
                fixture.serverDefs(), fixture.publishedVersions(), fixture.manifest());
    }

    @Test
    @DisplayName("✅ 基本闭包：Agent→Skill→Tool 的固定版本集")
    void resolvesAgentSkillToolChain() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-a"), "{}")
                .withSkill("skill-a", 2, CapabilityRiskLevel.WRITE_EXTERNAL, List.of("tool-a"))
                .withTool("tool-a", 3, CapabilityRiskLevel.READ, "[]");

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-1", 1);

        assertThat(closure.agentId()).isEqualTo("agent-1");
        assertThat(closure.agentVersion()).isEqualTo(1);
        assertThat(closure.skill("skill-a")).isPresent();
        assertThat(closure.skill("skill-a").orElseThrow().version()).isEqualTo(2);
        assertThat(closure.tool("tool-a")).isPresent();
        assertThat(closure.tool("tool-a").orElseThrow().version()).isEqualTo(3);
        assertThat(closure.tool("tool-a").orElseThrow().riskLevel())
                .isEqualTo(CapabilityRiskLevel.READ);
    }

    @Test
    @DisplayName("✅ 空闭包是合法状态：Agent 未启用任何 Skill")
    void emptyClosureIsLegalNotFailure() {
        CapFixture fixture = new CapFixture().withAgent(TENANT, "agent-empty", 1, List.of(), "{}");

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-empty", 1);

        assertThat(closure.isEmpty())
                .as("⛔ 空闭包被当成解析失败：会让「未启用 Skill」与「配置缺陷」混淆")
                .isTrue();
        assertThat(closure.skills()).isEmpty();
    }

    @Test
    @DisplayName("⛔ 缺失对象（Skill 无已发布版本）→ 解析失败，不得静默跳过")
    void missingSkillFailsClosed() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-missing"), "{}");

        assertThatThrownBy(() -> resolver(fixture).resolve(TENANT, "agent-1", 1))
                .isInstanceOf(ClosureResolutionException.class)
                .satisfies(e -> assertThat(((ClosureResolutionException) e).reasonCode())
                        .isEqualTo(ReasonCode.RESOURCE_UNKNOWN));
    }

    @Test
    @DisplayName("✅ 版本解析边界：取当前已发布版本，而非最高版本行")
    void resolvesCurrentPublishedVersion() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-a"), "{}")
                .withSkill("skill-a", 1, CapabilityRiskLevel.READ, List.of())
                .withSkill("skill-a", 2, CapabilityRiskLevel.READ, List.of());

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-1", 1);

        assertThat(closure.skill("skill-a").orElseThrow().version())
                .as("⛔ 未取到最新的已发布版本")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("✅ overrides.removeSkills：被移除的 Skill 不进闭包（只能收紧）")
    void overridesRemoveSkill() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-a", "skill-b"),
                        "{\"removeSkills\":[\"skill-b\"]}")
                .withSkill("skill-a", 1, CapabilityRiskLevel.READ, List.of())
                .withSkill("skill-b", 1, CapabilityRiskLevel.READ, List.of());

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-1", 1);

        assertThat(closure.skill("skill-a")).isPresent();
        assertThat(closure.skill("skill-b"))
                .as("⛔ 被 overrides 移除的 Skill 仍在闭包：收紧语义失效")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ overrides.removeTools：被移除的 Tool 不进闭包")
    void overridesRemoveTool() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-a"),
                        "{\"removeTools\":[\"tool-b\"]}")
                .withSkill("skill-a", 1, CapabilityRiskLevel.READ, List.of("tool-a", "tool-b"))
                .withTool("tool-a", 1, CapabilityRiskLevel.READ, "[]")
                .withTool("tool-b", 1, CapabilityRiskLevel.READ, "[]");

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-1", 1);

        assertThat(closure.tool("tool-a")).isPresent();
        assertThat(closure.tool("tool-b")).isEmpty();
        assertThat(closure.skill("skill-a").orElseThrow().declaredTools())
                .as("⛔ 声明边未同步移除：SkillToolAllowed 会误判为已声明")
                .containsExactly("tool-a");
    }

    @Test
    @DisplayName("✅ overrides.lowerRiskCeiling：只能下调，不能上调")
    void overridesLowerRiskCeilingOnly() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-a"),
                        "{\"lowerRiskCeiling\":\"WRITE_PROFILE\"}")
                .withSkill("skill-a", 1, CapabilityRiskLevel.WRITE_EXTERNAL, List.of());

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-1", 1);

        assertThat(closure.skill("skill-a").orElseThrow().riskCeiling())
                .as("⛔ 风险上限未被下调")
                .isEqualTo(CapabilityRiskLevel.WRITE_PROFILE);
    }

    @Test
    @DisplayName("⛔ overrides 不能放宽风险上限（覆盖值高于 Skill 上限时取更严者）")
    void overridesCannotLoosenRiskCeiling() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-a"),
                        "{\"lowerRiskCeiling\":\"WRITE_EXTERNAL\"}")
                .withSkill("skill-a", 1, CapabilityRiskLevel.READ, List.of());

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-1", 1);

        assertThat(closure.skill("skill-a").orElseThrow().riskCeiling())
                .as("⛔ 覆盖抬高了风险上限：overrides 只允许收紧（§4.2）")
                .isEqualTo(CapabilityRiskLevel.READ);
    }

    @Test
    @DisplayName("✅ §4.4 规范化：server.allowlist=null → 取 ManifestOps 全集")
    void serverAllowlistNullMeansFullManifest() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-a"), "{}")
                .withSkill("skill-a", 1, CapabilityRiskLevel.WRITE_EXTERNAL, List.of(),
                        List.of(new CapSkillDef.McpDeclaration("mcp-1", null)), "[]")
                .withServer("mcp-1", 1, null)
                .withManifest("mcp-1", "op-a", "op-b")
                .withTool("tool-mcp", 1, CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                        "mcp-1", "op-a", "[]")
                .withTool("tool-mcp2", 1, CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                        "mcp-1", "op-b", "[]");

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-1", 1);

        assertThat(closure.mcpBinding("skill-a", "mcp-1").orElseThrow().allowedOps())
                .as("⛔ null 未按「取清单全集」规范化")
                .containsExactly("op-a", "op-b");
    }

    @Test
    @DisplayName("✅ §4.4 规范化：server.allowlist=[] → 显式空集（与 null 语义不同）")
    void serverAllowlistEmptyMeansNothing() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-a"), "{}")
                .withSkill("skill-a", 1, CapabilityRiskLevel.WRITE_EXTERNAL, List.of(),
                        List.of(new CapSkillDef.McpDeclaration("mcp-1", null)), "[]")
                .withServer("mcp-1", 1, List.of())
                .withManifest("mcp-1", "op-a")
                .withTool("tool-mcp", 1, CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                        "mcp-1", "op-a", "[]");

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-1", 1);

        assertThat(closure.mcpBinding("skill-a", "mcp-1").orElseThrow().allowedOps())
                .as("⛔ 显式空清单被当成「未配置」：null≠[] 的语义被抹平")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ §4.4 交集：server.allowlist=L → L ∩ ManifestOps")
    void serverAllowlistIntersectsManifest() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-a"), "{}")
                .withSkill("skill-a", 1, CapabilityRiskLevel.WRITE_EXTERNAL, List.of(),
                        List.of(new CapSkillDef.McpDeclaration("mcp-1", null)), "[]")
                .withServer("mcp-1", 1, List.of("op-a", "op-gone"))
                .withManifest("mcp-1", "op-a", "op-b")
                .withTool("tool-mcp", 1, CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                        "mcp-1", "op-a", "[]");

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-1", 1);

        assertThat(closure.mcpBinding("skill-a", "mcp-1").orElseThrow().allowedOps())
                .as("⛔ 未与 ManifestOps 求交：清单已下线的操作被放行")
                .containsExactly("op-a");
    }

    @Test
    @DisplayName("✅ §4.4 交集：skill.toolAllowlist=L → L ∩ ServerAllowedOps")
    void skillAllowlistIntersectsServerAllowed() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-a"), "{}")
                .withSkill("skill-a", 1, CapabilityRiskLevel.WRITE_EXTERNAL, List.of(),
                        List.of(new CapSkillDef.McpDeclaration("mcp-1", List.of("op-a", "op-b"))),
                        "[]")
                .withServer("mcp-1", 1, List.of("op-a"))
                .withManifest("mcp-1", "op-a", "op-b")
                .withTool("tool-mcp", 1, CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                        "mcp-1", "op-a", "[]");

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-1", 1);

        assertThat(closure.mcpBinding("skill-a", "mcp-1").orElseThrow().allowedOps())
                .as("⛔ 未与 ServerAllowedOps 求交：Skill 白名单被放大")
                .containsExactly("op-a");
    }

    @Test
    @DisplayName("⛔ ForcedSafety：MCP 操作风险超上限 → 该操作被剔除")
    void forcedSafetyRemovesOverCeilingOp() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-a"), "{}")
                .withSkill("skill-a", 1, CapabilityRiskLevel.READ, List.of(),
                        List.of(new CapSkillDef.McpDeclaration("mcp-1", null)), "[]")
                .withServer("mcp-1", 1, null)
                .withManifest("mcp-1", "op-a")
                .withTool("tool-mcp", 1, CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                        "mcp-1", "op-a", "[]");

        EffectiveClosure closure = resolver(fixture).resolve(TENANT, "agent-1", 1);

        assertThat(closure.mcpBinding("skill-a", "mcp-1").orElseThrow().allowedOps())
                .as("⛔ 风险超限的 MCP 操作仍在允许集：风险上限对 MCP 形同虚设")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 确定性：同样输入产出逐字段相同的闭包")
    void closureIsDeterministic() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-b", "skill-a"), "{}")
                .withSkill("skill-a", 1, CapabilityRiskLevel.READ, List.of("tool-a", "tool-b"))
                .withSkill("skill-b", 1, CapabilityRiskLevel.READ, List.of())
                .withTool("tool-a", 1, CapabilityRiskLevel.READ, "[]")
                .withTool("tool-b", 1, CapabilityRiskLevel.READ, "[]");

        PermissionResolver resolver = resolver(fixture);
        EffectiveClosure first = resolver.resolve(TENANT, "agent-1", 1);
        EffectiveClosure second = resolver.resolve(TENANT, "agent-1", 1);

        assertThat(first).isEqualTo(second);
        assertThat(first.skills().stream().map(EffectiveClosure.SkillBinding::skillId))
                .as("⛔ 闭包顺序不确定：同一依赖集合会算出不同指纹")
                .containsExactly("skill-a", "skill-b");
    }

    @Test
    @DisplayName("⛔ overrides 结构非法 → 解析失败（不得静默忽略）")
    void malformedOverridesFailClosed() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, "agent-1", 1, List.of("skill-a"), "{not-json")
                .withSkill("skill-a", 1, CapabilityRiskLevel.READ, List.of());

        assertThatThrownBy(() -> resolver(fixture).resolve(TENANT, "agent-1", 1))
                .isInstanceOf(ClosureResolutionException.class);
    }
}
