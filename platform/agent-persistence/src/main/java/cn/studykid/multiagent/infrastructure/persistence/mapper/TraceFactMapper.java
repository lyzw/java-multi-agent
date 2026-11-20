package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;

/** Trace 事实读取（run / agent_invocation / usage_record / tool_operation / run_step）。 */
public interface TraceFactMapper {

    RunRow readRun(@Param("runId") UUID runId);

    InvocationRow readFirstInvocation(@Param("runId") UUID runId, @Param("tenantId") String tenantId);

    RunTraceRow readRunTrace(@Param("runId") UUID runId, @Param("tenantId") String tenantId);

    InvocationParentRow readInvocationParent(@Param("runId") UUID runId);

    UsageRow readUsage(@Param("runId") UUID runId, @Param("tenantId") String tenantId);

    List<ToolOpRow> readToolOps(@Param("runId") UUID runId, @Param("tenantId") String tenantId);

    StepRow readSteps(@Param("runId") UUID runId, @Param("tenantId") String tenantId);

    List<String> knownToolNames();

    record RunRow(String tenantId, String state, String errorCode, String traceId,
                  String correlationId, Instant createdAt, Instant completedAt,
                  String scenario, String questionId) {}
    record InvocationRow(String tenantId, String traceId) {}
    record RunTraceRow(String tenantId, String traceId, String correlationId) {}
    record InvocationParentRow(String tenantId, String parentAgentId, Integer parentAgentVersion) {}
    record UsageRow(Long inTokens, Long outTokens, BigDecimal cost, String currency, String modelId) {}
    record ToolOpRow(String tenantId, String toolName, String requestDigest, long callCount,
                     long failedCount, long invalidArg, boolean hasTimeout, boolean hasUpstream,
                     Long totalMs, Long maxMs) {}
    record StepRow(long stepCount, long thinkSteps, long toolSteps, long askSteps,
                   long thinkMs, int maxStepNumber) {}
}
