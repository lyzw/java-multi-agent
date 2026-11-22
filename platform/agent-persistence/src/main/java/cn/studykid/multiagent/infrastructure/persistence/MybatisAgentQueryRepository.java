package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.AgentQueryMapper;
import cn.studykid.multiagent.infrastructure.persistence.model.AgentSummaryDO;
import cn.studykid.multiagent.infrastructure.persistence.model.AgentVersionViewDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentItem;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentVersionItem;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * {@link AgentQueryPort} 的 MyBatis-Plus 实现（对应 {@code JdbcAgentQueryRepository}）。
 *
 * <p>Port 签名零改动；三条 SQL 逐字搬迁到 {@code AgentQueryMapper.xml}，
 * 仅 {@code ?} → {@code #{param}}。{@code LEFT JOIN LATERAL} 一次取全、
 * 全序游标（{@code created_at, agent_id}）、{@code limit+1} 探底全部保留。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（bind/clear try-finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接。
 *
 * <p><b>P1 影子态</b>：{@code shadowCompare} 在查询入口、{@code shadowTaint} 在结果行上
 * 逐条调用，主体（本次查询租户）与行归属（结果行 tenant_id）分开传，
 * 与旧实现逐字一致；未注入 {@code AuthzShadowBinding} 时钩子内部直接返回。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisAgentQueryRepository implements AgentQueryPort {

    private final AgentQueryMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId = "";

    public MybatisAgentQueryRepository(@NonNull AgentQueryMapper mapper,
                                       @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
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

    @Override
    public @NonNull Page<AgentItem> listAgents(@NonNull String tenantId,
                                               @Nullable String cursor,
                                               int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        // 游标解码保持原实现语义：非法游标抛 IllegalArgumentException（调用方转 400）
        PageCursor after = cursor == null || cursor.isBlank()
                ? null : PageCursor.decode(cursor);

        shadowCompare("agent", tenantId, true);
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> {
                        List<AgentSummaryDO> rows = mapper.listAgents(tenantId,
                                after == null ? null : after.createdAt(),
                                after == null ? null : after.id(),
                                limit + 1);
                        List<AgentItem> items = new ArrayList<>(limit);
                        // ⚠️ 游标需要 (created_at, agent_id)；AgentItem 不含 createdAt，
                        //    故在迭代时单独捕获最后一行的键，避免改动端口契约。
                        Instant lastCreatedAt = null;
                        String lastAgentId = null;
                        for (AgentSummaryDO d : rows) {
                            items.add(toItem(d));
                            // ⚠️ 主体 = 本次查询的租户；行归属 = 结果行真实 tenant_id（D6/D13）
                            shadowTaint("agent", tenantId, tenantId);
                            // D13：JOIN 从表 agent_version 的行归属单独染色（无版本时为 null → 跳过）
                            shadowTaint("agent_version", tenantId, d.getJoinTenantId());
                            lastCreatedAt = d.getCreatedAt();
                            lastAgentId = d.getAgentId();
                        }
                        String next = null;
                        if (items.size() > limit) {
                            // 与原实现逐字一致：多取的一行只用于判定「有下一页」，
                            // 游标取该多取行的键（既有行为，迁移不改语义）。
                            items.remove(items.size() - 1);
                            next = new PageCursor(lastCreatedAt, lastAgentId).encode();
                        }
                        return Page.of(items, next);
                    }));
        } catch (RuntimeException e) {
            if (isSqlFailure(e)) {
                throw new IllegalStateException("查询 Agent 列表失败", e);
            }
            throw e;
        }
    }

    @Override
    public @Nullable AgentItem findAgent(@NonNull String tenantId, @NonNull String agentId) {
        shadowCompare("agent", tenantId, true);
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> {
                        Optional<AgentSummaryDO> row = mapper.findAgent(tenantId, agentId);
                        if (row.isEmpty()) {
                            return null;
                        }
                        AgentSummaryDO d = row.get();
                        shadowTaint("agent", tenantId, tenantId);
                        shadowTaint("agent_version", tenantId, d.getJoinTenantId());
                        return toItem(d);
                    }));
        } catch (RuntimeException e) {
            if (isSqlFailure(e)) {
                throw new IllegalStateException("查询 Agent 失败", e);
            }
            throw e;
        }
    }

    @Override
    public @NonNull List<AgentVersionItem> listVersions(@NonNull String tenantId,
                                                       @NonNull String agentId) {
        shadowCompare("agent_version", tenantId, true);
        // ⚠️ agent_version 已是全局表，租户可见性由 XML 里 EXISTS 的
        //    tenant_agent_binding ACTIVE 绑定收口。
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> {
                        List<AgentVersionItem> out = new ArrayList<>();
                        for (AgentVersionViewDO d : mapper.listVersions(agentId, tenantId)) {
                            out.add(new AgentVersionItem(
                                    d.getVersionId(),
                                    d.getVersionNumber(),
                                    d.getDefinition(),
                                    d.getPublishedAt(),
                                    d.getCreatedAt()));
                            shadowTaint("agent_version", tenantId, tenantId);
                        }
                        return out;
                    }));
        } catch (RuntimeException e) {
            if (isSqlFailure(e)) {
                throw new IllegalStateException("查询 Agent 版本历史失败", e);
            }
            throw e;
        }
    }

    /** DO → 端口记录（逐字段，与旧 JDBC 的 {@code read(rs)} 对齐）。 */
    private static AgentItem toItem(AgentSummaryDO d) {
        // ⚠️ 原实现用 rs.getInt：SQL NULL 回落 0。version_number 约束 > 0，
        //    故 0 即「无版本」，与「有版本 0」不冲突。
        int latestVersionNumber =
                d.getLatestVersionNumber() == null ? 0 : d.getLatestVersionNumber();
        return new AgentItem(
                d.getAgentId(),
                d.getName(),
                d.getDescription(),
                d.getVersionCount(),
                latestVersionNumber,
                d.getLatestDefinition(),
                d.getLatestPublishedAt());
    }

    /** 在租户作用域内执行：绑定 → 执行 → 无条件清理。 */
    private <T> T inTenantScope(@NonNull String tenantId, Supplier<T> action) {
        TenantScope.bind(tenantId); // 来自认证上下文的 tenantId，非客户端输入
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }

    @Override
    public int deleteUnpublishedVersions(@NonNull String tenantId, @NonNull String agentId) {
        shadowCompare("agent_version", tenantId, true);
        // ⚠️ 仅删未发布草稿：已发布版本（线上生效过）不可动 —— 保留历史可解释性。
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn ->
                            mapper.deleteUnpublishedVersions(agentId, tenantId)));
        } catch (RuntimeException e) {
            if (isSqlFailure(e)) {
                throw new IllegalStateException("清理 Agent 未发布草稿失败", e);
            }
            throw e;
        }
    }

    /** 系统身份仅用于绑定 RLS 上下文，不携带任何角色权限。 */
    private static TenantContext context(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }

    /** MyBatis 把 SQLException 包进 PersistenceException；沿 cause 链识别以免误伤业务异常。 */
    private static boolean isSqlFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException) {
                return true;
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /** 影子比对：Gate 算出的 ScopeFilter vs 本次查询的绑定租户（只记日志）。 */
    void shadowCompare(@NonNull String resourceType, @NonNull String tenantId,
                       boolean hasPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(tenantId), tenantId, hasPredicate);
    }

    /** 逐行染色：结果行的真实租户归属与 Gate 算出的 ScopeFilter 比对。 */
    void shadowTaint(@NonNull String resourceType,
                     @NonNull String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        // 哨兵行可由「行租户 == 哨兵租户」直接推断，调用方传的 shared 只作补充。
        boolean isShared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                SubjectContext.forTenant(subjectTenantId), rowTenantId, isShared);
    }
}
