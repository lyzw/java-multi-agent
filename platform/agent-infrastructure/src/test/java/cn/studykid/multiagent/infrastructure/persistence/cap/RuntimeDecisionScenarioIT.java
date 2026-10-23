package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.application.cap.ClosureFingerprint;
import cn.studykid.multiagent.application.cap.InvocationValidator;
import cn.studykid.multiagent.application.cap.PermissionResolver;
import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.mcp.TransportMcpManifestPort;
import cn.studykid.multiagent.ports.mcp.McpManifestPort;
import cn.studykid.multiagent.ports.mcp.McpTransportPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.security.RolePermissionPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §9.1 运行期四字段的<b>真实 PG</b> 集成测试（W-PERM-P6R2 · 消解 P6 限制 #3）。
 *
 * <p>⚠️ <b>本类补的是哪一层缺口</b>：
 * P6 冻结报告登记「S4a/S4b/S4c/S5b/S5c/未知风险/参数策略失败 缺 IT 层」。
 * 这些场景此前<b>只有单测层</b>证据 —— 判定期望四字段（authz / exec / status / reasonCode）
 * 虽已被单测断言，但**从未在真实 PostgreSQL 上跑过**：
 * 真实库的对象版本行、发布指针、闭包解析、能力快照、指纹比对、
 * 以及 RLS 租户边界都没参与过判定。
 *
 * <p>⚠️ <b>本类锁的是「同一套判定在真实持久化状态下给出同样的四字段」</b>：
 * 单测用内存替身，替身与真实仓储的差异（版本行缺失、指针未发布、指纹算不出、
 * RLS 过滤掉对象）恰恰是最容易藏 bug 的地方。
 *
 * <p>⚠️ <b>哪些是真实 PG、哪些是替身（逐条说清，不含糊）</b>：
 * <ul>
 *   <li><b>真实</b>：{@code cap_skill_def} / {@code cap_tool_def} /
 *       {@code cap_mcp_server_def} 的版本行、<b>发布指针</b>（§6.3 第 5 条）、
 *       {@code cap_agent_def}、闭包解析、能力快照、闭包指纹、
 *       以及<b>非特权角色</b>（{@code app_agent}）的 RLS 边界；</li>
 *   <li><b>替身</b>：MCP <b>传输边界</b>（{@code listTools}）——
 *       清单按定义来自外部 MCP Server，IT 不可能真连一个外部 server。
 *       替身只控制「远端当前暴露了哪些 op」，
 *       清单的<b>求交、指纹、绑定</b>全部由真实代码与真实库完成。</li>
 * </ul>
 *
 * <p>⚠️ <b>「未知风险」在本类的形态与其他场景不同</b>：
 * {@code cap_tool_def.risk_level} 是 {@code NOT NULL + CHECK}，
 * <b>未知风险在数据层根本不可能存在</b>。
 * 故本类证明的是「该状态<b>不可达</b>」（DB CHECK 兜底），
 * 而运行期四字段仍由单测覆盖（若该状态因任何原因出现，拒为 RISK_LEVEL_UNKNOWN）。
 * 两者合起来才是完整结论：<b>状态不可达 + 不可达时仍 fail-closed</b>。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class RuntimeDecisionScenarioIT extends CapITBase {

    private static final String SKILL_ID = "it-r2-skill";
    private static final String TOOL_ID = "it-r2-tool";
    private static final String MCP_SERVER_ID = "it-r2-mcp";
    private static final String MCP_TOOL_A_ID = "it-r2-mcp-tool-a";
    private static final String MCP_TOOL_B_ID = "it-r2-mcp-tool-b";
    private static final String PRINCIPAL = "it-r2-principal";
    private static final String OP_A = "op-a";
    private static final String OP_B = "op-b";

    /** 角色权限替身：只放行 TEACHER 的 tool:invoke。 */
    private static final RolePermissionPort ROLE_STUB = (roles, action) ->
            roles.contains("TEACHER") && "tool:invoke".equals(action);

    // ── S4a：Skill 从 enabled 移除 ────────────────────────────────

    @Test
    @DisplayName("⛔ S4a Skill 从 enabled 移除（真实转移）→ DENY/NOT_EVALUATED/NOT_STARTED/SKILL_NOT_ENABLED")
    void skillRemovedFromEnabledIsDeniedOnRealDatabase() {
        seedSkill(SKILL_ID, 1, "WRITE_EXTERNAL", "[]", "[\"" + TOOL_ID + "\"]", "[]");
        seedTool(TOOL_ID, 1, "READ");
        publishPlatformObjects("tool:" + TOOL_ID + "@1", "skill:" + SKILL_ID + "@1");

        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        writeSnapshot(tenantA, runId, agentId);

        // ⚠️ 基线必须先成立：移除转移的起点若不通，后续断言会因「起点就不对」而失真
        assertThat(validator(manifestOf(Set.of(OP_A))).validate(request(tenantA, agentId, runId, 1L))
                        .authorization())
                .as("⚠️ 基线未放行：Skill 启用时本应通过，移除转移无从谈起")
                .isEqualTo(AuthorizationDecision.Authorization.ALLOW);

        // ── 转移：发布 Agent 新版本，enabled_skills 中移除该 Skill ──
        seedAgentVersion(tenantA, agentId, 2, "[]");

        AuthorizationDecision decision = validator(manifestOf(Set.of(OP_A)))
                .validate(request(tenantA, agentId, runId, 1L));

        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.SKILL_NOT_ENABLED);
        assertThat(decision.authorization()).isEqualTo(AuthorizationDecision.Authorization.DENY);
        assertThat(decision.execution()).isEqualTo(AuthorizationDecision.Execution.NOT_EVALUATED);
        assertThat(decision.status()).isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
    }

    // ── S4b：riskCeiling 收紧 ─────────────────────────────────────

    @Test
    @DisplayName("⛔ S4b riskCeiling 收紧（真实转移）→ DENY/NOT_EVALUATED/NOT_STARTED/RISK_EXCEEDS_CEILING")
    void tightenedRiskCeilingIsDeniedOnRealDatabase() {
        seedSkill(SKILL_ID, 1, "WRITE_EXTERNAL", "[]", "[\"" + TOOL_ID + "\"]", "[]");
        seedTool(TOOL_ID, 1, "WRITE_EXTERNAL");
        publishPlatformObjects("tool:" + TOOL_ID + "@1", "skill:" + SKILL_ID + "@1");

        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        writeSnapshot(tenantA, runId, agentId);

        assertThat(validator(manifestOf(Set.of(OP_A))).validate(request(tenantA, agentId, runId, 1L))
                        .authorization())
                .as("⚠️ 基线未放行：上限未收紧时本应通过")
                .isEqualTo(AuthorizationDecision.Authorization.ALLOW);

        // ── 转移：发布 Skill 新版本，风险上限收紧到 READ ──
        seedSkill(SKILL_ID, 2, "READ", "[]", "[\"" + TOOL_ID + "\"]", "[]");
        publishPlatformObjects("tool:" + TOOL_ID + "@1", "skill:" + SKILL_ID + "@2");

        AuthorizationDecision decision = validator(manifestOf(Set.of(OP_A)))
                .validate(request(tenantA, agentId, runId, 1L));

        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.RISK_EXCEEDS_CEILING);
        assertThat(decision.authorization()).isEqualTo(AuthorizationDecision.Authorization.DENY);
        assertThat(decision.execution()).isEqualTo(AuthorizationDecision.Execution.NOT_EVALUATED);
        assertThat(decision.status()).isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
    }

    // ── S4c / 参数策略失败：参数策略收紧 ──────────────────────────

    @Test
    @DisplayName("⛔ S4c 参数策略收紧（真实转移）→ DENY/NOT_EVALUATED/NOT_STARTED/PARAM_POLICY_FAIL")
    void tightenedParamPolicyIsDeniedOnRealDatabase() {
        seedSkill(SKILL_ID, 1, "WRITE_EXTERNAL", "[]", "[\"" + TOOL_ID + "\"]", "[]");
        seedTool(TOOL_ID, 1, "READ");
        publishPlatformObjects("tool:" + TOOL_ID + "@1", "skill:" + SKILL_ID + "@1");

        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        writeSnapshot(tenantA, runId, agentId);

        Map<String, Object> arguments = Map.of("mode", "unsafe");

        assertThat(validator(manifestOf(Set.of(OP_A)))
                        .validate(request(tenantA, agentId, runId, 1L, arguments)).authorization())
                .as("⚠️ 基线未放行：策略未收紧时本应通过")
                .isEqualTo(AuthorizationDecision.Authorization.ALLOW);

        // ── 转移：发布 Skill 新版本，参数策略收紧为 mode ∈ {safe} ──
        seedSkill(SKILL_ID, 2, "WRITE_EXTERNAL",
                "[{\"param\":\"mode\",\"op\":\"in_allowlist\",\"value\":[\"safe\"]}]",
                "[\"" + TOOL_ID + "\"]", "[]");
        publishPlatformObjects("tool:" + TOOL_ID + "@1", "skill:" + SKILL_ID + "@2");

        AuthorizationDecision decision = validator(manifestOf(Set.of(OP_A)))
                .validate(request(tenantA, agentId, runId, 1L, arguments));

        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.PARAM_POLICY_FAIL);
        assertThat(decision.authorization()).isEqualTo(AuthorizationDecision.Authorization.DENY);
        assertThat(decision.execution()).isEqualTo(AuthorizationDecision.Execution.NOT_EVALUATED);
        assertThat(decision.status()).isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
    }

    // ── 未知风险：数据层不可达 ────────────────────────────────────

    @Test
    @DisplayName("⛔ 未知风险在数据层不可达：cap_tool_def.risk_level 的 CHECK 兜底")
    void unknownRiskCannotBePersisted() {
        // ⚠️ 本例**不**产生运行期四字段 —— 它证明的是更靠前的一层：
        //    未知风险这个状态在真实库中**根本写不进去**。
        //    （risk_level 是 NOT NULL + CHECK，取值域只含三个已知等级。）
        //    运行期「若出现则拒为 RISK_LEVEL_UNKNOWN」由
        //    InvocationValidatorTest#unknownRiskIsDeniedWithDedicatedCode 覆盖。
        assertThatThrownBy(() -> adminExec("INSERT INTO cap_tool_def (id, version, name, kind,"
                + " risk_level, input_schema, param_policies, namespace, config_fingerprint,"
                + " published_at) VALUES ('it-r2-bad-risk', 1, 'bad', 'native', 'MYSTERY',"
                + " '{}'::jsonb, '[]'::jsonb, 'platform', 'fp-bad', now())"))
                .as("⛔ 未知风险被写入了真实库：数据层没有兜住，"
                        + "运行期的 RISK_LEVEL_UNKNOWN 拒绝就成了唯一防线")
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(SQLException.class)
                // ⚠️ 断言在**直接 cause** 上：管理员写入助手把 SQLException 包进了
                //    IllegalStateException，而 SQLException 自身还可能再包一层，
                //    用 root cause 会指向与约束无关的内层异常。
                .satisfies(e -> assertThat(e.getCause().getMessage())
                        .contains("cap_tool_def_risk_ck"));
    }

    // ── S5b：操作级越界 ───────────────────────────────────────────

    @Test
    @DisplayName("⛔ S5b 操作级越界（真实库闭包）→ DENY/NOT_EVALUATED/NOT_STARTED/MCP_OP_NOT_ALLOWED")
    void mcpOperationOutsideAllowlistIsDeniedOnRealDatabase() {
        seedMcpScenario();

        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        writeSnapshot(tenantA, runId, agentId);

        // ⚠️ 请求的是 op-b：它有工具定义，但<b>不在</b> Skill 的操作清单内，
        //    因此不在闭包里 —— 越界必须由 §5.8 步骤 2 捕获。
        AuthorizationDecision decision = validator(manifestOf(Set.of(OP_A)))
                .validate(request(tenantA, agentId, runId, 1L, Map.of(),
                        new InvocationValidator.McpTarget(MCP_SERVER_ID, OP_B),
                        MCP_TOOL_B_ID));

        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.MCP_OP_NOT_ALLOWED);
        assertThat(decision.authorization()).isEqualTo(AuthorizationDecision.Authorization.DENY);
        assertThat(decision.execution()).isEqualTo(AuthorizationDecision.Execution.NOT_EVALUATED);
        assertThat(decision.status()).isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
    }

    // ── S5c：清单下线/漂移 ────────────────────────────────────────

    @Test
    @DisplayName("⛔ S5c 清单在指纹重算与操作复核之间漂移（TOCTOU）→ MCP_TOOL_DRIFTED")
    void mcpManifestToctouDriftIsDeniedOnRealDatabase() {
        seedMcpScenario();

        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);

        // ⚠️ 清单序列：① 写快照时 M1 → ② 闭包重算时 M1 → ③ 操作级复核时 M2（op 已下线）。
        //    这正是 §5.8 步骤 3 存在的意义：指纹涵盖不到的变化由它兜住。
        Deque<Set<String>> sequence = new ArrayDeque<>(List.of(
                Set.of(OP_A), Set.of(OP_A), Set.of()));
        McpManifestPort manifest = manifestPort(sequence);

        writeSnapshot(tenantA, runId, agentId, manifest);

        AuthorizationDecision decision = validator(manifest)
                .validate(request(tenantA, agentId, runId, 1L, Map.of(),
                        new InvocationValidator.McpTarget(MCP_SERVER_ID, OP_A),
                        MCP_TOOL_A_ID));

        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.MCP_TOOL_DRIFTED);
        assertThat(decision.authorization()).isEqualTo(AuthorizationDecision.Authorization.DENY);
        assertThat(decision.execution()).isEqualTo(AuthorizationDecision.Execution.NOT_EVALUATED);
        assertThat(decision.status()).isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
    }

    @Test
    @DisplayName("⛔ S5c 已发布白名单收紧（纯 DB 漂移）→ CONFIG_FINGERPRINT_STALE")
    void narrowedServerAllowlistInvalidatesFingerprintOnRealDatabase() {
        seedMcpScenario();

        String agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        writeSnapshot(tenantA, runId, agentId);

        // ── 漂移：MCP Server 发布新版本，暴露的操作清单收紧 ──
        //    （这是完全落在 DB 里的漂移：清单定义变了 → 闭包内容变了 → 指纹失效）
        seedMcpServer(MCP_SERVER_ID, 2, "[\"" + OP_B + "\"]");
        publishPlatformObjects("mcp_server:" + MCP_SERVER_ID + "@2",
                "tool:" + MCP_TOOL_A_ID + "@1", "tool:" + MCP_TOOL_B_ID + "@1",
                "skill:" + SKILL_ID + "@1");

        AuthorizationDecision decision = validator(manifestOf(Set.of(OP_A)))
                .validate(request(tenantA, agentId, runId, 1L, Map.of(),
                        new InvocationValidator.McpTarget(MCP_SERVER_ID, OP_A),
                        MCP_TOOL_A_ID));

        assertThat(decision.reasonCode()).isEqualTo(ReasonCode.CONFIG_FINGERPRINT_STALE);
        assertThat(decision.authorization()).isEqualTo(AuthorizationDecision.Authorization.DENY);
        assertThat(decision.execution()).isEqualTo(AuthorizationDecision.Execution.NOT_EVALUATED);
        assertThat(decision.status()).isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    /**
     * MCP 场景的公共夹具。
     *
     * <p>⚠️ Skill 的操作清单只含 {@code op-a}；
     * {@code op-b} <b>有工具定义</b>（否则请求连工具都解析不出来），
     * 但<b>不在</b>清单内 → 不进闭包 → 越界在 §5.8 步骤 2 被捕获。
     */
    private void seedMcpScenario() {
        seedMcpServer(MCP_SERVER_ID, 1, null);
        seedMcpTool(MCP_TOOL_A_ID, 1, OP_A, "READ");
        seedMcpTool(MCP_TOOL_B_ID, 1, OP_B, "READ");
        seedSkill(SKILL_ID, 1, "WRITE_EXTERNAL", "[]", "[]",
                "[{\"serverId\":\"" + MCP_SERVER_ID + "\",\"toolAllowlist\":[\"" + OP_A + "\"]}]");
        publishPlatformObjects("mcp_server:" + MCP_SERVER_ID + "@1",
                "tool:" + MCP_TOOL_A_ID + "@1", "tool:" + MCP_TOOL_B_ID + "@1",
                "skill:" + SKILL_ID + "@1");
    }

    /**
     * 插入一条 Skill 版本行。
     *
     * <p>⚠️ <b>声明的工具必须与「已发布指针」一致</b>：
     * Skill 声明了某个工具而该工具没有已发布版本时，
     * 闭包解析会以 {@code RESOURCE_VERSION_MISMATCH} 失败 ——
     * 那不是本类要验的场景，会让断言失真。
     */
    private void seedSkill(String id, int version, String riskCeiling, String paramPolicies,
                           String declaredTools, String declaredMcp) {
        adminExec("INSERT INTO cap_skill_def (id, version, name, declared_tools, declared_mcp,"
                + " risk_ceiling, param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + id + "', " + version + ", 'it skill', '" + declaredTools + "', '"
                + declaredMcp + "'::jsonb, '" + riskCeiling + "', '" + paramPolicies
                + "'::jsonb, 'platform', 'fp-skill-" + version + "', now())");
    }

    private void seedTool(String id, int version, String riskLevel) {
        adminExec("INSERT INTO cap_tool_def (id, version, name, kind, risk_level, input_schema,"
                + " param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + id + "', " + version + ", 'it tool', 'native', '" + riskLevel
                + "', '{}'::jsonb, '[]'::jsonb, 'platform', 'fp-tool-" + version + "', now())");
    }

    private void seedMcpServer(String id, int version, String allowlistJson) {
        String allowlist = allowlistJson == null ? "NULL" : "'" + allowlistJson + "'::jsonb";
        adminExec("INSERT INTO cap_mcp_server_def (id, version, transport, endpoint, auth_kind,"
                + " trust_tier, exposed_tool_allowlist, connection_risk, dynamic_discovery,"
                + " config_fingerprint, published_at)"
                + " VALUES ('" + id + "', " + version + ", 'http',"
                + " 'https://mcp.example.com/sse', 'token', 'internal', " + allowlist
                + ", 'WRITE_EXTERNAL', false, 'fp-server-" + version + "', now())");
    }

    private void seedMcpTool(String id, int version, String op, String riskLevel) {
        adminExec("INSERT INTO cap_tool_def (id, version, name, kind, risk_level, mcp_server_id,"
                + " mcp_tool_name, input_schema, param_policies, namespace, config_fingerprint,"
                + " published_at)"
                + " VALUES ('" + id + "', " + version + ", 'it mcp tool', 'mcp', '" + riskLevel
                + "', '" + MCP_SERVER_ID + "', '" + op + "', '{}'::jsonb, '[]'::jsonb, 'platform',"
                + " 'fp-mcp-tool-" + version + "', now())");
    }

    private String seedAgent(String tenantId, String enabledSkillsJson) {
        String agentId = createAgentWithVersion(tenantId, uniqueName("it-r2-agent"), "{}", true);
        seedAgentVersion(tenantId, agentId, 1, enabledSkillsJson);
        return agentId;
    }

    /**
     * 发布 Agent 定义的<b>新版本</b>（S4a 的「移除」转移用）。
     *
     * <p>⚠️ Agent 定义的「当前版本」解析口径是
     * {@code ORDER BY version DESC ... published_at IS NOT NULL}
     * （{@code JdbcCapAgentDefRepository}），故插入新版本行即刻生效 ——
     * 这与「以 max(seq) 选当前版本」<b>不是</b>一回事：
     * 平台对象的当前版本由<b>发布指针</b>决定（§6.3 第 5 条），
     * Agent 定义行则按其自身版本序取最新已发布行。
     */
    private void seedAgentVersion(String tenantId, String agentId, int version,
                                  String enabledSkillsJson) {
        adminExec("INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, roles,"
                + " enabled_skills, overrides, config_fingerprint, published_at)"
                + " VALUES ('" + tenantId + "'::uuid, '" + agentId + "'::uuid, " + version
                + ", 'it agent', '[\"TEACHER\"]'::jsonb, '" + enabledSkillsJson
                + "'::jsonb, '{}'::jsonb, 'fp-agent-" + version + "', now())");
    }

    private String seedRun(String tenantId, String agentId) {
        String snapshotId = createExecutionSnapshot(tenantId, agentId);
        return createRun(tenantId, agentId, snapshotId);
    }

    private void seedAuthzVersion(String tenantId, long version) {
        adminExec("INSERT INTO cap_authz_version (tenant_id, principal_id, authz_version)"
                + " VALUES ('" + tenantId + "'::uuid, '" + PRINCIPAL + "', " + version + ")");
    }

    /** 写入能力快照（指纹取当前闭包）。 */
    private void writeSnapshot(String tenantId, String runId, String agentId) {
        writeSnapshot(tenantId, runId, agentId, manifestOf(Set.of(OP_A)));
    }

    /** 写入能力快照（指纹取当前闭包；清单源可指定，用于 TOCTOU 序列）。 */
    private void writeSnapshot(String tenantId, String runId, String agentId,
                               McpManifestPort manifest) {
        EffectiveClosure closure = resolver(manifest).resolveCurrent(tenantId, agentId);
        new JdbcCapabilitySnapshotRepository(appPool).save(new CapabilitySnapshot(
                runId, tenantId, agentId, ClosureFingerprint.canonicalJson(closure), 1, 0L,
                ClosureFingerprint.of(closure), 1L, Instant.now()));
    }

    // ── 装配 ──────────────────────────────────────────────────────

    private PermissionResolver resolver(McpManifestPort manifest) {
        return new PermissionResolver(
                new JdbcCapAgentDefRepository(appPool),
                new JdbcCapSkillDefRepository(appPool),
                new JdbcCapToolDefRepository(appPool),
                new JdbcCapMcpServerDefRepository(appPool),
                new JdbcPublishedVersionPort(appPool),
                manifest);
    }

    private InvocationValidator validator(McpManifestPort manifest) {
        return new InvocationValidator(
                new JdbcAuthzVersionRepository(appPool),
                ROLE_STUB,
                resolver(manifest),
                new JdbcCapabilitySnapshotRepository(appPool),
                manifest);
    }

    /**
     * 构造清单端口。
     *
     * <p>⚠️ <b>用生产的 {@link TransportMcpManifestPort}，只替身传输边界</b>：
     * 已发布的服务定义从<b>真实库</b>读（因此配置非法、定义缺失都会真实地表现为
     * 「清单不可用」），只有「远端当前暴露了哪些 op」由替身给出。
     *
     * <p>⚠️ TTL 设为 0：清单缓存是<b>漂移暴露窗口</b>（TTL 越短越安全），
     * 而 TOCTOU 场景要求「同一秒内两次读取看到不同的清单」，
     * 带缓存就永远看不到漂移。
     */
    private McpManifestPort manifestPort(Deque<Set<String>> sequence) {
        McpTransportPort transport = new McpTransportPort() {
            @Override
            public List<McpToolInfo> listTools(CapMcpServerDef server) {
                Set<String> ops = sequence.isEmpty() ? Set.of() : sequence.pop();
                List<McpToolInfo> tools = new ArrayList<>();
                for (String op : ops) {
                    tools.add(new McpToolInfo(op, "it tool", "{}"));
                }
                return tools;
            }

            @Override
            public McpToolResult callTool(CapMcpServerDef server, String op,
                                          Map<String, Object> arguments) {
                throw new UnsupportedOperationException("本 IT 不执行 MCP 调用");
            }
        };
        CapMcpServerDefPort serverDefs = new JdbcCapMcpServerDefRepository(appPool);
        return new TransportMcpManifestPort(transport, serverDefs, Duration.ZERO);
    }

    /** 固定清单（单个 op 集合，重复读取恒定）。 */
    private McpManifestPort manifestOf(Set<String> ops) {
        return new McpManifestPort() {
            @Override
            public ManifestSnapshot manifest(String serverId) {
                return ManifestSnapshot.available(ops);
            }
        };
    }

    private InvocationValidator.InvocationRequest request(String tenantId, String agentId,
                                                           String runId, long authzVersion) {
        return request(tenantId, agentId, runId, authzVersion, Map.of(), null, TOOL_ID);
    }

    private InvocationValidator.InvocationRequest request(String tenantId, String agentId,
                                                           String runId, long authzVersion,
                                                           Map<String, Object> arguments) {
        return request(tenantId, agentId, runId, authzVersion, arguments, null, TOOL_ID);
    }

    private InvocationValidator.InvocationRequest request(String tenantId, String agentId,
                                                           String runId, long authzVersion,
                                                           Map<String, Object> arguments,
                                                           InvocationValidator.McpTarget target,
                                                           String toolId) {
        return new InvocationValidator.InvocationRequest(
                new TenantContext(tenantId, PRINCIPAL, Set.of("TEACHER"), authzVersion),
                agentId, SKILL_ID, toolId, arguments, runId, target);
    }
}
