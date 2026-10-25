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
import cn.studykid.multiagent.core.cap.ExecutionScope;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;
import cn.studykid.multiagent.ports.tool.ToolHandler;
import cn.studykid.multiagent.testsupport.acceptance.DecisionFields;

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
        return flow(capSwitch, approvalPolicy, null);
    }

    /**
     * 带 Run 级预算探针的装配（第七批 · MA-T17）。
     *
     * @param runBudget Run 级预算探针；{@code null} = 未装配（不做 Run 级判定）
     */
    private CapExecutionFlow flow(CapEnforcementSwitch capSwitch,
                                  ApprovalPolicy approvalPolicy,
                                  cn.studykid.multiagent.application.orchestration.RunBudgetProbe
                                          runBudget) {
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
                exec.resultEventPort(), BigDecimal.ONE,
                cn.studykid.multiagent.application.cap.CapExecutionFlow.DEFAULT_PRINCIPAL_ID,
                capSwitch, DecisionAuditService.noop(), runBudget);
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
    @DisplayName("Run 级预算（MA-T17 · 全调用树共享上限）")
    class RunBudget {

        @Test
        @DisplayName("⛔ Run 级额度用尽 → 工具零执行 + BUDGET_EXCEEDED（CAP 链路）")
        void exhaustedRunBudgetBlocksToolExecution() {
            // ⚠️ CAP 链路是**生产主链路**：这里漏了 Run 级判定，
            //    「全调用树共享预算上限」在生产上就是空的。
            var exhausted = new cn.studykid.multiagent.application.orchestration.RunBudgetProbe(
                    (tenantId, runId) -> java.util.Optional.of(
                            new cn.studykid.multiagent.ports.persistence.RunBudgetPort.RunBudget(
                                    runId, BigDecimal.TEN, BigDecimal.valueOf(6),
                                    BigDecimal.valueOf(4))));

            CapToolDecision decision = flow(new CapEnforcementSwitch(true),
                    handler -> ApprovalPolicy.ApprovalDecision.notRequired(), exhausted)
                    .invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(exec.toolExecutions)
                    .as("⚠️⚠️ Run 额度用尽 → 绝不能产生副作用")
                    .isZero();
            assertThat(decision.decision().reasonCode())
                    .as("⚠️ 必须报预算超限（而不是笼统的执行失败）")
                    .isEqualTo(ReasonCode.BUDGET_EXCEEDED);
            assertThat(decision.decision().status())
                    .as("⚠️ 工具根本没发出 → 未开始（NOT_STARTED），"
                            + "不是「执行了但失败」，更不是结果未知")
                    .isEqualTo(AuthorizationDecision.Status.NOT_STARTED);
            assertThat(decision.decision().authorization())
                    .as("⚠️ Phase B 拒绝时授权仍为 ALLOW（08 §1 S6/S7："
                            + "不得笼统记为授权 DENY）")
                    .isEqualTo(AuthorizationDecision.Authorization.ALLOW);
            assertThat(decision.decision().execution())
                    .isEqualTo(AuthorizationDecision.Execution.DENY);
        }

        @Test
        @DisplayName("✅ Run 级额度未声明 → 不额外限制（≠「余额为 0」）")
        void undeclaredRunBudgetDoesNotBlock() {
            var undeclared = new cn.studykid.multiagent.application.orchestration.RunBudgetProbe(
                    (tenantId, runId) -> java.util.Optional.empty());

            CapToolDecision decision = flow(new CapEnforcementSwitch(true),
                    handler -> ApprovalPolicy.ApprovalDecision.notRequired(), undeclared)
                    .invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(exec.toolExecutions).isEqualTo(1);
            assertThat(decision.decision().status())
                    .isEqualTo(AuthorizationDecision.Status.SUCCEEDED);
        }
    }

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
        @DisplayName("✅ 成功路径必须回填工具输出（没有输出就无法回灌给模型）")
        void successCarriesToolOutputForFeedback() {
            CapToolDecision decision = flow().invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(decision.hasOutput())
                    .as("⚠️ 工具输出若只写进 result_events 却不回传，模型就看不到自己刚调了什么，"
                            + "多轮工具协作不成立")
                    .isTrue();
            assertThat(decision.output())
                    .as("回灌的必须是工具的真实输出")
                    .isEqualTo("ok");
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

        @Test
        @DisplayName("⚠️ 准入通过后撤权 + 工具确定失败 → FAILED/TOOL_EXECUTION_FAILED（在途不被撤销）")
        void inFlightCallFailureIsNotCancelled() {
            // ⚠️ 与 SUCCEEDED 分支同构：准入点读到 v1 → 准入通过；此后撤权（v2）。
            //    区别只在工具结局 —— 这里工具**明确失败**（无副作用，可重试）。
            authz.bumpAfterRead(3, 2L);
            exec.toolFailure = ToolExecutionException.definitelyFailed("下游明确拒绝");

            CapToolDecision decision = flow().invoke(TENANT, task(), call(), exec.tool(TOOL));

            DecisionFields.assertFourFields(decision.decision(),
                    AuthorizationDecision.Authorization.ALLOW,
                    AuthorizationDecision.Execution.ALLOW,
                    AuthorizationDecision.Status.FAILED,
                    ReasonCode.TOOL_EXECUTION_FAILED);
            assertThat(exec.toolExecutions)
                    .as("⚠️⚠️ 准入后的在途调用不承诺取消（§5.5 契约 2）")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 准入通过后撤权 + 工具结果未知 → UNKNOWN/EXEC_UNKNOWN（进对账，不盲目重试）")
        void inFlightCallUnknownIsNotCancelled() {
            // ⚠️ 结果未知是**唯一**需要进对账的状态：
            //    可能已产生副作用 → 不得释放预算预留、不得盲目重试（红线）。
            authz.bumpAfterRead(3, 2L);
            exec.toolFailure = ToolExecutionException.unknownResult("响应丢失", null);

            CapToolDecision decision = flow().invoke(TENANT, task(), call(), exec.tool(TOOL));

            DecisionFields.assertFourFields(decision.decision(),
                    AuthorizationDecision.Authorization.ALLOW,
                    AuthorizationDecision.Execution.ALLOW,
                    AuthorizationDecision.Status.UNKNOWN,
                    ReasonCode.EXEC_UNKNOWN);
            assertThat(exec.toolExecutions)
                    .as("⚠️⚠️ 准入后的在途调用不承诺取消（§5.5 契约 2）")
                    .isEqualTo(1);
            assertThat(exec.reservations)
                    .as("⚠️⚠️ UNKNOWN 不释放预算预留：成本可能已发生（红线）。"
                            + "误释放会让下一次调用白花已发生的成本")
                    .hasSize(1);
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

    // ── 第十七批：幂等键按「当前执行中的 Agent」分域 ──────────────

    /**
     * 第十七批：幂等键按当前 Agent 分域。
     *
     * <p>⚠️ <b>本组用例断言的是「幂等键」，不是「执行结果」</b>：
     * 幂等键在 §5.3 <b>步骤 8</b>算出并送进幂等守卫，
     * 而子 Agent 的调用目前会在<b>步骤 13（执行准入）</b>被拒 ——
     * 原因是准入检查拿「Run 快照里的<b>根 Agent</b>闭包指纹」
     * 去比「Phase A 的<b>子 Agent</b>闭包指纹」，两者必然不等
     * （指纹含 {@code agent.id}）→ {@code EXEC_ADMISSION_STALE}。
     * <br>⚠️ 那是<b>另一个</b>真实缺陷（CAP 链路的子 Agent 路径整体不可用），
     * 已登记为待修缺口，<b>不在本批修</b>（它要动的是准入语义，不是幂等键）。
     * <br>⚠️ 正因为如此，本组断言<b>不能</b>写「子 Agent 的调用必须成功」——
     * 那会把一个尚未修复的缺陷固化成「预期行为」。
     */
    @Nested
    @DisplayName("第十七批 幂等键按当前 Agent 分域")
    class AgentDimension {

        /** 子 Agent 标识（与根 Agent 同 Skill、异身份）。 */
        private static final String CHILD = "child_tutor";

        /**
         * 构造子 Agent 的执行作用域（固定 release + 真实闭包指纹）。
         */
        private ExecutionScope childScope() {
            cap.withAgent(TENANT, CHILD, 1, List.of(SKILL), "{}");
            return new ExecutionScope(CHILD, 1,
                    ClosureFingerprint.of(resolver.resolve(TENANT, CHILD, 1)));
        }

        @Test
        @DisplayName("⚠️⚠️ 父 Agent 与子 Agent 的幂等键不同（键带 Agent 维度）")
        void parentAndChildUseDifferentKeys() {
            // ⚠️ 修复前键里没有 Agent 维度 → 父子算出同一个键 →
            //    后者被幂等守卫判「已完成 / 进行中」→ **子 Agent 那次调用没发生**，
            //    且模型拿到的还是父的结果。
            CapExecutionFlow flow = flow();

            flow.invoke(TENANT, task(), call(), exec.tool(TOOL));                    // 根路径
            flow.invoke(TENANT, task(), call(), exec.tool(TOOL), childScope());      // 子路径

            assertThat(exec.lookedUpKeys)
                    .as("⚠️ 两次调用都必须走幂等校验")
                    .hasSize(2);
            assertThat(exec.lookedUpKeys.get(0))
                    .as("✅ 根路径的键不含 Agent 维度（与历史逐字符一致，"
                            + "在途 tool_operation 行按旧键写入，改形状会让重放变新调用）")
                    .isEqualTo("tool.invoke:" + RUN_ID + "/" + TOOL + "/call-1");
            assertThat(exec.lookedUpKeys.get(1))
                    .as("⚠️⚠️ 子 Agent 的键必须带自己的身份（agentId@version）")
                    .contains(CHILD + "@1");
            assertThat(exec.lookedUpKeys.get(0))
                    .as("⚠️⚠️ 父子撞键 = 子 Agent 的调用被父的幂等记录吞掉")
                    .isNotEqualTo(exec.lookedUpKeys.get(1));
        }

        @Test
        @DisplayName("✅ 同一 Agent 重复调用 → 同一个键（幂等未被破坏）")
        void sameAgentRepeatUsesSameKey() {
            // ⚠️ 这是本次修复的**反面**：加了 Agent 维度之后，
            //    同一 Agent 的同一次调用重投**必须**仍是同一个键，
            //    否则「重复投递」会变成「重复副作用」——那比吞调用更糟。
            CapExecutionFlow flow = flow();
            ExecutionScope scope = childScope();

            flow.invoke(TENANT, task(), call(), exec.tool(TOOL), scope);
            flow.invoke(TENANT, task(), call(), exec.tool(TOOL), scope);

            assertThat(exec.lookedUpKeys).hasSize(2);
            assertThat(exec.lookedUpKeys.get(0))
                    .as("⚠️⚠️ 同一 Agent 的重复调用必须是同一个键 —— "
                            + "键若每次都变，幂等就退化成「每次都执行一遍」")
                    .isEqualTo(exec.lookedUpKeys.get(1));
        }

        @Test
        @DisplayName("✅ 根路径（无作用域）的键与历史完全一致")
        void rootPathKeyShapeUnchanged() {
            CapExecutionFlow flow = flow();
            flow.invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(exec.lookedUpKeys).hasSize(1);
            assertThat(exec.lookedUpKeys.get(0))
                    .as("⚠️⚠️ 单 Agent 路径的键必须与历史逐字符一致"
                            + "（在途 tool_operation 行按旧键写入，改形状会导致重放变新调用）")
                    .isEqualTo("tool.invoke:" + RUN_ID + "/" + TOOL + "/call-1");
        }
    }

    // ── 第十八批：CAP 链路的子 Agent 路径必须真正可用 ────────────

    /**
     * 第十八批：CAP 链路的子 Agent 路径。
     *
     * <p>⚠️ <b>本组用例针对的是「子 Agent 能不能真的调到工具」</b>，
     * 不是幂等键。修复前该路径**每一次调用都在步骤 13 被拒**
     * （{@code EXEC_ADMISSION_STALE}）—— 详见 00 文档「历批修正的设计缺陷」。
     */
    @Nested
    @DisplayName("第十八批 CAP 链路的子 Agent 路径")
    class ChildAgentPath {

        /** 子 Agent 标识（与根 Agent 同 Skill、异身份）。 */
        private static final String CHILD = "child_tutor";

        /** 子 Agent **新 release** 专用的 Skill（刻意不声明 TOOL）。 */
        private static final String SKILL_V2 = "profile_skill_v2";

        /**
         * 构造子 Agent 的执行作用域（固定 release + 真实闭包指纹）。
         */
        private ExecutionScope childScope(int version) {
            cap.withAgent(TENANT, CHILD, version, List.of(SKILL), "{}");
            return new ExecutionScope(CHILD, version,
                    ClosureFingerprint.of(resolver.resolve(TENANT, CHILD, version)));
        }

        @Test
        @DisplayName("⚠️⚠️ 子 Agent 的工具调用必须成功（此前被准入检查整体拒绝）")
        void childToolCallSucceeds() {
            CapExecutionFlow flow = flow();

            CapToolDecision decision = flow.invoke(TENANT, task(), call(),
                    exec.tool(TOOL), childScope(1));

            assertThat(decision.decision().reasonCode())
                    .as("⚠️⚠️ 子 Agent 的调用不得被准入检查拒绝 —— "
                            + "准入拿「Run 快照里的**根 Agent** 闭包指纹」去比"
                            + "「Phase A 的**子 Agent** 闭包指纹」，两者必然不等")
                    .isEqualTo(ReasonCode.SUCCESS);
            assertThat(decision.decision().status())
                    .isEqualTo(AuthorizationDecision.Status.SUCCEEDED);
            assertThat(exec.toolExecutions)
                    .as("⚠️⚠️ 子 Agent 的工具必须真的执行 —— "
                            + "「被拒」与「执行失败」必须分开记账")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️⚠️ 父子各调一次同名工具 → 各自都执行")
        void parentAndChildBothExecute() {
            CapExecutionFlow flow = flow();

            flow.invoke(TENANT, task(), call(), exec.tool(TOOL));
            flow.invoke(TENANT, task(), call(), exec.tool(TOOL), childScope(1));

            assertThat(exec.toolExecutions)
                    .as("⚠️⚠️ 父子两次调用必须真的执行两次 —— "
                            + "只执行一次意味着子 Agent 的那次副作用从未发生")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("✅ 子 Agent 发布新 release 后，在途委派仍按固定 release 授权")
        void childDoesNotDriftToNewRelease() {
            // ⚠️ 子 Agent 的 release 在**委派时固定**（不可妥协约束）：
            //    别人给子 Agent 发布新版本，**不得**让在途委派漂移到新版本，
            //    更不得让在途委派因此被拒。
            //    v2 启用另一个 Skill（刻意不声明 TOOL）→ 一旦漂移到 v2，
            //    TOOL 就会变成「未声明」→ 被拒 —— 那是最容易被忽略的漂移形态。
            cap.withSkill(SKILL_V2, 1, CapabilityRiskLevel.READ, List.of("get_profile_v2"));
            cap.withTool("get_profile_v2", 1, CapabilityRiskLevel.READ, "[]");
            cap.withAgent(TENANT, CHILD, 2, List.of(SKILL_V2), "{}");

            CapExecutionFlow flow = flow();
            CapToolDecision decision = flow.invoke(TENANT, task(), call(),
                    exec.tool(TOOL), childScope(1));

            assertThat(decision.decision().reasonCode())
                    .as("⚠️⚠️ 子 Agent 发布新版本不得影响在途委派 —— "
                            + "固定 release 漂移会让在途子调用全挂")
                    .isEqualTo(ReasonCode.SUCCESS);
            assertThat(exec.toolExecutions).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 根 Agent 的闭包漂移 → 子路径仍被拒（不得放宽既有防线）")
        void rootClosureDriftStillBlocksChildPath() {
            // ⚠️ 修「子路径被误拒」**不得**顺带把「根的依赖集漂移」也放行：
            //    Run 快照是根的闭包的固化证据，根的闭包变了 → 在途 Run 失效
            //    （与根路径同口径，见「编排声明入指纹导致在途 Run 一次性失效」）。
            cap.withAgent(TENANT, AGENT, 2, List.of(SKILL), "{}");
            cap.withPublishedAgentVersion(TENANT, AGENT, 2);

            CapExecutionFlow flow = flow();
            CapToolDecision decision = flow.invoke(TENANT, task(), call(),
                    exec.tool(TOOL), childScope(1));

            assertThat(decision.decision().reasonCode())
                    .as("⚠️⚠️ 根的闭包漂移必须仍然阻断子路径的新副作用")
                    .isEqualTo(ReasonCode.EXEC_ADMISSION_STALE);
            assertThat(exec.toolExecutions)
                    .as("⚠️⚠️ 被准入拒绝的调用不得有任何副作用")
                    .isEqualTo(0);
        }
    }

    // ── 第十八批：根路径的既有防线不得被放宽（回归保护）──────────

    /**
     * 第十八批：根路径的回归保护。
     *
     * <p>⚠️ <b>本组用例的存在理由</b>：第十八批动了执行准入的
     * 「Run 快照指纹复核」口径（改为按<b>快照自己的 Agent</b>比对），
     * 而该口径在<b>根路径</b>上<b>必须保持等价</b> ——
     * 根的闭包漂移仍然要阻断新副作用。
     * <br>⚠️ 此前这条防线<b>没有</b>回归保护：既有用例只覆盖「撤权」路径
     * （授权版本递增），闭包漂移路径没人钉 —— 口径一改就可能悄悄放松。
     */
    @Nested
    @DisplayName("第十八批 根路径的既有防线（回归保护）")
    class RootPathRegression {

        @Test
        @DisplayName("⚠️⚠️ 根 Agent 的闭包漂移 → 根路径的工具调用仍被拒")
        void rootClosureDriftStillBlocksRootPath() {
            cap.withAgent(TENANT, AGENT, 2, List.of(SKILL), "{}");
            cap.withPublishedAgentVersion(TENANT, AGENT, 2);

            CapExecutionFlow flow = flow();
            CapToolDecision decision = flow.invoke(TENANT, task(), call(), exec.tool(TOOL));

            // ⚠️ 根路径的漂移在 **Phase A 步骤 7** 就被拦（指纹与 Run 快照不符），
            //    根本走不到步骤 13 的准入复核 —— 拦截点更早是好事（fail-closed 且更早）。
            //    步骤 13 的快照复核是**第二道**防线：它只在「Phase A 之后、
            //    准入之前闭包又变了」这个窄窗口里才有意义。
            assertThat(decision.decision().reasonCode())
                    .as("⚠️⚠️ 根的闭包漂移必须仍然阻断新副作用 —— "
                            + "快照是「根的依赖集没变」的固化证据")
                    .isEqualTo(ReasonCode.CONFIG_FINGERPRINT_STALE);
            assertThat(exec.toolExecutions)
                    .as("⚠️⚠️ 被拒的调用不得有任何副作用")
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("✅ 根路径未被改动时照常放行（口径没变 ≠ 一律拒绝）")
        void rootPathStillWorksWhenNothingChanged() {
            CapExecutionFlow flow = flow();
            CapToolDecision decision = flow.invoke(TENANT, task(), call(), exec.tool(TOOL));

            assertThat(decision.decision().status())
                    .as("⚠️⚠️ 修复不得把根路径一并拒了 —— 那等于把单 Agent 链路也打死")
                    .isEqualTo(AuthorizationDecision.Status.SUCCEEDED);
            assertThat(exec.toolExecutions).isEqualTo(1);
        }
    }
}
