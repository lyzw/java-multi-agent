package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import javax.sql.DataSource;

import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;

/**
 * {@link CapToolDefPort} 的 PostgreSQL 实现（W-PERM-P1）。
 *
 * <p>platform 级对象（{@code cap_tool_def} <b>不启用 RLS</b>），读取不受租户边界限制；
 * 但仍需绑定租户上下文 —— 同一事务内若还访问了带 RLS 的表，缺少上下文会被 RLS 直接拒绝。
 */
public class JdbcCapToolDefRepository implements CapToolDefPort {

    private static final String COLUMNS = """
            id, version, name, kind, risk_level, mcp_server_id, mcp_tool_name,
            input_schema::text, param_policies::text, config_fingerprint,
            schema_version, created_at, published_at
            """;

    private static final String SQL_FIND = "SELECT " + COLUMNS
            + " FROM cap_tool_def WHERE id = ? AND version = ?";

    private static final String SQL_FIND_LATEST_PUBLISHED = "SELECT " + COLUMNS
            + " FROM cap_tool_def WHERE id = ? AND published_at IS NOT NULL"
            + " ORDER BY version DESC LIMIT 1";

    private static final String SQL_FIND_BY_MCP_BINDING = "SELECT " + COLUMNS
            + " FROM cap_tool_def WHERE mcp_server_id = ? AND mcp_tool_name = ?"
            + " AND published_at IS NOT NULL ORDER BY version DESC LIMIT 1";

    private final DataSource dataSource;

    public JdbcCapToolDefRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Optional<CapToolDef> find(@NonNull String toolId, int version) {
        return query(SQL_FIND, ps -> {
            ps.setString(1, toolId);
            ps.setInt(2, version);
        });
    }

    @Override
    public @NonNull Optional<CapToolDef> findLatestPublished(@NonNull String toolId) {
        return query(SQL_FIND_LATEST_PUBLISHED, ps -> ps.setString(1, toolId));
    }

    @Override
    public @NonNull Optional<CapToolDef> findByMcpBinding(@NonNull String mcpServerId,
                                                           @NonNull String mcpToolName) {
        return query(SQL_FIND_BY_MCP_BINDING, ps -> {
            ps.setString(1, mcpServerId);
            ps.setString(2, mcpToolName);
        });
    }

    private @NonNull Optional<CapToolDef> query(@NonNull String sql,
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

    private static CapToolDef map(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new CapToolDef(
                rs.getString("id"),
                rs.getInt("version"),
                rs.getString("name"),
                requireKind(rs.getString("kind")),
                CapabilityRiskLevel.parse(rs.getString("risk_level")),
                rs.getString("mcp_server_id"),
                rs.getString("mcp_tool_name"),
                rs.getString("input_schema"),
                rs.getString("param_policies"),
                rs.getString("config_fingerprint"),
                rs.getInt("schema_version"),
                rs.getTimestamp("created_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant());
    }

    /**
     * ⚠️ <b>非法 kind 一律抛错</b>（fail-closed）：<b>绝不兜底成 {@code NATIVE}</b> ——
     * 那会把「类型未知的 MCP 工具」当成原生工具放行，让本该走 MCP 绑定校验的调用绕过它。
     * DB 有 CHECK 约束兜底，真出现非法值说明数据被绕过了约束。
     */
    private static @NonNull CapToolKind requireKind(@Nullable String raw) {
        CapToolKind kind = CapToolKind.parse(raw);
        if (kind == null) {
            throw new IllegalStateException("Tool 定义含非法 kind 取值: " + raw);
        }
        return kind;
    }

    /**
     * platform 级读取用的租户上下文。
     *
     * <p>⚠️ <b>零 UUID 哨兵</b>：{@code current_tenant_id()} 的实现是
     * {@code NULLIF(current_setting('app.tenant_id'),'')::UUID}，
     * 绑非 UUID 文本会直接类型转换报错。零 UUID 是合法 UUID 且不对应任何真实租户。
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
