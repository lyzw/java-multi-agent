package cn.studykid.multiagent.application.cap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.jspecify.annotations.NonNull;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapResultContractDef;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;

/**
 * 配置对象的<b>内容指纹</b>（P6R4 · 管理端点写入口）。
 *
 * <p>⚠️ <b>为什么写入口必须自己算，而不是让调用方给</b>：
 * {@code config_fingerprint} 是「这一行内容没变」的证明（§6.1）。
 * 若让调用方（HTTP 客户端）提供这个值，它就成了一个<b>可以随便填的字符串</b> ——
 * 客户端填 A、实际内容是 B，闭包指纹仍会自洽（闭包指纹就是把这一行里的 fingerprint
 * 一起哈希的），于是「内容变了但指纹没变」这件事就再也检测不出来了。
 * 故本类只在<b>服务端</b>按内容的规范形式计算指纹。
 *
 * <p>⚠️ <b>与 {@link ClosureFingerprint} 的关系</b>：
 * 闭包指纹是「一组对象的聚合摘要」（§6.4），
 * 内容指纹是「单个版本行的摘要」。闭包指纹把内容指纹当作输入之一，
 * 所以内容指纹一旦不可信，闭包指纹的失效检测也就跟着不可信。
 *
 * <p>⚠️ <b>不包含时间戳</b>：{@code createdAt} / {@code publishedAt} 不参与计算。
 * 理由：同一份配置在两次发布时间下必须得到**同一个**指纹 ——
 * 若时间戳参与计算，每次发布都会让指纹变化，撤回（DEPRECATED）与新发布会互相误判为
 * 「内容变了」。
 *
 * <p>⚠️ <b>不包含 {@code configFingerprint} 自身</b>：否则就是自引用，无法计算。
 */
public final class CapObjectFingerprint {

    /** 内容指纹算法版本前缀。 */
    public static final String PREFIX = "cap_obj_fp_v1:";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CapObjectFingerprint() {
    }

    /**
     * 计算 Agent 定义的内容指纹。
     *
     * @param def Agent 定义版本行
     * @return {@code cap_obj_fp_v1:} 前缀 + SHA-256 十六进制摘要（小写）
     */
    public static @NonNull String of(@NonNull CapAgentDef def) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "agent");
        root.put("id", def.agentId());
        root.put("version", def.version());
        root.put("name", def.name());
        root.put("description", def.description());
        ArrayNode roles = root.putArray("roles");
        def.roles().forEach(roles::add);
        ArrayNode enabledSkills = root.putArray("enabledSkills");
        def.enabledSkills().forEach(enabledSkills::add);
        ArrayNode skillBundles = root.putArray("skillBundles");
        def.skillBundles().forEach(skillBundles::add);
        ArrayNode capabilityTags = root.putArray("capabilityTags");
        def.capabilityTags().forEach(capabilityTags::add);
        root.put("overrides", def.overrides());
        // ⚠️ 编排声明必须入内容指纹：子 Agent 引用变了（换了目标 release、
        //    改了深度/次数上限）就是<b>行为性变化</b>，
        //    不入指纹会让「配置变了但指纹没变」——失效检测彻底失效（MA-T15）。
        root.put("orchestration", canonicalJsonFragment(def.orchestration()));
        root.put("schemaVersion", def.schemaVersion());
        return PREFIX + sha256Hex(MAPPER.writeValueAsString(root));
    }

    /**
     * 把一段 JSON 文本规范化为「键序固定」的等价文本。
     *
     * <p>⚠️ <b>不合法 JSON 不抛异常</b>：原样保留为字符串。
     * 编排声明的合法性由编排解析器判定（配置期拒），
     * 指纹层若在此处抛异常，会把「配置缺陷」变成「指纹计算失败」，
     * 掩盖真正的原因码。
     *
     * @param raw 原始 JSON 文本
     * @return 规范化后的文本；不合法时原样返回
     */
    private static @NonNull String canonicalJsonFragment(@NonNull String raw) {
        try {
            JsonNode parsed = MAPPER.readTree(raw);
            if (parsed == null || parsed.isMissingNode() || !parsed.isObject()) {
                return raw;
            }
            ObjectNode sorted = MAPPER.createObjectNode();
            parsed.properties().stream()
                    .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
                    .forEach(entry -> sorted.set(entry.getKey(), entry.getValue()));
            return MAPPER.writeValueAsString(sorted);
        } catch (RuntimeException e) {
            return raw;
        }
    }

    /**
     * 计算输出契约的内容指纹（多级 Agent v1.1 · FR-027 · MA-T16）。
     *
     * <p>⚠️ <b>为什么契约也必须由服务端算</b>：与 Agent / Tool / Skill 同理 ——
     * {@code config_fingerprint} 是「这一行内容没变」的证明；
     * 若让调用方（HTTP 客户端或内部调用点）提供这个值，
     * 它就成了一个<b>可以随便填的字符串</b> —— 客户端填 A、实际内容是 B，
     * 闭包指纹仍会自洽，于是「内容变了但指纹没变」这件事就再也检测不出来了。
     * 而契约恰恰是<b>子 Agent 输出是否合法</b>的判定依据 ——
     * 它的内容变了却指纹不变，等于「输出校验口径悄悄换了一套」。
     *
     * <p>⚠️ <b>不包含时间戳</b>：{@code publishedAt} 不参与计算，
     * 理由与其他对象一致（同一份契约在草稿与发布态必须得到同一个指纹）。
     */
    public static @NonNull String of(@NonNull CapResultContractDef def) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "result_contract");
        root.put("id", def.contractId());
        root.put("version", def.version());
        // ⚠️ Schema 文本必须**规范化**后入哈希：同一份 Schema 的键序不同
        //    不应被读成「契约变了」—— 那会让每次重新登记都变成一次内容变化。
        root.put("schemaJson", canonicalJsonFragment(def.schemaJson()));
        root.put("schemaVersion", def.schemaVersion());
        return PREFIX + sha256Hex(MAPPER.writeValueAsString(root));
    }

    /**
     * 计算 Tool 定义的内容指纹。
     *
     * @param def Tool 定义版本行
     * @return 内容指纹
     */
    public static @NonNull String of(@NonNull CapToolDef def) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "tool");
        root.put("id", def.id());
        root.put("version", def.version());
        root.put("name", def.name());
        root.put("kind", def.kind().name());
        root.put("riskLevel", def.riskLevel().name());
        root.put("mcpServerId", def.mcpServerId());
        root.put("mcpToolName", def.mcpToolName());
        root.put("inputSchema", def.inputSchema());
        root.put("paramPolicies", def.paramPolicies());
        root.put("schemaVersion", def.schemaVersion());
        return PREFIX + sha256Hex(MAPPER.writeValueAsString(root));
    }

    /**
     * 计算 Skill 定义的内容指纹。
     *
     * @param def Skill 定义版本行
     * @return 内容指纹
     */
    public static @NonNull String of(@NonNull CapSkillDef def) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "skill");
        root.put("id", def.id());
        root.put("version", def.version());
        root.put("name", def.name());
        root.put("description", def.description());
        ArrayNode declaredTools = root.putArray("declaredTools");
        def.declaredTools().forEach(declaredTools::add);
        ArrayNode declaredMcp = root.putArray("declaredMcp");
        for (CapSkillDef.McpDeclaration declaration : def.declaredMcp()) {
            ObjectNode node = declaredMcp.addObject();
            node.put("serverId", declaration.serverId());
            ArrayNode allowlist = node.putArray("toolAllowlist");
            if (declaration.toolAllowlist() != null) {
                declaration.toolAllowlist().forEach(allowlist::add);
            }
        }
        root.put("riskCeiling", def.riskCeiling().name());
        root.put("paramPolicies", def.paramPolicies());
        ArrayNode requiredCaps = root.putArray("requiredCaps");
        def.requiredCaps().forEach(requiredCaps::add);
        root.put("schemaVersion", def.schemaVersion());
        return PREFIX + sha256Hex(MAPPER.writeValueAsString(root));
    }

    /**
     * 计算 MCP Server 定义的内容指纹。
     *
     * <p>⚠️ <b>凭据定位符绝不参与计算</b>：{@code credentialRef} 是凭据库定位符，
     * 参与计算会让凭据轮换把所有在途 Run 集体失效，并把定位符泄漏进指纹
     * （同 {@link ClosureFingerprint} 的硬约束）。
     *
     * @param def MCP Server 定义版本行
     * @return 内容指纹
     */
    public static @NonNull String of(@NonNull CapMcpServerDef def) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "mcp_server");
        root.put("id", def.id());
        root.put("version", def.version());
        root.put("transport", def.transport().name());
        root.put("endpoint", def.endpoint());
        root.put("command", def.command());
        root.put("authKind", def.authKind().name());
        root.put("trustTier", def.trustTier().name());
        if (def.exposedToolAllowlist() == null) {
            root.putNull("exposedToolAllowlist");
        } else {
            ArrayNode allowlist = root.putArray("exposedToolAllowlist");
            def.exposedToolAllowlist().forEach(allowlist::add);
        }
        root.put("connectionRisk", def.connectionRisk().name());
        root.put("dynamicDiscovery", def.dynamicDiscovery());
        root.put("schemaVersion", def.schemaVersion());
        return PREFIX + sha256Hex(MAPPER.writeValueAsString(root));
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
}
