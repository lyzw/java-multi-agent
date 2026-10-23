package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.McpTrustTier;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.tenant.TenantContext;

/**
 * MCP 具名安全行为测试（P4-T8 · §9.1 S5a/S5b/S5c · §5.8 · 退出门禁 3/4）。
 *
 * <p>⚠️ <b>本类断言的是「四字段」而非「成功/失败」</b>（§8.3 冻结门槛）：
 * {@code authorizationDecision} / {@code executionDecision} / {@code executionStatus} /
 * {@code reasonCode} 必须<b>逐字段</b>匹配。
 * 只断言「拒绝了」会漏掉「拒绝理由对了、但字段分类错了」这类错误
 * （例如把 Phase A 的拒绝写成 Phase B 的 DENY）。
 *
 * <p>⚠️ <b>退出门禁 3：同一类变化不得产生两种原因码</b>：
 * 本类对「清单漂移」「清单不可用」「端点不安全」分别反复判定，
 * 断言同一情形恒得同一码。
 */
class McpSecurityBehaviorTest {

    private static final String TENANT = "t-mcp-sec";
    private static final String PRINCIPAL = "user-1";
    private static final String RUN = "run-mcp-sec";
    private static final String AGENT = "agent-mcp";
    private static final String SKILL = "skill-mcp";
    private static final String SERVER = "mcp-1";
    private static final String TOOL = "tool-mcp";
    private static final String TOOL2 = "tool-mcp-2";
    private static final String OP = "op-a";

    // ── 夹具 ──────────────────────────────────────────────────────

    private static CapFixture mcpFixture() {
        return new CapFixture()
                .withAgent(TENANT, AGENT, 1, List.of(SKILL), "{}")
                .withSkill(SKILL, 1, CapabilityRiskLevel.WRITE_EXTERNAL, List.of(),
                        List.of(new CapSkillDef.McpDeclaration(SERVER, List.of(OP))), "[]")
                .withServer(SERVER, 1, null)
                .withManifest(SERVER, OP)
                .withTool(TOOL, 1, CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                        SERVER, OP, "[]")
                // ⚠️ 第二个工具绑定 op-b：Skill 的 toolAllowlist 只有 op-a，
                //    故它**不在**允许集内（S5b 用例的越界目标）
                .withTool(TOOL2, 1, CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                        SERVER, "op-b", "[]")
                .withAuthzVersion(TENANT, PRINCIPAL, 1L)
                .withRolePermission("TEACHER", "tool:invoke");
    }

    private static PermissionResolver resolver(CapFixture fixture) {
        return new PermissionResolver(fixture.agentDefs(), fixture.skillDefs(), fixture.toolDefs(),
                fixture.serverDefs(), fixture.publishedVersions(), fixture.manifest());
    }

    private static InvocationValidator validator(CapFixture fixture) {
        return new InvocationValidator(fixture.authzVersions(), fixture.rolePermissions(),
                resolver(fixture), fixture.snapshots(), fixture.manifest());
    }

    private static void withSnapshot(CapFixture fixture) {
        PermissionResolver resolver = resolver(fixture);
        EffectiveClosure closure = resolver.resolve(TENANT, AGENT, 1);
        fixture.withSnapshot(new CapabilitySnapshot(RUN, TENANT, AGENT,
                ClosureFingerprint.canonicalJson(closure), 1, 0L,
                ClosureFingerprint.of(closure), 1L, Instant.EPOCH));
    }

    private static InvocationValidator.InvocationRequest mcpRequest(String toolId, String op) {
        return new InvocationValidator.InvocationRequest(
                new TenantContext(TENANT, PRINCIPAL, Set.of("TEACHER"), 1L),
                AGENT, SKILL, toolId, Map.of(), RUN,
                new InvocationValidator.McpTarget(SERVER, op));
    }

    /** 逐字段断言（§9.1 四字段）。 */
    private static void assertFourFields(AuthorizationDecision decision,
                                         AuthorizationDecision.Authorization authz,
                                         AuthorizationDecision.Execution execution,
                                         AuthorizationDecision.Status status,
                                         ReasonCode reasonCode) {
        assertThat(decision.authorization())
                .as("四字段之一：authorizationDecision").isEqualTo(authz);
        assertThat(decision.execution())
                .as("四字段之二：executionDecision").isEqualTo(execution);
        assertThat(decision.status())
                .as("四字段之三：executionStatus").isEqualTo(status);
        assertThat(decision.reasonCode())
                .as("四字段之四：reasonCode").isEqualTo(reasonCode);
    }

    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("§9.1 S5a/S5b/S5c 具名用例")
    class NamedScenarios {

        @Test
        @DisplayName("⛔ S5a 闭包指纹变化 → DENY / NOT_EVALUATED / NOT_STARTED / CONFIG_FINGERPRINT_STALE")
        void s5aClosureFingerprintChanged() {
            CapFixture fixture = mcpFixture();
            withSnapshot(fixture);
            // MCP 工具重发新版本 → 闭包内容变化 → 指纹失效
            fixture.withTool(TOOL, 2, CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                    SERVER, OP, "[]");

            AuthorizationDecision decision = validator(fixture).validate(mcpRequest(TOOL, OP));

            assertFourFields(decision, AuthorizationDecision.Authorization.DENY,
                    AuthorizationDecision.Execution.NOT_EVALUATED,
                    AuthorizationDecision.Status.NOT_STARTED,
                    ReasonCode.CONFIG_FINGERPRINT_STALE);
        }

        @Test
        @DisplayName("⛔ S5b 操作级越界 → DENY / NOT_EVALUATED / NOT_STARTED / MCP_OP_NOT_ALLOWED")
        void s5bOperationOutsideAllowlist() {
            CapFixture fixture = mcpFixture();
            withSnapshot(fixture);

            // 请求的是绑定 op-b 的工具，但 Skill 的 toolAllowlist 只有 op-a
            AuthorizationDecision decision = validator(fixture).validate(mcpRequest(TOOL2, "op-b"));

            assertFourFields(decision, AuthorizationDecision.Authorization.DENY,
                    AuthorizationDecision.Execution.NOT_EVALUATED,
                    AuthorizationDecision.Status.NOT_STARTED,
                    ReasonCode.MCP_OP_NOT_ALLOWED);
        }

        @Test
        @DisplayName("⛔ S5c 清单下线 → CONFIG_FINGERPRINT_STALE（清单变更已纳入指纹，统一用此码）")
        void s5cManifestRemoved() {
            CapFixture fixture = mcpFixture();
            withSnapshot(fixture);
            fixture.manifests.put(SERVER, Set.of());

            AuthorizationDecision decision = validator(fixture).validate(mcpRequest(TOOL, OP));

            assertFourFields(decision, AuthorizationDecision.Authorization.DENY,
                    AuthorizationDecision.Execution.NOT_EVALUATED,
                    AuthorizationDecision.Status.NOT_STARTED,
                    ReasonCode.CONFIG_FINGERPRINT_STALE);
        }

        @Test
        @DisplayName("⛔ S5c 清单在指纹重算与操作复核之间漂移 → MCP_TOOL_DRIFTED（§5.8 步骤 3）")
        void s5cToctouDrift() {
            CapFixture fixture = mcpFixture();
            // 快照生成 M1 → 指纹重算 M1 → 操作级复核 M2（op 已下线）
            fixture.manifestSequences.put(SERVER, List.of(Set.of(OP), Set.of(OP), Set.of()));
            withSnapshot(fixture);

            AuthorizationDecision decision = validator(fixture).validate(mcpRequest(TOOL, OP));

            assertFourFields(decision, AuthorizationDecision.Authorization.DENY,
                    AuthorizationDecision.Execution.NOT_EVALUATED,
                    AuthorizationDecision.Status.NOT_STARTED,
                    ReasonCode.MCP_TOOL_DRIFTED);
        }

        @Test
        @DisplayName("✅ 正向：MCP 操作在允许集内且清单在位 → ALLOW")
        void positiveMcpOperationIsAllowed() {
            CapFixture fixture = mcpFixture();
            withSnapshot(fixture);

            AuthorizationDecision decision = validator(fixture).validate(mcpRequest(TOOL, OP));

            assertThat(decision.isAllowed())
                    .as("⛔ 合法的 MCP 操作被拒")
                    .isTrue();
            // ⚠️ Phase A 的 allow 工厂固定产出 ALLOW / ALLOW / SUCCEEDED / SUCCESS
            //    （真实执行状态由 Phase B 决定，Phase A 不预判）
            assertFourFields(decision, AuthorizationDecision.Authorization.ALLOW,
                    AuthorizationDecision.Execution.ALLOW,
                    AuthorizationDecision.Status.SUCCEEDED,
                    ReasonCode.SUCCESS);
        }
    }

    @Nested
    @DisplayName("退出门禁 3：同一类变化只有一种原因码")
    class OneCodePerChangeClass {

        @Test
        @DisplayName("✅ 清单漂移恒为 CONFIG_FINGERPRINT_STALE（重复判定不换码）")
        void manifestDriftAlwaysSameCode() {
            for (int i = 0; i < 5; i++) {
                CapFixture fixture = mcpFixture();
                withSnapshot(fixture);
                fixture.manifests.put(SERVER, Set.of());

                AuthorizationDecision decision = validator(fixture).validate(mcpRequest(TOOL, OP));

                assertThat(decision.reasonCode())
                        .as("⛔ 第 %d 次判定的码变了 = 同一类变化产生两种码", i)
                        .isEqualTo(ReasonCode.CONFIG_FINGERPRINT_STALE);
            }
        }

        @Test
        @DisplayName("✅ 清单不可用恒为 CONFIG_FINGERPRINT_STALE（与「清单漂移」同一码，fail-closed）")
        void manifestUnavailableAlwaysSameCode() {
            for (int i = 0; i < 5; i++) {
                CapFixture fixture = mcpFixture();
                withSnapshot(fixture);
                fixture.unavailableManifests.add(SERVER);

                AuthorizationDecision decision = validator(fixture).validate(mcpRequest(TOOL, OP));

                assertThat(decision.reasonCode())
                        .as("⛔ 第 %d 次判定的码变了 = 同一类变化产生两种码", i)
                        .isEqualTo(ReasonCode.CONFIG_FINGERPRINT_STALE);
                assertThat(decision.isAllowed())
                        .as("⛔ 清单不可用却放行 = fail-open")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("✅ 端点不安全恒为 MCP_OP_NOT_ALLOWED（ForcedSafety 端点谓词生效）")
        void unsafeEndpointAlwaysSameCode() {
            for (int i = 0; i < 5; i++) {
                CapFixture fixture = new CapFixture()
                        .withAgent(TENANT, AGENT, 1, List.of(SKILL), "{}")
                        .withSkill(SKILL, 1, CapabilityRiskLevel.WRITE_EXTERNAL, List.of(),
                                List.of(new CapSkillDef.McpDeclaration(SERVER, List.of(OP))), "[]")
                        // ⚠️ endpoint 直指云元数据地址
                        .withServer(SERVER, 1, null, "http://169.254.169.254/mcp",
                                McpTrustTier.INTERNAL)
                        .withManifest(SERVER, OP)
                        .withTool(TOOL, 1, CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                                SERVER, OP, "[]")
                        .withAuthzVersion(TENANT, PRINCIPAL, 1L)
                        .withRolePermission("TEACHER", "tool:invoke");
                withSnapshot(fixture);

                AuthorizationDecision decision = validator(fixture).validate(mcpRequest(TOOL, OP));

                assertThat(decision.reasonCode())
                        .as("⛔ 第 %d 次判定的码变了", i)
                        .isEqualTo(ReasonCode.MCP_OP_NOT_ALLOWED);
                assertThat(decision.isAllowed())
                        .as("⛔ endpoint 指向元数据地址却放行 = SSRF")
                        .isFalse();
            }
        }
    }
}
