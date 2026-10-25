package cn.studykid.multiagent.application.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import cn.studykid.multiagent.ports.persistence.RunLimits;

/**
 * Run 创建时的<b>额度一致性</b>（多级 Agent v1.1 · FR-024 · MA-T17）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：Run 级额度由创建方声明，
 * 此前<b>不校验它是否 ≤ 租户账户总额度</b> ——
 * 若声明的额度更大，Run 级上限<b>永远不会被触发</b>
 * （租户级先耗尽），「这一棵调用树最多花多少」就成了空话。
 *
 * <p><b>本类的判据</b>：
 * <ul>
 *   <li>L-1：声明额度 &gt; 租户总额度 → <b>拒绝创建</b>（fail-closed）；</li>
 *   <li>L-2：租户未开通预算账户 → 拒绝（没有账户就没有「租户总额度」可比）；</li>
 *   <li>L-3：读不到账户 → 拒绝（校验缺席 ≠ 校验通过）；</li>
 *   <li>L-4：未装配预算端口 → 拒绝（否则「声明了额度」一路畅通）；</li>
 *   <li>L-5：<b>未声明额度</b> → 不校验、不拒绝（不给无额度的创建请求
 *       增加「预算系统必须可用」这一新的失败面）。</li>
 * </ul>
 */
class RunCreationServiceBudgetTest {

    private static final String TENANT = "t-a";
    private static final TenantContext CONTEXT =
            new TenantContext(TENANT, "p-1", java.util.Set.of("TENANT_ADMIN"), 1L);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
            ZoneOffset.UTC);

    // ── 替身 ────────────────────────────────────────────────────────

    /** 记录是否真的写了 Run（拒绝路径上必须一次都不写）。 */
    private static final class RecordingRunCreation implements RunCreationPort {
        final AtomicInteger inserts = new AtomicInteger();
        RunLimits seenLimits;

        @Override
        public @NonNull String insertRunAtomically(@NonNull String tenantId,
                                                   @NonNull String runId,
                                                   @NonNull String snapshotId,
                                                   @NonNull IdempotencyKey key,
                                                   @NonNull RequestDigest digest,
                                                   @NonNull Map<String, Object> inputPayload,
                                                   @NonNull String correlationId,
                                                   @NonNull OutboxMessage agentPayload) {
            inserts.incrementAndGet();
            return runId;
        }

        @Override
        public @NonNull String insertRunAtomically(@NonNull String tenantId,
                                                   @NonNull String runId,
                                                   @NonNull String snapshotId,
                                                   @NonNull IdempotencyKey key,
                                                   @NonNull RequestDigest digest,
                                                   @NonNull Map<String, Object> inputPayload,
                                                   @NonNull String correlationId,
                                                   @NonNull OutboxMessage agentPayload,
                                                   @NonNull RunLimits limits) {
            this.seenLimits = limits;
            inserts.incrementAndGet();
            return runId;
        }

        @Override
        public RunCreationPort.@Nullable RunRecord
                findRun(@NonNull String tenantId, @NonNull String runId) {
            return null;
        }

        @Override
        public @NonNull OutboxBatch loadPendingOutbox(@NonNull String tenantId, int limit) {
            return new OutboxBatch(List.of());
        }

        @Override
        public void markOutboxPublished(@NonNull String tenantId, @NonNull String outboxId,
                                        @NonNull Instant publishedAt) {
        }

        @Override
        public void markOutboxFailed(@NonNull String tenantId, @NonNull String outboxId,
                                     @NonNull String error) {
        }

        @Override
        public @NonNull IdempotencyDecision findExistingResource(
                @NonNull String tenantId, @NonNull IdempotencyKey key,
                @NonNull RequestDigest digest) {
            return IdempotencyDecision.firstExecution(UUID.randomUUID().toString());
        }
    }

    /** 恒解析出快照的替身。 */
    private static final ExecutionSnapshotPort SNAPSHOT =
            (tenantId, agentId) -> Optional.of("11111111-1111-1111-1111-111111111111");

    /** 恒「首次执行」的极简事务器（不引入真实事务语义）。 */
    private static final RunCreationService.TransactionRunner TX =
            new RunCreationService.TransactionRunner() {
                @Override
                public <T> T execute(@NonNull String tenantId,
                                     TransactionalAction<T> action) {
                    return action.run();
                }
            };

    /** 预算端口替身：账户总额度可配，读取可配「抛异常」。 */
    private static BudgetPort budgetPort(BigDecimal totalLimit) {
        return budgetPort(totalLimit, false);
    }

    private static BudgetPort budgetPort(BigDecimal totalLimit, boolean failing) {
        return new BudgetPort() {
            @Override
            public @NonNull ReserveOutcome reserve(@NonNull String tenantId,
                                                   @NonNull String runId,
                                                   String operationId,
                                                   @NonNull BigDecimal amount) {
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
            public @NonNull Optional<AccountSnapshot> accountSnapshot(
                    @NonNull String tenantId, @NonNull String currency) {
                if (failing) {
                    throw new IllegalStateException("预算存储不可用");
                }
                if (totalLimit == null) {
                    // 租户未开通预算账户
                    return Optional.empty();
                }
                return Optional.of(new AccountSnapshot("acc-1", currency, totalLimit,
                        BigDecimal.ZERO, BigDecimal.ZERO));
            }

            @Override
            public boolean isConsistent(@NonNull String tenantId, @NonNull String currency) {
                return true;
            }
        };
    }

    private static RunCreationService service(RunCreationPort runCreation, BudgetPort budget) {
        return new RunCreationService(runCreation, SNAPSHOT, TX, CLOCK, budget);
    }

    private static RunLimits budget(String amount) {
        return new RunLimits(null, new BigDecimal(amount));
    }

    // ── L-1 ～ L-5 ──────────────────────────────────────────────────

    @Test
    @DisplayName("L-1 ⛔⚠️ 声明的 Run 额度 > 租户账户总额度 → 拒绝创建（该上限永远不会被触发）")
    void declaredAmountAboveTenantLimitIsRejected() {
        RecordingRunCreation runCreation = new RecordingRunCreation();
        RunCreationService service = service(runCreation, budgetPort(new BigDecimal("100")));

        assertThatThrownBy(() -> service.createRun(CONTEXT, "agent-1",
                new IdempotencyKey("run.create", "k-1"), Map.of("q", "x"),
                budget("500")))
                .isInstanceOf(RunBudgetLimitInvalidException.class)
                .hasMessageContaining("永远不会被触发");

        assertThat(runCreation.inserts.get())
                .as("⚠️ 拒绝必须发生在写入之前：矛盾的额度声明不该留下半成品 Run")
                .isZero();
    }

    @Test
    @DisplayName("L-2 ⛔ 租户未开通预算账户 → 拒绝创建（没有账户就没有可比的上限）")
    void missingBudgetAccountIsRejected() {
        RecordingRunCreation runCreation = new RecordingRunCreation();
        RunCreationService service = service(runCreation, budgetPort(null));

        assertThatThrownBy(() -> service.createRun(CONTEXT, "agent-1",
                new IdempotencyKey("run.create", "k-2"), Map.of("q", "x"),
                budget("10")))
                .isInstanceOf(RunBudgetLimitInvalidException.class)
                .hasMessageContaining("未开通预算账户");

        assertThat(runCreation.inserts.get()).isZero();
    }

    @Test
    @DisplayName("L-3 ⛔ 预算账户读不到 → 拒绝创建（fail-closed）")
    void unreadableAccountIsRejected() {
        RecordingRunCreation runCreation = new RecordingRunCreation();
        RunCreationService service = service(runCreation,
                budgetPort(new BigDecimal("100"), true));

        assertThatThrownBy(() -> service.createRun(CONTEXT, "agent-1",
                new IdempotencyKey("run.create", "k-3"), Map.of("q", "x"),
                budget("10")))
                .isInstanceOf(RunBudgetLimitInvalidException.class)
                .hasMessageContaining("fail-closed");

        assertThat(runCreation.inserts.get()).isZero();
    }

    @Test
    @DisplayName("L-4 ⛔ 未装配预算端口 → 拒绝「声明了额度」的创建（校验缺席 ≠ 校验通过）")
    void missingBudgetPortIsRejected() {
        RecordingRunCreation runCreation = new RecordingRunCreation();
        RunCreationService service = service(runCreation, null);

        assertThatThrownBy(() -> service.createRun(CONTEXT, "agent-1",
                new IdempotencyKey("run.create", "k-4"), Map.of("q", "x"),
                budget("10")))
                .isInstanceOf(RunBudgetLimitInvalidException.class)
                .hasMessageContaining("未装配预算端口");

        assertThat(runCreation.inserts.get()).isZero();
    }

    @Test
    @DisplayName("L-5 ✅ 未声明额度 → 不校验、不拒绝（不得新增「预算系统必须可用」的失败面）")
    void undeclaredBudgetIsNotChecked() {
        RecordingRunCreation runCreation = new RecordingRunCreation();
        // ⚠️ 故意装配一个「读就会抛」的预算端口：
        //    未声明额度时根本不该去读它。
        RunCreationService service = service(runCreation,
                budgetPort(new BigDecimal("100"), true));

        var result = service.createRun(CONTEXT, "agent-1",
                new IdempotencyKey("run.create", "k-5"), Map.of("q", "x"),
                RunLimits.UNLIMITED);

        assertThat(result.runId()).isNotBlank();
        assertThat(runCreation.inserts.get())
                .as("未声明额度 = 不额外限制，创建必须正常放行")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("✅ 声明额度 ≤ 租户总额度 → 正常创建，且额度随 Run 落库")
    void declaredAmountWithinTenantLimitIsAccepted() {
        RecordingRunCreation runCreation = new RecordingRunCreation();
        RunCreationService service = service(runCreation, budgetPort(new BigDecimal("100")));

        var result = service.createRun(CONTEXT, "agent-1",
                new IdempotencyKey("run.create", "k-6"), Map.of("q", "x"),
                budget("80"));

        assertThat(result.runId()).isNotBlank();
        assertThat(runCreation.inserts.get()).isEqualTo(1);
        assertThat(runCreation.seenLimits)
                .as("⚠️ 额度必须随 Run 一起落库，否则「共享上限」在根层就是一句空话")
                .isNotNull();
        assertThat(runCreation.seenLimits.budgetAmount())
                .isEqualByComparingTo("80");
    }
}
