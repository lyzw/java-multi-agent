package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * D3 测试：Worker 执行循环与工具闸门。
 *
 * <p><b>核心命题</b>（P2-D03 §3）：三种异常语义不可混淆。
 * 断言针对<b>不可绕过的性质</b>，而非仅「状态变了」。
 */
class RunExecutionLoopTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String RUN_ID = UUID.randomUUID().toString();
    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();

    // ── 假实现 ──────────────────────────────────────────────────────

    private static RunConfigSnapshot snapshot(int maxSteps) {
        return new RunConfigSnapshot(RUN_ID, new java.math.BigDecimal("2.00"),
                12, maxSteps, ApprovalRequirement.NOT_REQUIRED, Instant.now());
    }

    private static RunExecutionLoop.AgentModelConfig agentConfig() {
        return new RunExecutionLoop.AgentModelConfig(
                "test-model", "你是初中数学辅导助手", 0.7d, 1024,
                Duration.ofSeconds(5), Map.of());
    }

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(RUN_ID, 1L, Instant.now().plusSeconds(30), 1);
    }

    /**
     * 两个工具的调用，顺序固定为：先 record_weakness，后 recording_tool。
     *
     * <p>⚠️ <b>必须用 LinkedHashMap</b>：{@code Map.of} 的<b>迭代顺序未定义</b>，
     * 可能让 recording_tool 先执行 —— 那样测的就变成「顺序恰好如此」，
     * 而非「UNKNOWN 后停止后续工具」。
     * 这个缺陷曾让本用例时绿时红。
     */
    private static Map<String, ModelResponse.ToolCallRequest> orderedCalls() {
        Map<String, ModelResponse.ToolCallRequest> calls = new java.util.LinkedHashMap<>();
        calls.put("c1", new ModelResponse.ToolCallRequest(
                "record_weakness", Map.of(), "c1"));
        calls.put("c2", new ModelResponse.ToolCallRequest(
                "recording_tool", Map.of(), "c2"));
        return calls;
    }

    /** 可配置的 claim 端口。 */
    private static class FakeClaimPort implements RunClaimPort {
        boolean generationValid = true;
        boolean renewSucceeds = true;
        boolean commitSucceeds = true;
        String committedState;
        long committedToken = -1;
        int commitCount;
        /** ⚠️ 记录续租传入的身份，供「身份一致性」断言（P3-D02 F-1）。 */
        String lastRenewWorkerId;
        int renewCount;

        @Override public List<LeasedTask> claimTasks(String t, String w, Duration d, int l) {
            return List.of();
        }
        @Override public Optional<LeasedTask> claimSpecificRun(String t, String r, String w, Duration d) {
            return Optional.empty();
        }
        @Override public boolean renewLease(String t, String r, String w, long f, Duration d) {
            renewCount++;
            lastRenewWorkerId = w;
            return renewSucceeds;
        }
        @Override public boolean releaseLease(String t, String r, String w, long f) { return true; }
        @Override public boolean commitResult(String t, String r, long f, String s, String p) {
            commitCount++;
            committedState = s;
            committedToken = f;
            return commitSucceeds;
        }
        @Override public boolean isCurrentGeneration(String t, String r, long f) {
            return generationValid;
        }
    }

    /**
     * 统一构造：workerId 固定为 {@code w-1}。
     *
     * <p>⚠️ P3-D02 F-1 修复后 workerId 是构造参数 ——
     * 所有用例必须经此工厂创建，避免散落的构造调用再次出现身份分叉。
     */
    private static RunExecutionLoop loop(ModelGateway gw, RunClaimPort port,
                                         ToolInvocationGate gate) {
        return new RunExecutionLoop(gw, port, gate, Duration.ofSeconds(30), "w-1");
    }

    /** 内存账本替身（P3-D03）。每个用例独立实例，互不污染。 */
    private static InMemoryLedger ledger() {
        return new InMemoryLedger();
    }

    /**
     * 内存账本替身。
     *
     * <p>⚠️ P3-D03 F-1 教训的延续：替身必须让契约可证伪——
     * {@code recordResult} 校验操作处于 IN_FLIGHT（与真实仓储一致的迁移语义），
     * 而非无条件放行。
     */
    private static final class InMemoryLedger implements RunToolLedgerPort {

        final Map<String, ToolOperationState> states = new java.util.LinkedHashMap<>();
        final Map<String, String> operationsByKey = new java.util.LinkedHashMap<>();
        int registerCount;
        boolean registerFails;

        @Override public Registration register(String tenantId, String runId,
                String toolName, String riskLevel, String idempotencyKey,
                String requestDigest, Map<String, Object> arguments, long fencingToken) {
            if (registerFails) {
                throw new IllegalStateException("模拟账本故障");
            }
            registerCount++;
            String existing = operationsByKey.get(idempotencyKey);
            if (existing != null) {
                return new Registration(existing, states.get(existing), 1);
            }
            String opId = UUID.randomUUID().toString();
            operationsByKey.put(idempotencyKey, opId);
            states.put(opId, ToolOperationState.IN_FLIGHT);
            return new Registration(opId, ToolOperationState.IN_FLIGHT, 0);
        }

        @Override public void recordResult(String tenantId, String operationId,
                ToolOperationState result, long fencingToken,
                String errorClass, String errorMessage) {
            ToolOperationState current = states.get(operationId);
            if (current != ToolOperationState.IN_FLIGHT) {
                throw new IllegalStateException("替身契约: recordResult 须 IN_FLIGHT，实际 "
                        + current);
            }
            states.put(operationId, result);
        }

        @Override public void reconcile(String tenantId, String operationId,
                ToolOperationState reconciled, String source) {
            states.put(operationId, reconciled);
        }

        @Override public void manualDispose(String tenantId, String operationId,
                String disposedBy, String note) {
            states.put(operationId, ToolOperationState.MANUAL_DISPOSED);
        }

        @Override public List<UnknownOperation> findUnknownOperations(String tenantId) {
            return states.entrySet().stream()
                    .filter(e -> e.getValue() == ToolOperationState.UNKNOWN)
                    .map(e -> new UnknownOperation(e.getKey(), RUN_ID, "x", "k",
                            Instant.now()))
                    .toList();
        }

        @Override public Optional<Registration> findByIdempotencyKey(
                String tenantId, String idempotencyKey) {
            String opId = operationsByKey.get(idempotencyKey);
            return opId == null ? Optional.empty()
                    : Optional.of(new Registration(opId, states.get(opId), 1));
        }
    }

    private static ModelGateway modelReturning(ModelResponse response) {
        return request -> {
            MODEL_CALLS.incrementAndGet();
            return response;
        };
    }

    private static ModelResponse textResponse(String content) {
        return new ModelResponse(content, Map.of(),
                new ModelResponse.ModelUsage(10, 5),
                ModelResponse.FinishReason.STOP);
    }

    // ── 测试 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("执行前检查（§2.1）")
    class PreExecutionCheck {

        @Test
        @DisplayName("⚠️ 世代失效 → 不调用模型（省成本），不提交")
        void staleGenerationSkipsModelCall() {
            MODEL_CALLS.set(0);
            FakeClaimPort port = new FakeClaimPort();
            port.generationValid = false;

            var loop = loop(modelReturning(textResponse("ok")), port,
                    new ToolInvocationGate(List.of(), allowAll(),
                            h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                            (t, r, n) -> true, ledger()));

            var outcome = loop.execute(TENANT, task(), agentConfig(), snapshot(5),
                    Map.of("q", "一元二次方程"));

            assertThat(outcome.kind())
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.SKIPPED_STALE_GENERATION);
            assertThat(MODEL_CALLS.get())
                    .as("⚠️ 世代已失效仍调模型 = 白花钱（真实计费）")
                    .isZero();
            assertThat(port.commitCount)
                    .as("被接管的 Run 不应再提交")
                    .isZero();
        }

        @Test
        @DisplayName("✅ 正常执行 → SUCCEEDED 且携带当前世代提交")
        void normalExecutionSucceeds() {
            FakeClaimPort port = new FakeClaimPort();
            var loop = loop(modelReturning(textResponse("x=2 或 x=3")), port,
                    new ToolInvocationGate(List.of(), allowAll(),
                            h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                            (t, r, n) -> true, ledger()));

            var outcome = loop.execute(TENANT, task(), agentConfig(), snapshot(5),
                    Map.of("q", "一元二次方程"));

            assertThat(outcome.kind()).isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.SUCCEEDED);
            assertThat(port.committedState).isEqualTo("SUCCEEDED");
            assertThat(port.committedToken)
                    .as("提交必须携带当前世代，否则 fencing 失效")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("⚠️⚠️ 续租身份 = 构造时的 workerId（P3-D02 F-1 回归）")
        void renewUsesConstructedWorkerId() {
            FakeClaimPort port = new FakeClaimPort();
            // 模型第一次返回工具调用（触发续租），第二次返回文本（完成）
            int[] step = {0};
            ModelGateway gw = request -> {
                if (step[0]++ == 0) {
                    return new ModelResponse("", Map.of(
                            "c1", new ModelResponse.ToolCallRequest(
                                    "recording_tool", Map.of(), "c1")),
                            new ModelResponse.ModelUsage(1, 1),
                            ModelResponse.FinishReason.TOOL_CALLS);
                }
                return textResponse("完成");
            };

            var outcome = loop(gw, port,
                    new ToolInvocationGate(List.of(new RecordingTool()), allowAll(),
                            h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                            (t, r, n) -> true, ledger()))
                    .execute(TENANT, task(), agentConfig(), snapshot(5),
                            Map.of("q", "x"));

            assertThat(outcome.kind()).isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.SUCCEEDED);
            assertThat(port.renewCount)
                    .as("多步执行第二步前必须续租")
                    .isEqualTo(1);
            assertThat(port.lastRenewWorkerId)
                    .as("⚠️ F-1：续租身份必须与领取身份一致。"
                            + "旧实现从 task 拼凑 'worker-'+token，lease_owner 永不匹配，"
                            + "所有多步 Run 被误判为已被接管")
                    .isEqualTo("w-1");
        }

        @Test
        @DisplayName("⚠️ workerId 为空 → 构造即拒绝（fail-fast）")
        void blankWorkerIdRejected() {
            assertThatThrownBy(() -> new RunExecutionLoop(
                    modelReturning(textResponse("x")), new FakeClaimPort(),
                    new ToolInvocationGate(List.of(), allowAll(),
                            h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                            (t, r, n) -> true, ledger()), Duration.ofSeconds(30), " "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("⚠️ 三种异常语义（§3）")
    class ExceptionSemantics {

        @Test
        @DisplayName("✅ 模型超时 → RETRYABLE_FAILURE（可重试）")
        void modelTimeoutRetryable() {
            FakeClaimPort port = new FakeClaimPort();
            ModelGateway gw = request -> {
                throw new ModelGateway.ModelGatewayException(
                        ModelGateway.ModelErrorKind.TIMEOUT, "timeout");
            };
            var loop = loop(gw, port,
                    new ToolInvocationGate(List.of(), allowAll(),
                            h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                            (t, r, n) -> true, ledger()));

            var outcome = loop.execute(TENANT, task(), agentConfig(), snapshot(5),
                    Map.of("q", "x"));

            assertThat(outcome.kind())
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.RETRYABLE_FAILURE);
            assertThat(outcome.retryable()).isTrue();
        }

        @Test
        @DisplayName("✅ 模型鉴权失败 → FAILED（不可重试）")
        void modelUnauthorizedNotRetryable() {
            FakeClaimPort port = new FakeClaimPort();
            ModelGateway gw = request -> {
                throw new ModelGateway.ModelGatewayException(
                        ModelGateway.ModelErrorKind.UNAUTHORIZED, "invalid key");
            };
            var loop = loop(gw, port,
                    new ToolInvocationGate(List.of(), allowAll(),
                            h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                            (t, r, n) -> true, ledger()));

            var outcome = loop.execute(TENANT, task(), agentConfig(), snapshot(5),
                    Map.of("q", "x"));

            assertThat(outcome.kind()).isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.FAILED);
            assertThat(outcome.retryable())
                    .as("鉴权失败重试无意义，只会触发封禁")
                    .isFalse();
            assertThat(port.committedState).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("⚠️⚠️⚠️ 工具 UNKNOWN → UNKNOWN_RESULT 且**不可重试**")
        void toolUnknownNotRetryable() {
            FakeClaimPort port = new FakeClaimPort();
            ModelGateway gw = request -> new ModelResponse("", Map.of(
                    "call-1", new ModelResponse.ToolCallRequest(
                            "record_weakness",
                            Map.of("studentId", "stu_a1b2c3d4e5f6g7h8",
                                    "knowledgePoint", "一元二次方程",
                                    "errorType", "概念不清"),
                            "call-1")),
                    new ModelResponse.ModelUsage(10, 5),
                    ModelResponse.FinishReason.TOOL_CALLS);

            var loop = loop(gw, port,
                    new ToolInvocationGate(List.of(new UnknownResultTool()),
                            allowAll(),
                            h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                            (t, r, n) -> true, ledger()));

            var outcome = loop.execute(TENANT, task(), agentConfig(), snapshot(5),
                    Map.of("q", "x"));

            assertThat(outcome.kind())
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.UNKNOWN_RESULT);
            assertThat(outcome.retryable())
                    .as("⚠️⚠️ UNKNOWN 绝不可重试 —— 可能有副作用已发生，"
                            + "重试会导致 record_weakness 重复写入 = Sev-0")
                    .isFalse();
            assertThat(port.committedState)
                    .as("⚠️ 不得直接标记 FAILED（那会让人以为没发生副作用）")
                    .isNotEqualTo("FAILED");
        }

        @Test
        @DisplayName("✅ 工具确定失败 → 可重试路径（不是 UNKNOWN）")
        void toolDefinitelyFailedIsRetryable() {
            FakeClaimPort port = new FakeClaimPort();
            // ⚠️ 用数组而非局部变量：lambda 只能捕获实际 final 的变量
            int[] step = {0};
            ModelGateway gw = request -> {
                // 第一次带工具调用（失败），第二次返回文本
                if (step[0]++ == 0) {
                    return new ModelResponse("", Map.of(
                            "c1", new ModelResponse.ToolCallRequest(
                                    "flaky_tool", Map.of(), "c1")),
                            new ModelResponse.ModelUsage(10, 5),
                            ModelResponse.FinishReason.TOOL_CALLS);
                }
                return textResponse("完成");
            };

            var loop = loop(gw, port,
                    new ToolInvocationGate(List.of(new DefinitelyFailedTool()),
                            allowAll(),
                            h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                            (t, r, n) -> true, ledger()));

            var outcome = loop.execute(TENANT, task(), agentConfig(), snapshot(5),
                    Map.of("q", "x"));

            assertThat(outcome.kind())
                    .as("确定失败无副作用，循环继续后可正常完成")
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.SUCCEEDED);
        }
    }

    @Nested
    @DisplayName("工具闸门（§4）")
    class ToolGate {

        @Test
        @DisplayName("⚠️ 授权被拒 → 不执行工具")
        void authorizationDeniedBlocksTool() {
            var handler = new RecordingTool();
            var gate = new ToolInvocationGate(List.of(handler), denyAll(),
                    h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                    (t, r, n) -> true, ledger());

            var result = gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "recording_tool", Map.of(), "c1")));

            assertThat(handler.executeCount.get())
                    .as("⚠️ 授权未过不得执行 —— 模型不能越权调工具")
                    .isZero();
            assertThat(result.rejectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ Schema 校验失败 → 不执行")
        void schemaFailureBlocksTool() {
            var handler = new SchemaRejectingTool();
            var gate = new ToolInvocationGate(List.of(handler), allowAll(),
                    h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                    (t, r, n) -> true, ledger());

            var result = gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "bad_schema_tool", Map.of(), "c1")));

            assertThat(handler.executeCount.get())
                    .as("Schema 错误必须拒绝，不得「尽力解析」")
                    .isZero();
            assertThat(result.rejectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️⚠️ 需要审批但审批流未实现 → fail-closed 拒绝")
        void requiredApprovalFailsClosed() {
            var handler = new RecordingTool();
            var gate = new ToolInvocationGate(List.of(handler), allowAll(),
                    // 模拟风险级别被提升为需要审批
                    h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.required("WRITE_EXTERNAL"),
                    (t, r, n) -> true, ledger());

            var result = gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "recording_tool", Map.of(), "c1")));

            assertThat(handler.executeCount.get())
                    .as("⚠️⚠️ 审批流未实现时必须拒绝。若静默通过 = 安全门禁形同虚设")
                    .isZero();
            assertThat(result.rejectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 未登记工具 → 拒绝")
        void unregisteredToolRejected() {
            var gate = new ToolInvocationGate(List.of(), allowAll(),
                    h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                    (t, r, n) -> true, ledger());

            var result = gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "never_registered", Map.of(), "c1")));

            assertThat(result.rejectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️⚠️ 账本登记失败 → fail-closed 拒绝执行（P3-D03 T-6）")
        void ledgerFailureBlocksTool() {
            var handler = new RecordingTool();
            InMemoryLedger broken = new InMemoryLedger();
            broken.registerFails = true;
            var gate = new ToolInvocationGate(List.of(handler), allowAll(),
                    h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                    (t, r, n) -> true, broken);

            var result = gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "recording_tool", Map.of(), "c1")));

            assertThat(handler.executeCount.get())
                    .as("⚠️⚠️ 无账可对的调用，其 UNKNOWN 无处安放——"
                            + "账本故障时必须拒绝执行，而非降级放行")
                    .isZero();
            assertThat(result.rejectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 预算不足 → 不执行")
        void budgetExhaustedBlocksTool() {
            var handler = new RecordingTool();
            var gate = new ToolInvocationGate(List.of(handler), allowAll(),
                    h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                    (t, r, n) -> false, ledger());  // 预算预留失败

            gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "recording_tool", Map.of(), "c1")));

            assertThat(handler.executeCount.get())
                    .as("预算不足不得执行（成本已超 DEC-9 上限）")
                    .isZero();
        }

        @Test
        @DisplayName("⚠️ UNKNOWN 后停止后续工具执行")
        void stopsAfterUnknown() {
            var unknownTool = new UnknownResultTool();
            var laterTool = new RecordingTool();
            var gate = new ToolInvocationGate(List.of(unknownTool, laterTool), allowAll(),
                    h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                    (t, r, n) -> true, ledger());

            var result = gate.invokeAll(TENANT, task(), orderedCalls());

            assertThat(result.unknownResultOccurred()).isTrue();
            assertThat(laterTool.executeCount.get())
                    .as("⚠️ UNKNOWN 后不得继续执行其他工具（状态已不可信）")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("步数上限（§5.1）")
    class StepLimit {

        @Test
        @DisplayName("⚠️ 步数超限 → FAILED，不无限循环")
        void stepLimitEnforced() {
            FakeClaimPort port = new FakeClaimPort();
            MODEL_CALLS.set(0);
            // 模型永远返回工具调用
            ModelGateway gw = request -> {
                MODEL_CALLS.incrementAndGet();
                return new ModelResponse("", Map.of(
                        "c" + MODEL_CALLS.get(),
                        new ModelResponse.ToolCallRequest(
                                "recording_tool", Map.of(), "c" + MODEL_CALLS.get())),
                        new ModelResponse.ModelUsage(1, 1),
                        ModelResponse.FinishReason.TOOL_CALLS);
            };
            var loop = loop(gw, port,
                    new ToolInvocationGate(List.of(new RecordingTool()), allowAll(),
                            h -> ToolInvocationGate.ApprovalPolicy.ApprovalDecision.notRequired(),
                            (t, r, n) -> true, ledger()));

            var outcome = loop.execute(TENANT, task(), agentConfig(), snapshot(3),
                    Map.of("q", "x"));

            assertThat(outcome.kind()).isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.FAILED);
            assertThat(MODEL_CALLS.get())
                    .as("⚠️ 必须有步数上限，否则模型可反复调工具直到耗尽预算")
                    .isEqualTo(3);
        }
    }

    // ── 授权替身 ────────────────────────────────────────────────────

    private static AuthorizationService allowAll() {
        return new AuthorizationService() {
            @Override public boolean isAuthorized(TenantContext c, String a, String r) {
                return true;
            }
            @Override public long currentAuthzVersion(String t, String p) { return 0L; }
            @Override public Set<String> rolesOf(TenantContext c) { return c.roles(); }
        };
    }

    private static AuthorizationService denyAll() {
        return new AuthorizationService() {
            @Override public boolean isAuthorized(TenantContext c, String a, String r) {
                return false;
            }
            @Override public long currentAuthzVersion(String t, String p) { return 0L; }
            @Override public Set<String> rolesOf(TenantContext c) { return c.roles(); }
        };
    }

    // ── 工具替身 ────────────────────────────────────────────────────

    /** 记录是否被执行。 */
    private static class RecordingTool implements ToolHandler {
        final AtomicInteger executeCount = new AtomicInteger();
        @Override public String toolName() { return "recording_tool"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ; }
        @Override public void validate(Map<String, Object> arguments) { }
        @Override public ToolResult execute(ToolExecutionContext ctx, Map<String, Object> a) {
            executeCount.incrementAndGet();
            return ToolResult.of("ok");
        }
    }

    /**
     * 抛出 UNKNOWN 的工具。
     *
     * <p>⚠️ 工具名必须与调用方传入的一致：名称不匹配会被判为
     * 「未登记工具」而拒绝，测的就不是 UNKNOWN 路径了。
     */
    private static class UnknownResultTool extends RecordingTool {
        @Override public String toolName() { return "record_weakness"; }
        @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.WRITE_PROFILE; }
        @Override public ToolResult execute(ToolExecutionContext ctx, Map<String, Object> a) {
            executeCount.incrementAndGet();
            throw ToolExecutionException.unknownResult("下游超时，结果未知",
                    new RuntimeException("timeout"));
        }
    }

    /** 抛出确定失败的工具。 */
    private static class DefinitelyFailedTool extends RecordingTool {
        @Override public String toolName() { return "flaky_tool"; }
        @Override public ToolResult execute(ToolExecutionContext ctx, Map<String, Object> a) {
            executeCount.incrementAndGet();
            throw ToolExecutionException.definitelyFailed("参数错误");
        }
    }

    /** Schema 校验必失败的工具。 */
    private static class SchemaRejectingTool extends RecordingTool {
        @Override public String toolName() { return "bad_schema_tool"; }
        @Override public void validate(Map<String, Object> arguments) {
            throw new IllegalArgumentException("缺少必填字段");
        }
    }
}