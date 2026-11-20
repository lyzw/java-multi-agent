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

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.ProfileQueryMapper;
import cn.studykid.multiagent.infrastructure.persistence.model.StudentProfileDO;
import cn.studykid.multiagent.infrastructure.persistence.model.WeaknessRecordDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.ProfileQueryPort;

/**
 * {@link ProfileQueryPort} 的 MyBatis-Plus 实现（对应 {@code JdbcProfileQueryRepository}）。
 *
 * <p>Port 签名零改动；两条 SQL 逐字搬迁到 {@code ProfileQueryMapper.xml}，
 * 仅 {@code ?} → {@code #{param}}。{@code tenant_id} 谓词、{@code ORDER BY updated_at DESC}
 * 与 {@code LIMIT 50} 全部保留。
 *
 * <p>⚠️ <b>白名单纪律</b>：只查 {@code grade}/{@code subject}/{@code updated_at} 与
 * 薄弱点的知识点/错因/置信度/时刻；<b>不查</b>姓名、学校、班级（DB 层也没有这些列）。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：两条查询在<b>同一次</b> {@code inTransaction} 内执行
 * （bind/clear try-finally 无条件清理），共用同一条事务连接 ——
 * 与旧实现「同一 lease 上先查画像再查薄弱点」的连接模型逐字对应。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisProfileQueryRepository implements ProfileQueryPort {

    /** 单次返回的薄弱点上限，防止异常数据量下响应过大（SQL 侧同样 LIMIT 50）。 */
    private static final int MAX_WEAKNESSES = 50;

    private final ProfileQueryMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId = "";

    public MybatisProfileQueryRepository(@NonNull ProfileQueryMapper mapper,
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
    public @NonNull ProfileView loadProfile(@NonNull String tenantId,
                                            @NonNull String studentId) {
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> {
                        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
                        shadowCompare("student_profile", tenantId, true);
                        Optional<StudentProfileDO> row = mapper.loadProfile(tenantId, studentId);
                        if (row.isEmpty()) {
                            // ⚠️ 学生不存在 → 返回空画像而非报错：
                            //    若不存在报错、存在返回数据，攻击者可据此枚举有效 studentId。
                            return ProfileView.empty(studentId);
                        }
                        StudentProfileDO d = row.get();
                        // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                        shadowTaint("student_profile", tenantId, d.getTenantId());
                        return new ProfileView(studentId, d.getGrade(), d.getSubject(),
                                loadWeaknesses(tenantId, studentId),
                                d.getUpdatedAt());
                    }));
        } catch (RuntimeException e) {
            if (isSqlFailure(e)) {
                throw new IllegalStateException("查询学习画像失败", e);
            }
            throw e;
        }
    }

    private List<WeaknessView> loadWeaknesses(String tenantId, String studentId) {
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
        shadowCompare("weakness_record", tenantId, true);
        List<WeaknessView> result = new ArrayList<>();
        for (WeaknessRecordDO d : mapper.loadWeaknesses(tenantId, studentId)) {
            if (result.size() >= MAX_WEAKNESSES) {
                break;
            }
            // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
            result.add(new WeaknessView(
                    d.getKnowledgePoint(),
                    d.getErrorType(),
                    d.getConfidence(),
                    toInstant(d.getCreatedAt())));
            shadowTaint("weakness_record", tenantId, d.getTenantId());
        }
        return result;
    }

    private static @NonNull Instant toInstant(@Nullable Instant at) {
        return at == null ? Instant.EPOCH : at;
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
