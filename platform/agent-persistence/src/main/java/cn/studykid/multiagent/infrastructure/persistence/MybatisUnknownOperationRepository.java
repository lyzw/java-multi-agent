package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.UnknownOperationMapper;
import cn.studykid.multiagent.infrastructure.persistence.model.ToolOperationDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.UnknownOperationPort;

/**
 * {@link UnknownOperationPort} 的 MyBatis-Plus 实现（对应 {@code JdbcUnknownOperationRepository}）。
 *
 * <p>Port 签名零改动；四条 SQL 逐字搬迁到 {@code UnknownOperationMapper.xml}，
 * 仅 {@code ?} → {@code #{param}}。游标分页（limit+1 探底、全序排序）、
 * {@code FOR UPDATE} 加锁读、{@code ::tool_operation_state} cast、审计 fail-closed 全部保留。
 *
 * <p>⛔ <b>本类不存在任何把状态改回 {@code PENDING}/{@code IN_FLIGHT} 的路径</b>：
 * UNKNOWN 的定义是「副作用是否发生<b>未知</b>」，重放 = 可能重复施加副作用（Sev-0）。
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
public class MybatisUnknownOperationRepository implements UnknownOperationPort {

    private final UnknownOperationMapper mapper;
    private final DataSource dataSource;
    private final AuditPort audit;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    // ⚠️ 非 final：由 Spring 启动期通过 {@link #bindAuthzShadow} 一次性绑定，之后只读。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有 DataSource + AuditPort 依赖口径，额外注入 Mapper）。 */
    @Autowired
    public MybatisUnknownOperationRepository(@NonNull UnknownOperationMapper mapper,
                                             @Qualifier("dataSource") @NonNull DataSource dataSource,
                                             @NonNull AuditPort audit) {
        this(mapper, dataSource, audit, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     *
     * @param shadowEnabled 是否启用影子比对 + 染色（P1 默认 false；P2 翻转为 true 进入阻断模式）
     * @param sentinelTenantId 哨兵（共享）租户标识，与 {@code SqlScopeTranslator} 共用配置项
     */
    MybatisUnknownOperationRepository(@NonNull UnknownOperationMapper mapper,
                                      @NonNull DataSource dataSource,
                                      @NonNull AuditPort audit,
                                      @Nullable ScopedDataAccess scoped,
                                      @Nullable AuthzShadowReporter reporter,
                                      boolean shadowEnabled,
                                      @NonNull String sentinelTenantId) {
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.audit = audit;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
    }

    @Override
    public @NonNull Page<OperationItem> list(@NonNull String tenantId,
                                             @Nullable String state,
                                             @Nullable String cursor,
                                             int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        PageCursor after = cursor == null || cursor.isBlank()
                ? null : PageCursor.decode(cursor);

        // 与旧实现逐字一致：时间条件传 Timestamp（驱动 setTimestamp 语义不变）。
        Timestamp cursorCreatedAt = after == null ? null : Timestamp.from(after.createdAt());
        String cursorId = after == null ? null : after.id();

        // ⚠️ P1 影子比对：本次查询绑定租户 vs Gate 算出的 ScopeFilter（只记日志）。
        shadowCompare("tool_operation", tenantId, true);
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, tenantContext(tenantId), conn -> {
                        List<OperationItem> rows = new ArrayList<>(limit);
                        for (ToolOperationDO row : mapper.list(
                                tenantId, state, cursorCreatedAt, cursorId, limit + 1)) {
                            // ⚠️ 主体 = 本次查询的租户；行归属 = 结果行真实 tenant_id。
                            //    两者必须分开传：用行租户当主体是同义反复（恒不违例）。
                            String rowTenant = row.getTenantId();
                            rows.add(read(row));
                            shadowTaint("tool_operation", tenantId, rowTenant);
                        }
                        String next = null;
                        if (rows.size() > limit) {
                            rows.remove(rows.size() - 1);
                            OperationItem last = rows.get(rows.size() - 1);
                            next = new PageCursor(last.createdAt(), last.operationId()).encode();
                        }
                        return Page.of(rows, next);
                    }));
        } catch (PersistenceException e) {
            // ⚠️ 旧实现 catch(SQLException) -> IllegalStateException("查询操作列表失败", e)，逐字保留。
            throw new IllegalStateException("查询操作列表失败", e);
        }
    }

    @Override
    public @Nullable OperationItem find(@NonNull String tenantId, @NonNull String operationId) {
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter。
        shadowCompare("tool_operation", tenantId, true);
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, tenantContext(tenantId), conn -> {
                        ToolOperationDO row = mapper.find(tenantId, operationId);
                        if (row == null) {
                            // ⚠️ 不存在与「不属于该租户」返回同一个 null（同源纪律）。
                            return null;
                        }
                        String rowTenant = row.getTenantId();
                        OperationItem item = read(row);
                        shadowTaint("tool_operation", tenantId, rowTenant);
                        return item;
                    }));
        } catch (PersistenceException e) {
            // ⚠️ 旧实现 catch(SQLException) -> IllegalStateException("查询操作失败", e)，逐字保留。
            throw new IllegalStateException("查询操作失败", e);
        }
    }

    @Override
    public @NonNull DispositionResult dispose(@NonNull String tenantId,
                                              @NonNull String operationId,
                                              @NonNull Disposition disposition,
                                              @NonNull String operatorId,
                                              @Nullable String note) {
        // 行锁读 + 状态校验 + 更新在同一事务内；提交后才写审计（与旧实现同序）。
        try {
            Disposed disposed = inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, tenantContext(tenantId), conn -> {
                        OperationItem current = lockAndRead(tenantId, operationId);
                        if (current == null) {
                            throw new UnknownOperationPort.OperationNotFoundException(operationId);
                        }
                        // ⛔ 非 UNKNOWN 态一律拒绝：重复处置、或已被 P3-D03 自动对账接管
                        if (!"UNKNOWN".equals(current.state())) {
                            throw new NotInUnknownStateException(current.state());
                        }
                        Instant now = Instant.now();
                        update(tenantId, operationId, disposition, operatorId, note, now);
                        return new Disposed(current, now);
                    }));

            audit.recordRequired(tenantId, operatorId, AuditPort.Actions.UNKNOWN_DISPOSE,
                    "tool_operation", operationId,
                    Map.of("disposition", disposition.name(),
                            "runId", disposed.item().runId(),
                            "toolName", disposed.item().toolName()));
            return new DispositionResult(operationId, disposition.name(), operatorId, disposed.at());
        } catch (PersistenceException e) {
            // ⚠️ 旧实现 catch(SQLException) -> IllegalStateException("UNKNOWN 处置失败", e)，逐字保留。
            throw new IllegalStateException("UNKNOWN 处置失败", e);
        }
    }

    private @Nullable OperationItem lockAndRead(@NonNull String tenantId,
                                                @NonNull String operationId) {
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter。
        shadowCompare("tool_operation", tenantId, true);
        ToolOperationDO row = mapper.lockAndRead(tenantId, operationId);
        if (row == null) {
            return null;
        }
        String rowTenant = row.getTenantId();
        OperationItem item = read(row);
        shadowTaint("tool_operation", tenantId, rowTenant);
        return item;
    }

    private void update(@NonNull String tenantId,
                        @NonNull String operationId,
                        @NonNull Disposition disposition,
                        @NonNull String operatorId,
                        @Nullable String note,
                        @NonNull Instant now) {
        // ⚠️ reconciliation_source='MANUAL' 与 P3-D03 的自动对账来源可区分，
        //    事后能回答「这条是系统判的，还是人判的」。
        int n = mapper.updateDisposition(tenantId, operationId, disposition.name(),
                Timestamp.from(now), note == null ? operatorId : operatorId + ": " + note);
        if (n != 1) {
            throw new IllegalStateException("操作更新行数异常: " + n);
        }
    }

    private OperationItem read(ToolOperationDO row) {
        return new OperationItem(
                row.getOperationId(),
                row.getRunId(),
                row.getToolName(),
                row.getRiskLevel(),
                row.getState(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getReconciliationSource(),
                row.getManualDisposedAt() != null);
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

    /** 处置事务内的中间结果（提交后才用于写审计与组装返回值）。 */
    private record Disposed(@NonNull OperationItem item, @NonNull Instant at) {
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>⚠️ 用 setter 而不是可选构造参数：本仓既有构造签名被 {@code @Repository} 装配依赖，
     * 改成可选构造参数会把「影子态」渗进每一个调用点。setter 只在启动期被调用一次。
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
     * 逐行染色：结果行的真实 {@code tenant_id} 与 Gate 算出的 ScopeFilter 比对。
     *
     * <p>⚠️ <b>主体与行归属必须分开</b>（12 文 §4.5 · D6/D13）：
     * 主体是<b>发起本次查询的租户</b>，行归属从<b>结果行</b>读出的 {@code tenant_id}。
     * 若把行租户当作主体（{@code SubjectContext.forTenant(rowTenantId)}），
     * 策略引擎必然算出 {@code TenantOnly(行租户)}，逐行比对恒通过 ——
     * 染色会退化成「永远 0 违例」的装饰。
     *
     * @param subjectTenantId 发起查询的租户（主体）
     * @param rowTenantId 结果行的真实租户（不可用查询绑定租户代替，否则是同义反复）
     */
    void shadowTaint(@NonNull String resourceType,
                     @Nullable String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        // 哨兵行可由「行租户 == 哨兵租户」直接推断，无需调用方另行声明。
        boolean shared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                subjectTenantId == null ? null : SubjectContext.forTenant(subjectTenantId),
                rowTenantId, shared);
    }
}
