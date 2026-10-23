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
 *
 * <p>⚠️ <b>刻意去掉 {@code @Repository} 组件扫描</b>（W-PERM-P0-T6）：
 * 本类现在被 {@code DualWriteAgentManagePort} 装饰（迁移期双写），
 * 若同时保留组件扫描，Spring 上下文里会有<b>两个</b> {@code AgentManagePort}
 * 候选 Bean → {@code NoUniqueBeanDefinitionException}，<b>整个应用无法启动</b>。
 * 装配改由 {@code ApplicationConfiguration} 显式 {@code @Bean} 完成，
 * 与 {@code runClaimPort}（P3-D07）的既有惯例一致。
 */
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
                // 2) 同事务创建执行快照（**必须有**）。
                //    ⚠️ 为什么必须有：`JdbcExecutionSnapshotResolver` 的解析 SQL 是
                //    `agent JOIN agent_version(已发布) JOIN execution_snapshot` 的**内连接**，
                //    没有快照 = 「查不到」= 该 Agent 永远不可运行（404 AGENT_SNAPSHOT_UNAVAILABLE）。
                //    而此前**没有任何生产代码路径**创建 execution_snapshot ——
                //    只有种子脚本（V1_seed.sql）会建。故经 API 发布的 Agent 一律不可运行，
                //    症状是「发布成功、跑不起来」，且报错点在 Run 创建处，离真因很远。
                //    （2026-10-23 全栈 E2E 实测发现）
                //
                //    ⚠️ 必须与版本行**同事务**：快照与版本是同一个发布事实的两面，
                //    分开提交会出现「版本已发布但无快照」的中间态 —— 那正是本缺陷本身。
                createExecutionSnapshot(conn, tenantId, versionId, storedDefinition);
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

    /**
     * 为已发布版本创建执行快照（与版本行同事务）。
     *
     * <p>⚠️ <b>内容口径</b>：
     * <ul>
     *   <li>{@code agent_config} = 该版本的 {@code definition}（Agent 自身的配置）；
     *       运行期当前**不读**该列（见 §6.6 待接线），但写进去可让快照自解释、
     *       并为后续「读路径切到 cap_agent_def」保留对照基准；</li>
     *   <li>{@code prompt_config} / {@code tool_contracts} = {@code {}}（尚无权威模型，
     *       凭空造结构等于自创契约）；</li>
     *   <li>{@code run_config} = DEC-9 的运行期容量配置，取值与
     *       {@code application.yml} 的 {@code agent.run.*} 默认值一致
     *       （max-cost-per-run=2.00 / max-llm-calls=12 / max-steps=20），
     *       与种子脚本 {@code V1_seed.sql} 的形态保持一致。</li>
     * </ul>
     *
     * <p>⚠️ <b>不与种子脚本抢口径</b>：种子里这三列是 {@code {}}，
     * 因为种子造的是「形状占位」；运行期发布的版本则带上真实 definition。
     * 两者都满足 NOT NULL 约束，且运行期当前不消费这三列。
     */
    private void createExecutionSnapshot(Connection conn, String tenantId, String versionId,
                                         @Nullable String definition) throws SQLException {
        String sql = """
                INSERT INTO execution_snapshot
                    (tenant_id, version_id, agent_config, prompt_config, tool_contracts, run_config)
                VALUES (?::uuid, ?::uuid, ?::jsonb, '{}'::jsonb, '{}'::jsonb, ?::jsonb)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, versionId);
            ps.setString(3, definition == null || definition.isBlank() ? "{}" : definition);
            ps.setString(4, RUN_CONFIG_DEFAULTS);
            ps.executeUpdate();
        }
    }

    /**
     * DEC-9 运行期容量配置快照（与 {@code application.yml} 的 {@code agent.run.*} 默认值一致）。
     *
     * <p>⚠️ 值来源是**平台默认档**，不是「该租户当时的生效值」——
     * 运行期当前不消费该列（读路径未接线），故此处取平台默认档作为形状基准；
     * 待「Run 创建时固化生效配置」落地后，须改为读取该租户当时的实际生效值。
     */
    private static final String RUN_CONFIG_DEFAULTS =
            "{\"maxCostPerRun\":\"2.00\",\"maxLlmCalls\":12,\"maxSteps\":20,"
                    + "\"approvalRequired\":\"OPTIONAL\"}";

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
