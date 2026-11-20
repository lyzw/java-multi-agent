package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import org.jspecify.annotations.Nullable;

/**
 * 生命周期状态读取投影（{@code ConfigLifecyclePort#stateOf} 的租户级分支）。
 *
 * <p>⚠️ 这不是表实体：租户级两张表（{@code cap_result_contract} / {@code cap_routing_policy}）
 * 的状态查询只取两列 —— {@code tenant_id::text}（供影子染色读行归属）与
 * {@code lifecycle_state}。故不建 {@code @TableName} DO，只用最小投影承载这两列，
 * 避免把「读状态」伪装成「读整行定义」。
 */
public class LifecycleStateRow {

    /** 结果行的真实租户归属（影子染色用；旧 JDBC 读的是 {@code rs.getString("tenant_id")}）。 */
    private @Nullable String tenantId;

    /** 生命周期状态原文（PG 枚举按 String 读，适配层再转 {@code ConfigLifecycleState}）。 */
    private @Nullable String lifecycleState;

    public @Nullable String getTenantId() { return tenantId; }
    public void setTenantId(@Nullable String tenantId) { this.tenantId = tenantId; }
    public @Nullable String getLifecycleState() { return lifecycleState; }
    public void setLifecycleState(@Nullable String lifecycleState) { this.lifecycleState = lifecycleState; }
}
