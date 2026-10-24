package cn.studykid.multiagent.core.cap;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 能力依赖图谱（管理面 M7 · §3.2 关系与依赖方向）。
 *
 * <p><b>边方向 = 引用方向</b>（A ─▶ B 表示「A 引用 B」）：
 * <pre>
 *   Tenant ─TENANT_OF─▶ Agent ─ENABLED_SKILL─▶ Skill ─DECLARED_TOOL─▶ Tool
 *                                    │                    └─DECLARED_MCP─▶ MCP Server
 *                                    └─OVERRIDE_REMOVED_SKILL─⇢ Skill（负向：显式移除）
 *         Agent ─OVERRIDE_REMOVED_TOOL─⇢ Tool（负向）
 *         Tool(kind=mcp) ─MCP_BINDING─▶ MCP Server
 * </pre>
 *
 * <p>⚠️ <b>图谱口径 ≠ 运行期闭包口径</b>：本图谱取每个对象的<b>最高版本行</b>
 * （含未发布草稿，与能力目录同口径），用于管理面「声明了什么」；
 * 运行期有效权限由 {@code PermissionResolver} 按<b>发布指针</b>解析
 * （fail-closed）。二者用途不同，不可互相替代。
 *
 * <p>⚠️ <b>{@link Node#exists()}{@code = false} 是悬空引用</b>：被引用但
 * 目录 / 租户中找不到定义。图谱保留幽灵节点而不是静默丢弃边 ——
 * 「启用了不存在的 Skill」必须看得见（{@code PermissionResolver} 对它是 fail-closed 拒绝）。
 */
public record DependencyGraph(
        @NonNull String tenantId,
        @NonNull List<Node> nodes,
        @NonNull List<Edge> edges,
        boolean truncated
) {

    /** 节点类型。{@code TENANT} 仅作根节点（所有权，非引用目标）。 */
    public enum NodeType {
        TENANT, AGENT, TOOL, SKILL, MCP_SERVER
    }

    /** 边类型（引用语义）。 */
    public enum EdgeKind {
        /** 租户 ─▶ Agent（1:N 所有权）。 */
        TENANT_OF,
        /** Agent ─▶ Skill（{@code enabled_skills}，V1 唯一启用途径）。 */
        ENABLED_SKILL,
        /** Skill ─▶ Tool（{@code declared_tools}）。 */
        DECLARED_TOOL,
        /** Skill ─▶ MCP Server（{@code declared_mcp}）。 */
        DECLARED_MCP,
        /** Tool({@code kind=mcp}) ─▶ MCP Server（{@code mcp_server_id}）。 */
        MCP_BINDING,
        /** Agent ⇢ Skill（{@code overrides.removeSkills}，负向：显式移除）。 */
        OVERRIDE_REMOVED_SKILL,
        /** Agent ⇢ Tool（{@code overrides.removeTools}，负向：显式移除）。 */
        OVERRIDE_REMOVED_TOOL
    }

    /**
     * 图谱节点。
     *
     * @param id        全局节点 id（{@code "agent:<id>"} / {@code "tool:<id>"} / {@code "skill:<id>"} / {@code "mcp:<id>"} / {@code "tenant:<id>"}）
     * @param type      节点类型
     * @param refId     业务 id（agent_id / tool id / skill id / server id / tenant_id）
     * @param label     展示名（缺省回退 refId）
     * @param detail    副标题（风险等级 / 信任分级 / 版本等）
     * @param exists    是否有定义；{@code false} = 悬空引用的幽灵节点
     * @param published 是否已发布（最高版本行的 {@code published_at}）
     * @param version   版本号（幽灵节点为 0）
     */
    public record Node(
            @NonNull String id,
            @NonNull NodeType type,
            @NonNull String refId,
            @NonNull String label,
            @Nullable String detail,
            boolean exists,
            boolean published,
            int version
    ) {
    }

    /**
     * 图谱边（{@code from} 引用 {@code to}）。
     *
     * <p>⚠️ 负向边（{@code OVERRIDE_REMOVED_*}）同样计入「被引用」：
     * Agent 显式移除某对象，说明其配置里写着这个对象 —— 删除/停用该对象
     * 会留下失效引用，影响分析必须看得到。
     */
    public record Edge(
            @NonNull String from,
            @NonNull String to,
            @NonNull EdgeKind kind
    ) {
    }
}
