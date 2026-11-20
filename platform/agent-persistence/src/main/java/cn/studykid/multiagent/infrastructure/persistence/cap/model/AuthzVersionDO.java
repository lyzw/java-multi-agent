package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

/**
 * 授权版本持久化对象（W2 迁移，对应 {@code cap_authz_version} 表）。
 *
 * <p>⚠️ 这是 persistence 模块<b>内部</b>的 DO，不与 core 的 {@code AuthzVersion} record 混用。
 * 适配层 {@code MybatisAuthzVersionRepository} 负责 DO ↔ record 的转换。
 *
 * <p>⚠️ 本表的业务主键是复合键 {@code (tenant_id, principal_id)}（DDL 的唯一约束），
 * 递增 SQL 由 {@code ON CONFLICT (tenant_id, principal_id)} 仲裁；DO 只承载持久化字段，
 * 通用 CRUD 不参与本仓储的写入路径。
 */
@TableName("cap_authz_version")
public class AuthzVersionDO {

    @TableField("tenant_id")
    private String tenantId;

    @TableField("principal_id")
    private String principalId;

    @TableField("authz_version")
    private long authzVersion;

    @TableField("version")
    private int version;

    @TableField("updated_at")
    private @Nullable Instant updatedAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPrincipalId() { return principalId; }
    public void setPrincipalId(String principalId) { this.principalId = principalId; }
    public long getAuthzVersion() { return authzVersion; }
    public void setAuthzVersion(long authzVersion) { this.authzVersion = authzVersion; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public @Nullable Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(@Nullable Instant updatedAt) { this.updatedAt = updatedAt; }
}
