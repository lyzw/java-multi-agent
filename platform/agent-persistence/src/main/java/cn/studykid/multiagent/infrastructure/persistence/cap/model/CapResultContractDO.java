package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * 输出契约定义行持久化对象（对应 {@code JdbcCapResultContractRepository}）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core 的 {@code CapResultContractDef} record 混用；
 * 适配层 {@code MybatisCapResultContractRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构来自 {@code cap_result_contract}（租户级 RLS 表）。逻辑主键是
 * {@code (tenant_id, contract_id, version)}：{@code contract_id} 标为 {@code @TableId(INPUT)}，
 * 写入走自定义 {@code INSERT ... ON CONFLICT DO NOTHING}，不调用 BaseMapper.insert。
 *
 * <p>⚠️ {@code contract_id} 是<b>可读字符串而非 UUID</b>（V23 修复）：
 * SQL 里只有 {@code tenant_id} 带 {@code ::uuid} cast，{@code contract_id} 绝不能加该 cast，
 * 否则非 UUID 形式的契约 id 会让发布事务回滚。
 *
 * <p>⚠️ {@code schema_json} 为 JSONB 列：复用 {@link JsonbTypeHandler} 按 String 读写；
 * SQL 侧保留显式 {@code ::jsonb} / {@code ::text} cast（与旧 JDBC 逐字一致）。
 */
@TableName("cap_result_contract")
public class CapResultContractDO {

    @TableId(value = "contract_id", type = IdType.INPUT)
    private String contractId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("version")
    private int version;

    @TableField(value = "schema_json", typeHandler = JsonbTypeHandler.class)
    private String schemaJson;

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

    public String getContractId() { return contractId; }
    public void setContractId(String contractId) { this.contractId = contractId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getSchemaJson() { return schemaJson; }
    public void setSchemaJson(String schemaJson) { this.schemaJson = schemaJson; }
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
