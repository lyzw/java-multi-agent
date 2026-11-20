package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.Optional;

import org.apache.ibatis.annotations.Param;

public interface RunQueryMapper {
    /** 按 run_id 读当前运行状态 */
    Optional<RunQueryRow> findById(@Param("tenantId") String tenantId, @Param("runId") String runId);

    class RunQueryRow {
        private String tenantId;
        private String runId;
        private String state;
        private java.time.Instant createdAt;
        private java.time.Instant completedAt;
        private int attempt;
        private String errorCode;
        private String requesterId;
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public String getRunId() { return runId; }
        public void setRunId(String v) { this.runId = v; }
        public String getState() { return state; }
        public void setState(String v) { this.state = v; }
        public java.time.Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(java.time.Instant v) { this.createdAt = v; }
        public java.time.Instant getCompletedAt() { return completedAt; }
        public void setCompletedAt(java.time.Instant v) { this.completedAt = v; }
        public int getAttempt() { return attempt; }
        public void setAttempt(int v) { this.attempt = v; }
        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String v) { this.errorCode = v; }
        public String getRequesterId() { return requesterId; }
        public void setRequesterId(String v) { this.requesterId = v; }
    }
}
