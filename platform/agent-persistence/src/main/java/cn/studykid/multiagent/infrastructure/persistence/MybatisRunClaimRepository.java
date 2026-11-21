package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Duration;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RunClaimMapper;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RunClaimMapper.LeasedTaskRow;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort.LeasedTask;

/**
 * RunClaimPort MyBatis-Plus 实现（W4 RunClaim 租约状态机，tenantSqlSessionFactory + RLS）。
 *
 * <p>⚠️ <b>PG 特有语义零改动承诺</b>：全部 11 条 SQL 从 JdbcRunClaimRepository
 * {@code """..."""} 文本块逐字搬运 —— WITH MATERIALIZED / UPDATE…FROM /
 * FOR UPDATE SKIP LOCKED / IS DISTINCT FROM / || ' seconds'::interval /
 * CAS fencing_token + 1 / RETURNING —— 一条都不丢。
 *
 * <p>⚠️ <b>claimTasks 双阶段同事务</b>：先收敛超限 RUNNING（failOverCapExpired），
 * 再跑 MATERIALIZED CTE 领取 —— 同一事务保证收敛后的 state=FAILED 对后续
 * SELECT 候选集立即可见（等价于 attempt>=maxAttempts 排除）。
 *
 * <p>⚠️ <b>requestCancel 审计</b>：只在真正取消（受影响行数 > 0）时留痕，
 * 避免对已终态 Run 的无效取消污染追溯。
 */
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisRunClaimRepository implements RunClaimPort {

    private static final Logger log = LoggerFactory.getLogger(MybatisRunClaimRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunClaimMapper mapper;
    private final DataSource dataSource;
    private final AuditPort audit;

    @Value("${agent.run.max-attempts:3}")
    private int maxAttempts = 3;

    public MybatisRunClaimRepository(@NonNull RunClaimMapper mapper,
                                     @Qualifier("dataSource") @NonNull DataSource dataSource,
                                     @Nullable AuditPort audit) {
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.audit = audit;
    }

    @Override
    public @NonNull List<LeasedTask> claimTasks(@NonNull String tenantId,
                                                 @NonNull String workerId,
                                                 @NonNull Duration leaseDuration,
                                                 int limit) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        // [0] 先收敛超限 RUNNING（同事务内 state 立即可见）
                        int overCap = mapper.failOverCapExpired(tenantId, maxAttempts);
                        if (overCap > 0) {
                            log.warn("租户 {} 收敛重试超限的过期 RUNNING {} 个 (maxAttempts={})",
                                    tenantId, overCap, maxAttempts);
                        }
                        // [1] WITH MATERIALIZED CTE 批量领取
                        List<LeasedTaskRow> rows = mapper.claimBatch(
                                tenantId, limit, workerId,
                                (int) leaseDuration.toSeconds(),
                                RunClaimPort.SSE_DIRECT_LEASE_OWNER);
                        return rows.stream().map(this::toLeasedTask).toList();
                    });
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public @NonNull Optional<LeasedTask> claimSpecificRun(@NonNull String tenantId,
                                                           @NonNull String runId,
                                                           @NonNull String workerId,
                                                           @NonNull Duration leaseDuration) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        // messageTokenOf：先读 fencing_token，再 CAS 用
                        Optional<Long> fencing = mapper.readFencingToken(tenantId, runId);
                        if (fencing.isEmpty()) {
                            return Optional.empty();
                        }
                        return mapper.claimSpecific(tenantId, runId, workerId,
                                (int) leaseDuration.toSeconds(), fencing.get())
                                .map(this::toLeasedTask);
                    });
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public @NonNull Optional<LeasedTask> claimParkedRunForResume(@NonNull String tenantId,
                                                                   @NonNull String runId,
                                                                   @NonNull String workerId,
                                                                   @NonNull Duration leaseDuration) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        Optional<Long> fencing = mapper.readFencingToken(tenantId, runId);
                        if (fencing.isEmpty()) {
                            return Optional.empty();
                        }
                        return mapper.claimParkedForResume(tenantId, runId, workerId,
                                (int) leaseDuration.toSeconds(), fencing.get())
                                .map(this::toLeasedTask);
                    });
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public boolean renewLease(@NonNull String tenantId, @NonNull String runId,
                               @NonNull String workerId, long expectedFencingToken,
                               @NonNull Duration leaseDuration) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.renewLease(tenantId, runId, workerId,
                            expectedFencingToken, (int) leaseDuration.toSeconds()) > 0);
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public boolean releaseLease(@NonNull String tenantId, @NonNull String runId,
                                 @NonNull String workerId, long expectedFencingToken) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.releaseLease(tenantId, runId, workerId, expectedFencingToken) > 0);
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public boolean commitResult(@NonNull String tenantId, @NonNull String runId,
                                 long expectedFencingToken, @NonNull String state,
                                 @NonNull String resultPayload) {
        return commitResult(tenantId, runId, expectedFencingToken, state, resultPayload, null);
    }

    @Override
    public boolean commitResult(@NonNull String tenantId, @NonNull String runId,
                                 long expectedFencingToken, @NonNull String state,
                                 @NonNull String resultPayload, @Nullable String errorCode) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.commitResult(tenantId, runId, expectedFencingToken,
                            state, resultPayload, errorCode) > 0);
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public boolean isCurrentGeneration(@NonNull String tenantId, @NonNull String runId,
                                        long fencingToken) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.isCurrentGeneration(tenantId, runId, fencingToken));
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public boolean requestCancel(@NonNull String tenantId, @NonNull String runId) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> {
                        int affected = mapper.requestCancel(tenantId, runId);
                        if (affected > 0 && audit != null) {
                            audit.recordRequired(tenantId, "api",
                                    AuditPort.Actions.RUN_CANCEL,
                                    "run", runId, Map.of());
                        }
                        return affected > 0;
                    });
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public boolean isCancelled(@NonNull String tenantId, @NonNull String runId) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.isCancelled(tenantId, runId));
        } finally {
            TenantScope.clear();
        }
    }

    @Override
    public int failStaleParkedRuns(@NonNull String tenantId, @NonNull Duration maxWaiting) {
        TenantScope.bind(tenantId);
        try {
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, new TenantContext(tenantId, "system", Set.of(), 0L),
                    conn -> mapper.failStaleParkedRuns(tenantId, (int) maxWaiting.toSeconds()));
        } finally {
            TenantScope.clear();
        }
    }

    private LeasedTask toLeasedTask(LeasedTaskRow r) {
        return new LeasedTask(r.getRunId(), r.getFencingToken(),
                r.getLeaseExpiresAt(), r.getAttempt(), r.getDeadlineAt());
    }
}
