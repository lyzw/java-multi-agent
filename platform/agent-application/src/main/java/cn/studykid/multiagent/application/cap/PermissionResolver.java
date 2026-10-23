package cn.studykid.multiagent.application.cap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapObjectType;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.mcp.McpManifestPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.PublishedVersionPort;

/**
 * 无副作用能力闭包计算（§7.1 {@code PermissionResolver} · §6.2 · §4.4）。
 *
 * <p>闭包 = Agent → Skill → {Tool, MCP Server} 的<b>交集</b>：
 * <ul>
 *   <li>Agent 决定<b>启用哪些 Skill</b>（{@code enabledSkills} 是 V1 唯一启用途径，§4.2）；</li>
 *   <li>Skill 决定<b>声明哪些 Tool</b> 与<b>声明哪些 MCP Server（及操作白名单）</b>；</li>
 *   <li>Agent 的 {@code overrides} <b>只能收紧</b>（§4.2）：移除 Skill / 移除 Tool / 下调 riskCeiling。</li>
 * </ul>
 *
 * <p>⚠️ <b>无副作用</b>：本类只读，不写库、不发消息、不改任何状态。
 * 它是 Phase A 步骤 3 的输入来源，必须可被反复调用而不产生副作用。
 *
 * <p>⚠️ <b>fail-closed</b>：任何对象解析不到（无已发布版本 / 版本行缺失）都抛
 * {@link ClosureResolutionException}，由调用方转成拒绝。
 * <b>绝不</b>退化为「跳过该对象」—— 跳过等于把「声明了但解析不出」当成「没声明」，
 * 那会让越权被伪装成「工具未声明」。
 */
public class PermissionResolver implements ClosureResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CapAgentDefPort agentDefs;
    private final CapSkillDefPort skillDefs;
    private final CapToolDefPort toolDefs;
    private final CapMcpServerDefPort serverDefs;
    private final PublishedVersionPort publishedVersions;
    private final McpManifestPort manifest;

    public PermissionResolver(@NonNull CapAgentDefPort agentDefs,
                              @NonNull CapSkillDefPort skillDefs,
                              @NonNull CapToolDefPort toolDefs,
                              @NonNull CapMcpServerDefPort serverDefs,
                              @NonNull PublishedVersionPort publishedVersions,
                              @NonNull McpManifestPort manifest) {
        this.agentDefs = agentDefs;
        this.skillDefs = skillDefs;
        this.toolDefs = toolDefs;
        this.serverDefs = serverDefs;
        this.publishedVersions = publishedVersions;
        this.manifest = manifest;
    }

    /**
     * 解析<b>固定版本</b>的闭包（创建 Run 快照时用 · §6.2）。
     *
     * <p>Agent 版本由调用方给定（来自 Run 的创建上下文），
     * Skill / Tool / MCP Server 的版本由当前已发布 release 解析。
     *
     * @param tenantId    租户标识
     * @param agentId     Agent 标识
     * @param agentVersion Agent 定义版本
     * @return 有效能力闭包
     * @throws ClosureResolutionException 解析失败
     */
    @Override
    public @NonNull EffectiveClosure resolve(@NonNull String tenantId,
                                              @NonNull String agentId,
                                              int agentVersion) {
        var agentDef = agentDefs.find(tenantId, agentId, agentVersion)
                .orElseThrow(() -> new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                        "Agent 定义不存在: tenant=" + tenantId + ", agent=" + agentId
                                + ", version=" + agentVersion));
        return build(agentId, agentVersion, agentDef);
    }

    /**
     * 解析<b>当前已发布</b>的闭包（授权期新鲜度校验用 · §5.3 步骤 7 / §6.4）。
     *
     * <p>与 {@link #resolve} 的唯一差别是 Agent 版本取「当前已发布版本」。
     * Skill / Tool / MCP Server 的版本解析口径两者相同 ——
     * 因为闭包的失效判定本来就建立在「依赖对象是否被重新发布」之上。
     *
     * @param tenantId 租户标识
     * @param agentId  Agent 标识
     * @return 有效能力闭包
     * @throws ClosureResolutionException 解析失败
     */
    @Override
    public @NonNull EffectiveClosure resolveCurrent(@NonNull String tenantId,
                                                     @NonNull String agentId) {
        var agentDef = agentDefs.findLatestPublished(tenantId, agentId)
                .orElseThrow(() -> new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                        "Agent 无已发布版本: tenant=" + tenantId + ", agent=" + agentId));
        return build(agentId, agentDef.version(), agentDef);
    }

    // ── 闭包构建 ──────────────────────────────────────────────────

    private @NonNull EffectiveClosure build(@NonNull String agentId,
                                             int agentVersion,
                                             @NonNull CapAgentDef agentDef) {
        Overrides overrides = Overrides.parse(agentDef.overrides());

        List<EffectiveClosure.@NonNull SkillBinding> skills = new ArrayList<>();
        Map<String, EffectiveClosure.@NonNull ToolBinding> tools = new LinkedHashMap<>();
        List<EffectiveClosure.@NonNull McpBinding> mcpBindings = new ArrayList<>();

        for (String skillId : agentDef.enabledSkills()) {
            // ⚠️ overrides 只能收紧：被移除的 Skill 直接不进入闭包
            if (overrides.removesSkill(skillId)) {
                continue;
            }
            int skillVersion = resolveVersion(CapObjectType.SKILL, skillId);
            CapSkillDef skill = skillDefs.find(skillId, skillVersion)
                    .orElseThrow(() -> new ClosureResolutionException(
                            ReasonCode.RESOURCE_VERSION_MISMATCH,
                            "Skill 版本行缺失: skill=" + skillId + ", version=" + skillVersion));

            CapabilityRiskLevel ceiling = effectiveCeiling(skill.riskCeiling(), overrides, skillId);

            // 声明的原生 Tool（去掉 overrides.removeTools）
            List<String> declaredTools = skill.declaredTools().stream()
                    .filter(toolId -> !overrides.removesTool(toolId))
                    .distinct()
                    .sorted()
                    .toList();
            for (String toolId : declaredTools) {
                int toolVersion = resolveVersion(CapObjectType.TOOL, toolId);
                CapToolDef tool = toolDefs.find(toolId, toolVersion)
                        .orElseThrow(() -> new ClosureResolutionException(
                                ReasonCode.RESOURCE_VERSION_MISMATCH,
                                "Tool 版本行缺失: tool=" + toolId + ", version=" + toolVersion));
                tools.putIfAbsent(toolId, toBinding(tool, toolVersion));
            }

            // MCP 绑定（§4.4 规范化后再交集）
            for (CapSkillDef.McpDeclaration declaration : skill.declaredMcp()) {
                String serverId = declaration.serverId();
                int serverVersion = resolveVersion(CapObjectType.MCP_SERVER, serverId);
                CapMcpServerDef server = serverDefs.find(serverId, serverVersion)
                        .orElseThrow(() -> new ClosureResolutionException(
                                ReasonCode.RESOURCE_VERSION_MISMATCH,
                                "MCP Server 版本行缺失: server=" + serverId
                                        + ", version=" + serverVersion));

                // ⚠️ 清单不可用 ≠ 清单为空：
                //    「读不到这个 server」与「这个 server 没有工具」是两件事。
                //    这里两者都让 allowedOps 变空（fail-closed），
                //    但「不可用」会在 §5.8 步骤 3 由 InvocationValidator 给出 MCP_TOOL_DRIFTED
                //    （无法确认操作仍在当前清单内），运维据此区分「宕机」与「下线」。
                McpManifestPort.ManifestSnapshot manifestSnapshot =
                        manifest.manifest(serverId);
                Set<String> manifestOps = new TreeSet<>(manifestSnapshot.ops());
                Set<String> serverAllowed = McpOpAllowlistNormalizer.serverAllowedOps(
                        server.exposedToolAllowlist(), manifestOps);
                Set<String> skillAllowed = McpOpAllowlistNormalizer.skillAllowedOps(
                        declaration.toolAllowlist(), serverAllowed);
                Set<String> allowed = applyForcedSafety(skillAllowed, server, serverId, ceiling);

                // 被 ForcedSafety 放行的操作，其工具定义一并进入闭包
                // （MCP 工具不在 declaredTools 里，只能沿 MCP 绑定反查）
                for (String op : allowed) {
                    toolDefs.findByMcpBinding(serverId, op).ifPresent(tool ->
                            tools.putIfAbsent(tool.id(), toBinding(tool, tool.version())));
                }

                mcpBindings.add(new EffectiveClosure.McpBinding(
                        serverId, serverVersion, skillId,
                        server.trustTier(), server.connectionRisk(),
                        manifestOps, allowed));
            }

            skills.add(new EffectiveClosure.SkillBinding(
                    skillId, skillVersion, ceiling, skill.configFingerprint(),
                    declaredTools, skill.paramPolicies()));
        }

        return new EffectiveClosure(agentId, agentVersion, skills,
                new ArrayList<>(tools.values()), mcpBindings).canonicalized();
    }

    private EffectiveClosure.@NonNull ToolBinding toBinding(@NonNull CapToolDef tool, int version) {
        return new EffectiveClosure.ToolBinding(
                tool.id(), version, tool.kind(), tool.riskLevel(),
                tool.configFingerprint(), tool.paramPolicies(),
                tool.mcpServerId(), tool.mcpToolName());
    }

    private int resolveVersion(@NonNull CapObjectType type, @NonNull String objectId) {
        return publishedVersions.currentVersion(type, objectId)
                .orElseThrow(() -> new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                        "对象当前无已发布版本: type=" + type.code() + ", id=" + objectId));
    }

    // ── §4.4 规范化与交集 ─────────────────────────────────────────

    /**
     * {@code ServerAllowedOps} / {@code SkillAllowedOps} 的规范化已抽到
     * {@link McpOpAllowlistNormalizer}（P4-T3）——
     * <b>全仓库只有那一处实现</b>，避免「两处各写一遍三态规则」导致语义漂移。
     */

    /**
     * ForcedSafety 逐操作过滤（§4.4）。
     *
     * <p>本阶段实现的谓词：
     * <ul>
     *   <li><b>风险上限</b>：操作的工具定义风险等级必须 ≤ Skill 上限；</li>
     *   <li><b>端点安全</b>（P4-T5 · 06 §5）：endpoint 的静态形态必须合规
     *       （协议 / 用户信息 / IP 字面量判定）。DNS 后的实际 IP 校验在传输层
     *       （{@code McpEndpointGuard}）—— Phase A 不触网。</li>
     * </ul>
     *
     * ⚠️ <b>查不到工具定义的操作一律剔除</b>（fail-closed）：
     * 无法确认其风险等级，就不能断言它满足 ForcedSafety。
     */
    private @NonNull Set<String> applyForcedSafety(@NonNull Set<String> candidateOps,
                                                    @NonNull CapMcpServerDef server,
                                                    @NonNull String serverId,
                                                    @NonNull CapabilityRiskLevel ceiling) {
        // ⚠️ 端点静态形态不合规 → 该 server 的<b>全部</b>操作都不放行。
        //    端点是连接级属性：endpoint 不安全时，按操作逐个放行毫无意义。
        if (!endpointIsStaticallySafe(server)) {
            return Set.of();
        }
        Set<String> allowed = new TreeSet<>();
        for (String op : candidateOps) {
            Optional<CapToolDef> bound = toolDefs.findByMcpBinding(serverId, op);
            if (bound.isEmpty()) {
                // 无对应工具定义 → 风险不可判定 → 剔除
                continue;
            }
            CapabilityRiskLevel risk = bound.get().riskLevel();
            if (risk.isUnknown()) {
                // ⚠️ 未知风险不得降级（§5.6 / P1 退出门禁）：直接剔除而非按最高风险放行
                continue;
            }
            if (!risk.exceeds(ceiling)) {
                allowed.add(op);
            }
        }
        return allowed;
    }

    /**
     * 端点的<b>静态</b>安全判定（P4-T5 · 06 §5）。
     *
     * <p>⚠️ <b>只做静态判定，不触网</b>：
     * Phase A 是授权路径，不应引入 DNS 解析（延迟 + 被用作放大器）。
     * 「域名解析到哪里」由传输层的 {@code McpEndpointGuard} 在<b>每次连接前</b>判定，
     * 且那里才是最终拦截点。
     *
     * <p>静态判定能拦住：非 http/sse 协议、URL 里带凭据、endpoint 直接写成
     * 内网/元数据 IP 字面量。
     */
    private boolean endpointIsStaticallySafe(@NonNull CapMcpServerDef server) {
        if (!server.transport().isSupportedInV1()) {
            // stdio 在 V1 未启用（N7）
            return false;
        }
        if (!McpServerConfigValidator.validate(server).isEmpty()) {
            return false;
        }
        String endpoint = server.endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return false;
        }
        // ⚠️ IP 字面量：直接按地址策略判定（云元数据 / 内网地址当场拒）
        String host = hostOf(endpoint);
        if (host == null) {
            return false;
        }
        java.net.InetAddress literal = literalAddress(host);
        if (literal != null) {
            return McpAddressPolicy.isAllowed(literal, server.trustTier());
        }
        return true;
    }

    private static @Nullable String hostOf(@NonNull String endpoint) {
        try {
            return java.net.URI.create(endpoint.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static java.net.@Nullable InetAddress literalAddress(@NonNull String host) {
        String candidate = host;
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        // ⚠️ 只认「确凿是 IP 字面量」的形态：含字母的一律当域名处理
        if (!candidate.matches("^(\\d{1,3}\\.){3}\\d{1,3}$") && !candidate.contains(":")) {
            return null;
        }
        try {
            return java.net.InetAddress.getByName(candidate);
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    private @NonNull CapabilityRiskLevel effectiveCeiling(@NonNull CapabilityRiskLevel skillCeiling,
                                                           @NonNull Overrides overrides,
                                                           @NonNull String skillId) {
        CapabilityRiskLevel result = skillCeiling;
        CapabilityRiskLevel global = overrides.globalLowerRiskCeiling();
        if (global != null) {
            // ⚠️ overrides 只能收紧：取更严者，override 更高时不得抬高上限
            result = CapabilityRiskLevel.stricter(result, global);
        }
        CapabilityRiskLevel perSkill = overrides.lowerRiskCeilingOf(skillId);
        if (perSkill != null) {
            result = CapabilityRiskLevel.stricter(result, perSkill);
        }
        return result;
    }

    // ── Agent overrides ───────────────────────────────────────────

    /**
     * Agent 的收紧覆盖（§4.2「overrides 仅收紧」）。
     *
     * <p>支持的形态：
     * <ul>
     *   <li>{@code removeSkills: [..]} —— 移除 Skill；</li>
     *   <li>{@code removeTools: [..]} —— 移除 Tool；</li>
     *   <li>{@code lowerRiskCeiling: "READ"} —— 全局下调风险上限；</li>
     *   <li>{@code lowerRiskCeiling: {"skill-a": "READ"}} —— 按 Skill 下调。</li>
     * </ul>
     *
     * ⚠️ <b>解析失败即拒绝</b>：覆盖写错却静默忽略，等于把「本应被收紧的配置」放行。
     */
    record Overrides(Set<String> removeSkills, Set<String> removeTools,
                     @Nullable CapabilityRiskLevel globalLowerRiskCeiling,
                     Map<String, CapabilityRiskLevel> perSkillLowerRiskCeiling) {

        static @NonNull Overrides parse(@Nullable String overridesJson) {
            if (overridesJson == null || overridesJson.isBlank()) {
                return new Overrides(Set.of(), Set.of(), null, Map.of());
            }
            JsonNode root;
            try {
                root = MAPPER.readTree(overridesJson);
            } catch (RuntimeException e) {
                throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                        "Agent overrides 不是合法 JSON: " + overridesJson);
            }
            if (root == null || root.isMissingNode() || root.isNull()) {
                return new Overrides(Set.of(), Set.of(), null, Map.of());
            }
            if (!root.isObject()) {
                throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                        "Agent overrides 结构非法: " + overridesJson);
            }
            ObjectNode node = (ObjectNode) root;
            Set<String> removeSkills = readStringSet(node.get("removeSkills"), "removeSkills");
            Set<String> removeTools = readStringSet(node.get("removeTools"), "removeTools");

            CapabilityRiskLevel global = null;
            Map<String, CapabilityRiskLevel> perSkill = new LinkedHashMap<>();
            JsonNode ceilingNode = node.get("lowerRiskCeiling");
            if (ceilingNode != null && !ceilingNode.isMissingNode() && !ceilingNode.isNull()) {
                if (ceilingNode.isTextual()) {
                    global = CapabilityRiskLevel.parse(ceilingNode.asText());
                    if (global.isUnknown()) {
                        throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                                "lowerRiskCeiling 取值非法: " + ceilingNode.asText());
                    }
                } else if (ceilingNode.isObject()) {
                    ceilingNode.properties().forEach(entry -> {
                        CapabilityRiskLevel level = CapabilityRiskLevel.parse(entry.getValue().asText(null));
                        if (level.isUnknown()) {
                            throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                                    "lowerRiskCeiling 取值非法: skill=" + entry.getKey());
                        }
                        perSkill.put(entry.getKey(), level);
                    });
                } else {
                    throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                            "lowerRiskCeiling 结构非法");
                }
            }
            return new Overrides(removeSkills, removeTools, global, Map.copyOf(perSkill));
        }

        private static @NonNull Set<String> readStringSet(@Nullable JsonNode node, @NonNull String field) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return Set.of();
            }
            if (!node.isArray()) {
                throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                        "Agent overrides." + field + " 结构非法");
            }
            Set<String> values = new LinkedHashSet<>();
            for (JsonNode element : node) {
                if (!element.isTextual()) {
                    throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                            "Agent overrides." + field + " 含非字符串元素");
                }
                values.add(element.asText());
            }
            return Set.copyOf(values);
        }

        boolean removesSkill(@NonNull String skillId) {
            return removeSkills.contains(skillId);
        }

        boolean removesTool(@NonNull String toolId) {
            return removeTools.contains(toolId);
        }

        @Nullable CapabilityRiskLevel lowerRiskCeilingOf(@NonNull String skillId) {
            return perSkillLowerRiskCeiling.get(skillId);
        }
    }
}
