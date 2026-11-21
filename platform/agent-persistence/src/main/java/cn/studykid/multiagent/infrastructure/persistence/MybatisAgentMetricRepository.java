package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.AgentMetricMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort;

/**
 * AgentMetricPort MyBatis 实现（platform schema，无 RLS）。
 *
 * <p>⚠️ 指标表在 platform schema，且定时任务（ReviewSampleJob 等）在租户上下文之外扫描全租户数据；
 * 本仓储的 Mapper 仍绑在租户 SqlSessionFactory 上，因此每次调用都必须显式提供哨兵租户 + 外层事务，
 * 否则 RlsTenantBindingInterceptor 会 fail-closed 拒绝裸 Mapper 调用。
 */
@Repository
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisAgentMetricRepository implements AgentMetricPort {

    private static final String SYSTEM_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    private final AgentMetricMapper mapper;
    private final DataSource dataSource;

    public MybatisAgentMetricRepository(@NonNull AgentMetricMapper mapper,
                                         @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public void upsert(@NonNull TraceMetric m) {
        inSystemScopeVoid(() -> mapper.upsertMetric(new AgentMetricMapper.MetricRow(
                m.traceId(), m.tenantId(), m.runId(), m.agentId(), m.agentVersion(), m.scenario(),
                m.modelId(), m.startedAt(), m.endedAt(), m.durationMs(), m.terminalState(),
                m.terminationReason().name(), m.inputTokens(), m.outputTokens(), m.costAmount(),
                m.currency(), m.toolRounds(), m.toolCallTotal(), m.toolCallFailed(),
                m.toolCallInvalid(), m.toolCallDuplicate(), m.loopSuspectedCount(),
                m.maxRoundsReached(), m.clarificationCount(), m.thinkMsTotal(), m.toolMsTotal(),
                m.autoChecked(), m.autoVerified(), m.sampled(), m.humanVerdict(), m.hallucination(),
                m.instructionFollowed(), m.feedbackUp(), m.feedbackDown(), m.retryCount(),
                m.abandoned(), m.safetyBlockedCount(), m.unauthorizedAttemptCount(),
                m.sensitiveOutputHits(), m.traceIdSource().name())));
    }

    @Override
    public @NonNull Optional<TraceMetric> findByTraceId(@NonNull String traceId) {
        return inSystemScope(() -> Optional.ofNullable(mapper.findByTraceId(traceId))
                .map(this::toMetric));
    }

    @Override
    public void replaceToolCalls(@NonNull String traceId, @NonNull List<ToolCallRow> rows) {
        inSystemScopeVoid(() -> {
            mapper.deleteToolCalls(traceId);
            for (ToolCallRow r : rows) {
                mapper.insertToolCall(new AgentMetricMapper.ToolCallRow(r.traceId(), r.tenantId(),
                        r.toolName(), r.callCount(), r.failedCount(), r.invalidCount(),
                        r.duplicateCount(), r.totalDurationMs(), r.maxDurationMs(), r.startedAt()));
            }
        });
    }

    @Override
    public @NonNull Overview overview(@NonNull MetricQuery q) {
        return inSystemScope(() -> {
            MetricQuery c = q.clamp();
            AgentMetricMapper.OverviewRow r = mapper.overview(c.from(), c.to(), c.tenantId(),
                    c.agentId(), c.agentVersion(), c.scenario());
            long n = r.traceCount();
            Double successRate = n == 0 ? null : (double) r.succeeded() / n;
            Double oversizedRate = n == 0 ? null : (double) r.oversized() / n;
            Double maxRoundsRate = n == 0 ? null : (double) r.maxRounds() / n;
            Double invalidRate = (r.calls() == null || r.invalid() == null || r.calls() == 0)
                    ? null : (double) r.invalid() / r.calls();
            Long autoChecked = zeroAsNull(r.autoChecked());
            Long sampled = zeroAsNull(r.sampled());
            Long followedDenom = zeroAsNull(r.followedDenom());
            Long falseDenom = zeroAsNull(r.falseDenom());
            Long attrGraded = zeroAsNull(r.attrGraded());
            return new Overview(n, r.succeeded(), successRate, r.avgDuration(), r.inTokens(),
                    r.outTokens(), r.cost(), r.avgCost() == null ? null : r.avgCost().doubleValue(),
                    oversizedRate, r.avgRounds(), maxRoundsRate,
                    invalidRate, r.loops(), autoChecked, r.autoVerified(),
                    (autoChecked == null || autoChecked == 0) ? null
                            : (double) r.autoVerified() / autoChecked,
                    n == 0 ? null : (double) (autoChecked == null ? 0 : autoChecked) / n,
                    sampled,
                    (sampled == null || sampled == 0) ? null : (double) r.humanOk() / sampled,
                    (falseDenom == null || falseDenom == 0) ? null : (double) r.falseOk() / falseDenom,
                    (sampled == null || sampled == 0) ? null : (double) r.hallucinated() / sampled,
                    (followedDenom == null || followedDenom == 0) ? null
                            : (double) r.followed() / followedDenom,
                    attrGraded, r.attrCorrect(),
                    (attrGraded == null || attrGraded < Overview.MIN_SAMPLE_SIZE) ? null
                            : (double) r.attrCorrect() / attrGraded,
                    r.fbUp(), r.fbDown(),
                    (r.fbUp() == null || r.fbDown() == null || (r.fbUp() + r.fbDown()) == 0)
                            ? null : (double) r.fbUp() / (r.fbUp() + r.fbDown()),
                    n == 0 ? null : (double) r.abandoned() / n,
                    r.safety(), r.unauthorized(), r.sensitive(), Instant.now());
        });
    }

    @Override
    public @NonNull List<ReasonCount> terminationDistribution(@NonNull MetricQuery q) {
        return inSystemScope(() -> {
            MetricQuery c = q.clamp();
            return mapper.terminationDistribution(c.from(), c.to(), c.tenantId(), c.agentId(),
                            c.agentVersion(), c.scenario()).stream()
                    .map(r -> new ReasonCount(TerminationReason.valueOf(r.key()), r.count()))
                    .toList();
        });
    }

    @Override
    public @NonNull LatencyPercentiles latency(@NonNull MetricQuery q) {
        return inSystemScope(() -> {
            MetricQuery c = q.clamp();
            AgentMetricMapper.PercentileRow r = mapper.latency(c.from(), c.to(), c.tenantId(),
                    c.agentId(), c.agentVersion(), c.scenario());
            return r == null ? new LatencyPercentiles(null, null, null)
                    : new LatencyPercentiles(r.p50(), r.p95(), r.p99());
        });
    }

    @Override
    public @NonNull List<ToolMetric> toolBreakdown(@NonNull MetricQuery q) {
        return inSystemScope(() -> {
            MetricQuery c = q.clamp();
            return mapper.toolBreakdown(c.from(), c.to(), c.tenantId(), c.agentId(),
                            c.agentVersion(), c.scenario()).stream()
                    .map(r -> new ToolMetric(r.toolName(), r.calls(), r.failed(),
                            r.calls() == 0 ? null : (double) r.failed() / r.calls(),
                            r.calls() == 0 || r.totalMs() == null
                                    ? null : (double) r.totalMs() / r.calls(),
                            r.maxMs() == null ? null : (double) r.maxMs()))
                    .toList();
        });
    }

    @Override
    public @NonNull List<AnomalyTrace> anomalies(@NonNull MetricQuery q, int limit) {
        return inSystemScope(() -> {
            MetricQuery c = q.clamp();
            return mapper.anomalies(c.from(), c.to(), c.tenantId(), c.agentId(),
                            c.agentVersion(), c.scenario(), limit).stream()
                    .map(r -> new AnomalyTrace(r.traceId(),
                            r.loopSuspectedCount() != null && r.loopSuspectedCount() > 0
                                    ? AnomalyTrace.AnomalyKind.LOOP_SUSPECTED
                                    : r.totalTokens() > 20000
                                            ? AnomalyTrace.AnomalyKind.OVERSIZED_TOKEN
                                            : AnomalyTrace.AnomalyKind.MAX_ROUNDS,
                            r.totalTokens(), r.toolRounds(), r.loopSuspectedCount(), r.startedAt()))
                    .toList();
        });
    }

    @Override
    public @NonNull List<ScenarioCount> scenarios(@NonNull MetricQuery q) {
        return inSystemScope(() -> {
            MetricQuery c = q.clamp();
            return mapper.scenarios(c.from(), c.to(), c.tenantId(), c.agentId(),
                            c.agentVersion()).stream()
                    .map(r -> new ScenarioCount(r.key() == null ? UNLABELED_SCENARIO : r.key(),
                            r.key() == null ? UNLABELED_SCENARIO : r.key(), r.count()))
                    .toList();
        });
    }

    @Override
    public @NonNull List<SamplingCandidate> samplingCandidates(@NonNull MetricQuery q, int limit) {
        return inSystemScope(() -> {
            MetricQuery c = q.clamp();
            return mapper.samplingCandidates(c.from(), c.to(), c.tenantId(), c.agentId(),
                            c.agentVersion(), c.scenario(), limit).stream()
                    .map(r -> new SamplingCandidate(r.traceId(), r.tenantId(), r.runId(), r.scenario(),
                            r.autoChecked(), r.autoVerified(), r.maxRoundsReached(),
                            r.loopSuspectedCount(), r.safetyBlockedCount(), r.totalTokens(),
                            r.startedAt()))
                    .toList();
        });
    }

    @Override
    public void recordEvalSample(@NonNull EvalSample s) {
        inSystemScopeVoid(() -> {
            mapper.insertEvalSample(new AgentMetricMapper.EvalSampleRow(s.traceId(), s.tenantId(),
                    s.verdict(), s.hallucination(), s.instructionFollowed(), s.attributionCorrect(),
                    s.note(), s.sampler()));
            mapper.markSampled(s.traceId(), s.tenantId(), s.verdict(), s.hallucination(),
                    s.instructionFollowed(), s.attributionCorrect());
        });
    }

    @Override
    public void recordAttributionGrade(@NonNull String traceId, @NonNull String tenantId, boolean correct) {
        inSystemScopeVoid(() -> mapper.recordAttribution(traceId, tenantId, correct));
    }

    @Override
    public void recordFeedback(@NonNull FeedbackEvent e) {
        inSystemScopeVoid(() -> {
            mapper.insertFeedback(e.traceId(), e.tenantId(), e.kind().name());
            mapper.bumpFeedback(e.traceId(), e.tenantId(), e.kind().name());
        });
    }

    @Override
    public void recordRiskEvent(@NonNull RiskEvent e) {
        inSystemScopeVoid(() -> {
            mapper.insertRiskEvent(e.traceId(), e.tenantId(), e.eventType().name(), e.detailDigest());
            mapper.bumpRiskCounters(e.traceId(), e.tenantId(), e.eventType().name());
        });
    }

    @Override
    public void recordTaskOutcome(@NonNull TaskOutcome o) {
        inSystemScopeVoid(() -> {
            mapper.upsertTaskOutcome(o.traceId(), o.tenantId(), o.checkerCode(), o.externalRef(),
                    o.verified(), o.evidenceDigest());
            mapper.refreshAutoVerified(o.traceId(), o.tenantId());
        });
    }

    /** 指标表属于 platform schema 且无 RLS：用哨兵租户显式开外层事务，不能裸调 Mapper。 */
    private <T> T inSystemScope(Supplier<T> action) {
        TenantScope.bind(SYSTEM_TENANT_ID);
        try {
            return TransactionalConnectionHolder.inTransaction(dataSource,
                    new TenantContext(SYSTEM_TENANT_ID, "system", Set.of(), 0L),
                    conn -> action.get());
        } finally {
            TenantScope.clear();
        }
    }

    private void inSystemScopeVoid(Runnable action) {
        inSystemScope(() -> {
            action.run();
            return null;
        });
    }

    private TraceMetric toMetric(AgentMetricMapper.MetricRow r) {
        return new TraceMetric(r.traceId(), r.tenantId(), r.runId(), r.agentId(), r.agentVersion(),
                r.scenario(), r.modelId(), r.startedAt(), r.endedAt(), r.durationMs(),
                r.terminalState(), TerminationReason.valueOf(r.terminationReason()),
                r.inputTokens(), r.outputTokens(), r.costAmount(), r.currency(), r.toolRounds(),
                r.toolCallTotal(), r.toolCallFailed(), r.toolCallInvalid(), r.toolCallDuplicate(),
                r.loopSuspectedCount(), r.maxRoundsReached(), r.clarificationCount(),
                r.thinkMsTotal(), r.toolMsTotal(), r.autoChecked(), r.autoVerified(), r.sampled(),
                r.humanVerdict(), r.hallucination(), r.instructionFollowed(), r.feedbackUp(),
                r.feedbackDown(), r.retryCount(), r.abandoned(), r.safetyBlockedCount(),
                r.unauthorizedAttemptCount(), r.sensitiveOutputHits(),
                TraceIdSource.valueOf(r.traceIdSource()));
    }

    private static Long zeroAsNull(long v) {
        return v == 0 ? null : v;
    }
}
