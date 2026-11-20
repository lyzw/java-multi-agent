package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * 确定结果事件持久化对象（对应 {@code JdbcResultEventRepository} 的两条 SQL）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core 的 {@code ResultEvent} record 混用；
 * 适配层 {@code MybatisResultEventRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构来自 {@code deploy/sql/platform/01_platform_ddl.sql}（{@code cap_result_event}）。
 * {@code result_payload} 为 JSONB 列，复用 {@link JsonbTypeHandler} 按 String 读写；
 * SQL 侧保留显式 {@code ::jsonb} / {@code ::text} cast（与旧 JDBC 逐字一致）。
 * {@code result_status} 是 PG 枚举 {@code cap_result_status}，按 String 映射。
 *
 * <p>⚠️ 唯一约束 {@code (tenant_id, invocation_id)} 是「只重试落账、不重执行」的地基，
 * 写入靠 {@code ON CONFLICT DO NOTHING}（不是 upsert），DO 层不改变该语义。
 */
@TableName("cap_result_event")
public class ResultEventDO {

    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("run_id")
    private @Nullable String runId;

    @TableField("invocation_id")
    private String invocationId;

    @TableField("terminal_event_id")
    private @Nullable String terminalEventId;

    @TableField("result_status")
    private String resultStatus;

    @TableField(value = "result_payload", typeHandler = JsonbTypeHandler.class)
    private @Nullable String resultPayload;

    @TableField("reason_code")
    private @Nullable String reasonCode;

    @TableField("created_at")
    private Instant createdAt;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public @Nullable String getRunId() { return runId; }
    public void setRunId(@Nullable String runId) { this.runId = runId; }
    public String getInvocationId() { return invocationId; }
    public void setInvocationId(String invocationId) { this.invocationId = invocationId; }
    public @Nullable String getTerminalEventId() { return terminalEventId; }
    public void setTerminalEventId(@Nullable String terminalEventId) { this.terminalEventId = terminalEventId; }
    public String getResultStatus() { return resultStatus; }
    public void setResultStatus(String resultStatus) { this.resultStatus = resultStatus; }
    public @Nullable String getResultPayload() { return resultPayload; }
    public void setResultPayload(@Nullable String resultPayload) { this.resultPayload = resultPayload; }
    public @Nullable String getReasonCode() { return reasonCode; }
    public void setReasonCode(@Nullable String reasonCode) { this.reasonCode = reasonCode; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
