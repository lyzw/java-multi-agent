package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.orchestration.CapAgentChildRef;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.CapAgentChildRefPort;

/**
 * {@link CapAgentChildRefPort} 的 PostgreSQL 实现（多级 Agent v1.1 · 06 变更补充「关系数据」）。
 *
 * <p>⚠️ <b>幂等插入</b>：{@code ON CONFLICT DO NOTHING} —— 同一父 release 重复投影
 * 同一子 release 不产生第二行（唯一约束同时防止「同一父 release 重复声明同一子 release」）。
 */
public class JdbcCapAgentChildRefRepository implements CapAgentChildRefPort {

    private static final String SQL_INSERT = """
            INSERT INTO cap_agent_child_ref
                (tenant_id, parent_agent_id, parent_release_version,
                 child_agent_id, child_release_version, ordinal, required, created_at)
            VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, parent_agent_id, parent_release_version,
                         child_agent_id, child_release_version) DO NOTHING
            """;

    private static final String SQL_LIST_BY_PARENT = """
            SELECT tenant_id::text, parent_agent_id::text, parent_release_version,
                   child_agent_id::text, child_release_version, ordinal, required, created_at
            FROM cap_agent_child_ref
            WHERE tenant_id = ?::uuid AND parent_agent_id = ?::uuid
              AND parent_release_version = ?
            ORDER BY ordinal ASC
            """;

    private static final String SQL_LIST_BY_PARENT_AGENT = """
            SELECT tenant_id::text, parent_agent_id::text, parent_release_version,
                   child_agent_id::text, child_release_version, ordinal, required, created_at
            FROM cap_agent_child_ref
            WHERE tenant_id = ?::uuid AND parent_agent_id = ?::uuid
            ORDER BY parent_release_version ASC, ordinal ASC
            """;

    private static final String SQL_LIST_BY_CHILD = """
            SELECT tenant_id::text, parent_agent_id::text, parent_release_version,
                   child_agent_id::text, child_release_version, ordinal, required, created_at
            FROM cap_agent_child_ref
            WHERE tenant_id = ?::uuid AND child_agent_id = ?::uuid
            ORDER BY parent_agent_id ASC, parent_release_version ASC, ordinal ASC
            """;

    private static final String SQL_LIST_BY_TENANT = """
            SELECT tenant_id::text, parent_agent_id::text, parent_release_version,
                   child_agent_id::text, child_release_version, ordinal, required, created_at
            FROM cap_agent_child_ref
            WHERE tenant_id = ?::uuid
            ORDER BY parent_agent_id ASC, parent_release_version ASC, ordinal ASC
            """;

    private final DataSource dataSource;

    public JdbcCapAgentChildRefRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean insert(@NonNull CapAgentChildRef ref) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(ref.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, ref.tenantId());
                        ps.setString(2, ref.parentAgentId());
                        ps.setInt(3, ref.parentReleaseVersion());
                        ps.setString(4, ref.childAgentId());
                        ps.setInt(5, ref.childReleaseVersion());
                        ps.setInt(6, ref.ordinal());
                        ps.setBoolean(7, ref.required());
                        ps.setTimestamp(8, Timestamp.from(ref.createdAt()));
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public @NonNull List<CapAgentChildRef> listByParentRelease(@NonNull String tenantId,
                                                                @NonNull String parentAgentId,
                                                                int parentReleaseVersion) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<CapAgentChildRef> rows = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_BY_PARENT)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, parentAgentId);
                        ps.setInt(3, parentReleaseVersion);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(map(rs));
                            }
                        }
                    }
                    return rows;
                });
    }

    @Override
    public @NonNull List<CapAgentChildRef> listByParentAgent(@NonNull String tenantId,
                                                              @NonNull String parentAgentId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<CapAgentChildRef> rows = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_BY_PARENT_AGENT)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, parentAgentId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(map(rs));
                            }
                        }
                    }
                    return rows;
                });
    }

    @Override
    public @NonNull List<CapAgentChildRef> listByChildAgent(@NonNull String tenantId,
                                                              @NonNull String childAgentId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<CapAgentChildRef> rows = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_BY_CHILD)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, childAgentId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(map(rs));
                            }
                        }
                    }
                    return rows;
                });
    }

    @Override
    public @NonNull List<CapAgentChildRef> listByTenant(@NonNull String tenantId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<CapAgentChildRef> rows = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_BY_TENANT)) {
                        ps.setString(1, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(map(rs));
                            }
                        }
                    }
                    return rows;
                });
    }

    private static CapAgentChildRef map(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new CapAgentChildRef(
                rs.getString("tenant_id"),
                rs.getString("parent_agent_id"),
                rs.getInt("parent_release_version"),
                rs.getString("child_agent_id"),
                rs.getInt("child_release_version"),
                rs.getInt("ordinal"),
                rs.getBoolean("required"),
                createdAt == null ? java.time.Instant.EPOCH : createdAt.toInstant());
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }
}
