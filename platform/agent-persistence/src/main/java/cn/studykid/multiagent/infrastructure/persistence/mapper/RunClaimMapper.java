package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

/**
 * RunClaimPort Mapper —— Worker 租约状态机核心。
 *
 * <p>⚠️ <b>PG 特有语义零改动承诺</b>（从 JdbcRunClaimRepository 逐行搬运）：
 * <ul>
 *   <li>{@code WITH candidates AS MATERIALIZED} — 强制候选集只求值一次，
 *       防 RLS security-barrier 下 IN 子查询退化成 SubPlan 超发 Worker 配额；</li>
 *   <li>{@code UPDATE ... FROM candidates RETURNING} — 必须是 UPDATE…FROM 不是 UPDATE…WHERE IN；</li>
 *   <li>{@code FOR UPDATE SKIP LOCKED} — 并发 Worker 无阻塞领取；</li>
 *   <li>{@code IS DISTINCT FROM '%s'} — 放行 lease_owner IS NULL 的 QUEUED 行、
 *       同时排除 SSE_DIRECT_LEASE_OWNER 占位行；</li>
 *   <li>{@code lease_expires_at < now() + (? || ' seconds')::interval} — PG 间隔拼接；</li>
 *   <li>{@code fencing_token = fencing_token + 1} — CAS fencing 自增。</li>
 * </ul>
 */
public interface RunClaimMapper {

    /** [0] 收敛超限 RUNNING：attempt >= maxAttempts + 租约过期 → FAILED(MAX_ATTEMPTS_EXCEEDED) */
    int failOverCapExpired(@Param("tenantId") String tenantId, @Param("maxAttempts") int maxAttempts);

    /**
     * [1] 批量领取：WITH MATERIALIZED CTE + UPDATE…FROM + FOR UPDATE SKIP LOCKED。
     *
     * @param sseDirectLeaseOwner SSE 编排占位行的特殊 lease_owner（排除）
     */
    List<LeasedTaskRow> claimBatch(@Param("tenantId") String tenantId,
                                    @Param("limit") int limit,
                                    @Param("workerId") String workerId,
                                    @Param("leaseSeconds") int leaseSeconds,
                                    @Param("sseDirectLeaseOwner") String sseDirectLeaseOwner);

    /** [2] 指定 Run 领取：CAS fencing + 状态围栏（QUEUED 或 租约过期的 RUNNING） */
    java.util.Optional<LeasedTaskRow> claimSpecific(@Param("tenantId") String tenantId,
                                                     @Param("runId") String runId,
                                                     @Param("workerId") String workerId,
                                                     @Param("leaseSeconds") int leaseSeconds,
                                                     @Param("fencingToken") long fencingToken);

    /** [3] PARKED 恢复执行：CAS fencing + lease IS NULL + state='WAITING_APPROVAL' */
    java.util.Optional<LeasedTaskRow> claimParkedForResume(@Param("tenantId") String tenantId,
                                                            @Param("runId") String runId,
                                                            @Param("workerId") String workerId,
                                                            @Param("leaseSeconds") int leaseSeconds,
                                                            @Param("fencingToken") long fencingToken);

    /** [4] 续租：CAS fencing + lease_owner 归属 */
    int renewLease(@Param("tenantId") String tenantId,
                   @Param("runId") String runId,
                   @Param("workerId") String workerId,
                   @Param("fencingToken") long fencingToken,
                   @Param("leaseSeconds") int leaseSeconds);

    /** [5] 释放租约：CAS fencing + lease_owner 归属 */
    int releaseLease(@Param("tenantId") String tenantId,
                     @Param("runId") String runId,
                     @Param("workerId") String workerId,
                     @Param("fencingToken") long fencingToken);

    /** [6] 提交结果：CAS fencing + state='RUNNING' 围栏 + 写结果/错误 */
    int commitResult(@Param("tenantId") String tenantId,
                     @Param("runId") String runId,
                     @Param("fencingToken") long fencingToken,
                     @Param("newState") String newState,
                     @Param("resultPayloadJson") String resultPayloadJson,
                     @Param("errorCode") String errorCode);

    /** [7] CAS fencing 校验：当前 generation */
    boolean isCurrentGeneration(@Param("tenantId") String tenantId,
                                 @Param("runId") String runId,
                                 @Param("fencingToken") long fencingToken);

    /** [8] 请求取消：state IN (QUEUED, RUNNING) → CANCELLED */
    int requestCancel(@Param("tenantId") String tenantId,
                      @Param("runId") String runId);

    /** [9] 查取消状态：state='CANCELLED' OR cancel_requested_at IS NOT NULL */
    boolean isCancelled(@Param("tenantId") String tenantId, @Param("runId") String runId);

    /** [10] 收敛过期 WAITING_APPROVAL → FAILED(APPROVAL_TIMEOUT) */
    int failStaleParkedRuns(@Param("tenantId") String tenantId, @Param("maxWaitingSeconds") int maxWaitingSeconds);

    /** 读取当前 fencing_token（claimSpecificRun / claimParkedRunForResume 前置）。 */
    java.util.Optional<Long> readFencingToken(@Param("tenantId") String tenantId, @Param("runId") String runId);

    /** LeasedTask 返回行（claimBatch / claimSpecific / claimParkedForResume 的 RETURNING 结果）。 */
    class LeasedTaskRow {
        private String runId;
        private long fencingToken;
        private Instant leaseExpiresAt;
        private int attempt;
        private Instant deadlineAt;
        public String getRunId() { return runId; }
        public void setRunId(String v) { this.runId = v; }
        public long getFencingToken() { return fencingToken; }
        public void setFencingToken(long v) { this.fencingToken = v; }
        public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
        public void setLeaseExpiresAt(Instant v) { this.leaseExpiresAt = v; }
        public int getAttempt() { return attempt; }
        public void setAttempt(int v) { this.attempt = v; }
        public Instant getDeadlineAt() { return deadlineAt; }
        public void setDeadlineAt(Instant v) { this.deadlineAt = v; }
    }
}
