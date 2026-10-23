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
import cn.studykid.multiagent.ports.persistence.ApprovalPort;
import cn.studykid.multiagent.ports.persistence.ApprovalPort.ApprovalOutcome;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunEventPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;
import cn.studykid.multiagent.ports.security.ApprovalPolicy;
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
        /** P3-D06：可配置的取消标志（默认未取消）。 */
        boolean cancelled = false;
        @Override public boolean requestCancel(String t, String r) {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            return true;
        }
        @Override public boolean isCancelled(String t, String r) {
            return cancelled;
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

    /** 带事件写入的循环（P4-D12 / G4）。 */
    private static RunExecutionLoop loop(ModelGateway gw, RunClaimPort port,
                                         ToolInvocationGate gate,
                                         cn.studykid.multiagent.ports.persistence.RunEventPort eventPort) {
        return new RunExecutionLoop(gw, port, gate, Duration.ofSeconds(30), "w-1",
                new cn.studykid.multiagent.application.prompt.PromptGuard(), eventPort);
    }

    /**
     * 记录型事件端口（P4-D12 / G4）。
     *
     * <p>⚠️ 本类存在的理由：P4-D08 的 R-1 教训——
     * 「工具未注册为 Bean → 工具从不执行，而系统表面完全正常」。
     * 事件写入若只做「类里有个方法」，同样会**静默不写**。
     */
    private static final class RecordingEventPort
            implements cn.studykid.multiagent.ports.persistence.RunEventPort {
        final List<String> types = new java.util.ArrayList<>();
        final List<Map<String, Object>> payloads = new java.util.ArrayList<>();
        boolean failOnAppend = false;

        @Override
        public void append(String tenantId, String runId, String eventType,
                           Map<String, Object> payload) {
            if (failOnAppend) {
                throw new IllegalStateException("事件写入失败（模拟）");
            }
            types.add(eventType);
            payloads.add(payload);
        }
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
                return new Registration(existing, states.get(existing), 1, 0L);
            }
            String opId = UUID.randomUUID().toString();
            operationsByKey.put(idempotencyKey, opId);
            states.put(opId, ToolOperationState.IN_FLIGHT);
            return new Registration(opId, ToolOperationState.IN_FLIGHT, 0, 0L);
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
                    : Optional.of(new Registration(opId, states.get(opId), 1, 0L));
        }
    }

    /**
     * 内存预算替身（P3-D04）。
     *
     * <p>⚠️ <b>不可退化为 lambda</b>：D4 之前闸门持有的是返回 boolean 的
     * {@code BudgetReserver}，测试里写 {@code (t, r, n) -> true} 恒真放行——
     * 那正是「预算闸门从未真正生效」的根源。端口改为
     * {@link BudgetPort} 后（预留 + 结算 + 释放 + 查询），
     * lambda 无法表达，<b>替身被强制实现</b>：预算失败路径必须可构造。
     *
     * <p>替身同样要让契约可证伪：
     * <ul>
     *   <li>{@code granted} 可关（构造「额度不足 / 未开通账户」）；</li>
     *   <li>{@code reserveCalls} 计数（证明预算环节真的被调用）；</li>
     *   <li>结算/释放各计数（证明三种结局的处置分支都走到了）；</li>
     *   <li>UNKNOWN 分支<b>不</b>结算也不释放——可在断言中核对。</li>
     * </ul>
     */
    private static final class InMemoryBudget implements BudgetPort {

        final Map<String, Reservation> byOperation = new java.util.LinkedHashMap<>();
        int reserveCalls;
        int settleCalls;
        int releaseCalls;
        /** false = 预留被拒（模拟额度不足 / 未开通账户） */
        boolean granted = true;
        /** true = 预留层抛异常（验证 fail-closed） */
        boolean reserveThrows;

        @Override public ReserveOutcome reserve(String tenantId, String runId,
                String operationId, java.math.BigDecimal amount) {
            reserveCalls++;
            if (reserveThrows) {
                throw new IllegalStateException("模拟预算服务故障");
            }
            if (!granted) {
                return ReserveOutcome.INSUFFICIENT;
            }
            String key = operationId == null ? "anon-" + reserveCalls : operationId;
            Reservation existing = byOperation.get(key);
            if (existing != null) {
                // ⚠️ 与真实实现一致：同 operation 重复预留复用既有预留
                return ReserveOutcome.REPLAYED;
            }
            String rid = UUID.randomUUID().toString();
            byOperation.put(key, new Reservation(rid, runId, operationId, amount,
                    java.math.BigDecimal.ZERO, States.RESERVED, "CNY"));
            return ReserveOutcome.RESERVED;
        }

        @Override public boolean settle(String tenantId, String reservationId,
                java.math.BigDecimal actualAmount, String modelId) {
            settleCalls++;
            return true;
        }

        @Override public boolean release(String tenantId, String reservationId,
                String reason) {
            releaseCalls++;
            return true;
        }

        @Override public Optional<Reservation> findByOperation(String tenantId,
                String operationId) {
            return Optional.ofNullable(byOperation.get(operationId));
        }

        @Override public List<OrphanedReservation> findOrphaned(String tenantId) {
            return List.of();
        }

        @Override public Optional<AccountSnapshot> accountSnapshot(String tenantId,
                String currency) {
            return Optional.of(new AccountSnapshot("a", currency,
                    java.math.BigDecimal.valueOf(100), java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO));
        }

        @Override public boolean isConsistent(String tenantId, String currency) {
            return true;
        }
    }

    /** 预算充足且行为正常的替身工厂。 */
    private static InMemoryBudget budget() {
        return new InMemoryBudget();
    }

    /** 预算被拒的替身工厂（额度不足 / 未开通账户）。 */
    private static InMemoryBudget deniedBudget() {
        InMemoryBudget b = new InMemoryBudget();
        b.granted = false;
        return b;
    }

    /** 预留层抛异常的替身工厂（验证 fail-closed 而非降级放行）。 */
    private static InMemoryBudget brokenBudget() {
        InMemoryBudget b = new InMemoryBudget();
        b.reserveThrows = true;
        return b;
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
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()));

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
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()));

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
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()))
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
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()), Duration.ofSeconds(30), " "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("执行事件写入（P4-D12 / G4）")
    class ExecutionEvents {

        /**
         * ⚠️ 事件类型断言必须引用 {@link RunEventPort.Types} 的**常量本身**，
         * 不得用字符串拼接（如 {@code "RunEventPort." + "Types.RUN_STARTED"}）：
         * 拼出来的是全限定类名字符串，与实际写入的 {@code "RUN_STARTED"} 不等，
         * 而断言看上去仍在「验证事件类型」——实测过：编译通过、断言写对、
         * 但期望值根本不是事件类型，于是恒红。
         */

        @Test
        @DisplayName("✅ E-3 执行成功 → 写入 RUN_STARTED 与 RUN_SUCCEEDED")
        void writesStartAndSuccess() {
            FakeClaimPort port = new FakeClaimPort();
            RecordingEventPort events = new RecordingEventPort();
            var loop = loop(modelReturning(textResponse("x=2 或 x=3")), port,
                    new ToolInvocationGate(List.of(), allowAll(),
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()), events);

            loop.execute(TENANT, task(), agentConfig(), snapshot(5),
                    Map.of("q", "一元二次方程"));

            assertThat(events.types)
                    .as("⚠️ 此前生产里**没有任何写入方** → 时间线永远只有 RUN_CREATED")
                    .contains(RunEventPort.Types.RUN_STARTED, RunEventPort.Types.RUN_SUCCEEDED);
            assertThat(events.types.get(events.types.size() - 1))
                    .as("终态事件必须是最后一条")
                    .isEqualTo(RunEventPort.Types.RUN_SUCCEEDED);
        }

        @Test
        @DisplayName("✅ E-8 载荷只含白名单标量（无 Prompt 原文、无学生信息）")
        void payloadOnlyScalarWhitelistFields() {
            FakeClaimPort port = new FakeClaimPort();
            RecordingEventPort events = new RecordingEventPort();
            var loop = loop(modelReturning(textResponse("x=2 或 x=3")), port,
                    new ToolInvocationGate(List.of(), allowAll(),
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()), events);

            loop.execute(TENANT, task(), agentConfig(), snapshot(5),
                    Map.of("q", "一元二次方程"));

            assertThat(events.payloads).isNotEmpty();
            for (Map<String, Object> payload : events.payloads) {
                for (Map.Entry<String, Object> e : payload.entrySet()) {
                    // ⚠️ 必须用 isInstanceOfAny(String, Number, Boolean) 一次断言三者之一：
                    //    分三条链式断言会因「先 isInstanceOf 失败就短路」而漏掉后两种类型，
                    //    等于把「只允许标量」写成了「只允许 String」。
                    assertThat(e.getValue())
                            .as("载荷只允许标量（String/Number/Boolean）")
                            .isInstanceOfAny(String.class, Number.class, Boolean.class);
                    assertThat(e.getKey()).isIn("state", "attempt", "stepType", "toolName",
                            "operationState", "errorCode", "phase");
                }
            }
        }

        @Test
        @DisplayName("⛔ E-7 事件写入失败**不影响** Run 成败（否则会误触发重试 → 重复副作用）")
        void eventWriteFailureDoesNotBreakRun() {
            FakeClaimPort port = new FakeClaimPort();
            RecordingEventPort events = new RecordingEventPort();
            events.failOnAppend = true;
            var loop = loop(modelReturning(textResponse("x=2 或 x3")), port,
                    new ToolInvocationGate(List.of(), allowAll(),
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()), events);

            var outcome = loop.execute(TENANT, task(), agentConfig(), snapshot(5),
                    Map.of("q", "一元二次方程"));

            assertThat(outcome.kind())
                    .as("⚠️ 事件写失败若让 Run 失败 → 重试 → 重复副作用（Sev-0）")
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.SUCCEEDED);
            assertThat(port.committedState).isEqualTo("SUCCEEDED");
        }

        @Test
        @DisplayName("⛔ E-6 失败时载荷不含失败原因原文")
        void failurePayloadHasNoReasonText() {
            FakeClaimPort port = new FakeClaimPort();
            RecordingEventPort events = new RecordingEventPort();
            ModelGateway gw = request -> {
                // ⚠️ 必须用**不可重试**的错误种类：可重试失败按 P4-D12 §2.2
                //    **刻意不写终态事件**（会重投，写了会让前端误判已结束）。
                //    此前此用例误用 TIMEOUT（可重试）→ 断言 RUN_FAILED 出现，
                //    而实现正确地不写 —— 测的是「与设计相反」的东西。
                throw new ModelGateway.ModelGatewayException(
                        ModelGateway.ModelErrorKind.INVALID_REQUEST, "模型调用失败：TIMEOUT");
            };
            var loop = loop(gw, port,
                    new ToolInvocationGate(List.of(), allowAll(),
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()), events);

            loop.execute(TENANT, task(), agentConfig(), snapshot(5), Map.of("q", "x"));

            assertThat(events.types).contains(RunEventPort.Types.RUN_FAILED);
            assertThat(events.payloads.toString())
                    .as("⛔ 失败原因是内部错误细节，不得进事件载荷")
                    .doesNotContain("TIMEOUT")
                    .doesNotContain("模型调用失败");
        }

        @Test
        @DisplayName("⛔ E-6b 可重试失败**不得**写终态事件（否则前端误判已结束）")
        void retryableFailureWritesNoTerminalEvent() {
            FakeClaimPort port = new FakeClaimPort();
            RecordingEventPort events = new RecordingEventPort();
            ModelGateway gw = request -> {
                throw new ModelGateway.ModelGatewayException(
                        ModelGateway.ModelErrorKind.TIMEOUT, "timeout");
            };
            var loop = loop(gw, port,
                    new ToolInvocationGate(List.of(), allowAll(),
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()), events);

            var outcome = loop.execute(TENANT, task(), agentConfig(), snapshot(5),
                    Map.of("q", "x"));

            assertThat(outcome.kind())
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.RETRYABLE_FAILURE);
            assertThat(events.types)
                    .as("⛔ 会重投的失败不得写终态事件——前端会以为「结束了」而不再等")
                    .containsExactly(RunEventPort.Types.RUN_STARTED);
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
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()));

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
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()));

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
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()));

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
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()));

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
                    h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                    budget(), ledger());

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
                    h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                    budget(), ledger());

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
                    h -> ApprovalPolicy.ApprovalDecision.required("WRITE_EXTERNAL"),
                    budget(), ledger());

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
                    h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                    budget(), ledger());

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
                    h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                    budget(), broken);

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
        @DisplayName("⚠️ 预算不足 → 不执行，且账本记 FAILED（不是 UNKNOWN）")
        void budgetExhaustedBlocksTool() {
            var handler = new RecordingTool();
            InMemoryBudget denied = deniedBudget();
            var led = ledger();
            var gate = new ToolInvocationGate(List.of(handler), allowAll(),
                    h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                    denied, led);  // 预算预留失败

            gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "recording_tool", Map.of(), "c1")));

            assertThat(handler.executeCount.get())
                    .as("预算不足不得执行（成本已超 DEC-9 上限）")
                    .isZero();
            assertThat(denied.reserveCalls)
                    .as("预算环节必须真的被调用过")
                    .isEqualTo(1);
            // ⚠️ 预留失败时工具**根本没有发出**，结果是确定的「未执行」。
            //    记 UNKNOWN 会凭空造出待对账的幽灵操作，污染对账队列。
            assertThat(led.states.values())
                    .as("⚠️ 预留失败必须记 FAILED 而非 UNKNOWN")
                    .containsExactly(ToolOperationState.FAILED);
            assertThat(led.states.values())
                    .as("⚠️ 不得产生任何 UNKNOWN（幽灵对账项）")
                    .doesNotContain(ToolOperationState.UNKNOWN);
        }

        @Test
        @DisplayName("⚠️⚠️ 预算服务故障 → fail-closed 拒绝执行（降级放行 = 无限花钱）")
        void budgetServiceFailureFailsClosed() {
            var handler = new RecordingTool();
            var led = ledger();
            var gate = new ToolInvocationGate(List.of(handler), allowAll(),
                    h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                    brokenBudget(), led);

            gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "recording_tool", Map.of(), "c1")));

            assertThat(handler.executeCount.get())
                    .as("⚠️ 预算系统不可用时放行 = 该租户可无限花钱（P1-10 Sev-0 类）")
                    .isZero();
            assertThat(led.states.values())
                    .containsExactly(ToolOperationState.FAILED);
        }

        @Test
        @DisplayName("⚠️⚠️ UNKNOWN → 预留既不结算也不释放（R-007）")
        void unknownNeitherSettlesNorReleases() {
            var unknownTool = new UnknownResultTool();
            var b = budget();
            var gate = new ToolInvocationGate(List.of(unknownTool), allowAll(),
                    h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                    b, ledger());

            gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "record_weakness", Map.of(), "c1")));

            assertThat(b.reserveCalls).isEqualTo(1);
            assertThat(b.settleCalls)
                    .as("⚠️⚠️ UNKNOWN 时成本可能已发生，绝不可结算为「确定消耗」")
                    .isZero();
            assertThat(b.releaseCalls)
                    .as("⚠️⚠️ UNKNOWN 时释放预留 = 白送额度（R-007）")
                    .isZero();
        }

        @Test
        @DisplayName("⚠️ 工具确定失败 → 释放预留（额度可安全退回）")
        void definitelyFailedReleasesBudget() {
            var failing = new DefinitelyFailedTool();
            var b = budget();
            var gate = new ToolInvocationGate(List.of(failing), allowAll(),
                    h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                    b, ledger());

            gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "flaky_tool", Map.of(), "c1")));

            assertThat(b.releaseCalls)
                    .as("确定失败 = 无副作用，额度应退回")
                    .isEqualTo(1);
            assertThat(b.settleCalls)
                    .as("确定失败不得结算")
                    .isZero();
        }

        @Test
        @DisplayName("⚠️ 工具成功 → 结算预留，且账本先落 SUCCEEDED")
        void successSettlesBudget() {
            var ok = new RecordingTool();
            var b = budget();
            var led = ledger();
            var gate = new ToolInvocationGate(List.of(ok), allowAll(),
                    h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                    b, led);

            gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "recording_tool", Map.of(), "c1")));

            assertThat(ok.executeCount.get()).isEqualTo(1);
            assertThat(b.settleCalls)
                    .as("成功必须结算（成本真实发生）")
                    .isEqualTo(1);
            assertThat(b.releaseCalls).isZero();
            assertThat(led.states.values()).containsExactly(ToolOperationState.SUCCEEDED);
        }

        @Test
        @DisplayName("⚠️ UNKNOWN 后停止后续工具执行")
        void stopsAfterUnknown() {
            var unknownTool = new UnknownResultTool();
            var laterTool = new RecordingTool();
            var gate = new ToolInvocationGate(List.of(unknownTool, laterTool), allowAll(),
                    h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                    budget(), ledger());

            var result = gate.invokeAll(TENANT, task(), orderedCalls());

            assertThat(result.unknownResultOccurred()).isTrue();
            assertThat(laterTool.executeCount.get())
                    .as("⚠️ UNKNOWN 后不得继续执行其他工具（状态已不可信）")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("⚠️ P3-D05 审批消费（FR-013）")
    class ApprovalConsumption {

        /**
         * 可脚本化审批端口替身：让「闸门是否真的消费审批并尊重其结果」可证伪。
         *
         * <p>⚠️ 只实现 {@code consume}，其余抛异常——闸门第 ⑤ 环只消费、不授予，
         * 替身若被调用到 grant/revoke 即说明闸门越权。
         */
        private static final class ScriptedApproval implements ApprovalPort {
            private final ApprovalOutcome forced;
            int consumeCalls;

            ScriptedApproval(ApprovalOutcome forced) {
                this.forced = forced;
            }

            @Override
            public ApprovalOutcome consume(String tenantId, String operationId,
                                          String argumentDigest, String targetResource,
                                          String runId) {
                consumeCalls++;
                return forced;
            }

            @Override public String grant(String t, String o, String d, String r,
                                          ApprovalRequirement req, String s, Instant e, String a) {
                throw new UnsupportedOperationException();
            }
            @Override public void revoke(String t, String o, String a, String reason) {
                throw new UnsupportedOperationException();
            }
            @Override public Optional<ApprovalView> findActive(String t, String o) {
                throw new UnsupportedOperationException();
            }
        }

        /**
         * 可脚本化的审批结论来源（P4-D01）。
         *
         * <p>⚠️ D5 的用例此前只提供 {@code ApprovalPort}，闸门在本片新增
         * 「先问审批权威」之后会先被它拦住 —— 故须一并提供。
         */
        private static cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                .ApprovalSource source(
                cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                        .ApprovalSource.Verdict verdict) {
            return (tenantId, handler) -> verdict;
        }

        @Test
        @DisplayName("⚠️ 需要审批且审批有效(CONSUMED) → 工具执行")
        void requiredWithValidApprovalRuns() {
            var handler = new RecordingTool();
            var approval = new ScriptedApproval(ApprovalOutcome.CONSUMED);
            var gate = new ToolInvocationGate(List.of(handler), allowAll(),
                    h -> ApprovalPolicy.ApprovalDecision.required("WRITE_EXTERNAL"),
                    budget(), ledger(), approval, java.math.BigDecimal.ONE,
                    (t, r) -> false, source(
                            cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                                    .ApprovalSource.Verdict.APPROVED));

            gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "recording_tool", Map.of(), "c1")));

            assertThat(approval.consumeCalls)
                    .as("⚠️ 需要审批时必须真的调用审批端口消费")
                    .isEqualTo(1);
            assertThat(handler.executeCount.get())
                    .as("⚠️ 审批有效则工具必须执行")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️⚠️ 需要审批但无有效审批(NO_APPROVAL) → fail-closed 拒绝")
        void requiredWithoutApprovalBlocked() {
            var handler = new RecordingTool();
            var approval = new ScriptedApproval(ApprovalOutcome.NO_APPROVAL);
            var gate = new ToolInvocationGate(List.of(handler), allowAll(),
                    h -> ApprovalPolicy.ApprovalDecision.required("WRITE_EXTERNAL"),
                    budget(), ledger(), approval, java.math.BigDecimal.ONE,
                    (t, r) -> false, source(
                            cn.studykid.multiagent.application.approval.RiskBasedApprovalPolicy
                                    .ApprovalSource.Verdict.APPROVED));

            var result = gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "recording_tool", Map.of(), "c1")));

            assertThat(approval.consumeCalls).isEqualTo(1);
            assertThat(handler.executeCount.get())
                    .as("⚠️⚠️ 查不到有效审批必须拒绝，否则审批门禁形同虚设（Sev-0）")
                    .isZero();
            assertThat(result.rejectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 需要审批但过期(EXPIRED) → 拒绝")
        void requiredExpiredBlocked() {
            var handler = new RecordingTool();
            var approval = new ScriptedApproval(ApprovalOutcome.EXPIRED);
            var gate = new ToolInvocationGate(List.of(handler), allowAll(),
                    h -> ApprovalPolicy.ApprovalDecision.required("WRITE_EXTERNAL"),
                    budget(), ledger(), approval);

            var result = gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "recording_tool", Map.of(), "c1")));

            assertThat(handler.executeCount.get())
                    .as("过期审批不得放行")
                    .isZero();
            assertThat(result.rejectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 不需审批 → 直接执行，且不查审批端口")
        void notRequiredSkipsApproval() {
            var handler = new RecordingTool();
            // 即便端口表态拒绝，不需审批的工具也不应被拒
            var approval = new ScriptedApproval(ApprovalOutcome.NO_APPROVAL);
            var gate = new ToolInvocationGate(List.of(handler), allowAll(),
                    h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                    budget(), ledger(), approval);

            gate.invokeAll(TENANT, task(), Map.of(
                    "c1", new ModelResponse.ToolCallRequest(
                            "recording_tool", Map.of(), "c1")));

            assertThat(approval.consumeCalls)
                    .as("⚠️ 不需审批时绝不应调用审批端口（避免无谓的 fail-closed）")
                    .isZero();
            assertThat(handler.executeCount.get()).isEqualTo(1);
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
                            h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                            budget(), ledger()));

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

    // ── P3-D06 取消竞态（FR-011）─────────────────────────────────────

    @Nested
    @DisplayName("⚠️ P3-D06 取消竞态（FR-011）")
    class CancellationRace {

        /** 恒定「已取消」的守卫替身。 */
        private static final ToolInvocationGate.CancelGuard CANCELLED =
                (t, r) -> true;

        /** 恒定「未取消」的守卫替身。 */
        private static final ToolInvocationGate.CancelGuard NOT_CANCELLED =
                (t, r) -> false;

        /**
         * 构造带显式取消守卫的闸门。
         *
         * <p>⚠️ 审批策略一律 notRequired —— 本组用例只验证取消路径，
         * 审批路径由 {@link ApprovalConsumption} 独立覆盖，两者互不干扰
         * （D6 与 D5 的检查点必须能分别证伪）。
         */
        private static ToolInvocationGate gateWith(ToolHandler handler,
                                                 ToolInvocationGate.CancelGuard guard) {
            return new ToolInvocationGate(List.of(handler), allowAll(),
                    h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                    budget(), ledger(), neverApproving(), java.math.BigDecimal.ONE, guard);
        }

        /** 永不批准的审批端口（策略为 notRequired 时不会被调用，仅为满足构造契约）。 */
        private static ApprovalPort neverApproving() {
            return new ApprovalPort() {
                @Override public ApprovalOutcome consume(String t, String o, String d,
                        String r, String runId) {
                    throw new AssertionError("notRequired 策略下不应调用审批消费");
                }
                @Override public String grant(String t, String o, String d, String r,
                        ApprovalRequirement q, String s, Instant e, String a) {
                    throw new UnsupportedOperationException();
                }
                @Override public void revoke(String t, String o, String a, String reason) {
                    throw new UnsupportedOperationException();
                }
                @Override public Optional<ApprovalView> findActive(String t, String o) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Test
        @DisplayName("⚠️⚠️ 循环级：已取消的 Run → 模型不被调用、工具不执行")
        void cancelledRunStopsBeforeModelCall() {
            MODEL_CALLS.set(0);
            FakeClaimPort port = new FakeClaimPort();
            // ⚠️ 取消在执行开始前已到达
            assertThat(port.requestCancel(TENANT, RUN_ID)).isTrue();
            assertThat(port.isCancelled(TENANT, RUN_ID)).isTrue();

            var handler = new RecordingTool();
            var loop = loop(modelReturning(textResponse("ok")), port,
                    gateWith(handler, CANCELLED));

            var outcome = loop.execute(TENANT, task(), agentConfig(), snapshot(5),
                    Map.of("q", "一元二次方程"));

            assertThat(outcome.kind())
                    .isEqualTo(RunExecutionLoop.ExecutionOutcome.Kind.CANCELLED);
            assertThat(MODEL_CALLS.get())
                    .as("⚠️⚠️ 已取消的 Run 不得再调模型（白花钱且可能启动新动作）")
                    .isZero();
            assertThat(handler.executeCount.get()).isZero();
            assertThat(port.commitCount)
                    .as("取消不额外提交终态——requestCancel 已原子置 CANCELLED")
                    .isZero();
        }

        @Test
        @DisplayName("⚠️⚠️ 闸门级：取消在模型返回后到达 → 批次内工具被拒、不执行")
        void cancelAfterModelReturnBlocksTools() {
            var handler = new RecordingTool();
            var gate = gateWith(handler, CANCELLED);

            // ⚠️ 批次内两个工具调用（顺序固定：record_weakness → recording_tool）
            var result = gate.invokeAll(TENANT, task(), orderedCalls());

            assertThat(handler.executeCount.get())
                    .as("⚠️⚠️ 取消后不得启动任何工具动作（副作用可能已发生）")
                    .isZero();
            assertThat(result.rejectedCount())
                    .as("批次内两个工具都应被拒")
                    .isEqualTo(2);
            assertThat(result.allRejected()).isTrue();
        }

        @Test
        @DisplayName("⚠️ 反证：闸门无取消检查（默认 NO_CANCEL 桩）→ 已取消 Run 的工具照常执行")
        void withoutCancelGuardToolsStillRun() {
            var handler = new RecordingTool();
            // ⚠️ 默认构造器 = NO_CANCEL 桩 = 取消检查缺失
            var gate = new ToolInvocationGate(List.of(handler), allowAll(),
                    h -> ApprovalPolicy.ApprovalDecision.notRequired(),
                    budget(), ledger());

            gate.invokeAll(TENANT, task(), orderedCalls());

            assertThat(handler.executeCount.get())
                    .as("⚠️ 反证：默认桩下工具仍执行，证明闸门级取消检查是 load-bearing")
                    .isGreaterThan(0);
        }

        @Test
        @DisplayName("✅ 控制组：未取消 → 工具正常执行（闸门不被误伤）")
        void notCancelledRunsNormally() {
            FakeClaimPort port = new FakeClaimPort();
            assertThat(port.isCancelled(TENANT, RUN_ID)).isFalse();

            var handler = new RecordingTool();
            gateWith(handler, NOT_CANCELLED).invokeAll(TENANT, task(), orderedCalls());

            assertThat(handler.executeCount.get())
                    .as("⚠️ 未取消时不得被误拦（否则功能不可用）")
                    .isGreaterThan(0);
        }

        @Test
        @DisplayName("⚠️ 取消幂等：已取消的 Run 重复取消返回 false")
        void cancelIsIdempotent() {
            FakeClaimPort port = new FakeClaimPort();
            assertThat(port.requestCancel(TENANT, RUN_ID)).isTrue();
            assertThat(port.requestCancel(TENANT, RUN_ID))
                    .as("已取消的 Run 重复取消必须返回 false（幂等且不回退终态）")
                    .isFalse();
        }
    }
}