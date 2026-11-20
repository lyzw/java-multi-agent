package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 租户配置覆盖仓储（P4-D15 / F6 · C2 + C3）。
 *
 * <p>⚠️ <b>本类只存覆盖值，不解释「生效」</b>：yml 仍是默认值唯一来源。
 * 「yml ⊕ DB 覆盖」的合并语义在 application 层
 * （{@code EffectiveConfigResolver}），本类不碰——否则默认值来源会被搅进持久化。
 *
 * <p>⚠️ <b>审计 fail-closed</b>：写入/清除走
 * {@code AuditPort.recordRequired}，失败即抛——无审计的配置变更等于无声变更。
 *
 * <p>⚠️ <b>审计 detail 不放 value_text</b>：值是用户输入的自由文本，
 * PIPL 敏感信息扫描（fail-closed）会拒写含可识别信息的明细，
 * 进而让整条配置变更失败（P4-D14 已踩过同形态）。
 * 明细只放<b>键名 + 类型 + 方向</b>——都是系统常量，不含用户数据；
 * 变更理由与新旧值落在 {@code reason} 列与本表，由审计的资源关联还原。
 *
 * <p>⚠️ 全部走 {@code tenantConnection} 绑定 RLS；事务内显式 commit/rollback。
 */
@Repository
// 架构决策变更：MP 开启时由 MybatisConfigOverrideRepository 接管本 Port；默认仍用本实现。
// matchIfMissing=true => 未配置 persistence.mybatis.enabled 时本实现照常生效（行为零变更）。
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
public class JdbcConfigOverrideRepository implements ConfigOverridePort {

    private final DataSource dataSource;
    private final AuditPort audit;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // tenant_config_override 为租户隔离实体（policy-mapping.yaml: TENANT_ONLY + FORCE RLS）。
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有 2 参构造，确保 @Repository 装配不变）。 */
    @Autowired
    public JdbcConfigOverrideRepository(@NonNull DataSource dataSource,
                                        @NonNull AuditPort audit) {
        this(dataSource, audit, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     */
    JdbcConfigOverrideRepository(@NonNull DataSource dataSource,
                                 @NonNull AuditPort audit,
                                 @Nullable ScopedDataAccess scoped,
                                 @Nullable AuthzShadowReporter reporter,
                                 boolean shadowEnabled,
                                 @NonNull String sentinelTenantId) {
        this.dataSource = dataSource;
        this.audit = audit;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
    }

    @Override
    public @NonNull Map<String, OverrideEntry> overridesFor(@NonNull String tenantId) {
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
        shadowCompare("tenant_config_override", tenantId, true);
        String sql = """
                SELECT tenant_id, config_key, value_text, value_type, reason, changed_by, updated_at
                FROM tenant_config_override
                WHERE tenant_id = ?::uuid
                ORDER BY config_key
                """;
        Map<String, OverrideEntry> out = new LinkedHashMap<>();
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                        String rowTenant = rs.getString("tenant_id");
                        shadowTaint("tenant_config_override", tenantId, rowTenant);
                        String key = rs.getString("config_key");
                        Tightening tightening = ConfigOverridePort.WRITABLE.get(key);
                        out.put(key, new OverrideEntry(
                                key,
                                rs.getString("value_text"),
                                rs.getString("value_type"),
                                // ⚠️ 目录里没有的键（历史脏数据 / 目录变更后遗留）
                                //    一律按 NEUTRAL 呈现，绝不因此阻断读取。
                                tightening == null ? Tightening.NEUTRAL : tightening,
                                rs.getString("reason"),
                                rs.getString("changed_by"),
                                instantOrNull(rs.getTimestamp("updated_at"))));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取配置覆盖失败", e);
        }
        return out;
    }

    @Override
    public @NonNull OverrideEntry apply(@NonNull String tenantId,
                                        @NonNull String key,
                                        @NonNull String value,
                                        @NonNull String reason,
                                        @NonNull String actorId)
            throws UnknownConfigKeyException {
        Tightening tightening = ConfigOverridePort.WRITABLE.get(key);
        if (tightening == null) {
            throw new UnknownConfigKeyException(key);
        }
        String valueType = valueTypeOf(key);
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            conn.setAutoCommit(false);
            try {
                // ON CONFLICT 覆盖写：同键至多一条（V11 主键即 (tenant_id, config_key)）
                String sql = """
                        INSERT INTO tenant_config_override
                            (tenant_id, config_key, value_text, value_type, reason, changed_by)
                        VALUES (?::uuid, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, config_key) DO UPDATE
                            SET value_text  = EXCLUDED.value_text,
                                value_type  = EXCLUDED.value_type,
                                reason      = EXCLUDED.reason,
                                changed_by  = EXCLUDED.changed_by,
                                updated_at  = now()
                        RETURNING updated_at
                        """;
                Instant updatedAt;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, tenantId);
                    ps.setString(2, key);
                    ps.setString(3, value);
                    ps.setString(4, valueType);
                    ps.setString(5, reason);
                    ps.setString(6, actorId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("配置覆盖写入未返回 updated_at");
                        }
                        updatedAt = rs.getTimestamp(1).toInstant();
                    }
                }
                conn.commit();
                // ⚠️ 明细只放系统常量（键名/类型/方向），不放 value_text —— 见类注释。
                audit.recordRequired(tenantId, actorId,
                        AuditPort.Actions.CONFIG_UPDATE, "config", key,
                        Map.of("valueType", valueType,
                                "tightening", tightening.name()));
                return new OverrideEntry(key, value, valueType, tightening,
                        reason, actorId, updatedAt);
            } catch (SQLException | RuntimeException e) {
                safeRollback(conn);
                throw e instanceof RuntimeException re
                        ? re : new IllegalStateException("写入配置覆盖失败", e);
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入配置覆盖失败", e);
        }
    }

    @Override
    public void clear(@NonNull String tenantId,
                      @NonNull String key,
                      @NonNull String actorId)
            throws UnknownConfigKeyException {
        if (!ConfigOverridePort.WRITABLE.containsKey(key)) {
            throw new UnknownConfigKeyException(key);
        }
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tenant_config_override "
                            + "WHERE tenant_id = ?::uuid AND config_key = ?")) {
                ps.setString(1, tenantId);
                ps.setString(2, key);
                ps.executeUpdate();
            }
            conn.commit();
            audit.recordRequired(tenantId, actorId,
                    AuditPort.Actions.CONFIG_UPDATE, "config", key,
                    Map.of("valueType", "", "tightening", "CLEARED"));
        } catch (SQLException e) {
            throw new IllegalStateException("清除配置覆盖失败", e);
        }
    }

    /**
     * 配置键 → V11 的 {@code value_type}。
     *
     * <p>⚠️ 类型判定是<b>显式映射</b>而非按值猜：猜类型会让
     * 「写了个 15 的 TTL」被存成 INTEGER 而非 DURATION，
     * 解析时得到 15 分钟还是 15 秒取决于猜中与否——不透明即不可审计。
     */
    private static String valueTypeOf(String key) {
        return switch (key) {
            case "agent.run.max-cost-per-run" -> "DECIMAL";
            case "agent.approval.required-by-risk" -> "ENUM";
            case "agent.approval.ttl" -> "DURATION";
            default -> "INTEGER";
        };
    }

    @Nullable
    private static Instant instantOrNull(@Nullable Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // rollback 失败不掩盖原始异常
        }
    }

    private static void restoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 同上
        }
    }

    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, new TenantContext(tenantId, "system", java.util.Set.of(), 0L));
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

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