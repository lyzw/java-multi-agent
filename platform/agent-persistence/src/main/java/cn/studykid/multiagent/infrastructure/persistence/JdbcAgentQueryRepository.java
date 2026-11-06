package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.page.PageCursor;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentItem;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * Agent 查询仓储（P4-D14 / G2 · FE-01 §P18）。
 *
 * <p>⚠️ <b>为什么走 LATERAL 一次取全</b>：每个 Agent 都要带「最新版本摘要」
 * （版本数 / 最新版本号 / definition / 发布时间）。用 {@code LEFT JOIN LATERAL}
 * 在一条语句里把「该 agent 的最新版本」算出来，避免 N+1 查询，
 * 也避免「先取 agent 再逐条查 version」的并发窗口。
 *
 * <p>⚠️ <b>version_number 为 0 的语义</b>：PG 约束 {@code version_number > 0}，
 * 所以 {@code 0} 唯一表示「该 agent 尚无任何版本」，与「有版本 0」不冲突。
 *
 * <p>⚠️ 全部走 {@code tenantConnection} 绑定 RLS；禁止直接 {@code dataSource.getConnection()}。
 */
@Repository
public class JdbcAgentQueryRepository implements AgentQueryPort {

    private static final String SQL_SELECT = """
            SELECT a.agent_id,
                   a.tenant_id::text                                          AS tenant_id,
                   a.created_at                                                AS created_at,
                   a.name,
                   a.description,
                   COALESCE(v.version_count, 0)                            AS version_count,
                   v.latest_version_number,
                   v.latest_definition,
                   v.latest_published_at,
                   v.join_tenant_id
            FROM agent a
            LEFT JOIN LATERAL (
                SELECT COUNT(*)                                                   AS version_count,
                       MAX(av.version_number)                                     AS latest_version_number,
                       (ARRAY_AGG(av.definition ORDER BY av.version_number DESC))[1] AS latest_definition,
                       MAX(av.published_at)                                       AS latest_published_at,
                       -- D13：JOIN 从表的行归属必须能被染色（否则从表越权不可观测）
                       -- ⚠️ 必须 MAX(av.tenant_id::text)：PG 没有 max(uuid) 聚合函数
                       MAX(av.tenant_id::text)                                    AS join_tenant_id
                FROM agent_version av
                WHERE av.tenant_id = a.tenant_id AND av.agent_id = a.agent_id
            ) v ON true
            WHERE a.tenant_id = ?::uuid
            """;

    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    // ⚠️ 非 final：由 Spring 启动期通过 {@link #bindAuthzShadow} 一次性绑定，之后只读。
    //    容器里没有 AuthzShadowBinding 时保持构造期默认值（null / false）⇒ 零行为变更。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有单参构造，确保既有的 @Repository 装配不变）。 */
    @Autowired
    public JdbcAgentQueryRepository(@NonNull DataSource dataSource) {
        this(dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthConfiguration} 在开启染色时调用；测试用）。
     *
     * @param shadowEnabled 是否启用影子比对 + 染色（P1 默认 false；P2 翻转为 true 进入阻断模式）
     * @param sentinelTenantId 哨兵（共享）租户标识，与 {@code SqlScopeTranslator} 共用配置项
     */
    JdbcAgentQueryRepository(@NonNull DataSource dataSource,
                              @Nullable ScopedDataAccess scoped,
                              @Nullable AuthzShadowReporter reporter,
                              boolean shadowEnabled,
                              @NonNull String sentinelTenantId) {
        this.dataSource = dataSource;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
    }

    @Override
    public @NonNull Page<AgentItem> listAgents(@NonNull String tenantId,
                                               @Nullable String cursor,
                                               int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        PageCursor after = cursor == null || cursor.isBlank()
                ? null : PageCursor.decode(cursor);

        shadowCompare("agent", tenantId, true);
        StringBuilder sql = new StringBuilder(SQL_SELECT);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (after != null) {
            // ⚠️ 全序游标 = (created_at, agent_id)，两个分量都参与比较
            sql.append(" AND (a.created_at < ?"
                    + " OR (a.created_at = ? AND a.agent_id < ?::uuid))");
            params.add(Timestamp.from(after.createdAt()));
            params.add(Timestamp.from(after.createdAt()));
            params.add(after.id());
        }
        sql.append(" ORDER BY a.created_at DESC, a.agent_id DESC LIMIT ?");
        params.add(limit + 1);

        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            List<AgentItem> rows = new ArrayList<>(limit);
            // ⚠️ 游标需要 (created_at, agent_id)；AgentItem 不含 createdAt，
            //    故在迭代时单独捕获最后一行的时间戳与主键，避免改动端口契约。
            Instant lastCreatedAt = null;
            String lastAgentId = null;
            try (PreparedStatement ps = lease.connection().prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(read(rs));
                    // ⚠️ 主体 = 本次查询的租户；行归属 = 结果行真实 tenant_id（D6/D13）
                    shadowTaint("agent", tenantId, rs.getString("tenant_id"));
                    // D13：JOIN 从表 agent_version 的行归属单独染色（无版本时为 null → 跳过）
                    shadowTaint("agent_version", tenantId, rs.getString("join_tenant_id"));
                    lastCreatedAt = rs.getTimestamp("created_at").toInstant();
                    lastAgentId = rs.getString("agent_id");
                }
                }
            }
            String next = null;
            if (rows.size() > limit) {
                rows.remove(rows.size() - 1);
                next = new PageCursor(lastCreatedAt, lastAgentId).encode();
            }
            return Page.of(rows, next);
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Agent 列表失败", e);
        }
    }

    @Override
    public @Nullable AgentItem findAgent(@NonNull String tenantId, @NonNull String agentId) {
        shadowCompare("agent", tenantId, true);
        String sql = SQL_SELECT + " AND a.agent_id = ?::uuid";
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, agentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        shadowTaint("agent", tenantId, rs.getString("tenant_id"));
                        shadowTaint("agent_version", tenantId, rs.getString("join_tenant_id"));
                        return read(rs);
                    }
                    return null;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Agent 失败", e);
        }
    }

    @Override
    public @NonNull List<AgentVersionItem> listVersions(@NonNull String tenantId,
                                                       @NonNull String agentId) {
        shadowCompare("agent_version", tenantId, true);
        // ⚠️ 走 tenantConnection 绑定 RLS：agent_version 是租户隔离实体
        String sql = """
                SELECT version_id, tenant_id::text AS tenant_id, version_number, definition,
                       published_at, created_at
                FROM agent_version
                WHERE tenant_id = ?::uuid AND agent_id = ?::uuid
                ORDER BY version_number DESC
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            List<AgentVersionItem> rows = new ArrayList<>();
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, agentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new AgentVersionItem(
                                rs.getString("version_id"),
                                rs.getInt("version_number"),
                                rs.getString("definition"),
                                instantOrNull(rs.getTimestamp("published_at")),
                                rs.getTimestamp("created_at").toInstant()));
                        shadowTaint("agent_version", tenantId, rs.getString("tenant_id"));
                    }
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Agent 版本历史失败", e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>⚠️ 用 setter 而不是可选构造参数：本仓既有构造签名被大量单测与 {@code @Bean} 装配依赖，
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

    void shadowCompare(@NonNull String resourceType, @NonNull String tenantId, boolean hasPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(tenantId), tenantId, hasPredicate);
    }

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

    private AgentItem read(ResultSet rs) throws SQLException {
        // ⚠️ getInt 对 NULL 返回 0；version_number 约束 > 0，故 0 即「无版本」
        int latestVersionNumber = rs.getInt("latest_version_number");
        return new AgentItem(
                rs.getString("agent_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("version_count"),
                latestVersionNumber,
                rs.getString("latest_definition"),
                instantOrNull(rs.getTimestamp("latest_published_at")));
    }

    @Nullable
    private static Instant instantOrNull(@Nullable Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, new TenantContext(tenantId, "system", java.util.Set.of(), 0L));
    }
}
