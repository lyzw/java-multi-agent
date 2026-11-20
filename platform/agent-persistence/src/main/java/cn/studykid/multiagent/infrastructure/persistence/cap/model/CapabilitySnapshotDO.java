package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * Run 级能力闭包快照持久化对象（对应 {@code JdbcCapabilitySnapshotRepository}）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core 的 {@code CapabilitySnapshot} record 混用；
 * 适配层 {@code MybatisCapabilitySnapshotRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构来自 {@code execution_capability_snapshot}（租户级 RLS 表）。
 *
 * <p>⚠️ <b>快照不可变</b>：写入用 {@code ON CONFLICT (run_id) DO NOTHING}，
 * 同一 runId 的重复保存<b>不覆盖</b>既有快照（06 §2「历史可追溯」）。
 * 快照被改写 = 历史失真，事后无法解释「当时到底按哪版配置跑的」。
 *
 * <p>⚠️ {@code effective_closure} 是 JSONB 列：复用 {@link JsonbTypeHandler} 按 String 读写，
 * SQL 侧保留显式 {@code ::jsonb} / {@code ::text} cast（与旧 JDBC 逐字一致）。
 */
@TableName("execution_capability_snapshot")
public class CapabilitySnapshotDO {

    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("agent_id")
    private String agentId;

    @TableField(value = "effective_closure", typeHandler = JsonbTypeHandler.class)
    private String effectiveClosure;

    @TableField("agent_version")
    private int agentVersion;

    @TableField("config_version")
    private long configVersion;

    @TableField("config_fingerprint")
    private String configFingerprint;

    @TableField("authz_version")
    private long authzVersion;

    @TableField("created_at")
    private Instant createdAt;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getEffectiveClosure() { return effectiveClosure; }
    public void setEffectiveClosure(String effectiveClosure) { this.effectiveClosure = effectiveClosure; }
    public int getAgentVersion() { return agentVersion; }
    public void setAgentVersion(int agentVersion) { this.agentVersion = agentVersion; }
    public long getConfigVersion() { return configVersion; }
    public void setConfigVersion(long configVersion) { this.configVersion = configVersion; }
    public String getConfigFingerprint() { return configFingerprint; }
    public void setConfigFingerprint(String configFingerprint) { this.configFingerprint = configFingerprint; }
    public long getAuthzVersion() { return authzVersion; }
    public void setAuthzVersion(long authzVersion) { this.authzVersion = authzVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
