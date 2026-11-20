package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.BudgetMapper;
import cn.studykid.multiagent.infrastructure.persistence.mapper.BudgetMapper.ReservationRow;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.BudgetPort.AccountSnapshot;
import cn.studykid.multiagent.ports.persistence.BudgetPort.OrphanedReservation;
import cn.studykid.multiagent.ports.persistence.BudgetPort.ReserveOutcome;
import cn.studykid.multiagent.ports.persistence.BudgetPort.Reservation;

/** BudgetPort MyBatis 实现（条件 UPDATE / ON CONFLICT / FOR UPDATE 锁行）。 */
@Repository
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisBudgetRepository implements BudgetPort {

    private static final Logger log = LoggerFactory.getLogger(MybatisBudgetRepository.class);
    private static final String DEFAULT_CURRENCY = BudgetPort.SETTLEMENT_CURRENCY;

    private final BudgetMapper mapper;
    private final DataSource dataSource;

    public MybatisBudgetRepository(@NonNull BudgetMapper mapper,
                                    @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public @NonNull ReserveOutcome reserve(@NonNull String tenantId, @NonNull String runId,
                                            @Nullable String operationId, @NonNull BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            log.warn("预算预留金额非法，fail-closed: tenant={}, amount={}", tenantId, amount);
            return ReserveOutcome.FAIL_CLOSED;
        }
        return inTenantTx(tenantId, () -> {
            if (operationId != null) {
                Optional<ReservationRow> existing = mapper.findByOperation(tenantId, operationId);
                if (existing.isPresent()) return ReserveOutcome.REPLAYED;
            }
            Optional<BigDecimal> runCap = mapper.lockRunBudgetCap(tenantId, runId);
            if (runCap.isPresent()) {
                BigDecimal occupied = Optional.ofNullable(mapper.sumRunOccupied(tenantId, runId)).orElse(BigDecimal.ZERO);
                if (occupied.add(amount).compareTo(runCap.get()) > 0)
                    return ReserveOutcome.RUN_BUDGET_EXHAUSTED;
            }
            int rows = mapper.tryReserveAccount(tenantId, DEFAULT_CURRENCY, amount);
            if (rows == 0) {
                Optional<BudgetMapper.AccountRow> acct = mapper.findAccount(tenantId, DEFAULT_CURRENCY);
                if (acct.isEmpty()) { log.warn("租户未开通预算账户: tenant={}", tenantId); return ReserveOutcome.NO_ACCOUNT; }
                log.warn("预算额度不足: tenant={}", tenantId);
                return ReserveOutcome.INSUFFICIENT;
            }
            Optional<String> resvId = mapper.insertReservation(tenantId, runId, operationId, amount, DEFAULT_CURRENCY);
            if (resvId.isEmpty()) {
                if (operationId != null && mapper.findByOperation(tenantId, operationId).isPresent())
                    return ReserveOutcome.REPLAYED;
                return ReserveOutcome.FAIL_CLOSED;
            }
            return ReserveOutcome.RESERVED;
        });
    }

    @Override
    public boolean settle(@NonNull String tenantId, @NonNull String reservationId,
                           @NonNull BigDecimal actualAmount, @Nullable String modelId) {
        return settleInternal(tenantId, reservationId, actualAmount, modelId, 0L, 0L);
    }
    @Override
    public boolean settle(@NonNull String tenantId, @NonNull String reservationId,
                           @NonNull BigDecimal actualAmount, @Nullable String modelId,
                           long inputTokens, long outputTokens) {
        return settleInternal(tenantId, reservationId, actualAmount, modelId, inputTokens, outputTokens);
    }
    private boolean settleInternal(String tenantId, String reservationId, BigDecimal actualAmount,
                                    String modelId, long inputTokens, long outputTokens) {
        if (actualAmount.signum() < 0) return false;
        if (inputTokens < 0 || outputTokens < 0) return false;
        return inTenantTx(tenantId, () -> {
            Optional<ReservationRow> rOpt = mapper.lockReservation(tenantId, reservationId);
            if (rOpt.isEmpty()) return false;
            ReservationRow r = rOpt.get();
            if (!"RESERVED".equals(r.state())) return false;
            int rows = mapper.settleAccount(tenantId, DEFAULT_CURRENCY, r.reservedAmount(), actualAmount);
            if (rows == 0) throw new IllegalStateException("预算结算失败");
            mapper.updateReservationState(tenantId, reservationId, "SETTLED");
            if (modelId != null || inputTokens > 0 || outputTokens > 0) {
                mapper.insertUsage(tenantId, r.runId(), r.operationId(), reservationId,
                        modelId, inputTokens, outputTokens, actualAmount, DEFAULT_CURRENCY);
            }
            return true;
        });
    }

    @Override
    public boolean release(@NonNull String tenantId, @NonNull String reservationId, @NonNull String reason) {
        return inTenantTx(tenantId, () -> {
            Optional<ReservationRow> rOpt = mapper.lockReservation(tenantId, reservationId);
            if (rOpt.isEmpty()) return false;
            ReservationRow r = rOpt.get();
            if (!"RESERVED".equals(r.state())) return false;
            int rows = mapper.releaseAccount(tenantId, DEFAULT_CURRENCY, r.reservedAmount());
            if (rows == 0) throw new IllegalStateException("预算释放失败");
            mapper.updateReservationState(tenantId, reservationId, "RELEASED");
            return true;
        });
    }

    @Override
    public @NonNull Optional<Reservation> findByOperation(@NonNull String tenantId,
                                                           @NonNull String operationId) {
        return inTenantTx(tenantId, () -> mapper.findByOperation(tenantId, operationId)
                .map(r -> new Reservation(r.reservationId(), r.runId(), r.operationId(),
                        r.reservedAmount(), r.settledAmount(), r.state(), r.currency())));
    }

    @Override
    public @NonNull List<OrphanedReservation> findOrphaned(@NonNull String tenantId) {
        List<String> terminal = List.of("SUCCEEDED", "FAILED", "RECONCILED_SUCCEEDED",
                "RECONCILED_ABSENT", "MANUAL_DISPOSED");
        return inTenantTx(tenantId, () -> mapper.findReservationsByOpState(tenantId, terminal).stream()
                .map(r -> new OrphanedReservation(r.reservationId(), r.operationId(),
                        r.reservedAmount(), r.state(), r.createdAt())).toList());
    }

    @Override
    public @NonNull Optional<AccountSnapshot> accountSnapshot(@NonNull String tenantId,
                                                                @NonNull String currency) {
        return inTenantTx(tenantId, () -> mapper.findAccount(tenantId, currency)
                .map(a -> new AccountSnapshot(a.accountId(), currency,
                        a.totalLimit(), a.reservedAmount(), a.settledAmount())));
    }

    @Override
    public boolean isConsistent(@NonNull String tenantId, @NonNull String currency) {
        return inTenantTx(tenantId, () -> {
            BudgetMapper.ConsistencyRow c = mapper.accountConsistency(tenantId, currency);
            if (c == null || c.acctReserved() == null) return true;
            return c.detailReserved().compareTo(c.acctReserved()) == 0;
        });
    }

    // ── 事务 helper ──
    private <T> T inTenantTx(String tenantId, ThrowingSupplier<T> fn) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> { try { return fn.get(); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); } });
        } finally { TenantScope.clear(); }
    }
    @FunctionalInterface
    private interface ThrowingSupplier<T> { T get() throws Exception; }
}
