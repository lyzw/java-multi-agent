package cn.studykid.multiagent.core.cap;

import org.jspecify.annotations.NonNull;

/**
 * 发布头中的一条对象版本引用（{@code object_versions[]} 的一项 · §6.2 / 06 §1.7）。
 *
 * <p>格式固定为 {@code {"type":"...","id":"...","version":N}} ——
 * §6.2 规定对象定义之间**只存逻辑 id**，有效版本统一由当前发布头解析，
 * 因此发布头必须记录 id→<b>精确版本</b> 的映射。
 *
 * <p>⚠️ <b>type 不可省略</b>：Tool / Skill / MCP Server / Agent 的 id 命名空间相互独立
 * （一个名为 {@code "profile"} 的 Skill 与一个名为 {@code "profile"} 的 Tool 可以并存）。
 * 不带 type 的解析会把「Skill 的 id」解析成「同名 Tool 的版本」——
 * 那是一条静默的越权路径。
 *
 * @param type    对象类型标识（{@link #TYPE_TOOL} 等）
 * @param id      对象逻辑 id
 * @param version 精确版本号
 */
public record ConfigObjectVersion(@NonNull String type, @NonNull String id, int version) {

    /** Tool 类型标识。 */
    public static final String TYPE_TOOL = "tool";

    /** Skill 类型标识。 */
    public static final String TYPE_SKILL = "skill";

    /** MCP Server 类型标识。 */
    public static final String TYPE_MCP_SERVER = "mcp_server";

    /** Agent 类型标识（租户级对象）。 */
    public static final String TYPE_AGENT = "agent";

    /**
     * 输出契约类型标识（租户级对象 · 多级 Agent v1.1）。
     *
     * <p>⚠️ <b>必须独立成一个 type</b>：契约与 Agent 的 id 命名空间相互独立
     * （一个名为 {@code "profile"} 的契约与一个名为 {@code "profile"} 的 Agent 可以并存）。
     * 沿用 {@code TYPE_AGENT} 会让「Agent 的 id」解析成「同名契约的版本」——
     * 与 {@code TYPE_TOOL} 同构的静默越权路径。
     */
    public static final String TYPE_RESULT_CONTRACT = "result_contract";

    /**
     * 路由策略类型标识（租户级对象 · 多级 Agent v1.1 第十四批）。
     *
     * <p>⚠️ <b>必须独立成一个 type</b>：路由策略与 Agent / 输出契约的 id 命名空间
     * 相互独立（一个名为 {@code "profile"} 的策略与一个名为 {@code "profile"} 的 Agent
     * 可以并存）。沿用 {@code TYPE_AGENT} 会让「Agent 的 id」解析成
     * 「同名策略的版本」—— 与 {@code TYPE_TOOL} 同构的静默越权路径。
     */
    public static final String TYPE_ROUTING_POLICY = "routing_policy";

    public ConfigObjectVersion {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type 不能为空");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version 必须为正数，收到: " + version);
        }
    }

    /**
     * @param id      对象逻辑 id
     * @param version 版本号
     * @return Tool 引用
     */
    public static @NonNull ConfigObjectVersion tool(@NonNull String id, int version) {
        return new ConfigObjectVersion(TYPE_TOOL, id, version);
    }

    /**
     * @param id      对象逻辑 id
     * @param version 版本号
     * @return Skill 引用
     */
    public static @NonNull ConfigObjectVersion skill(@NonNull String id, int version) {
        return new ConfigObjectVersion(TYPE_SKILL, id, version);
    }

    /**
     * @param id      对象逻辑 id
     * @param version 版本号
     * @return MCP Server 引用
     */
    public static @NonNull ConfigObjectVersion mcpServer(@NonNull String id, int version) {
        return new ConfigObjectVersion(TYPE_MCP_SERVER, id, version);
    }

    /**
     * @param id      对象逻辑 id
     * @param version 版本号
     * @return Agent 引用
     */
    public static @NonNull ConfigObjectVersion agent(@NonNull String id, int version) {
        return new ConfigObjectVersion(TYPE_AGENT, id, version);
    }

    /**
     * @param id      对象逻辑 id
     * @param version 版本号
     * @return 输出契约引用
     */
    public static @NonNull ConfigObjectVersion resultContract(@NonNull String id, int version) {
        return new ConfigObjectVersion(TYPE_RESULT_CONTRACT, id, version);
    }

    /**
     * @param id      对象逻辑 id
     * @param version 版本号
     * @return 路由策略引用
     */
    public static @NonNull ConfigObjectVersion routingPolicy(@NonNull String id, int version) {
        return new ConfigObjectVersion(TYPE_ROUTING_POLICY, id, version);
    }
}
