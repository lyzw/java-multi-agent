package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

/** Budget 账户/预留/结算（12 SQL，含 FOR UPDATE + ON CONFLICT + 条件 UPDATE）。 */
public interface BudgetMapper {

    /** SQL[0] 条件 UPDATE reserved_amount ≤ total_limit */
    int tryReserveAccount(@Param("tenantId") String tenantId,
                          @Param("currency") String currency,
                          @Param("amount") BigDecimal amount);

    /** SQL[1] INSERT budget_reservation ON CONFLICT DO NOTHING RETURNING */
    Optional<String> insertReservation(@Param("tenantId") String tenantId,
                                        @Param("runId") String runId,
                                        @Param("operationId") String operationId,
                                        @Param("amount") BigDecimal amount,
                                        @Param("currency") String currency);

    /** SQL[2] INSERT usage_record RETURNING (有 reservation_id) */
    Optional<String> insertUsage(@Param("tenantId") String tenantId,
                                  @Param("runId") String runId,
                                  @Param("operationId") String operationId,
                                  @Param("reservationId") String reservationId,
                                  @Param("modelId") String modelId,
                                  @Param("inputTokens") long inputTokens,
                                  @Param("outputTokens") long outputTokens,
                                  @Param("costAmount") BigDecimal costAmount,
                                  @Param("currency") String currency);

    /** SQL[3] INSERT usage_record (reservation_id 为 NULL —— 无预留的工具调用) */
    Optional<String> insertUsageNoReservation(@Param("tenantId") String tenantId,
                                                @Param("runId") String runId,
                                                @Param("operationId") String operationId,
                                                @Param("modelId") String modelId,
                                                @Param("inputTokens") long inputTokens,
                                                @Param("outputTokens") long outputTokens,
                                                @Param("costAmount") BigDecimal costAmount,
                                                @Param("currency") String currency);

    /** SQL[4] 结算: reserved -= amount, settled += actual */
    int settleAccount(@Param("tenantId") String tenantId,
                       @Param("currency") String currency,
                       @Param("amount") BigDecimal reservedDecrement,
                       @Param("actualAmount") BigDecimal settledIncrement);

    /** SQL[5] 释放: reserved -= amount */
    int releaseAccount(@Param("tenantId") String tenantId,
                        @Param("currency") String currency,
                        @Param("amount") BigDecimal amount);

    /** SQL[6][7] 查 orphaned 预留（按 tool_operation 状态） */
    List<ReservationRow> findReservationsByOpState(@Param("tenantId") String tenantId,
                                                     @Param("states") List<String> states);

    /** SQL[8] 查账户余额 */
    Optional<AccountRow> findAccount(@Param("tenantId") String tenantId,
                                     @Param("currency") String currency);

    /** SQL[9] 四字段一致性快照（子查询聚合） */
    ConsistencyRow accountConsistency(@Param("tenantId") String tenantId,
                                       @Param("currency") String currency);

    /** SQL[10] 按 operationId 查 reservation */
    Optional<ReservationRow> findByOperation(@Param("tenantId") String tenantId,
                                               @Param("operationId") String operationId);

    /** SQL[11] FOR UPDATE 锁 reservation */
    Optional<ReservationRow> lockReservation(@Param("tenantId") String tenantId,
                                              @Param("reservationId") String reservationId);

    /** lockRunBudgetCap — FOR UPDATE 锁 run 行读 budget_amount */
    Optional<BigDecimal> lockRunBudgetCap(@Param("tenantId") String tenantId,
                                           @Param("runId") String runId);

    /** 结算 reservation（对齐 JDBC markSettled：settled_amount + settled_at；budget_reservation 无 updated_at 列） */
    int settleReservation(@Param("tenantId") String tenantId,
                          @Param("reservationId") String reservationId,
                          @Param("actualAmount") BigDecimal actualAmount);

    /** 释放 reservation（对齐 JDBC release：release_reason） */
    int releaseReservation(@Param("tenantId") String tenantId,
                           @Param("reservationId") String reservationId,
                           @Param("reason") String reason);

    /** Run 级聚合: SUM(reserved_amount - settled_amount) */
    BigDecimal sumRunOccupied(@Param("tenantId") String tenantId,
                              @Param("runId") String runId);

    /** 查 reservation（按 reservationId 无 FOR UPDATE —— 用于 accountSnapshot） */
    List<ReservationRow> findReservations(@Param("tenantId") String tenantId);

    // ── POJO ──
    record ReservationRow(String reservationId, String runId, String operationId,
                          BigDecimal reservedAmount, BigDecimal settledAmount, String state,
                          String currency, java.time.Instant createdAt) {}
    record AccountRow(String accountId, String currency, BigDecimal totalLimit,
                      BigDecimal reservedAmount, BigDecimal settledAmount) {}
    record ConsistencyRow(BigDecimal acctReserved, BigDecimal detailReserved,
                          BigDecimal acctSettled, BigDecimal detailSettled) {}
}
