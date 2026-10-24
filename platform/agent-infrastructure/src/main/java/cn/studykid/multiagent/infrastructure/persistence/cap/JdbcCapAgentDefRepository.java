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

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

/**
 * {@link CapAgentDefPort} 的 PostgreSQL 实现（§6.1 / 06 §1.4 · W-PERM-P0）。
 *
 * <p>⚠️ <b>写入是「插入不可变版本行」，不是更新</b>：同一
 * {@code (tenantId, agentId, version)} 已存在时返回 {@code false} 且
 * <b>不覆盖</b>（DB 层另有触发器封禁对已发布行的修改）。
 * 改配置只能发新版本（{@code version+1}）。
 */
public class JdbcCapAgentDefRepository implements CapAgentDefPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SQL_INSERT = """
            INSERT INTO cap_agent_def
                (tenant_id, agent_id, version, name, description, roles, enabled_skills,
                 skill_bundles, capability_tags, overrides, config_fingerprint,
                 schema_version, created_at, updated_at, published_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                    ?::jsonb, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, agent_id, version) DO NOTHING
            """;

    private static final String SQL_FIND = """
            SELECT tenant_id::text, agent_id::text, version, name, description,
                   roles::text, enabled_skills::text, skill_bundles::text,
                   capability_tags::text, overrides::text, config_fingerprint,
                   schema_version, created_at, published_at
            FROM cap_agent_def
            WHERE tenant_id = ?::uuid AND agent_id = ?::uuid AND version = ?
            """;

    private static final String SQL_FIND_LATEST = """
            SELECT tenant_id::text, agent_id::text, version, name, description,
                   roles::text, enabled_skills::text, skill_bundles::text,
                   capability_tags::text, overrides::text, config_fingerprint,
                   schema_version, created_at, published_at
            FROM cap_agent_def
            WHERE tenant_id = ?::uuid AND agent_id = ?::uuid
            ORDER BY version DESC
            LIMIT 1
            """;

    private static final String SQL_FIND_LATEST_PUBLISHED = """
            SELECT tenant_id::text, agent_id::text, version, name, description,
                   roles::text, enabled_skills::text, skill_bundles::text,
                   capability_tags::text, overrides::text, config_fingerprint,
                   schema_version, created_at, published_at
            FROM cap_agent_def
            WHERE tenant_id = ?::uuid AND agent_id = ?::uuid
              AND published_at IS NOT NULL
            ORDER BY version DESC
            LIMIT 1
            """;

    /**
     * 每 Agent 一行的最高版本（按 {@code agent_id} 分组取 {@code MAX(version)}）。
     *
     * <p>⚠️ 与 {@code JdbcCapMcpServerDefRepository} 的 {@code listAll} 同口径；
     * ⚠️ {@code cap_agent_def} 是 RLS 表 —— 必须经 {@code tenantConnection(tenantId)}
     * 绑定租户上下文后查询（外层 WHERE tenant_id 与 RLS 策略双保险）。
     */
    private static final String SQL_LIST_LATEST_PER_AGENT = """
            SELECT tenant_id::text, agent_id::text, version, name, description,
                   roles::text, enabled_skills::text, skill_bundles::text,
                   capability_tags::text, overrides::text, config_fingerprint,
                   schema_version, created_at, published_at
            FROM cap_agent_def
            WHERE tenant_id = ?::uuid
              AND (agent_id, version) IN (
                  SELECT agent_id, MAX(version)
                    FROM cap_agent_def
                   WHERE tenant_id = ?::uuid
                   GROUP BY agent_id
              )
            ORDER BY agent_id ASC
            """;

    private final DataSource dataSource;

    public JdbcCapAgentDefRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean insert(@NonNull CapAgentDef def) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(def.tenantId()),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, def.tenantId());
                        ps.setString(2, def.agentId());
                        ps.setInt(3, def.version());
                        ps.setString(4, def.name());
                        ps.setString(5, def.description());
                        ps.setString(6, toJson(def.roles()));
                        ps.setString(7, toJson(def.enabledSkills()));
                        ps.setString(8, toJson(def.skillBundles()));
                        ps.setString(9, toJson(def.capabilityTags()));
                        ps.setString(10, def.overrides());
                        ps.setString(11, def.configFingerprint());
                        ps.setInt(12, def.schemaVersion());
                        ps.setTimestamp(13, Timestamp.from(def.createdAt()));
                        ps.setTimestamp(14, Timestamp.from(def.createdAt()));
                        if (def.publishedAt() == null) {
                            ps.setNull(15, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(15, Timestamp.from(def.publishedAt()));
                        }
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public @NonNull Optional<CapAgentDef> find(@NonNull String tenantId,
                                                @NonNull String agentId,
                                                int version) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, agentId);
                        ps.setInt(3, version);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.<CapAgentDef>empty();
                        }
                    }
                });
    }

    @Override
    public @NonNull Optional<CapAgentDef> findLatest(@NonNull String tenantId,
                                                      @NonNull String agentId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_LATEST)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, agentId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.<CapAgentDef>empty();
                        }
                    }
                });
    }

    @Override
    public @NonNull Optional<CapAgentDef> findLatestPublished(@NonNull String tenantId,
                                                               @NonNull String agentId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_LATEST_PUBLISHED)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, agentId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.<CapAgentDef>empty();
                        }
                    }
                });
    }

    @Override
    public @NonNull List<CapAgentDef> listLatestPerAgent(@NonNull String tenantId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<CapAgentDef> rows = new ArrayList<>();
                    try (PreparedStatement ps =
                                 conn.prepareStatement(SQL_LIST_LATEST_PER_AGENT)) {
                        ps.setString(1, tenantId);
                        ps.setString(2, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(map(rs));
                            }
                        }
                    }
                    return rows;
                });
    }

    private static CapAgentDef map(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new CapAgentDef(
                rs.getString("tenant_id"),
                rs.getString("agent_id"),
                rs.getInt("version"),
                rs.getString("name"),
                rs.getString("description"),
                fromJson(rs.getString("roles")),
                fromJson(rs.getString("enabled_skills")),
                fromJson(rs.getString("skill_bundles")),
                fromJson(rs.getString("capability_tags")),
                rs.getString("overrides"),
                rs.getString("config_fingerprint"),
                rs.getInt("schema_version"),
                rs.getTimestamp("created_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant());
    }

    private static String toJson(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化 JSON 数组失败", e);
        }
    }

    private static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JacksonException e) {
            // ⚠️ 存量脏数据不应让整个列表读取失败。
            //    但**不能**静默当空列表 —— 那会让「本来启用了 3 个 Skill」
            //    被读成「一个都没启用」，授权被无声放宽。
            //    故抛出，让上层 fail-closed。
            throw new IllegalStateException("解析 JSON 数组失败: " + json, e);
        }
    }

    private static TenantContext tenantContext(String tenantId) {
        return new TenantContext(tenantId, "system", Set.of(), 0L);
    }
}
