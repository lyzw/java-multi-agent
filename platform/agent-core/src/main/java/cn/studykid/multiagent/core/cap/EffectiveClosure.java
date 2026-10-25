package cn.studykid.multiagent.core.cap;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.orchestration.OrchestrationBinding;
import cn.studykid.multiagent.core.orchestration.OrchestrationMode;

/**
 * 有效能力闭包（§6.2「Run 快照：固定版本集 + 闭包指纹」）。
 *
 * <p>闭包 = 一次调用实际依赖的<b>全部</b>对象及其固定版本：
 * Agent → Skill → {Tool, MCP Server}。它是 §6.4 闭包指纹的<b>唯一输入</b>，
 * 也是 Phase A 判定的唯一事实依据。
 *
 * <p>⚠️ <b>闭包只含「本 Run 依赖的对象」</b>（§6.4：无关对象变更不触发本 Run 失效）。
 * 因此闭包里<b>不能</b>混入全局发布序号（{@code configVersion}）——
 * §7.3 明确「不得将『仅全局序号变、闭包未变』自动视为授权失败」。
 *
 * <p>⚠️ <b>闭包记录的是「生效后」的状态</b>：Agent 的 {@code overrides}
 * （移除 Skill / 移除 Tool / 下调 riskCeiling）已在解析阶段应用完毕。
 * 这样「移除一个 Tool」会直接改变闭包 → 改变指纹 → 触发失效判定，
 * 而不是留下一条需要额外解释的 override 记录。
 *
 * <p><b>确定性要求</b>：同样的依赖集合必须产出<b>逐字段相同</b>的闭包
 * （各集合均按字典序排列），否则指纹不稳定，失效判定会随机误报。
 *
 * <p>⚠️ <b>多级 Agent 的后代维度</b>（v1.1）：{@code childAgents} 记录「父 → 子」的
 * 固定版本委派边。<b>子 Agent 的 Skill/Tool 不并入本闭包的工具集</b> ——
 * 委派不是授权，父 Agent 不能借委派直接使用子 Agent 的能力；
 * 但子 Agent 的<b>闭包指纹</b>会进入父闭包指纹，
 * 从而「后代的行为性变化是否影响我」有确定答案（MA-T15）。
 *
 * <p>⚠️ <b>编排声明必须固化进闭包</b>（v1.1 · MA-T15 / MA-T18）：
 * {@code orchestration} 承载本 Agent 自身的编排声明
 * （路由策略引用、输出契约引用、超时/深度/次数上限、失败策略）。
 * 不固化它就会出现「<b>指纹说没变，行为却变了</b>」——
 * 在途 Run 不失效、绑定了旧指纹的审批被继续复用，
 * 而审计看到的却是「配置没动过」。
 * 编排声明是<b>行为性</b>的（它决定委派给谁、按什么 Schema 校验输出、能跑多久），
 * 正因如此才必须与 Skill/Tool 一样进指纹。
 */
public record EffectiveClosure(
        @NonNull String agentId,
        int agentVersion,
        @NonNull List<SkillBinding> skills,
        @NonNull List<ToolBinding> tools,
        @NonNull List<McpBinding> mcpServers,
        @NonNull List<ChildAgentBinding> childAgents,
        @NonNull OrchestrationBinding orchestration) {

    /**
     * 兼容构造器：无子 Agent 的闭包（单 Agent 场景的全部既有调用点）。
     *
     * <p>⚠️ 单 Agent 的闭包<b>结构上</b>与多级闭包同构，只是「后代集合」为空 ——
     * 这样指纹算法只有一套实现，不需要为 LEAF / 多级各写一份。
     */
    public EffectiveClosure(@NonNull String agentId,
                            int agentVersion,
                            @NonNull List<SkillBinding> skills,
                            @NonNull List<ToolBinding> tools,
                            @NonNull List<McpBinding> mcpServers) {
        this(agentId, agentVersion, skills, tools, mcpServers, List.of(),
                OrchestrationBinding.leaf());
    }

    /**
     * 兼容构造器：有子 Agent 但<b>无编排声明</b>的闭包。
     *
     * <p>⚠️ 编排绑定缺省按 {@link OrchestrationBinding#leaf()} 处理 ——
     * 「没有编排声明」与「声明为 LEAF」在闭包层面是同一件事（MA-T01）。
     */
    public EffectiveClosure(@NonNull String agentId,
                            int agentVersion,
                            @NonNull List<SkillBinding> skills,
                            @NonNull List<ToolBinding> tools,
                            @NonNull List<McpBinding> mcpServers,
                            @NonNull List<ChildAgentBinding> childAgents) {
        this(agentId, agentVersion, skills, tools, mcpServers, childAgents,
                OrchestrationBinding.leaf());
    }

    /**
     * 子 Agent 绑定：闭包中的一条「父 → 子」委派边（多级 Agent v1.1）。
     *
     * <p>⚠️ <b>子 Agent 的能力<b>不并入</b>父 Agent 的工具集</b>：
     * 委派关系不是授权关系 —— 父 Agent 不能因为「能委派给 X」就直接调用 X 的工具。
     * 本绑定只承载<b>依赖信息</b>，用途是让父闭包指纹覆盖后代，
     * 从而「后代变了吗」这个问题有确定答案（MA-T15）。
     *
     * @param agentId            子 Agent 标识
     * @param releaseVersion     子 Agent 的<b>固定版本号</b>（委派目标必须固定 release）
     * @param configFingerprint  子 Agent 版本行的内容指纹
     * @param closureFingerprint 子 Agent <b>自身闭包</b>的指纹 ——
     *                          递归覆盖其 Skill/Tool/MCP 及<b>更深层后代</b>，
     *                          故任何后代的任何行为性变化都会传导到父闭包指纹
     * @param mode               子 Agent 的编排模式
     * @param depth              该子 Agent 相对本闭包根的深度（直接子 = 1）
     */
    public record ChildAgentBinding(
            @NonNull String agentId,
            int releaseVersion,
            @NonNull String configFingerprint,
            @NonNull String closureFingerprint,
            @NonNull OrchestrationMode mode,
            int depth) {
    }

    /**
     * Skill 绑定：闭包中的一个 Skill 版本。
     *
     * @param skillId        Skill 标识（逻辑 id）
     * @param version        固定版本号
     * @param riskCeiling    <b>生效后</b>的风险上限（已含 Agent overrides 的下调）
     * @param configFingerprint 该 Skill 版本行的内容指纹
     * @param declaredTools  该 Skill 声明的 Tool id（<b>已应用 overrides 的移除</b>）——
     *                       这是「Skill → Tool」的边关系，
     *                       判定 {@code SkillToolAllowed} 时需要它
     * @param paramPolicies  该 Skill 的参数策略（JSON 文本）——
     *                       §4.3「Skill 参数策略仅可进一步收紧」，
     *                       求值时与 Tool 策略取交集
     */
    public record SkillBinding(
            @NonNull String skillId,
            int version,
            @NonNull CapabilityRiskLevel riskCeiling,
            @NonNull String configFingerprint,
            @NonNull List<String> declaredTools,
            @NonNull String paramPolicies) {
    }

    /**
     * Tool 绑定：闭包中的一个 Tool 版本。
     *
     * @param toolId        Tool 标识（逻辑 id）
     * @param version       固定版本号
     * @param kind          Tool 种类（native / mcp）
     * @param riskLevel     Tool 的实际风险等级（未知即 {@link CapabilityRiskLevel#UNKNOWN}）
     * @param configFingerprint 该 Tool 版本行的内容指纹
     * @param paramPolicies 该 Tool 的参数策略（JSON 文本，原样保留以参与指纹）
     * @param mcpServerId   MCP Server 标识（仅 kind=mcp 时非空）
     * @param mcpToolName   MCP 远端操作名（仅 kind=mcp 时非空）——
     *                       它就是 §4.4 意义下的「操作（op）」
     */
    public record ToolBinding(
            @NonNull String toolId,
            int version,
            @NonNull CapToolKind kind,
            @NonNull CapabilityRiskLevel riskLevel,
            @NonNull String configFingerprint,
            @NonNull String paramPolicies,
            @Nullable String mcpServerId,
            @Nullable String mcpToolName) {

        /**
         * @return 是否为 MCP 工具
         */
        public boolean isMcp() {
            return kind == CapToolKind.MCP;
        }
    }

    /**
     * MCP Server 绑定：某 Skill 对某 MCP Server 的允许操作边。
     *
     * @param serverId        MCP Server 标识
     * @param version         固定版本号
     * @param skillId         声明该 Server 的 Skill（同一 Server 可被多个 Skill 声明，
     *                        各 Skill 的操作清单不同，故按边记录）
     * @param trustTier       信任层级（ForcedSafety 判定要素）
     * @param connectionRisk  连接层风险
     * @param manifestOps     闭包固化时的 MCP 固定清单（{@code ManifestOps} 快照）
     * @param allowedOps      本条边实际允许的操作集
     *                       （{@code SkillAllowedOps ∩ ManifestOps}）
     */
    public record McpBinding(
            @NonNull String serverId,
            int version,
            @NonNull String skillId,
            @NonNull McpTrustTier trustTier,
            @NonNull CapabilityRiskLevel connectionRisk,
            @NonNull Set<String> manifestOps,
            @NonNull Set<String> allowedOps) {
    }

    /**
     * @param skillId Skill 标识
     * @return 该 Skill 的绑定；未启用时为 {@link Optional#empty()}
     */
    public @NonNull Optional<SkillBinding> skill(@NonNull String skillId) {
        return skills.stream().filter(s -> s.skillId().equals(skillId)).findFirst();
    }

    /**
     * @param toolId Tool 标识
     * @return 该 Tool 的绑定；不在闭包内时为 {@link Optional#empty()}
     */
    public @NonNull Optional<ToolBinding> tool(@NonNull String toolId) {
        return tools.stream().filter(t -> t.toolId().equals(toolId)).findFirst();
    }

    /**
     * @param skillId  声明方 Skill 标识
     * @param serverId MCP Server 标识
     * @return 该边；未声明时为 {@link Optional#empty()}
     */
    public @NonNull Optional<McpBinding> mcpBinding(@NonNull String skillId,
                                                    @NonNull String serverId) {
        return mcpServers.stream()
                .filter(b -> b.skillId().equals(skillId) && b.serverId().equals(serverId))
                .findFirst();
    }

    /**
     * @param serverId MCP Server 标识
     * @return 闭包中该 Server 的全部边
     */
    public @NonNull List<McpBinding> mcpBindingsOf(@NonNull String serverId) {
        return mcpServers.stream().filter(b -> b.serverId().equals(serverId)).toList();
    }

    /**
     * @param agentId 子 Agent 标识
     * @return 该子 Agent 的委派边；未声明时为 {@link Optional#empty()}
     */
    public @NonNull Optional<ChildAgentBinding> childAgent(@NonNull String agentId) {
        return childAgents.stream().filter(b -> b.agentId().equals(agentId)).findFirst();
    }

    /**
     * @return 闭包是否为空（Agent 未启用任何 Skill）
     */
    public boolean isEmpty() {
        return skills.isEmpty() && tools.isEmpty() && mcpServers.isEmpty();
    }

    /**
     * 构造空闭包（Agent 未启用任何 Skill —— 这是<b>合法</b>状态，不是解析失败）。
     *
     * @param agentId      Agent 标识
     * @param agentVersion Agent 定义版本
     * @return 空闭包
     */
    public static @NonNull EffectiveClosure empty(@NonNull String agentId, int agentVersion) {
        return new EffectiveClosure(agentId, agentVersion, List.of(), List.of(), List.of(),
                List.of(), OrchestrationBinding.leaf());
    }

    /**
     * 规整为确定性顺序（各集合按标识字典序）。
     *
     * <p>⚠️ <b>必须在生成指纹前调用</b>：否则同样的依赖集合会因解析顺序不同
     * 产出不同指纹，把「内容未变」误判成「内容变化」。
     *
     * @return 规整后的闭包
     */
    public @NonNull EffectiveClosure canonicalized() {
        return new EffectiveClosure(
                agentId,
                agentVersion,
                skills.stream()
                        .sorted(java.util.Comparator.comparing(SkillBinding::skillId))
                        .map(s -> new SkillBinding(s.skillId(), s.version(), s.riskCeiling(),
                                s.configFingerprint(),
                                s.declaredTools().stream().distinct().sorted().toList(),
                                s.paramPolicies()))
                        .toList(),
                tools.stream()
                        .sorted(java.util.Comparator.comparing(ToolBinding::toolId))
                        .toList(),
                mcpServers.stream()
                        .sorted(java.util.Comparator
                                .comparing(McpBinding::skillId)
                                .thenComparing(McpBinding::serverId))
                        .map(b -> new McpBinding(b.serverId(), b.version(), b.skillId(),
                                b.trustTier(), b.connectionRisk(),
                                b.manifestOps().stream().sorted()
                                        .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)),
                                b.allowedOps().stream().sorted()
                                        .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new))))
                        .toList(),
                childAgents.stream()
                        // ⚠️ 排序键必须包含 depth：同一子 release 可能在不同深度出现
                        //    （A→B→C 与 A→C 是两条不同的边），只按 id 排序会不稳定。
                        .sorted(java.util.Comparator
                                .comparing(ChildAgentBinding::depth)
                                .thenComparing(ChildAgentBinding::agentId)
                                .thenComparingInt(ChildAgentBinding::releaseVersion))
                        .toList(),
                // ⚠️ 编排绑定原样透传：它是单个值，规整无意义，
                //    但**必须**在这里显式传下去 —— 漏传会让闭包退回「无编排声明」，
                //    指纹随之变化，在途 Run 被无故判失效（MA-T15 的反面：误失效）。
                orchestration);
    }
}
