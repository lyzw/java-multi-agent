package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.application.cap.ApprovalBindingService;
import cn.studykid.multiagent.application.cap.ApprovalStateMachine;
import cn.studykid.multiagent.application.cap.CapEnforcementSwitch;
import cn.studykid.multiagent.application.cap.CapExecutionFlow;
import cn.studykid.multiagent.application.cap.ClosureFingerprint;
import cn.studykid.multiagent.application.cap.CompensationFramework;
import cn.studykid.multiagent.application.cap.DecisionAuditService;
import cn.studykid.multiagent.application.cap.ExecutionAdmissionChecker;
import cn.studykid.multiagent.application.cap.IdempotencyGuard;
import cn.studykid.multiagent.application.cap.InvocationValidator;
import cn.studykid.multiagent.application.cap.PermissionResolver;
import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.infrastructure.persistence.JdbcBudgetRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcToolOperationLedger;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.DecisionAuditCoverage;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.mcp.McpManifestPort;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;
import cn.studykid.multiagent.ports.persistence.CompensationTaskPort;
import cn.studykid.multiagent.ports.persistence.DecisionAuditPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.security.RolePermissionPort;
import cn.studykid.multiagent.ports.tool.ToolHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W-PERM-P5 四字段决策日志的<b>真实 PG</b> 集成测试（§5.1 / §7.4 / 08 §7 · P5-T6/T7/T8）。
 *
 * <p>⚠️ <b>本类锁的是 08 §7 的三条审计指标</b>：
 * <ol>
 *   <li>四字段完整率 <b>100%</b>；</li>
 *   <li>非笼统 DENY 占比 <b>100%</b>；</li>
 *   <li>每次调用（允许/拒绝/失败/未知）可经 <b>runId + invocationId</b> 追踪；</li>
 *   <li>日志<b>不含</b>明文凭据与不必要的敏感参数（NFR-06）。</li>
 * </ol>
 *
 * <p>⚠️ <b>断言的对象是数据库里的行</b>，不是内存替身的返回值 ——
 * 否则「日志结构完整」这条性质根本没被验证过。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class DecisionAuditIT extends CapITBase {

    private static final String SKILL_ID = "it-p5a-skill";
    private static final String TOOL_ID = "it-p5a-tool";
    private static final String PRINCIPAL = CapExecutionFlow.DEFAULT_PRINCIPAL_ID;

    private static final McpManifestPort EMPTY_MANIFEST =
            serverId -> McpManifestPort.ManifestSnapshot.available(Set.of());

    /**
     * 角色权限替身：放行 WORKER 的 tool:invoke。
     *
     * <p>⚠️ 必须放行 {@code WORKER}（而非 TEACHER）：
     * Phase A 的主体角色集固定为 Worker 服务账号（{@code CapExecutionFlow}），
     * 放行 TEACHER 会让每次调用都在「角色不足」处被拒 ——
     * 那样测的就不是审计，而是「审计了一个拒绝」。
     */
    private static final RolePermissionPort ROLE_STUB = (roles, action) ->
            roles.contains("WORKER") && "tool:invoke".equals(action);

    private final AtomicInteger toolExecutions = new AtomicInteger();

    private String agentId;
    private String runId;
    private DecisionAuditPort audit;
    private DecisionAuditService auditService;

    @BeforeEach
    void setUp() {
        toolExecutions.set(0);
        seedPlatformDefs();
        agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        resetBudget();
        audit = new JdbcDecisionAuditRepository(appPool);
        auditService = new DecisionAuditService(audit, java.time.Clock.systemUTC());
        writeSnapshot(tenantA, runId, agentId, resolver().resolve(tenantA, agentId, 1));
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 允许的调用：四字段 + ledger + 原因码 + 上下文 id 全部落库")
    void allowedInvocationIsFullyAudited() {
        CapExecutionFlow flow = flow();

        CapExecutionFlowResult result = invoke(flow);

        assertThat(result.decision.decision().authorization())
                .isEqualTo(AuthorizationDecision.Authorization.ALLOW);
        assertThat(toolExecutions.get()).isEqualTo(1);

        List<DecisionAuditEntry> entries = audit.findByInvocation(tenantA, result.invocationId);
        assertThat(entries)
                .as("⛔ 允许的调用没有留下审计行：每次调用都必须可追责（08 §7）")
                .hasSize(1);
        DecisionAuditEntry entry = entries.get(0);
        assertThat(entry.authzDecision()).isEqualTo(AuthorizationDecision.Authorization.ALLOW);
        assertThat(entry.execDecision()).isEqualTo(AuthorizationDecision.Execution.ALLOW);
        assertThat(entry.execStatus()).isEqualTo(AuthorizationDecision.Status.SUCCEEDED);
        assertThat(entry.ledgerStatus()).isEqualTo(AuthorizationDecision.Ledger.RECORDED);
        assertThat(entry.reasonCode()).isEqualTo(ReasonCode.SUCCESS);
        assertThat(entry.runId()).isEqualTo(runId);
        assertThat(entry.invocationId()).isEqualTo(result.invocationId);
        assertThat(entry.toolId()).isEqualTo(TOOL_ID);
        assertThat(entry.principalId()).isEqualTo(PRINCIPAL);
    }

    @Test
    @DisplayName("✅ 拒绝的调用：DENY + NOT_EVALUATED + NOT_STARTED + 具体原因码")
    void deniedInvocationIsAuditedWithSpecificReasonCode() {
        // 该 Agent 未启用任何 Skill → SKILL_NOT_ENABLED
        String deniedAgent = seedAgent(tenantA, "[]");
        String deniedRun = seedRun(tenantA, deniedAgent);
        writeSnapshot(tenantA, deniedRun, deniedAgent, resolver().resolve(tenantA, deniedAgent, 1));

        CapExecutionFlow flow = flow();
        CapExecutionFlowResult result = invoke(flow, deniedAgent, deniedRun);

        assertThat(result.decision.decision().authorization())
                .isEqualTo(AuthorizationDecision.Authorization.DENY);
        assertThat(toolExecutions.get())
                .as("⛔ 被拒绝的调用仍执行了工具：授权拒绝未拦住副作用")
                .isZero();

        List<DecisionAuditEntry> entries = audit.findByInvocation(tenantA, result.invocationId);
        assertThat(entries).hasSize(1);
        DecisionAuditEntry entry = entries.get(0);
        assertThat(entry.authzDecision()).isEqualTo(AuthorizationDecision.Authorization.DENY);
        assertThat(entry.execDecision()).isEqualTo(AuthorizationDecision.Execution.NOT_EVALUATED);
        assertThat(entry.execStatus()).isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
        // ⚠️ 实际码是 TOOL_NOT_DECLARED 而非 SKILL_NOT_ENABLED：
        //    Agent 未启用任何 Skill → 闭包里根本没有 Skill → 目标 Tool 因而不被声明。
        //    两个码都是**具体的** §5.7 码（非笼统 DENY），这才是本用例要锁的性质。
        assertThat(entry.reasonCode()).isEqualTo(ReasonCode.TOOL_NOT_DECLARED);
        assertThat(entry.reasonCode())
                .as("⛔ 拒绝没有具体原因码：非笼统 DENY 占比未达 100%（08 §7）")
                .isNotEqualTo(ReasonCode.SUCCESS);
    }

    @Test
    @DisplayName("✅ 审计覆盖率：四字段完整率 100% + 非笼统 DENY 占比 100%")
    void auditCoverageIsComplete() {
        CapExecutionFlow flow = flow();
        invoke(flow);

        DecisionAuditCoverage coverage = auditService.coverage(tenantA);
        assertThat(coverage.total()).isPositive();
        assertThat(coverage.incompleteCount())
                .as("⛔ 存在四字段不完整的日志行：完整率未达 100%（08 §7）")
                .isZero();
        assertThat(coverage.nonSpecificDenyCount())
                .as("⛔ 存在笼统 DENY（无意义原因码）：非笼统 DENY 占比未达 100%")
                .isZero();
        assertThat(coverage.completenessRate()).isEqualTo(1.0d);
        assertThat(coverage.denySpecificityRate()).isEqualTo(1.0d);
        assertThat(coverage.isComplete()).isTrue();
    }

    @Test
    @DisplayName("✅ 可经 runId + invocationId 追踪每次调用（08 §7 双重验收第 1 条）")
    void everyCallIsTraceableByRunAndInvocation() {
        CapExecutionFlow flow = flow();
        CapExecutionFlowResult result = invoke(flow);

        assertThat(audit.findByRun(tenantA, runId))
                .as("⛔ 按 runId 查不到调用记录：调用不可追踪")
                .hasSize(1);
        assertThat(audit.findByInvocation(tenantA, result.invocationId)).hasSize(1);
    }

    @Test
    @DisplayName("✅ 日志不含明文凭据与业务参数（NFR-06）")
    void auditLogCarriesNoSensitivePayload() {
        CapExecutionFlow flow = flow();
        CapExecutionFlowResult result = invoke(flow);

        // ⚠️ 整行文本都不该出现参数值：本表**没有任何承载参数的列**
        String raw = adminQuery("SELECT coalesce(string_agg(t::text, ' '), '')"
                + " FROM cap_decision_log t");
        assertThat(raw)
                .as("⛔ 审计日志出现了参数值：日志承载了不必要的敏感参数（NFR-06）")
                .doesNotContain("a@example.com");
        assertThat(raw)
                .as("⛔ 审计日志出现了业务载荷：日志承载了不必要的敏感参数（NFR-06）")
                .doesNotContain("studentId");

        // 列结构上也不存在参数列
        long payloadColumns = adminQueryLong("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_name = 'cap_decision_log'"
                + " AND column_name IN ('arguments', 'payload', 'params', 'detail', 'body')");
        assertThat(payloadColumns)
                .as("⛔ 决策日志表存在承载业务参数的列：脱敏只能靠约定，挡不住「伪装成日志」")
                .isZero();
        assertThat(result.invocationId).isNotBlank();
    }

    @Test
    @DisplayName("✅ 发布事件同样留下结构化审计（decision_kind=PUBLISH）")
    void publicationIsAudited() {
        ConfigPublishServiceFixture fixture = new ConfigPublishServiceFixture(adminPool);
        fixture.publish();

        assertThat(adminQueryLong("SELECT count(*) FROM cap_decision_log WHERE decision_kind ="
                + " 'PUBLISH' AND (authz_decision IS NULL OR exec_decision IS NULL"
                + " OR exec_status IS NULL OR ledger_status IS NULL OR reason_code IS NULL)"))
                .as("⛔ 发布审计的四字段不完整")
                .isZero();
        assertThat(adminQueryLong("SELECT count(*) FROM cap_decision_log WHERE decision_kind ="
                + " 'PUBLISH' AND reason_code = 'SUCCESS'"))
                .as("⛔ 发布事件没有留下审计记录：配置生效边界不可追责")
                .isPositive();
    }

    // ── 编排 ──────────────────────────────────────────────────────

    private record CapExecutionFlowResult(cn.studykid.multiagent.core.cap.CapToolDecision decision,
                                          String invocationId) {
    }

    private CapExecutionFlowResult invoke(CapExecutionFlow flow) {
        return invoke(flow, agentId, runId);
    }

    private CapExecutionFlowResult invoke(CapExecutionFlow flow, String agent, String run) {
        cn.studykid.multiagent.core.cap.CapToolDecision decision = flow.invoke(
                tenantA,
                new RunClaimPort.LeasedTask(run, 1L, Instant.now().plusSeconds(60), 1),
                new ModelResponse.ToolCallRequest(TOOL_ID, Map.of("to", "a@example.com"), "call-1"),
                tool());
        String invocationId = decision.operationId() != null
                ? decision.operationId()
                : "pending:" + run + ':' + TOOL_ID;
        return new CapExecutionFlowResult(decision, invocationId);
    }

    private CapExecutionFlow flow() {
        PermissionResolver resolver = resolver();
        InvocationValidator validator = new InvocationValidator(
                new JdbcAuthzVersionRepository(appPool), ROLE_STUB, resolver,
                new JdbcCapabilitySnapshotRepository(appPool), EMPTY_MANIFEST);
        ExecutionAdmissionChecker admission = new ExecutionAdmissionChecker(
                new JdbcAuthzVersionRepository(appPool), resolver,
                new JdbcCapabilitySnapshotRepository(appPool));
        IdempotencyGuard idempotency = new IdempotencyGuard(
                new JdbcToolOperationLedger(appPool), new JdbcResultEventRepository(appPool));
        CapApprovalPort approvals = new JdbcCapApprovalRepository(appPool);
        CompensationTaskPort tasks = new JdbcCompensationTaskRepository(appPool);
        return new CapExecutionFlow(
                validator, resolver, admission, idempotency,
                new JdbcAuthzVersionRepository(appPool), ROLE_STUB,
                new JdbcCapabilitySnapshotRepository(appPool),
                (ApprovalPolicy) handler -> ApprovalPolicy.ApprovalDecision.notRequired(),
                new ApprovalStateMachine(approvals, new ApprovalBindingService()),
                new CompensationFramework(approvals,
                        new JdbcBudgetRepository(appPool), tasks),
                new JdbcCapToolDefRepository(appPool),
                new JdbcBudgetRepository(appPool),
                new JdbcToolOperationLedger(appPool),
                new JdbcExecutionIntentRepository(appPool),
                new JdbcResultEventRepository(appPool),
                BigDecimal.ONE, new CapEnforcementSwitch(true), auditService);
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

    private ToolHandler tool() {
        return new ToolHandler() {
            @Override
            public @NonNull String toolName() {
                return TOOL_ID;
            }

            @Override
            public @NonNull String inputSchema() {
                return "{}";
            }

            @Override
            public cn.studykid.multiagent.core.config.@NonNull ToolRiskLevel riskLevel() {
                return cn.studykid.multiagent.core.config.ToolRiskLevel.WRITE_EXTERNAL;
            }

            @Override
            public void validate(@NonNull Map<String, Object> arguments) {
            }

            @Override
            public ToolHandler.@NonNull ToolResult execute(
                    ToolHandler.@NonNull ToolExecutionContext context,
                    @NonNull Map<String, Object> arguments) {
                toolExecutions.incrementAndGet();
                return ToolHandler.ToolResult.of("ok");
            }
        };
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    private void seedPlatformDefs() {
        adminExec("INSERT INTO cap_skill_def (id, version, name, declared_tools, declared_mcp,"
                + " risk_ceiling, param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + SKILL_ID + "', 1, 'it skill', '[\"" + TOOL_ID + "\"]', '[]'::jsonb,"
                + " 'WRITE_EXTERNAL', '[]'::jsonb, 'platform', 'fp-skill', now())");
        adminExec("INSERT INTO cap_tool_def (id, version, name, kind, risk_level, input_schema,"
                + " param_policies, namespace, config_fingerprint, published_at)"
                + " VALUES ('" + TOOL_ID + "', 1, 'it tool', 'native', 'WRITE_EXTERNAL', '{}'::jsonb,"
                + " '[]'::jsonb, 'platform', 'fp-tool', now())");
        publishPlatformObjects("tool:" + TOOL_ID + "@1", "skill:" + SKILL_ID + "@1");
    }

    private String seedAgent(String tenantId, String enabledSkillsJson) {
        String agent = createAgentWithVersion(tenantId, uniqueName("it-audit-agent"), "{}", true);
        adminExec("INSERT INTO cap_agent_def (tenant_id, agent_id, version, name, roles,"
                + " enabled_skills, overrides, config_fingerprint, published_at)"
                + " VALUES ('" + tenantId + "'::uuid, '" + agent + "'::uuid, 1, 'it agent',"
                + " '[\"TEACHER\"]'::jsonb, '" + enabledSkillsJson + "'::jsonb, '{}'::jsonb,"
                + " 'fp-agent', now())");
        return agent;
    }

    private String seedRun(String tenantId, String agent) {
        String snapshotId = createExecutionSnapshot(tenantId, agent);
        return createRun(tenantId, agent, snapshotId);
    }

    private void seedAuthzVersion(String tenantId, long version) {
        adminExec("INSERT INTO cap_authz_version (tenant_id, principal_id, authz_version)"
                + " VALUES ('" + tenantId + "'::uuid, '" + PRINCIPAL + "', " + version + ")");
    }

    private void writeSnapshot(String tenantId, String run, String agent,
                                EffectiveClosure closure) {
        new JdbcCapabilitySnapshotRepository(appPool).save(new CapabilitySnapshot(
                run, tenantId, agent, ClosureFingerprint.canonicalJson(closure), 1, 0L,
                ClosureFingerprint.of(closure), 1L, Instant.now()));
    }

    private void resetBudget() {
        adminExec("DELETE FROM budget_reservation WHERE tenant_id = '" + tenantA + "'::uuid");
        adminExec("UPDATE budget_account SET total_limit = 1000, reserved_amount = 0,"
                + " settled_amount = 0 WHERE tenant_id = '" + tenantA + "'::uuid");
    }

    /**
     * 极简发布夹具（只为产生一条 PUBLISH 审计记录）。
     */
    private static final class ConfigPublishServiceFixture {

        private final DataSource dataSource;

        ConfigPublishServiceFixture(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        void publish() {
            cn.studykid.multiagent.application.cap.ConfigPublishService service =
                    new cn.studykid.multiagent.application.cap.ConfigPublishService(
                            new JdbcConfigPublishTransaction(dataSource),
                            new JdbcConfigPointerRepository(dataSource),
                            new JdbcConfigReleaseRepository(dataSource),
                            new JdbcCapToolDefRepository(dataSource),
                            new JdbcCapSkillDefRepository(dataSource),
                            new JdbcCapMcpServerDefRepository(dataSource),
                            new JdbcCapAgentDefRepository(dataSource),
                            new JdbcDecisionAuditRepository(dataSource),
                            new cn.studykid.multiagent.application.cap.ConfigValidationGate(),
                            java.time.Clock.systemUTC());
            service.publish(new cn.studykid.multiagent.core.cap.ConfigPublishCommand(
                    cn.studykid.multiagent.core.cap.ConfigRelease.Scope.PLATFORM, null, null,
                    "it-requester", "it-approver", null,
                    List.of(new cn.studykid.multiagent.core.cap.CapToolDef(
                            TOOL_ID, 1, TOOL_ID,
                            cn.studykid.multiagent.core.cap.CapToolKind.NATIVE,
                            cn.studykid.multiagent.core.cap.CapabilityRiskLevel.WRITE_EXTERNAL,
                            null, null, "{}", "{}", "fp-tool", 1, Instant.now(), null)),
                    List.of(new cn.studykid.multiagent.core.cap.CapSkillDef(
                            SKILL_ID, 1, SKILL_ID, null, List.of(TOOL_ID), List.of(),
                            cn.studykid.multiagent.core.cap.CapabilityRiskLevel.WRITE_EXTERNAL,
                            "{}", List.of(), "fp-skill", 1, Instant.now(), null)),
                    List.of(), List.of(), null));
        }
    }
}
