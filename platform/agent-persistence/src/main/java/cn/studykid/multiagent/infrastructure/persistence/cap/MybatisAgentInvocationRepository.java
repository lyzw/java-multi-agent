package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.apache.ibatis.exceptions.PersistenceException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.orchestration.AgentInvocation;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.AgentInvocationMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.AgentInvocationDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AgentInvocationPort;

/**
 * {@link AgentInvocationPort} 的 MyBatis-Plus 实现（对应 {@code JdbcAgentInvocationRepository}）。
 *
 * <p>Port 签名零改动；六条 SQL 逐字搬迁到 {@code AgentInvocationMapper.xml}，仅 {@code ?} → {@code #{param}}。
 * 幂等插入（MA-T10）、终态 CAS 与三态细分（MA-T12）、在途接管提升、
 * 影子比对/逐行染色全部与原实现一致。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（bind/clear try-finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接，与原实现一致。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisAgentInvocationRepository implements AgentInvocationPort {

    /** 影子比对/染色的资源类型（与原实现逐字一致，policy-mapping.yaml: TENANT_ONLY + FORCE RLS）。 */
    private static final String RESOURCE_TYPE = "agent_invocation";

    private final AgentInvocationMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态接入（默认 null / false ⇒ 现有行为 100% 不变）──
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有单参 DataSource 构造口径，额外注入 Mapper）。 */
    public MybatisAgentInvocationRepository(@NonNull AgentInvocationMapper mapper,
                                            @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this(mapper, dataSource, null, null, false, "");
    }

    /** 影子态接线构造（测试与包内装配用；生产走 {@link #bindAuthzShadow} setter）。 */
    MybatisAgentInvocationRepository(@NonNull AgentInvocationMapper mapper,
                                     @NonNull DataSource dataSource,
                                     @Nullable ScopedDataAccess scoped,
                                     @Nullable AuthzShadowReporter reporter,
                                     boolean shadowEnabled,
                                     @NonNull String sentinelTenantId) {
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
    }

    @Override
    public boolean insert(@NonNull AgentInvocation invocation) {
        return inTenantScope(invocation.tenantId(), () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(invocation.tenantId()), conn ->
                        mapper.insertInvocation(
                                invocation.invocationId(), invocation.tenantId(), invocation.runId(),
                                invocation.parentInvocationId(), invocation.parentAgentId(),
                                invocation.parentAgentVersion(),
                                invocation.targetAgentId(), invocation.targetAgentVersion(),
                                invocation.requestedTargetAgentId(),
                                invocation.status().name(), invocation.depth(), invocation.traceId(),
                                invocation.inputDigest(), invocation.outputDigest(), invocation.reasonCode(),
                                invocation.fencingToken(), invocation.startedAt(), invocation.finishedAt(),
                                invocation.routingDecision(), invocation.policyRef(),
                                invocation.policyFingerprint()) > 0));
    }

    @Override
    public @NonNull Optional<AgentInvocation> find(@NonNull String tenantId,
                                                    @NonNull String invocationId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
                    shadowCompare(RESOURCE_TYPE, tenantId, true);
                    Optional<AgentInvocation> row = mapper.find(tenantId, invocationId)
                            .map(MybatisAgentInvocationRepository::toRecord);
                    // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                    row.ifPresent(r -> shadowTaint(RESOURCE_TYPE, tenantId, r.tenantId()));
                    return row;
                }));
    }

    @Override
    public @NonNull List<AgentInvocation> listByRun(@NonNull String tenantId,
                                                    @NonNull String runId) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
                    shadowCompare(RESOURCE_TYPE, tenantId, true);
                    List<AgentInvocation> rows = mapper.listByRun(tenantId, runId).stream()
                            .map(MybatisAgentInvocationRepository::toRecord).toList();
                    for (AgentInvocation row : rows) {
                        // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                        shadowTaint(RESOURCE_TYPE, tenantId, row.tenantId());
                    }
                    return rows;
                }));
    }

    @Override
    public int adoptInFlight(@NonNull String tenantId,
                             @NonNull String runId,
                             long newFencingToken) {
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn ->
                        mapper.adoptInFlight(tenantId, runId, newFencingToken)));
    }

    @Override
    public @NonNull TerminalWriteOutcome markTerminal(@NonNull String tenantId,
                                                      @NonNull String invocationId,
                                                      @NonNull AgentInvocationStatus status,
                                                      @Nullable String reasonCode,
                                                      @Nullable String outputDigest,
                                                      @Nullable Instant finishedAt,
                                                      long fencingToken) {
        if (!status.isTerminal()) {
            // ⚠️ 非终态不得走终态写入路径：那会把中间态固化成「结果」。
            throw new IllegalArgumentException("status 必须是终态: " + status);
        }
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    int rows = mapper.markTerminal(tenantId, invocationId, status.name(),
                            reasonCode, outputDigest, finishedAt, fencingToken);
                    if (rows > 0) {
                        return TerminalWriteOutcome.WRITTEN;
                    }
                    // ⚠️ 影响行数 0 有两种成因，必须区分（MA-T12）：
                    //    「已是终态」（幂等重放，返回既有终态）
                    //    vs 「token 过期」（旧世代越权提交，不得承认本次结果）。
                    return diagnoseMissedUpdate(tenantId, invocationId, fencingToken);
                }));
    }

    /** 诊断「CAS 未命中」的原因（对应旧 diagnoseMissedUpdate）。 */
    private @NonNull TerminalWriteOutcome diagnoseMissedUpdate(
            @NonNull String tenantId, @NonNull String invocationId, long fencingToken) {
        try {
            Optional<AgentInvocationDO> row = mapper.diagnose(tenantId, invocationId);
            if (row.isEmpty()) {
                return TerminalWriteOutcome.NOT_FOUND;
            }
            AgentInvocationDO d = row.get();
            AgentInvocationStatus parsed = AgentInvocationStatus.valueOf(d.getStatus());
            if (parsed.isTerminal()) {
                // ⚠️ 已是终态 → 幂等重放，不得覆盖既有终态
                return TerminalWriteOutcome.ALREADY_TERMINAL;
            }
            if (d.getFencingToken() != fencingToken) {
                // ⚠️ 旧世代带过期 token 提交 → 必须失败（MA-T12）
                return TerminalWriteOutcome.TOKEN_EXPIRED;
            }
            // ⚠️ 非终态且 token 匹配却没写进去：保守按 token 过期处理（不得承认本次结果）。
            return TerminalWriteOutcome.TOKEN_EXPIRED;
        } catch (PersistenceException e) {
            // ⚠️ 诊断读不到 → 保守按 token 过期处理（fail-closed）；消息与原实现逐字一致。
            throw new IllegalStateException("诊断终态写入失败: invocation=" + invocationId, e);
        }
    }

    // ── DO ↔ record 转换（逐字段，与旧 JDBC 的 map() 对齐）──

    private static AgentInvocation toRecord(AgentInvocationDO d) {
        return new AgentInvocation(
                d.getInvocationId(), d.getTenantId(), d.getRunId(),
                d.getParentInvocationId(), d.getParentAgentId(), d.getParentAgentVersion(),
                d.getTargetAgentId(), d.getTargetAgentVersion(), d.getRequestedTargetAgentId(),
                AgentInvocationStatus.valueOf(d.getStatus()), d.getDepth(), d.getTraceId(),
                d.getInputDigest(), d.getOutputDigest(), d.getStartedAt(), d.getFinishedAt(),
                d.getReasonCode(), d.getFencingToken(), d.getRoutingDecision(),
                d.getPolicyRef(), d.getPolicyFingerprint());
    }

    /** 在租户作用域内执行：绑定 -> 执行 -> 无条件清理（try-finally）。 */
    private <T> T inTenantScope(@NonNull String tenantId, Supplier<T> action) {
        TenantScope.bind(tenantId); // 来自认证上下文的 tenantId，非客户端输入
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口：容器里存在 {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     */
    @Autowired(required = false)
    public void bindAuthzShadow(@Nullable AuthzShadowBinding binding) {
        if (binding == null) {
            return;
        }
        this.scoped = binding.scoped();
        this.reporter = binding.reporter();
        this.shadowEnabled = true;
        this.sentinelTenantId = binding.sentinelTenantId();
    }

    /** 影子比对：Gate 算出的 ScopeFilter vs 本次查询的绑定租户（只记日志）。 */
    void shadowCompare(@NonNull String resourceType,
                       @NonNull String boundTenantId,
                       boolean hasTenantPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(boundTenantId), boundTenantId, hasTenantPredicate);
    }

    /**
     * 逐行染色：结果行的真实 tenant_id 与 Gate 算出的 ScopeFilter 比对。
     *
     * <p>主体是发起本次查询的租户，行归属从结果行读出的 tenant_id（防同义反复）。
     */
    void shadowTaint(@NonNull String resourceType,
                     @Nullable String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        boolean shared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                subjectTenantId == null ? null : SubjectContext.forTenant(subjectTenantId),
                rowTenantId, shared);
    }
}
