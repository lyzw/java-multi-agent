package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

/**
 * Agent 列表/详情投影（对应 {@code JdbcAgentQueryRepository.SQL_SELECT}）。
 *
 * <p>⚠️ {@code version_count} / {@code latest_version_number} 来自
 * {@code LEFT JOIN LATERAL} 的聚合；{@code latest_version_number} 在「该 Agent 尚无版本」时
 * 为 {@code null}，适配层按原实现 {@code rs.getInt(...)} 的语义回落为 {@code 0}
 * （PG 约束 {@code version_number > 0}，故 0 唯一表示「无版本」）。
 *
 * <p>⚠️ {@code join_tenant_id} 恒为 SQL 里的 {@code NULL::text}：它只是把
 * 「从表行归属」显式投影出来供影子染色读取，不参与任何业务字段
 * （与原实现的 {@code rs.getString("join_tenant_id")} 逐字对应）。
 *
 * <p>表结构：{@code agent}（全局实体表，无 {@code tenant_id} 列）。
 */
@TableName("agent")
public class AgentSummaryDO {

    @TableField("agent_id")
    private @Nullable String agentId;

    @TableField("created_at")
    private @Nullable Instant createdAt;

    @TableField("name")
    private @Nullable String name;

    @TableField("description")
    private @Nullable String description;

    @TableField("version_count")
    private int versionCount;

    @TableField("latest_version_number")
    private @Nullable Integer latestVersionNumber;

    @TableField("latest_definition")
    private @Nullable String latestDefinition;

    @TableField("latest_published_at")
    private @Nullable Instant latestPublishedAt;

    @TableField("join_tenant_id")
    private @Nullable String joinTenantId;

    public @Nullable String getAgentId() { return agentId; }
    public void setAgentId(@Nullable String agentId) { this.agentId = agentId; }
    public @Nullable Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(@Nullable Instant createdAt) { this.createdAt = createdAt; }
    public @Nullable String getName() { return name; }
    public void setName(@Nullable String name) { this.name = name; }
    public @Nullable String getDescription() { return description; }
    public void setDescription(@Nullable String description) { this.description = description; }
    public int getVersionCount() { return versionCount; }
    public void setVersionCount(int versionCount) { this.versionCount = versionCount; }
    public @Nullable Integer getLatestVersionNumber() { return latestVersionNumber; }
    public void setLatestVersionNumber(@Nullable Integer latestVersionNumber) {
        this.latestVersionNumber = latestVersionNumber;
    }
    public @Nullable String getLatestDefinition() { return latestDefinition; }
    public void setLatestDefinition(@Nullable String latestDefinition) {
        this.latestDefinition = latestDefinition;
    }
    public @Nullable Instant getLatestPublishedAt() { return latestPublishedAt; }
    public void setLatestPublishedAt(@Nullable Instant latestPublishedAt) {
        this.latestPublishedAt = latestPublishedAt;
    }
    public @Nullable String getJoinTenantId() { return joinTenantId; }
    public void setJoinTenantId(@Nullable String joinTenantId) { this.joinTenantId = joinTenantId; }
}
