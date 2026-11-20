package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * MCP Server 定义版本行持久化对象（对应 {@code JdbcCapMcpServerDefRepository}）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core 的 {@code CapMcpServerDef} record 混用；
 * 适配层 {@code MybatisCapMcpServerDefRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构来自 {@code cap_mcp_server_def}（platform 级对象，无 RLS、无 tenant_id 列）。
 * 逻辑主键是 {@code (id, version)}：{@code id} 标为 {@code @TableId(INPUT)}，
 * {@code version} 作为普通列；写入走自定义 {@code INSERT ... ON CONFLICT DO NOTHING}，
 * 不调用 BaseMapper.insert。
 *
 * <p>⚠️ {@code exposed_tool_allowlist} 为 JSONB 列：复用 {@link JsonbTypeHandler} 按 String 读写；
 * SQL 侧保留显式 {@code ::jsonb} / {@code ::text} cast（与旧 JDBC 逐字一致）。
 * 该列可空且无默认值：{@code null}（未配置）与 JSON 空数组 {@code []}（显式空清单）必须区分（§4.4）；
 * 用可空 String 承载即可天然区分 SQL NULL 与 {@code "[]"}。
 *
 * <p>⚠️ {@code credential_ref} 只是凭据库定位符，凭据材料永不入库。
 */
@TableName("cap_mcp_server_def")
public class CapMcpServerDefDO {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("version")
    private int version;

    @TableField("transport")
    private String transport;

    @TableField("endpoint")
    private @Nullable String endpoint;

    @TableField("command")
    private @Nullable String command;

    @TableField("auth_kind")
    private String authKind;

    @TableField("trust_tier")
    private String trustTier;

    @TableField(value = "exposed_tool_allowlist", typeHandler = JsonbTypeHandler.class)
    private @Nullable String exposedToolAllowlist;

    @TableField("connection_risk")
    private String connectionRisk;

    @TableField("dynamic_discovery")
    private boolean dynamicDiscovery;

    @TableField("config_fingerprint")
    private String configFingerprint;

    @TableField("credential_ref")
    private @Nullable String credentialRef;

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
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public @Nullable String getEndpoint() { return endpoint; }
    public void setEndpoint(@Nullable String endpoint) { this.endpoint = endpoint; }
    public @Nullable String getCommand() { return command; }
    public void setCommand(@Nullable String command) { this.command = command; }
    public String getAuthKind() { return authKind; }
    public void setAuthKind(String authKind) { this.authKind = authKind; }
    public String getTrustTier() { return trustTier; }
    public void setTrustTier(String trustTier) { this.trustTier = trustTier; }
    public @Nullable String getExposedToolAllowlist() { return exposedToolAllowlist; }
    public void setExposedToolAllowlist(@Nullable String exposedToolAllowlist) { this.exposedToolAllowlist = exposedToolAllowlist; }
    public String getConnectionRisk() { return connectionRisk; }
    public void setConnectionRisk(String connectionRisk) { this.connectionRisk = connectionRisk; }
    public boolean isDynamicDiscovery() { return dynamicDiscovery; }
    public void setDynamicDiscovery(boolean dynamicDiscovery) { this.dynamicDiscovery = dynamicDiscovery; }
    public String getConfigFingerprint() { return configFingerprint; }
    public void setConfigFingerprint(String configFingerprint) { this.configFingerprint = configFingerprint; }
    public @Nullable String getCredentialRef() { return credentialRef; }
    public void setCredentialRef(@Nullable String credentialRef) { this.credentialRef = credentialRef; }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public @Nullable Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(@Nullable Instant publishedAt) { this.publishedAt = publishedAt; }
}
