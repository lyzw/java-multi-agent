package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

/**
 * 当前发布指针持久化对象（W2 迁移，对应 {@code JdbcConfigPointerRepository}）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core 的 {@code ConfigPointer} record 混用；
 * 适配层 {@code MybatisConfigPointerRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构来自 {@code deploy/sql/platform/01_platform_ddl.sql}（{@code cap_config_pointer}）。
 * 主键为 {@code pointer_key text}（{@code PLATFORM} / {@code TENANT:<uuid>}），由适配层显式赋值。
 *
 * <p>⚠️ {@code tenant_id} 用 {@code String} 而非 {@code UUID}：旧 JDBC 实现以
 * {@code ps.setString(..., tenantId)} 绑定，SQL 侧用 {@code ?::uuid} 转换；
 * 读取侧 SQL 用 {@code tenant_id::text}，直接映射为 String，语义与旧实现逐字一致。
 * 当前 MyBatis 3.5.19 / MP 3.5.17 也没有内置 UUID TypeHandler。
 */
@TableName("cap_config_pointer")
public class ConfigPointerDO {

    @TableId(value = "pointer_key", type = IdType.INPUT)
    private String pointerKey;

    @TableField("scope")
    private String scope;

    /** PLATFORM 指针为 null；TENANT 指针为租户文本标识。 */
    @TableField("tenant_id")
    private @Nullable String tenantId;

    @TableField("current_release_id")
    private @Nullable Long currentReleaseId;

    @TableField("current_publish_seq")
    private @Nullable Long currentPublishSeq;

    @TableField("updated_at")
    private @Nullable Instant updatedAt;

    public String getPointerKey() { return pointerKey; }
    public void setPointerKey(String pointerKey) { this.pointerKey = pointerKey; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public @Nullable String getTenantId() { return tenantId; }
    public void setTenantId(@Nullable String tenantId) { this.tenantId = tenantId; }
    public @Nullable Long getCurrentReleaseId() { return currentReleaseId; }
    public void setCurrentReleaseId(@Nullable Long currentReleaseId) { this.currentReleaseId = currentReleaseId; }
    public @Nullable Long getCurrentPublishSeq() { return currentPublishSeq; }
    public void setCurrentPublishSeq(@Nullable Long currentPublishSeq) { this.currentPublishSeq = currentPublishSeq; }
    public @Nullable Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(@Nullable Instant updatedAt) { this.updatedAt = updatedAt; }
}
