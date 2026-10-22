package cn.studykid.multiagent.application.cap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.tenant.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase A 全因子校验单测（P1-T3/T4/T5/T6/T7/T8 · §5.3 步骤 2–7 · §9.1）。
 *
 * <p>每个拒绝场景都<b>逐字段</b>断言（§9.1 的四字段要求）：
 * {@code authorizationDecision=DENY} / {@code executionDecision=NOT_EVALUATED} /
 * {@code executionStatus=NOT_STARTED} / 精确原因码。
 */
class InvocationValidatorTest {

    private static final String TENANT = "t-a";
    private static final String PRINCIPAL = "user-1";
    private static final String RUN = "run-1";
    private static final String AGENT = "agent-1";
    private static final String SKILL = "skill-a";
    private static final String TOOL = "tool-a";

    // ── 夹具 ──────────────────────────────────────────────────────

    private static CapFixture baseFixture() {
        return new CapFixture()
                .withAgent(TENANT, AGENT, 1, List.of(SKILL), "{}")
                .withSkill(SKILL, 1, CapabilityRiskLevel.WRITE_EXTERNAL, List.of(TOOL))
                .withTool(TOOL, 1, CapabilityRiskLevel.READ, "[]")
                .withAuthzVersion(TENANT, PRINCIPAL, 1L)
                .withRolePermission("TEACHER", "tool:invoke");
    }

    private static CapFixture mcpFixture() {
        return new CapFixture()
                .withAgent(TENANT, AGENT, 1, List.of(SKILL), "{}")
                .withSkill(SKILL, 1, CapabilityRiskLevel.WRITE_EXTERNAL, List.of(),
                        List.of(new CapSkillDef.McpDeclaration("mcp-1", List.of("op-a"))), "[]")
                .withServer("mcp-1", 1, null)
                .withManifest("mcp-1", "op-a")
                .withTool("tool-mcp", 1, CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                        "mcp-1", "op-a", "[]")
                .withTool("tool-mcp2", 1, CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                        "mcp-1", "op-b", "[]")
                .withAuthzVersion(TENANT, PRINCIPAL, 1L)
                .withRolePermission("TEACHER", "tool:invoke");
    }

    private static PermissionResolver resolver(CapFixture fixture) {
        return new PermissionResolver(fixture.agentDefs(), fixture.skillDefs(), fixture.toolDefs(),
                fixture.serverDefs(), fixture.publishedVersions(), fixture.manifest());
    }

    private static InvocationValidator validator(CapFixture fixture) {
        return validator(fixture, resolver(fixture));
    }

    private static InvocationValidator validator(CapFixture fixture, PermissionResolver resolver) {
        return new InvocationValidator(fixture.authzVersions(), fixture.rolePermissions(),
                resolver, fixture.snapshots(), fixture.manifest());
    }

    /** 生成与当前闭包一致的快照（否则指纹校验必然失败）。 */
    private static void withSnapshot(CapFixture fixture) {
        PermissionResolver resolver = resolver(fixture);
        EffectiveClosure closure = resolver.resolve(TENANT, AGENT, 1);
        fixture.withSnapshot(new CapabilitySnapshot(RUN, TENANT, AGENT,
                ClosureFingerprint.canonicalJson(closure), 1, 0L,
                ClosureFingerprint.of(closure), 1L, Instant.EPOCH));
    }

    private static InvocationValidator.InvocationRequest request(String skillId, String toolId) {
        return new InvocationValidator.InvocationRequest(
                new TenantContext(TENANT, PRINCIPAL, Set.of("TEACHER"), 1L),
                AGENT, skillId, toolId, Map.of(), RUN, null);
    }

    private static InvocationValidator.InvocationRequest mcpRequest(String toolId, String op) {
        return new InvocationValidator.InvocationRequest(
                new TenantContext(TENANT, PRINCIPAL, Set.of("TEACHER"), 1L),
                AGENT, SKILL, toolId, Map.of(), RUN,
                new InvocationValidator.McpTarget("mcp-1", op));
    }

    private static void assertDenied(AuthorizationDecision decision, ReasonCode expected) {
        assertThat(decision.authorization())
                .as("⛔ 授权结论不是 DENY")
                .isEqualTo(AuthorizationDecision.Authorization.DENY);
        assertThat(decision.execution())
                .as("⛔ 执行准入结论不是 NOT_EVALUATED（Phase A 未进入 Phase B）")
                .isEqualTo(AuthorizationDecision.Execution.NOT_EVALUATED);
        assertThat(decision.status())
                .as("⛔ 执行状态不是 NOT_STARTED")
                .isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
        assertThat(decision.reasonCode())
                .as("⛔ 原因码不符：%s", decision.reasonCode())
                .isEqualTo(expected);
    }

    // ── 正向路径 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 正向路径：全因子通过 → ALLOW + ALLOW + SUCCEEDED + SUCCESS")
    void allowsAuthorizedInvocation() {
        CapFixture fixture = baseFixture();
        withSnapshot(fixture);

        AuthorizationDecision decision = validator(fixture).validate(request(SKILL, TOOL));

        assertThat(decision.authorization()).isEqualTo(AuthorizationDecision.Authorization.ALLOW);
        assertThat(decision.execution()).isEqualTo(AuthorizationDecision.Execution.ALLOW);
        assertThat(decision.status()).isEqualTo(AuthorizationDecision.Status.SUCCEEDED);
        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.SUCCESS);
    }

    // ── S2 / S4a ──────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ S2 未启用 Skill → SKILL_NOT_ENABLED")
    void skillNotEnabledIsDenied() {
        CapFixture fixture = baseFixture();
        withSnapshot(fixture);

        AuthorizationDecision decision = validator(fixture).validate(request("skill-other", TOOL));

        assertDenied(decision, ReasonCode.SKILL_NOT_ENABLED);
    }

    @Test
    @DisplayName("⛔ S4a Skill 从 enabled 移除 → SKILL_NOT_ENABLED（重发后生效）")
    void skillRemovedFromEnabledIsDenied() {
        CapFixture fixture = baseFixture();
        withSnapshot(fixture);
        // Agent 重发：enabledSkills 里不再有该 Skill
        fixture.withAgent(TENANT, AGENT, 2, List.of(), "{}");

        AuthorizationDecision decision = validator(fixture).validate(request(SKILL, TOOL));

        assertDenied(decision, ReasonCode.SKILL_NOT_ENABLED);
    }

    // ── S3 / S3a / S3d ────────────────────────────────────────────

    @Test
    @DisplayName("⛔ S3 主体撤权 → AUTHZ_STALE（上下文版本落后于权威源）")
    void staleAuthzVersionIsDenied() {
        CapFixture fixture = baseFixture();
        withSnapshot(fixture);
        // 权威源版本已递增（撤权），而上下文仍带旧版本
        fixture.withAuthzVersion(TENANT, PRINCIPAL, 2L);

        AuthorizationDecision decision = validator(fixture).validate(request(SKILL, TOOL));

        assertDenied(decision, ReasonCode.AUTHZ_STALE);
    }

    @Test
    @DisplayName("⛔ S3a 跨实例撤权后调用 → AUTHZ_STALE（版本只从权威源读，不看本地缓存）")
    void crossInstanceRevocationIsDenied() {
        CapFixture fixture = baseFixture();
        withSnapshot(fixture);
        // 模拟「另一个实例执行了撤权」：本实例的权威源读到新版本
        fixture.withAuthzVersion(TENANT, PRINCIPAL, 5L);

        AuthorizationDecision decision = validator(fixture).validate(request(SKILL, TOOL));

        assertDenied(decision, ReasonCode.AUTHZ_STALE);
    }

    @Test
    @DisplayName("⛔ S3d 版本服务不可用 → AUTHZ_UNAVAILABLE（fail-closed，不得放行）")
    void authzUnavailableIsDenied() {
        CapFixture fixture = baseFixture();
        withSnapshot(fixture);
        fixture.authzUnavailable = true;

        AuthorizationDecision decision = validator(fixture).validate(request(SKILL, TOOL));

        assertDenied(decision, ReasonCode.AUTHZ_UNAVAILABLE);
    }

    @Test
    @DisplayName("⛔ 角色不足 → AUTHZ_ROLE_MISSING")
    void missingRoleIsDenied() {
        CapFixture fixture = baseFixture();
        withSnapshot(fixture);
        // 主体只有 VIEWER 角色，无 tool:invoke 权限
        fixture.withRolePermission("TEACHER", "run:read");

        AuthorizationDecision decision = validator(fixture).validate(request(SKILL, TOOL));

        assertDenied(decision, ReasonCode.AUTHZ_ROLE_MISSING);
    }

    // ── 顺序：主体授权最先 ────────────────────────────────────────

    @Test
    @DisplayName("⛔ 顺序契约：主体授权未通过时，**不得**解析闭包")
    void principalCheckHappensBeforeClosureResolution() {
        CapFixture fixture = baseFixture();
        withSnapshot(fixture);
        fixture.withAuthzVersion(TENANT, PRINCIPAL, 9L);

        CountingResolver resolver = new CountingResolver(fixture);
        AuthorizationDecision decision = validator(fixture, resolver).validate(request(SKILL, TOOL));

        assertDenied(decision, ReasonCode.AUTHZ_STALE);
        assertThat(resolver.calls)
                .as("⛔ 主体授权未通过仍解析了闭包：既泄露能力面，又让步骤顺序失去约束")
                .isZero();
    }

    // ── S4b / S4c / 未知风险 ──────────────────────────────────────

    @Test
    @DisplayName("⛔ S4b riskCeiling 收紧 → RISK_EXCEEDS_CEILING（且先于指纹判定）")
    void riskCeilingTightenedIsDenied() {
        // ⚠️ 工具风险必须**高于**收紧后的上限，否则不构成超限场景
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, AGENT, 1, List.of(SKILL), "{}")
                .withSkill(SKILL, 1, CapabilityRiskLevel.WRITE_EXTERNAL, List.of(TOOL))
                .withTool(TOOL, 1, CapabilityRiskLevel.WRITE_EXTERNAL, "[]")
                .withAuthzVersion(TENANT, PRINCIPAL, 1L)
                .withRolePermission("TEACHER", "tool:invoke");
        withSnapshot(fixture);
        // Skill 重发：riskCeiling 收紧到 READ，而 tool 风险是 WRITE_EXTERNAL
        fixture.withSkill(SKILL, 2, CapabilityRiskLevel.READ, List.of(TOOL));

        AuthorizationDecision decision = validator(fixture).validate(request(SKILL, TOOL));

        assertDenied(decision, ReasonCode.RISK_EXCEEDS_CEILING);
    }

    @Test
    @DisplayName("⛔ 未知风险 → RISK_LEVEL_UNKNOWN（**不降级为 WRITE_EXTERNAL**）")
    void unknownRiskIsDeniedWithDedicatedCode() {
        CapFixture fixture = new CapFixture()
                .withAgent(TENANT, AGENT, 1, List.of(SKILL), "{}")
                .withSkill(SKILL, 1, CapabilityRiskLevel.WRITE_EXTERNAL, List.of(TOOL))
                .withTool(TOOL, 1, CapabilityRiskLevel.UNKNOWN, "[]")
                .withAuthzVersion(TENANT, PRINCIPAL, 1L)
                .withRolePermission("TEACHER", "tool:invoke");
        withSnapshot(fixture);

        AuthorizationDecision decision = validator(fixture).validate(request(SKILL, TOOL));

        assertDenied(decision, ReasonCode.RISK_LEVEL_UNKNOWN);
        assertThat(decision.reasonCode())
                .as("⛔ 未知风险被报成超限：配置缺陷被误报成策略边界")
                .isNotEqualTo(ReasonCode.RISK_EXCEEDS_CEILING);
    }

    @Test
    @DisplayName("⛔ S4c 参数策略收紧 → PARAM_POLICY_FAIL")
    void paramPolicyTightenedIsDenied() {
        CapFixture fixture = baseFixture();
        withSnapshot(fixture);
        // Skill 重发：新增参数策略，要求 param=toDomain ∈ {school.edu}
        fixture.withSkill(SKILL, 2, CapabilityRiskLevel.WRITE_EXTERNAL, List.of(TOOL),
                List.of(), "[{\"param\":\"toDomain\",\"op\":\"in_allowlist\",\"value\":[\"school.edu\"]}]");

        AuthorizationDecision decision = validator(fixture).validate(request(SKILL, TOOL));

        assertDenied(decision, ReasonCode.PARAM_POLICY_FAIL);
    }

    @Test
    @DisplayName("⛔ Tool 未声明 → TOOL_NOT_DECLARED")
    void undeclaredToolIsDenied() {
        CapFixture fixture = baseFixture();
        withSnapshot(fixture);

        AuthorizationDecision decision = validator(fixture).validate(request(SKILL, "tool-other"));

        assertDenied(decision, ReasonCode.TOOL_NOT_DECLARED);
    }

    // ── S5a ───────────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ S5a 闭包指纹变化 → CONFIG_FINGERPRINT_STALE")
    void closureFingerprintChangedIsDenied() {
        CapFixture fixture = baseFixture();
        withSnapshot(fixture);
        // Tool 重发：版本变化 → 闭包内容变化 → 指纹变化
        fixture.withTool(TOOL, 2, CapabilityRiskLevel.READ, "[]");

        AuthorizationDecision decision = validator(fixture).validate(request(SKILL, TOOL));

        assertDenied(decision, ReasonCode.CONFIG_FINGERPRINT_STALE);
    }

    @Test
    @DisplayName("⛔ 缺失闭包快照 → RESOURCE_UNKNOWN（无法证明指纹有效）")
    void missingSnapshotIsDenied() {
        CapFixture fixture = baseFixture();
        // 故意不建快照

        AuthorizationDecision decision = validator(fixture).validate(request(SKILL, TOOL));

        assertDenied(decision, ReasonCode.RESOURCE_UNKNOWN);
    }

    @Test
    @DisplayName("⛔ Agent 不存在 → RESOURCE_UNKNOWN")
    void unknownAgentIsDenied() {
        CapFixture fixture = baseFixture();
        withSnapshot(fixture);

        AuthorizationDecision decision = validator(fixture)
                .validate(new InvocationValidator.InvocationRequest(
                        new TenantContext(TENANT, PRINCIPAL, Set.of("TEACHER"), 1L),
                        "agent-missing", SKILL, TOOL, Map.of(), RUN, null));

        assertDenied(decision, ReasonCode.RESOURCE_UNKNOWN);
    }

    // ── S5b / S5c：MCP 操作级 ─────────────────────────────────────

    @Test
    @DisplayName("⛔ S5b 操作级越界 → MCP_OP_NOT_ALLOWED（op 不在 Skill 白名单内）")
    void mcpOperationOutsideAllowlistIsDenied() {
        CapFixture fixture = mcpFixture();
        withSnapshot(fixture);

        AuthorizationDecision decision = validator(fixture).validate(mcpRequest("tool-mcp2", "op-b"));

        assertDenied(decision, ReasonCode.MCP_OP_NOT_ALLOWED);
    }

    @Test
    @DisplayName("⛔ Skill 未声明该 MCP server → MCP_NOT_BOUND")
    void mcpServerNotBoundIsDenied() {
        CapFixture fixture = mcpFixture();
        // 目标工具绑定在 Skill 未声明的 server 上 → 不进闭包
        fixture.withTool("tool-mcp3", 1, CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                "mcp-other", "op-x", "[]");
        withSnapshot(fixture);

        AuthorizationDecision decision = validator(fixture).validate(
                new InvocationValidator.InvocationRequest(
                        new TenantContext(TENANT, PRINCIPAL, Set.of("TEACHER"), 1L),
                        AGENT, SKILL, "tool-mcp3", Map.of(), RUN,
                        new InvocationValidator.McpTarget("mcp-other", "op-x")));

        assertDenied(decision, ReasonCode.MCP_NOT_BOUND);
    }

    @Test
    @DisplayName("⛔ S5c 清单下线 → CONFIG_FINGERPRINT_STALE（§5.8：清单变更已纳入指纹，统一用此码）")
    void mcpManifestDriftIsCaughtByFingerprint() {
        CapFixture fixture = mcpFixture();
        withSnapshot(fixture);
        // 快照之后清单发生变化（op-a 下线）
        fixture.manifests.put("mcp-1", Set.of());

        AuthorizationDecision decision = validator(fixture).validate(mcpRequest("tool-mcp", "op-a"));

        assertDenied(decision, ReasonCode.CONFIG_FINGERPRINT_STALE);
    }

    @Test
    @DisplayName("⛔ S5c 清单在指纹重算与操作复核之间漂移 → MCP_TOOL_DRIFTED（§5.8 步骤 3）")
    void mcpManifestToctouDriftIsDenied() {
        CapFixture fixture = mcpFixture();
        // 依次返回：快照生成时 M1 → 指纹重算时 M1 → 操作级复核时 M2（op 已下线）
        fixture.manifestSequences.put("mcp-1", List.of(Set.of("op-a"), Set.of("op-a"), Set.of()));
        withSnapshot(fixture);

        AuthorizationDecision decision = validator(fixture).validate(mcpRequest("tool-mcp", "op-a"));

        assertDenied(decision, ReasonCode.MCP_TOOL_DRIFTED);
    }

    @Test
    @DisplayName("⛔ 请求声明的 MCP 操作与闭包绑定不一致 → RESOURCE_VERSION_MISMATCH")
    void mcpTargetMismatchIsDenied() {
        CapFixture fixture = mcpFixture();
        withSnapshot(fixture);

        AuthorizationDecision decision = validator(fixture).validate(
                new InvocationValidator.InvocationRequest(
                        new TenantContext(TENANT, PRINCIPAL, Set.of("TEACHER"), 1L),
                        AGENT, SKILL, "tool-mcp", Map.of(), RUN,
                        new InvocationValidator.McpTarget("mcp-1", "op-b")));

        assertDenied(decision, ReasonCode.RESOURCE_VERSION_MISMATCH);
    }

    /** 计数型解析器：用于断言「主体授权最先」这一顺序契约。 */
    private static final class CountingResolver extends PermissionResolver {
        int calls;

        CountingResolver(CapFixture fixture) {
            super(fixture.agentDefs(), fixture.skillDefs(), fixture.toolDefs(),
                    fixture.serverDefs(), fixture.publishedVersions(), fixture.manifest());
        }

        @Override
        public @NonNull EffectiveClosure resolveCurrent(@NonNull String tenantId,
                                                         @NonNull String agentId) {
            calls++;
            return super.resolveCurrent(tenantId, agentId);
        }
    }
}
