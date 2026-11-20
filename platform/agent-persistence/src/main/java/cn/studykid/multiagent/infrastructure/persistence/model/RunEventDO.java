package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * Run 事件持久化对象（对应 {@code JdbcRunEventAppendRepository} 的三条 SQL）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与任何 core record 混用；
 * 适配层 {@code MybatisRunEventAppendRepository} 负责载荷序列化与序号分配。
 *
 * <p>表结构来自 {@code deploy/sql/platform/01_platform_ddl.sql}（{@code run_event}）。
 * {@code event_id} / {@code created_at} 由 DB DEFAULT 生成，旧实现的 INSERT 也不写这两列，
 * 故这里仅供 {@code BaseMapper} 反射使用，实际写入走自定义 {@code insertEvent}（不回填主键）。
 * {@code payload} 为 JSONB 列，复用 {@link JsonbTypeHandler} 按 String 写入，
 * SQL 侧保留显式 {@code ::jsonb} cast（与旧 JDBC 逐字一致）。
 */
@TableName("run_event")
public class RunEventDO {

    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("run_id")
    private String runId;

    @TableField("sequence_no")
    private long sequenceNo;

    @TableField("event_type")
    private String eventType;

    @TableField(value = "payload", typeHandler = JsonbTypeHandler.class)
    private String payload;

    @TableField("created_at")
    private @Nullable Instant createdAt;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public long getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(long sequenceNo) { this.sequenceNo = sequenceNo; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public @Nullable Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(@Nullable Instant createdAt) { this.createdAt = createdAt; }
}
