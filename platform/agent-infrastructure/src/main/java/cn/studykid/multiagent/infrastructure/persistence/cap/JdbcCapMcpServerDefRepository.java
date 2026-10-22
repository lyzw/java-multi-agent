package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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

    private static final String SQL_FIND_LATEST_PUBLISHED = "SELECT " + COLUMNS
            + " FROM cap_mcp_server_def WHERE id = ? AND published_at IS NOT NULL"
            + " ORDER BY version DESC LIMIT 1";

    private final DataSource dataSource;

    public JdbcCapMcpServerDefRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
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

    /** 语句参数绑定器。 */
    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
