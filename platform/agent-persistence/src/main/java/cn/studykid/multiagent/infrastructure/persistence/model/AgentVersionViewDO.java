package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

/**
 * Agent 版本历史行投影（对应 {@code JdbcAgentQueryRepository.listVersions}）。
 *
 * <p>⚠️ {@code definition} 是 JSONB 列，原实现用 {@code rs.getString("definition")} 取文本；
 * 这里保持同一读取方式（MyBatis 默认 {@code StringTypeHandler} → {@code rs.getString}），
 * SQL 里<b>不</b>额外加 {@code ::text} cast，与原语句逐字一致。
 *
 * <p>表结构：{@code agent_version}（全局版本表，无 {@code tenant_id} 列、无 RLS）。
 */
@TableName("agent_version")
public class AgentVersionViewDO {

    @TableField("version_id")
    private @Nullable String versionId;

    @TableField("version_number")
    private int versionNumber;

    @TableField("definition")
    private @Nullable String definition;

    @TableField("published_at")
    private @Nullable Instant publishedAt;

    @TableField("created_at")
    private @Nullable Instant createdAt;

    public @Nullable String getVersionId() { return versionId; }
    public void setVersionId(@Nullable String versionId) { this.versionId = versionId; }
    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }
    public @Nullable String getDefinition() { return definition; }
    public void setDefinition(@Nullable String definition) { this.definition = definition; }
    public @Nullable Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(@Nullable Instant publishedAt) { this.publishedAt = publishedAt; }
    public @Nullable Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(@Nullable Instant createdAt) { this.createdAt = createdAt; }
}
