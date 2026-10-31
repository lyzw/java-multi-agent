package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.ApprovalQueryPort;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.ApprovalWakeupPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;

/**
 * M1-6 回归测试：审批决定唤醒器的 <b>fail-closed</b> 语义。
 *
 * <p><b>背景</b>：Run 挂起（{@code WAITING_APPROVAL}）后，审批决定到达时
 * <b>必须有人把它拉回执行</b>；而唤醒过程中的每一种不确定都必须
 * <b>明确收敛</b>，不得静默放行、也不得把 Run 悬死。
 *
 * <p><b>断言要点</b>：
 * <ol>
 *   <li>审批行读不到 → 丢弃并告警，<b>不抛</b>（人工审批可用性不受影响）；</li>
 *   <li>审批仍是 PENDING → 不唤醒（不能用「还没决定」的审批去消费）；</li>
 *   <li>Run 已不在挂起态 → 不重试（重试 = 反复抢一个已不在挂起态的 Run）；</li>
 *   <li>反查不到原始调用 → <b>绝不臆造参数</b>重入，Run 显式收敛 FAILED。</li>
 * </ol>
 */
class ApprovalDecidedWakeupConsumerTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";
    private static final String RUN_ID = "run-1";
    private static final String OPERATION_ID = "op-1";
    private static final String APPROVAL_ID = "ap-1";

    /** 审批查询端口：可配置查到的审批行。 */
    private static final class StubApprovalQuery implements ApprovalQueryPort {
        ApprovalItem item;

        @Override
        public Page<ApprovalItem> list(String tenantId, String state, String cursor, int limit) {
            return new Page<>(List.of(), null);
        }

        @Override
        public ApprovalItem find(String tenantId, String approvalId) {
            return item;
        }

        @Override
        public ApprovalDecision decide(String tenantId, String approvalId, boolean approve,
                String approverId, String reason) {
            throw new UnsupportedOperationException("本用例不做决定");
        }
    }

    /** 领取端口：记录是否尝试领取挂起 Run。 */
    private static final class StubClaimPort implements RunClaimPort {
        boolean parked = true;
        int claimAttempts;
        /** 收敛时提交的状态（用于断言 Run 被显式收敛而非悬着）。 */
        String committedState;

        @Override
        public List<LeasedTask> claimTasks(String tenantId, String workerId,
                Duration leaseDuration, int limit) {
            return List.of();
        }

        @Override
        public Optional<LeasedTask> claimSpecificRun(String tenantId, String runId,
                String workerId, Duration leaseDuration) {
            return Optional.empty();
        }

        @Override
        public Optional<LeasedTask> claimParkedRunForResume(String tenantId, String runId,
                String workerId, Duration leaseDuration) {
            claimAttempts++;
            if (!parked) {
                return Optional.empty();
            }
            return Optional.of(new LeasedTask(runId, 7L, Instant.now().plusSeconds(60), 2));
        }

        @Override
        public boolean renewLease(String tenantId, String runId, String workerId,
                long expectedFencingToken, Duration leaseDuration) {
            return true;
        }

        @Override
        public boolean releaseLease(String tenantId, String runId, String workerId,
                long expectedFencingToken) {
            return true;
        }

        @Override
        public boolean commitResult(String tenantId, String runId, long expectedFencingToken,
                String state, String resultPayload) {
            committedState = state;
            return true;
        }

        @Override
        public boolean isCurrentGeneration(String tenantId, String runId, long fencingToken) {
            return true;
        }

        @Override
        public boolean isCancelled(String tenantId, String runId) {
            return false;
        }

        @Override
        public boolean requestCancel(String tenantId, String runId) {
            return true;
        }
    }

    /** 账本：可配置反查结果。 */
    private static final class StubLedger implements RunToolLedgerPort {
        boolean invocationPresent = true;
        String committedState;

        @Override
        public Registration register(String tenantId, String runId, String toolName,
                String riskLevel, String idempotencyKey, String requestDigest,
                Map<String, Object> arguments, long fencingToken) {
            return new Registration(OPERATION_ID,
                    cn.studykid.multiagent.core.run.ToolOperationState.IN_FLIGHT,
                    1, fencingToken, true);
        }

        @Override
        public void recordResult(String tenantId, String operationId,
                cn.studykid.multiagent.core.run.ToolOperationState result, long fencingToken,
                String errorClass, String errorMessage) { }

        @Override
        public void reconcile(String tenantId, String operationId,
                cn.studykid.multiagent.core.run.ToolOperationState reconciled, String source) { }

        @Override
        public void manualDispose(String tenantId, String operationId, String disposedBy,
                String note) { }

        @Override
        public List<UnknownOperation> findUnknownOperations(String tenantId) {
            return List.of();
        }

        @Override
        public Optional<Registration> findByIdempotencyKey(String tenantId,
                String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public Optional<Invocation> findInvocation(String tenantId, String operationId) {
            if (!invocationPresent) {
                return Optional.empty();
            }
            return Optional.of(new Invocation("record_weakness",
                    Map.of("studentId", "s-1"), "c1"));
        }
    }

    private static ApprovalQueryPort.ApprovalItem item(String state) {
        return new ApprovalQueryPort.ApprovalItem(
                APPROVAL_ID, OPERATION_ID, RUN_ID, state, "REQUIRED",
                "args_norm_v1:abc", "record_weakness", "PARENT_OR_TEACHER",
                Instant.now().plusSeconds(600), "approver-1", Instant.now(),
                Instant.now(), false);
    }

    @Test
    @DisplayName("✅ 审批行读不到 → 丢弃 + 不抛（人工审批可用性不受影响）")
    void missingApprovalIsDiscardedQuietly() {
        StubApprovalQuery approvals = new StubApprovalQuery();
        StubClaimPort claims = new StubClaimPort();
        ApprovalDecidedWakeupConsumer consumer = new ApprovalDecidedWakeupConsumer(
                approvals, claims, new StubLedger(), null, "worker-1", Duration.ofSeconds(30));

        ApprovalWakeupPort.Result result = consumer.wakeup(TENANT, APPROVAL_ID);

        assertThat(result).isEqualTo(ApprovalWakeupPort.Result.NOT_FOUND);
        assertThat(claims.claimAttempts)
                .as("读不到审批行时不得尝试领取（无从知道该唤醒哪个 Run）")
                .isZero();
    }

    @Test
    @DisplayName("✅ 审批仍是 PENDING → 不唤醒（不能用「还没决定」的审批去消费）")
    void pendingApprovalDoesNotWakeUp() {
        StubApprovalQuery approvals = new StubApprovalQuery();
        approvals.item = item("PENDING");
        StubClaimPort claims = new StubClaimPort();
        ApprovalDecidedWakeupConsumer consumer = new ApprovalDecidedWakeupConsumer(
                approvals, claims, new StubLedger(), null, "worker-1", Duration.ofSeconds(30));

        ApprovalWakeupPort.Result result = consumer.wakeup(TENANT, APPROVAL_ID);

        assertThat(result).isEqualTo(ApprovalWakeupPort.Result.NOT_DECIDED);
        assertThat(claims.claimAttempts).isZero();
    }

    @Test
    @DisplayName("✅ Run 已不在挂起态 → 放弃唤醒，不重试")
    void runNotParkedIsNotRetried() {
        StubApprovalQuery approvals = new StubApprovalQuery();
        approvals.item = item("APPROVED");
        StubClaimPort claims = new StubClaimPort();
        claims.parked = false;
        ApprovalDecidedWakeupConsumer consumer = new ApprovalDecidedWakeupConsumer(
                approvals, claims, new StubLedger(), null, "worker-1", Duration.ofSeconds(30));

        ApprovalWakeupPort.Result result = consumer.wakeup(TENANT, APPROVAL_ID);

        assertThat(result).isEqualTo(ApprovalWakeupPort.Result.NOT_PARKED);
        assertThat(claims.claimAttempts)
                .as("重试 = 反复抢一个已不在挂起态的 Run（打转）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("⚠️ 反查不到原始调用 → Run 显式收敛 FAILED，工具未执行")
    void missingInvocationFailsRunWithoutSideEffect() {
        StubApprovalQuery approvals = new StubApprovalQuery();
        approvals.item = item("APPROVED");
        StubClaimPort claims = new StubClaimPort();
        StubLedger ledger = new StubLedger();
        ledger.invocationPresent = false;
        ApprovalDecidedWakeupConsumer consumer = new ApprovalDecidedWakeupConsumer(
                approvals, claims, ledger, null, "worker-1", Duration.ofSeconds(30));

        ApprovalWakeupPort.Result result = consumer.wakeup(TENANT, APPROVAL_ID);

        assertThat(result).isEqualTo(ApprovalWakeupPort.Result.INVOCATION_MISSING);
        assertThat(claims.committedState)
                .as("⚠️ 读不到原始调用时必须显式收敛 FAILED（不得悬在 RUNNING）")
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("✅ 审批已批准 → 领取挂起 Run 并进入恢复执行")
    void approvedDecisionResumesRun() {
        StubApprovalQuery approvals = new StubApprovalQuery();
        approvals.item = item("APPROVED");
        StubClaimPort claims = new StubClaimPort();
        StubLedger ledger = new StubLedger();
        ApprovalDecidedWakeupConsumer consumer = new ApprovalDecidedWakeupConsumer(
                approvals, claims, ledger, null, "worker-1", Duration.ofSeconds(30));

        // loop 为 null → 恢复执行时会 NPE；本用例只验证「已进入恢复路径」
        try {
            consumer.wakeup(TENANT, APPROVAL_ID);
        } catch (NullPointerException expected) {
            // 预期：恢复执行需要装配 loop
        }
        assertThat(claims.claimAttempts)
                .as("必须尝试领取挂起 Run（不领 = 没人恢复）")
                .isEqualTo(1);
    }
}
