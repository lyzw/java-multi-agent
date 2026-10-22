package cn.studykid.multiagent.core.cap;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
 */
public record EffectiveClosure(
        @NonNull String agentId,
        int agentVersion,
        @NonNull List<SkillBinding> skills,
        @NonNull List<ToolBinding> tools,
        @NonNull List<McpBinding> mcpServers) {

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
        return new EffectiveClosure(agentId, agentVersion, List.of(), List.of(), List.of());
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
                        .toList());
    }
}
