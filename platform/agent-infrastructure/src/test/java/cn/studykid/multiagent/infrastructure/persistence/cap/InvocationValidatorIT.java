package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.application.cap.CapDualWriteService;
import cn.studykid.multiagent.application.cap.CapDualWriteSwitch;
import cn.studykid.multiagent.application.cap.ClosureFingerprint;
import cn.studykid.multiagent.application.cap.InvocationValidator;
import cn.studykid.multiagent.application.cap.PermissionResolver;
import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.mcp.McpManifestPort;
import cn.studykid.multiagent.ports.security.RolePermissionPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase A 授权校验的<b>真实 PG</b> 集成测试（P1-T3/T8 · §5.3 / §5.5 / §9.1）。
 *
 * <p>与单元测试的分工：单元测试锁「判定逻辑」，本类锁「<b>数据真的从库里来</b>」：
 * <ul>
 *   <li>authzVersion 必须来自 <b>DB 强一致读</b>（§5.5），
 *       而非进程内内存 —— 否则「另一个实例撤权」会被静默忽略；</li>
 *   <li>闭包必须由 {@code cap_*_def} 的<b>不可变版本行</b>解析而来；</li>
 *   <li>租户隔离必须在 <b>非特权角色</b>下成立（RLS 真伪只能这样验）。</li>
 * </ul>
 *
 * <p>⚠️ 本类继承 {@link CapITBase}：应用连接用非特权角色 {@code app_agent}
 * （NOSUPERUSER NOBYPASSRLS）—— 超级用户会绕过 RLS，验出来的「隔离生效」是假的。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class InvocationValidatorIT extends CapITBase {

    private static final String SKILL_ID = "it-cap-skill";
    private static final String TOOL_ID = "it-cap-tool";
    private static final String PRINCIPAL = "it-cap-principal";

    /** 固定清单（P4 落地前的测试替身）：本阶段只测 native 工具。 */
    private static final McpManifestPort EMPTY_MANIFEST =
            serverId -> McpManifestPort.ManifestSnapshot.available(Set.of());

    // ── 夹具 ──────────────────────────────────────────────────────

    private void seedPlatformDefs() {
        adminExec("INSERT INTO cap_skill_def (id, version, name, declared_tools, declared_mcp,"
                + " risk_ceiling, param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + SKILL_ID + "', 1, 'it skill', '[\"" + TOOL_ID + "\"]', '[]'::jsonb,"
                + " 'WRITE_EXTERNAL', '[]'::jsonb, 'platform', 'fp-skill', now())");
        adminExec("INSERT INTO cap_tool_def (id, version, name, kind, risk_level, input_schema,"
                + " param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + TOOL_ID + "', 1, 'it tool', 'native', 'READ', '{}'::jsonb,"
                + " '[]'::jsonb, 'platform', 'fp-tool', now())");
        // ⚠️ P5：当前生效版本只由**发布指针**决定（§6.3 第 5 条），
        //    仅插版本行不足以让对象可解析 —— 必须同时发布到指针上。
        publishPlatformObjects("tool:" + TOOL_ID + "@1", "skill:" + SKILL_ID + "@1");
    }

    /**
     * 建 Agent（legacy 行 + cap_agent_def 行）。
     *
     * @return agentId
     */
    private String seedAgent(String tenantId, String enabledSkillsJson) {
        String agentId = createAgentWithVersion(tenantId, uniqueName("it-agent"), "{}", true);
        adminExec("INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, roles,"
                + " enabled_skills, overrides, config_fingerprint, published_at)"
                + " VALUES ('" + tenantId + "'::uuid, '" + agentId + "'::uuid, 1, 'it agent',"
                + " '[\"TEACHER\"]'::jsonb, '" + enabledSkillsJson + "'::jsonb, '{}'::jsonb,"
                + " 'fp-agent', now())");
        return agentId;
    }

    /**
     * 建 Run（execution_capability_snapshot 对 run 有复合外键，必须有真实 run 行）。
     *
     * @return runId
     */
    private String seedRun(String tenantId, String agentId) {
        String snapshotId = createExecutionSnapshot(tenantId, agentId);
        return createRun(tenantId, agentId, snapshotId);
    }

    private PermissionResolver resolver() {
        return new PermissionResolver(
                new JdbcCapAgentDefRepository(appPool),
                new JdbcCapSkillDefRepository(appPool),
                new JdbcCapToolDefRepository(appPool),
                new JdbcCapMcpServerDefRepository(appPool),
                new JdbcPublishedVersionPort(appPool),
                EMPTY_MANIFEST);
    }

    private InvocationValidator validator() {
        return new InvocationValidator(
                new JdbcAuthzVersionRepository(appPool),
                ROLE_STUB,
                resolver(),
                new JdbcCapabilitySnapshotRepository(appPool),
                EMPTY_MANIFEST);
    }

    /** 角色权限测试替身：只放行 TEACHER 的 tool:invoke。 */
    private static final RolePermissionPort ROLE_STUB = (roles, action) ->
            roles.contains("TEACHER") && "tool:invoke".equals(action);

    private void seedAuthzVersion(String tenantId, long version) {
        adminExec("INSERT INTO cap_authz_version (tenant_id, principal_id, authz_version)"
                + " VALUES ('" + tenantId + "'::uuid, '" + PRINCIPAL + "', " + version + ")");
    }

    private void writeSnapshot(String tenantId, String runId, String agentId,
                                EffectiveClosure closure) {
        new JdbcCapabilitySnapshotRepository(appPool).save(new CapabilitySnapshot(
                runId, tenantId, agentId, ClosureFingerprint.canonicalJson(closure), 1, 0L,
                ClosureFingerprint.of(closure), 1L, Instant.now()));
    }

    private InvocationValidator.InvocationRequest request(String tenantId, String agentId,
                                                            String runId, long authzVersion) {
        return new InvocationValidator.InvocationRequest(
                new TenantContext(tenantId, PRINCIPAL, Set.of("TEACHER"), authzVersion),
                agentId, SKILL_ID, TOOL_ID, Map.of(), runId, null);
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 端到端：真实库闭包 + 真实指纹一致 → ALLOW")
    void allowsAuthorizedInvocationOnRealDatabase() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);

        PermissionResolver resolver = resolver();
        EffectiveClosure closure = resolver.resolve(tenantA, agentId, 1);
        assertThat(closure.skill(SKILL_ID)).isPresent();
        assertThat(closure.tool(TOOL_ID)).isPresent();
        writeSnapshot(tenantA, runId, agentId, closure);

        AuthorizationDecision decision = validator()
                .validate(request(tenantA, agentId, runId, 1L));

        assertThat(decision.authorization()).isEqualTo(AuthorizationDecision.Authorization.ALLOW);
        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.SUCCESS);
    }

    @Test
    @DisplayName("⛔ S3 主体撤权：DB 版本递增后上下文变陈旧 → AUTHZ_STALE")
    void revokedPrincipalIsDeniedByDatabaseVersion() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        PermissionResolver resolver = resolver();
        writeSnapshot(tenantA, runId, agentId, resolver.resolve(tenantA, agentId, 1));

        // 撤权：DB 原子自增（§5.5「所有改变授权结果的变更与版本递增同一事务」）
        long bumped = new JdbcAuthzVersionRepository(appPool).bump(tenantA, PRINCIPAL);
        assertThat(bumped).isEqualTo(2L);

        AuthorizationDecision decision = validator()
                .validate(request(tenantA, agentId, runId, 1L));

        assertThat(decision.authorization()).isEqualTo(AuthorizationDecision.Authorization.DENY);
        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.AUTHZ_STALE);
    }

    @Test
    @DisplayName("⛔ S3d 无授权版本记录 → AUTHZ_UNAVAILABLE（fail-closed）")
    void missingAuthzVersionIsDenied() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        // 故意不写 cap_authz_version

        AuthorizationDecision decision = validator()
                .validate(request(tenantA, agentId, runId, 1L));

        assertThat(decision.reasonCode())
                .as("⛔ 读不到授权版本却未 fail-closed")
                .isEqualTo(ReasonCode.AUTHZ_UNAVAILABLE);
    }

    @Test
    @DisplayName("⛔ S5a Tool 重发 → 闭包指纹变化 → CONFIG_FINGERPRINT_STALE")
    void republishedToolInvalidatesFingerprint() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        PermissionResolver resolver = resolver();
        writeSnapshot(tenantA, runId, agentId, resolver.resolve(tenantA, agentId, 1));

        // Tool 重发为 version 2
        adminExec("INSERT INTO cap_tool_def (id, version, name, kind, risk_level, input_schema,"
                + " param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + TOOL_ID + "', 2, 'it tool', 'native', 'READ', '{}'::jsonb,"
                + " '[]'::jsonb, 'platform', 'fp-tool-v2', now())");
        // Tool 重发 = 新版本 + **新的发布头 + 指针前移**（§6.3 原子发布）
        publishPlatformObjects("tool:" + TOOL_ID + "@2", "skill:" + SKILL_ID + "@1");

        AuthorizationDecision decision = validator()
                .validate(request(tenantA, agentId, runId, 1L));

        assertThat(decision.reasonCode())
                .as("⛔ 依赖对象重发未被感知：失效检测失效")
                .isEqualTo(ReasonCode.CONFIG_FINGERPRINT_STALE);
    }

    @Test
    @DisplayName("⛔ 租户隔离：跨租户读不到别家 Agent 的闭包 → RESOURCE_UNKNOWN")
    void crossTenantAgentIsNotResolvable() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        // B 租户的主体也必须有授权版本 —— 否则会先在「主体授权」步被拒，
        // 根本走不到闭包解析，测的就不是 RLS 了。
        seedAuthzVersion(tenantB, 1L);

        // 以 B 租户身份解析 A 租户的 Agent：RLS 过滤 → 读不到 → 拒绝
        AuthorizationDecision decision = validator()
                .validate(request(tenantB, agentId, runId, 1L));

        assertThat(decision.authorization()).isEqualTo(AuthorizationDecision.Authorization.DENY);
        assertThat(decision.reasonCode())
                .as("⛔ 跨租户读取未被 RLS 拦截")
                .isEqualTo(ReasonCode.RESOURCE_UNKNOWN);
    }

    @Test
    @DisplayName("✅ 双写快照：真实写入 execution_capability_snapshot 且指纹可复算")
    void dualWriteProducesRealCapabilitySnapshot() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 3L);

        CapDualWriteService dualWrite = new CapDualWriteService(
                new CapDualWriteSwitch(true),
                new JdbcCapAgentDefRepository(appPool),
                resolver(),
                new JdbcAuthzVersionRepository(appPool),
                new JdbcCapabilitySnapshotRepository(appPool),
                java.time.Clock.systemUTC());
        dualWrite.onExecutionSnapshotCreated(tenantA, runId, agentId, 1, PRINCIPAL);

        var snapshot = new JdbcCapabilitySnapshotRepository(appPool).find(tenantA, runId);
        assertThat(snapshot).as("⛔ 双写未产出执行能力快照").isPresent();
        assertThat(snapshot.orElseThrow().configFingerprint())
                .as("⛔ 快照指纹不是真实 §6.4 指纹")
                .startsWith(ClosureFingerprint.PREFIX);
        assertThat(snapshot.orElseThrow().authzVersion())
                .as("⛔ 快照未记录生成时的授权版本")
                .isEqualTo(3L);

        // 指纹可复算：同一闭包重算得到同一值
        EffectiveClosure closure = resolver().resolve(tenantA, agentId, 1);
        assertThat(ClosureFingerprint.of(closure))
                .isEqualTo(snapshot.orElseThrow().configFingerprint());
    }

    @Test
    @DisplayName("✅ 无关对象变更不触发本 Run 失效（§6.4）")
    void unrelatedObjectChangeDoesNotInvalidate() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        PermissionResolver resolver = resolver();
        writeSnapshot(tenantA, runId, agentId, resolver.resolve(tenantA, agentId, 1));

        // 与本 Run 无关的 Tool 被重发（不在闭包内）
        adminExec("INSERT INTO cap_tool_def (id, version, name, kind, risk_level, input_schema,"
                + " param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('it-cap-unrelated-tool', 9, 'unrelated', 'native', 'WRITE_EXTERNAL',"
                + " '{}'::jsonb, '[]'::jsonb, 'platform', 'fp-unrelated', now())");

        AuthorizationDecision decision = validator()
                .validate(request(tenantA, agentId, runId, 1L));

        assertThat(decision.authorization())
                .as("⛔ 无关对象变更导致在途 Run 失效：指纹范围过宽")
                .isEqualTo(AuthorizationDecision.Authorization.ALLOW);
    }

    @Test
    @DisplayName("✅ Skill 未启用 → SKILL_NOT_ENABLED（真实库）")
    void skillNotEnabledIsDeniedOnRealDatabase() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);

        AuthorizationDecision decision = validator()
                .validate(request(tenantA, agentId, runId, 1L));

        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.SKILL_NOT_ENABLED);
        assertThat(decision.execution()).isEqualTo(AuthorizationDecision.Execution.NOT_EVALUATED);
        assertThat(decision.status()).isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
    }

    @Test
    @DisplayName("✅ 闭包只含依赖对象（未启用的 Skill 不产生 Tool 绑定）")
    void closureContainsOnlyEnabledSkills() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");

        EffectiveClosure closure = resolver().resolve(tenantA, agentId, 1);

        assertThat(closure.skills()).hasSize(1);
        assertThat(closure.tools()).extracting(EffectiveClosure.ToolBinding::toolId)
                .containsExactly(TOOL_ID);
    }

    @Test
    @DisplayName("✅ Skill 声明的 Tool 关系进入闭包（供 SkillToolAllowed 判定）")
    void closureKeepsSkillToolEdges() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");

        EffectiveClosure closure = resolver().resolve(tenantA, agentId, 1);

        assertThat(closure.skill(SKILL_ID).orElseThrow().declaredTools())
                .containsExactly(TOOL_ID);
    }

    @Test
    @DisplayName("✅ 平台级定义对所有租户均可见（platform 作用域不设 RLS）")
    void platformDefinitionsAreVisibleToAllTenants() {
        seedPlatformDefs();

        assertThat(new JdbcCapSkillDefRepository(appPool).findLatestPublished(SKILL_ID))
                .as("⛔ 平台级 Skill 定义读不到：闭包解析会整体失败")
                .isPresent();
        assertThat(new JdbcCapToolDefRepository(appPool).findLatestPublished(TOOL_ID))
                .isPresent();
    }

    // ── S3a 真跨实例 ──────────────────────────────────────────────

    @Test
    @DisplayName("⛔ S3a 跨实例撤权：A 实例提交后，B 实例（独立连接池）立即判 AUTHZ_STALE")
    void crossInstanceRevocationIsDeniedImmediately() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        PermissionResolver resolver = resolver();
        writeSnapshot(tenantA, runId, agentId, resolver.resolve(tenantA, agentId, 1));

        // ⚠️ **实例 A**：提交撤权（authzVersion 原子自增）
        long bumped = new JdbcAuthzVersionRepository(appPool).bump(tenantA, PRINCIPAL);
        assertThat(bumped).isEqualTo(2L);

        // ⚠️ **实例 B**：全新独立连接池 —— 与 A 无任何进程内共享状态。
        //    若版本被缓存在实例内存里，B 读到的仍是旧版本 → 撤权跨实例失效。
        try (com.zaxxer.hikari.HikariDataSource instanceB = newInstancePool()) {
            InvocationValidator validatorB = new InvocationValidator(
                    new JdbcAuthzVersionRepository(instanceB),
                    ROLE_STUB,
                    resolver(),
                    new JdbcCapabilitySnapshotRepository(appPool),
                    EMPTY_MANIFEST);

            AuthorizationDecision decision = validatorB
                    .validate(request(tenantA, agentId, runId, 1L));

            assertThat(decision.authorization())
                    .as("⛔ B 实例未感知 A 实例的撤权：跨实例撤权失效（旧上下文仍能调用）")
                    .isEqualTo(AuthorizationDecision.Authorization.DENY);
            assertThat(decision.reasonCode())
                    .isEqualTo(ReasonCode.AUTHZ_STALE);
            assertThat(decision.execution())
                    .isEqualTo(AuthorizationDecision.Execution.NOT_EVALUATED);
            assertThat(decision.status())
                    .isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
        }
    }

    // ── S3d 存储真不可用 ──────────────────────────────────────────

    @Test
    @DisplayName("⛔ S3d 授权存储真不可用（连不上）→ fail-closed AUTHZ_UNAVAILABLE")
    void unreachableAuthzStoreFailsClosed() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);

        // ⚠️ 授权版本读指向一个**必然连不上**的地址：
        //    这不是「记录不存在」，而是真正的存储不可用。
        InvocationValidator validatorWithBrokenStore = new InvocationValidator(
                new JdbcAuthzVersionRepository(unreachableDataSource()),
                ROLE_STUB,
                resolver(),
                new JdbcCapabilitySnapshotRepository(appPool),
                EMPTY_MANIFEST);

        AuthorizationDecision decision = validatorWithBrokenStore
                .validate(request(tenantA, agentId, runId, 1L));

        assertThat(decision.authorization())
                .as("⛔ 存储不可用却未拒绝：无法确认授权状态时放行 = 直接越权")
                .isEqualTo(AuthorizationDecision.Authorization.DENY);
        assertThat(decision.reasonCode())
                .as("⛔ 存储不可用的原因码不稳定：运维无法区分「服务故障」与「配置错误」")
                .isEqualTo(ReasonCode.AUTHZ_UNAVAILABLE);
        assertThat(decision.execution())
                .isEqualTo(AuthorizationDecision.Execution.NOT_EVALUATED);
        assertThat(decision.status())
                .isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
    }

    // ── S3g 单次调用配置有效性 / 新调用只见完整已发布配置 ───────────

    @Test
    @DisplayName("✅ S3g-a 单次调用配置有效性：同一次解析内的对象版本自洽")
    void singleCallSeesSelfConsistentConfiguration() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);

        PermissionResolver resolver = resolver();
        EffectiveClosure closure = resolver.resolve(tenantA, agentId, 1);

        // ⚠️ 「配置有效性」= 一次调用内解析到的对象集合必须**自洽**：
        //    不得出现「Skill 是 v1 而它声明的 Tool 是另一个版本」这类撕裂。
        assertThat(closure.skill(SKILL_ID)).isPresent();
        assertThat(closure.tool(TOOL_ID))
                .as("⛔ 闭包里没有它声明的 Tool：配置集合不完整")
                .isPresent();
        assertThat(closure.tool(TOOL_ID).orElseThrow().version())
                .as("⛔ 闭包解析到的 Tool 版本与当前发布头不一致：一次调用内配置被撕裂")
                .isEqualTo(1);

        // 同一份配置重复解析必须得到同一指纹（可复现性 = 有效性）
        assertThat(ClosureFingerprint.of(closure))
                .as("⛔ 同一配置两次解析得到不同指纹：在途 Run 会被无故判失效")
                .isEqualTo(ClosureFingerprint.of(resolver.resolve(tenantA, agentId, 1)));
    }

    @Test
    @DisplayName("✅ S3g-b 新调用只见完整已发布配置：发布后新调用立刻看到新版本")
    void newCallSeesCompleteNewConfigurationAfterPublish() {
        seedPlatformDefs();
        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        PermissionResolver resolver = resolver();
        writeSnapshot(tenantA, runId, agentId, resolver.resolve(tenantA, agentId, 1));

        // 发布新版本：Tool → v2，Skill 仍 v1（一次原子发布）
        adminExec("INSERT INTO cap_tool_def (id, version, name, kind, risk_level, input_schema,"
                + " param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + TOOL_ID + "', 2, 'it tool v2', 'native', 'READ', '{}'::jsonb,"
                + " '[]'::jsonb, 'platform', 'fp-tool-v2', now())");
        publishPlatformObjects("tool:" + TOOL_ID + "@2", "skill:" + SKILL_ID + "@1");

        // ⚠️ **新调用**：按当前发布头解析 → 立刻看到 v2
        EffectiveClosure newClosure = resolver.resolve(tenantA, agentId, 1);
        assertThat(newClosure.tool(TOOL_ID).orElseThrow().version())
                .as("⛔ 发布后新调用仍解析到旧版本：当前生效版本不是由发布指针决定的")
                .isEqualTo(2);
        assertThat(newClosure.skill(SKILL_ID).orElseThrow().version())
                .as("⛔ 发布后 Skill 版本丢失：新配置不完整（半成品可见）")
                .isEqualTo(1);

        // ⚠️ 而**在途 Run 的旧快照**必须被判失效 —— 这证明新旧配置没有混在一起：
        //    「新调用只见完整新配置」的前提是旧配置不再被当作当前配置。
        AuthorizationDecision decision = validator()
                .validate(request(tenantA, agentId, runId, 1L));
        assertThat(decision.reasonCode())
                .as("⛔ 旧快照在配置发布后仍然有效：新旧配置被混用")
                .isEqualTo(ReasonCode.CONFIG_FINGERPRINT_STALE);
    }
}
