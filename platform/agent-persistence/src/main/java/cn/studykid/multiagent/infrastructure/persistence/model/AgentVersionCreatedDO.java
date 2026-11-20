package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

/**
 * {@code agent_version} 发布回读投影
 * （对应 {@code INSERT INTO agent_version ... RETURNING ...}）。
 *
 * <p>⚠️ 只承载 {@code RETURNING} 返回的五列：主键、版本号、两个时间戳、
 * 以及 {@code definition::text AS stored_definition} 的落库文本。
 * 后续快照写入用 {@code storedDefinition}（不是入参 definition）——
 * 与原 JDBC 的 {@code createExecutionSnapshot(..., storedDefinition)} 同口径。
 *
 * <p>表结构：{@code agent_version}（全局版本表，无 {@code tenant_id} 列、无 RLS）。
 */
@TableName("agent_version")
public class AgentVersionCreatedDO {

    @TableField("version_id")
    private @Nullable String versionId;

    @TableField("version_number")
    private int versionNumber;

    @TableField("created_at")
    private @Nullable Instant createdAt;

    @TableField("published_at")
    private @Nullable Instant publishedAt;

    /** {@code definition::text AS stored_definition}。 */
    @TableField("stored_definition")
    private @Nullable String storedDefinition;

    public @Nullable String getVersionId() { return versionId; }
    public void setVersionId(@Nullable String versionId) { this.versionId = versionId; }
    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }
    public @Nullable Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(@Nullable Instant createdAt) { this.createdAt = createdAt; }
    public @Nullable Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(@Nullable Instant publishedAt) { this.publishedAt = publishedAt; }
    public @Nullable String getStoredDefinition() { return storedDefinition; }
    public void setStoredDefinition(@Nullable String storedDefinition) {
        this.storedDefinition = storedDefinition;
    }
}
