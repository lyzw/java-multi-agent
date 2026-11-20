package cn.studykid.multiagent.infrastructure.persistence.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

/**
 * {@code agent} 主键回读投影（对应 {@code INSERT INTO agent ... RETURNING agent_id::text}）。
 *
 * <p>⚠️ 这不是表实体：{@code createAgent} 只需要新行的主键，
 * 其余字段由调用方入参决定（与原 JDBC 的 {@code rs.getString(1)} 逐字对应）。
 *
 * <p>表结构：{@code agent}（全局实体表，无 {@code tenant_id} 列、无 RLS）。
 */
@TableName("agent")
public class AgentIdDO {

    @TableField("agent_id")
    private @Nullable String agentId;

    public @Nullable String getAgentId() { return agentId; }
    public void setAgentId(@Nullable String agentId) { this.agentId = agentId; }
}
