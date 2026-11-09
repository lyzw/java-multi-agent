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

import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;

/**
 * {@link CapabilitySnapshotPort} 的 PostgreSQL 实现（§6.1 / 06 §1.6 · W-PERM-P0-T4）。
 *
 * <p><b>快照不可变</b>：写入用 {@code ON CONFLICT DO NOTHING}，
 * 同一 runId 的重复保存<b>不覆盖</b>既有快照（06 §2「历史可追溯」）。
 * 快照被改写 = 历史失真，事后无法解释「当时到底按哪版配置跑的」。
 *
 * <p>⚠️ 全部走 {@link TransactionalConnectionHolder}：该表启用了
 * {@code ENABLE + FORCE ROW LEVEL SECURITY}，未绑定 {@code app.tenant_id}
 * 时可见 <b>0 行</b>——症状是「快照明明写了却读不出来」，
 * 与「Run 真的没建快照」症状完全相同，极易把排查方向带偏。
 *
 * <p>⚠️ <b>P1 影子态：只观测读路径</b>：执行期能力快照。**1 处读查询**（find）已接影子，
 * 自带显式 {@code tenant_id = ?::uuid} 谓词；写路径（save）属 P2 范围。
 */
public class JdbcCapabilitySnapshotRepository implements CapabilitySnapshotPort {
    /** 影子比对/染色的资源类型（对齐 policy-mapping.yaml 的 TENANT_ONLY）。 */
    private static final String RESOURCE_TYPE = "execution_capability_snapshot";


    private static final String SQL_INSERT = """
            INSERT INTO execution_capability_snapshot
                (run_id, tenant_id, agent_id, effective_closure, agent_version,
                 config_version, config_fingerprint, authz_version, created_at)
            VALUES (?::uuid, ?::uuid, ?::uuid, ?::jsonb, ?, ?, ?, ?, ?)
            ON CONFLICT (run_id) DO NOTHING
            """;

    private static final String SQL_FIND = """
            SELECT run_id::text, tenant_id::text, agent_id::text, effective_closure::text,
                   agent_version, config_version, config_fingerprint, authz_version, created_at
            FROM execution_capability_snapshot
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            """;

    private final DataSource dataSource;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    public JdbcCapabilitySnapshotRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 影子态接线构造（测试与包内装配用；生产走 {@link #bindAuthzShadow} setter）。 */
    JdbcCapabilitySnapshotRepository(@NonNull DataSource dataSource,
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
    public boolean save(@NonNull CapabilitySnapshot snapshot) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(snapshot.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, snapshot.runId());
                        ps.setString(2, snapshot.tenantId());
                        ps.setString(3, snapshot.agentId());
                        ps.setString(4, snapshot.effectiveClosure());
                        ps.setInt(5, snapshot.agentVersion());
                        ps.setLong(6, snapshot.configVersion());
                        ps.setString(7, snapshot.configFingerprint());
                        ps.setLong(8, snapshot.authzVersion());
                        ps.setTimestamp(9, Timestamp.from(snapshot.createdAt()));
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public @NonNull Optional<CapabilitySnapshot> find(@NonNull String tenantId,
                                                      @NonNull String runId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, runId);
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

    private static CapabilitySnapshot map(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        return new CapabilitySnapshot(
                rs.getString("run_id"),
                rs.getString("tenant_id"),
                rs.getString("agent_id"),
                rs.getString("effective_closure"),
                rs.getInt("agent_version"),
                rs.getLong("config_version"),
                rs.getString("config_fingerprint"),
                rs.getLong("authz_version"),
                ts == null ? Instant.EPOCH : ts.toInstant());
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
