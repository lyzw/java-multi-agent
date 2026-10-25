package cn.studykid.multiagent.application.cap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.EffectiveClosure;

/**
 * 闭包指纹（§6.4）。
 *
 * <p>§6.4 规定指纹内容：「本 Run 依赖的 Agent/Skill/Tool/MCP 的 {@code (id,version)}、
 * riskCeiling、allowlist、paramPolicies、MCP 固定清单、ForcedSafety 策略版本摘要等；
 * 规范化后 SHA-256」。
 *
 * <p><b>三条硬约束</b>：
 * <ol>
 *   <li><b>无关对象变更不触发本 Run 失效</b>：指纹只覆盖闭包内的对象。
 *       因此<b>绝不能</b>把全局发布序号（{@code configVersion}）混进指纹 ——
 *       §7.3 明确「不得将『仅全局序号变、闭包未变』自动视为授权失败」。</li>
 *   <li><b>凭据材料永不入指纹</b>：MCP Server 的 {@code credentialRef} 绝不参与计算。
 *       否则凭据轮换会让所有在途 Run 集体失效，且会把凭据库定位符泄漏进指纹。</li>
 *   <li><b>确定性</b>：同一闭包必须得到同一摘要。故先规范化（键序固定、集合按字典序）再哈希。</li>
 * </ol>
 *
 * <p>⚠️ <b>与 P0 占位指纹物理隔离</b>：本算法输出 {@code cap_fp_v1:} 前缀，
 * 与 P0 的 {@code pending_v1:} 占位值不共用前缀 ——
 * 存量占位值一眼可辨，不会被误当成可用于失效判定的真实指纹。
 */
public final class ClosureFingerprint {

    /** 闭包指纹算法版本前缀。 */
    public static final String PREFIX = "cap_fp_v1:";

    /**
     * ForcedSafety 策略版本摘要（§6.4 要求的「ForcedSafety 策略版本摘要」）。
     *
     * <p>⚠️ ForcedSafety 是<b>逐操作安全谓词</b>（riskCeiling / trustTier / endpoint 等），
     * 不是集合（§4.4），故无法逐项入指纹 —— 改为把<b>策略本身的版本</b>入指纹：
     * 策略语义变更时版本号随之变化，所有在途 Run 一次性失效并重算，
     * 而不是「策略变了但指纹没变」导致旧判定被沿用。
     */
    public static final String FORCED_SAFETY_POLICY_VERSION = "forced_safety_v1";

    /**
     * 编排绑定算法版本（v1.1 · MA-T15 / MA-T18）。
     *
     * <p>⚠️ <b>为什么编排声明入指纹是一次「显式版本化」的变更</b>：
     * 本常量入哈希，意味着「编排声明参与指纹」这件事本身被固化下来。
     * 它带来一个<b>必须知悉的副作用</b>：本次变更后，
     * <b>变更前创建的在途 Run 快照指纹一律与新算出的指纹不一致</b>
     * → 运行期落到 {@code CONFIG_FINGERPRINT_STALE}，Run 被拒。
     *
     * <p>⚠️ <b>这是预期行为，不是回归</b>：
     * 旧指纹<b>没有</b>覆盖编排声明，那份快照的「内容没变」证明是<b>不完整</b>的 ——
     * 继续放行等于让「配置变了但指纹说没变」继续成立（MA-T15 的核心命题）。
     * fail-closed 优先于「让在途 Run 跑完」。
     */
    public static final String ORCHESTRATION_BINDING_VERSION = "orchestration_binding_v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClosureFingerprint() {
    }

    /**
     * 计算闭包指纹。
     *
     * @param closure 有效能力闭包
     * @return {@code cap_fp_v1:} 前缀 + SHA-256 十六进制摘要（小写）
     */
    public static @NonNull String of(@NonNull EffectiveClosure closure) {
        return PREFIX + sha256Hex(canonicalJson(closure));
    }

    /**
     * 生成闭包的<b>规范化 JSON</b>。
     *
     * <p>该文本同时用于两处：
     * <ul>
     *   <li>作为 {@code execution_capability_snapshot.effective_closure} 的存储形态；</li>
     *   <li>作为指纹的哈希输入。</li>
     * </ul>
     * 二者同源，才能保证「存下来的闭包」与「算出来的指纹」永远一致。
     *
     * @param closure 有效能力闭包
     * @return 规范化 JSON 文本（键序固定、集合按字典序）
     */
    public static @NonNull String canonicalJson(@NonNull EffectiveClosure closure) {
        EffectiveClosure canonical = closure.canonicalized();
        ObjectNode root = MAPPER.createObjectNode();

        ObjectNode agent = root.putObject("agent");
        agent.put("id", canonical.agentId());
        agent.put("version", canonical.agentVersion());

        ArrayNode skills = root.putArray("skills");
        for (EffectiveClosure.SkillBinding skill : canonical.skills()) {
            ObjectNode node = skills.addObject();
            node.put("id", skill.skillId());
            node.put("version", skill.version());
            node.put("riskCeiling", skill.riskCeiling().name());
            node.put("fingerprint", skill.configFingerprint());
            node.put("paramPolicies", canonicalJsonFragment(skill.paramPolicies()));
            ArrayNode declared = node.putArray("declaredTools");
            skill.declaredTools().forEach(declared::add);
        }

        ArrayNode tools = root.putArray("tools");
        for (EffectiveClosure.ToolBinding tool : canonical.tools()) {
            ObjectNode node = tools.addObject();
            node.put("id", tool.toolId());
            node.put("version", tool.version());
            node.put("kind", tool.kind().name());
            node.put("riskLevel", tool.riskLevel().name());
            node.put("fingerprint", tool.configFingerprint());
            node.put("paramPolicies", canonicalJsonFragment(tool.paramPolicies()));
            if (tool.mcpServerId() != null) {
                node.put("mcpServerId", tool.mcpServerId());
            }
            if (tool.mcpToolName() != null) {
                node.put("mcpToolName", tool.mcpToolName());
            }
        }

        ArrayNode mcpServers = root.putArray("mcpServers");
        for (EffectiveClosure.McpBinding binding : canonical.mcpServers()) {
            ObjectNode node = mcpServers.addObject();
            node.put("serverId", binding.serverId());
            node.put("version", binding.version());
            node.put("skillId", binding.skillId());
            node.put("trustTier", binding.trustTier().name());
            node.put("connectionRisk", binding.connectionRisk().name());
            ArrayNode manifest = node.putArray("manifestOps");
            binding.manifestOps().forEach(manifest::add);
            ArrayNode allowed = node.putArray("allowedOps");
            binding.allowedOps().forEach(allowed::add);
        }

        // ⚠️ 多级 Agent 的后代维度（v1.1）：子 Agent 边及其<b>自身闭包指纹</b>入哈希。
        //    子 Agent 的闭包指纹是递归的 —— 它已覆盖其 Skill/Tool/MCP 与更深层后代，
        //    所以这里只需把「直接子代的闭包指纹」列进去，就能让
        //    「任意后代的任意行为性变化」传导到本闭包指纹（MA-T15）。
        //    ⚠️ 不并入子 Agent 的工具清单：委派不是授权，
        //    父 Agent 的工具面不能因「能委派给 X」而扩大。
        ArrayNode childAgents = root.putArray("childAgents");
        for (EffectiveClosure.ChildAgentBinding child : canonical.childAgents()) {
            ObjectNode node = childAgents.addObject();
            node.put("id", child.agentId());
            node.put("version", child.releaseVersion());
            node.put("fingerprint", child.configFingerprint());
            node.put("closureFingerprint", child.closureFingerprint());
            node.put("mode", child.mode().name());
            node.put("depth", child.depth());
        }

        root.put("forcedSafetyPolicyVersion", FORCED_SAFETY_POLICY_VERSION);

        // ⚠️ 多级 Agent 的编排声明（v1.1 · MA-T15 / MA-T18）：
        //    编排声明决定「委派给谁 / 按什么 Schema 校验子输出 / 能跑多久多久深」，
        //    是**行为性**内容 —— 不入指纹就会出现「指纹说没变，行为却变了」：
        //    在途 Run 不失效、绑定了旧指纹的审批被继续复用。
        //    ⚠️ 空值必须写成显式 null（putNull），不能省略字段 ——
        //    省略会让「没有该字段」与「该字段为空」两种闭包算出同一个哈希。
        ObjectNode orchestration = root.putObject("orchestration");
        orchestration.put("mode", canonical.orchestration().mode().name());
        putNullable(orchestration, "routingPolicyRef", canonical.orchestration().routingPolicyRef());
        putNullable(orchestration, "resultContractRef", canonical.orchestration().resultContractRef());
        orchestration.put("maxDelegationDepth", canonical.orchestration().maxDelegationDepth());
        orchestration.put("maxChildInvocations", canonical.orchestration().maxChildInvocations());
        orchestration.put("timeoutMs", canonical.orchestration().timeoutMs());
        orchestration.put("failurePolicy", canonical.orchestration().failurePolicy().name());
        root.put("orchestrationBindingVersion", ORCHESTRATION_BINDING_VERSION);
        return MAPPER.writeValueAsString(root);
    }

    /**
     * 写入可空字段：{@code null} 写成显式 {@code null}，而不是省略字段。
     *
     * <p>⚠️ <b>为什么不能省略</b>：省略会让
     * 「根本没有这个字段的闭包」与「这个字段为空的闭包」产出同一个哈希，
     * 于是「引用被撤掉了」与「引用一直是空的」无法区分 ——
     * 而这两者对「这次委派到底校验什么」的影响完全不同。
     */
    private static void putNullable(@NonNull ObjectNode node,
                                    @NonNull String field,
                                    @Nullable String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /**
     * 把一段 JSON 文本规范化为「键序固定」的等价文本。
     *
     * <p>⚠️ <b>不合法 JSON 不抛异常</b>：原样保留为字符串。
     * 参数策略是<b>配置内容</b>，其合法性由参数策略引擎判定（§4.3「未知 op → 配置期拒」），
     * 指纹层若在此处抛异常，会把「配置缺陷」变成「指纹计算失败」，
     * 掩盖真正的原因码（PARAM_POLICY_FAIL）。
     *
     * @param raw 原始 JSON 文本
     * @return 规范化后的文本；不合法时原样返回
     */
    private static @NonNull String canonicalJsonFragment(@NonNull String raw) {
        try {
            JsonNode parsed = MAPPER.readTree(raw);
            if (parsed == null || parsed.isMissingNode()) {
                return raw;
            }
            return MAPPER.writeValueAsString(canonicalize(parsed));
        } catch (RuntimeException e) {
            return raw;
        }
    }

    /**
     * 递归规范化 JSON 节点：对象键按字典序重排；<b>顶层数组按元素规范文本排序</b>。
     *
     * <p>⚠️ <b>为什么要给顶层数组排序</b>：参数策略的语义是「同参数多策略取更严交集」，
     * <b>与书写顺序无关</b>（§4.3）；而 PostgreSQL 的 {@code jsonb} <b>保留数组原序</b>，
     * 同一组策略换个顺序写进库里就是另一个值 —— 若指纹对顺序敏感，
     * 「内容没变、只是顺序变了」会被误判成内容变化，让在途 Run 无故失效。
     *
     * <p>⚠️ <b>只排顶层</b>：策略值内部的数组（如 {@code in_allowlist} 的取值表）
     * 不排序 —— 保持原样，避免对未知取值形态做过度推断。
     */
    private static @NonNull JsonNode canonicalize(@NonNull JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            // ⚠️ Jackson 3 用 properties()（Map.Entry 集合），不再有 propertyNames()
            node.properties().stream()
                    .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
                    .forEach(entry -> sorted.set(entry.getKey(), canonicalize(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = MAPPER.createArrayNode();
            List<JsonNode> children = new java.util.ArrayList<>();
            node.forEach(children::add);
            children.stream()
                    .map(ClosureFingerprint::canonicalize)
                    .sorted(java.util.Comparator.comparing(child -> MAPPER.writeValueAsString(child)))
                    .forEach(sorted::add);
            return sorted;
        }
        return node;
    }

    private static @NonNull String sha256Hex(@NonNull String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // ⚠️ SHA-256 是 JDK 强制支持的算法，走到这里说明运行环境异常。
            //    抛异常而不是返回固定值 —— 返回固定值会让所有指纹相同，
            //    「指纹相同即内容相同」的判定将永远成立，失效检测彻底失效。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * @param fingerprint 指纹文本
     * @return 是否为占位指纹（P0 的 {@code pending_v1:}）——
     *         占位值<b>不得</b>参与失效判定
     */
    public static boolean isProvisional(@Nullable String fingerprint) {
        return fingerprint != null && fingerprint.startsWith("pending_v1:");
    }
}
