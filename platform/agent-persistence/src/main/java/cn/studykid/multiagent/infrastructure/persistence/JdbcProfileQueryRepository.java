package cn.studykid.multiagent.infrastructure.persistence;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.ProfileQueryPort;

/**
 * 学习画像查询实现（P4-D09 / G6）。
 *
 * <p>⚠️ <b>白名单纪律</b>：只查 {@code student_profile} 的
 * {@code grade}/{@code subject}/{@code updated_at} 与 {@code weakness_record} 的
 * 知识点/错因/置信度/时刻。
 * <b>不查</b>姓名、学校、班级等——DB 层也没有这些列（V2 迁移刻意不建）。
 *
 * <p>⚠️ <b>默认启用（互补条件）</b>：仅当 {@code persistence.mybatis.enabled} 不是
 * {@code true}（含未配置）时注册；开启后由 {@code MybatisProfileQueryRepository} 接管，
 * 本类不被注册 —— 灰度/一键回滚开关，二者不会同时存在于容器。
 */
@Repository
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "false",
        matchIfMissing = true)
public class JdbcProfileQueryRepository implements ProfileQueryPort {

    /**
     * ⚠️ <b>{@code tenant_id} 条件不可删除</b>（即便 RLS 已隔离，应用侧条件仍是第一道防线）。
     */
    private static final String SQL_LOAD_PROFILE = """
            SELECT tenant_id, grade, subject, updated_at
            FROM student_profile
            WHERE tenant_id = ?::uuid AND student_id = ?
            """;

    private static final String SQL_LOAD_WEAKNESSES = """
            SELECT tenant_id, knowledge_point, error_type, confidence, created_at
            FROM weakness_record
            WHERE tenant_id = ?::uuid AND student_id = ?
            ORDER BY updated_at DESC
            LIMIT 50
            """;

    /** 单次返回的薄弱点上限，防止异常数据量下响应过大。 */
    private static final int MAX_WEAKNESSES = 50;

    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // weakness_record 与 student_profile 两条读路径均接染色（student_profile 资源类型
    // 已登记于 AuthzShadowPolicyDefaults.RESOURCE_TYPES，对齐 policy-mapping.yaml 的
    // TENANT_ONLY + FORCE RLS）。
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有单参构造，确保 @Repository 装配不变）。 */
    @Autowired
    public JdbcProfileQueryRepository(@NonNull DataSource dataSource) {
        this(dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     */
    JdbcProfileQueryRepository(@NonNull DataSource dataSource,
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
    public @NonNull ProfileView loadProfile(@NonNull String tenantId,
                                            @NonNull String studentId) {
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(SQL_LOAD_PROFILE)) {
                ps.setString(1, tenantId);
                ps.setString(2, studentId);
                // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
                shadowCompare("student_profile", tenantId, true);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        // ⚠️ 学生不存在 → 返回空画像而非报错：
                        //    若不存在报错、存在返回数据，攻击者可据此枚举有效 studentId。
                        return ProfileView.empty(studentId);
                    }
                    // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                    String rowTenant = rs.getString("tenant_id");
                    String grade = rs.getString("grade");
                    String subject = rs.getString("subject");
                    Instant updatedAt = toNullableInstant(rs.getTimestamp("updated_at"));
                    shadowTaint("student_profile", tenantId, rowTenant);
                    return new ProfileView(studentId, grade, subject,
                            loadWeaknesses(lease.connection(), tenantId, studentId),
                            updatedAt);
                }
            } finally {
                // ⚠️ 只读查询：回滚以释放读事务。
                lease.rollbackQuietly();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询学习画像失败", e);
        }
    }

    private List<WeaknessView> loadWeaknesses(java.sql.Connection conn,
                                               String tenantId, String studentId)
            throws SQLException {
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
        shadowCompare("weakness_record", tenantId, true);
        List<WeaknessView> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_LOAD_WEAKNESSES)) {
            ps.setString(1, tenantId);
            ps.setString(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && result.size() < MAX_WEAKNESSES) {
                    // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                    String rowTenant = rs.getString("tenant_id");
                    double raw = rs.getDouble("confidence");
                    Double confidence = rs.wasNull() ? null : raw;
                    result.add(new WeaknessView(
                            rs.getString("knowledge_point"),
                            rs.getString("error_type"),
                            confidence,
                            toInstant(rs.getTimestamp("created_at"))));
                    shadowTaint("weakness_record", tenantId, rowTenant);
                }
            }
        }
        return result;
    }

    private static @NonNull Instant toInstant(@Nullable Timestamp ts) {
        return ts == null ? Instant.EPOCH : ts.toInstant();
    }

    /**
     * 可空时刻：{@code null} 原样保留。
     *
     * <p>⚠️ <b>{@code updated_at} 在学生不存在时本就为 {@code null}</b>，
     * 用 EPOCH 兜底会把它伪装成「1970 年更新的画像」；
     * 而 {@code ProfileView.updatedAt} 本身声明为 {@code @Nullable}，
     * 故此处必须原样传递。
     */
    private static @Nullable Instant toNullableInstant(@Nullable Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * 取得已绑定租户的连接。
     *
     * <p>⚠️ 与 {@code JdbcRunEventQueryRepository} 同源铁律：
     * 外层有事务则复用，否则自开并绑定租户。
     */
    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        java.sql.Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, systemContext(tenantId));
    }

    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
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
