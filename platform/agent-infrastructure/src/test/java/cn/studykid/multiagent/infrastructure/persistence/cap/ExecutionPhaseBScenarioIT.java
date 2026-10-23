package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.application.cap.CapEnforcementSwitch;
import cn.studykid.multiagent.application.cap.CapExecutionFlow;
import cn.studykid.multiagent.application.cap.ClosureFingerprint;
import cn.studykid.multiagent.application.cap.DecisionAuditService;
import cn.studykid.multiagent.application.cap.ExecutionAdmissionChecker;
import cn.studykid.multiagent.application.cap.IdempotencyGuard;
import cn.studykid.multiagent.application.cap.InvocationValidator;
import cn.studykid.multiagent.application.cap.PermissionResolver;
import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.CapToolDecision;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.JdbcBudgetRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcToolOperationLedger;
import cn.studykid.multiagent.ports.mcp.McpManifestPort;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.security.RolePermissionPort;
import cn.studykid.multiagent.ports.tool.ToolHandler;
import cn.studykid.multiagent.ports.tool.ToolValidationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §9.1 Phase B（执行准入）场景的<b>真实 PG</b> 集成测试（W-PERM-P6R2 · 消解 P6 限制 #3）。
 *
 * <p>⚠️ <b>本类补的是哪一层缺口</b>：
 * 「Schema 失败」一行此前只有单测层证据
 * （{@code CapExecutionFlowTest#schemaFailureDeniesWithoutSideEffects}）。
 * 单测能断言四字段，但<b>断言不了「无副作用」</b> ——
 * 它的账本、预算、审批、意图都是内存替身。
 *
 * <p>⚠️ <b>本类锁的是「拒绝发生在任何写入之前」</b>：
 * Schema 校验失败必须落在 <b>Phase B</b>，且在此之前
 * <b>不得</b>预占审批、<b>不得</b>预留预算、<b>不得</b>写执行意图（PRE）、
 * <b>不得</b>注册账本行。本类的全部持久化端口都用<b>真实 JDBC 仓储</b>，
 * 因此断言直接落在<b>真实表</b>上：
 * <pre>
 *   cap_decision_log     → 恰好 1 行，且四字段与决策一致
 *   cap_execution_intent → 0 行（未写 PRE）
 *   cap_approval         → 0 行（未预占审批）
 *   budget_reservation   → 0 行（未预留预算）
 *   tool_operation       → 0 行（未注册账本）
 *   cap_result_event     → 0 行（未写结果事件）
 * </pre>
 *
 * <p>⚠️ <b>为什么必须用真实表而不是替身计数</b>：
 * 替身计数只能证明「某个方法被调用过」，
 * 而真实表能证明「<b>没有任何东西被写下去</b>」——
 * 后者才是「无副作用」这条承诺的含义。
 * 写路径若绕过端口直接落库（未来引入新表/新触发器），
 * 只有真实表的断言能发现。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class ExecutionPhaseBScenarioIT extends CapITBase {

    private static final String SKILL_ID = "it-r2b-skill";
    private static final String TOOL_ID = "it-r2b-tool";
    /**
     * 执行流的主体标识。
     *
     * <p>⚠️ <b>必须是 Worker 服务账号</b>：§5.3 步骤 1 规定可信上下文的
     * 「主体是 Worker 服务账号」，其角色集固定为 {@code {WORKER}}。
     * 本类刻意<b>不</b>自造主体，否则 Phase A 会在主体授权阶段就拒绝，
     * 根本走不到 Schema 校验 —— 断言的将是「授权拒绝」而非「Schema 失败」。
     */
    private static final String PRINCIPAL = CapExecutionFlow.DEFAULT_PRINCIPAL_ID;
    private static final Clock CLOCK = Clock.systemUTC();

    /** 角色权限替身：放行 WORKER 的 tool:invoke（§5.3 步骤 1 的主体角色集）。 */
    private static final RolePermissionPort ROLE_STUB = (roles, action) ->
            roles.contains(AuthorizationService.Roles.WORKER) && "tool:invoke".equals(action);

    /** 空清单：V1 未启用动态发现，Phase B 场景与 MCP 无关。 */
    private static final McpManifestPort EMPTY_MANIFEST =
            serverId -> McpManifestPort.ManifestSnapshot.available(Set.of());

    @Test
    @DisplayName("⛔ Schema 失败 → ALLOW/DENY/NOT_STARTED/PARAM_SCHEMA_FAIL，且真实表零副作用")
    void schemaFailureDeniesWithoutSideEffectsOnRealDatabase() {
        seedSkillAndTool();
        String agentId = seedAgent(tenantA);
        String runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        writeSnapshot(tenantA, runId, agentId);

        CapToolDecision decision = flow().invoke(tenantA, task(runId), call(), failingHandler());

        // ── ① 四字段 ──
        assertThat(decision.decision().authorization())
                .as("§9.1 Schema 失败：授权仍为 ALLOW（属执行准入失败，不是授权拒绝）")
                .isEqualTo(AuthorizationDecision.Authorization.ALLOW);
        assertThat(decision.decision().execution())
                .as("§9.1 Schema 失败：执行准入必须 DENY")
                .isEqualTo(AuthorizationDecision.Execution.DENY);
        assertThat(decision.decision().status())
                .as("§9.1 Schema 失败：工具未启动，状态为 NOT_STARTED")
                .isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
        assertThat(decision.decision().reasonCode())
                .as("§9.1 Schema 失败：原因码必须是 PARAM_SCHEMA_FAIL")
                .isEqualTo(ReasonCode.PARAM_SCHEMA_FAIL);

        // ⚠️ **所有断言都按本次调用精确限定**（run_id / invocation_id / tool_id），
        //    不用全表计数 —— 后者会被其他 IT 的残留污染：
        //    「budget_reservation」「tool_operation」是 V1 基座表，
        //    不在 CAP 基座的清理范围内（那里只清 cap_* 与 execution_* ），
        //    用全表计数断言「零副作用」会在全量构建里被邻座的残留打红。
        String invocationId = "pending:" + runId + ':' + TOOL_ID;

        // ── ② 决策日志：四字段必须落库且与决策一致 ──
        assertThat(adminQueryLong("SELECT count(*) FROM cap_decision_log"
                + " WHERE invocation_id = '" + invocationId + "'"
                + " AND reason_code = 'PARAM_SCHEMA_FAIL' AND authz_decision = 'ALLOW'"
                + " AND exec_decision = 'DENY' AND exec_status = 'NOT_STARTED'"))
                .as("⛔ 决策日志未落库或四字段与决策不一致：审计是决策的对外承诺")
                .isEqualTo(1L);

        // ── ③ 零副作用：真实表逐张核对（限定本次调用）──
        assertThat(adminQueryLong("SELECT count(*) FROM cap_execution_intent"
                + " WHERE run_id = '" + runId + "'::uuid"))
                .as("⛔ Schema 失败仍写了执行意图（PRE）："
                        + "意图登记先于外部副作用，失败路径写入会让恢复逻辑误判为已执行")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_approval"
                + " WHERE tool_id = '" + TOOL_ID + "'"))
                .as("⛔ Schema 失败仍预占了审批：审批额度被无效占用")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM budget_reservation"
                + " WHERE run_id = '" + runId + "'::uuid"))
                .as("⛔ Schema 失败仍预留了预算：成本被无效占用")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM tool_operation"
                + " WHERE run_id = '" + runId + "'::uuid"))
                .as("⛔ Schema 失败仍注册了账本行：账本里会出现一个从未发生的调用")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_result_event"
                + " WHERE run_id = '" + runId + "'::uuid"))
                .as("⛔ Schema 失败仍写了结果事件")
                .isZero();
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    private void seedSkillAndTool() {
        adminExec("INSERT INTO cap_skill_def (id, version, name, declared_tools, declared_mcp,"
                + " risk_ceiling, param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + SKILL_ID + "', 1, 'it skill', '[\"" + TOOL_ID + "\"]', '[]'::jsonb,"
                + " 'WRITE_EXTERNAL', '[]'::jsonb, 'platform', 'fp-skill', now())");
        adminExec("INSERT INTO cap_tool_def (id, version, name, kind, risk_level, input_schema,"
                + " param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + TOOL_ID + "', 1, 'it tool', 'native', 'READ',"
                + " '{\"type\":\"object\",\"required\":[\"studentId\"]}'::jsonb, '[]'::jsonb,"
                + " 'platform', 'fp-tool', now())");
        // ⚠️ P5：当前生效版本只由**发布指针**决定（§6.3 第 5 条）
        publishPlatformObjects("tool:" + TOOL_ID + "@1", "skill:" + SKILL_ID + "@1");
    }

    private String seedAgent(String tenantId) {
        String agentId = createAgentWithVersion(tenantId, uniqueName("it-r2b-agent"), "{}", true);
        adminExec("INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, roles,"
                + " enabled_skills, overrides, config_fingerprint, published_at)"
                + " VALUES ('" + tenantId + "'::uuid, '" + agentId + "'::uuid, 1, 'it agent',"
                + " '[\"TEACHER\"]'::jsonb, '[\"" + SKILL_ID + "\"]'::jsonb, '{}'::jsonb,"
                + " 'fp-agent', now())");
        return agentId;
    }

    private String seedRun(String tenantId, String agentId) {
        String snapshotId = createExecutionSnapshot(tenantId, agentId);
        return createRun(tenantId, agentId, snapshotId);
    }

    private void seedAuthzVersion(String tenantId, long version) {
        adminExec("INSERT INTO cap_authz_version (tenant_id, principal_id, authz_version)"
                + " VALUES ('" + tenantId + "'::uuid, '" + PRINCIPAL + "', " + version + ")");
    }

    private void writeSnapshot(String tenantId, String runId, String agentId) {
        EffectiveClosure closure = resolver().resolveCurrent(tenantId, agentId);
        new JdbcCapabilitySnapshotRepository(appPool).save(new CapabilitySnapshot(
                runId, tenantId, agentId, ClosureFingerprint.canonicalJson(closure), 1, 0L,
                ClosureFingerprint.of(closure), 1L, Instant.now()));
    }

    /**
     * Schema 校验失败的工具处理器。
     *
     * <p>⚠️ <b>execute 永不被调用</b>：若被调用会抛
     * {@link AssertionError}，让「校验失败后仍执行了工具」当场暴露。
     */
    private ToolHandler failingHandler() {
        return new ToolHandler() {
            @Override
            public @NonNull String toolName() {
                return TOOL_ID;
            }

            @Override
            public @NonNull String inputSchema() {
                return "{\"type\":\"object\",\"required\":[\"studentId\"]}";
            }

            @Override
            public @NonNull ToolRiskLevel riskLevel() {
                return ToolRiskLevel.READ;
            }

            @Override
            public void validate(@NonNull Map<String, Object> arguments) {
                throw new ToolValidationException("缺少必填参数 studentId");
            }

            @Override
            public @NonNull ToolResult execute(@NonNull ToolExecutionContext context,
                                                @NonNull Map<String, Object> arguments) {
                throw new AssertionError("Schema 校验失败后绝不能执行工具");
            }
        };
    }

    private RunClaimPort.LeasedTask task(String runId) {
        return new RunClaimPort.LeasedTask(runId, 1L, Instant.now().plusSeconds(60), 1);
    }

    private ModelResponse.ToolCallRequest call() {
        return new ModelResponse.ToolCallRequest(TOOL_ID, Map.of(), "call-r2b-1");
    }

    // ── 装配（全部使用真实 JDBC 仓储）──────────────────────────────

    private PermissionResolver resolver() {
        return new PermissionResolver(
                new JdbcCapAgentDefRepository(appPool),
                new JdbcCapSkillDefRepository(appPool),
                new JdbcCapToolDefRepository(appPool),
                new JdbcCapMcpServerDefRepository(appPool),
                new JdbcPublishedVersionPort(appPool),
                EMPTY_MANIFEST);
    }

    /**
     * 执行流装配。
     *
     * <p>⚠️ <b>全部持久化端口都是真实 JDBC 实现</b>，
     * 没有一个替身 —— 这是本类与单测的根本差别：
     * 「无副作用」必须由真实表来证明。
     */
    private CapExecutionFlow flow() {
        InvocationValidator validator = new InvocationValidator(
                new JdbcAuthzVersionRepository(appPool), ROLE_STUB, resolver(),
                new JdbcCapabilitySnapshotRepository(appPool), EMPTY_MANIFEST);
        ExecutionAdmissionChecker admission = new ExecutionAdmissionChecker(
                new JdbcAuthzVersionRepository(appPool), resolver(),
                new JdbcCapabilitySnapshotRepository(appPool));
        IdempotencyGuard idempotency = new IdempotencyGuard(
                new JdbcToolOperationLedger(appPool), new JdbcResultEventRepository(appPool));
        return new CapExecutionFlow(
                validator, resolver(), admission, idempotency,
                new JdbcAuthzVersionRepository(appPool), ROLE_STUB,
                new JdbcCapabilitySnapshotRepository(appPool),
                handler -> cn.studykid.multiagent.ports.security.ApprovalPolicy.ApprovalDecision
                        .notRequired(),
                new cn.studykid.multiagent.application.cap.ApprovalStateMachine(
                        new JdbcCapApprovalRepository(appPool),
                        new cn.studykid.multiagent.application.cap.ApprovalBindingService()),
                new cn.studykid.multiagent.application.cap.CompensationFramework(
                        new JdbcCapApprovalRepository(appPool),
                        new JdbcBudgetRepository(appPool),
                        new JdbcCompensationTaskRepository(appPool)),
                new JdbcCapToolDefRepository(appPool),
                new JdbcBudgetRepository(appPool),
                new JdbcToolOperationLedger(appPool),
                new JdbcExecutionIntentRepository(appPool),
                new JdbcResultEventRepository(appPool),
                BigDecimal.ONE, new CapEnforcementSwitch(true),
                new DecisionAuditService(new JdbcDecisionAuditRepository(appPool), CLOCK));
    }
}
