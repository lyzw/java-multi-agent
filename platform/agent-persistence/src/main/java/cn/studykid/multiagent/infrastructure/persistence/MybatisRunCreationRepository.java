package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RunCreationMapper;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RunCreationMapper.PendingOutboxRow;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import cn.studykid.multiagent.ports.persistence.RunCreationPort.OutboxBatch;
import cn.studykid.multiagent.ports.persistence.RunCreationPort.PendingOutbox;
import cn.studykid.multiagent.ports.persistence.RunCreationPort.RunRecord;
import cn.studykid.multiagent.ports.persistence.RunLimits;

/**
 * RunCreation Port MyBatis-Plus 实现（W3 Run 域迁移，tenantSqlSessionFactory + RLS）。
 *
 * <p>⚠️ <b>核心不变量保留</b>：同事务 4 表写入 + 幂等键查重同事务内 + requesterId UUID 归一化。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisRunCreationRepository implements RunCreationPort {

    private static final Logger log = LoggerFactory.getLogger(MybatisRunCreationRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunCreationMapper mapper;
    private final DataSource dataSource;

    public MybatisRunCreationRepository(@NonNull RunCreationMapper mapper,
                                        @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull String insertRunAtomically(@NonNull String tenantId, @NonNull String runId,
                                                @NonNull String snapshotId, @NonNull IdempotencyKey key,
                                                @NonNull RequestDigest digest,
                                                @NonNull Map<String, Object> inputPayload,
                                                @NonNull String correlationId,
                                                @NonNull OutboxMessage agentPayload) {
        // 基础契约（8 参）：limits/requester 无，全部落 NULL
        return insertRunAtomically(tenantId, runId, snapshotId, key, digest,
                inputPayload, correlationId, agentPayload, RunLimits.UNLIMITED, null);
    }

    @Override
    public @NonNull String insertRunAtomically(@NonNull String tenantId, @NonNull String runId,
                                                @NonNull String snapshotId, @NonNull IdempotencyKey key,
                                                @NonNull RequestDigest digest,
                                                @NonNull Map<String, Object> inputPayload,
                                                @NonNull String correlationId,
                                                @NonNull OutboxMessage agentPayload,
                                                @NonNull RunLimits limits,
                                                @Nullable String requesterId) {
        String normalized = normalizeRequesterId(requesterId);
        if (requesterId != null && normalized == null) {
            log.warn("requesterId 非合法 UUID，落 NULL: {}", requesterId);
        }
        OutboxMessage.assertPayloadSafe(agentPayload.toPayload());

        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        Optional<RunCreationMapper.IdempotencyHitRow> hit =
                                mapper.checkIdempotency(tenantId, key.namespace(), key.keyValue());
                        if (hit.isPresent()) {
                            log.info("[MP run] 幂等命中: ns={}, key={}, resourceId={}",
                                    key.namespace(), key.keyValue(), hit.get().getResourceId());
                            return hit.get().getResourceId();
                        }
                        String inputJson = JSON.writeValueAsString(inputPayload);
                        String outboxJson = JSON.writeValueAsString(agentPayload.toPayload());
                        mapper.insertRun(tenantId, runId, snapshotId, key.keyValue(),
                                digest.value(), inputJson, correlationId,
                                limits.deadlineAt() != null ? limits.deadlineAt().toString() : null,
                                limits.budgetAmount() != null ? limits.budgetAmount().intValue() : 0,
                                normalized);
                        mapper.insertRunCreatedEvent(tenantId, runId, correlationId);
                        mapper.pgNotifyRunEvent(tenantId, runId, "1");
                        mapper.insertIdempotencyRecord(tenantId, key.namespace(), key.keyValue(),
                                digest.value(), runId);
                        mapper.insertOutboxDispatch(tenantId, runId, outboxJson);
                        return runId;
                    });
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public @NonNull IdempotencyDecision findExistingResource(@NonNull String tenantId,
                                                             @NonNull IdempotencyKey key,
                                                             @NonNull RequestDigest digest) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        Optional<RunCreationMapper.IdempotencyHitRow> hit =
                                mapper.checkIdempotency(tenantId, key.namespace(), key.keyValue());
                        if (hit.isEmpty()) {
                            return IdempotencyDecision.firstExecution("");
                        }
                        if (hit.get().getRequestDigest().equals(digest.value())) {
                            return IdempotencyDecision.replay(hit.get().getResourceId());
                        }
                        return IdempotencyDecision.conflict();
                    });
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public @Nullable RunRecord findRun(@NonNull String tenantId, @NonNull String runId) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.readRun(tenantId, runId).map(r -> new RunRecord(
                            r.getRunId(), r.getState(), r.getAttempt(), r.getFencingToken(),
                            r.getLeaseOwner(), r.getLeaseExpiresAt(), r.getCreatedAt())).orElse(null));
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public @NonNull OutboxBatch loadPendingOutbox(@NonNull String tenantId, int limit) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        List<PendingOutboxRow> rows = mapper.listPendingOutbox(tenantId, limit);
                        List<PendingOutbox> outboxes = rows.stream()
                                .map(r -> new PendingOutbox(r.getOutboxId(), r.getRunId(),
                                        r.getEventType(), JSON.readValue(r.getPayload(), new tools.jackson.core.type.TypeReference<>() {}),
                                        r.getAttempts()))
                                .toList();
                        return new OutboxBatch(outboxes);
                    });
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public boolean markOutboxPublished(@NonNull String tenantId, @NonNull String outboxId,
                                       @NonNull Instant publishedAt) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.markOutboxPublished(tenantId, outboxId, publishedAt.toString()) > 0);
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public void markOutboxFailed(@NonNull String tenantId, @NonNull String outboxId,
                                 @NonNull String error) {
        TenantScope.bind(tenantId);
        try {
            TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        mapper.markOutboxFailed(tenantId, outboxId, error);
                        return null;
                    });
        } finally {
            TenantScope.clear();
        }
    }

    private static @Nullable String normalizeRequesterId(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            UUID.fromString(raw);
            return raw;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
