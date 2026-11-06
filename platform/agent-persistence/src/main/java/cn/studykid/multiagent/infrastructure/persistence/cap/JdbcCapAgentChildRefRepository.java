package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import cn.studykid.multiagent.core.orchestration.CapAgentChildRef;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.CapAgentChildRefPort;

/**
 * {@link CapAgentChildRefPort} 的 PostgreSQL 实现（多级 Agent v1.1 · 06 变更补充「关系数据」）。
 *
 * <p>⚠️ <b>幂等插入</b>：{@code ON CONFLICT DO NOTHING} —— 同一父 release 重复投影
 * 同一子 release 不产生第二行（唯一约束同时防止「同一父 release 重复声明同一子 release」）。
 */
public class JdbcCapAgentChildRefRepository implements CapAgentChildRefPort {

    /** 影子比对/染色的资源类型（P0-1b 第 1 批）。 */
    private static final String RESOURCE_TYPE = "cap_agent_child_ref";

    private static final String SQL_INSERT = """
            INSERT INTO cap_agent_child_ref
                (tenant_id, parent_agent_id, parent_release_version,
                 child_agent_id, child_release_version, ordinal, required, created_at)
            VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, parent_agent_id, parent_release_version,
                         child_agent_id, child_release_version) DO NOTHING
            """;

    private static final String SQL_LIST_BY_PARENT = """
            SELECT tenant_id::text, parent_agent_id::text, parent_release_version,
                   child_agent_id::text, child_release_version, ordinal, required, created_at
            FROM cap_agent_child_ref
            WHERE tenant_id = ?::uuid AND parent_agent_id = ?::uuid
              AND parent_release_version = ?
            ORDER BY ordinal ASC
            """;

    private static final String SQL_LIST_BY_PARENT_AGENT = """
            SELECT tenant_id::text, parent_agent_id::text, parent_release_version,
                   child_agent_id::text, child_release_version, ordinal, required, created_at
            FROM cap_agent_child_ref
            WHERE tenant_id = ?::uuid AND parent_agent_id = ?::uuid
            ORDER BY parent_release_version ASC, ordinal ASC
            """;

    private static final String SQL_LIST_BY_CHILD = """
            SELECT tenant_id::text, parent_agent_id::text, parent_release_version,
                   child_agent_id::text, child_release_version, ordinal, required, created_at
            FROM cap_agent_child_ref
            WHERE tenant_id = ?::uuid AND child_agent_id = ?::uuid
            ORDER BY parent_agent_id ASC, parent_release_version ASC, ordinal ASC
            """;

    private static final String SQL_LIST_BY_TENANT = """
            SELECT tenant_id::text, parent_agent_id::text, parent_release_version,
                   child_agent_id::text, child_release_version, ordinal, required, created_at
            FROM cap_agent_child_ref
            WHERE tenant_id = ?::uuid
            ORDER BY parent_agent_id ASC, parent_release_version ASC, ordinal ASC
            """;

    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2；阶段一 P0-1b 第 1 批）────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    // ⚠️ 非 final：由 Spring 启动期通过 {@link #bindAuthzShadow} 一次性绑定，之后只读。
    //    容器里没有 AuthzShadowBinding 时保持构造期默认值（null / false）⇒ 零行为变更。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    public JdbcCapAgentChildRefRepository(@NonNull DataSource dataSource) {
        this(dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     *
     * @param shadowEnabled    是否启用影子比对 + 染色（P1 默认 false；P2 翻转为 true 进入阻断模式）
     * @param sentinelTenantId 哨兵（共享）租户标识，与 {@code SqlScopeTranslator} 共用配置项
     */
    JdbcCapAgentChildRefRepository(@NonNull DataSource dataSource,
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
    public boolean insert(@NonNull CapAgentChildRef ref) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(ref.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, ref.tenantId());
                        ps.setString(2, ref.parentAgentId());
                        ps.setInt(3, ref.parentReleaseVersion());
                        ps.setString(4, ref.childAgentId());
                        ps.setInt(5, ref.childReleaseVersion());
                        ps.setInt(6, ref.ordinal());
                        ps.setBoolean(7, ref.required());
                        ps.setTimestamp(8, Timestamp.from(ref.createdAt()));
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public @NonNull List<CapAgentChildRef> listByParentRelease(@NonNull String tenantId,
                                                                @NonNull String parentAgentId,
                                                                int parentReleaseVersion) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<CapAgentChildRef> rows = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_BY_PARENT)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, parentAgentId);
                        ps.setInt(3, parentReleaseVersion);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                CapAgentChildRef d = map(rs);
                                rows.add(d);
                                shadowTaint(RESOURCE_TYPE, tenantId, d.tenantId());
                            }
                        }
                    }
                    return rows;
                });
    }

    @Override
    public @NonNull List<CapAgentChildRef> listByParentAgent(@NonNull String tenantId,
                                                              @NonNull String parentAgentId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<CapAgentChildRef> rows = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_BY_PARENT_AGENT)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, parentAgentId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                CapAgentChildRef d = map(rs);
                                rows.add(d);
                                shadowTaint(RESOURCE_TYPE, tenantId, d.tenantId());
                            }
                        }
                    }
                    return rows;
                });
    }

    @Override
    public @NonNull List<CapAgentChildRef> listByChildAgent(@NonNull String tenantId,
                                                              @NonNull String childAgentId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<CapAgentChildRef> rows = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_BY_CHILD)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, childAgentId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                CapAgentChildRef d = map(rs);
                                rows.add(d);
                                shadowTaint(RESOURCE_TYPE, tenantId, d.tenantId());
                            }
                        }
                    }
                    return rows;
                });
    }

    @Override
    public @NonNull List<CapAgentChildRef> listByTenant(@NonNull String tenantId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<CapAgentChildRef> rows = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_BY_TENANT)) {
                        ps.setString(1, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                CapAgentChildRef d = map(rs);
                                rows.add(d);
                                shadowTaint(RESOURCE_TYPE, tenantId, d.tenantId());
                            }
                        }
                    }
                    return rows;
                });
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>⚠️ 用 setter 而不是可选构造参数：本仓既有构造签名被单测与 {@code @Bean} 装配依赖，
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
        boolean isShared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                SubjectContext.forTenant(subjectTenantId), rowTenantId, isShared);
    }

    private static CapAgentChildRef map(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new CapAgentChildRef(
                rs.getString("tenant_id"),
                rs.getString("parent_agent_id"),
                rs.getInt("parent_release_version"),
                rs.getString("child_agent_id"),
                rs.getInt("child_release_version"),
                rs.getInt("ordinal"),
                rs.getBoolean("required"),
                createdAt == null ? java.time.Instant.EPOCH : createdAt.toInstant());
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }
}
