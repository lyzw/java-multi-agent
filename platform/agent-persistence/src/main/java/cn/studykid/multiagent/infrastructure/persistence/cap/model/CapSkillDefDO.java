package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * Skill 定义版本行持久化对象（对应 {@code JdbcCapSkillDefRepository}）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core 的 {@code CapSkillDef} record 混用；
 * 适配层 {@code MybatisCapSkillDefRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构来自 {@code cap_skill_def}（platform 级对象，无 RLS、无 tenant_id 列）。
 * 逻辑主键是 {@code (id, version)}：{@code id} 标为 {@code @TableId(INPUT)}，
 * {@code version} 作为普通列；写入走自定义 {@code INSERT ... ON CONFLICT DO NOTHING}，
 * 不调用 BaseMapper.insert。
 *
 * <p>⚠️ JSONB 列（usage / declared_tools / declared_mcp / param_policies / required_caps）
 * 复用 {@link JsonbTypeHandler} 按 String 读写；SQL 侧保留显式 {@code ::jsonb} / {@code ::text} cast
 * （与旧 JDBC 逐字一致）。{@code content} 按 text 列处理（{@code ::text} cast）。
 */
@TableName("cap_skill_def")
public class CapSkillDefDO {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("version")
    private int version;

    @TableField("name")
    private @Nullable String name;

    /** 遗留单级描述列：新入库一律 NULL，三级语义只走 purpose / usage / content。 */
    @TableField("description")
    private @Nullable String description;

    @TableField("purpose")
    private @Nullable String purpose;

    @TableField(value = "usage", typeHandler = JsonbTypeHandler.class)
    private @Nullable String usage;

    @TableField("content")
    private @Nullable String content;

    @TableField(value = "declared_tools", typeHandler = JsonbTypeHandler.class)
    private String declaredTools;

    @TableField(value = "declared_mcp", typeHandler = JsonbTypeHandler.class)
    private String declaredMcp;

    @TableField("risk_ceiling")
    private String riskCeiling;

    @TableField(value = "param_policies", typeHandler = JsonbTypeHandler.class)
    private String paramPolicies;

    @TableField(value = "required_caps", typeHandler = JsonbTypeHandler.class)
    private String requiredCaps;

    @TableField("config_fingerprint")
    private String configFingerprint;

    @TableField("schema_version")
    private int schemaVersion;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("published_at")
    private @Nullable Instant publishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public @Nullable String getName() { return name; }
    public void setName(@Nullable String name) { this.name = name; }
    public @Nullable String getDescription() { return description; }
    public void setDescription(@Nullable String description) { this.description = description; }
    public @Nullable String getPurpose() { return purpose; }
    public void setPurpose(@Nullable String purpose) { this.purpose = purpose; }
    public @Nullable String getUsage() { return usage; }
    public void setUsage(@Nullable String usage) { this.usage = usage; }
    public @Nullable String getContent() { return content; }
    public void setContent(@Nullable String content) { this.content = content; }
    public String getDeclaredTools() { return declaredTools; }
    public void setDeclaredTools(String declaredTools) { this.declaredTools = declaredTools; }
    public String getDeclaredMcp() { return declaredMcp; }
    public void setDeclaredMcp(String declaredMcp) { this.declaredMcp = declaredMcp; }
    public String getRiskCeiling() { return riskCeiling; }
    public void setRiskCeiling(String riskCeiling) { this.riskCeiling = riskCeiling; }
    public String getParamPolicies() { return paramPolicies; }
    public void setParamPolicies(String paramPolicies) { this.paramPolicies = paramPolicies; }
    public String getRequiredCaps() { return requiredCaps; }
    public void setRequiredCaps(String requiredCaps) { this.requiredCaps = requiredCaps; }
    public String getConfigFingerprint() { return configFingerprint; }
    public void setConfigFingerprint(String configFingerprint) { this.configFingerprint = configFingerprint; }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public @Nullable Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(@Nullable Instant publishedAt) { this.publishedAt = publishedAt; }
}
