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

import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
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
 */
public class JdbcCapabilitySnapshotRepository implements CapabilitySnapshotPort {

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

    public JdbcCapabilitySnapshotRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
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
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next()
                                    ? Optional.of(map(rs))
                                    : Optional.<CapabilitySnapshot>empty();
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
}
