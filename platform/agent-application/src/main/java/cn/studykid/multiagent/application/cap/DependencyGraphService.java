package cn.studykid.multiagent.application.cap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapSkillDef.McpDeclaration;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.DependencyEntityType;
import cn.studykid.multiagent.core.cap.DependencyGraph;
import cn.studykid.multiagent.core.cap.DependencyGraph.Edge;
import cn.studykid.multiagent.core.cap.DependencyGraph.EdgeKind;
import cn.studykid.multiagent.core.cap.DependencyGraph.Node;
import cn.studykid.multiagent.core.cap.DependencyGraph.NodeType;
import cn.studykid.multiagent.core.cap.ImpactReport;
import cn.studykid.multiagent.core.cap.ImpactReport.Chain;
import cn.studykid.multiagent.core.cap.ImpactReport.Chain.Step;
import cn.studykid.multiagent.core.cap.ImpactReport.DanglingRef;
import cn.studykid.multiagent.core.cap.ImpactReport.Ref;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;

/**
 * 能力依赖图谱与影响分析（管理面 M7 · §3.2 / §5.3）。
 *
 * <p><b>只读、无副作用</b>：不写库、不发消息、不改状态，可被反复调用。
 * 与 {@link PermissionResolver} 的分工：后者按<b>发布指针</b>算运行期闭包
 * （fail-closed，授权依据）；本服务按<b>最高版本行</b>算管理面图谱
 * （含草稿，与能力目录同口径），回答「声明了什么、改它会命中谁」。
 *
 * <p>⚠️ <b>overrides 解析失败即整体失败</b>（{@link AgentOverrides#parse}
 * 抛 {@link ClosureResolutionException}）：图谱宁可报错，也不展示一套
 * 「少了几条移除边」的错误依赖关系 —— 运维会依据它做删除决策。
 *
 * <p>⚠️ <b>RLS</b>：Agent 侧一律经 {@link CapAgentDefPort}（其实现绑定
 * {@code tenantConnection}）；平台侧 Tool/Skill/MCP 表无 RLS（全局目录）。
 * 跨租户可见性由端口层保证，本服务不再自建过滤。
 */
public class DependencyGraphService {

    /** 节点数上限：防御超大租户/目录把响应体打爆（按需展开留 V2）。 */
    private static final int MAX_NODES = 500;

    /** 边数上限。 */
    private static final int MAX_EDGES = 2_000;

    /** 影响链条数上限（防爆）。 */
    private static final int MAX_CHAINS = 50;

    /** 单链深度上限（V1 无环，纯防御）。 */
    private static final int MAX_CHAIN_DEPTH = 8;

    private final CapAgentDefPort agentDefs;
    private final CapToolDefPort toolDefs;
    private final CapSkillDefPort skillDefs;
    private final CapMcpServerDefPort serverDefs;

    public DependencyGraphService(@NonNull CapAgentDefPort agentDefs,
                                  @NonNull CapToolDefPort toolDefs,
                                  @NonNull CapSkillDefPort skillDefs,
                                  @NonNull CapMcpServerDefPort serverDefs) {
        this.agentDefs = agentDefs;
        this.toolDefs = toolDefs;
        this.skillDefs = skillDefs;
        this.serverDefs = serverDefs;
    }

    // ── 图谱 ──────────────────────────────────────────────────────

    /**
     * 构建租户的能力依赖图谱。
     *
     * <p>节点 = Tenant + 本租户 Agent + 全平台 Tool/Skill/MCP 目录；
     * 被引用但无定义的 id 以<b>幽灵节点</b>（{@code exists=false}）保留，
     * 不静默丢边。
     */
    public @NonNull DependencyGraph graph(@NonNull String tenantId) {
        return build(tenantId).toGraph(tenantId);
    }

    // ── 影响分析 ──────────────────────────────────────────────────

    /**
     * 影响分析：目标实体「引用了谁」+「改/删它会命中谁」。
     *
     * @param type     实体类型
     * @param entityId 实体业务 id
     * @return 报告；实体不存在（含仅被悬空引用）时 {@code entityExists=false}
     *         （控制器转 404，不泄露存在性细节之外的任何信息）
     */
    public @NonNull ImpactReport impact(@NonNull String tenantId,
                                        @NonNull DependencyEntityType type,
                                        @NonNull String entityId) {
        Mutable g = build(tenantId);
        String targetId = nodeId(nodeTypeOf(type), entityId);
        Node target = g.nodes.get(targetId);
        if (target == null || !target.exists()) {
            return ImpactReport.notFound(type.code(), entityId);
        }

        List<Ref> dependencies = g.outgoing(targetId).stream()
                .map(e -> toRef(g, e.to(), e.kind()))
                .toList();
        // ⚠️ TENANT_OF 是所有权边而非引用边：被租户拥有不等于被租户引用，
        //    计入 dependents 会让「每个 Agent 的影响者都是租户」这种噪声淹没真实信号。
        List<Ref> dependents = g.incoming(targetId).stream()
                .filter(e -> e.kind() != EdgeKind.TENANT_OF)
                .map(e -> toRef(g, e.from(), e.kind()))
                .toList();
        List<DanglingRef> dangling = g.outgoing(targetId).stream()
                .filter(e -> !g.nodes.get(e.to()).exists())
                .map(e -> {
                    Node n = g.nodes.get(e.to());
                    return new DanglingRef(n.type().name(), n.refId(), e.kind());
                })
                .toList();

        List<Chain> chains = new ArrayList<>();
        Set<String> affected = new LinkedHashSet<>();
        if (target.type() != NodeType.AGENT) {
            // ⚠️ Agent 是链末端（V1 无对象引用 Agent）：对 Agent 自身跑回溯
            //    只会产出「自己影响自己」的空转链，故直接跳过。
            Deque<Step> path = new ArrayDeque<>();
            Set<String> onPath = new HashSet<>();
            walkUp(g, targetId, path, onPath, chains, affected);
        }

        return new ImpactReport(type.code(), entityId, target.label(), true,
                dependencies, dependents, chains, List.copyOf(affected), dangling);
    }

    /**
     * 沿入向回溯到 Agent（影响链）。
     *
     * <p>每到达一个 Agent 节点即闭合一条链；深度 / 条数封顶，环防御用
     * {@code onPath}（V1 结构上无环：引用方向 Agent→Skill→{Tool,MCP}→MCP，
     * 回溯方向严格反向）。
     */
    private void walkUp(@NonNull Mutable g, @NonNull String nodeId,
                        @NonNull Deque<Step> path, @NonNull Set<String> onPath,
                        @NonNull List<Chain> chains, @NonNull Set<String> affected) {
        if (chains.size() >= MAX_CHAINS || path.size() >= MAX_CHAIN_DEPTH) {
            return;
        }
        if (!onPath.add(nodeId)) {
            return;
        }
        try {
            Node node = g.nodes.get(nodeId);
            path.addLast(new Step(node.type().name(), node.refId(), node.label()));
            if (node.type() == NodeType.AGENT) {
                chains.add(new Chain(List.copyOf(path), node.refId(), node.label()));
                affected.add(node.refId());
                return;
            }
            for (Edge e : g.incoming(nodeId)) {
                if (e.kind() == EdgeKind.TENANT_OF) {
                    continue;
                }
                walkUp(g, e.from(), path, onPath, chains, affected);
                if (chains.size() >= MAX_CHAINS) {
                    return;
                }
            }
        } finally {
            onPath.remove(nodeId);
            path.removeLast();
        }
    }

    // ── 构建 ──────────────────────────────────────────────────────

    private @NonNull Mutable build(@NonNull String tenantId) {
        Mutable g = new Mutable();
        String tenantNode = nodeId(NodeType.TENANT, tenantId);
        g.node(tenantNode, NodeType.TENANT, tenantId, tenantId, "租户", true, true, 0);

        List<CapToolDef> tools = toolDefs.listAll();
        List<CapSkillDef> skills = skillDefs.listAll();
        List<CapMcpServerDef> servers = serverDefs.listAll();
        List<CapAgentDef> agents = agentDefs.listLatestPerAgent(tenantId);

        for (CapToolDef t : tools) {
            g.node(nodeId(NodeType.TOOL, t.id()), NodeType.TOOL, t.id(),
                    label(t.name(), t.id()),
                    t.riskLevel().name() + " · " + t.kind().name(),
                    true, t.isPublished(), t.version());
        }
        for (CapSkillDef s : skills) {
            g.node(nodeId(NodeType.SKILL, s.id()), NodeType.SKILL, s.id(),
                    label(s.name(), s.id()),
                    "风险上限 " + s.riskCeiling().name(),
                    true, s.isPublished(), s.version());
        }
        for (CapMcpServerDef m : servers) {
            // ⚠️ CapMcpServerDef 无 name 字段：label 回退 server id
            g.node(nodeId(NodeType.MCP_SERVER, m.id()), NodeType.MCP_SERVER, m.id(),
                    m.id(),
                    m.trustTier().name() + " · " + m.transport().name(),
                    true, m.isPublished(), m.version());
        }

        for (CapAgentDef a : agents) {
            String agentNode = nodeId(NodeType.AGENT, a.agentId());
            g.node(agentNode, NodeType.AGENT, a.agentId(),
                    label(a.name(), a.agentId()),
                    "v" + a.version() + (a.isPublished() ? "" : " · 草稿"),
                    true, a.isPublished(), a.version());
            g.edge(tenantNode, agentNode, EdgeKind.TENANT_OF);

            AgentOverrides overrides = AgentOverrides.parse(a.overrides());
            for (String skillId : distinct(a.enabledSkills())) {
                g.ensureNode(nodeId(NodeType.SKILL, skillId), NodeType.SKILL, skillId);
                g.edge(agentNode, nodeId(NodeType.SKILL, skillId), EdgeKind.ENABLED_SKILL);
            }
            for (String skillId : overrides.removeSkills()) {
                g.ensureNode(nodeId(NodeType.SKILL, skillId), NodeType.SKILL, skillId);
                g.edge(agentNode, nodeId(NodeType.SKILL, skillId),
                        EdgeKind.OVERRIDE_REMOVED_SKILL);
            }
            for (String toolId : overrides.removeTools()) {
                g.ensureNode(nodeId(NodeType.TOOL, toolId), NodeType.TOOL, toolId);
                g.edge(agentNode, nodeId(NodeType.TOOL, toolId),
                        EdgeKind.OVERRIDE_REMOVED_TOOL);
            }
        }

        for (CapSkillDef s : skills) {
            String skillNode = nodeId(NodeType.SKILL, s.id());
            for (String toolId : distinct(s.declaredTools())) {
                g.ensureNode(nodeId(NodeType.TOOL, toolId), NodeType.TOOL, toolId);
                g.edge(skillNode, nodeId(NodeType.TOOL, toolId), EdgeKind.DECLARED_TOOL);
            }
            for (String serverId : s.declaredMcp().stream()
                    .map(McpDeclaration::serverId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList()) {
                g.ensureNode(nodeId(NodeType.MCP_SERVER, serverId), NodeType.MCP_SERVER, serverId);
                g.edge(skillNode, nodeId(NodeType.MCP_SERVER, serverId), EdgeKind.DECLARED_MCP);
            }
        }

        for (CapToolDef t : tools) {
            // kind=mcp 的 Tool 是 MCP 操作的包装定义：它绑定到具体 Server
            if (t.isMcp() && t.mcpServerId() != null && !t.mcpServerId().isBlank()) {
                String mcpNode = nodeId(NodeType.MCP_SERVER, t.mcpServerId());
                g.ensureNode(mcpNode, NodeType.MCP_SERVER, t.mcpServerId());
                g.edge(nodeId(NodeType.TOOL, t.id()), mcpNode, EdgeKind.MCP_BINDING);
            }
        }

        g.truncateIfNeeded();
        return g;
    }

    private static @NonNull Ref toRef(@NonNull Mutable g, @NonNull String nodeId,
                                      @NonNull EdgeKind kind) {
        Node n = g.nodes.get(nodeId);
        return new Ref(n.type().name(), n.refId(), n.label(), kind);
    }

    private static @NonNull NodeType nodeTypeOf(@NonNull DependencyEntityType type) {
        return switch (type) {
            case AGENT -> NodeType.AGENT;
            case TOOL -> NodeType.TOOL;
            case SKILL -> NodeType.SKILL;
            case MCP_SERVER -> NodeType.MCP_SERVER;
        };
    }

    private static @NonNull String nodeId(@NonNull NodeType type, @NonNull String refId) {
        return switch (type) {
            case TENANT -> "tenant:" + refId;
            case AGENT -> "agent:" + refId;
            case TOOL -> "tool:" + refId;
            case SKILL -> "skill:" + refId;
            case MCP_SERVER -> "mcp_server:" + refId;
        };
    }

    private static @NonNull List<String> distinct(@NonNull List<String> ids) {
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static @NonNull String label(@Nullable String name, @NonNull String fallback) {
        return name != null && !name.isBlank() ? name : fallback;
    }

    // ── 可变中间态 ────────────────────────────────────────────────

    /** 构建过程中的可变图谱（节点 / 边 / 双向邻接），完成后转不可变 {@link DependencyGraph}。 */
    private static final class Mutable {

        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final List<Edge> edges = new ArrayList<>();
        private final Map<String, List<Edge>> out = new HashMap<>();
        private final Map<String, List<Edge>> in = new HashMap<>();
        private boolean truncated;

        private void node(@NonNull String id, @NonNull NodeType type, @NonNull String refId,
                          @NonNull String label, @Nullable String detail, boolean exists,
                          boolean published, int version) {
            nodes.putIfAbsent(id, new Node(id, type, refId, label, detail, exists, published, version));
        }

        /**
         * 确保被引用的节点存在；目录/租户中无定义时建<b>幽灵节点</b>。
         *
         * <p>⚠️ 不静默丢边：「启用了不存在的 Skill」是配置缺陷，
         * 授权路径对它 fail-closed 拒绝，管理面必须看得见。
         */
        private void ensureNode(@NonNull String id, @NonNull NodeType type,
                                @NonNull String refId) {
            node(id, type, refId, refId, "悬空引用（无定义）", false, false, 0);
        }

        private void edge(@NonNull String from, @NonNull String to, @NonNull EdgeKind kind) {
            List<Edge> existing = out.get(from);
            if (existing != null) {
                for (Edge e : existing) {
                    if (e.to().equals(to) && e.kind() == kind) {
                        return; // 同一 (from,to,kind) 去重
                    }
                }
            }
            Edge e = new Edge(from, to, kind);
            edges.add(e);
            out.computeIfAbsent(from, k -> new ArrayList<>()).add(e);
            in.computeIfAbsent(to, k -> new ArrayList<>()).add(e);
        }

        private @NonNull List<Edge> outgoing(@NonNull String id) {
            return out.getOrDefault(id, List.of());
        }

        private @NonNull List<Edge> incoming(@NonNull String id) {
            return in.getOrDefault(id, List.of());
        }

        private @NonNull DependencyGraph toGraph(@NonNull String tenantId) {
            return new DependencyGraph(tenantId, List.copyOf(nodes.values()),
                    List.copyOf(edges), truncated);
        }

        /**
         * 超限截断（保插入序：tenant → 目录 → Agent），置 {@code truncated}。
         *
         * <p>⚠️ 截断是<b>展示降级</b>不是数据丢失承诺：响应带 {@code truncated=true}，
         * 前端明示「图谱过大已截断」，引导按实体走影响分析（按需展开）。
         */
        private void truncateIfNeeded() {
            if (nodes.size() > MAX_NODES) {
                Set<String> dropped = new HashSet<>();
                int i = 0;
                for (Iterator<String> it = nodes.keySet().iterator(); it.hasNext(); ) {
                    String id = it.next();
                    if (i++ >= MAX_NODES) {
                        dropped.add(id);
                        it.remove();
                    }
                }
                edges.removeIf(e -> dropped.contains(e.from()) || dropped.contains(e.to()));
                rebuildAdjacency();
                truncated = true;
            }
            if (edges.size() > MAX_EDGES) {
                while (edges.size() > MAX_EDGES) {
                    edges.remove(edges.size() - 1);
                }
                rebuildAdjacency();
                truncated = true;
            }
        }

        private void rebuildAdjacency() {
            out.clear();
            in.clear();
            for (Edge e : edges) {
                out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
                in.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
            }
        }
    }
}
