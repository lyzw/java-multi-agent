package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * Tool 定义版本行持久化对象（对应 {@code JdbcCapToolDefRepository}）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core 的 {@code CapToolDef} record 混用；
 * 适配层 {@code MybatisCapToolDefRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构来自 {@code cap_tool_def}（platform 级对象，无 RLS、无 tenant_id 列）。
 * 逻辑主键是 {@code (id, version)}：{@code id} 标为 {@code @TableId(INPUT)}，
 * {@code version} 作为普通列；写入走自定义 {@code INSERT ... ON CONFLICT DO NOTHING}，
 * 不调用 BaseMapper.insert。
 *
 * <p>⚠️ {@code input_schema} / {@code param_policies} 为 JSONB 列：复用
 * {@link JsonbTypeHandler} 按 String 读写；SQL 侧保留显式 {@code ::jsonb} / {@code ::text} cast
 * （与旧 JDBC 逐字一致）。
 */
@TableName("cap_tool_def")
public class CapToolDefDO {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("version")
    private int version;

    @TableField("name")
    private @Nullable String name;

    @TableField("kind")
    private String kind;

    @TableField("risk_level")
    private String riskLevel;

    @TableField("mcp_server_id")
    private @Nullable String mcpServerId;

    @TableField("mcp_tool_name")
    private @Nullable String mcpToolName;

    @TableField("http_method")
    private @Nullable String httpMethod;

    @TableField("http_url")
    private @Nullable String httpUrl;

    @TableField("http_timeout_ms")
    private @Nullable Integer httpTimeoutMs;

    @TableField(value = "input_schema", typeHandler = JsonbTypeHandler.class)
    private String inputSchema;

    @TableField(value = "param_policies", typeHandler = JsonbTypeHandler.class)
    private String paramPolicies;

    @TableField("description")
    private @Nullable String description;

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
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public @Nullable String getMcpServerId() { return mcpServerId; }
    public void setMcpServerId(@Nullable String mcpServerId) { this.mcpServerId = mcpServerId; }
    public @Nullable String getMcpToolName() { return mcpToolName; }
    public void setMcpToolName(@Nullable String mcpToolName) { this.mcpToolName = mcpToolName; }
    public @Nullable String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(@Nullable String httpMethod) { this.httpMethod = httpMethod; }
    public @Nullable String getHttpUrl() { return httpUrl; }
    public void setHttpUrl(@Nullable String httpUrl) { this.httpUrl = httpUrl; }
    public @Nullable Integer getHttpTimeoutMs() { return httpTimeoutMs; }
    public void setHttpTimeoutMs(@Nullable Integer httpTimeoutMs) { this.httpTimeoutMs = httpTimeoutMs; }
    public String getInputSchema() { return inputSchema; }
    public void setInputSchema(String inputSchema) { this.inputSchema = inputSchema; }
    public String getParamPolicies() { return paramPolicies; }
    public void setParamPolicies(String paramPolicies) { this.paramPolicies = paramPolicies; }
    public @Nullable String getDescription() { return description; }
    public void setDescription(@Nullable String description) { this.description = description; }
    public String getConfigFingerprint() { return configFingerprint; }
    public void setConfigFingerprint(String configFingerprint) { this.configFingerprint = configFingerprint; }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public @Nullable Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(@Nullable Instant publishedAt) { this.publishedAt = publishedAt; }
}
