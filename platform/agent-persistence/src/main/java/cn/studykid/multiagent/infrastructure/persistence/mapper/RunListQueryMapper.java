package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

/** Run 列表查询 Mapper（游标分页 + 动态 WHERE）。 */
public interface RunListQueryMapper {

    /** 按条件查一批 Run，limit+1 行用于判断 hasMore。 */
    List<RunListRow> list(@Param("tenantId") String tenantId,
                          @Param("requesterId") String requesterId,
                          @Param("state") String state,
                          @Param("subject") String subject,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          @Param("cursorCreatedAt") Instant cursorCreatedAt,
                          @Param("cursorRunId") String cursorRunId,
                          @Param("limit") int limit);

    class RunListRow {
        private String runId;
        private String state;
        private Instant createdAt;
        private Instant completedAt;
        private int attempt;
        private String errorCode;
        private String subject;
        private String grade;
        public String getRunId() { return runId; }
        public void setRunId(String v) { this.runId = v; }
        public String getState() { return state; }
        public void setState(String v) { this.state = v; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant v) { this.createdAt = v; }
        public Instant getCompletedAt() { return completedAt; }
        public void setCompletedAt(Instant v) { this.completedAt = v; }
        public int getAttempt() { return attempt; }
        public void setAttempt(int v) { this.attempt = v; }
        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String v) { this.errorCode = v; }
        public String getSubject() { return subject; }
        public void setSubject(String v) { this.subject = v; }
        public String getGrade() { return grade; }
        public void setGrade(String v) { this.grade = v; }
    }
}
