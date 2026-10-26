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

import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.SkillDescription;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;

/**
 * {@link CapSkillDefPort} 的 PostgreSQL 实现（W-PERM-P1）。
 *
 * <p>platform 级对象（{@code cap_skill_def} <b>不启用 RLS</b>）。
 *
 * <p>⚠️ <b>{@code declaredMcp[].toolAllowlist} 的 null 与 [] 必须区分</b>（§4.4）：
 * {@code null} = 未配置（取 ServerAllowedOps 全集），{@code []} = 显式不允许任何操作。
 * 故 DTO 用 {@code @Nullable List<String>} 承载 —— Jackson 会把「键缺失」与「显式 null」
 * 都映射成 {@code null}，把 {@code []} 映射成空列表，语义得以保留。
 */
public class JdbcCapSkillDefRepository implements CapSkillDefPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COLUMNS = """
            id, version, name, description, purpose, usage::text, declared_tools::text, declared_mcp::text,
            risk_ceiling, param_policies::text, required_caps::text, config_fingerprint,
            schema_version, created_at, published_at
            """;

    private static final String SQL_FIND = "SELECT " + COLUMNS
            + " FROM cap_skill_def WHERE id = ? AND version = ?";

    /**
     * 插入一条不可变版本行（§6.3 第 1 条 · W-PERM-P5）。
     *
     * <p>⚠️ <b>ON CONFLICT DO NOTHING 而非 DO UPDATE</b>：已发布版本行不可变（§6.1）。
     */
    private static final String SQL_INSERT = """
            INSERT INTO cap_skill_def
                (id, version, name, description, purpose, usage, declared_tools, declared_mcp,
                 risk_ceiling, param_policies, required_caps, namespace,
                 config_fingerprint, schema_version, created_at, updated_at, published_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, 'platform',
                    ?, ?, ?, ?, ?)
            ON CONFLICT (id, version) DO NOTHING
            """;
    // ⚠️ 参数位次（两位描述改造）：
    //    1 id / 2 version / 3 name / 4 description(遗留单级描述列，新入库一律 NULL)
    //    / 5 purpose / 6 usage · jsonb / 7 declared_tools · jsonb / 8 declared_mcp · jsonb
    //    / 9 risk_ceiling / 10 param_policies · jsonb / 11 required_caps · jsonb
    //    / 12 config_fingerprint / 13 schema_version / 14 created_at / 15 updated_at / 16 published_at

    private static final String SQL_FIND_LATEST_PUBLISHED = "SELECT " + COLUMNS
            + " FROM cap_skill_def WHERE id = ? AND published_at IS NOT NULL"
            + " ORDER BY version DESC LIMIT 1";

    /**
     * 每个逻辑 id 的最高版本行（只读目录，见 {@link CapSkillDefPort#listAll()}）。
     *
     * <p>⚠️ {@code DISTINCT ON} + 首列一致的排序键（PG 强制要求）。
     */
    private static final String SQL_LIST_ALL = "SELECT DISTINCT ON (id) " + COLUMNS
            + " FROM cap_skill_def ORDER BY id, version DESC";

    private final DataSource dataSource;

    public JdbcCapSkillDefRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean insert(@NonNull CapSkillDef def) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, def.id());
                        ps.setInt(2, def.version());
                        ps.setString(3, def.name());
                        // 旧单级 description 列：两级语义只走 purpose / usage，遗留列新入库一律 NULL
                        ps.setNull(4, java.sql.Types.VARCHAR);
                        if (def.description() == null) {
                            ps.setNull(5, java.sql.Types.VARCHAR);
                            ps.setNull(6, java.sql.Types.LONGVARCHAR);
                        } else {
                            ps.setString(5, def.description().purpose());
                            ps.setString(6, toJson(def.description().usage()));
                        }
                        ps.setString(7, toJson(def.declaredTools()));
                        ps.setString(8, toJsonMcpDeclarations(def.declaredMcp()));
                        ps.setString(9, def.riskCeiling().name());
                        ps.setString(10, def.paramPolicies());
                        ps.setString(11, toJson(def.requiredCaps()));
                        ps.setString(12, def.configFingerprint());
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
    public @NonNull Optional<CapSkillDef> find(@NonNull String skillId, int version) {
        return query(SQL_FIND, ps -> {
            ps.setString(1, skillId);
            ps.setInt(2, version);
        });
    }

    @Override
    public @NonNull Optional<CapSkillDef> findLatestPublished(@NonNull String skillId) {
        return query(SQL_FIND_LATEST_PUBLISHED, ps -> ps.setString(1, skillId));
    }

    private @NonNull Optional<CapSkillDef> query(@NonNull String sql,
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
    public @NonNull List<CapSkillDef> listAll() {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_ALL);
                         ResultSet rs = ps.executeQuery()) {
                        List<CapSkillDef> out = new java.util.ArrayList<>();
                        while (rs.next()) {
                            out.add(map(rs));
                        }
                        return List.copyOf(out);
                    }
                });
    }

    private static CapSkillDef map(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new CapSkillDef(
                rs.getString("id"),
                rs.getInt("version"),
                rs.getString("name"),
                readDescription(rs.getString("purpose"), rs.getString("usage")),
                readStringList(rs.getString("declared_tools")),
                readMcpDeclarations(rs.getString("declared_mcp")),
                CapabilityRiskLevel.parse(rs.getString("risk_ceiling")),
                rs.getString("param_policies"),
                readStringList(rs.getString("required_caps")),
                rs.getString("config_fingerprint"),
                rs.getInt("schema_version"),
                rs.getTimestamp("created_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant());
    }

    /**
     * 组装两级描述。
     *
     * <p>⚠️ 存量行只有遗留单级 {@code description}、两级列为 NULL，
     * 此时描述视为缺省（<b>不得</b>把遗留 description 充当 purpose ——
     * 选型信号与执行指引是新的行为内容，混装旧文本会误导模型）。
     */
    private static @Nullable SkillDescription readDescription(
            @Nullable String purpose, @Nullable String usageJson) {
        if (purpose == null || purpose.isBlank()) {
            return null;
        }
        List<SkillDescription.SkillUsage> usage;
        if (usageJson == null || usageJson.isBlank()) {
            usage = List.of();
        } else {
            try {
                usage = MAPPER.readValue(usageJson,
                        new TypeReference<List<SkillUsageDto>>() {
                        }).stream()
                        .map(dto -> new SkillDescription.SkillUsage(dto.when(), dto.how()))
                        .toList();
            } catch (JacksonException e) {
                // ⚠️ 读失败要显炸：把「指引存在」读成「没有指引」是行为侧的静默放宽
                throw new IllegalStateException("解析 usage 失败: " + usageJson, e);
            }
        }
        return new SkillDescription(purpose, usage);
    }

    private static @NonNull List<String> readStringList(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JacksonException e) {
            // ⚠️ 与 P0 既有实现一致：存量脏数据不能静默当空列表
            //    （那会把「声明了 3 个 Tool」读成「一个都没声明」，授权被无声放宽）
            throw new IllegalStateException("解析 JSON 数组失败: " + json, e);
        }
    }

    private static @NonNull List<CapSkillDef.McpDeclaration> readMcpDeclarations(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<McpDeclarationDto> dtos =
                    MAPPER.readValue(json, new TypeReference<List<McpDeclarationDto>>() {
                    });
            return dtos.stream()
                    .map(dto -> new CapSkillDef.McpDeclaration(dto.serverId(), dto.toolAllowlist()))
                    .toList();
        } catch (JacksonException e) {
            throw new IllegalStateException("解析 declaredMcp 失败: " + json, e);
        }
    }

    /**
     * {@code declaredMcp[]} 的传输形态。
     *
     * @param serverId      MCP Server 标识
     * @param toolAllowlist 操作白名单；<b>{@code null} ≠ {@code []}</b>（§4.4）
     */
    private record McpDeclarationDto(@NonNull String serverId, @Nullable List<String> toolAllowlist) {
    }

    /**
     * {@code usage[]}（2级操作指引）的传输形态。
     */
    private record SkillUsageDto(@NonNull String when, @NonNull String how) {
    }

    /**
     * 序列化为 JSON 文本。
     *
     * <p>⚠️ 序列化失败<b>抛异常</b>而非写空值：写空数组会让
     * 「声明了 3 个 MCP server」被读成「一个都没声明」，授权被无声放宽。
     */
    private static @NonNull String toJson(@NonNull Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化 JSON 失败", e);
        }
    }

    /**
     * 序列化 {@code declaredMcp}。
     *
     * <p>⚠️ <b>{@code null} 与 {@code []} 必须区分</b>（§4.4）：
     * {@code null} = 未配置（取 ServerAllowedOps 全集），
     * {@code []} = 显式不允许任何操作。
     * 故 DTO 用可空的 {@code toolAllowlist}，Jackson 会把「键缺失」与「显式 null」
     * 都写成 {@code null}，把 {@code []} 写成空数组，语义得以保留。
     */
    private static @NonNull String toJsonMcpDeclarations(
            @NonNull List<CapSkillDef.McpDeclaration> declarations) {
        return toJson(declarations.stream()
                .map(d -> new McpDeclarationDto(d.serverId(), d.toolAllowlist()))
                .toList());
    }

    /**
     * platform 级读取用的租户上下文（零 UUID 哨兵，见
     * {@link JdbcCapToolDefRepository} 的同名说明）。
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
