package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

/** platform.agent_trace_metric / tool_call / eval_sample / feedback / risk / task_outcome。 */
public interface AgentMetricMapper {

    // ── 写入 ──
    int upsertMetric(@Param("m") MetricRow m);
    int insertToolCall(@Param("r") ToolCallRow r);
    int deleteToolCalls(@Param("traceId") String traceId);
    int insertEvalSample(@Param("r") EvalSampleRow r);
    int markSampled(@Param("traceId") String traceId, @Param("tenantId") String tenantId,
                     @Param("verdict") String verdict, @Param("hallucination") Boolean hallucination,
                     @Param("instructionFollowed") Boolean instructionFollowed,
                     @Param("attributionCorrect") Boolean attributionCorrect);
    int recordAttribution(@Param("traceId") String traceId, @Param("tenantId") String tenantId,
                           @Param("correct") boolean correct);
    int insertFeedback(@Param("traceId") String traceId, @Param("tenantId") String tenantId,
                        @Param("kind") String kind);
    int bumpFeedback(@Param("traceId") String traceId, @Param("tenantId") String tenantId,
                      @Param("kind") String kind);
    int insertRiskEvent(@Param("traceId") String traceId, @Param("tenantId") String tenantId,
                         @Param("eventType") String eventType, @Param("detailDigest") String detailDigest);
    int bumpRiskCounters(@Param("traceId") String traceId, @Param("tenantId") String tenantId,
                          @Param("eventType") String eventType);
    int upsertTaskOutcome(@Param("traceId") String traceId, @Param("tenantId") String tenantId,
                           @Param("checkerCode") String checkerCode, @Param("externalRef") String externalRef,
                           @Param("verified") boolean verified, @Param("evidenceDigest") String evidenceDigest);
    int refreshAutoVerified(@Param("traceId") String traceId, @Param("tenantId") String tenantId);

    // ── 读取 ──
    MetricRow findByTraceId(@Param("traceId") String traceId);
    OverviewRow overview(@Param("from") Instant from, @Param("to") Instant to,
                          @Param("tenantId") String tenantId, @Param("agentId") String agentId,
                          @Param("agentVersion") Integer agentVersion, @Param("scenario") String scenario);
    List<CountRow> terminationDistribution(@Param("from") Instant from, @Param("to") Instant to,
                                            @Param("tenantId") String tenantId, @Param("agentId") String agentId,
                                            @Param("agentVersion") Integer agentVersion, @Param("scenario") String scenario);
    PercentileRow latency(@Param("from") Instant from, @Param("to") Instant to,
                           @Param("tenantId") String tenantId, @Param("agentId") String agentId,
                           @Param("agentVersion") Integer agentVersion, @Param("scenario") String scenario);
    List<ToolAggRow> toolBreakdown(@Param("from") Instant from, @Param("to") Instant to,
                                    @Param("tenantId") String tenantId, @Param("agentId") String agentId,
                                    @Param("agentVersion") Integer agentVersion, @Param("scenario") String scenario);
    List<AnomalyRow> anomalies(@Param("from") Instant from, @Param("to") Instant to,
                                @Param("tenantId") String tenantId, @Param("agentId") String agentId,
                                @Param("agentVersion") Integer agentVersion, @Param("scenario") String scenario,
                                @Param("limit") int limit);
    List<CountRow> scenarios(@Param("from") Instant from, @Param("to") Instant to,
                              @Param("tenantId") String tenantId, @Param("agentId") String agentId,
                              @Param("agentVersion") Integer agentVersion);
    List<SamplingRow> samplingCandidates(@Param("from") Instant from, @Param("to") Instant to,
                                          @Param("tenantId") String tenantId, @Param("agentId") String agentId,
                                          @Param("agentVersion") Integer agentVersion,
                                          @Param("scenario") String scenario, @Param("limit") int limit);

    record MetricRow(String traceId, String tenantId, String runId, String agentId, Integer agentVersion,
                     String scenario, String modelId, Instant startedAt, Instant endedAt, Long durationMs,
                     String terminalState, String terminationReason, Long inputTokens, Long outputTokens,
                     BigDecimal costAmount, String currency, Integer toolRounds, Integer toolCallTotal,
                     Integer toolCallFailed, Integer toolCallInvalid, Integer toolCallDuplicate,
                     Integer loopSuspectedCount, Boolean maxRoundsReached, Integer clarificationCount,
                     Long thinkMsTotal, Long toolMsTotal, Boolean autoChecked, Boolean autoVerified,
                     Boolean sampled, String humanVerdict, Boolean hallucination, Boolean instructionFollowed,
                     Integer feedbackUp, Integer feedbackDown, Integer retryCount, Boolean abandoned,
                     Integer safetyBlockedCount, Integer unauthorizedAttemptCount, Integer sensitiveOutputHits,
                     String traceIdSource) {}
    record ToolCallRow(String traceId, String tenantId, String toolName, long callCount, long failedCount,
                       long invalidCount, long duplicateCount, Long totalDurationMs, Long maxDurationMs,
                       Instant startedAt) {}
    record EvalSampleRow(String traceId, String tenantId, String verdict, Boolean hallucination,
                         Boolean instructionFollowed, Boolean attributionCorrect, String note, String sampler) {}
    record OverviewRow(long traceCount, long succeeded, Double avgDuration, Long inTokens, Long outTokens,
                       BigDecimal cost, BigDecimal avgCost, long oversized, Double avgRounds,
                       long maxRounds, Long calls, Long invalid, long loops, long autoChecked,
                       long autoVerified, long sampled, long humanOk, long falseOk, long falseDenom,
                       long hallucinated, long followed, long followedDenom, long attrCorrect,
                       long attrGraded, Long fbUp, Long fbDown, long abandoned, long safety,
                       long unauthorized, long sensitive) {}
    record CountRow(String key, long count) {}
    record PercentileRow(Double p50, Double p95, Double p99) {}
    record ToolAggRow(String toolName, long calls, long failed, Long totalMs, Long maxMs) {}
    record AnomalyRow(String traceId, Instant startedAt, Integer toolRounds, Integer loopSuspectedCount,
                      long totalTokens) {}
    record SamplingRow(String traceId, String tenantId, String runId, String scenario, Boolean autoChecked,
                       Boolean autoVerified, Boolean maxRoundsReached, Integer loopSuspectedCount,
                       Integer safetyBlockedCount, long totalTokens, Instant startedAt) {}
}
