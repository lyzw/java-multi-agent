package cn.studykid.multiagent.infrastructure.persistence;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.TraceFactMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.TraceIdSource;
import cn.studykid.multiagent.ports.metrics.TraceFactPort;

/** TraceFactPort MyBatis 实现（只读聚合）。 */
@Repository
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisTraceFactRepository implements TraceFactPort {

    private final TraceFactMapper mapper;
    private final DataSource dataSource;

    public MybatisTraceFactRepository(@NonNull TraceFactMapper mapper,
                                       @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override
    public @NonNull Optional<TraceFacts> load(@NonNull String tenantId, @NonNull UUID runId) {
        return inTx(tenantId, () -> {
            TraceFactMapper.RunRow run = mapper.readRun(runId);
            if (run == null || !converged(run.state())) {
                // 不存在 / 在途不采集 —— 计入会把「还没跑完」算成「没成功」
                return Optional.empty();
            }
            // traceId 三级解析：run.trace_id → invocation.trace_id → correlation
            String traceId = run.traceId();
            TraceIdSource source = TraceIdSource.RUN_TRACE_ID;
            if (traceId == null || traceId.isBlank()) {
                TraceFactMapper.InvocationRow inv = mapper.readFirstInvocation(runId, tenantId);
                if (inv != null && inv.traceId() != null) {
                    traceId = inv.traceId();
                    source = TraceIdSource.INVOCATION_TRACE_ID;
                } else {
                    TraceFactMapper.RunTraceRow rt = mapper.readRunTrace(runId, tenantId);
                    traceId = rt != null ? rt.correlationId() : null;
                    source = TraceIdSource.CORRELATION_FALLBACK;
                }
            }
            if (traceId == null || traceId.isBlank()) {
                return Optional.empty();
            }
            TraceFactMapper.InvocationParentRow parent = mapper.readInvocationParent(runId);
            RunFacts runFacts = new RunFacts(runId.toString(), run.state(), run.errorCode(), traceId,
                    source, run.createdAt(), run.completedAt(),
                    parent != null ? parent.parentAgentId() : null,
                    parent != null ? parent.parentAgentVersion() : null,
                    run.scenario(), run.questionId());

            TraceFactMapper.UsageRow u = mapper.readUsage(runId, tenantId);
            UsageFacts usage = u == null
                    ? new UsageFacts(null, null, null, null, null)
                    : new UsageFacts(u.inTokens(), u.outTokens(), u.cost(), u.currency(), u.modelId());

            List<ToolGroup> tools = mapper.readToolOps(runId, tenantId).stream()
                    .map(r -> new ToolGroup(r.toolName(), (int) r.callCount(), (int) r.failedCount(),
                            (int) r.invalidArg(), r.hasTimeout(), r.hasUpstream(), r.totalMs(), r.maxMs()))
                    .toList();

            Set<String> known = new HashSet<>(mapper.knownToolNames());

            TraceFactMapper.StepRow s = mapper.readSteps(runId, tenantId);
            StepFacts steps = (s == null || s.stepCount() == 0)
                    ? StepFacts.ABSENT
                    : new StepFacts(true, s.thinkMs(), s.toolSteps(), s.askSteps(),
                            s.stepCount(), s.maxStepNumber());

            return Optional.of(new TraceFacts(runFacts, usage, tools, known, steps));
        });
    }

    @Override
    public @NonNull Optional<String> resolveTraceId(@NonNull String tenantId, @NonNull UUID runId) {
        return load(tenantId, runId).map(f -> f.run().traceId());
    }

    private static boolean converged(String state) {
        return switch (state) {
            case "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT" -> true;
            default -> false;
        };
    }

    private <T> T inTx(String tenantId, java.util.function.Supplier<T> fn) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L), conn -> fn.get());
        } finally {
            TenantScope.clear();
        }
    }
}
