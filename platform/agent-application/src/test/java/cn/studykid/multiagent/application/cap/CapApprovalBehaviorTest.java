package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.CapToolDecision;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * P3 行为测试（P3-T8 · §9.1 S6a~e / S7 / S8 / S9）。
 *
 * <p>⚠️ <b>本类断言的是「四字段」而非「成功/失败」</b>（§8.3 冻结门槛）：
 * {@code authorizationDecision} / {@code executionDecision} / {@code executionStatus} /
 * {@code reasonCode} 必须<b>逐字段</b>匹配。
 * 断言粒度粗一档，就会漏掉「授权通过、仅执行准入拒绝」这类最容易被掩盖的错误分类
 * （S6/S7 尤其如此：授权是 ALLOW 的，只有 executionDecision 是 DENY）。
 *
 * <p>⚠️ <b>越权拒绝与正向授权必须成对</b>：每个「允许执行」旁边都有「越权必须被拒」。
 */
class CapApprovalBehaviorTest {

    private static final String TENANT = "t-cap-approval-behav";
    private static final String AGENT = "homework_tutor";
    private static final String SKILL = "profile_skill";
    private static final String TOOL = "send_email";
    private static final String RUN_ID = "run-cap-approval-behav";

    private CapFixture cap;
    private CapExecutionFixture exec;
    private PermissionResolver resolver;
    private BumpingAuthzVersions authz;

    @BeforeEach
    void setUp() {
        cap = new CapFixture()
                .withSkill(SKILL, 1, CapabilityRiskLevel.WRITE_EXTERNAL, List.of(TOOL))
                .withTool(TOOL, 1, CapabilityRiskLevel.WRITE_EXTERNAL, "[]")
                .withAgent(TENANT, AGENT, 1, List.of(SKILL), "{}")
                .withAuthzVersion(TENANT, CapExecutionFlow.DEFAULT_PRINCIPAL_ID, 1L)
                .withRolePermission("WORKER", "tool:invoke");

        exec = new CapExecutionFixture();
        resolver = new PermissionResolver(cap.agentDefs(), cap.skillDefs(), cap.toolDefs(),
                cap.serverDefs(), cap.publishedVersions(), cap.manifest());
        authz = new BumpingAuthzVersions(cap.authzVersions());

        cap.withSnapshot(new CapabilitySnapshot(RUN_ID, TENANT, AGENT,
                ClosureFingerprint.canonicalJson(resolver.resolveCurrent(TENANT, AGENT)),
                1, 0L,
                ClosureFingerprint.of(resolver.resolveCurrent(TENANT, AGENT)),
                1L, Instant.EPOCH));
    }

    // ── 装配 ──────────────────────────────────────────────────────

    private CapExecutionFlow flow(ApprovalPolicy policy) {
        InvocationValidator validator = new InvocationValidator(authz, cap.rolePermissions(),
                resolver, cap.snapshots(), cap.manifest());
        ExecutionAdmissionChecker admission = new ExecutionAdmissionChecker(authz, resolver,
                cap.snapshots());
        IdempotencyGuard idempotency = new IdempotencyGuard(exec.ledgerPort(), exec.resultEventPort());
        return new CapExecutionFlow(
                validator, resolver, admission, idempotency, authz, cap.rolePermissions(),
                cap.snapshots(), policy,
                new ApprovalStateMachine(exec.capApprovalPort(), new ApprovalBindingService()),
                new CompensationFramework(exec.capApprovalPort(), exec.budgetPort(),
                        exec.compensationTaskPort()),
                cap.toolDefs(), exec.budgetPort(), exec.ledgerPort(), exec.intentPort(),
                exec.resultEventPort(), BigDecimal.ONE, new CapEnforcementSwitch(true));
    }

    private CapExecutionFlow flowRequiringApproval() {
        return flow(handler -> ApprovalPolicy.ApprovalDecision.required("test"));
    }

    private static ApprovalPolicy noApproval() {
        return handler -> ApprovalPolicy.ApprovalDecision.notRequired();
    }

    private ApprovalScope scope() {
        return ApprovalScope.of(TOOL, Map.of("to", new ApprovalScope.Constraint("eq",
                "a@example.com")));
    }

    private String grantApproval(Map<String, Object> args, Instant expiresAt) {
        ApprovalScope scope = scope();
        ApprovalBindingService.Binding binding =
                new ApprovalBindingService().generate(scope, args);
        return exec.capApprovalPort().grant(new CapApprovalPort.CapApprovalGrant(
                TENANT, CapExecutionFlow.DEFAULT_PRINCIPAL_ID, TOOL, "resource",
                binding.approvalScopeHash(), scope.canonicalText(), binding.effectiveArgsHash(),
                ClosureFingerprint.of(resolver.resolveCurrent(TENANT, AGENT)), 1L,
                "approver-1", "SCOPE_ALL", expiresAt));
    }

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(RUN_ID, 1L, Instant.now().plusSeconds(60), 1);
    }

    private static ModelResponse.ToolCallRequest call() {
        return new ModelResponse.ToolCallRequest(TOOL, Map.of("to", "a@example.com"), "call-1");
    }

    /** 断言四字段（§8.3 冻结门槛要求的断言粒度）。 */
    private static void assertFourFields(CapToolDecision decision,
                                         AuthorizationDecision.Authorization authz,
                                         AuthorizationDecision.Execution execution,
                                         AuthorizationDecision.Status status,
                                         ReasonCode reasonCode) {
        assertThat(decision.decision().authorization())
                .as("四字段之一：authorizationDecision").isEqualTo(authz);
        assertThat(decision.decision().execution())
                .as("四字段之二：executionDecision").isEqualTo(execution);
        assertThat(decision.decision().status())
                .as("四字段之三：executionStatus").isEqualTo(status);
        assertThat(decision.decision().reasonCode())
                .as("四字段之四：reasonCode").isEqualTo(reasonCode);
    }

    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("§9.1 S6a~e 审批五态")
    class ApprovalScenarios {

        @Test
        @DisplayName("S6a 审批不存在 → ALLOW / DENY / NOT_STARTED / APPROVAL_NOT_FOUND")
        void notFound() {
            CapToolDecision decision = flowRequiringApproval()
                    .invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertFourFields(decision, AuthorizationDecision.Authorization.ALLOW,
                    AuthorizationDecision.Execution.DENY,
                    AuthorizationDecision.Status.NOT_STARTED,
                    ReasonCode.APPROVAL_NOT_FOUND);
            assertThat(exec.toolExecutions).as("⛔ 审批缺失却执行了工具").isZero();
        }

        @Test
        @DisplayName("S6b 审批过期 → APPROVAL_EXPIRED")
        void expired() {
            grantApproval(call().arguments(), Instant.now().minusSeconds(1));

            CapToolDecision decision = flowRequiringApproval()
                    .invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertFourFields(decision, AuthorizationDecision.Authorization.ALLOW,
                    AuthorizationDecision.Execution.DENY,
                    AuthorizationDecision.Status.NOT_STARTED,
                    ReasonCode.APPROVAL_EXPIRED);
            assertThat(exec.toolExecutions).as("⛔ 审批过期却执行了工具").isZero();
        }

        @Test
        @DisplayName("S6c 参数被替换 → APPROVAL_SCOPE_MISMATCH")
        void scopeMismatch() {
            grantApproval(call().arguments(), Instant.now().plusSeconds(3600));

            ModelResponse.ToolCallRequest swapped = new ModelResponse.ToolCallRequest(
                    TOOL, Map.of("to", "attacker@example.com"), "call-1");

            CapToolDecision decision = flowRequiringApproval()
                    .invoke(TENANT, task(), swapped, exec.tool(TOOL));

            assertFourFields(decision, AuthorizationDecision.Authorization.ALLOW,
                    AuthorizationDecision.Execution.DENY,
                    AuthorizationDecision.Status.NOT_STARTED,
                    ReasonCode.APPROVAL_SCOPE_MISMATCH);
            assertThat(exec.toolExecutions).as("⛔ 参数被替换却执行了工具").isZero();
        }

        @Test
        @DisplayName("S6d 被其他调用预占 → APPROVAL_RESERVED_BY_OTHER")
        void reservedByOther() {
            String approvalId = grantApproval(call().arguments(), Instant.now().plusSeconds(3600));
            ApprovalBindingService.Binding binding =
                    new ApprovalBindingService().generate(scope(), call().arguments());

            exec.capApprovalPort().reserve(TENANT, approvalId, "inv-other", "op-other",
                    RUN_ID, binding.effectiveArgsHash(),
                    ClosureFingerprint.of(resolver.resolveCurrent(TENANT, AGENT)), 1L);

            CapToolDecision decision = flowRequiringApproval()
                    .invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertFourFields(decision, AuthorizationDecision.Authorization.ALLOW,
                    AuthorizationDecision.Execution.DENY,
                    AuthorizationDecision.Status.NOT_STARTED,
                    ReasonCode.APPROVAL_RESERVED_BY_OTHER);
            assertThat(exec.toolExecutions).as("⛔ 审批被他调用预占却执行了工具").isZero();
        }

        @Test
        @DisplayName("S6e 已消费重放 → APPROVAL_REPLAY_REJECTED")
        void replayRejected() {
            String approvalId = grantApproval(call().arguments(), Instant.now().plusSeconds(3600));
            ApprovalBindingService.Binding binding =
                    new ApprovalBindingService().generate(scope(), call().arguments());

            exec.capApprovalPort().reserve(TENANT, approvalId, "inv-1", "op-1",
                    RUN_ID, binding.effectiveArgsHash(),
                    ClosureFingerprint.of(resolver.resolveCurrent(TENANT, AGENT)), 1L);
            exec.capApprovalPort().markIntentSubmitted(TENANT, approvalId, "inv-1");
            exec.capApprovalPort().consume(TENANT, approvalId, "inv-1", "SUCCEEDED");

            CapToolDecision decision = flowRequiringApproval()
                    .invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertFourFields(decision, AuthorizationDecision.Authorization.ALLOW,
                    AuthorizationDecision.Execution.DENY,
                    AuthorizationDecision.Status.NOT_STARTED,
                    ReasonCode.APPROVAL_REPLAY_REJECTED);
            assertThat(exec.toolExecutions).as("⛔ 已消费重放却执行了工具").isZero();
        }

        @Test
        @DisplayName("✅ 正向：审批有效 → ALLOW / ALLOW / SUCCEEDED / SUCCESS")
        void approved() {
            grantApproval(call().arguments(), Instant.now().plusSeconds(3600));

            CapToolDecision decision = flowRequiringApproval()
                    .invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertFourFields(decision, AuthorizationDecision.Authorization.ALLOW,
                    AuthorizationDecision.Execution.ALLOW,
                    AuthorizationDecision.Status.SUCCEEDED, ReasonCode.SUCCESS);
            assertThat(exec.toolExecutions).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("§9.1 S7 预算不足")
    class BudgetScenario {

        @Test
        @DisplayName("S7 预算不足 → ALLOW / DENY / NOT_STARTED / BUDGET_EXCEEDED")
        void budgetExceeded() {
            grantApproval(call().arguments(), Instant.now().plusSeconds(3600));
            exec.budgetReserveOutcome = BudgetPort.ReserveOutcome.INSUFFICIENT;

            CapToolDecision decision = flowRequiringApproval()
                    .invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertFourFields(decision, AuthorizationDecision.Authorization.ALLOW,
                    AuthorizationDecision.Execution.DENY,
                    AuthorizationDecision.Status.NOT_STARTED,
                    ReasonCode.BUDGET_EXCEEDED);
            assertThat(exec.toolExecutions).as("⛔ 预算不足却执行了工具").isZero();
        }
    }

    @Nested
    @DisplayName("§9.1 S8 响应丢失")
    class UnknownScenario {

        @Test
        @DisplayName("S8 响应丢失 → ALLOW / ALLOW / UNKNOWN / EXEC_UNKNOWN")
        void resultUnknown() {
            exec.toolFailure = ToolExecutionException.unknownResult("响应丢失", null);

            CapToolDecision decision = flow(noApproval())
                    .invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertFourFields(decision, AuthorizationDecision.Authorization.ALLOW,
                    AuthorizationDecision.Execution.ALLOW,
                    AuthorizationDecision.Status.UNKNOWN, ReasonCode.EXEC_UNKNOWN);
            assertThat(decision.decision().requiresReconciliation())
                    .as("⛔ UNKNOWN 必须进对账").isTrue();
        }
    }

    @Nested
    @DisplayName("§9.1 S9 成功但 POST 失败")
    class PostFailureScenario {

        @Test
        @DisplayName("S9 → ALLOW / ALLOW / SUCCEEDED / SUCCESS 且 ledger=RETRYING")
        void succeededButPostFailed() {
            exec.failLedgerPost = true;

            CapToolDecision decision = flow(noApproval())
                    .invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertFourFields(decision, AuthorizationDecision.Authorization.ALLOW,
                    AuthorizationDecision.Execution.ALLOW,
                    AuthorizationDecision.Status.SUCCEEDED, ReasonCode.SUCCESS);
            assertThat(decision.decision().ledger())
                    .as("⛔ 落账失败 → ledger 必须 RETRYING")
                    .isEqualTo(AuthorizationDecision.Ledger.RETRYING);
            assertThat(exec.toolExecutions)
                    .as("⚠️ 工具只应执行一次（落账失败不得触发重新执行）")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 落账失败 → 必须落「重试落账」补偿任务（结局不得凭空丢失）")
        void postFailureEnqueuesCompensation() {
            exec.failLedgerPost = true;

            flow(noApproval()).invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(exec.compensationTasks.values())
                    .as("⛔ 结局未落补偿任务：重启后无从恢复")
                    .anyMatch(task -> task.kind() == cn.studykid.multiagent.core.cap
                            .CapCompensationTask.CompensationKind.RETRY_LEDGER_POST);
        }
    }

    @Nested
    @DisplayName("§7.4 双维度结果")
    class DualDimension {

        @Test
        @DisplayName("✅ 全部落盘 → ledger=RECORDED")
        void recorded() {
            CapToolDecision decision = flow(noApproval())
                    .invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(decision.decision().ledger())
                    .isEqualTo(AuthorizationDecision.Ledger.RECORDED);
        }

        @Test
        @DisplayName("⚠️ 结果事件写不进去且补偿任务也落不下去 → ledger=NEEDS_MANUAL")
        void needsManualWhenOutcomeLost() {
            exec.failResultEventWrite = true;
            exec.failLedgerPost = true;
            // ⚠️ 连补偿任务都落不下去 → 结局彻底无处留存
            exec.failCompensationEnqueue = true;

            CapToolDecision decision = flow(noApproval())
                    .invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(decision.decision().ledger())
                    .as("⛔ 结局彻底丢失时不得谎称可重试")
                    .isEqualTo(AuthorizationDecision.Ledger.NEEDS_MANUAL);
        }
    }
}
