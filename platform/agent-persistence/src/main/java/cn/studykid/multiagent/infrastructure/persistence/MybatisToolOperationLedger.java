package cn.studykid.multiagent.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.run.ToolOperationState;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.ToolOperationLedgerMapper;
import cn.studykid.multiagent.infrastructure.persistence.mapper.ToolOperationLedgerMapper.ToolOperationDetailRow;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.RunToolLedgerPort;

/**
 * ToolOperation 账本 MyBatis-Plus 实现（W2 迁移）。
 *
 * <p><b>不变量保留</b>：状态机由 ToolOperationState.requireTransitionTo 先校验
 * （DB 触发器 forbid_attempt_after_terminal 是第二道）；幂等键查重同一事务内；
 * 连接全走 TransactionalConnectionHolder。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisToolOperationLedger implements RunToolLedgerPort {

    private static final Logger log = LoggerFactory.getLogger(MybatisToolOperationLedger.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolOperationLedgerMapper mapper;
    private final DataSource dataSource;

    public MybatisToolOperationLedger(@NonNull ToolOperationLedgerMapper mapper,
                                      @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Registration register(@NonNull String tenantId, @NonNull String runId,
                                          @NonNull String toolName, @NonNull String riskLevel,
                                          @NonNull String idempotencyKey,
                                          @NonNull String requestDigest,
                                          @NonNull Map<String, Object> arguments,
                                          long fencingToken) {
        // Jackson 3 writeValueAsString 不再声明 checked IOException（改抛 JacksonException）
        String argsJson = JSON.writeValueAsString(arguments);
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        Optional<ToolOperationDetailRow> existing = mapper.findByIdempotencyKey(tenantId, idempotencyKey);
                        if (existing.isPresent()) {
                            log.info("[MP tool] 幂等命中: tenant={}, key={}, opId={}",
                                    tenantId, idempotencyKey, existing.get().getOperationId());
                            return buildRegistration(existing.get(), false);
                        }
                        String opId = mapper.inFlight(tenantId, runId, toolName, riskLevel,
                                idempotencyKey, requestDigest, argsJson, String.valueOf(fencingToken));
                        if (opId == null || opId.isBlank()) {
                            throw new IllegalStateException("tool_operation INSERT RETURNING 空");
                        }
                        return new Registration(opId, ToolOperationState.IN_FLIGHT, 0, fencingToken, true);
                    });
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public void recordResult(@NonNull String tenantId, @NonNull String operationId,
                             @NonNull ToolOperationState result, long fencingToken,
                             @Nullable String errorClass, @Nullable String errorMessage) {
        ToolOperationState.IN_FLIGHT.requireTransitionTo(result);
        TenantScope.bind(tenantId);
        try {
            TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        int updated = mapper.reconcile(tenantId, operationId, result.name(), "tool-callback");
                        if (updated == 0) {
                            throw new IllegalStateException("tool_operation reconcile 未命中（operation_id=" + operationId + "）");
                        }
                        mapper.recordAttempt(tenantId, operationId, 0, result.name(),
                                String.valueOf(fencingToken), errorClass, errorMessage,
                                result == ToolOperationState.UNKNOWN);
                        return null;
                    });
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public void reconcile(@NonNull String tenantId, @NonNull String operationId,
                          @NonNull ToolOperationState reconciled, @NonNull String source) {
        ToolOperationState.UNKNOWN.requireTransitionTo(reconciled);
        TenantScope.bind(tenantId);
        try {
            TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        int updated = mapper.reconcile(tenantId, operationId, reconciled.name(), source);
                        if (updated == 0) {
                            throw new IllegalStateException("tool_operation reconcile 未命中（可能已是终态）");
                        }
                        return null;
                    });
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public void manualDispose(@NonNull String tenantId, @NonNull String operationId,
                              @NonNull String disposedBy, @NonNull String note) {
        TenantScope.bind(tenantId);
        try {
            TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        int updated = mapper.manualDispose(tenantId, operationId, "manual-dispose", disposedBy);
                        if (updated == 0) {
                            throw new IllegalStateException("tool_operation manualDispose 未命中");
                        }
                        return null;
                    });
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public @NonNull List<UnknownOperation> findUnknownOperations(@NonNull String tenantId) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.listUnknown(tenantId).stream()
                            .map(r -> new UnknownOperation(r.getOperationId(), r.getRunId(),
                                    r.getToolName(), r.getIdempotencyKey(), r.getCreatedAt()))
                            .toList());
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public @NonNull Optional<Registration> findByIdempotencyKey(@NonNull String tenantId,
                                                                 @NonNull String idempotencyKey) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.findByIdempotencyKey(tenantId, idempotencyKey)
                            .map(r -> buildRegistration(r, false)));
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public @NonNull Optional<String> findRequestDigest(@NonNull String tenantId,
                                                       @NonNull String idempotencyKey) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.findDigestByIdempotencyKey(tenantId, idempotencyKey));
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public @NonNull Optional<Registration> findByOperationId(@NonNull String tenantId,
                                                              @NonNull String operationId) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.findById(tenantId, operationId)
                            .map(r -> buildRegistration(r, false)));
        } finally {
            TenantScope.clear();
        }
    }

    private Registration buildRegistration(ToolOperationDetailRow r, boolean created) {
        ToolOperationState state;
        try {
            state = ToolOperationState.valueOf(r.getState());
        } catch (IllegalArgumentException e) {
            state = ToolOperationState.UNKNOWN;
        }
        return new Registration(r.getOperationId(), state, r.getAttemptCount(),
                Long.parseLong(r.getFencingToken()), created);
    }
}
