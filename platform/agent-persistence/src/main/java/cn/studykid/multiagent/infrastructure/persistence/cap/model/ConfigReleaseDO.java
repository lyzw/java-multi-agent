package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * 配置发布头持久化对象（W2 迁移，对应 {@code JdbcConfigReleaseRepository}）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core 的 {@code ConfigRelease} record 混用；
 * 适配层 {@code MybatisConfigReleaseRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构来自 {@code deploy/sql/platform/01_platform_ddl.sql}（{@code cap_config_release}）。
 * {@code release_id} 与 {@code config_publish_seq} 均由数据库分配（后者取
 * {@code nextval('cap_config_publish_seq')}），故 {@code IdType.INPUT} + 适配层不回填主键，
 * 写入语句用自定义 {@code INSERT ... RETURNING} 取回。
 *
 * <p>⚠️ {@code object_versions} 为 JSONB 列：复用 {@link JsonbTypeHandler} 按 String 读写；
 * SQL 侧保留显式 {@code ::jsonb} / {@code ::text} cast（与旧 JDBC 逐字一致）。
 * {@code status} 是 PG 枚举 {@code cap_release_status}，按 String 映射。
 */
@TableName("cap_config_release")
public class ConfigReleaseDO {

    @TableId(value = "release_id", type = IdType.INPUT)
    private long releaseId;

    @TableField("config_publish_seq")
    private long configPublishSeq;

    @TableField("scope")
    private String scope;

    @TableField("tenant_id")
    private @Nullable String tenantId;

    @TableField(value = "object_versions", typeHandler = JsonbTypeHandler.class)
    private String objectVersions;

    @TableField("aggregate_fingerprint")
    private @Nullable String aggregateFingerprint;

    @TableField("status")
    private String status;

    @TableField("requester_id")
    private @Nullable String requesterId;

    @TableField("approver_id")
    private @Nullable String approverId;

    @TableField("effective_at")
    private @Nullable Instant effectiveAt;

    @TableField("created_at")
    private @Nullable Instant createdAt;

    public long getReleaseId() { return releaseId; }
    public void setReleaseId(long releaseId) { this.releaseId = releaseId; }
    public long getConfigPublishSeq() { return configPublishSeq; }
    public void setConfigPublishSeq(long configPublishSeq) { this.configPublishSeq = configPublishSeq; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public @Nullable String getTenantId() { return tenantId; }
    public void setTenantId(@Nullable String tenantId) { this.tenantId = tenantId; }
    public String getObjectVersions() { return objectVersions; }
    public void setObjectVersions(String objectVersions) { this.objectVersions = objectVersions; }
    public @Nullable String getAggregateFingerprint() { return aggregateFingerprint; }
    public void setAggregateFingerprint(@Nullable String aggregateFingerprint) { this.aggregateFingerprint = aggregateFingerprint; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public @Nullable String getRequesterId() { return requesterId; }
    public void setRequesterId(@Nullable String requesterId) { this.requesterId = requesterId; }
    public @Nullable String getApproverId() { return approverId; }
    public void setApproverId(@Nullable String approverId) { this.approverId = approverId; }
    public @Nullable Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(@Nullable Instant effectiveAt) { this.effectiveAt = effectiveAt; }
    public @Nullable Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(@Nullable Instant createdAt) { this.createdAt = createdAt; }
}
