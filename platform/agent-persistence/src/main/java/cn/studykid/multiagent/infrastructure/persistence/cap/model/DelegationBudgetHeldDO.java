package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.math.BigDecimal;
import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * 委派侧「持有中预留」读取投影（{@code DelegationBudgetPort} 的反查行）。
 *
 * <p>⚠️ 这不是表实体：一条结果行来自
 * {@code budget_reservation LEFT JOIN agent_invocation} 两张表，
 * 故不建 {@code @TableName} DO，只用最小投影承载 Port 需要的列。
 *
 * <p>⚠️ {@code joinTenantId} 是 LEFT JOIN 从表的真实租户列
 * （SQL 里别名为 {@code join_tenant_id}），仅供影子态逐行染色使用；
 * 无匹配时为 {@code null}（旧实现同样跳过染色，不误判）。
 *
 * <p>⚠️ {@code status} 是 PG 枚举 {@code agent_invocation_status} 的原文，
 * 按 String 读、由适配层转 {@code AgentInvocationStatus}；
 * 无锚点（LEFT JOIN 无匹配）时为 {@code null}。
 */
public class DelegationBudgetHeldDO {

    /** {@code r.reservation_id::text}。 */
    private @Nullable String reservationId;

    /** {@code r.operation_id::text}（委派侧即委派调用标识）。 */
    private @Nullable String operationId;

    /** {@code r.reserved_amount}（numeric(18,6)）。 */
    private @Nullable BigDecimal reservedAmount;

    /** {@code r.tenant_id::text}：结果行的真实租户（影子染色用）。 */
    private @Nullable String tenantId;

    /** {@code r.created_at}（timestamptz）。 */
    private @Nullable Instant createdAt;

    /** {@code i.status}：委派调用状态原文；无锚点时 null。 */
    private @Nullable String status;

    /** {@code i.run_id::text}：所属 Run；无锚点时 null。 */
    private @Nullable String runId;

    /** {@code i.tenant_id::text AS join_tenant_id}：从表行归属；无匹配时 null。 */
    private @Nullable String joinTenantId;

    public @Nullable String getReservationId() { return reservationId; }
    public void setReservationId(@Nullable String reservationId) {
        this.reservationId = reservationId;
    }
    public @Nullable String getOperationId() { return operationId; }
    public void setOperationId(@Nullable String operationId) { this.operationId = operationId; }
    public @Nullable BigDecimal getReservedAmount() { return reservedAmount; }
    public void setReservedAmount(@Nullable BigDecimal reservedAmount) {
        this.reservedAmount = reservedAmount;
    }
    public @Nullable String getTenantId() { return tenantId; }
    public void setTenantId(@Nullable String tenantId) { this.tenantId = tenantId; }
    public @Nullable Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(@Nullable Instant createdAt) { this.createdAt = createdAt; }
    public @Nullable String getStatus() { return status; }
    public void setStatus(@Nullable String status) { this.status = status; }
    public @Nullable String getRunId() { return runId; }
    public void setRunId(@Nullable String runId) { this.runId = runId; }
    public @Nullable String getJoinTenantId() { return joinTenantId; }
    public void setJoinTenantId(@Nullable String joinTenantId) { this.joinTenantId = joinTenantId; }
}
