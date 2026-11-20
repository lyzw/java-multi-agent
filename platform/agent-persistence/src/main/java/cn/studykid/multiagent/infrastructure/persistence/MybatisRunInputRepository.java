package cn.studykid.multiagent.infrastructure.persistence;

import java.util.Map;

import javax.sql.DataSource;

import org.apache.ibatis.exceptions.PersistenceException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RunInputMapper;
import cn.studykid.multiagent.infrastructure.persistence.model.RunInputDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.RunInputPort;

/**
 * {@link RunInputPort} 的 MyBatis-Plus 实现（对应 {@code JdbcRunInputRepository}）。
 *
 * <p>Port 签名零改动；SQL 逐字搬迁到 {@code RunInputMapper.xml}，仅 {@code ?} → {@code #{param}}。
 * 影子比对 / 逐行染色 / 坏 JSON 吞异常返回空 Map 的语义与原实现完全一致。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：Mapper 调用前用 {@code tenantId} 绑定 {@link TenantScope}（try/finally 无条件清理），
 * 并经 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接：
 * 外层有连接则复用、否则由该工具自开并绑定租户（内部仍走 {@link TenantConnectionLease}），
 * 与旧 {@code tenantConnection()} 的连接模型逐字对应。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisRunInputRepository implements RunInputPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final RunInputMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态接入（与旧 Jdbc 实现同口径；默认 null/false ⇒ 行为 100% 不变）──
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有单参构造，确保 @Repository 装配不变）。 */
    @Autowired
    public MybatisRunInputRepository(@NonNull RunInputMapper mapper,
                                     @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this(mapper, dataSource, null, null, false, "");
    }

    /** 影子态接线构造（测试用；生产由 {@link #bindAuthzShadow} 注入）。 */
    MybatisRunInputRepository(@NonNull RunInputMapper mapper,
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
    public @NonNull Map<String, Object> inputOf(@NonNull String tenantId,
                                                @NonNull String runId) {
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter（只记日志）。
        shadowCompare("run", tenantId, true);
        try {
            return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, systemContext(tenantId), conn -> {
                        java.util.Optional<RunInputDO> row = mapper.findInput(tenantId, runId);
                        if (row.isEmpty()) {
                            return Map.<String, Object>of();
                        }
                        RunInputDO d = row.get();
                        // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                        String rowTenant = d.getTenantId();
                        Map<String, Object> input = parse(d.getInputPayload());
                        shadowTaint("run", tenantId, rowTenant);
                        return input;
                    }));
        } catch (PersistenceException e) {
            // ⚠️ 与原实现 catch(SQLException) 同口径：底层 SQL 失败显式报错，不静默返回空 Map。
            //    MyBatis 把 SQLException 包成 PersistenceException，故在此处转译。
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

    /** 在租户作用域内执行：绑定 → 执行 → 无条件清理。 */
    private <T> T inTenantScope(@NonNull String tenantId, java.util.function.Supplier<T> action) {
        TenantScope.bind(tenantId);
        try {
            return action.get();
        } finally {
            TenantScope.clear();
        }
    }

    /** 系统身份仅用于绑定 RLS 上下文，不携带任何角色权限。 */
    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）—— 与原实现逐字一致
    // ══════════════════════════════════════════════════════════════

    /** 影子态装配入口：容器里存在 {@code AuthzShadowBinding} 即开启，不存在即完全关闭。 */
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
