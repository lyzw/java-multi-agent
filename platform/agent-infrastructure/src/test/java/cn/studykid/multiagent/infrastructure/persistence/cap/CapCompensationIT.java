package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.application.cap.ApprovalBindingService;
import cn.studykid.multiagent.application.cap.ApprovalScope;
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
import cn.studykid.multiagent.application.cap.ResultRecoveryService;
import cn.studykid.multiagent.core.cap.AuthzVersion;
import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.CapApproval;
import cn.studykid.multiagent.core.cap.CapCompensationTask;
import cn.studykid.multiagent.core.cap.CapToolDecision;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.cap.ResultEvent;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.monitoring.AuthzWatermark;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.infrastructure.persistence.JdbcBudgetRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcToolOperationLedger;
import cn.studykid.multiagent.ports.mcp.McpManifestPort;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;
import cn.studykid.multiagent.ports.persistence.ResultEventPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.security.RolePermissionPort;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;
import cn.studykid.multiagent.ports.tool.ToolHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W-PERM-P3 故障注入集成测试（§9.2 COMP-1~6 · P3-T7）。
 *
 * <p>⚠️ <b>本类是 P3 的硬门槛</b>（§8.3 冻结门槛「COMP-6 等故障注入与不变量通过」）：
 * COMP-6「确定成功 → 结果持久化失败 → 进程重启 → 落账 SUCCEEDED 且外部只执行一次」
 * 一旦不通过，意味着系统在最脆弱的时刻会制造<b>重复副作用</b> ——
 * 对「给学发奖励」这类工具，那是直接的资损与重复发放。
 *
 * <p>⚠️ <b>真实 PG + 真实仓储 + 故障注入装饰器</b>：
 * 断言的对象是<b>数据库里的状态</b>（审批状态、账本状态、补偿任务），
 * 不是内存替身的返回值 ——
 * 否则「补偿任务落库」这条最关键的性质根本没被验证过。
 *
 * <p>⚠️ <b>RLS 必须用非特权角色</b>（{@code app_agent}）：超级用户绕过 RLS，
 * 验出来的「隔离生效」是假的。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class CapCompensationIT extends CapITBase {

    private static final String SKILL_ID = "it-cap-c-skill";
    private static final String TOOL_ID = "it-cap-c-tool";
    /** ⚠️ 必须与编排器的默认主体一致（{@code CapExecutionFlow.DEFAULT_PRINCIPAL_ID}）。 */
    private static final String PRINCIPAL = "worker";
    private static final String EMPTY_MANIFEST_ID = "it-cap-c-empty";

    /** 固定清单替身（本阶段只测 native 工具）。 */
    private static final McpManifestPort EMPTY_MANIFEST =
            serverId -> McpManifestPort.ManifestSnapshot.available(Set.of());

    /** 角色权限替身：放行 WORKER 的 tool:invoke。 */
    private static final RolePermissionPort ROLE_STUB = (roles, action) ->
            roles.contains("WORKER") && "tool:invoke".equals(action);

    private String agentId;
    private String runId;
    private PermissionResolver resolver;

    // ── 故障注入开关 ──────────────────────────────────────────────

    private boolean failBudgetReserve;
    private boolean failBudgetRelease;
    private boolean failResultEventWrite;
    private boolean failLedgerPost;

    /** 工具执行次数（COMP-6 的「外部只执行一次」判据）。 */
    private final AtomicInteger toolExecutions = new AtomicInteger();

    @BeforeEach
    void setUp() {
        failBudgetReserve = false;
        failBudgetRelease = false;
        failResultEventWrite = false;
        failLedgerPost = false;
        toolExecutions.set(0);

        seedPlatformDefs();
        agentId = seedAgent(tenantA, "[\"" + SKILL_ID + "\"]");
        runId = seedRun(tenantA, agentId);
        seedAuthzVersion(tenantA, 1L);
        resetBudget();
        resolver = resolver();
        writeSnapshot(tenantA, runId, agentId, resolver.resolve(tenantA, agentId, 1));
    }

    /**
     * 重置预算账户。
     *
     * <p>⚠️ <b>必须每例重置</b>：{@code budget_account} 是<b>共享状态</b>，
     * 其他 IT（如 {@code BudgetReservationIT}）会改动额度与占用额且不还原。
     * 若不重置，本类的预算预留会因「额度已被别例耗尽」而返回 INSUFFICIENT ——
     * 失败现场指向「预算逻辑坏了」，真因却是测试之间的状态污染。
     */
    private void resetBudget() {
        adminExec("DELETE FROM budget_reservation WHERE tenant_id = '" + tenantA + "'::uuid");
        adminExec("UPDATE budget_account SET total_limit = 1000, reserved_amount = 0,"
                + " settled_amount = 0 WHERE tenant_id = '" + tenantA + "'::uuid");
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
        // ⚠️ P5：当前生效版本只由**发布指针**决定（§6.3 第 5 条）
        publishPlatformObjects("tool:" + TOOL_ID + "@1", "skill:" + SKILL_ID + "@1");
    }

    private String seedAgent(String tenantId, String enabledSkillsJson) {
        String agent = createAgentWithVersion(tenantId, uniqueName("it-agent"), "{}", true);
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

    private PermissionResolver resolver() {
        return new PermissionResolver(
                new JdbcCapAgentDefRepository(appPool),
                new JdbcCapSkillDefRepository(appPool),
                new JdbcCapToolDefRepository(appPool),
                new JdbcCapMcpServerDefRepository(appPool),
                new JdbcPublishedVersionPort(appPool),
                EMPTY_MANIFEST);
    }

    // ── 故障注入装饰器 ────────────────────────────────────────────

    /** 预算端口：可注入「预留失败」「释放失败」。 */
    private final class FaultyBudget implements BudgetPort {

        private final BudgetPort delegate = new JdbcBudgetRepository(appPool);

        @Override
        public @NonNull ReserveOutcome reserve(@NonNull String tenantId, @NonNull String runId,
                                                String operationId, @NonNull BigDecimal amount) {
            if (failBudgetReserve) {
                return ReserveOutcome.INSUFFICIENT;
            }
            return delegate.reserve(tenantId, runId, operationId, amount);
        }

        @Override
        public boolean settle(@NonNull String tenantId, @NonNull String reservationId,
                              @NonNull BigDecimal actualAmount, String modelId) {
            return delegate.settle(tenantId, reservationId, actualAmount, modelId);
        }

        @Override
        public boolean release(@NonNull String tenantId, @NonNull String reservationId,
                               @NonNull String reason) {
            if (failBudgetRelease) {
                throw new IllegalStateException("注入：预算释放失败");
            }
            return delegate.release(tenantId, reservationId, reason);
        }

        @Override
        public @NonNull Optional<Reservation> findByOperation(@NonNull String tenantId,
                                                              @NonNull String operationId) {
            return delegate.findByOperation(tenantId, operationId);
        }

        @Override
        public @NonNull List<OrphanedReservation> findOrphaned(@NonNull String tenantId) {
            return delegate.findOrphaned(tenantId);
        }

        @Override
        public @NonNull Optional<AccountSnapshot> accountSnapshot(@NonNull String tenantId,
                                                                  @NonNull String currency) {
            return delegate.accountSnapshot(tenantId, currency);
        }

        @Override
        public boolean isConsistent(@NonNull String tenantId, @NonNull String currency) {
            return delegate.isConsistent(tenantId, currency);
        }
    }

    /** 结果事件端口：可注入「写入失败」。 */
    private final class FaultyResultEvents implements ResultEventPort {

        private final ResultEventPort delegate = new JdbcResultEventRepository(appPool);

        @Override
        public boolean recordIfAbsent(@NonNull ResultEvent event) {
            if (failResultEventWrite) {
                throw new IllegalStateException("注入：结果事件写入失败");
            }
            return delegate.recordIfAbsent(event);
        }

        @Override
        public @NonNull Optional<ResultEvent> findByInvocation(
                @NonNull String tenantId, @NonNull String invocationId) {
            return delegate.findByInvocation(tenantId, invocationId);
        }
    }

    /** 账本端口：可注入「落账失败」。 */
    private final class FaultyLedger implements RunToolLedgerPort {

        private final RunToolLedgerPort delegate = new JdbcToolOperationLedger(appPool);

        @Override
        public @NonNull Registration register(@NonNull String tenantId, @NonNull String runId,
                                              @NonNull String toolName, @NonNull String riskLevel,
                                              @NonNull String idempotencyKey,
                                              @NonNull String requestDigest,
                                              @NonNull Map<String, Object> arguments,
                                              long fencingToken) {
            return delegate.register(tenantId, runId, toolName, riskLevel, idempotencyKey,
                    requestDigest, arguments, fencingToken);
        }

        @Override
        public void recordResult(@NonNull String tenantId, @NonNull String operationId,
                                  @NonNull ToolOperationState result, long fencingToken,
                                  String errorClass, String errorMessage) {
            if (failLedgerPost) {
                throw new IllegalStateException("注入：账本落账失败");
            }
            delegate.recordResult(tenantId, operationId, result, fencingToken,
                    errorClass, errorMessage);
        }

        @Override
        public void reconcile(@NonNull String tenantId, @NonNull String operationId,
                              @NonNull ToolOperationState reconciled, @NonNull String source) {
            delegate.reconcile(tenantId, operationId, reconciled, source);
        }

        @Override
        public void manualDispose(@NonNull String tenantId, @NonNull String operationId,
                                  @NonNull String disposedBy, @NonNull String note) {
            delegate.manualDispose(tenantId, operationId, disposedBy, note);
        }

        @Override
        public @NonNull List<UnknownOperation> findUnknownOperations(@NonNull String tenantId) {
            return delegate.findUnknownOperations(tenantId);
        }

        @Override
        public @NonNull Optional<Registration> findByIdempotencyKey(@NonNull String tenantId,
                                                                     @NonNull String idempotencyKey) {
            return delegate.findByIdempotencyKey(tenantId, idempotencyKey);
        }

        @Override
        public @NonNull Optional<String> findRequestDigest(@NonNull String tenantId,
                                                            @NonNull String idempotencyKey) {
            return delegate.findRequestDigest(tenantId, idempotencyKey);
        }

        @Override
        public @NonNull Optional<Registration> findByOperationId(@NonNull String tenantId,
                                                                  @NonNull String operationId) {
            return delegate.findByOperationId(tenantId, operationId);
        }
    }

    // ── 编排器装配 ────────────────────────────────────────────────

    private CapApprovalPort approvals() {
        return new JdbcCapApprovalRepository(appPool);
    }

    private CompensationFramework compensation() {
        return new CompensationFramework(approvals(), new FaultyBudget(),
                new JdbcCompensationTaskRepository(appPool));
    }

    private CapExecutionFlow flow(ApprovalPolicy policy) {
        InvocationValidator validator = new InvocationValidator(
                new JdbcAuthzVersionRepository(appPool), ROLE_STUB, resolver,
                new JdbcCapabilitySnapshotRepository(appPool), EMPTY_MANIFEST);
        ExecutionAdmissionChecker admission = new ExecutionAdmissionChecker(
                new JdbcAuthzVersionRepository(appPool), resolver,
                new JdbcCapabilitySnapshotRepository(appPool));
        IdempotencyGuard idempotency = new IdempotencyGuard(
                new FaultyLedger(), new FaultyResultEvents());
        return new CapExecutionFlow(
                validator, resolver, admission, idempotency,
                new JdbcAuthzVersionRepository(appPool), ROLE_STUB,
                new JdbcCapabilitySnapshotRepository(appPool), policy,
                new ApprovalStateMachine(approvals(), new ApprovalBindingService()),
                compensation(),
                new JdbcCapToolDefRepository(appPool),
                new FaultyBudget(), new FaultyLedger(),
                new JdbcExecutionIntentRepository(appPool),
                new FaultyResultEvents(),
                BigDecimal.ONE, new CapEnforcementSwitch(true),
                DecisionAuditService.noop());
    }

    /** 需要审批的策略（P3 走 CAP 审批链）。 */
    private static ApprovalPolicy requireApproval() {
        return handler -> ApprovalPolicy.ApprovalDecision.required("it-comp");
    }

    /** 记录型工具替身（可注入失败 + 计数执行次数）。 */
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
            public @NonNull ToolRiskLevel riskLevel() {
                return ToolRiskLevel.WRITE_EXTERNAL;
            }

            @Override
            public void validate(@NonNull Map<String, Object> arguments) {
            }

            @Override
            public @NonNull ToolResult execute(@NonNull ToolExecutionContext context,
                                                @NonNull Map<String, Object> arguments) {
                toolExecutions.incrementAndGet();
                return ToolResult.of("ok");
            }
        };
    }

    /** 抛出「结果未知」的工具替身。 */
    private ToolHandler unknownTool() {
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
            public @NonNull ToolRiskLevel riskLevel() {
                return ToolRiskLevel.WRITE_EXTERNAL;
            }

            @Override
            public void validate(@NonNull Map<String, Object> arguments) {
            }

            @Override
            public @NonNull ToolResult execute(@NonNull ToolExecutionContext context,
                                                @NonNull Map<String, Object> arguments) {
                toolExecutions.incrementAndGet();
                throw ToolExecutionException.unknownResult("注入：响应丢失", null);
            }
        };
    }

    private RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(runId, 1L, Instant.now().plusSeconds(60), 1);
    }

    private ModelResponse.ToolCallRequest call() {
        return new ModelResponse.ToolCallRequest(TOOL_ID, Map.of("to", "a@example.com"), "call-1");
    }

    /** 授予一份与本次调用绑定的 CAP 审批。 */
    private String grantApproval(Map<String, Object> args) {
        ApprovalScope scope = ApprovalScope.of(TOOL_ID,
                Map.of("to", new ApprovalScope.Constraint("eq", "a@example.com")));
        ApprovalBindingService.Binding binding =
                new ApprovalBindingService().generate(scope, args);
        EffectiveClosure closure = resolver.resolve(tenantA, agentId, 1);
        return approvals().grant(new CapApprovalPort.CapApprovalGrant(
                tenantA, CapExecutionFlow.DEFAULT_PRINCIPAL_ID, TOOL_ID, "resource",
                binding.approvalScopeHash(), scope.canonicalText(), binding.effectiveArgsHash(),
                ClosureFingerprint.of(closure), 1L, "it-approver", "SCOPE_ALL",
                Instant.now().plusSeconds(3600)));
    }

    // ── DB 断言助手 ────────────────────────────────────────────────

    private String approvalState(String approvalId) {
        return adminQuery("SELECT approval_state::text FROM cap_approval"
                + " WHERE tenant_id = '" + tenantA + "'::uuid AND approval_id = '"
                + approvalId + "'::uuid");
    }

    private long approvalCount() {
        return adminQueryLong("SELECT count(*) FROM cap_approval WHERE tenant_id = '"
                + tenantA + "'::uuid");
    }

    private long compensationCount(String kind) {
        return adminQueryLong("SELECT count(*) FROM cap_compensation_task WHERE tenant_id = '"
                + tenantA + "'::uuid AND kind = '" + kind + "'");
    }

    private long reservedAmount() {
        return adminQueryLong("SELECT reserved_amount FROM budget_account WHERE tenant_id = '"
                + tenantA + "'::uuid");
    }

    private long attemptCount(String operationId) {
        return adminQueryLong("SELECT count(*) FROM tool_operation_attempt WHERE tenant_id = '"
                + tenantA + "'::uuid AND operation_id = '" + operationId + "'::uuid");
    }

    private String operationState(String operationId) {
        return adminQuery("SELECT state::text FROM tool_operation WHERE tenant_id = '"
                + tenantA + "'::uuid AND operation_id = '" + operationId + "'::uuid");
    }

    private String operationIdOf(CapToolDecision decision) {
        assertThat(decision.operationId()).as("决策必须带操作标识").isNotBlank();
        return decision.operationId();
    }

    // ══════════════════════════════════════════════════════════════
    // COMP-1~6
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("COMP-1 RESERVED 后预算失败 → 审批 CANCELLED、预算无残留")
    class Comp1BudgetFailureAfterReserve {

        @Test
        @DisplayName("⚠️ 审批已预占后预算被拒 → 审批作废 + 额度无残留 + 工具零执行")
        void approvalIsCancelledAndBudgetHasNoResidue() {
            Map<String, Object> args = Map.of("to", "a@example.com");
            String approvalId = grantApproval(args);
            failBudgetReserve = true;

            CapToolDecision decision = flow(requireApproval())
                    .invoke(tenantA, task(), call(), tool());

            assertThat(decision.decision().execution())
                    .isEqualTo(AuthorizationDecision.Execution.DENY);
            assertThat(decision.decision().reasonCode())
                    .isEqualTo(ReasonCode.BUDGET_EXCEEDED);
            assertThat(approvalState(approvalId))
                    .as("⛔ 审批未作废：它会悬在 RESERVED 挡住后续调用")
                    .isEqualTo("CANCELLED");
            assertThat(reservedAmount())
                    .as("⛔ 预算额度有残留：失败的预留没有被退回")
                    .isZero();
            assertThat(toolExecutions.get())
                    .as("⛔ 预算被拒后绝不能发起外部副作用")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("COMP-2 预算成功后准入失败 → 取消审批 + 释放预算")
    class Comp2AdmissionFailureReleases {

        @Test
        @DisplayName("⚠️ 准入失效 → 审批作废 + 预算释放 + 工具零执行")
        void admissionFailureCancelsApprovalAndReleasesBudget() {
            Map<String, Object> args = Map.of("to", "a@example.com");
            String approvalId = grantApproval(args);

            // ⚠️ 撤权发生在 Phase A 之后、准入之前 → 准入失效（§5.3 步骤 13）
            long before = reservedAmount();

            CapToolDecision decision = flowWithRevocation(requireApproval())
                    .invoke(tenantA, task(), call(), tool());

            assertThat(decision.decision().reasonCode())
                    .isEqualTo(ReasonCode.EXEC_ADMISSION_STALE);
            assertThat(approvalState(approvalId))
                    .as("⛔ 准入失败后审批未作废")
                    .isEqualTo("CANCELLED");
            assertThat(reservedAmount())
                    .as("⛔ 准入失败后预算额度未释放")
                    .isEqualTo(before);
            assertThat(toolExecutions.get()).isZero();
        }
    }

    @Nested
    @DisplayName("COMP-3 释放失败 → 持久化补偿任务")
    class Comp3ReleaseFailurePersistsTask {

        @Test
        @DisplayName("⚠️ 补偿动作失败 → 补偿任务落库（不只记日志）")
        void failedCompensationIsPersisted() {
            Map<String, Object> args = Map.of("to", "a@example.com");
            grantApproval(args);
            failBudgetRelease = true;

            CapToolDecision decision = flowWithRevocation(requireApproval())
                    .invoke(tenantA, task(), call(), tool());

            assertThat(decision.decision().reasonCode())
                    .isEqualTo(ReasonCode.EXEC_ADMISSION_STALE);
            assertThat(compensationCount("RELEASE_BUDGET"))
                    .as("⛔ 补偿失败未落库：额度悬空只能靠人肉排查")
                    .isPositive();
        }
    }

    @Nested
    @DisplayName("COMP-4 成功 + POST 失败 → SUCCEEDED + ledger 重试")
    class Comp4PostFailure {

        @Test
        @DisplayName("⚠️ 账本 POST 失败 → executionStatus=SUCCEEDED、ledger=RETRYING")
        void succeededButNotPostedIsRetrying() {
            grantApproval(Map.of("to", "a@example.com"));
            failLedgerPost = true;

            CapToolDecision decision = flow(requireApproval())
                    .invoke(tenantA, task(), call(), tool());

            assertThat(decision.decision().status())
                    .as("⛔ 副作用确实成功，状态必须是 SUCCEEDED（不得因落账失败改写事实）")
                    .isEqualTo(AuthorizationDecision.Status.SUCCEEDED);
            assertThat(decision.decision().reasonCode()).isEqualTo(ReasonCode.SUCCESS);
            assertThat(decision.decision().ledger())
                    .as("⛔ 落账未完成，ledger 必须是 RETRYING")
                    .isEqualTo(AuthorizationDecision.Ledger.RETRYING);
            assertThat(compensationCount("RETRY_LEDGER_POST"))
                    .as("⛔ 未落「重试落账」任务：结局无处留存")
                    .isPositive();
            assertThat(toolExecutions.get())
                    .as("⚠️ 工具只应执行一次")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("COMP-5 UNKNOWN 上幂等并发重试 + 人工确认 → 仅一次终态")
    class Comp5UnknownSingleTerminalState {

        @Test
        @DisplayName("⚠️ UNKNOWN 后重试不得重复执行；人工处置只落一个终态")
        void unknownIsNotRetriedAndHasSingleTerminalState() {
            grantApproval(Map.of("to", "a@example.com"));

            CapToolDecision decision = flow(requireApproval())
                    .invoke(tenantA, task(), call(), unknownTool());
            String operationId = operationIdOf(decision);

            assertThat(decision.decision().status())
                    .isEqualTo(AuthorizationDecision.Status.UNKNOWN);
            assertThat(decision.decision().reasonCode()).isEqualTo(ReasonCode.EXEC_UNKNOWN);
            assertThat(operationState(operationId)).isEqualTo("UNKNOWN");

            // ⚠️ 同幂等键重试：不得再次执行副作用
            CapToolDecision retry = flow(requireApproval())
                    .invoke(tenantA, task(), call(), unknownTool());

            assertThat(toolExecutions.get())
                    .as("⛔ UNKNOWN 后重试触发了外部执行 = 重复副作用")
                    .isEqualTo(1);

            // ⚠️ 人工确认：只落一个终态
            new JdbcToolOperationLedger(appPool)
                    .manualDispose(tenantA, operationId, "it-operator", "人工确认已发放");

            assertThat(operationState(operationId))
                    .as("⛔ 终态不是唯一的")
                    .isEqualTo("MANUAL_DISPOSED");
            assertThat(attemptCount(operationId))
                    .as("⛔ attempt 数 > 1：说明发生了重复调用尝试")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("COMP-6 确定成功 → 结果持久化失败 → 重启 → 落账 SUCCEEDED 且外部只执行一次")
    class Comp6RestartRecovery {

        @Test
        @DisplayName("⚠️⚠️ 重启后只补记落账，绝不重新 execute（硬门槛）")
        void restartRecoversLedgerWithoutReExecuting() {
            grantApproval(Map.of("to", "a@example.com"));
            failResultEventWrite = true;

            CapToolDecision decision = flow(requireApproval())
                    .invoke(tenantA, task(), call(), tool());
            String operationId = operationIdOf(decision);

            assertThat(decision.decision().status())
                    .isEqualTo(AuthorizationDecision.Status.SUCCEEDED);
            assertThat(decision.decision().ledger())
                    .as("⛔ 结果未落盘 → 落账待重试，ledger 必须是 RETRYING")
                    .isEqualTo(AuthorizationDecision.Ledger.RETRYING);
            assertThat(operationState(operationId))
                    .as("⛔ 14a 失败后不得直接落账（§5.3「结果事件必须先落盘」）")
                    .isEqualTo("IN_FLIGHT");
            assertThat(compensationCount("RETRY_LEDGER_POST"))
                    .as("⛔ 结局未进补偿任务：重启后无从恢复")
                    .isPositive();

            // ══ 模拟进程重启：新建恢复服务（不复用任何内存态）══════════
            failResultEventWrite = false;
            ResultRecoveryService recovery = new ResultRecoveryService(
                    new JdbcExecutionIntentRepository(appPool),
                    new JdbcResultEventRepository(appPool),
                    new JdbcToolOperationLedger(appPool),
                    new JdbcCompensationTaskRepository(appPool),
                    approvals());
            recovery.recover(tenantA, 50);

            assertThat(operationState(operationId))
                    .as("⛔ 重启后未补记落账")
                    .isEqualTo("SUCCEEDED");
            assertThat(attemptCount(operationId))
                    .as("⛔ attempt 数 > 1：恢复过程产生了新的调用尝试")
                    .isEqualTo(1);
            assertThat(toolExecutions.get())
                    .as("⛔⚠️ 外部工具被执行了多次 = 重复副作用（COMP-6 硬门槛）")
                    .isEqualTo(1);
            assertThat(new JdbcResultEventRepository(appPool)
                    .findByInvocation(tenantA, operationId))
                    .as("⛔ 结果事件未被补写：确定结果仍然没有落盘")
                    .isPresent();
        }
    }

    /**
     * 构造一个「Phase A 之后授权版本被抬高」的编排器（模拟准入前撤权）。
     */
    private CapExecutionFlow flowWithRevocation(ApprovalPolicy policy) {
        // ⚠️ 必须**共用同一个**计数实例：
        //    授权版本的三次权威读（Phase A / 校验器 / 准入）发生在同一进程内，
        //    各自 new 一个实例 = 各自从 1 开始数 → 准入点永远读不到被抬高的版本，
        //    场景就测成了「正常放行」。
        BumpingAuthz authz = new BumpingAuthz(appPool);
        InvocationValidator validator = new InvocationValidator(
                authz, ROLE_STUB, resolver,
                new JdbcCapabilitySnapshotRepository(appPool), EMPTY_MANIFEST);
        ExecutionAdmissionChecker admission = new ExecutionAdmissionChecker(
                authz, resolver, new JdbcCapabilitySnapshotRepository(appPool));
        IdempotencyGuard idempotency = new IdempotencyGuard(
                new FaultyLedger(), new FaultyResultEvents());
        return new CapExecutionFlow(
                validator, resolver, admission, idempotency,
                authz, ROLE_STUB,
                new JdbcCapabilitySnapshotRepository(appPool), policy,
                new ApprovalStateMachine(approvals(), new ApprovalBindingService()),
                compensation(),
                new JdbcCapToolDefRepository(appPool),
                new FaultyBudget(), new FaultyLedger(),
                new JdbcExecutionIntentRepository(appPool),
                new FaultyResultEvents(),
                BigDecimal.ONE, new CapEnforcementSwitch(true),
                DecisionAuditService.noop());
    }

    /**
     * 授权版本读取替身：第 N 次之后读到被抬高的版本（模拟跨实例撤权）。
     */
    private static final class BumpingAuthz implements AuthzVersionPort {

        private final AuthzVersionPort delegate;
        private int reads;

        BumpingAuthz(DataSource pool) {
            this.delegate = new JdbcAuthzVersionRepository(pool);
        }

        @Override
        public @NonNull Optional<AuthzVersion> find(@NonNull String tenantId,
                                                     @NonNull String principalId) {
            return delegate.find(tenantId, principalId);
        }

        @Override
        public @NonNull AuthzVersion require(@NonNull String tenantId,
                                             @NonNull String principalId) {
            reads++;
            // ⚠️ Phase A 读 1 次、校验器读 1 次 → 第 3 次读即准入点；
            //    从第 3 次起返回被抬高的版本 → 准入必然失效。
            if (reads >= 3) {
                return new AuthzVersion(tenantId, principalId, 99L, Instant.EPOCH);
            }
            return delegate.require(tenantId, principalId);
        }

        @Override
        public @NonNull AuthzVersion initialize(@NonNull String tenantId,
                                                @NonNull String principalId) {
            return delegate.initialize(tenantId, principalId);
        }

        @Override
        public long bump(@NonNull String tenantId, @NonNull String principalId) {
            return delegate.bump(tenantId, principalId);
        }

        @Override
        public AuthzWatermark watermark(@NonNull String tenantId) {
            return delegate.watermark(tenantId);
        }
    }
}
