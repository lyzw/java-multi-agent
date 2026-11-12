package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.AgentReleaseArchivePort;

/**
 * {@link AgentReleaseArchivePort} 的 PostgreSQL 实现（2026-11-09 · 权威源定 A）。
 *
 * <p>⚠️ <b>本类写的是归档，不是真源</b>：
 * {@code agent_version.definition} 由 {@code cap_agent_def} 派生写入，
 * 运行期不读它 —— 它存在的唯一理由是
 * <b>维持 {@code run → execution_snapshot → agent_version} 的外键链</b>
 * （{@code run_snapshot_fk} / {@code execution_snapshot_version_fk}）。
 *
 * <p>⚠️ <b>不可覆盖</b>：{@code agent_version} 是不可变版本行
 * （唯一约束 {@code (tenant_id, agent_id, version_number)}），
 * 重复归档用 {@code ON CONFLICT DO NOTHING} 并返回 {@code false}。
 *
 * <p>⚠️ <b>{@code context_policy} 由调用方传入，本仓储不做二次解析</b>：
 * 值来自 {@code AgentReleaseArchive.deriveContextPolicy(agent)}，
 * 与发布期门禁、运行期读取共用同一归一化实现。存量版本行幂等补插快照时
 * 沿用同一参数，但**不回写**既有不可变快照（符合 [D4]「在途 Run 沿用原快照」）。
 */
public class JdbcAgentReleaseArchiveRepository implements AgentReleaseArchivePort {

    private static final Logger log = LoggerFactory.getLogger(
            JdbcAgentReleaseArchiveRepository.class);

    /**
     * DEC-9 运行期容量配置快照（与 {@code application.yml} 的 {@code agent.run.*} 一致）。
     *
     * <p>⚠️ 与 {@code JdbcAgentManageRepository} 同口径：平台默认档，
     * 不是该租户当时的生效值（运行期当前不消费该列）。
     */
    private static final String RUN_CONFIG_DEFAULTS =
            "{\"maxCostPerRun\":\"2.00\",\"maxLlmCalls\":12,\"maxSteps\":20,"
                    + "\"approvalRequired\":\"OPTIONAL\"}";

    private static final String SQL_INSERT_VERSION = """
            INSERT INTO agent_version
                (tenant_id, agent_id, version_number, definition, published_at, created_at)
            VALUES (?::uuid, ?::uuid, ?, ?::jsonb, now(), now())
            ON CONFLICT (tenant_id, agent_id, version_number) DO NOTHING
            RETURNING version_id::text
            """;

    private static final String SQL_INSERT_SNAPSHOT = """
            INSERT INTO execution_snapshot
                (tenant_id, version_id, agent_config, prompt_config, tool_contracts, run_config,
                 context_policy)
            VALUES (?::uuid, ?::uuid, ?::jsonb, '{}'::jsonb, '{}'::jsonb, ?::jsonb, ?::jsonb)
            """;

    private final DataSource dataSource;

    public JdbcAgentReleaseArchiveRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean archiveRelease(@NonNull String tenantId,
                                  @NonNull String agentId,
                                  int version,
                                  @NonNull String definition,
                                  @Nullable String agentConfig,
                                  @NonNull String contextPolicy) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> doArchive(conn, tenantId, agentId, version, definition, agentConfig,
                        contextPolicy));
    }

    private boolean doArchive(Connection conn, String tenantId, String agentId, int version,
                              String definition, @Nullable String agentConfig,
                              @NonNull String contextPolicy) throws SQLException {
        // ⚠️ **必须先有 agent 行**：agent_version 的外键指向 agent(tenant_id, agent_id)。
        //    没有 agent 行就插入 = 外键违约 → 整笔发布事务回滚。
        //    这里显式判一次并给出可读原因，而不是让外键以 PSQLException 冒泡成 500。
        if (!agentExists(conn, tenantId, agentId)) {
            throw new IllegalStateException(
                    "归档 Agent 发布失败：agent 行不存在（agent=" + agentId + "）。"
                            + "治理通道发布前必须先登记 Agent 主体。");
        }
        Optional<String> versionId = insertVersion(conn, tenantId, agentId, version, definition);
        boolean inserted = versionId.isPresent();
        if (!inserted) {
            // ⚠️ 已存在同版本行（不可变版本行不覆盖）。
            //    这**不是错误**：说明该版本此前已归档过（幂等重放）。
            //    ⚠️ 但「版本行已存在」**不等于**「执行快照也已存在」：
            //    存量行可能来自旧通道（直发端点 / 夹具），没有伴随快照 ——
            //    而没有快照的版本行同样让 run.snapshot_id 外键违约。
            //    故此处按 version_id 回查并**幂等补插**快照（快照无唯一约束，
            //    用 NOT EXISTS 守卫防重复，与 ItTenantProvisioner 同一教训）。
            log.debug("Agent 发布归档：版本行已存在，幂等补齐快照（tenant={}, agent={}, version={}）",
                    tenantId, agentId, version);
            versionId = findVersionId(conn, tenantId, agentId, version);
            if (versionId.isEmpty()) {
                throw new IllegalStateException(
                        "归档 Agent 发布失败：版本行插入被跳过但回查不到版本行"
                                + "（tenant=" + tenantId + ", agent=" + agentId
                                + ", version=" + version + "）—— 数据不一致，拒绝发布");
            }
        }
        insertSnapshotIfAbsent(conn, tenantId, versionId.get(), agentConfig, contextPolicy);
        return inserted;
    }

    /**
     * 回查既有的版本行主键（{@code ON CONFLICT DO NOTHING} 没返回行时用）。
     */
    private @NonNull Optional<String> findVersionId(Connection conn, String tenantId,
                                                     String agentId, int version)
            throws SQLException {
        String sql = "SELECT version_id::text FROM agent_version "
                + "WHERE tenant_id = ?::uuid AND agent_id = ?::uuid AND version_number = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, agentId);
            ps.setInt(3, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private void insertSnapshotIfAbsent(Connection conn, String tenantId, String versionId,
                                        @Nullable String agentConfig,
                                        @NonNull String contextPolicy) throws SQLException {
        // ⚠️ NOT EXISTS 守卫而不是唯一约束：execution_snapshot 只有
        //    (tenant_id, snapshot_id) 主键，快照 id 每次都是新随机值 ——
        //    ON CONFLICT 永远不触发，「幂等」就成了每轮泄漏一行
        //    （ItTenantProvisioner 的实测教训）。快照行被不可变触发器保护、删不掉。
        String guard = "SELECT 1 FROM execution_snapshot "
                + "WHERE tenant_id = ?::uuid AND version_id = ?::uuid";
        try (PreparedStatement ps = conn.prepareStatement(guard)) {
            ps.setString(1, tenantId);
            ps.setString(2, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
        }
        insertSnapshot(conn, tenantId, versionId, agentConfig, contextPolicy);
    }

    private @NonNull Optional<String> insertVersion(Connection conn, String tenantId,
                                                     String agentId, int version,
                                                     String definition) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_VERSION)) {
            ps.setString(1, tenantId);
            ps.setString(2, agentId);
            ps.setInt(3, version);
            ps.setString(4, definition == null || definition.isBlank() ? "{}" : definition);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("version_id")) : Optional.empty();
            }
        }
    }

    private void insertSnapshot(Connection conn, String tenantId, String versionId,
                                @Nullable String agentConfig,
                                @NonNull String contextPolicy) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_SNAPSHOT)) {
            ps.setString(1, tenantId);
            ps.setString(2, versionId);
            ps.setString(3, agentConfig == null || agentConfig.isBlank() ? "{}" : agentConfig);
            ps.setString(4, RUN_CONFIG_DEFAULTS);
            ps.setString(5, contextPolicy);
            ps.executeUpdate();
        }
    }

    private static boolean agentExists(Connection conn, String tenantId, String agentId)
            throws SQLException {
        String sql = "SELECT 1 FROM agent WHERE tenant_id = ?::uuid AND agent_id = ?::uuid";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }

    /**
     * 生成一个确定性的版本主键（仅用于测试夹具的可预期 id）。
     *
     * @param tenantId 租户标识
     * @param agentId  Agent 标识
     * @param version  版本号
     * @return 确定性 UUID
     */
    @NonNull
    public static String deterministicVersionId(@NonNull String tenantId,
                                                @NonNull String agentId,
                                                int version) {
        return UUID.nameUUIDFromBytes(
                (tenantId + "/" + agentId + "/" + version)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }
}
