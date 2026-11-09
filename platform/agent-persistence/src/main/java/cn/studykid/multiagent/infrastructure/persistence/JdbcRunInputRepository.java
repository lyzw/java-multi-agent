package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.RunInputPort;

/**
 * {@link RunInputPort} 的 JDBC 实现（L-4 闭合）。
 *
 * <p>⚠️ <b>{@code tenant_id} 条件不可删除</b>：与 {@code JdbcRunQueryRepository}
 * 同一铁律 —— RLS 之外应用侧条件是第一道防线。
 */
@Repository
public class JdbcRunInputRepository implements RunInputPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private static final String SQL_FIND_INPUT = """
            SELECT tenant_id, input_payload::text FROM run
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            """;

    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有单参构造，确保 @Repository 装配不变）。 */
    @Autowired
    public JdbcRunInputRepository(@NonNull DataSource dataSource) {
        this(dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     */
    JdbcRunInputRepository(@NonNull DataSource dataSource,
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
    public @NonNull Map<String, Object> inputOf(@NonNull String tenantId,
                                                @NonNull String runId) {
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
        shadowCompare("run", tenantId, true);
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(SQL_FIND_INPUT)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Map.of();
                    }
                    // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                    String rowTenant = rs.getString("tenant_id");
                    Map<String, Object> input = parse(rs.getString("input_payload"));
                    shadowTaint("run", tenantId, rowTenant);
                    return input;
                }
            } finally {
                lease.rollbackQuietly();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Run 输入失败", e);
        }
    }

    /**
     * JSONB 文本 → Map。
     *
     * <p>⚠️ 解析失败返回空 Map 而不是抛异常：坏输入属于<b>该 Run 的问题</b>，
     * 由执行侧按「无输入」拒绝执行并落终态，比在读取层抛一串栈更可归因。
     */
    private static Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * 取得已绑定租户的连接。
     *
     * <p>⚠️ 外层有事务则复用，否则自开并绑定租户（同 {@code JdbcRunQueryRepository}）：
     * 未绑定上下文时 RLS 默认拒绝，读取会静默返回 0 行。
     */
    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        java.sql.Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, systemContext(tenantId));
    }

    /** 系统身份仅用于绑定 RLS 上下文，不携带任何角色权限。 */
    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭。
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
     * <p>⚠️ <b>主体与行归属必须分开</b>（12 文 §4.5 · D6/D13）。
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
