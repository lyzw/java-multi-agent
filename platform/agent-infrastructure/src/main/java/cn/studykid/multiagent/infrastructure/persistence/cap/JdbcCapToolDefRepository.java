package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapHttpToolConfig;
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
            http_method, http_url, http_timeout_ms,
            input_schema::text, param_policies::text, config_fingerprint,
            schema_version, created_at, published_at
            """;

    private static final String SQL_FIND = "SELECT " + COLUMNS
            + " FROM cap_tool_def WHERE id = ? AND version = ?";

    /**
     * 插入一条不可变版本行（§6.3 第 1 条 · W-PERM-P5）。
     *
     * <p>⚠️ <b>ON CONFLICT DO NOTHING 而非 DO UPDATE</b>：
     * 已发布版本行不可变（§6.1）；重复插入必须<b>保留原行</b>。
     * 若用 DO UPDATE，一次误重放就会<b>改写已发布内容</b>。
     */
    private static final String SQL_INSERT = """
            INSERT INTO cap_tool_def
                (id, version, name, kind, risk_level, mcp_server_id, mcp_tool_name,
                 http_method, http_url, http_timeout_ms,
                 input_schema, param_policies, namespace, config_fingerprint,
                 schema_version, created_at, updated_at, published_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'platform', ?, ?, ?, ?, ?)
            ON CONFLICT (id, version) DO NOTHING
            """;

    private static final String SQL_FIND_LATEST_PUBLISHED = "SELECT " + COLUMNS
            + " FROM cap_tool_def WHERE id = ? AND published_at IS NOT NULL"
            + " ORDER BY version DESC LIMIT 1";

    private static final String SQL_FIND_BY_MCP_BINDING = "SELECT " + COLUMNS
            + " FROM cap_tool_def WHERE mcp_server_id = ? AND mcp_tool_name = ?"
            + " AND published_at IS NOT NULL ORDER BY version DESC LIMIT 1";

    /**
     * 每个逻辑 id 的最高版本行。
     *
     * <p>⚠️ 用 {@code DISTINCT ON} 而非 {@code GROUP BY + MAX}：
     * 后者只能拿到版本号，取不到整行；前者一次扫描即得「每个 id 的最高版本整行」。
     * ⚠️ 排序键必须与 DISTINCT ON 的首列一致（PG 强制要求）。
     */
    private static final String SQL_LIST_ALL = "SELECT DISTINCT ON (id) " + COLUMNS
            + " FROM cap_tool_def ORDER BY id, version DESC";

    /**
     * 按 MCP Server 反查其下挂的 Tool（第三十四批）。
     *
     * <p>⚠️ 用 {@code DISTINCT ON (mcp_tool_name)} 取「每个远端工具名的最高版本」——
     * 与 {@link #SQL_LIST_ALL} 同款口径（关系按对象维度，不按版本维度）。
     */
    private static final String SQL_LIST_BY_MCP_SERVER = "SELECT DISTINCT ON (mcp_tool_name) "
            + COLUMNS
            + " FROM cap_tool_def WHERE mcp_server_id = ?"
            + " ORDER BY mcp_tool_name, version DESC";

    private final DataSource dataSource;

    public JdbcCapToolDefRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean insert(@NonNull CapToolDef def) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, def.id());
                        ps.setInt(2, def.version());
                        ps.setString(3, def.name());
                        // ⚠️ DB 的 kind 取值域是小写（'native' / 'mcp' / 'http'）
                        ps.setString(4, def.kind().name().toLowerCase(java.util.Locale.ROOT));
                        ps.setString(5, def.riskLevel().name());
                        ps.setString(6, def.mcpServerId());
                        ps.setString(7, def.mcpToolName());
                        // ⚠️ HTTP 三件套：非 HTTP 工具必须写 NULL，
                        //    绝不能写空串或 0 —— DB 的 CHECK 会拒绝「非 HTTP 却带 http 配置」，
                        //    而空串/0 能骗过「是否为空」的判断。
                        CapHttpToolConfig http = def.httpConfig();
                        if (http == null) {
                            ps.setNull(8, java.sql.Types.VARCHAR);
                            ps.setNull(9, java.sql.Types.VARCHAR);
                            ps.setNull(10, java.sql.Types.INTEGER);
                        } else {
                            ps.setString(8, http.method());
                            ps.setString(9, http.url());
                            ps.setInt(10, http.timeoutMs());
                        }
                        ps.setString(11, def.inputSchema());
                        ps.setString(12, def.paramPolicies());
                        ps.setString(13, def.configFingerprint());
                        ps.setInt(14, def.schemaVersion());
                        ps.setTimestamp(15, Timestamp.from(def.createdAt()));
                        ps.setTimestamp(16, Timestamp.from(def.createdAt()));
                        if (def.publishedAt() == null) {
                            ps.setNull(17, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(17, Timestamp.from(def.publishedAt()));
                        }
                        return ps.executeUpdate() > 0;
                    }
                });
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

    @Override
    public @NonNull List<CapToolDef> listByMcpServer(@NonNull String mcpServerId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_BY_MCP_SERVER)) {
                        ps.setString(1, mcpServerId);
                        try (ResultSet rs = ps.executeQuery()) {
                            List<CapToolDef> out = new java.util.ArrayList<>();
                            while (rs.next()) {
                                out.add(map(rs));
                            }
                            return List.copyOf(out);
                        }
                    }
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

    @Override
    public @NonNull List<CapToolDef> listAll() {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_ALL);
                         ResultSet rs = ps.executeQuery()) {
                        List<CapToolDef> out = new java.util.ArrayList<>();
                        while (rs.next()) {
                            out.add(map(rs));
                        }
                        return List.copyOf(out);
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
                mapHttpConfig(rs),
                rs.getString("input_schema"),
                rs.getString("param_policies"),
                rs.getString("config_fingerprint"),
                rs.getInt("schema_version"),
                rs.getTimestamp("created_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant());
    }

    /**
     * 读取 HTTP 配置列。
     *
     * <p>⚠️ <b>以 method 列为准</b>：三列要么齐全要么全空（DB 有 CHECK 兜底），
     * 所以读 {@code http_method} 是否为 NULL 就足以判定「这是不是 HTTP 工具」。
     * 若三列残缺（例如绕过约束写进来的脏数据），这里<b>抛异常</b>而不是补默认值 ——
     * 补默认值会造出一个「能跑但目标错了」的 Tool，那比读不出来更危险。
     */
    private static @Nullable CapHttpToolConfig mapHttpConfig(ResultSet rs) throws SQLException {
        String method = rs.getString("http_method");
        String url = rs.getString("http_url");
        // ⚠️ 用 getObject 判空而不是「getInt + wasNull」：
        //    wasNull() 只反映**最后一次**读，调用顺序一变就静默读错 ——
        //    这里宁可多一次装箱，也要让「这一列到底是不是 NULL」与读取动作绑在一起。
        Integer timeout = (Integer) rs.getObject("http_timeout_ms");
        if (method == null) {
            if (url != null || timeout != null) {
                throw new IllegalStateException(
                        "Tool 定义含残缺的 HTTP 配置（缺方法）：URL/超时必须同时为空");
            }
            return null;
        }
        if (url == null || timeout == null) {
            throw new IllegalStateException(
                    "Tool 定义含残缺的 HTTP 配置：方法/URL/超时必须三者齐全");
        }
        return new CapHttpToolConfig(method, url, timeout);
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
