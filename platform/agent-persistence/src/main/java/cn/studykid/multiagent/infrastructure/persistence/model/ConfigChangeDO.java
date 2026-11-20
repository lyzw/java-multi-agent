package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

/**
 * 高危配置变更申请持久化对象（对应 {@code JdbcConfigChangeRepository}）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core / ports 的 record 混用；
 * 适配层 {@code MybatisConfigChangeRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构：{@code config_change_request}。
 * {@code change_id} 由 DB 默认 {@code gen_random_uuid()} 生成，写入用自定义
 * {@code INSERT ... RETURNING} 取回，故 {@code IdType.INPUT}。
 * {@code state} 为 PG 枚举 {@code config_change_state}，SQL 侧保留 {@code ::text} / {@code ::config_change_state} cast。
 */
@TableName("config_change_request")
public class ConfigChangeDO {

    @TableField("tenant_id")
    private String tenantId;

    @TableId(value = "change_id", type = IdType.INPUT)
    private String changeId;

    @TableField("config_key")
    private String configKey;

    @TableField("proposed_value")
    private String proposedValue;

    @TableField("value_type")
    private String valueType;

    @TableField("reason")
    private String reason;

    @TableField("requester_id")
    private String requesterId;

    @TableField("requested_at")
    private @Nullable Instant requestedAt;

    @TableField("state")
    private String state;

    @TableField("approver_id")
    private @Nullable String approverId;

    @TableField("decided_at")
    private @Nullable Instant decidedAt;

    @TableField("decision_note")
    private @Nullable String decisionNote;

    @TableField("applied_at")
    private @Nullable Instant appliedAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getChangeId() { return changeId; }
    public void setChangeId(String changeId) { this.changeId = changeId; }
    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getProposedValue() { return proposedValue; }
    public void setProposedValue(String proposedValue) { this.proposedValue = proposedValue; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequesterId() { return requesterId; }
    public void setRequesterId(String requesterId) { this.requesterId = requesterId; }
    public @Nullable Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(@Nullable Instant requestedAt) { this.requestedAt = requestedAt; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public @Nullable String getApproverId() { return approverId; }
    public void setApproverId(@Nullable String approverId) { this.approverId = approverId; }
    public @Nullable Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(@Nullable Instant decidedAt) { this.decidedAt = decidedAt; }
    public @Nullable String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(@Nullable String decisionNote) { this.decisionNote = decisionNote; }
    public @Nullable Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(@Nullable Instant appliedAt) { this.appliedAt = appliedAt; }
}
