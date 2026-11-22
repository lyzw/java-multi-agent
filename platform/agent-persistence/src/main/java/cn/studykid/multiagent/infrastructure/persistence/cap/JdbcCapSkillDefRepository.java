package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
 * <p>⚠️ <b>不接租户级影子（P1 平台级豁免）</b>：本表<b>没有 {@code tenant_id} 列</b>、
 * 也没有 RLS 策略（{@code policy-mapping.yaml} 中无对应条目）⇒ 行<b>没有租户归属</b>，
 * 「染色」在语义上无从谈起；连接也走平台上下文而非租户上下文。
 * <p>因此本仓<b>刻意不持有 {@code ScopedDataAccess}</b>、<b>不调用影子钩子</b>：
 * 持有却不观测正是「装饰性接线」，会让 P1 的 {@code AUTHZ_MISMATCH=0} 变成假绿
 * （2026-11-09 实测：3 个 CAP 平台目录仓都曾如此，现已拆除）。
 * 该表的访问控制走平台级运维通道（{@code platform.resource.read} 等平台权限）。
 */
public class JdbcCapSkillDefRepository implements CapSkillDefPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COLUMNS = """
            id, version, name, description, purpose, usage::text, content::text, declared_tools::text, declared_mcp::text,
            risk_ceiling, param_policies::text, required_caps::text, config_fingerprint,
            schema_version, created_at, published_at
            """;

    /** SQL_PUBLISH_DRAFT：把未发布草稿行转正（WHERE 限定 published_at IS NULL）。 */
    private static final String SQL_PUBLISH_DRAFT =
            "UPDATE cap_skill_def SET published_at = ? " +
            "WHERE id = ? AND version = ? AND published_at IS NULL";

    private static final String SQL_FIND = "SELECT " + COLUMNS
            + " FROM cap_skill_def WHERE id = ? AND version = ?";

    /**
     * 插入一条不可变版本行（§6.3 第 1 条 · W-PERM-P5）。
     *
     * <p>⚠️ <b>ON CONFLICT DO NOTHING 而非 DO UPDATE</b>：已发布版本行不可变（§6.1）。
     */
    private static final String SQL_INSERT = """
            INSERT INTO cap_skill_def
                (id, version, name, description, purpose, usage, content, declared_tools, declared_mcp,
                 risk_ceiling, param_policies, required_caps, namespace,
                 config_fingerprint, schema_version, created_at, updated_at, published_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::text, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, 'platform',
                    ?, ?, ?, ?, ?)
            ON CONFLICT (id, version) DO NOTHING
            """;
    // ⚠️ 参数位次（三级描述改造）：
    //    1 id / 2 version / 3 name / 4 description(遗留单级描述列，新入库一律 NULL)
    //    / 5 purpose / 6 usage · jsonb / 7 content · text / 8 declared_tools · jsonb / 9 declared_mcp · jsonb
    //    / 10 risk_ceiling / 11 param_policies · jsonb / 12 required_caps · jsonb
    //    / 13 config_fingerprint / 14 schema_version / 15 created_at / 16 updated_at / 17 published_at

    private static final String SQL_FIND_LATEST_PUBLISHED = "SELECT " + COLUMNS
            + " FROM cap_skill_def WHERE id = ? AND published_at IS NOT NULL"
            + " ORDER BY version DESC LIMIT 1";

    /**
     * 该逻辑 id 的绝对最高版本行（不区分发布状态 · 能力目录版本管理）。
     *
     * <p>⚠️ 与 {@link #SQL_FIND_LATEST_PUBLISHED} 的「已发布口径」不同：本查询不过滤
     * {@code published_at}，因此能读到尚未发布的草稿版本行 —— 管理台据此取编辑基线，
     * 服务端据此算下一个版本号。
     */
    private static final String SQL_FIND_LATEST = "SELECT " + COLUMNS
            + " FROM cap_skill_def WHERE id = ? ORDER BY version DESC LIMIT 1";

    /**
     * 就地更新一条**未发布草稿**版本行（WHERE 带 published_at IS NULL）。
     *
     * <p>⚠️ 参数位次：1 name / 2 description / 3 purpose / 4 usage·jsonb / 5 content·text
     * / 6 declared_tools·jsonb / 7 declared_mcp·jsonb / 8 risk_ceiling
     * / 9 param_policies·jsonb / 10 required_caps·jsonb / 11 config_fingerprint
     * / 12 updated_at / 13 id / 14 version。
     */
    private static final String SQL_UPDATE_DRAFT = """
            UPDATE cap_skill_def
            SET name = ?, description = ?, purpose = ?, usage = ?::jsonb, content = ?::text,
                declared_tools = ?::jsonb, declared_mcp = ?::jsonb, risk_ceiling = ?,
                param_policies = ?::jsonb, required_caps = ?::jsonb,
                config_fingerprint = ?, updated_at = ?
            WHERE id = ? AND version = ? AND published_at IS NULL
            """;

    /**
     * 每个逻辑 id 的最高版本行（只读目录，见 {@link CapSkillDefPort#listAll()}）。
     *
     * <p>⚠️ {@code DISTINCT ON} + 首列一致的排序键（PG 强制要求）。
     */
    private static final String SQL_LIST_ALL = "SELECT DISTINCT ON (id) " + COLUMNS
            + " FROM cap_skill_def ORDER BY id, version DESC";

    /** 该 id 的全部版本行（不可变历史，按版本倒序 · 能力目录版本回溯用）。 */
    private static final String SQL_LIST_VERSIONS = "SELECT " + COLUMNS
            + " FROM cap_skill_def WHERE id = ? ORDER BY version DESC";

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
                            ps.setNull(7, java.sql.Types.VARCHAR);
                        } else {
                            ps.setString(5, def.description().purpose());
                            ps.setString(6, toJson(def.description().usage()));
                            ps.setString(7, def.description().content());
                        }
                        // ⚠️ 位次（三级描述改造）：content 插在第 7 位，
                        //    其后 declared_tools 及以后**全部 +1**。
                        //    ⚠️ 漏掉 +1 会把 content 覆盖掉、并让后续列整体错位
                        //       （schema_version 写进 published_at…）——
                        //       这类错误不会报错，只会静默写坏数据。
                        ps.setString(8, toJson(def.declaredTools()));
                        ps.setString(9, toJsonMcpDeclarations(def.declaredMcp()));
                        ps.setString(10, def.riskCeiling().name());
                        ps.setString(11, def.paramPolicies());
                        ps.setString(12, toJson(def.requiredCaps()));
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

    @Override
    public @NonNull Optional<CapSkillDef> findLatest(@NonNull String skillId) {
        return query(SQL_FIND_LATEST, ps -> ps.setString(1, skillId));
    }

    @Override
    public boolean publishDraft(@NonNull String id, int version,
                                         @NonNull Instant publishedAt) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_PUBLISH_DRAFT)) {
                        ps.setTimestamp(1, Timestamp.from(publishedAt));
                        ps.setString(2, id);
                        ps.setInt(3, version);
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public boolean updateDraft(@NonNull CapSkillDef def) {
        String purpose = null;
        String usageJson = null;
        String content = null;
        if (def.description() != null) {
            purpose = def.description().purpose();
            usageJson = toJson(def.description().usage());
            content = def.description().content();
        }
        final String p1 = purpose;
        final String p2 = usageJson;
        final String p3 = content;
        return TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_DRAFT)) {
                        ps.setString(1, def.name());
                        ps.setString(2, null); // 遗留单级描述列
                        ps.setString(3, p1);
                        ps.setString(4, p2);
                        ps.setString(5, p3);
                        ps.setString(6, toJson(def.declaredTools()));
                        ps.setString(7, toJsonMcpDeclarations(def.declaredMcp()));
                        ps.setString(8, def.riskCeiling().name());
                        ps.setString(9, def.paramPolicies());
                        ps.setString(10, toJson(def.requiredCaps()));
                        ps.setString(11, def.configFingerprint());
                        ps.setTimestamp(12, Timestamp.from(java.time.Instant.now()));
                        ps.setString(13, def.id());
                        ps.setInt(14, def.version());
                        return ps.executeUpdate() > 0;
                    }
                });
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

    @Override
    public @NonNull List<CapSkillDef> listVersions(@NonNull String skillId) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_VERSIONS)) {
                        ps.setString(1, skillId);
                        try (ResultSet rs = ps.executeQuery()) {
                            List<CapSkillDef> out = new java.util.ArrayList<>();
                            while (rs.next()) {
                                out.add(map(rs));
                            }
                            return List.copyOf(out);
                        }
                    }
                });
    }

    private static CapSkillDef map(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new CapSkillDef(
                rs.getString("id"),
                rs.getInt("version"),
                rs.getString("name"),
                readDescription(rs.getString("purpose"), rs.getString("usage"), rs.getString("content")),
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
     * 组装三级描述。
     *
     * <p>⚠️ 存量行只有遗留单级 {@code description}、三级列为 NULL，
     * 此时描述视为缺省（<b>不得</b>把遗留 description 充当 purpose ——
     * 选型信号与执行指引是新的行为内容，混装旧文本会误导模型）。
     */
    private static @Nullable SkillDescription readDescription(
            @Nullable String purpose, @Nullable String usageJson, @Nullable String content) {
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
        String contentText = (content == null || content.isBlank()) ? "" : content;
        return new SkillDescription(purpose, usage, contentText);
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
