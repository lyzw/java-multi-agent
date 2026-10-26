package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapMcpServerDef.AuthKind;
import cn.studykid.multiagent.core.cap.CapMcpServerDef.Transport;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapSkillDef.McpDeclaration;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.DependencyEntityType;
import cn.studykid.multiagent.core.cap.DependencyGraph;
import cn.studykid.multiagent.core.cap.DependencyGraph.Edge;
import cn.studykid.multiagent.core.cap.DependencyGraph.EdgeKind;
import cn.studykid.multiagent.core.cap.DependencyGraph.Node;
import cn.studykid.multiagent.core.cap.DependencyGraph.NodeType;
import cn.studykid.multiagent.core.cap.ImpactReport;
import cn.studykid.multiagent.core.cap.McpTrustTier;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;

/**
 * 依赖图谱 / 影响分析测试（P3 · 管理面 M7）。
 *
 * <p><b>本类验证什么</b>：
 * <ul>
 *   <li>边正确性：{@code ENABLED_SKILL} / {@code DECLARED_TOOL} / {@code DECLARED_MCP} /
 *       {@code MCP_BINDING} / {@code OVERRIDE_REMOVED_*} / {@code TENANT_OF}；</li>
 *   <li>悬空引用以幽灵节点保留（{@code exists=false}），不静默丢边；</li>
 *   <li>影响分析：直接引用者、传递链（Tool→Skill→Agent、MCP→Tool→Skill→Agent）、
 *       受影响 Agent 去重、负向边同样计入命中；</li>
 *   <li>Agent 是链末端（V1 无对象引用 Agent）：对 Agent 自身不产出回溯链；</li>
 *   <li>超限截断标记。</li>
 * </ul>
 *
 * <p>⚠️ 不依赖数据库：四个端口以 Mockito 桩替代，聚焦<b>图结构与链路语义</b>。
 * 仓储侧（RLS 绑定、DISTINCT ON 口径）由 IT 覆盖。
 */
@DisplayName("P3 DependencyGraphService · 依赖图谱与影响分析")
@ExtendWith(MockitoExtension.class)
class DependencyGraphServiceTest {

    private static final String TENANT = "tenant-1";
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    @Mock private CapAgentDefPort agentDefs;
    @Mock private CapToolDefPort toolDefs;
    @Mock private CapSkillDefPort skillDefs;
    @Mock private CapMcpServerDefPort serverDefs;

    private DependencyGraphService service;

    @BeforeEach
    void setUp() {
        service = new DependencyGraphService(agentDefs, toolDefs, skillDefs, serverDefs);
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    /**
     * 标准夹具（引用拓扑）：
     * <pre>
     *   tenant ─▶ a-1 ─ENABLED_SKILL─▶ sk-1 ─DECLARED_TOOL─▶ t-native
     *                  │                    └─DECLARED_MCP──▶ srv-1 ◀─MCP_BINDING─ t-mcp
     *                  └─ENABLED_SKILL─▶ sk-2 ─DECLARED_TOOL─▶ t-mcp
     *                                      └─DECLARED_TOOL─▶ t-missing（悬空）
     *   a-2 ─ENABLED_SKILL─▶ {sk-1, sk-2, sk-missing（悬空）}
     *       └OVERRIDE_REMOVED_TOOL─⇢ t-mcp（负向）
     * </pre>
     */
    private void stubStandardFixtures() {
        when(toolDefs.listAll()).thenReturn(List.of(
                tool("t-native", CapToolKind.NATIVE, CapabilityRiskLevel.READ, null, null, true),
                tool("t-mcp", CapToolKind.MCP, CapabilityRiskLevel.WRITE_EXTERNAL,
                        "srv-1", "search", true)));
        when(skillDefs.listAll()).thenReturn(List.of(
                skill("sk-1", List.of("t-native"),
                        List.of(new McpDeclaration("srv-1", null)), true),
                skill("sk-2", List.of("t-mcp", "t-missing"), List.of(), true)));
        when(serverDefs.listAll()).thenReturn(List.of(
                server("srv-1", true)));
        when(agentDefs.listLatestPerAgent(TENANT)).thenReturn(List.of(
                agent("a-1", 3, List.of("sk-1"), "{}", true),
                agent("a-2", 1, List.of("sk-1", "sk-2", "sk-missing"),
                        "{\"removeTools\":[\"t-mcp\"]}", false)));
    }

    private static CapToolDef tool(String id, CapToolKind kind, CapabilityRiskLevel risk,
                                   String mcpServerId, String mcpToolName, boolean published) {
        return new CapToolDef(id, 1, id + "-name", kind, risk, mcpServerId, mcpToolName,
                "{}", "{}", "fp-" + id, 1, NOW, published ? NOW : null);
    }

    private static CapSkillDef skill(String id, List<String> declaredTools,
                                     List<McpDeclaration> declaredMcp, boolean published) {
        return new CapSkillDef(id, 2, id + "-name", null, declaredTools, declaredMcp,
                CapabilityRiskLevel.READ, "{}", List.of(), "fp-" + id, 1, NOW,
                published ? NOW : null);
    }

    private static CapMcpServerDef server(String id, boolean published) {
        return new CapMcpServerDef(id, 5, Transport.HTTP, "https://" + id + ".example",
                null, AuthKind.TOKEN, McpTrustTier.INTERNAL, null,
                CapabilityRiskLevel.READ, false, "fp-" + id, null, 1, NOW,
                published ? NOW : null);
    }

    private static CapAgentDef agent(String agentId, int version, List<String> enabledSkills,
                                     String overrides, boolean published) {
        return new CapAgentDef(TENANT, agentId, version, agentId + "-name", null,
                List.of(), enabledSkills, List.of(), List.of(), overrides,
                "fp-" + agentId, 1, NOW, published ? NOW : null);
    }

    private static boolean hasEdge(DependencyGraph g, String from, String to, EdgeKind kind) {
        return g.edges().stream()
                .anyMatch(e -> e.from().equals(from) && e.to().equals(to) && e.kind() == kind);
    }

    private static Node nodeOf(DependencyGraph g, String id) {
        return g.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElse(null);
    }

    // ── 图谱 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 节点与边：四类实体 + 租户根 + 全部引用方向")
    void graph_nodesAndEdges() {
        stubStandardFixtures();

        DependencyGraph g = service.graph(TENANT);

        // 节点：tenant + 2 agent + 2 skill + 2 tool + 1 mcp + 2 幽灵（t-missing / sk-missing）
        assertThat(g.nodes()).hasSize(10);
        assertThat(g.truncated()).isFalse();

        assertThat(nodeOf(g, "tenant:" + TENANT)).isNotNull()
                .satisfies(n -> assertThat(n.type()).isEqualTo(NodeType.TENANT));
        assertThat(nodeOf(g, "agent:a-1")).isNotNull()
                .satisfies(n -> {
                    assertThat(n.type()).isEqualTo(NodeType.AGENT);
                    assertThat(n.label()).isEqualTo("a-1-name");
                    assertThat(n.published()).isTrue();
                    assertThat(n.detail()).isEqualTo("v3");
                });
        assertThat(nodeOf(g, "agent:a-2")).isNotNull()
                .satisfies(n -> assertThat(n.published()).isFalse()
                        .as("未发布 Agent 节点必须如实标注").isFalse());

        // 悬空引用：幽灵节点存在但无定义
        assertThat(nodeOf(g, "skill:sk-missing")).isNotNull()
                .satisfies(n -> {
                    assertThat(n.exists()).isFalse();
                    assertThat(n.version()).isZero();
                });
        assertThat(nodeOf(g, "tool:t-missing")).isNotNull()
                .satisfies(n -> assertThat(n.exists()).isFalse());

        // 边：七种 kind 全覆盖（除 TENANT_OF 外六种 + 租户根）
        assertThat(hasEdge(g, "tenant:" + TENANT, "agent:a-1", EdgeKind.TENANT_OF)).isTrue();
        assertThat(hasEdge(g, "tenant:" + TENANT, "agent:a-2", EdgeKind.TENANT_OF)).isTrue();
        assertThat(hasEdge(g, "agent:a-1", "skill:sk-1", EdgeKind.ENABLED_SKILL)).isTrue();
        assertThat(hasEdge(g, "agent:a-2", "skill:sk-missing", EdgeKind.ENABLED_SKILL))
                .isTrue();
        assertThat(hasEdge(g, "agent:a-2", "skill:sk-2", EdgeKind.ENABLED_SKILL)).isTrue();
        assertThat(hasEdge(g, "agent:a-2", "tool:t-mcp", EdgeKind.OVERRIDE_REMOVED_TOOL))
                .isTrue();
        assertThat(hasEdge(g, "skill:sk-1", "tool:t-native", EdgeKind.DECLARED_TOOL)).isTrue();
        assertThat(hasEdge(g, "skill:sk-1", "mcp_server:srv-1", EdgeKind.DECLARED_MCP)).isTrue();
        assertThat(hasEdge(g, "skill:sk-2", "tool:t-missing", EdgeKind.DECLARED_TOOL)).isTrue();
        assertThat(hasEdge(g, "tool:t-mcp", "mcp_server:srv-1", EdgeKind.MCP_BINDING)).isTrue();

        // 无多余边（去重生效：sk-missing 只一条）
        assertThat(g.edges().stream()
                .filter(e -> e.to().equals("skill:sk-missing"))).hasSize(1);
    }

    @Test
    @DisplayName("✅ overrides 负向边：显式移除同样计入引用")
    void graph_overrideRemovedEdges() {
        stubStandardFixtures();

        DependencyGraph g = service.graph(TENANT);

        assertThat(hasEdge(g, "agent:a-2", "tool:t-mcp", EdgeKind.OVERRIDE_REMOVED_TOOL))
                .isTrue();
        // 负向边不是启用边：a-2 没有 ENABLED_SKILL 到 sk-1 之外的多余边
        assertThat(g.edges().stream()
                .filter(e -> e.from().equals("agent:a-2")
                        && e.kind() == EdgeKind.OVERRIDE_REMOVED_TOOL))
                .hasSize(1);
    }

    @Test
    @DisplayName("⛔ overrides 解析失败 → 整体抛错（不展示错误依赖）")
    void graph_malformedOverridesFailClosed() {
        when(toolDefs.listAll()).thenReturn(List.of());
        when(skillDefs.listAll()).thenReturn(List.of());
        when(serverDefs.listAll()).thenReturn(List.of());
        when(agentDefs.listLatestPerAgent(TENANT)).thenReturn(List.of(
                agent("a-1", 1, List.of(), "{not-json", true)));

        assertThatThrownBy(() -> service.graph(TENANT))
                .isInstanceOf(ClosureResolutionException.class);
    }

    @Test
    @DisplayName("✅ 超限截断：节点超上限置 truncated（展示降级）")
    void graph_truncated() {
        List<CapSkillDef> many = new java.util.ArrayList<>();
        for (int i = 0; i < 600; i++) {
            many.add(skill("sk-" + i, List.of(), List.of(), true));
        }
        when(toolDefs.listAll()).thenReturn(List.of());
        when(skillDefs.listAll()).thenReturn(many);
        when(serverDefs.listAll()).thenReturn(List.of());
        when(agentDefs.listLatestPerAgent(TENANT)).thenReturn(List.of());

        DependencyGraph g = service.graph(TENANT);

        assertThat(g.truncated()).isTrue();
        assertThat(g.nodes()).hasSize(500);
        assertThat(g.edges()).isEmpty();
    }

    // ── 影响分析 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ Tool 影响：直接引用 Skill + 传递链到受影响 Agent")
    void impact_toolDependentsAndChains() {
        stubStandardFixtures();

        ImpactReport r = service.impact(TENANT, DependencyEntityType.TOOL, "t-native");

        assertThat(r.entityExists()).isTrue();
        assertThat(r.entityLabel()).isEqualTo("t-native-name");
        assertThat(r.dependencies()).isEmpty();
        assertThat(r.dependents()).hasSize(1)
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.type()).isEqualTo("SKILL");
                    assertThat(d.id()).isEqualTo("sk-1");
                    assertThat(d.kind()).isEqualTo(EdgeKind.DECLARED_TOOL);
                });
        // t-native ← sk-1 ← {a-1, a-2}
        assertThat(r.chains()).hasSize(2);
        assertThat(r.affectedAgentIds()).containsExactlyInAnyOrder("a-1", "a-2");
        assertThat(r.chains()).allSatisfy(c -> {
            assertThat(c.path().get(0).id()).isEqualTo("t-native");
            assertThat(c.path().get(1).id()).isEqualTo("sk-1");
        });
    }

    @Test
    @DisplayName("✅ MCP 影响：跨层传递链（MCP→Tool→Skill→Agent）+ 负向命中")
    void impact_mcpTransitiveAndNegative() {
        stubStandardFixtures();

        ImpactReport r = service.impact(TENANT, DependencyEntityType.MCP_SERVER, "srv-1");

        assertThat(r.dependents()).hasSize(2)
                .anySatisfy(d -> {
                    assertThat(d.type()).isEqualTo("SKILL");
                    assertThat(d.id()).isEqualTo("sk-1");
                    assertThat(d.kind()).isEqualTo(EdgeKind.DECLARED_MCP);
                })
                .anySatisfy(d -> {
                    assertThat(d.type()).isEqualTo("TOOL");
                    assertThat(d.id()).isEqualTo("t-mcp");
                    assertThat(d.kind()).isEqualTo(EdgeKind.MCP_BINDING);
                });
        // srv-1 ← sk-1 ← {a-1,a-2}；srv-1 ← t-mcp ← sk-2 ← a-2；srv-1 ← t-mcp ⇠ a-2（负向直达）
        assertThat(r.chains()).hasSize(4);
        assertThat(r.affectedAgentIds()).containsExactlyInAnyOrder("a-1", "a-2");
        assertThat(r.chains()).anySatisfy(c -> {
            assertThat(c.path()).hasSize(4);
            assertThat(c.path().get(1).id()).isEqualTo("t-mcp");
            assertThat(c.path().get(2).id()).isEqualTo("sk-2");
            assertThat(c.agentId()).isEqualTo("a-2");
        });
    }

    @Test
    @DisplayName("✅ Tool 影响：负向边命中（Agent 显式移除该 Tool）")
    void impact_negativeEdgeHitsAgent() {
        stubStandardFixtures();

        ImpactReport r = service.impact(TENANT, DependencyEntityType.TOOL, "t-mcp");

        assertThat(r.dependents()).hasSize(2)
                .anySatisfy(d -> {
                    assertThat(d.type()).isEqualTo("SKILL");
                    assertThat(d.kind()).isEqualTo(EdgeKind.DECLARED_TOOL);
                })
                .anySatisfy(d -> {
                    assertThat(d.type()).isEqualTo("AGENT");
                    assertThat(d.id()).isEqualTo("a-2");
                    assertThat(d.kind()).isEqualTo(EdgeKind.OVERRIDE_REMOVED_TOOL);
                });
        // 负向边直达 Agent：链 [t-mcp, a-2]
        assertThat(r.chains()).anySatisfy(c -> {
            assertThat(c.path()).hasSize(2);
            assertThat(c.path().get(1).id()).isEqualTo("a-2");
            assertThat(c.agentId()).isEqualTo("a-2");
        });
        assertThat(r.affectedAgentIds()).contains("a-2");
    }

    @Test
    @DisplayName("✅ Skill 影响：出向依赖 + 入向 Agent + 悬空引用告警")
    void impact_skillDependenciesAndDangling() {
        stubStandardFixtures();

        ImpactReport r = service.impact(TENANT, DependencyEntityType.SKILL, "sk-2");

        // 出向：sk-2 声明了 t-mcp（存在）与 t-missing（悬空）
        assertThat(r.dependencies()).hasSize(2);
        assertThat(r.danglingRefs()).hasSize(1)
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.type()).isEqualTo("TOOL");
                    assertThat(d.id()).isEqualTo("t-missing");
                    assertThat(d.via()).isEqualTo(EdgeKind.DECLARED_TOOL);
                });
        // 入向：a-2 启用 sk-2
        assertThat(r.dependents()).hasSize(1)
                .singleElement()
                .satisfies(d -> assertThat(d.id()).isEqualTo("a-2"));
    }

    @Test
    @DisplayName("✅ Agent 影响：出向依赖可见；无入向（V1 无对象引用 Agent）")
    void impact_agentDependenciesNoDependents() {
        stubStandardFixtures();

        ImpactReport r = service.impact(TENANT, DependencyEntityType.AGENT, "a-1");

        assertThat(r.dependencies()).hasSize(1)
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.type()).isEqualTo("SKILL");
                    assertThat(d.id()).isEqualTo("sk-1");
                    assertThat(d.kind()).isEqualTo(EdgeKind.ENABLED_SKILL);
                });
        // TENANT_OF 是所有权边，不计入 dependents
        assertThat(r.dependents()).isEmpty();
        assertThat(r.chains()).isEmpty();
        assertThat(r.affectedAgentIds()).isEmpty();
    }

    @Test
    @DisplayName("✅ 幽灵实体 / 不存在实体 → entityExists=false（控制器转 404）")
    void impact_unknownEntityNotFound() {
        stubStandardFixtures();

        assertThat(service.impact(TENANT, DependencyEntityType.TOOL, "t-missing")
                .entityExists()).isFalse();
        assertThat(service.impact(TENANT, DependencyEntityType.AGENT, "no-such-agent")
                .entityExists()).isFalse();
        assertThat(service.impact(TENANT, DependencyEntityType.SKILL, "no-such-skill")
                .entityExists()).isFalse();
    }

    @Test
    @DisplayName("✅ 空租户：只有租户根节点，无环无异常")
    void graph_emptyTenant() {
        when(toolDefs.listAll()).thenReturn(List.of());
        when(skillDefs.listAll()).thenReturn(List.of());
        when(serverDefs.listAll()).thenReturn(List.of());
        when(agentDefs.listLatestPerAgent(TENANT)).thenReturn(List.of());

        DependencyGraph g = service.graph(TENANT);

        assertThat(g.nodes()).hasSize(1);
        assertThat(g.edges()).isEmpty();
        assertThat(g.truncated()).isFalse();
    }
}
