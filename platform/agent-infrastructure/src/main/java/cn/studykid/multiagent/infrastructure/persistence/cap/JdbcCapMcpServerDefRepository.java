package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.McpTrustTier;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;

/**
 * {@link CapMcpServerDefPort} 的 PostgreSQL 实现（W-PERM-P1）。
 *
 * <p>platform 级对象（{@code cap_mcp_server_def} <b>不启用 RLS</b>）。
 *
 * <p>⚠️ <b>{@code exposed_tool_allowlist} 的 null 与 [] 必须区分</b>（§4.4）：
 * {@code null} = 未配置（{@code ServerAllowedOps := ManifestOps}），
 * {@code []} = 显式空清单（{@code ServerAllowedOps := ∅}）。
 * 该列可空且无默认值，用 {@code rs.getString(...)} 读取即可天然区分
 * SQL NULL（→ {@code null}）与 JSON 空数组（→ {@code "[]"}）。
 */
public class JdbcCapMcpServerDefRepository implements CapMcpServerDefPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COLUMNS = """
            id, version, transport, endpoint, command, auth_kind, trust_tier,
            exposed_tool_allowlist::text, connection_risk, dynamic_discovery,
            config_fingerprint, credential_ref, schema_version, created_at, published_at
            """;

    private static final String SQL_FIND = "SELECT " + COLUMNS
            + " FROM cap_mcp_server_def WHERE id = ? AND version = ?";

    /**
     * 插入一条不可变版本行（§6.3 第 1 条 · W-PERM-P5）。
     *
     * <p>⚠️ <b>ON CONFLICT DO NOTHING 而非 DO UPDATE</b>：已发布版本行不可变（§6.1）。
     */
    private static final String SQL_INSERT = """
            INSERT INTO cap_mcp_server_def
                (id, version, transport, endpoint, command, auth_kind, trust_tier,
                 exposed_tool_allowlist, connection_risk, dynamic_discovery,
                 config_fingerprint, credential_ref, schema_version, created_at,
                 updated_at, published_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id, version) DO NOTHING
            """;

    private static final String SQL_FIND_LATEST_PUBLISHED = "SELECT " + COLUMNS
            + " FROM cap_mcp_server_def WHERE id = ? AND published_at IS NOT NULL"
            + " ORDER BY version DESC LIMIT 1";

    /**
     * 列出全部 MCP Server 定义（各逻辑 id 的最高版本行）。
     *
     * <p>⚠️ 用 {@code (id, version) IN (SELECT id, MAX(version) ...)} 取每 id 最高版本，
     * 与 {@code CapToolDefPort#listAll} 的「最高版本行」语义一致。
     */
    private static final String SQL_LIST_ALL = "SELECT " + COLUMNS
            + " FROM cap_mcp_server_def"
            + " WHERE (id, version) IN ("
            + "   SELECT id, MAX(version) FROM cap_mcp_server_def GROUP BY id"
            + ")"
            + " ORDER BY id ASC";

    private final DataSource dataSource;

    public JdbcCapMcpServerDefRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean insert(@NonNull CapMcpServerDef def) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, def.id());
                        ps.setInt(2, def.version());
                        // ⚠️ DB 的 transport / auth_kind / trust_tier 取值域为小写
                        ps.setString(3, def.transport().name().toLowerCase(java.util.Locale.ROOT));
                        ps.setString(4, def.endpoint());
                        ps.setString(5, def.command());
                        ps.setString(6, def.authKind().name().toLowerCase(java.util.Locale.ROOT));
                        ps.setString(7, def.trustTier().name().toLowerCase(java.util.Locale.ROOT));
                        if (def.exposedToolAllowlist() == null) {
                            // ⚠️ null ≠ []（§4.4）：未配置与显式空清单语义不同，不可混淆
                            ps.setNull(8, java.sql.Types.OTHER);
                        } else {
                            ps.setString(8, writeJson(def.exposedToolAllowlist()));
                        }
                        ps.setString(9, def.connectionRisk().name());
                        ps.setBoolean(10, def.dynamicDiscovery());
                        ps.setString(11, def.configFingerprint());
                        ps.setString(12, def.credentialRef());
                        ps.setInt(13, def.schemaVersion());
                        ps.setTimestamp(14, Timestamp.from(def.createdAt()));
                        ps.setTimestamp(15, Timestamp.from(def.createdAt()));
                        if (def.publishedAt() == null) {
                            ps.setNull(16, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(16, Timestamp.from(def.publishedAt()));
                        }
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public @NonNull Optional<CapMcpServerDef> find(@NonNull String serverId, int version) {
        return query(SQL_FIND, ps -> {
            ps.setString(1, serverId);
            ps.setInt(2, version);
        });
    }

    @Override
    public @NonNull Optional<CapMcpServerDef> findLatestPublished(@NonNull String serverId) {
        return query(SQL_FIND_LATEST_PUBLISHED, ps -> ps.setString(1, serverId));
    }

    @Override
    public @NonNull List<CapMcpServerDef> listAll() {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(),
                conn -> {
                    List<CapMcpServerDef> rows = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_ALL)) {
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(map(rs));
                            }
                        }
                    }
                    return rows;
                });
    }

    private @NonNull Optional<CapMcpServerDef> query(@NonNull String sql,
                                                     @NonNull StatementBinder binder) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        binder.bind(ps);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                        }
                    }
                });
    }

    private static CapMcpServerDef map(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new CapMcpServerDef(
                rs.getString("id"),
                rs.getInt("version"),
                require(CapMcpServerDef.Transport.parse(rs.getString("transport")), "transport"),
                rs.getString("endpoint"),
                rs.getString("command"),
                require(CapMcpServerDef.AuthKind.parse(rs.getString("auth_kind")), "auth_kind"),
                require(McpTrustTier.parse(rs.getString("trust_tier")), "trust_tier"),
                readAllowlist(rs.getString("exposed_tool_allowlist")),
                CapabilityRiskLevel.parse(rs.getString("connection_risk")),
                rs.getBoolean("dynamic_discovery"),
                rs.getString("config_fingerprint"),
                rs.getString("credential_ref"),
                rs.getInt("schema_version"),
                rs.getTimestamp("created_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant());
    }

    /**
     * ⚠️ <b>枚举解析失败即抛错</b>（fail-closed）：DB 有 CHECK 约束兜底，
     * 真出现非法值说明数据被绕过约束写入了 —— 此时<b>不得</b>兜底成某个默认值
     * （例如把非法 transport 当成 http），必须让上层拒绝。
     */
    private static <T> @NonNull T require(@Nullable T parsed, @NonNull String column) {
        if (parsed == null) {
            throw new IllegalStateException("MCP Server 定义含非法 " + column + " 取值");
        }
        return parsed;
    }

    /**
     * @param json JSON 数组文本；<b>SQL NULL 时为 {@code null}</b>
     * @return 白名单；{@code null} 表示未配置，与显式空列表语义不同（§4.4）
     */
    @Nullable
    private static List<String> readAllowlist(@Nullable String json) {
        if (json == null) {
            // ⚠️ SQL NULL：未配置 —— 不可与 "[]" 混为一谈
            return null;
        }
        if (json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("解析 exposed_tool_allowlist 失败: " + json, e);
        }
    }

    /**
     * platform 级读取用的租户上下文（零 UUID 哨兵）。
     */
    private static TenantContext platformContext() {
        return new TenantContext("00000000-0000-0000-0000-000000000000", "system",
                java.util.Set.of(), 0L);
    }

    /**
     * 序列化为 JSON 文本。
     *
     * <p>⚠️ 序列化失败<b>抛异常</b>而非写空值：写空数组会让
     * 「显式不允许任何操作」被读成「未配置」——
     * 后者按 ServerAllowedOps 全集放行，是直接的权限放宽。
     */
    private static @NonNull String writeJson(@NonNull List<String> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化 exposed_tool_allowlist 失败", e);
        }
    }

    /** 语句参数绑定器。 */
    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
