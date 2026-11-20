package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * 路由策略定义行持久化对象（对应 {@code JdbcCapRoutingPolicyRepository}）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core 的 {@code CapRoutingPolicyDef} record 混用；
 * 适配层 {@code MybatisCapRoutingPolicyRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构来自 {@code cap_routing_policy}（租户级 RLS 表）。逻辑主键是
 * {@code (tenant_id, policy_id, version)}：{@code policy_id} 标为 {@code @TableId(INPUT)}，
 * 写入走自定义 {@code INSERT ... ON CONFLICT DO NOTHING}，不调用 BaseMapper.insert。
 *
 * <p>⚠️ {@code policy_id} 是<b>可读字符串而非 UUID</b>：SQL 里只有 {@code tenant_id} 带
 * {@code ::uuid} cast（V23 教训：非 UUID id 会让整笔发布事务回滚）。
 *
 * <p>⚠️ {@code input_schema} / {@code evaluation} / {@code sample_set} 为 JSONB 列：复用
 * {@link JsonbTypeHandler} 按 String 读写；SQL 侧保留显式 {@code ::jsonb} / {@code ::text} cast
 * （与旧 JDBC 逐字一致）。读回的是 PG 规范化后的文本，数组顺序保留 —— 那是路由语义本身。
 */
@TableName("cap_routing_policy")
public class CapRoutingPolicyDO {

    @TableId(value = "policy_id", type = IdType.INPUT)
    private String policyId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("version")
    private int version;

    @TableField("mode")
    private String mode;

    @TableField(value = "input_schema", typeHandler = JsonbTypeHandler.class)
    private String inputSchema;

    @TableField(value = "evaluation", typeHandler = JsonbTypeHandler.class)
    private String evaluation;

    @TableField("default_target")
    private @Nullable String defaultTarget;

    @TableField("no_match_action")
    private String noMatchAction;

    @TableField("on_conflict")
    private String onConflict;

    @TableField(value = "sample_set", typeHandler = JsonbTypeHandler.class)
    private String sampleSet;

    @TableField("config_fingerprint")
    private String configFingerprint;

    @TableField("schema_version")
    private int schemaVersion;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("published_at")
    private @Nullable Instant publishedAt;

    /** 生命周期状态（§6.6），PG 枚举按 String 映射；{@code null} = 未记录（≠ 未停用）。 */
    @TableField("lifecycle_state")
    private @Nullable String lifecycleState;

    public String getPolicyId() { return policyId; }
    public void setPolicyId(String policyId) { this.policyId = policyId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getInputSchema() { return inputSchema; }
    public void setInputSchema(String inputSchema) { this.inputSchema = inputSchema; }
    public String getEvaluation() { return evaluation; }
    public void setEvaluation(String evaluation) { this.evaluation = evaluation; }
    public @Nullable String getDefaultTarget() { return defaultTarget; }
    public void setDefaultTarget(@Nullable String defaultTarget) { this.defaultTarget = defaultTarget; }
    public String getNoMatchAction() { return noMatchAction; }
    public void setNoMatchAction(String noMatchAction) { this.noMatchAction = noMatchAction; }
    public String getOnConflict() { return onConflict; }
    public void setOnConflict(String onConflict) { this.onConflict = onConflict; }
    public String getSampleSet() { return sampleSet; }
    public void setSampleSet(String sampleSet) { this.sampleSet = sampleSet; }
    public String getConfigFingerprint() { return configFingerprint; }
    public void setConfigFingerprint(String configFingerprint) { this.configFingerprint = configFingerprint; }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public @Nullable Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(@Nullable Instant publishedAt) { this.publishedAt = publishedAt; }
    public @Nullable String getLifecycleState() { return lifecycleState; }
    public void setLifecycleState(@Nullable String lifecycleState) { this.lifecycleState = lifecycleState; }
}
