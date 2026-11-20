package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 父子 Agent 关系投影持久化对象（对应 {@code JdbcCapAgentChildRefRepository}）。
 *
 * <p>persistence 模块内部 DO，不与 core 的 {@code CapAgentChildRef} record 混用；
 * 适配层 {@code MybatisCapAgentChildRefRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构来自 {@code cap_agent_child_ref}（全局实体：无 tenant_id 列，租户可见性由
 * {@code tenant_agent_binding} 决定）。逻辑主键是
 * {@code (parent_agent_id, parent_release_version, child_agent_id, child_release_version)}；
 * {@code parent_agent_id} 标为 {@code @TableId(INPUT)}，其余主键列作为普通列（同
 * {@code CapAgentDefDO} 的复合键处理）。写入走自定义
 * {@code INSERT ... ON CONFLICT DO NOTHING}，不调用 BaseMapper.insert。
 *
 * <p>⚠️ 本表为投影而非权威来源：权威定义是不可变 release 中的 childAgentRefs；
 * 表上的 FK {@code ON DELETE CASCADE} 由 DDL 保证，仓储层不实现级联/子清理逻辑。
 */
@TableName("cap_agent_child_ref")
public class CapAgentChildRefDO {

    @TableId(value = "parent_agent_id", type = IdType.INPUT)
    private String parentAgentId;

    @TableField("parent_release_version")
    private int parentReleaseVersion;

    @TableField("child_agent_id")
    private String childAgentId;

    @TableField("child_release_version")
    private int childReleaseVersion;

    @TableField("ordinal")
    private int ordinal;

    @TableField("required")
    private boolean required;

    @TableField("created_at")
    private Instant createdAt;

    public String getParentAgentId() { return parentAgentId; }
    public void setParentAgentId(String parentAgentId) { this.parentAgentId = parentAgentId; }
    public int getParentReleaseVersion() { return parentReleaseVersion; }
    public void setParentReleaseVersion(int parentReleaseVersion) { this.parentReleaseVersion = parentReleaseVersion; }
    public String getChildAgentId() { return childAgentId; }
    public void setChildAgentId(String childAgentId) { this.childAgentId = childAgentId; }
    public int getChildReleaseVersion() { return childReleaseVersion; }
    public void setChildReleaseVersion(int childReleaseVersion) { this.childReleaseVersion = childReleaseVersion; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
