package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.ConfigLifecycleMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.LifecycleStateRow;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.ConfigLifecyclePort;

/**
 * {@link ConfigLifecyclePort} 的 MyBatis-Plus 实现（对应 {@code JdbcConfigLifecycleRepository}）。
 *
 * <p>⚠️ <b>CAS 前置条件是「期望原状态」</b>：
 * {@code WHERE ... AND lifecycle_state = #{expectedState}::cap_lifecycle_state}。
 * 没有它，两个并发操作（一个想发布、一个想停用）会各自读到同一个旧状态，
 * 后写者直接覆盖先写者 —— 状态迁移就成了「谁最后写谁赢」，
 * 而生命周期迁移恰恰需要「先到先得」或「显式冲突」。
 *
 * <p>⚠️ <b>本类不写 {@code published_at}</b>：
 * 「已发布」由发布动作决定（发布服务在发布事务里插入带 published_at 的版本行），
 * 生命周期迁移只动状态列。
 *
 * <p>⚠️ <b>对象类型分「平台级」与「租户级」两族</b>（第二十五批 · A5）：
 * <ul>
 *   <li><b>平台级</b>（tool / skill / mcp_server）：无 {@code tenant_id}，按 {@code id} 寻址；</li>
 *   <li><b>租户级</b>（agent / result_contract / routing_policy）：
 *       按 {@code (tenant_id, 业务 id, version)} 寻址，且都是 FORCE RLS 表 ——
 *       <b>必须</b>绑定租户上下文，否则读写恒为 0 行（迁移成功了但什么都没发生）。</li>
 * </ul>
 * 混用两族的寻址方式会让「按 id 更新」打到别的租户的行上 ——
 * 那是跨租户结构泄漏，而它<b>不报错</b>，只是静默更新错对象。
 *
 * <p>⚠️ <b>P1 影子态：只观测租户级读路径</b>：{@code stateOf} 的 2 个租户级分支
 * （result_contract / routing_policy）自带显式 {@code tenant_id} 谓词，已接影子钩子；
 * 平台级 3 分支与 agent 分支（无 {@code tenant_id} 可染色）<b>刻意不观测</b>
 * （登记进 {@code PLATFORM_LEVEL_RESOURCE_TYPES} 会制造恒定假违例）。
 * 写路径 {@code transition}（UPDATE）属 P2 范围，P1 不观测。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisConfigLifecycleRepository implements ConfigLifecyclePort {

    private static final String TYPE_TOOL = "tool";
    private static final String TYPE_SKILL = "skill";
    private static final String TYPE_MCP_SERVER = "mcp_server";
    private static final String TYPE_AGENT = "agent";
    /** 输出契约（租户级 · 多级 Agent v1.1 · 第二十五批 A5）。 */
    private static final String TYPE_RESULT_CONTRACT = "result_contract";
    /** 路由策略（租户级 · 多级 Agent v1.1 第十四批 · 第二十五批 A5）。 */
    private static final String TYPE_ROUTING_POLICY = "routing_policy";

    /** PLATFORM 作用域的租户哨兵：零 UUID。 */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private final ConfigLifecycleMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    @Autowired
    public MybatisConfigLifecycleRepository(@NonNull ConfigLifecycleMapper mapper,
                                            @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this(mapper, dataSource, null, null, false, "");
    }

    /** 影子态接线构造（测试与包内装配用；生产走 {@link #bindAuthzShadow} setter）。 */
    MybatisConfigLifecycleRepository(@NonNull ConfigLifecycleMapper mapper,
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
    public boolean transition(@NonNull String type,
                              @Nullable String tenantId,
                              @NonNull String objectId,
                              int version,
                              @NonNull ConfigLifecycleState expectedFrom,
                              @NonNull ConfigLifecycleState to) {
        TenantContext ctx = context(type, tenantId);
        return inTenantScope(ctx, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, ctx, conn -> switch (type) {
                    // 平台级：无 tenant_id，按 id 寻址。
                    case TYPE_TOOL -> mapper.transitionTool(
                            objectId, version, expectedFrom.name(), to.name()) == 1;
                    case TYPE_SKILL -> mapper.transitionSkill(
                            objectId, version, expectedFrom.name(), to.name()) == 1;
                    case TYPE_MCP_SERVER -> mapper.transitionMcpServer(
                            objectId, version, expectedFrom.name(), to.name()) == 1;
                    // agent_id 是 uuid 列（cast 保留）；agent 无 tenant_id 列。
                    case TYPE_AGENT -> mapper.transitionAgent(
                            objectId, version, expectedFrom.name(), to.name()) == 1;
                    // ⚠️ 契约 / 策略的 id 是**可读字符串**（V23 / V24 的教训），
                    //    绝不能写 ?::uuid —— 非 UUID 形式的 id 会让整条语句报错。
                    case TYPE_RESULT_CONTRACT -> mapper.transitionResultContract(
                            tenantId, objectId, version, expectedFrom.name(), to.name()) == 1;
                    case TYPE_ROUTING_POLICY -> mapper.transitionRoutingPolicy(
                            tenantId, objectId, version, expectedFrom.name(), to.name()) == 1;
                    default -> throw new IllegalArgumentException("未知对象类型: " + type);
                }));
    }

    @Override
    public @NonNull Optional<ConfigLifecycleState> stateOf(@NonNull String type,
                                                           @Nullable String tenantId,
                                                           @NonNull String objectId,
                                                           int version) {
        TenantContext ctx = context(type, tenantId);
        return inTenantScope(ctx, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, ctx, conn -> {
                    String rt = shadowResourceType(type);
                    if (rt != null && tenantId != null) {
                        // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = ?::uuid`。
                        shadowCompare(rt, tenantId, true);
                    }
                    return switch (type) {
                        // 平台级 / agent：只取状态列，无行归属可染色（刻意不观测）。
                        case TYPE_TOOL -> state(query(
                                () -> mapper.selectToolState(objectId, version), type, objectId, version));
                        case TYPE_SKILL -> state(query(
                                () -> mapper.selectSkillState(objectId, version), type, objectId, version));
                        case TYPE_MCP_SERVER -> state(query(
                                () -> mapper.selectMcpServerState(objectId, version),
                                type, objectId, version));
                        case TYPE_AGENT -> state(query(
                                () -> mapper.selectAgentState(objectId, version), type, objectId, version));
                        // 租户级：额外读 tenant_id::text，逐行染色用结果行的真实归属。
                        case TYPE_RESULT_CONTRACT -> {
                            LifecycleStateRow row = query(
                                    () -> mapper.selectResultContractState(tenantId, objectId, version),
                                    type, objectId, version);
                            if (row != null && rt != null && tenantId != null) {
                                shadowTaint(rt, tenantId, row.getTenantId());
                            }
                            yield state(row == null ? null : row.getLifecycleState());
                        }
                        case TYPE_ROUTING_POLICY -> {
                            LifecycleStateRow row = query(
                                    () -> mapper.selectRoutingPolicyState(tenantId, objectId, version),
                                    type, objectId, version);
                            if (row != null && rt != null && tenantId != null) {
                                shadowTaint(rt, tenantId, row.getTenantId());
                            }
                            yield state(row == null ? null : row.getLifecycleState());
                        }
                        default -> throw new IllegalArgumentException("未知对象类型: " + type);
                    };
                }));
    }

    /**
     * 状态读取的异常包装：<b>fail-closed</b>。
     *
     * <p>⚠️ 状态读不出来<b>不得</b>被当成「没有状态」：
     * 「读不到」与「不是 DEPRECATED」是两个事实，
     * 混为一谈会让「停用」防线静默失效 —— 故异常一律转成
     * {@link IllegalStateException} 向上抛（消息与旧实现逐字一致）。
     */
    private static <T> T query(java.util.function.Supplier<T> call,
                               String type,
                               String objectId,
                               int version) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "读取生命周期状态失败: type=" + type + ", object=" + objectId
                            + ", version=" + version, e);
        }
    }

    /** 无行（{@code null}）-> {@code Optional.empty()}，与旧实现的 {@code rs.next()} 判定同口径。 */
    private static Optional<ConfigLifecycleState> state(@Nullable String raw) {
        return raw == null ? Optional.empty() : Optional.of(ConfigLifecycleState.valueOf(raw));
    }

    /**
     * 该对象类型是否<b>按租户寻址</b>（租户级对象）。
     *
     * <p>⚠️ 租户级对象都是 FORCE RLS 表：寻址方式与 RLS 上下文<b>必须</b>同时对上，
     * 否则「更新 0 行」会被读成「状态没变」——
     * 那是一个<b>不报错</b>的失败：调用方收到「迁移未生效」，
     * 却不知道真正的原因是「租户上下文没绑」。
     */
    private static boolean isTenantScoped(@NonNull String type) {
        return TYPE_RESULT_CONTRACT.equals(type)
                || TYPE_ROUTING_POLICY.equals(type);
    }

    /**
     * 构造租户上下文。
     *
     * <p>⚠️ 租户级表（{@code cap_agent_def} / {@code cap_result_contract} /
     * {@code cap_routing_policy}）是 FORCE RLS 的租户表 ——
     * 不绑定租户上下文时读写恒为 0 行。
     * 平台级三张表无 RLS，但仍绑定上下文，
     * 以免同一事务内访问带 RLS 的表时被拒绝。
     */
    private static TenantContext context(String type, @Nullable String tenantId) {
        if (isTenantScoped(type) && tenantId != null) {
            return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
        }
        return new TenantContext(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId,
                "system", java.util.Set.of(), 0L);
    }

    /** 在租户作用域内执行：绑定 -> 执行 -> 无条件清理。 */
    private <T> T inTenantScope(@NonNull TenantContext ctx, java.util.function.Supplier<T> action) {
        TenantScope.bind(ctx.tenantId()); // 与连接上的 app.tenant_id 同源，拦截器漂移校验必过
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 该对象类型对应的影子资源类型；平台级对象（无 tenant_id）返回 {@code null} ⇒ 不观测。
     */
    private static @Nullable String shadowResourceType(@NonNull String type) {
        return switch (type) {
            // Agent 已是全局实体，没有可比的租户行归属；不参与租户染色影子比对。
            case TYPE_RESULT_CONTRACT -> "cap_result_contract";
            case TYPE_ROUTING_POLICY -> "cap_routing_policy";
            default -> null;
        };
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

    /**
     * 影子比对：Gate 算出的 ScopeFilter vs 本次查询的绑定租户（只记日志）。
     *
     * @param hasTenantPredicate 该查询的 SQL 是否真的带了显式租户谓词（必须如实填写）。
     */
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
