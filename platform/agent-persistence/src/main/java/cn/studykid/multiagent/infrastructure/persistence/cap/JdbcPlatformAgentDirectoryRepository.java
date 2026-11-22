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
import cn.studykid.multiagent.ports.persistence.cap.PlatformAgentDirectoryPort.PlatformAgentChange;

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
                   COALESCE(tc.tenant_count, 0) AS tenant_count,
                   pc.pending_state         AS pending_state,
                   pc.pending_at            AS pending_at
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
            -- 「已修改未发布」= 存在 PENDING / APPROVED 且尚未执行的申请单。
            -- 申请单 payload 存 JSON 原文：PUBLISH 看 agents[].agentId，
            -- LIFECYCLE_TRANSITION 看 lifecycle.objectId；resource_kind/id 兼容草稿分流写入。
            LEFT JOIN LATERAL (
                SELECT pr.state AS pending_state, pr.requested_at AS pending_at
                FROM cap_config_publish_request pr
                WHERE pr.state IN ('PENDING', 'APPROVED')
                  AND (
                        EXISTS (
                            SELECT 1
                            FROM jsonb_array_elements(
                                CASE WHEN jsonb_typeof(pr.payload::jsonb -> 'agents') = 'array'
                                     THEN pr.payload::jsonb -> 'agents'
                                     ELSE '[]'::jsonb END) ag
                            WHERE ag ->> 'agentId' = a.agent_id::text
                        )
                     OR pr.payload::jsonb -> 'lifecycle' ->> 'objectId' = a.agent_id::text
                     OR (pr.resource_kind = 'AGENT' AND pr.resource_id = a.agent_id::text)
                  )
                ORDER BY pr.requested_at DESC
                LIMIT 1
            ) pc ON TRUE
            WHERE b.status = 'ACTIVE'
              AND (?::uuid IS NULL OR b.tenant_id = ?::uuid)
            ORDER BY b.tenant_id, a.name, a.agent_id
            """;

    /**
     * 变更历史 SQL：只从申请单读管理元数据（动作 / 状态 / 理由 / 申请人 / 时间 / 目标版本），
     * 定义正文永远不出平台边界。申请单 payload 为 JSON 原文，用 jsonb 精确匹配 agentId，
     * 避免把「同批次里其它 Agent 的申请」误算到本 Agent 头上。
     */
    private static final String CHANGES_SQL = """
            SELECT pr.request_id::text       AS change_id,
                   'CONFIG'                  AS category,
                   pr.tenant_id::text        AS tenant_id,
                   pr.operation              AS action,
                   pr.state                  AS state,
                   pr.target_version         AS target_version,
                   pr.reason                 AS reason,
                   pr.requester_id           AS actor_id,
                   pr.requested_at           AS requested_at,
                   pr.applied_at             AS applied_at,
                   -- after_config：本次申请里该 Agent 的完整配置（变更后）
                   (
                       SELECT ag
                       FROM jsonb_array_elements(
                                CASE WHEN jsonb_typeof(pr.payload::jsonb -> 'agents') = 'array'
                                     THEN pr.payload::jsonb -> 'agents'
                                     ELSE '[]'::jsonb END) ag
                       WHERE ag ->> 'agentId' = ?
                       LIMIT 1
                   )::text                   AS after_config,
                   -- before_config：target_version 之前的最大版本配置（变更前）
                   --   ⚠️ 用 `version < target_version` 取最大，而非 `= target_version - 1`：
                   --      清理草稿后版本号不连续，-1 会命中不存在的行。
                   CASE WHEN pr.target_version IS NULL THEN NULL
                        ELSE (
                            SELECT to_jsonb(d)
                            FROM cap_agent_def d
                            WHERE d.agent_id = ?::uuid
                              AND d.version < pr.target_version
                            ORDER BY d.version DESC
                            LIMIT 1
                        )::text
                   END                       AS before_config
            FROM cap_config_publish_request pr
            WHERE EXISTS (
                      SELECT 1
                      FROM jsonb_array_elements(
                          CASE WHEN jsonb_typeof(pr.payload::jsonb -> 'agents') = 'array'
                               THEN pr.payload::jsonb -> 'agents'
                               ELSE '[]'::jsonb END) ag
                      WHERE ag ->> 'agentId' = ?
                  )
               OR pr.payload::jsonb -> 'lifecycle' ->> 'objectId' = ?
               OR (pr.resource_kind = 'AGENT' AND pr.resource_id = ?)
            ORDER BY pr.requested_at DESC
            LIMIT ?
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

    @Override
    public @NonNull List<PlatformAgentChange> listAgentChanges(@NonNull String agentId, int limit) {
        List<PlatformAgentChange> changes = new ArrayList<>();
        int size = Math.max(1, Math.min(limit, 200));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(CHANGES_SQL)) {
            ps.setString(1, agentId);   // after_config 子查询（payload 匹配）
            ps.setString(2, agentId);   // before_config 子查询（cap_agent_def 匹配）
            ps.setString(3, agentId);   // WHERE EXISTS（payload 匹配）
            ps.setString(4, agentId);   // WHERE lifecycle objectId
            ps.setString(5, agentId);   // WHERE resource_id
            ps.setInt(6, size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp requestedAt = rs.getTimestamp("requested_at");
                    Timestamp appliedAt = rs.getTimestamp("applied_at");
                    changes.add(new PlatformAgentChange(
                            rs.getString("change_id"),
                            rs.getString("category"),
                            rs.getString("tenant_id"),
                            rs.getString("action"),
                            rs.getString("state"),
                            (Integer) rs.getObject("target_version"),
                            rs.getString("reason"),
                            rs.getString("actor_id"),
                            requestedAt == null ? Instant.EPOCH : requestedAt.toInstant(),
                            appliedAt == null ? null : appliedAt.toInstant(),
                            rs.getString("before_config"),
                            rs.getString("after_config")));
                }
            }
            return changes;
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Agent 变更历史失败", e);
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
                rs.getInt("tenant_count"),
                rs.getString("pending_state"),
                rs.getTimestamp("pending_at") == null
                        ? null : rs.getTimestamp("pending_at").toInstant());
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
