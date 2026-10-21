package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.AgentManagePort;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentItem;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentVersionItem;
import cn.studykid.multiagent.ports.persistence.AuditPort;

/**
 * Agent 管理仓储（P4-D14 / G2 · FE-01 §P18 创建 / 发布）。
 *
 * <p>⚠️ <b>definition 是不可变快照</b>（FR-002）：本类<b>不提供</b>编辑已发布版本的方法，
 * 要改只能再发一个版本（{@link #publishVersion}）。
 *
 * <p>⚠️ <b>版本号并发安全</b>：{@code version_number = max(本 agent 现有版本号) + 1}，
 * 由 {@code (tenant_id, agent_id, version_number)} 唯一约束兜底并发竞态——
 * 同一 agent 并发发布时，后提交者拿到唯一冲突（SQLState {@code 23505}），
 * 转为 {@link AgentManagePort.VersionConflictException}（→ 409）。
 *
 * <p>⚠️ <b>审计 fail-closed</b>：{@code AGENT_CREATE} / {@code AGENT_PUBLISH}
 * 走 {@code AuditPort.recordRequired}，审计写入失败即抛异常（无审计的发布 = 无声变更，R-1 要防）。
 *
 * <p>⚠️ 全部走 {@code tenantConnection} 绑定 RLS；事务内显式 commit/rollback，
 * Repository 绝不自行提交借用连接。
 */
@Repository
public class JdbcAgentManageRepository implements AgentManagePort {

    /** Agent 名称长度上限（自由文本，过长是异常信号）。 */
    private static final int MAX_NAME_LENGTH = 200;

    /** Postgres 唯一约束冲突（unique_violation）。 */
    private static final String UNIQUE_VIOLATION = "23505";

    /** Postgres 非法文本表示（如非法 JSONB）。 */
    private static final String INVALID_TEXT_REPRESENTATION = "22P02";

    private final DataSource dataSource;
    private final AuditPort audit;

    public JdbcAgentManageRepository(@NonNull DataSource dataSource,
                                     @NonNull AuditPort audit) {
        this.dataSource = dataSource;
        this.audit = audit;
    }

    @Override
    public @NonNull AgentItem createAgent(@NonNull String tenantId,
                                                        @NonNull String name,
                                                        @Nullable String description,
                                                        @NonNull String creatorId)
            throws AgentManagePort.DuplicateAgentNameException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Agent 名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Agent 名称超长（>" + MAX_NAME_LENGTH + "）: " + name.length());
        }
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            conn.setAutoCommit(false);
            try {
                String sql = """
                        INSERT INTO agent (tenant_id, name, description)
                        VALUES (?::uuid, ?, ?)
                        RETURNING agent_id::text
                        """;
                String agentId;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, tenantId);
                    ps.setString(2, name);
                    ps.setString(3, description);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("Agent 插入未返回 ID");
                        }
                        agentId = rs.getString(1);
                    }
                }
                conn.commit();
                // ⚠️ 审计明细<b>不</b>放 name：name 是用户自由文本，PIPL 敏感信息扫描（fail-closed）
                //     会拒写含 UUID/数字串的名称，进而让「创建 Agent」整条失败（测试已踩到）。
                //     审计的 R-1 可追溯性由 actor+action+resourceId(agentId)+时间戳满足；
                //     名称经 audit_record→agent 关联即可还原（见 ConfigAuditPage），无需落明细。
                audit.recordRequired(tenantId, creatorId,
                        AuditPort.Actions.AGENT_CREATE, "agent", agentId,
                        Map.of());
                // ⚠️ 新建 Agent 尚无版本：versionCount=0 / latestVersionNumber=0 / 无 definition
                return new AgentItem(
                        agentId, name, description, 0, 0, null, null);
            } catch (SQLException | RuntimeException e) {
                safeRollback(conn);
                if (e instanceof SQLException se && UNIQUE_VIOLATION.equals(se.getSQLState())) {
                    // ⚠️ agent_name_per_tenant 唯一约束冲突 → 重名（409，非 400）
                    throw new AgentManagePort.DuplicateAgentNameException(name);
                }
                throw e instanceof RuntimeException re
                        ? re : new IllegalStateException("创建 Agent 失败", e);
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("创建 Agent 失败", e);
        }
    }

    @Override
    public @NonNull AgentVersionItem publishVersion(
            @NonNull String tenantId,
            @NonNull String agentId,
            @NonNull String definition,
            @NonNull String publisherId) {
        if (definition == null || definition.isBlank()) {
            throw new IllegalArgumentException("发布版本必须包含 definition（JSON）");
        }
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            Connection conn = lease.connection();
            conn.setAutoCommit(false);
            try {
                // 1) 校验 agent 存在（RLS 已限定租户，不存在即「不属于本租户」）
                if (!agentExists(conn, tenantId, agentId)) {
                    throw new AgentQueryPort.AgentNotFoundException(agentId);
                }
                // 2) 插入下一版本：版本号 = max(现有) + 1（同一语句原子计算）
                String sql = """
                        INSERT INTO agent_version
                            (tenant_id, agent_id, version_number, definition, published_at, created_at)
                        SELECT ?::uuid, ?::uuid,
                               COALESCE((
                                   SELECT MAX(version_number)
                                   FROM agent_version
                                   WHERE tenant_id = ?::uuid AND agent_id = ?::uuid
                               ), 0) + 1,
                               ?::jsonb, now(), now()
                        RETURNING version_id::text, version_number, created_at,
                                  published_at, definition::text AS stored_definition
                        """;
                String versionId;
                int versionNumber;
                Instant createdAt;
                Instant publishedAt;
                String storedDefinition;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, tenantId);
                    ps.setString(2, agentId);
                    ps.setString(3, tenantId);
                    ps.setString(4, agentId);
                    ps.setString(5, definition);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("版本插入未返回 ID");
                        }
                        versionId = rs.getString("version_id");
                        versionNumber = rs.getInt("version_number");
                        createdAt = rs.getTimestamp("created_at").toInstant();
                        publishedAt = instantOrNull(rs.getTimestamp("published_at"));
                        storedDefinition = rs.getString("stored_definition");
                    }
                }
                conn.commit();
                audit.recordRequired(tenantId, publisherId,
                        AuditPort.Actions.AGENT_PUBLISH, "agent", agentId,
                        Map.of("versionNumber", versionNumber));
                return new AgentVersionItem(
                        versionId, versionNumber, storedDefinition, publishedAt, createdAt);
            } catch (SQLException | RuntimeException e) {
                safeRollback(conn);
                if (e instanceof SQLException se) {
                    if (UNIQUE_VIOLATION.equals(se.getSQLState())) {
                        // ⚠️ (tenant_id, agent_id, version_number) 唯一约束冲突 → 并发版本竞态（409）
                        throw new AgentManagePort.VersionConflictException(
                                "版本号并发冲突，请刷新后重试（agent=" + agentId + "）");
                    }
                    if (INVALID_TEXT_REPRESENTATION.equals(se.getSQLState())) {
                        // ⚠️ definition 非法 JSON（PG 拒收 JSONB）= 请求体不合法（400）
                        throw new IllegalArgumentException(
                                "definition 不是合法 JSON: " + e.getMessage());
                    }
                }
                throw e instanceof RuntimeException re
                        ? re : new IllegalStateException("发布 Agent 版本失败", e);
            } finally {
                restoreAutoCommit(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("发布 Agent 版本失败", e);
        }
    }

    private boolean agentExists(Connection conn, String tenantId, String agentId)
            throws SQLException {
        String sql = """
                SELECT 1 FROM agent WHERE tenant_id = ?::uuid AND agent_id = ?::uuid
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Nullable
    private static Instant instantOrNull(@Nullable Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // rollback 失败不掩盖原始异常
        }
    }

    private static void restoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 同上
        }
    }

    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, new TenantContext(tenantId, "system", java.util.Set.of(), 0L));
    }
}
