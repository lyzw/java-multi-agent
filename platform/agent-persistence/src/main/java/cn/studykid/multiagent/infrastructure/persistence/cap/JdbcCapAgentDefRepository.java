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
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
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

    /** 影子比对/染色的资源类型（P0-1b 第 1 批）。 */
    private static final String RESOURCE_TYPE = "cap_agent_def";

    private static final String GLOBAL_CONTEXT_TENANT = "00000000-0000-0000-0000-000000000000";

    /** SQL_PUBLISH_DRAFT：把未发布草稿行转正（WHERE 限定 published_at IS NULL）。 */
    private static final String SQL_PUBLISH_DRAFT =
            "UPDATE cap_agent_def SET published_at = ? " +
            "WHERE agent_id = ?::uuid AND version = ? AND published_at IS NULL";

    private static final String SQL_INSERT = """
            INSERT INTO cap_agent_def
                (agent_id, version, name, description, roles, enabled_skills,
                 skill_bundles, capability_tags, overrides, orchestration, instructions,
                 model_ref, generation_params, config_fingerprint,
                 schema_version, created_at, updated_at, published_at)
            VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                    ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?)
            ON CONFLICT (agent_id, version) DO NOTHING
            """;

    /**
     * 就地更新一条**未发布草稿**版本行（全局口径，不按租户绑定过滤）。
     *
     * <p>⚠️ WHERE 带 {@code published_at IS NULL}：已发布行不可变（DB 触发器拒改）。
     * <p>⚠️ 不更新 version / created_at。
     * <p>⚠️ 参数位次：1 name / 2 description / 3 roles·jsonb / 4 enabled_skills·jsonb
     * / 5 skill_bundles·jsonb / 6 capability_tags·jsonb / 7 overrides·jsonb
     * / 8 orchestration·jsonb / 9 instructions·jsonb / 10 model_ref
     * / 11 generation_params·jsonb / 12 config_fingerprint / 13 updated_at
     * / 14 agent_id / 15 version。
     */
    private static final String SQL_UPDATE_DRAFT = """
            UPDATE cap_agent_def
            SET name = ?, description = ?, roles = ?::jsonb, enabled_skills = ?::jsonb,
                skill_bundles = ?::jsonb, capability_tags = ?::jsonb, overrides = ?::jsonb,
                orchestration = ?::jsonb, instructions = ?::jsonb, model_ref = ?,
                generation_params = ?::jsonb, config_fingerprint = ?, updated_at = ?
            WHERE agent_id = ?::uuid AND version = ? AND published_at IS NULL
            """;

    private static final String SQL_FIND = """
            SELECT d.agent_id::text, d.version, d.name, d.description,
                   d.roles::text, d.enabled_skills::text, d.skill_bundles::text,
                   d.capability_tags::text, d.overrides::text, d.orchestration::text,
                   d.instructions::text, d.model_ref, d.generation_params::text,
                   d.config_fingerprint,
                   d.schema_version, d.created_at, d.published_at
            FROM cap_agent_def d
            WHERE d.agent_id = ?::uuid AND d.version = ?
              AND EXISTS (SELECT 1 FROM tenant_agent_binding b
                           WHERE b.tenant_id = ?::uuid
                             AND b.agent_id = d.agent_id
                             AND b.status = 'ACTIVE')
            """;

    private static final String SQL_FIND_LATEST = """
            SELECT d.agent_id::text, d.version, d.name, d.description,
                   d.roles::text, d.enabled_skills::text, d.skill_bundles::text,
                   d.capability_tags::text, d.overrides::text, d.orchestration::text,
                   d.instructions::text, d.model_ref, d.generation_params::text,
                   d.config_fingerprint,
                   d.schema_version, d.created_at, d.published_at
            FROM cap_agent_def d
            WHERE d.agent_id = ?::uuid
              AND EXISTS (SELECT 1 FROM tenant_agent_binding b
                           WHERE b.tenant_id = ?::uuid
                             AND b.agent_id = d.agent_id
                             AND b.status = 'ACTIVE')
            ORDER BY d.version DESC
            LIMIT 1
            """;

    private static final String SQL_FIND_GLOBAL = """
            SELECT d.agent_id::text, d.version, d.name, d.description,
                   d.roles::text, d.enabled_skills::text, d.skill_bundles::text,
                   d.capability_tags::text, d.overrides::text, d.orchestration::text,
                   d.instructions::text, d.model_ref, d.generation_params::text,
                   d.config_fingerprint,
                   d.schema_version, d.created_at, d.published_at
            FROM cap_agent_def d
            WHERE d.agent_id = ?::uuid AND d.version = ?
            """;

    private static final String SQL_FIND_LATEST_GLOBAL = """
            SELECT d.agent_id::text, d.version, d.name, d.description,
                   d.roles::text, d.enabled_skills::text, d.skill_bundles::text,
                   d.capability_tags::text, d.overrides::text, d.orchestration::text,
                   d.instructions::text, d.model_ref, d.generation_params::text,
                   d.config_fingerprint,
                   d.schema_version, d.created_at, d.published_at
            FROM cap_agent_def d
            WHERE d.agent_id = ?::uuid
            ORDER BY d.version DESC
            LIMIT 1
            """;

    private static final String SQL_FIND_LATEST_PUBLISHED = """
            SELECT d.agent_id::text, d.version, d.name, d.description,
                   d.roles::text, d.enabled_skills::text, d.skill_bundles::text,
                   d.capability_tags::text, d.overrides::text, d.orchestration::text,
                   d.instructions::text, d.model_ref, d.generation_params::text,
                   d.config_fingerprint,
                   d.schema_version, d.created_at, d.published_at
            FROM cap_agent_def d
            WHERE d.agent_id = ?::uuid
              AND d.published_at IS NOT NULL
              AND EXISTS (SELECT 1 FROM tenant_agent_binding b
                           WHERE b.tenant_id = ?::uuid
                             AND b.agent_id = d.agent_id
                             AND b.status = 'ACTIVE')
            ORDER BY d.version DESC
            LIMIT 1
            """;

    /**
     * 每个已绑定 Agent 一行的最高版本。定义表是全局的，租户可见性完全由绑定表决定。
     */
    private static final String SQL_LIST_LATEST_PER_AGENT = """
            SELECT d.agent_id::text, d.version, d.name, d.description,
                   d.roles::text, d.enabled_skills::text, d.skill_bundles::text,
                   d.capability_tags::text, d.overrides::text, d.orchestration::text,
                   d.instructions::text, d.model_ref, d.generation_params::text,
                   d.config_fingerprint,
                   d.schema_version, d.created_at, d.published_at
            FROM cap_agent_def d
            JOIN tenant_agent_binding b
              ON b.agent_id = d.agent_id
             AND b.tenant_id = ?::uuid
             AND b.status = 'ACTIVE'
            WHERE d.version = (SELECT max(d2.version) FROM cap_agent_def d2
                                WHERE d2.agent_id = d.agent_id)
            ORDER BY d.agent_id ASC
            """;

    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2；阶段一 P0-1b 第 1 批）────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    // ⚠️ 非 final：由 Spring 启动期通过 {@link #bindAuthzShadow} 一次性绑定，之后只读。
    //    容器里没有 AuthzShadowBinding 时保持构造期默认值（null / false）⇒ 零行为变更。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    public JdbcCapAgentDefRepository(@NonNull DataSource dataSource) {
        this(dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     *
     * @param shadowEnabled    是否启用影子比对 + 染色（P1 默认 false；P2 翻转为 true 进入阻断模式）
     * @param sentinelTenantId 哨兵（共享）租户标识，与 {@code SqlScopeTranslator} 共用配置项
     */
    JdbcCapAgentDefRepository(@NonNull DataSource dataSource,
                              @Nullable ScopedDataAccess scoped,
                              @Nullable AuthzShadowReporter reporter,
                              boolean shadowEnabled,
                              @NonNull String sentinelTenantId) {
        this.dataSource = dataSource;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
    }

    @Override
    public boolean insert(@NonNull CapAgentDef def) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(GLOBAL_CONTEXT_TENANT),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
                        ps.setString(1, def.agentId());
                        ps.setInt(2, def.version());
                        ps.setString(3, def.name());
                        ps.setString(4, def.description());
                        ps.setString(5, toJson(def.roles()));
                        ps.setString(6, toJson(def.enabledSkills()));
                        ps.setString(7, toJson(def.skillBundles()));
                        ps.setString(8, toJson(def.capabilityTags()));
                        ps.setString(9, def.overrides());
                        ps.setString(10, def.orchestration());
                        ps.setString(11, def.instructions());
                        ps.setString(12, def.modelRef());
                        ps.setString(13, def.generationParams());
                        ps.setString(14, def.configFingerprint());
                        ps.setInt(15, def.schemaVersion());
                        ps.setTimestamp(16, Timestamp.from(def.createdAt()));
                        ps.setTimestamp(17, Timestamp.from(def.createdAt()));
                        if (def.publishedAt() == null) {
                            ps.setNull(18, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                        } else {
                            ps.setTimestamp(18, Timestamp.from(def.publishedAt()));
                        }
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public boolean publishDraftGlobal(@NonNull String agentId, int version,
                                         @NonNull Instant publishedAt) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(GLOBAL_CONTEXT_TENANT),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_PUBLISH_DRAFT)) {
                        ps.setTimestamp(1, Timestamp.from(publishedAt));
                        ps.setString(2, agentId);
                        ps.setInt(3, version);
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public boolean updateDraftGlobal(@NonNull CapAgentDef def) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(GLOBAL_CONTEXT_TENANT),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_DRAFT)) {
                        ps.setString(1, def.name());
                        ps.setString(2, def.description());
                        ps.setString(3, toJson(def.roles()));
                        ps.setString(4, toJson(def.enabledSkills()));
                        ps.setString(5, toJson(def.skillBundles()));
                        ps.setString(6, toJson(def.capabilityTags()));
                        ps.setString(7, def.overrides());
                        ps.setString(8, def.orchestration());
                        ps.setString(9, def.instructions());
                        ps.setString(10, def.modelRef());
                        ps.setString(11, def.generationParams());
                        ps.setString(12, def.configFingerprint());
                        ps.setTimestamp(13, Timestamp.from(java.time.Instant.now()));
                        ps.setString(14, def.agentId());
                        ps.setInt(15, def.version());
                        return ps.executeUpdate() > 0;
                    }
                });
    }

    @Override
    public @NonNull Optional<CapAgentDef> find(@Nullable String tenantId,
                                                @NonNull String agentId,
                                                int version) {
        if (tenantId == null) {
            // PLATFORM 作用域冲突回查：按 agentId+version 直接查，不走绑定过滤。
            return TransactionalConnectionHolder.inTransaction(
                    dataSource, tenantContext(GLOBAL_CONTEXT_TENANT),
                    conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_GLOBAL)) {
                            ps.setString(1, agentId);
                            ps.setInt(2, version);
                            try (ResultSet rs = ps.executeQuery()) {
                                return rs.next()
                                        ? Optional.of(map(rs))
                                        : Optional.<CapAgentDef>empty();
                            }
                        }
                    });
        }
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND)) {
                        ps.setString(1, agentId);
                        ps.setInt(2, version);
                        ps.setString(3, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            Optional<CapAgentDef> row =
                                    rs.next() ? Optional.of(map(rs)) : Optional.<CapAgentDef>empty();
                            row.ifPresent(d -> shadowTaint(RESOURCE_TYPE, tenantId, tenantId));
                            return row;
                        }
                    }
                });
    }

    @Override
    public @NonNull Optional<CapAgentDef> findLatestGlobal(@NonNull String agentId) {
        // 无绑定过滤：全局 Agent 可以尚未分配任何租户。用哨兵上下文绑定 RLS。
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(GLOBAL_CONTEXT_TENANT),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_LATEST_GLOBAL)) {
                        ps.setString(1, agentId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next()
                                    ? Optional.of(map(rs))
                                    : Optional.<CapAgentDef>empty();
                        }
                    }
                });
    }

    /** 该 Agent 的全部版本行（无绑定过滤，按版本倒序 · 能力目录版本回溯用）。 */
    private static final String SQL_LIST_VERSIONS_GLOBAL = """
            SELECT d.agent_id::text, d.version, d.name, d.description,
                   d.roles::text, d.enabled_skills::text, d.skill_bundles::text,
                   d.capability_tags::text, d.overrides::text, d.orchestration::text,
                   d.instructions::text, d.model_ref, d.generation_params::text,
                   d.config_fingerprint,
                   d.schema_version, d.created_at, d.published_at
            FROM cap_agent_def d
            WHERE d.agent_id = ?::uuid
            ORDER BY d.version DESC
            """;

    @Override
    public @NonNull List<CapAgentDef> listVersionsGlobal(@NonNull String agentId) {
        // 无绑定过滤：全局 Agent 可以尚未分配任何租户。用哨兵上下文绑定 RLS。
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(GLOBAL_CONTEXT_TENANT),
                conn -> {
                    List<CapAgentDef> rows = new ArrayList<>();
                    try (PreparedStatement ps =
                                 conn.prepareStatement(SQL_LIST_VERSIONS_GLOBAL)) {
                        ps.setString(1, agentId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(map(rs));
                            }
                        }
                    }
                    return rows;
                });
    }

    @Override
    public @NonNull Optional<CapAgentDef> findLatest(@NonNull String tenantId,
                                                      @NonNull String agentId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_LATEST)) {
                        ps.setString(1, agentId);
                        ps.setString(2, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            Optional<CapAgentDef> row =
                                    rs.next() ? Optional.of(map(rs)) : Optional.<CapAgentDef>empty();
                            row.ifPresent(d -> shadowTaint(RESOURCE_TYPE, tenantId, tenantId));
                            return row;
                        }
                    }
                });
    }

    @Override
    public @NonNull Optional<CapAgentDef> findLatestPublished(@NonNull String tenantId,
                                                               @NonNull String agentId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_LATEST_PUBLISHED)) {
                        ps.setString(1, agentId);
                        ps.setString(2, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            Optional<CapAgentDef> row =
                                    rs.next() ? Optional.of(map(rs)) : Optional.<CapAgentDef>empty();
                            row.ifPresent(d -> shadowTaint(RESOURCE_TYPE, tenantId, tenantId));
                            return row;
                        }
                    }
                });
    }

    @Override
    public @NonNull List<CapAgentDef> listLatestPerAgent(@NonNull String tenantId) {
        shadowCompare(RESOURCE_TYPE, tenantId, true);
        return TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> {
                    List<CapAgentDef> rows = new ArrayList<>();
                    try (PreparedStatement ps =
                                 conn.prepareStatement(SQL_LIST_LATEST_PER_AGENT)) {
                        ps.setString(1, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                CapAgentDef d = map(rs);
                                rows.add(d);
                                shadowTaint(RESOURCE_TYPE, tenantId, tenantId);
                            }
                        }
                    }
                    return rows;
                });
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>⚠️ 用 setter 而不是可选构造参数：本仓既有构造签名被单测与 {@code @Bean} 装配依赖，
     * 改成可选构造参数会把「影子态」渗进每一个调用点。setter 只在启动期被调用一次。
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

    void shadowCompare(@NonNull String resourceType, @NonNull String tenantId, boolean hasPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(tenantId), tenantId, hasPredicate);
    }

    void shadowTaint(@NonNull String resourceType,
                     @NonNull String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        boolean isShared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                SubjectContext.forTenant(subjectTenantId), rowTenantId, isShared);
    }

    private static CapAgentDef map(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new CapAgentDef(
                rs.getString("agent_id"),
                rs.getInt("version"),
                rs.getString("name"),
                rs.getString("description"),
                fromJson(rs.getString("roles")),
                fromJson(rs.getString("enabled_skills")),
                fromJson(rs.getString("skill_bundles")),
                fromJson(rs.getString("capability_tags")),
                rs.getString("overrides"),
                rs.getString("orchestration"),
                rs.getString("instructions"),
                rs.getString("model_ref"),
                rs.getString("generation_params"),
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
