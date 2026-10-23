package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.AuthzVersion;
import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.CapToolDecision;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * P2 执行准入行为测试（§9.1 场景 S1 / S3b / S3f + Schema 失败 + 幂等 + 准入失效）。
 *
 * <p>⚠️ <b>本类断言的是「四字段」而非「成功/失败」</b>（§8.3 冻结门槛）：
 * {@code authorizationDecision} / {@code executionDecision} / {@code executionStatus} /
 * {@code reasonCode} 必须<b>逐字段</b>匹配。断言粒度粗一档，就会漏掉
 * 「授权通过、仅执行准入拒绝」这类最容易被掩盖的错误分类。
 *
 * <p>⚠️ <b>越权拒绝与正向授权必须成对</b>（项目安全铁律）：
 * 每个「允许执行」的场景旁边都必须有一个「越权必须被拒」的场景，
 * 否则测试只能证明「闸门没挡住所有人」，证明不了「闸门挡住了该挡的人」。
 */
class CapExecutionFlowTest {

    private static final String TENANT = "t-cap-001";
    private static final String AGENT = "homework_tutor";
    private static final String SKILL = "profile_skill";
    private static final String TOOL = "get_student_profile";
    private static final String RUN_ID = "run-cap-001";

    private CapFixture cap;
    private CapExecutionFixture exec;
    private PermissionResolver resolver;
    private BumpingAuthzVersions authz;

    @BeforeEach
    void setUp() {
        cap = new CapFixture()
                .withSkill(SKILL, 1, CapabilityRiskLevel.READ, List.of(TOOL))
                .withTool(TOOL, 1, CapabilityRiskLevel.READ, "[]")
                .withAgent(TENANT, AGENT, 1, List.of(SKILL), "{}")
                .withAuthzVersion(TENANT, CapExecutionFlow.DEFAULT_PRINCIPAL_ID, 1L)
                .withRolePermission("WORKER", "tool:invoke");

        exec = new CapExecutionFixture();
        resolver = new PermissionResolver(cap.agentDefs(), cap.skillDefs(), cap.toolDefs(),
                cap.serverDefs(), cap.publishedVersions(), cap.manifest());
        authz = new BumpingAuthzVersions(cap.authzVersions());

        // ⚠️ 快照必须带<b>真实</b>闭包指纹：Phase A 步骤 7 要拿它比对，
        //    指纹对不上会被判 CONFIG_FINGERPRINT_STALE，根本走不到后续步骤。
        cap.withSnapshot(new CapabilitySnapshot(RUN_ID, TENANT, AGENT,
                ClosureFingerprint.canonicalJson(resolver.resolveCurrent(TENANT, AGENT)),
                1, 0L,
                ClosureFingerprint.of(resolver.resolveCurrent(TENANT, AGENT)),
                1L, Instant.EPOCH));
    }

    // ── 编排器装配 ────────────────────────────────────────────────

    private CapExecutionFlow flow() {
        return flow(new CapEnforcementSwitch(true));
    }

    private CapExecutionFlow flow(CapEnforcementSwitch capSwitch) {
        return flow(capSwitch, handler -> ApprovalPolicy.ApprovalDecision.notRequired());
    }

    private CapExecutionFlow flow(ApprovalPolicy approvalPolicy) {
        return flow(new CapEnforcementSwitch(true), approvalPolicy);
    }

    private CapExecutionFlow flow(CapEnforcementSwitch capSwitch, ApprovalPolicy approvalPolicy) {
        InvocationValidator validator = new InvocationValidator(authz, cap.rolePermissions(),
                resolver, cap.snapshots(), cap.manifest());
        ExecutionAdmissionChecker admission = new ExecutionAdmissionChecker(authz, resolver,
                cap.snapshots());
        IdempotencyGuard idempotency = new IdempotencyGuard(exec.ledgerPort(), exec.resultEventPort());
        // ⚠️ P3：审批走 CAP 状态机（cap_approval），不再是既有 approval 表的一次性消费。
        ApprovalStateMachine stateMachine = new ApprovalStateMachine(exec.capApprovalPort(),
                new ApprovalBindingService());
        CompensationFramework compensation = new CompensationFramework(exec.capApprovalPort(),
                exec.budgetPort(), exec.compensationTaskPort());
        return new CapExecutionFlow(
                validator, resolver, admission, idempotency, authz, cap.rolePermissions(),
                cap.snapshots(), approvalPolicy, stateMachine, compensation, cap.toolDefs(),
                exec.budgetPort(), exec.ledgerPort(), exec.intentPort(),
                exec.resultEventPort(), BigDecimal.ONE, capSwitch, DecisionAuditService.noop());
    }

    /**
     * 授予一份 CAP 审批（与本次调用参数、闭包指纹、授权版本绑定）。
     *
     * @param args 调用参数
     * @return 审批 id
     */
    private String grantApproval(Map<String, Object> args) {
        ApprovalScope scope = new ApprovalScope(TOOL, Map.of(), null, null, null);
        ApprovalBindingService.Binding binding =
                new ApprovalBindingService().generate(scope, args);
        return exec.capApprovalPort().grant(new cn.studykid.multiagent.ports.persistence
                .CapApprovalPort.CapApprovalGrant(
                TENANT, CapExecutionFlow.DEFAULT_PRINCIPAL_ID, TOOL, "resource",
                binding.approvalScopeHash(), scope.canonicalText(), binding.effectiveArgsHash(),
                ClosureFingerprint.of(resolver.resolveCurrent(TENANT, AGENT)), 1L,
                "approver-1", "SCOPE_ALL", Instant.now().plusSeconds(3600)));
    }

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(RUN_ID, 1L, Instant.now().plusSeconds(60), 1);
    }

    private static ModelResponse.ToolCallRequest call() {
        return new ModelResponse.ToolCallRequest(TOOL, Map.of("studentId", "stu_0123456789abcdef"),
                "call-1");
    }

    // ── S1 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("S1 已授权成功")
    class AuthorizedSuccess {

        @Test
        @DisplayName("✅ 四字段 = ALLOW / ALLOW / SUCCEEDED / SUCCESS")
        void successProducesExactFourFields() {
            CapToolDecision decision = flow().invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(decision.decision().authorization())
                    .as("§9.1 S1：授权必须 ALLOW")
                    .isEqualTo(AuthorizationDecision.Authorization.ALLOW);
            assertThat(decision.decision().execution())
                    .as("§9.1 S1：执行准入必须 ALLOW")
                    .isEqualTo(AuthorizationDecision.Execution.ALLOW);
            assertThat(decision.decision().status())
                    .as("§9.1 S1：执行状态必须 SUCCEEDED")
                    .isEqualTo(AuthorizationDecision.Status.SUCCEEDED);
            assertThat(decision.decision().reasonCode())
                    .as("§9.1 S1：成功原因码固定为 SUCCESS")
                    .isEqualTo(ReasonCode.SUCCESS);
            assertThat(decision.decision().ledger())
                    .as("§7.4：结果事件与账本均已落盘 → RECORDED")
                    .isEqualTo(AuthorizationDecision.Ledger.RECORDED);
            assertThat(exec.toolExecutions).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️⚠️ 执行意图必须先于外部副作用写入（§5.3 步骤 13a）")
        void executionIntentIsPersistedBeforeSideEffect() {
            flow().invoke(TENANT, task(), call(), exec.tool(TOOL));

            int intentIndex = exec.indexOf("intent:record");
            int executeIndex = exec.indexOf("tool:execute");
            assertThat(intentIndex)
                    .as("⚠️⚠️ 执行意图必须被写入（13a）")
                    .isGreaterThanOrEqualTo(0);
            assertThat(executeIndex)
                    .as("⚠️ 工具必须被调用（否则本用例无意义）")
                    .isGreaterThanOrEqualTo(0);
            assertThat(intentIndex)
                    .as("⚠️⚠️ 意图未持久化就发起外部副作用 → 崩溃后无从判断副作用是否已发生"
                            + "（重复副作用 = Sev-0）")
                    .isLessThan(executeIndex);
        }

        @Test
        @DisplayName("⚠️ 确定结果必须先入可恢复存储，再落账（§5.3 步骤 14a → 15）")
        void resultEventIsPersistedBeforeLedgerPost() {
            flow().invoke(TENANT, task(), call(), exec.tool(TOOL));

            int eventIndex = exec.indexOf("result-event:record:SUCCEEDED");
            int postIndex = exec.indexOf("ledger:record:SUCCEEDED");
            assertThat(eventIndex).isGreaterThanOrEqualTo(0);
            assertThat(postIndex).isGreaterThanOrEqualTo(0);
            assertThat(eventIndex)
                    .as("⚠️⚠️ 确定结果未落盘就更新账本 → 崩溃后结果丢失，"
                            + "只能按 UNKNOWN 人工处置")
                    .isLessThan(postIndex);
        }

        @Test
        @DisplayName("✅ 审批 RESERVED → INTENT_SUBMITTED 记账在意图之后")
        void approvalIntentSubmittedRecordedAfterIntent() {
            // ⚠️ 审批链必须真的走 CAP 状态机：先授予一份与本次参数绑定的审批。
            grantApproval(call().arguments());
            ApprovalPolicy requireApproval =
                    handler -> ApprovalPolicy.ApprovalDecision.required("test");

            flow(requireApproval).invoke(TENANT, task(), call(), exec.tool(TOOL));

            int intentIndex = exec.indexOf("intent:record");
            int approvalIndex = exec.indexOf("approval:intent-submitted");
            assertThat(intentIndex).isGreaterThanOrEqualTo(0);
            assertThat(approvalIndex)
                    .as("§5.3 步骤 13b：审批状态迁移必须发生在意图持久化之后")
                    .isGreaterThan(intentIndex);
        }
    }

    // ── S3b ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("S3b 撤权先于准入")
    class RevocationBeforeAdmission {

        @Test
        @DisplayName("⚠️⚠️ 撤权先提交 → EXEC_ADMISSION_STALE，阻断新副作用")
        void staleAdmissionBlocksSideEffect() {
            // ⚠️ 前两次权威读（流程内 + 校验器内）都读到 v1；
            //    第三次读（准入点）读到 v2 —— 模拟「撤权在 Phase A 之后、准入之前提交」。
            authz.bumpAfterRead(2, 2L);

            CapToolDecision decision = flow().invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(decision.decision().authorization())
                    .as("§9.1 S3b：授权在 Phase A 已通过，必须仍是 ALLOW")
                    .isEqualTo(AuthorizationDecision.Authorization.ALLOW);
            assertThat(decision.decision().execution())
                    .as("§9.1 S3b：执行准入必须 DENY")
                    .isEqualTo(AuthorizationDecision.Execution.DENY);
            assertThat(decision.decision().status())
                    .as("§9.1 S3b：副作用未启动 → NOT_STARTED")
                    .isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
            assertThat(decision.decision().reasonCode())
                    .as("§9.1 S3b：原因码必须精确为 EXEC_ADMISSION_STALE")
                    .isEqualTo(ReasonCode.EXEC_ADMISSION_STALE);
            assertThat(exec.toolExecutions)
                    .as("⚠️⚠️ 准入失效后绝不能发起外部副作用")
                    .isZero();
        }

        @Test
        @DisplayName("✅ 准入失效必须释放预算预留")
        void staleAdmissionReleasesBudget() {
            authz.bumpAfterRead(2, 2L);

            flow().invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(exec.indexOf("budget:release"))
                    .as("§5.3 步骤 13：准入失效 → 释放预算")
                    .isGreaterThanOrEqualTo(0);
        }
    }

    // ── S3f ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("S3f 准入后撤权")
    class RevocationAfterAdmission {

        @Test
        @DisplayName("⚠️ 准入通过后撤权不得静默撤销在途调用")
        void inFlightCallIsNotCancelled() {
            // ⚠️ 准入点（第 3 次读）仍读到 v1 → 准入通过；
            //    此后才发生撤权（第 4 次读起为 v2）。
            authz.bumpAfterRead(3, 2L);

            CapToolDecision decision = flow().invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(decision.decision().authorization())
                    .as("§9.1 S3f：授权 ALLOW")
                    .isEqualTo(AuthorizationDecision.Authorization.ALLOW);
            assertThat(decision.decision().execution())
                    .as("§9.1 S3f：执行准入 ALLOW（已进入执行阶段）")
                    .isEqualTo(AuthorizationDecision.Execution.ALLOW);
            assertThat(decision.decision().status())
                    .as("§9.1 S3f：状态取实际执行结果")
                    .isEqualTo(AuthorizationDecision.Status.SUCCEEDED);
            assertThat(decision.decision().reasonCode())
                    .as("§9.1 S3f：成功原因码 SUCCESS")
                    .isEqualTo(ReasonCode.SUCCESS);
            assertThat(exec.toolExecutions)
                    .as("⚠️⚠️ 准入后的在途调用不承诺取消（§5.5 契约 2）——"
                            + "撤销已发出的副作用是做不到的，硬要撤销只会制造不一致")
                    .isEqualTo(1);
        }
    }

    // ── Schema 失败 ───────────────────────────────────────────────

    @Nested
    @DisplayName("Schema 失败")
    class SchemaFailure {

        @Test
        @DisplayName("⚠️⚠️ PARAM_SCHEMA_FAIL，且不预占审批/不预留预算/不写 PRE")
        void schemaFailureDeniesWithoutSideEffects() {
            ToolHandler failing = new ToolHandler() {
                @Override
                public @NonNull String toolName() {
                    return TOOL;
                }

                @Override
                public @NonNull String inputSchema() {
                    return "{}";
                }

                @Override
                public @NonNull ToolRiskLevel riskLevel() {
                    return ToolRiskLevel.READ;
                }

                @Override
                public void validate(@NonNull Map<String, Object> arguments) {
                    throw new cn.studykid.multiagent.ports.tool.ToolValidationException(
                            "缺少必填参数");
                }

                @Override
                public @NonNull ToolResult execute(@NonNull ToolExecutionContext context,
                                                    @NonNull Map<String, Object> arguments) {
                    throw new AssertionError("Schema 校验失败后绝不能执行");
                }
            };

            CapToolDecision decision = flow().invoke(TENANT, task(), call(), failing);

            assertThat(decision.decision().authorization())
                    .as("§9.1：Schema 失败时授权仍为 ALLOW（属执行准入失败）")
                    .isEqualTo(AuthorizationDecision.Authorization.ALLOW);
            assertThat(decision.decision().execution())
                    .isEqualTo(AuthorizationDecision.Execution.DENY);
            assertThat(decision.decision().status())
                    .isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
            assertThat(decision.decision().reasonCode())
                    .as("§9.1：原因码必须精确为 PARAM_SCHEMA_FAIL")
                    .isEqualTo(ReasonCode.PARAM_SCHEMA_FAIL);
            assertThat(exec.indexOf("ledger:register"))
                    .as("§5.3 步骤 9：Schema 失败不得写 PRE")
                    .isEqualTo(-1);
            assertThat(exec.indexOf("budget:reserve"))
                    .as("§5.3 步骤 9：Schema 失败不得预留预算")
                    .isEqualTo(-1);
            assertThat(exec.indexOf("approval:reserve"))
                    .as("§5.3 步骤 9：Schema 失败不得预占审批")
                    .isEqualTo(-1);
        }
    }

    // ── 越权拒绝（与 S1 成对）─────────────────────────────────────

    @Nested
    @DisplayName("越权必须拒绝")
    class UnauthorizedMustBeDenied {

        @Test
        @DisplayName("⚠️⚠️ 未声明的工具 → TOOL_NOT_DECLARED，且工具零执行")
        void undeclaredToolIsDenied() {
            ModelResponse.ToolCallRequest foreign = new ModelResponse.ToolCallRequest(
                    "send_email", Map.of("to", "someone@example.com"), "call-2");

            CapToolDecision decision = flow().invoke(TENANT, task(), foreign, exec.tool("send_email"));

            assertThat(decision.decision().authorization())
                    .as("§9.1 S2：授权必须 DENY")
                    .isEqualTo(AuthorizationDecision.Authorization.DENY);
            assertThat(decision.decision().execution())
                    .as("§5.1：未进入 Phase B → NOT_EVALUATED")
                    .isEqualTo(AuthorizationDecision.Execution.NOT_EVALUATED);
            assertThat(decision.decision().status())
                    .isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
            assertThat(decision.decision().reasonCode())
                    .isEqualTo(ReasonCode.TOOL_NOT_DECLARED);
            assertThat(exec.toolExecutions)
                    .as("⚠️⚠️ 越权调用绝不能执行")
                    .isZero();
        }

        @Test
        @DisplayName("⚠️⚠️ 授权版本服务不可用 → AUTHZ_UNAVAILABLE，fail-closed")
        void unavailableAuthzVersionFailsClosed() {
            cap.authzUnavailable = true;

            CapToolDecision decision = flow().invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(decision.decision().authorization())
                    .isEqualTo(AuthorizationDecision.Authorization.DENY);
            assertThat(decision.decision().reasonCode())
                    .as("§9.1 S3d：版本服务不可用必须 fail-closed")
                    .isEqualTo(ReasonCode.AUTHZ_UNAVAILABLE);
            assertThat(exec.toolExecutions).isZero();
        }
    }

    // ── 幂等 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("幂等键校验（§5.3 步骤 8）")
    class Idempotency {

        @Test
        @DisplayName("✅ 重复调用返回首次结果，不重复执行副作用")
        void repeatedCallReturnsFirstResult() {
            CapExecutionFlow flow = flow();
            CapToolDecision first = flow.invoke(TENANT, task(), call(), exec.tool(TOOL));
            CapToolDecision second = flow.invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(first.decision().status())
                    .isEqualTo(AuthorizationDecision.Status.SUCCEEDED);
            assertThat(second.decision().status())
                    .as("§5.3 步骤 8：已完成 → 返回首次结果")
                    .isEqualTo(AuthorizationDecision.Status.SUCCEEDED);
            assertThat(second.decision().reasonCode())
                    .isEqualTo(ReasonCode.SUCCESS);
            assertThat(exec.toolExecutions)
                    .as("⚠️⚠️ 同一幂等键不得重复发起副作用（重复扣款 = Sev-0）")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️⚠️ 同键不同参数 → IDEMPOTENCY_CONFLICT，且不执行")
        void sameKeyDifferentArgumentsConflicts() {
            CapExecutionFlow flow = flow();
            flow.invoke(TENANT, task(), call(), exec.tool(TOOL));

            ModelResponse.ToolCallRequest tampered = new ModelResponse.ToolCallRequest(
                    TOOL, Map.of("studentId", "stu_ffffffffffffffff"), "call-1");
            CapToolDecision conflict = flow.invoke(TENANT, task(), tampered, exec.tool(TOOL));

            assertThat(conflict.decision().execution())
                    .isEqualTo(AuthorizationDecision.Execution.DENY);
            assertThat(conflict.decision().reasonCode())
                    .as("§5.3 步骤 8：同键不同参数 → IDEMPOTENCY_CONFLICT")
                    .isEqualTo(ReasonCode.IDEMPOTENCY_CONFLICT);
            assertThat(exec.toolExecutions)
                    .as("⚠️ 冲突调用绝不能执行")
                    .isEqualTo(1);
        }
    }

    // ── UNKNOWN ───────────────────────────────────────────────────

    @Nested
    @DisplayName("外部结果未知")
    class UnknownResult {

        @Test
        @DisplayName("⚠️⚠️ UNKNOWN → ALLOW/ALLOW/UNKNOWN/EXEC_UNKNOWN，且须进对账")
        void unknownResultRequiresReconciliation() {
            exec.toolFailure = cn.studykid.multiagent.ports.tool.ToolExecutionException
                    .unknownResult("响应丢失", null);

            CapToolDecision decision = flow().invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(decision.decision().execution())
                    .as("§9.1 S8：执行准入仍 ALLOW")
                    .isEqualTo(AuthorizationDecision.Execution.ALLOW);
            assertThat(decision.decision().status())
                    .as("§9.1 S8：状态必须是 UNKNOWN")
                    .isEqualTo(AuthorizationDecision.Status.UNKNOWN);
            assertThat(decision.decision().reasonCode())
                    .as("§9.1 S8：原因码必须是 EXEC_UNKNOWN")
                    .isEqualTo(ReasonCode.EXEC_UNKNOWN);
            assertThat(decision.requiresReconciliation())
                    .as("§7.4：UNKNOWN 是唯一需要进对账的状态")
                    .isTrue();
            assertThat(exec.indexOf("budget:release"))
                    .as("⚠️⚠️ UNKNOWN 绝不释放预算预留（成本可能已发生，R-007）")
                    .isEqualTo(-1);
        }
    }

    // ── 开关 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("迁移开关")
    class EnforcementSwitch {

        @Test
        @DisplayName("✅ 开关关闭 → 编排器报告未启用")
        void switchOffReportsDisabled() {
            assertThat(flow(new CapEnforcementSwitch(false)).isEnabled()).isFalse();
            assertThat(flow(new CapEnforcementSwitch(true)).isEnabled()).isTrue();
        }
    }
}
