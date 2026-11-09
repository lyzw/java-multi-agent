package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import cn.studykid.multiagent.core.cap.ResultEvent;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.ports.persistence.ResultEventPort;

/**
 * {@link ResultEventPort} 的 PostgreSQL 实现（§6.1 · W-PERM-P0-T5）。
 *
 * <p>⚠️ <b>写入是 {@code ON CONFLICT DO NOTHING}，不是 upsert</b>：
 * 结果事件是「已发生的事实」，先写入者为准。
 * 若改成 {@code DO UPDATE}，崩溃恢复时的重试会<b>覆盖</b>既有确定结果——
 * 而 §7.4 的双维度结果依赖「确定结果不变、只重试落账」，
 * 覆盖会让「结果未知」被改写成「确定成功」，掩盖真实状态。
 */
public class JdbcResultEventRepository implements ResultEventPort {
    /** 影子比对/染色的资源类型（对齐 policy-mapping.yaml 的 TENANT_ONLY）。 */
    private static final String RESOURCE_TYPE = "cap_result_event";

    private static final String SQL_INSERT = """
            INSERT INTO cap_result_event
                (event_id, tenant_id, run_id, invocation_id, terminal_event_id,
                 result_status, result_payload, reason_code, created_at)
            VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?::cap_result_status, ?::jsonb, ?, ?)
            ON CONFLICT (tenant_id, invocation_id) DO NOTHING
            """;

    private static final String SQL_FIND = """
            SELECT event_id::text, tenant_id::text, run_id::text, invocation_id,
                   terminal_event_id, result_status, result_payload::text, reason_code, created_at
            FROM cap_result_event
            WHERE tenant_id = ?::uuid AND invocation_id = ?
            """;

    private final DataSource dataSource;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    public JdbcResultEventRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 影子态接线构造（测试与包内装配用；生产走 {@link #bindAuthzShadow} setter）。 */
    JdbcResultEventRepository(@NonNull DataSource dataSource,
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
    public boolean recordIfAbsent(@NonNull ResultEvent event) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(event.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, event.eventId());
                        ps.setString(2, event.tenantId());
                        if (event.runId() == null) {
                            ps.setNull(3, java.sql.Types.OTHER);
                        } else {
                            ps.setString(3, event.runId());
                        }
                        ps.setString(4, event.invocationId());
                        ps.setString(5, event.terminalEventId());
                        ps.setString(6, event.resultStatus().name());
                        if (event.resultPayload() == null) {
                            ps.setNull(7, java.sql.Types.OTHER);
                        } else {
                            ps.setString(7, toJsonb(event.resultPayload()));
                        }
                        ps.setString(8, event.reasonCode());
                        ps.setTimestamp(9, Timestamp.from(event.createdAt()));
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    /**
     * 把结果载荷转成可写入 JSONB 的文本。
     *
     * <p>⚠️ <b>工具输出不保证是合法 JSON</b>：
     * {@code ToolResult.output} 是 {@code String}，真实工具一般产出 JSON 文档，
     * 但并非强制。若直接 {@code ?::jsonb} 写入非 JSON 文本，
     * PostgreSQL 会报 {@code invalid input syntax for type json} ——
     * 而这个失败发生在<b>「确定结果落盘」</b>这一步，
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
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, invocationId);
                        // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = ?::uuid`。
                        shadowCompare(RESOURCE_TYPE, tenantId, true);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                return Optional.empty();
                            }
                            shadowTaint(RESOURCE_TYPE, tenantId, rs.getString("tenant_id"));
                            return Optional.of(map(rs));
                        }
                    }
                });
    }

    private static ResultEvent map(ResultSet rs) throws SQLException {
        return new ResultEvent(
                rs.getString("event_id"),
                rs.getString("tenant_id"),
                rs.getString("run_id"),
                rs.getString("invocation_id"),
                rs.getString("terminal_event_id"),
                ResultEvent.Status.valueOf(rs.getString("result_status")),
                rs.getString("result_payload"),
                rs.getString("reason_code"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
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
