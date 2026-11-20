package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

/**
 * RunCreation Mapper —— 4 张表：run / run_event / idempotency_record / outbox_event + pg_notify。
 * 全部租户域（FORCE RLS，tenantSqlSessionFactory）。
 */
public interface RunCreationMapper {

    /** SQL 0. 幂等键查重（返回 digest + resource_id） */
    Optional<IdempotencyHitRow> checkIdempotency(@Param("tenantId") String tenantId,
                                                  @Param("namespace") String namespace,
                                                  @Param("idempotencyKey") String idempotencyKey);

    /** SQL 1. INSERT run ON CONFLICT DO NOTHING（返回受影响行：0=命中） */
    int insertRun(@Param("tenantId") String tenantId,
                  @Param("runId") String runId,
                  @Param("snapshotId") String snapshotId,
                  @Param("idempotencyKey") String idempotencyKey,
                  @Param("requestDigest") String requestDigest,
                  @Param("inputPayload") String inputPayload,
                  @Param("correlationId") String correlationId,
                  @Param("deadlineAt") String deadlineAt,
                  @Param("budgetAmount") int budgetAmount,
                  @Param("requesterId") String requesterId);

    /** SQL 2. INSERT run_event (RUN_CREATED) */
    int insertRunCreatedEvent(@Param("tenantId") String tenantId,
                              @Param("runId") String runId,
                              @Param("correlationId") String correlationId);

    /** SQL 3. pg_notify 'run_event' */
    int pgNotifyRunEvent(@Param("tenantId") String tenantId,
                         @Param("runId") String runId,
                         @Param("sequenceNo") String sequenceNo);

    /** SQL 4. INSERT idempotency_record ON CONFLICT DO NOTHING */
    int insertIdempotencyRecord(@Param("tenantId") String tenantId,
                                @Param("namespace") String namespace,
                                @Param("idempotencyKey") String idempotencyKey,
                                @Param("requestDigest") String requestDigest,
                                @Param("runId") String runId);

    /** SQL 5. INSERT outbox_event (RUN_DISPATCH, PENDING) */
    int insertOutboxDispatch(@Param("tenantId") String tenantId,
                             @Param("runId") String runId,
                             @Param("payload") String payload);

    /** SQL 6. 回读 run（创建后读取初始状态） */
    Optional<RunReadRow> readRun(@Param("tenantId") String tenantId, @Param("runId") String runId);

    /** SQL 7. 拉取 PENDING outbox（按 created_at ASC LIMIT N） */
    List<PendingOutboxRow> listPendingOutbox(@Param("tenantId") String tenantId, @Param("limit") int limit);

    /** SQL 8. outbox → PUBLISHED（CAS state=PENDING） */
    int markOutboxPublished(@Param("tenantId") String tenantId,
                            @Param("outboxId") String outboxId,
                            @Param("publishedAt") String publishedAt);

    /** SQL 9. outbox → FAILED（attempts+1） */
    int markOutboxFailed(@Param("tenantId") String tenantId,
                         @Param("outboxId") String outboxId,
                         @Param("error") String error);

    // ── Mapper 内部行值 POJO ──

    class IdempotencyHitRow {
        private String tenantId;
        private String requestDigest;
        private String resourceId;
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public String getRequestDigest() { return requestDigest; }
        public void setRequestDigest(String v) { this.requestDigest = v; }
        public String getResourceId() { return resourceId; }
        public void setResourceId(String v) { this.resourceId = v; }
    }

    class RunReadRow {
        private String tenantId;
        private String runId;
        private String state;
        private int attempt;
        private long fencingToken;
        private String leaseOwner;
        private java.time.Instant leaseExpiresAt;
        private java.time.Instant createdAt;
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public String getRunId() { return runId; }
        public void setRunId(String v) { this.runId = v; }
        public String getState() { return state; }
        public void setState(String v) { this.state = v; }
        public int getAttempt() { return attempt; }
        public void setAttempt(int v) { this.attempt = v; }
        public long getFencingToken() { return fencingToken; }
        public void setFencingToken(long v) { this.fencingToken = v; }
        public String getLeaseOwner() { return leaseOwner; }
        public void setLeaseOwner(String v) { this.leaseOwner = v; }
        public java.time.Instant getLeaseExpiresAt() { return leaseExpiresAt; }
        public void setLeaseExpiresAt(java.time.Instant v) { this.leaseExpiresAt = v; }
        public java.time.Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(java.time.Instant v) { this.createdAt = v; }
    }

    class PendingOutboxRow {
        private String tenantId;
        private String outboxId;
        private String runId;
        private String eventType;
        private String payload;
        private int attempts;
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public String getOutboxId() { return outboxId; }
        public void setOutboxId(String v) { this.outboxId = v; }
        public String getRunId() { return runId; }
        public void setRunId(String v) { this.runId = v; }
        public String getEventType() { return eventType; }
        public void setEventType(String v) { this.eventType = v; }
        public String getPayload() { return payload; }
        public void setPayload(String v) { this.payload = v; }
        public int getAttempts() { return attempts; }
        public void setAttempts(int v) { this.attempts = v; }
    }
}
