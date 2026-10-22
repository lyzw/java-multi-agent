package cn.studykid.multiagent.core.cap;

import org.jspecify.annotations.NonNull;

/**
 * 平台级能力对象的类型（§6.2「对象定义引用：仅逻辑 id」）。
 *
 * <p>⚠️ <b>必须带类型</b>：Tool / Skill / MCP Server 的 id 命名空间<b>相互独立</b>
 * （一个名为 {@code "profile"} 的 Skill 与一个名为 {@code "profile"} 的 Tool 可以并存）。
 * 版本解析若不带类型，会把「Skill 的 id」解析成「同名 Tool 的版本」——
 * 这是一条静默的越权路径（拿不到声明关系的对象被当成已声明）。
 */
public enum CapObjectType {

    /** Tool（{@code cap_tool_def}）。 */
    TOOL("tool"),

    /** Skill（{@code cap_skill_def}）。 */
    SKILL("skill"),

    /** MCP Server（{@code cap_mcp_server_def}）。 */
    MCP_SERVER("mcp_server");

    private final String code;

    CapObjectType(@NonNull String code) {
        this.code = code;
    }

    /**
     * @return 发布头 {@code object_versions[]} 中使用的类型标识
     */
    public @NonNull String code() {
        return code;
    }
}
