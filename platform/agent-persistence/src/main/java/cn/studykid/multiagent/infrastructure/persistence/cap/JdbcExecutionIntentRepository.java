package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import cn.studykid.multiagent.core.cap.ExecutionIntent;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.ports.persistence.ExecutionIntentPort;

/**
 * {@link ExecutionIntentPort} 的 PostgreSQL 实现（§6.1 · W-PERM-P0-T5）。
 *
 * <p>⚠️ <b>状态迁移用「期望原状态」做前置条件</b>（CAS 式）：
 * {@code UPDATE ... WHERE intent_state = expectedFrom}。
 * 若只按 {@code invocation_id} 定位，两个并发消费方会把同一意图都改成
 * CONSUMED —— 审批被消费两次，正是 P3 要防的「审批一次性消费」失效。
 */
public class JdbcExecutionIntentRepository implements ExecutionIntentPort {
    /** 影子比对/染色的资源类型（对齐 policy-mapping.yaml 的 TENANT_ONLY）。 */
    private static final String RESOURCE_TYPE = "cap_execution_intent";

    private static final String SQL_INSERT = """
            INSERT INTO cap_execution_intent
                (intent_id, tenant_id, run_id, invocation_id, tool_id, tool_version,
                 args_fingerprint, approval_id, intent_state, created_at, updated_at)
            VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?::cap_intent_state, ?, ?)
            ON CONFLICT (tenant_id, invocation_id) DO NOTHING
            """;

    private static final String SQL_FIND = """
            SELECT intent_id::text, tenant_id::text, run_id::text, invocation_id, tool_id,
                   tool_version, args_fingerprint, approval_id, intent_state,
                   created_at, updated_at
            FROM cap_execution_intent
            WHERE tenant_id = ?::uuid AND invocation_id = ?
            """;

    private static final String SQL_TRANSITION = """
            UPDATE cap_execution_intent
            SET intent_state = ?::cap_intent_state, updated_at = now()
            WHERE tenant_id = ?::uuid AND invocation_id = ? AND intent_state = ?::cap_intent_state
            """;

    /** 恢复扫描：捞出尚未终结的意图（§5.3.4 崩溃恢复入口）。 */
    private static final String SQL_FIND_PENDING = """
            SELECT intent_id::text, tenant_id::text, run_id::text, invocation_id, tool_id,
                   tool_version, args_fingerprint, approval_id, intent_state,
                   created_at, updated_at
            FROM cap_execution_intent
            WHERE tenant_id = ?::uuid
              AND intent_state IN ('RESERVED', 'INTENT_SUBMITTED')
            ORDER BY created_at ASC
            LIMIT ?
            """;

    private final DataSource dataSource;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    public JdbcExecutionIntentRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 影子态接线构造（测试与包内装配用；生产走 {@link #bindAuthzShadow} setter）。 */
    JdbcExecutionIntentRepository(@NonNull DataSource dataSource,
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
    public @NonNull ExecutionIntent record(@NonNull ExecutionIntent intent) {
        TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(intent.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, intent.intentId());
                        ps.setString(2, intent.tenantId());
                        if (intent.runId() == null) {
                            ps.setNull(3, java.sql.Types.OTHER);
                        } else {
                            ps.setString(3, intent.runId());
                        }
                        ps.setString(4, intent.invocationId());
                        ps.setString(5, intent.toolId());
                        if (intent.toolVersion() == null) {
                            ps.setNull(6, java.sql.Types.INTEGER);
                        } else {
                            ps.setInt(6, intent.toolVersion());
                        }
                        ps.setString(7, intent.argsFingerprint());
                        ps.setString(8, intent.approvalId());
                        ps.setString(9, intent.intentState().name());
                        ps.setTimestamp(10, Timestamp.from(intent.createdAt()));
                        ps.setTimestamp(11, Timestamp.from(intent.updatedAt()));
                        ps.executeUpdate();
                        return null;
                    }
                });
        // ⚠️ 回读而非直接返回入参：ON CONFLICT DO NOTHING 时本次可能<b>没有</b>写入，
        //    此时生效的是既有行。返回入参会掩盖「已被别人写过」这一事实，
        //    而调用方（P2 步骤 13a）需要知道意图是否由本次调用真正落盘。
        return findByInvocation(intent.tenantId(), intent.invocationId())
                .orElseThrow(() -> new IllegalStateException(
                        "执行意图写入后读不到（intent=" + intent.invocationId() + "）"));
    }

    @Override
    public @NonNull Optional<ExecutionIntent> findByInvocation(@NonNull String tenantId,
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

    @Override
    public boolean transitionState(@NonNull String tenantId,
                                   @NonNull String invocationId,
                                   ExecutionIntent.@NonNull IntentState expectedFrom,
                                   ExecutionIntent.@NonNull IntentState to) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_TRANSITION)) {
                        ps.setString(1, to.name());
                        ps.setString(2, tenantId);
                        ps.setString(3, invocationId);
                        ps.setString(4, expectedFrom.name());
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public @NonNull List<ExecutionIntent> findPending(@NonNull String tenantId,
                                                         int limit) {
        // ⚠️ 只捞「尚未终结」的两态（RESERVED / INTENT_SUBMITTED）：
        //    CONSUMED 已终结、CANCELLED/EXPIRED 已旁路掉 —— 捞进来只会拖慢恢复扫描。
        // ⚠️ 按创建时间升序：恢复必须<b>按发生顺序</b>处理，
        //    否则先处理后发生的意图会让先发生的意图永远等不到它的结果。
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<ExecutionIntent> out = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_PENDING)) {
                        ps.setString(1, tenantId);
                        ps.setInt(2, Math.max(1, limit));
                        // ⚠️ hasTenantPredicate=true：SQL 自带显式 `tenant_id = ?::uuid`。
                        shadowCompare(RESOURCE_TYPE, tenantId, true);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                shadowTaint(RESOURCE_TYPE, tenantId, rs.getString("tenant_id"));
                                out.add(map(rs));
                            }
                        }
                    }
                    return out;
                });
    }

    private static ExecutionIntent map(ResultSet rs) throws SQLException {
        int toolVersion = rs.getInt("tool_version");
        Integer toolVersionOrNull = rs.wasNull() ? null : toolVersion;
        return new ExecutionIntent(
                rs.getString("intent_id"),
                rs.getString("tenant_id"),
                rs.getString("run_id"),
                rs.getString("invocation_id"),
                rs.getString("tool_id"),
                toolVersionOrNull,
                rs.getString("args_fingerprint"),
                rs.getString("approval_id"),
                ExecutionIntent.IntentState.valueOf(rs.getString("intent_state")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
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
