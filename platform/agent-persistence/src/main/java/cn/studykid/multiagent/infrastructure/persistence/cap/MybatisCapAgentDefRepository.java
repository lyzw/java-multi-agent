package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.CapAgentDefMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapAgentDefDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

/**
 * {@link CapAgentDefPort} 的 MyBatis-Plus 实现（对应 {@code JdbcCapAgentDefRepository}）。
 *
 * <p>Port 签名零改动；七条 SQL 逐字搬迁到 {@code CapAgentDefMapper.xml}，仅 {@code ?} → {@code #{param}}。
 * {@code ON CONFLICT DO NOTHING}（不可变版本行）、JSON 数组序列化/反序列化的 fail-closed 语义、
 * 影子比对/逐行染色全部与原实现一致。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（try/finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接，与原实现一致；
 * 全局 Agent 方法绑定零 UUID 哨兵，租户方法绑定该租户。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisCapAgentDefRepository implements CapAgentDefPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 影子比对/染色的资源类型（P0-1b 第 1 批）。 */
    private static final String RESOURCE_TYPE = "cap_agent_def";

    private static final String GLOBAL_CONTEXT_TENANT = "00000000-0000-0000-0000-000000000000";

    private final CapAgentDefMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2；阶段一 P0-1b 第 1 批）────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    // ⚠️ 非 final：由 Spring 启动期通过 {@link #bindAuthzShadow} 一次性绑定，之后只读。
    //    容器里没有 AuthzShadowBinding 时保持构造期默认值（null / false）⇒ 零行为变更。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    @Autowired
    public MybatisCapAgentDefRepository(@NonNull CapAgentDefMapper mapper,
                                        @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this(mapper, dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     *
     * @param shadowEnabled    是否启用影子比对 + 染色（P1 默认 false；P2 翻转为 true 进入阻断模式）
     * @param sentinelTenantId 哨兵（共享）租户标识，与 {@code SqlScopeTranslator} 共用配置项
     */
    MybatisCapAgentDefRepository(@NonNull CapAgentDefMapper mapper,
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
    public boolean insert(@NonNull CapAgentDef def) {
        return inTenantScope(GLOBAL_CONTEXT_TENANT, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(GLOBAL_CONTEXT_TENANT), conn ->
                        mapper.insertVersion(
                                def.agentId(),
                                def.version(),
                                def.name(),
                                def.description(),
                                toJson(def.roles()),
                                toJson(def.enabledSkills()),
                                toJson(def.skillBundles()),
                                toJson(def.capabilityTags()),
                                def.overrides(),
                                def.orchestration(),
                                def.instructions(),
                                def.modelRef(),
                                def.generationParams(),
                                def.configFingerprint(),
                                def.schemaVersion(),
                                def.createdAt(),
                                // created_at / updated_at 同取 def.createdAt()，与旧 JDBC 的两次 setTimestamp 一致
                                def.createdAt(),
                                def.publishedAt()) > 0));
    }

    @Override
    public @NonNull Optional<CapAgentDef> find(@Nullable String tenantId,
                                                @NonNull String agentId,
                                                int version) {
        if (tenantId == null) {
            // PLATFORM 作用域冲突回查：按 agentId+version 直接查，不走绑定过滤。
            return inTenantScope(GLOBAL_CONTEXT_TENANT, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, tenantContext(GLOBAL_CONTEXT_TENANT), conn -> mapper
                            .findGlobal(agentId, version)
                            .map(MybatisCapAgentDefRepository::toRecord)));
        }
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    Optional<CapAgentDef> row = mapper.find(agentId, version, tenantId)
                            .map(MybatisCapAgentDefRepository::toRecord);
                    row.ifPresent(d -> shadowTaint(RESOURCE_TYPE, tenantId, tenantId));
                    return row;
                }));
    }

    @Override
    public @NonNull Optional<CapAgentDef> findLatestGlobal(@NonNull String agentId) {
        // 无绑定过滤：全局 Agent 可以尚未分配任何租户。用哨兵上下文绑定 RLS。
        return inTenantScope(GLOBAL_CONTEXT_TENANT, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(GLOBAL_CONTEXT_TENANT), conn -> mapper
                        .findLatestGlobal(agentId)
                        .map(MybatisCapAgentDefRepository::toRecord)));
    }

    @Override
    public @NonNull List<CapAgentDef> listVersionsGlobal(@NonNull String agentId) {
        // 无绑定过滤：全局 Agent 可以尚未分配任何租户。用哨兵上下文绑定 RLS。
        return inTenantScope(GLOBAL_CONTEXT_TENANT, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(GLOBAL_CONTEXT_TENANT), conn ->
                        mapper.listVersionsGlobal(agentId).stream()
                                .map(MybatisCapAgentDefRepository::toRecord)
                                .toList()));
    }

    @Override
    public @NonNull Optional<CapAgentDef> findLatest(@NonNull String tenantId,
                                                      @NonNull String agentId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    Optional<CapAgentDef> row = mapper.findLatest(agentId, tenantId)
                            .map(MybatisCapAgentDefRepository::toRecord);
                    row.ifPresent(d -> shadowTaint(RESOURCE_TYPE, tenantId, tenantId));
                    return row;
                }));
    }

    @Override
    public @NonNull Optional<CapAgentDef> findLatestPublished(@NonNull String tenantId,
                                                               @NonNull String agentId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    Optional<CapAgentDef> row = mapper.findLatestPublished(agentId, tenantId)
                            .map(MybatisCapAgentDefRepository::toRecord);
                    row.ifPresent(d -> shadowTaint(RESOURCE_TYPE, tenantId, tenantId));
                    return row;
                }));
    }

    @Override
    public @NonNull List<CapAgentDef> listLatestPerAgent(@NonNull String tenantId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    List<CapAgentDef> rows = new ArrayList<>();
                    for (CapAgentDefDO dbRow : mapper.listLatestPerAgent(tenantId)) {
                        CapAgentDef d = toRecord(dbRow);
                        rows.add(d);
                        shadowTaint(RESOURCE_TYPE, tenantId, tenantId);
                    }
                    return rows;
                }));
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

    // ── DO ↔ record（逐字段，与旧 JDBC 的 map() 对齐）──

    private static CapAgentDef toRecord(CapAgentDefDO d) {
        return new CapAgentDef(
                d.getAgentId(),
                d.getVersion(),
                d.getName(),
                d.getDescription(),
                fromJson(d.getRoles()),
                fromJson(d.getEnabledSkills()),
                fromJson(d.getSkillBundles()),
                fromJson(d.getCapabilityTags()),
                d.getOverrides(),
                d.getOrchestration(),
                d.getInstructions(),
                d.getModelRef(),
                d.getGenerationParams(),
                d.getConfigFingerprint(),
                d.getSchemaVersion(),
                d.getCreatedAt(),
                d.getPublishedAt());
    }

    private static String toJson(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化 JSON 数组失败", e);
        }
    }

    private static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JacksonException e) {
            // ⚠️ 存量脏数据不应让整个列表读取失败。
            //    但**不能**静默当空列表 —— 那会让「本来启用了 3 个 Skill」
            //    被读成「一个都没启用」，授权被无声放宽。
            //    故抛出，让上层 fail-closed。
            throw new IllegalStateException("解析 JSON 数组失败: " + json, e);
        }
    }

    /** 在租户作用域内执行：绑定 → 执行 → 无条件清理。 */
    private <T> T inTenantScope(@Nullable String tenantId, java.util.function.Supplier<T> action) {
        String effective = tenantId == null || tenantId.isBlank() ? GLOBAL_CONTEXT_TENANT : tenantId;
        TenantScope.bind(effective); // 来自认证上下文/平台哨兵的 tenantId，非客户端输入
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }
}
