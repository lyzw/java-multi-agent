package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

/**
 * Tool Operation Ledger Mapper（对应 {@code JdbcToolOperationLedger} 的 9 条 SQL）。
 *
 * <p>⚠️ PG 枚举 cast（{@code ::tool_risk_level} / {@code ::tool_operation_state}）、
 * JSONB cast（{@code ::jsonb}）、RETURNING 全部原样保留。
 */
public interface ToolOperationLedgerMapper {

    /** 0. INSERT ... RETURNING operation_id::text */
    String inFlight(@Param("tenantId") String tenantId,
                    @Param("runId") String runId,
                    @Param("toolName") String toolName,
                    @Param("riskLevel") String riskLevel,
                    @Param("idempotencyKey") String idempotencyKey,
                    @Param("requestDigest") String requestDigest,
                    @Param("toolArguments") String toolArguments,
                    @Param("fencingToken") String fencingToken);

    /** 1. 状态迁移（任意目标态） */
    int reconcile(@Param("tenantId") String tenantId,
                  @Param("operationId") String operationId,
                  @Param("newState") String newState,
                  @Param("source") String source);

    /** 2. 人工处置（MANUAL_DISPOSED） */
    int manualDispose(@Param("tenantId") String tenantId,
                      @Param("operationId") String operationId,
                      @Param("source") String source,
                      @Param("actorId") String actorId);

    /** 3. 对账：列出所有 UNKNOWN 操作（ORDER BY created_at） */
    List<ToolOperationSummaryRow> listUnknown(@Param("tenantId") String tenantId);

    /** 4. 幂等键查重（只返回 request_digest） */
    Optional<String> findDigestByIdempotencyKey(@Param("tenantId") String tenantId,
                                                @Param("idempotencyKey") String idempotencyKey);

    /** 5. 按 operation_id 查详情（带 attempt_count 子查询） */
    Optional<ToolOperationDetailRow> findById(@Param("tenantId") String tenantId,
                                              @Param("operationId") String operationId);

    /** 6. 按 operation_id 查原始 tool 调用参数（tool_name / tool_arguments / idempotency_key） */
    Optional<ToolOperationArgsRow> findArgsById(@Param("tenantId") String tenantId,
                                                @Param("operationId") String operationId);

    /** 7. 按 idempotency_key 查详情（带 attempt_count 子查询） */
    Optional<ToolOperationDetailRow> findByIdempotencyKey(@Param("tenantId") String tenantId,
                                                          @Param("idempotencyKey") String idempotencyKey);

    /** 8. 写入 attempt 行（对账） */
    int recordAttempt(@Param("tenantId") String tenantId,
                      @Param("operationId") String operationId,
                      @Param("attemptNumber") int attemptNumber,
                      @Param("state") String state,
                      @Param("fencingToken") String fencingToken,
                      @Param("errorClass") String errorClass,
                      @Param("errorMessage") String errorMessage,
                      @Param("resultUnknown") boolean resultUnknown);

    /** ── 行值映射辅助 POJO（包内可见，只承接 XML resultType） ── */
    class ToolOperationSummaryRow {
        private String tenantId, operationId, runId, toolName, idempotencyKey;
        private java.time.Instant createdAt;
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public String getOperationId() { return operationId; }
        public void setOperationId(String v) { this.operationId = v; }
        public String getRunId() { return runId; }
        public void setRunId(String v) { this.runId = v; }
        public String getToolName() { return toolName; }
        public void setToolName(String v) { this.toolName = v; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
        public java.time.Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(java.time.Instant v) { this.createdAt = v; }
    }

    class ToolOperationDetailRow {
        private String tenantId, operationId, state, fencingToken;
        private int attemptCount;
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public String getOperationId() { return operationId; }
        public void setOperationId(String v) { this.operationId = v; }
        public String getState() { return state; }
        public void setState(String v) { this.state = v; }
        public String getFencingToken() { return fencingToken; }
        public void setFencingToken(String v) { this.fencingToken = v; }
        public int getAttemptCount() { return attemptCount; }
        public void setAttemptCount(int v) { this.attemptCount = v; }
    }

    class ToolOperationArgsRow {
        private String tenantId, toolName, toolArguments, idempotencyKey;
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public String getToolName() { return toolName; }
        public void setToolName(String v) { this.toolName = v; }
        public String getToolArguments() { return toolArguments; }
        public void setToolArguments(String v) { this.toolArguments = v; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
    }
}
