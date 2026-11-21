package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.ResultEvent;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.ResultEventMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.ResultEventDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.ResultEventPort;

/**
 * {@link ResultEventPort} 的 MyBatis-Plus 实现（对应 {@code JdbcResultEventRepository}）。
 *
 * <p>Port 签名零改动；两条 SQL 逐字搬迁到 {@code ResultEventMapper.xml}，仅 {@code ?} → {@code #{param}}。
 * {@code ON CONFLICT DO NOTHING}（先写入者为准）、影子比对 / 染色、非 JSON 载荷降级为 JSON 字符串
 * 的吞异常语义与原实现完全一致。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（try/finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接，与原实现一致。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisResultEventRepository implements ResultEventPort {

    /** 影子比对/染色的资源类型（对齐 policy-mapping.yaml 的 TENANT_ONLY）。 */
    private static final String RESOURCE_TYPE = "cap_result_event";

    private final ResultEventMapper mapper;
    private final DataSource dataSource;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    @Autowired
    public MybatisResultEventRepository(@NonNull ResultEventMapper mapper,
                                        @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this(mapper, dataSource, null, null, false, "");
    }

    /** 影子态接线构造（测试与包内装配用；生产走 {@link #bindAuthzShadow} setter）。 */
    MybatisResultEventRepository(@NonNull ResultEventMapper mapper,
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
    public boolean recordIfAbsent(@NonNull ResultEvent event) {
        return inTenantScope(event.tenantId(), () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(event.tenantId()), conn ->
                        mapper.recordIfAbsent(
                                event.eventId(),
                                event.tenantId(),
                                event.runId(),
                                event.invocationId(),
                                event.terminalEventId(),
                                event.resultStatus().name(),
                                event.resultPayload() == null ? null : toJsonb(event.resultPayload()),
                                event.reasonCode(),
                                event.createdAt()) > 0));
    }

    /**
     * 把结果载荷转成可写入 JSONB 的文本。
     *
     * <p>⚠️ <b>工具输出不保证是合法 JSON</b>：
     * {@code ToolResult.output} 是 {@code String}，真实工具一般产出 JSON 文档，但并非强制。
     * 若直接 {@code ?::jsonb} 写入非 JSON 文本，PostgreSQL 会报
     * {@code invalid input syntax for type json}，而这个失败发生在「确定结果落盘」这一步，
     * 后果是「工具成功了但结果没落盘」→ 系统把成功判成「证据丢失」。
     *
     * <p>故：<b>合法 JSON 原样写入</b>（保住结构，下游可按 JSONB 查询）；
     * <b>非 JSON 转成 JSON 字符串</b>（内容不丢，只是类型退化为字符串）。
     */
    private static @NonNull String toJsonb(@NonNull String payload) {
        try {
            tools.jackson.databind.JsonNode node =
                    tools.jackson.databind.json.JsonMapper.builder().build().readTree(payload);
            if (node != null) {
                return payload;
            }
        } catch (RuntimeException e) {
            // ⚠️ 非 JSON → 落成 JSON 字符串（不抛：抛了等于让「载荷格式」决定成败）
        }
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(payload);
    }

    @Override
    public @NonNull Optional<ResultEvent> findByInvocation(@NonNull String tenantId,
                                                            @NonNull String invocationId) {
        // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = ?::uuid`。
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId), conn -> {
                    Optional<ResultEventDO> row = mapper.findByInvocation(tenantId, invocationId);
                    if (row.isEmpty()) {
                        return Optional.empty();
                    }
                    ResultEventDO d = row.get();
                    shadowTaint(RESOURCE_TYPE, tenantId, d.getTenantId());
                    return Optional.of(toRecord(d));
                }));
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

    private static ResultEvent toRecord(ResultEventDO d) {
        return new ResultEvent(
                d.getEventId(),
                d.getTenantId(),
                d.getRunId(),
                d.getInvocationId(),
                d.getTerminalEventId(),
                ResultEvent.Status.valueOf(d.getResultStatus()),
                d.getResultPayload(),
                d.getReasonCode(),
                d.getCreatedAt());
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
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
