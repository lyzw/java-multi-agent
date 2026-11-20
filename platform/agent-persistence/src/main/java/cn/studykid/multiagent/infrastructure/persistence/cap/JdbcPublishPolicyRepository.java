package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.PublishPolicy;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TenantConnectionLease;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.PublishPolicyPort;

/**
 * 发布策略配置 JDBC 仓储（cap_publish_policy）。
 *
 * <p>⚠️ 优先级解析由应用层 {@code PublishPolicyService} 负责，本仓储只做存储 + 按
 * (scope, tenant_id) 列出，不做排序/筛选。
 */
@Repository
public class JdbcPublishPolicyRepository implements PublishPolicyPort {

    /** PLATFORM 作用域的租户哨兵（与 {@link JdbcConfigPublishRequestRepository} 同款）。 */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private final DataSource dataSource;

    public JdbcPublishPolicyRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull PublishPolicy insert(@NonNull PublishPolicy policy) {
        return TransactionalConnectionHolder.inTransaction(dataSource,
                tenantContext(policy.tenantId()),
                conn -> {
                    String sql = """
                            INSERT INTO cap_publish_policy
                                (policy_id, scope, tenant_id, policy_level, resource_kind,
                                 resource_id, publish_mode, enabled, updated_by)
                            VALUES (?, ?, ?::uuid, ?, ?, ?, ?, ?, ?)
                            RETURNING created_at, updated_at
                            """;
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        int i = 1;
                        ps.setObject(i++, policy.policyId());
                        ps.setString(i++, policy.scope());
                        bindUuid(ps, i++, policy.tenantId());
                        ps.setString(i++, policy.policyLevel());
                        bindNullableString(ps, i++, policy.resourceKind());
                        bindNullableString(ps, i++, policy.resourceId());
                        ps.setString(i++, policy.publishMode());
                        ps.setBoolean(i++, policy.enabled());
                        ps.setString(i++, policy.updatedBy());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new IllegalStateException("cap_publish_policy INSERT 未返回 created_at");
                            return policy.withTimestamps(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant());
                        }
                    }
                });
    }

    @Override
    public @NonNull Optional<PublishPolicy> findById(@NonNull String scope,
                                                      @Nullable String tenantId,
                                                      @NonNull UUID policyId) {
        String sql = selectColumns() + """
                WHERE scope = ?
                  AND tenant_id IS NOT DISTINCT FROM ?::uuid
                  AND policy_id = ?::uuid
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, scope);
                bindUuid(ps, i++, tenantId);
                ps.setObject(i++, policyId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询发布策略失败", e);
        }
    }

    @Override
    public @NonNull Optional<PublishPolicy> update(@NonNull PublishPolicy policy) {
        return TransactionalConnectionHolder.inTransaction(dataSource,
                tenantContext(policy.tenantId()),
                conn -> {
                    String sql = """
                            UPDATE cap_publish_policy
                            SET publish_mode = ?, enabled = ?, updated_by = ?, updated_at = now()
                            WHERE policy_id = ?::uuid
                              AND scope = ?
                              AND tenant_id IS NOT DISTINCT FROM ?::uuid
                            RETURNING created_at, updated_at
                            """;
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        int i = 1;
                        ps.setString(i++, policy.publishMode());
                        ps.setBoolean(i++, policy.enabled());
                        ps.setString(i++, policy.updatedBy());
                        ps.setObject(i++, policy.policyId());
                        ps.setString(i++, policy.scope());
                        bindUuid(ps, i++, policy.tenantId());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) return Optional.empty();
                            return Optional.of(policy.withTimestamps(
                                    rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()));
                        }
                    }
                });
    }

    @Override
    public boolean delete(@NonNull String scope,
                          @Nullable String tenantId,
                          @NonNull UUID policyId) {
        return TransactionalConnectionHolder.inTransaction(dataSource,
                tenantContext(tenantId),
                conn -> {
                    String sql = """
                            DELETE FROM cap_publish_policy
                            WHERE policy_id = ?::uuid
                              AND scope = ?
                              AND tenant_id IS NOT DISTINCT FROM ?::uuid
                            """;
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        int i = 1;
                        ps.setObject(i++, policyId);
                        ps.setString(i++, scope);
                        bindUuid(ps, i++, tenantId);
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public @NonNull List<PublishPolicy> listEnabled(@NonNull String scope,
                                                     @Nullable String tenantId) {
        String sql = selectColumns() + """
                WHERE scope = ?
                  AND (tenant_id IS NULL OR tenant_id IS NOT DISTINCT FROM ?::uuid)
                  AND enabled = true
                ORDER BY policy_level, resource_kind NULLS FIRST
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, scope);
                bindUuid(ps, i++, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<PublishPolicy> rows = new ArrayList<>();
                    while (rs.next()) rows.add(read(rs));
                    return rows;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询发布策略列表失败", e);
        }
    }

    @Override
    public @NonNull Optional<PublishPolicy> findByTarget(@NonNull String scope,
                                                          @Nullable String tenantId,
                                                          @NonNull String policyLevel,
                                                          @Nullable String resourceKind,
                                                          @Nullable String resourceId) {
        String sql = selectColumns() + """
                WHERE scope = ?
                  AND tenant_id IS NOT DISTINCT FROM ?::uuid
                  AND policy_level = ?
                  AND COALESCE(resource_kind, '') = COALESCE(?, '')
                  AND COALESCE(resource_id, '') = COALESCE(?, '')
                LIMIT 1
                """;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, scope);
                bindUuid(ps, i++, tenantId);
                ps.setString(i++, policyLevel);
                ps.setString(i++, resourceKind == null ? "" : resourceKind);
                ps.setString(i++, resourceId == null ? "" : resourceId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("按目标查询发布策略失败", e);
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────

    private static String selectColumns() {
        return """
                SELECT policy_id::text, scope, tenant_id::text, policy_level,
                       resource_kind, resource_id, publish_mode, enabled,
                       updated_by, created_at, updated_at
                FROM cap_publish_policy
                """;
    }

    private static PublishPolicy read(@NonNull ResultSet rs) throws SQLException {
        return new PublishPolicy(
                UUID.fromString(rs.getString("policy_id")),
                rs.getString("scope"),
                rs.getString("tenant_id"),
                rs.getString("policy_level"),
                rs.getString("resource_kind"),
                rs.getString("resource_id"),
                rs.getString("publish_mode"),
                rs.getBoolean("enabled"),
                rs.getString("updated_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static void bindNullableString(@NonNull PreparedStatement ps, int index,
                                              @Nullable String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void bindUuid(@NonNull PreparedStatement ps, int index,
                                  @Nullable String uuidText) throws SQLException {
        if (uuidText == null || uuidText.isBlank()) {
            ps.setNull(index, Types.OTHER);
            return;
        }
        try {
            ps.setObject(index, UUID.fromString(uuidText));
        } catch (IllegalArgumentException e) {
            // ⚠️ 非 UUID 哨兵租户（如平台身份合成值）降级为 NULL，
            //    不中断写入 / 查询；PLATFORM scope 下 tenant_id 本就应为 NULL。
            ps.setNull(index, Types.OTHER);
        }
    }

    private TenantContext tenantContext(@Nullable String tenantId) {
        String normalized;
        if (tenantId == null || tenantId.isBlank()) {
            normalized = PLATFORM_SCOPE_TENANT;
        } else {
            try {
                UUID.fromString(tenantId);
                normalized = tenantId;
            } catch (IllegalArgumentException e) {
                // 非 UUID 哨兵 → 视作平台侧，归一化到标准哨兵
                normalized = PLATFORM_SCOPE_TENANT;
            }
        }
        return new TenantContext(normalized, "system", java.util.Set.of(), 0L);
    }

    private TenantConnectionLease tenantConnection(@Nullable String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) return TenantConnectionLease.borrowed(outer);
        return TransactionalConnectionHolder.openTenantConnection(dataSource, tenantContext(tenantId));
    }
}
