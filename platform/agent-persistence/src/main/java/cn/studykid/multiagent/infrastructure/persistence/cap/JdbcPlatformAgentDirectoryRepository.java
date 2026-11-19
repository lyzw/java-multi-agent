package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.persistence.cap.PlatformAgentDirectoryPort;

/**
 * {@link PlatformAgentDirectoryPort} 的 PostgreSQL 实现（第三十九批 · Agent 视角扩展）。
 *
 * <p>⚠️ <b>仅平台控制台注册表用途</b>：数据源是受会话守卫的平台运维连接
 * （{@code platformOps.guardedOrUnavailableDataSource()}），调用必须包在平台会话窗内 ——
 * 与平台通道的发布端口（{@code JdbcConfigPointerRepository} 等）同一纪律。
 *
 * <p>⚠️ <b>只读</b>：本类不提供任何写方法（平台不修改租户私有 Agent 配置）。
 */
public class JdbcPlatformAgentDirectoryRepository implements PlatformAgentDirectoryPort {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public JdbcPlatformAgentDirectoryRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * SQL 说明（Agent 视角扩展版）：
     * <ul>
     *   <li>{@code agent} 行 = 注册表主行（名称/描述/版本计数）；</li>
     *   <li>最新 {@code agent_version} 的 published_at 与最高版本号（窗口函数）；</li>
     *   <li>最新 {@code cap_agent_def}（权威定义）提供角色/启用技能/能力标签/
     *       模型引用/技能包/提示词/编排声明/编排模式 ——
     *       <b>不含 definition 正文</b>；</li>
     *   <li>子查询 {@code tc} 聚合 tenant_agent_binding 得出该 Agent 被多少租户
     *       ACTIVE 绑定（复用度指标）。</li>
     * </ul>
     */
    private static final String SQL = """
            SELECT b.tenant_id::text        AS tenant_id,
                   a.agent_id::text         AS agent_id,
                   a.name                   AS name,
                   a.description            AS description,
                   (SELECT count(*) FROM agent_version v
                     WHERE v.agent_id = a.agent_id) AS version_count,
                   av.version_number        AS latest_version_number,
                   av.published_at          AS latest_published_at,
                   d.version                AS def_version,
                   d.lifecycle_state        AS def_lifecycle_state,
                   d.roles                  AS def_roles,
                   d.enabled_skills         AS def_enabled_skills,
                   d.capability_tags        AS def_capability_tags,
                   d.overrides              AS def_overrides,
                   d.model_ref              AS def_model_ref,
                   d.skill_bundles          AS def_skill_bundles,
                   d.instructions           AS def_instructions,
                   d.orchestration          AS def_orchestration,
                   om.mode                  AS orchestration_mode,
                   COALESCE(tc.tenant_count, 0) AS tenant_count
            FROM tenant_agent_binding b
            JOIN agent a ON a.agent_id = b.agent_id
            LEFT JOIN LATERAL (
                SELECT v.version_number, v.published_at
                FROM agent_version v
                WHERE v.agent_id = a.agent_id
                ORDER BY v.version_number DESC LIMIT 1
            ) av ON TRUE
            LEFT JOIN LATERAL (
                SELECT d.version, d.lifecycle_state, d.roles, d.enabled_skills,
                       d.capability_tags, d.overrides, d.model_ref,
                       d.skill_bundles, d.instructions, d.orchestration
                FROM cap_agent_def d
                WHERE d.agent_id = a.agent_id
                ORDER BY d.version DESC LIMIT 1
            ) d ON TRUE
            LEFT JOIN LATERAL (
                SELECT CASE
                    -- ⚠️ orchestration 是 jsonb：trim() 会被 PG 解析成 btrim(jsonb) 直接报错，
                    --    '{}'::text 比较同样会因 jsonb = text 失效；一律走 jsonb 原生判定。
                    WHEN d2.orchestration IS NULL
                      OR d2.orchestration IN ('{}'::jsonb, 'null'::jsonb, '""'::jsonb)
                      OR jsonb_typeof(d2.orchestration) <> 'object'
                        THEN NULL
                    WHEN d2.orchestration::jsonb ->> 'mode' IS NOT NULL
                        THEN d2.orchestration::jsonb ->> 'mode'
                    ELSE NULL
                END AS mode
                FROM cap_agent_def d2
                WHERE d2.agent_id = a.agent_id
                ORDER BY d2.version DESC LIMIT 1
            ) om ON TRUE
            LEFT JOIN (
                SELECT agent_id, count(*) AS tenant_count
                FROM tenant_agent_binding
                WHERE status = 'ACTIVE'
                GROUP BY agent_id
            ) tc ON tc.agent_id = a.agent_id
            WHERE b.status = 'ACTIVE'
              AND (?::uuid IS NULL OR b.tenant_id = ?::uuid)
            ORDER BY b.tenant_id, a.name, a.agent_id
            """;

    @Override
    public List<PlatformAgentRow> listAgents(@Nullable String tenantIdFilter) {
        List<PlatformAgentRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL)) {
            ps.setString(1, tenantIdFilter);
            ps.setString(2, tenantIdFilter);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(map(rs));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("查询跨租户 Agent 注册表失败", e);
        }
    }

    private PlatformAgentRow map(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("latest_published_at");
        return new PlatformAgentRow(
                rs.getString("tenant_id"),
                rs.getString("agent_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("version_count"),
                (Integer) rs.getObject("latest_version_number"),
                publishedAt == null ? null : publishedAt.toInstant(),
                (Integer) rs.getObject("def_version"),
                rs.getString("def_lifecycle_state"),
                listOf(rs.getString("def_roles")),
                listOf(rs.getString("def_enabled_skills")),
                listOf(rs.getString("def_capability_tags")),
                hasOverrides(rs.getString("def_overrides")),
                rs.getString("def_model_ref"),
                listOf(rs.getString("def_skill_bundles")),
                hasNonEmptyJson(rs.getString("def_instructions")),
                hasNonEmptyJson(rs.getString("def_orchestration")),
                rs.getString("orchestration_mode"),
                rs.getInt("tenant_count"));
    }

    /** JSON 数组文本 → 列表；异常显式上抛（解析失败不静默清空）。 */
    private static List<String> listOf(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> items = JSON.readValue(json, new TypeReference<List<String>>() { });
            return items == null ? List.of() : items;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Agent 注册表 JSON 字段解析失败: " + json, e);
        }
    }

    /**
     * overrides 非空对象 = 有收紧覆盖。⚠️ overrides 是未被解释的字段碎片
     * （不是 definition 正文），仅回布尔，不回显内容。
     */
    private static boolean hasOverrides(@Nullable String overridesJson) {
        if (overridesJson == null || overridesJson.isBlank()) {
            return false;
        }
        try {
            return !overridesJson.trim().equals("{}");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 判断 JSON 文本是否为「非空对象/数组」—— 用于 hasInstructions / hasOrchestration。
     * 空对象 {}、空数组 []、null、空白字符串 → false；有内容 → true。
     */
    private static boolean hasNonEmptyJson(@Nullable String json) {
        if (json == null || json.isBlank()) return false;
        String trimmed = json.trim();
        if (trimmed.equals("{}") || trimmed.equals("[]")) return false;
        try {
            Object parsed = JSON.readValue(trimmed, Object.class);
            if (parsed == null) return false;
            if (parsed instanceof java.util.Map<?, ?> m && m.isEmpty()) return false;
            if (parsed instanceof java.util.List<?> l && l.isEmpty()) return false;
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
