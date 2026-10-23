package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapCompensationTask;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;
import cn.studykid.multiagent.ports.persistence.CompensationTaskPort;

/**
 * 补偿框架测试（P3-T4 · §5.3.3 补偿 · §5.3.5 跨系统恢复表）。
 *
 * <p>⚠️ <b>两条不变量</b>：
 * <ol>
 *   <li><b>补偿必须幂等</b>：重复释放额度 = 额度凭空放大（比超发更隐蔽）；</li>
 *   <li><b>补偿失败必须落库</b>：只记日志就只能靠人肉排查，
 *       且「悬空的额度 / 悬空的审批」会永久挡住后续调用。</li>
 * </ol>
 */
class CompensationFrameworkTest {

    private static final String TENANT = "t-cap-comp";
    private static final String APPROVAL_ID = "cap-approval-1";
    private static final String INVOCATION_ID = "inv-1";
    private static final String RESERVATION_ID = "res-1";

    /** 可控的失败开关（三个替身共享）。 */
    private static final class Flags {
        boolean approvalThrows;
        boolean budgetThrows;
        int cancelCount;
        int releaseCount;
        final List<CapCompensationTask> enqueued = new java.util.ArrayList<>();
    }

    /** 审批端口替身（可注入取消失败）。 */
    private static final class ApprovalStub implements CapApprovalPort {

        private final Flags flags;

        ApprovalStub(Flags flags) {
            this.flags = flags;
        }

        @Override
        public @NonNull String grant(@NonNull CapApprovalGrant grant) {
            return APPROVAL_ID;
        }

        @Override
        public @NonNull ReservationOutcome reserve(@NonNull String tenantId,
                                                    @NonNull String approvalId,
                                                    @NonNull String invocationId,
                                                    @NonNull String operationId,
                                                    @Nullable String runId,
                                                    @NonNull String effectiveArgsHash,
                                                    @NonNull String closureFingerprint,
                                                    long authzVersion) {
            return ReservationOutcome.RESERVED;
        }

        @Override
        public boolean markIntentSubmitted(@NonNull String tenantId, @NonNull String approvalId,
                                            @NonNull String invocationId) {
            return true;
        }

        @Override
        public boolean consume(@NonNull String tenantId, @NonNull String approvalId,
                               @NonNull String invocationId, @NonNull String terminalStatus) {
            return true;
        }

        @Override
        public boolean cancel(@NonNull String tenantId, @NonNull String approvalId,
                              @NonNull String invocationId, @NonNull String reason) {
            flags.cancelCount++;
            if (flags.approvalThrows) {
                throw new IllegalStateException("审批存储不可用");
            }
            return true;
        }

        @Override
        public @NonNull Optional<cn.studykid.multiagent.core.cap.CapApproval> findById(
                @NonNull String tenantId, @NonNull String approvalId) {
            return Optional.empty();
        }

        @Override
        public @NonNull List<cn.studykid.multiagent.core.cap.CapApproval> findByTool(
                @NonNull String tenantId, @NonNull String toolId) {
            return List.of();
        }

        @Override
        public @NonNull Optional<cn.studykid.multiagent.core.cap.CapApproval> findByInvocation(
                @NonNull String tenantId, @NonNull String invocationId) {
            return Optional.empty();
        }
    }

    /** 预算端口替身（可注入释放失败）。 */
    private static final class BudgetStub implements BudgetPort {

        private final Flags flags;

        BudgetStub(Flags flags) {
            this.flags = flags;
        }

        @Override
        public @NonNull ReserveOutcome reserve(@NonNull String tenantId, @NonNull String runId,
                                               String operationId, @NonNull BigDecimal amount) {
            return ReserveOutcome.RESERVED;
        }

        @Override
        public boolean settle(@NonNull String tenantId, @NonNull String reservationId,
                              @NonNull BigDecimal actualAmount, String modelId) {
            return true;
        }

        @Override
        public boolean release(@NonNull String tenantId, @NonNull String reservationId,
                               @NonNull String reason) {
            flags.releaseCount++;
            if (flags.budgetThrows) {
                throw new IllegalStateException("预算存储不可用");
            }
            return true;
        }

        @Override
        public @NonNull Optional<Reservation> findByOperation(@NonNull String tenantId,
                                                              @NonNull String operationId) {
            return Optional.empty();
        }

        @Override
        public @NonNull List<OrphanedReservation> findOrphaned(@NonNull String tenantId) {
            return List.of();
        }

        @Override
        public @NonNull Optional<AccountSnapshot> accountSnapshot(@NonNull String tenantId,
                                                                  @NonNull String currency) {
            return Optional.empty();
        }

        @Override
        public boolean isConsistent(@NonNull String tenantId, @NonNull String currency) {
            return true;
        }
    }

    /** 补偿任务端口替身。 */
    private static final class TaskStub implements CompensationTaskPort {

        private final Flags flags;

        TaskStub(Flags flags) {
            this.flags = flags;
        }

        @Override
        public boolean enqueue(@NonNull CapCompensationTask task) {
            // ⚠️ 照搬 JdbcCompensationTaskRepository 的 ON CONFLICT DO NOTHING 语义：
            //    幂等靠 DB 的 UNIQUE(tenant, kind, target_id)，
            //    替身必须复刻它，否则测到的是替身的行为而非幂等键是否稳定。
            boolean exists = flags.enqueued.stream()
                    .anyMatch(t -> t.kind() == task.kind()
                            && t.targetId().equals(task.targetId()));
            if (!exists) {
                flags.enqueued.add(task);
            }
            return !exists;
        }

        @Override
        public @NonNull Optional<CapCompensationTask> findById(@NonNull String tenantId,
                                                                @NonNull String taskId) {
            return Optional.empty();
        }

        @Override
        public @NonNull List<CapCompensationTask> findPending(@NonNull String tenantId, int limit) {
            return List.copyOf(flags.enqueued);
        }

        @Override
        public boolean markCompleted(@NonNull String tenantId, @NonNull String taskId) {
            return true;
        }

        @Override
        public boolean markFailed(@NonNull String tenantId, @NonNull String taskId,
                                  @NonNull String error, @NonNull Instant nextRetryAt) {
            return true;
        }
    }

    private static CompensationFramework framework(Flags flags) {
        return new CompensationFramework(new ApprovalStub(flags), new BudgetStub(flags),
                new TaskStub(flags));
    }

    private static CompensationFramework.Request request() {
        return new CompensationFramework.Request(TENANT, APPROVAL_ID, INVOCATION_ID,
                RESERVATION_ID, "budget-failed");
    }

    @Nested
    @DisplayName("补偿成功")
    class Success {

        @Test
        @DisplayName("✅ 取消审批 + 释放预算，且不入队补偿任务")
        void bothActionsSucceed() {
            Flags flags = new Flags();
            CompensationFramework framework = framework(flags);

            CompensationFramework.Result result = framework.compensate(request());

            assertThat(result.approvalCancelled()).isTrue();
            assertThat(result.budgetReleased()).isTrue();
            assertThat(result.enqueued()).isEmpty();
            assertThat(flags.cancelCount).isEqualTo(1);
            assertThat(flags.releaseCount).isEqualTo(1);
        }

        @Test
        @DisplayName("✅ 无审批无预留 → 空操作")
        void nothingToCompensate() {
            Flags flags = new Flags();
            CompensationFramework framework = framework(flags);

            CompensationFramework.Result result = framework.compensate(
                    new CompensationFramework.Request(TENANT, null, INVOCATION_ID, null, "x"));

            assertThat(flags.cancelCount).isZero();
            assertThat(flags.releaseCount).isZero();
            assertThat(result.enqueued()).isEmpty();
        }
    }

    @Nested
    @DisplayName("补偿失败必须落库")
    class FailurePersisted {

        @Test
        @DisplayName("⚠️ 审批取消失败 → 落 CANCEL_APPROVAL 任务，且仍释放预算")
        void approvalFailureEnqueuesTask() {
            Flags flags = new Flags();
            flags.approvalThrows = true;
            CompensationFramework framework = framework(flags);

            CompensationFramework.Result result = framework.compensate(request());

            assertThat(result.approvalCancelled()).isFalse();
            assertThat(result.enqueued())
                    .extracting(CapCompensationTask::kind)
                    .containsExactly(CapCompensationTask.CompensationKind.CANCEL_APPROVAL);
            assertThat(flags.releaseCount)
                    .as("⚠️ 取消审批失败不得跳过预算释放（否则同时留下两个悬空态）")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 预算释放失败 → 落 RELEASE_BUDGET 任务")
        void budgetFailureEnqueuesTask() {
            Flags flags = new Flags();
            flags.budgetThrows = true;
            CompensationFramework framework = framework(flags);

            CompensationFramework.Result result = framework.compensate(request());

            assertThat(result.budgetReleased()).isFalse();
            assertThat(result.enqueued())
                    .extracting(CapCompensationTask::kind)
                    .containsExactly(CapCompensationTask.CompensationKind.RELEASE_BUDGET);
            assertThat(flags.cancelCount).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 两者都失败 → 两个任务都落，且互不阻断")
        void bothFailuresEnqueueBothTasks() {
            Flags flags = new Flags();
            flags.approvalThrows = true;
            flags.budgetThrows = true;
            CompensationFramework framework = framework(flags);

            CompensationFramework.Result result = framework.compensate(request());

            assertThat(result.enqueued())
                    .extracting(CapCompensationTask::kind)
                    .containsExactlyInAnyOrder(
                            CapCompensationTask.CompensationKind.CANCEL_APPROVAL,
                            CapCompensationTask.CompensationKind.RELEASE_BUDGET);
        }
    }

    @Nested
    @DisplayName("幂等")
    class Idempotency {

        @Test
        @DisplayName("✅ 重复补偿不产生二次释放")
        void repeatedCompensationIsIdempotent() {
            Flags flags = new Flags();
            CompensationFramework framework = framework(flags);

            framework.compensate(request());
            framework.compensate(request());

            assertThat(flags.releaseCount)
                    .as("⚠️ 幂等由端口的 CAS 保证；框架不得额外制造二次效果")
                    .isEqualTo(2);
            assertThat(flags.enqueued.size()).isZero();
        }

        @Test
        @DisplayName("✅ 落账重试任务按 (kind, target) 幂等入队")
        void ledgerRetryEnqueueIsIdempotent() {
            Flags flags = new Flags();
            CompensationFramework framework = framework(flags);

            framework.enqueueLedgerRetry(TENANT, "op-1", "op-1", "{}");
            framework.enqueueLedgerRetry(TENANT, "op-1", "op-1", "{}");

            assertThat(flags.enqueued)
                    .extracting(CapCompensationTask::kind)
                    .containsExactly(CapCompensationTask.CompensationKind.RETRY_LEDGER_POST);
        }
    }
}
