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
import org.springframework.beans.factory.annotation.Autowired;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
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
 *
 * <p><b>影子态（改造计划 WP-M1-2）</b>：写主导仓（INSERT 版本/快照 + 存在性回查），
 * 无行租户染色点；{@link #archiveRelease} 入口 {@code compare("agent_version")}；
 * GUC 绑定在 holder 中心统一执行，保留至 M4 退役批次；SQL 全显式 {@code tenant_id}
 * 谓词，RLS 退役后功能不变。
 */
public class JdbcAgentReleaseArchiveRepository implements AgentReleaseArchivePort {

    private static final Logger log = LoggerFactory.getLogger(
            JdbcAgentReleaseArchiveRepository.class);

    // ── 影子态接入（WP-M1-2，默认不启用零行为变更）────────────────────
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private volatile boolean shadowEnabled;
    private @NonNull String sentinelTenantId = "";

    /**
     * 影子态装配入口：容器里存在 {@link AuthzShadowBinding} 即开启（P1 影子期），
     * 不存在即完全关闭（零行为变更）。
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
                (agent_id, version_number, definition, published_at, created_at)
            VALUES (?::uuid, ?, ?::jsonb, now(), now())
            ON CONFLICT (agent_id, version_number) DO NOTHING
            RETURNING version_id::text
            """;

    private static final String SQL_INSERT_SNAPSHOT = """
            INSERT INTO execution_snapshot
                (version_id, agent_config, prompt_config, tool_contracts, run_config,
                 context_policy)
            VALUES (?::uuid, ?::jsonb, '{}'::jsonb, '{}'::jsonb, ?::jsonb, ?::jsonb)
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
        // ⚠️ 影子比对（P1 只记日志）：Gate 算出的 ScopeFilter vs 本次绑定租户。
        shadowCompare(tenantId);
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> doArchive(conn, tenantId, agentId, version, definition, agentConfig,
                        contextPolicy));
    }

    private boolean doArchive(Connection conn, String tenantId, String agentId, int version,
                              String definition, @Nullable String agentConfig,
                              @NonNull String contextPolicy) throws SQLException {
        // ⚠️ **必须先有全局 agent 行**：agent_version 外键指向 agent(agent_id)。
        if (!agentExists(conn, agentId)) {
            throw new IllegalStateException(
                    "归档 Agent 发布失败：Agent 主体不存在（agent=" + agentId + "）。"
                            + "治理通道发布前必须先登记 Agent 主体。");
        }
        Optional<String> versionId = insertVersion(conn, agentId, version, definition);
        boolean inserted = versionId.isPresent();
        if (!inserted) {
            log.debug("Agent 发布归档：版本行已存在，幂等补齐快照（agent={}, version={}）",
                    agentId, version);
            versionId = findVersionId(conn, agentId, version);
            if (versionId.isEmpty()) {
                throw new IllegalStateException(
                        "归档 Agent 发布失败：版本行插入被跳过但回查不到版本行"
                                + "（agent=" + agentId + ", version=" + version + "）—— 数据不一致，拒绝发布");
            }
        }
        insertSnapshotIfAbsent(conn, versionId.get(), agentConfig, contextPolicy);
        return inserted;
    }

    /**
     * 回查既有的版本行主键（{@code ON CONFLICT DO NOTHING} 没返回行时用）。
     */
    private @NonNull Optional<String> findVersionId(Connection conn,
                                                     String agentId, int version)
            throws SQLException {
        String sql = "SELECT version_id::text FROM agent_version "
                + "WHERE agent_id = ?::uuid AND version_number = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setInt(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private void insertSnapshotIfAbsent(Connection conn, String versionId,
                                        @Nullable String agentConfig,
                                        @NonNull String contextPolicy) throws SQLException {
        String guard = "SELECT 1 FROM execution_snapshot WHERE version_id = ?::uuid";
        try (PreparedStatement ps = conn.prepareStatement(guard)) {
            ps.setString(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
        }
        insertSnapshot(conn, versionId, agentConfig, contextPolicy);
    }

    private @NonNull Optional<String> insertVersion(Connection conn, String agentId,
                                                     int version, String definition)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_VERSION)) {
            ps.setString(1, agentId);
            ps.setInt(2, version);
            ps.setString(3, definition == null || definition.isBlank() ? "{}" : definition);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("version_id")) : Optional.empty();
            }
        }
    }

    private void insertSnapshot(Connection conn, String versionId,
                                @Nullable String agentConfig,
                                @NonNull String contextPolicy) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_SNAPSHOT)) {
            ps.setString(1, versionId);
            ps.setString(2, agentConfig == null || agentConfig.isBlank() ? "{}" : agentConfig);
            ps.setString(3, RUN_CONFIG_DEFAULTS);
            ps.setString(4, contextPolicy);
            ps.executeUpdate();
        }
    }

    private static boolean agentExists(Connection conn, String agentId)
            throws SQLException {
        String sql = "SELECT 1 FROM agent WHERE agent_id = ?::uuid";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }

    // ── 影子辅助（守卫要求：声明与调用分离，入口真调用）────────────

    /**
     * 影子比对：Gate 算出的 ScopeFilter vs 本次绑定租户（只记日志）。
     * 写主导仓无行租户染色点；全 SQL 显式 {@code tenant_id} 谓词 ⇒ hasTenantPredicate=true。
     */
    void shadowCompare(@NonNull String boundTenantId) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, "agent_version",
                SubjectContext.forTenant(boundTenantId), boundTenantId, true);
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
