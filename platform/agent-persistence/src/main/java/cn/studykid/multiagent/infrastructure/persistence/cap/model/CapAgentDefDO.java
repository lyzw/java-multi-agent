package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * Agent 结构化定义版本行持久化对象（对应 {@code JdbcCapAgentDefRepository}）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core 的 {@code CapAgentDef} record 混用；
 * 适配层 {@code MybatisCapAgentDefRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构来自 {@code cap_agent_def}（全局实体：无 tenant_id 列，租户可见性由
 * {@code tenant_agent_binding} 决定）。逻辑主键是 {@code (agent_id, version)}：
 * {@code agent_id} 标为 {@code @TableId(INPUT)}，{@code version} 作为普通列；
 * 写入走自定义 {@code INSERT ... ON CONFLICT DO NOTHING}，不调用 BaseMapper.insert。
 *
 * <p>⚠️ {@code roles} / {@code enabled_skills} / {@code skill_bundles} /
 * {@code capability_tags} / {@code overrides} / {@code orchestration} /
 * {@code instructions} / {@code generation_params} 均为 JSONB 列：复用
 * {@link JsonbTypeHandler} 按 String 读写；SQL 侧保留显式 {@code ::jsonb} / {@code ::text} cast
 * （与旧 JDBC 逐字一致）。JSON 数组 ↔ {@code List<String>} 的序列化/反序列化在适配层完成，
 * 且解析失败一律 fail-closed（与旧实现同口径）。
 */
@TableName("cap_agent_def")
public class CapAgentDefDO {

    @TableId(value = "agent_id", type = IdType.INPUT)
    private String agentId;

    @TableField("version")
    private int version;

    @TableField("name")
    private @Nullable String name;

    @TableField("description")
    private @Nullable String description;

    @TableField(value = "roles", typeHandler = JsonbTypeHandler.class)
    private String roles;

    @TableField(value = "enabled_skills", typeHandler = JsonbTypeHandler.class)
    private String enabledSkills;

    @TableField(value = "skill_bundles", typeHandler = JsonbTypeHandler.class)
    private String skillBundles;

    @TableField(value = "capability_tags", typeHandler = JsonbTypeHandler.class)
    private String capabilityTags;

    @TableField(value = "overrides", typeHandler = JsonbTypeHandler.class)
    private String overrides;

    @TableField(value = "orchestration", typeHandler = JsonbTypeHandler.class)
    private String orchestration;

    @TableField(value = "instructions", typeHandler = JsonbTypeHandler.class)
    private String instructions;

    @TableField("model_ref")
    private @Nullable String modelRef;

    @TableField(value = "generation_params", typeHandler = JsonbTypeHandler.class)
    private String generationParams;

    @TableField("config_fingerprint")
    private String configFingerprint;

    @TableField("schema_version")
    private int schemaVersion;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("published_at")
    private @Nullable Instant publishedAt;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public @Nullable String getName() { return name; }
    public void setName(@Nullable String name) { this.name = name; }
    public @Nullable String getDescription() { return description; }
    public void setDescription(@Nullable String description) { this.description = description; }
    public String getRoles() { return roles; }
    public void setRoles(String roles) { this.roles = roles; }
    public String getEnabledSkills() { return enabledSkills; }
    public void setEnabledSkills(String enabledSkills) { this.enabledSkills = enabledSkills; }
    public String getSkillBundles() { return skillBundles; }
    public void setSkillBundles(String skillBundles) { this.skillBundles = skillBundles; }
    public String getCapabilityTags() { return capabilityTags; }
    public void setCapabilityTags(String capabilityTags) { this.capabilityTags = capabilityTags; }
    public String getOverrides() { return overrides; }
    public void setOverrides(String overrides) { this.overrides = overrides; }
    public String getOrchestration() { return orchestration; }
    public void setOrchestration(String orchestration) { this.orchestration = orchestration; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public @Nullable String getModelRef() { return modelRef; }
    public void setModelRef(@Nullable String modelRef) { this.modelRef = modelRef; }
    public String getGenerationParams() { return generationParams; }
    public void setGenerationParams(String generationParams) { this.generationParams = generationParams; }
    public String getConfigFingerprint() { return configFingerprint; }
    public void setConfigFingerprint(String configFingerprint) { this.configFingerprint = configFingerprint; }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public @Nullable Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(@Nullable Instant publishedAt) { this.publishedAt = publishedAt; }
}
