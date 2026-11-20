package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

public interface RunEventQueryMapper {
    /** 拉取 run_event 增量（sequence_no > since, ORDER BY ASC LIMIT N） */
    List<RunEventRow> findAfter(@Param("tenantId") String tenantId,
                                @Param("runId") String runId,
                                @Param("since") int since,
                                @Param("limit") int limit);

    /** 读 run 当前 state */
    Optional<RunStateRow> readRunState(@Param("tenantId") String tenantId, @Param("runId") String runId);

    class RunEventRow {
        private String tenantId;
        private int sequenceNo;
        private String eventType;
        private String payload;
        private java.time.Instant createdAt;
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public int getSequenceNo() { return sequenceNo; }
        public void setSequenceNo(int v) { this.sequenceNo = v; }
        public String getEventType() { return eventType; }
        public void setEventType(String v) { this.eventType = v; }
        public String getPayload() { return payload; }
        public void setPayload(String v) { this.payload = v; }
        public java.time.Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(java.time.Instant v) { this.createdAt = v; }
    }
    class RunStateRow {
        private String tenantId;
        private String state;
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public String getState() { return state; }
        public void setState(String v) { this.state = v; }
    }
}
