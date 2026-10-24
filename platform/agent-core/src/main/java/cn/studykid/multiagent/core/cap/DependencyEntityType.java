package cn.studykid.multiagent.core.cap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 影响分析目标实体类型（管理面 M7）。
 *
 * <p>⚠️ 与 {@link CapObjectType} 的分工：后者是<b>平台级</b>能力对象三型
 * （发布头 / 版本解析用，不带 Agent）；本枚举是<b>管理面查询</b>口径，
 * 多一个 {@link #AGENT}（租户级，影响分析的链末端）。
 * 版本解析一律用 {@link CapObjectType}，<b>不得</b>混用。
 */
public enum DependencyEntityType {

    /** 租户级 Agent（{@code cap_agent_def} / {@code agent}）。 */
    AGENT("agent"),

    /** 平台级 Tool（{@code cap_tool_def}）。 */
    TOOL("tool"),

    /** 平台级 Skill（{@code cap_skill_def}）。 */
    SKILL("skill"),

    /** 平台级 MCP Server（{@code cap_mcp_server_def}）。 */
    MCP_SERVER("mcp_server");

    private final String code;

    DependencyEntityType(@NonNull String code) {
        this.code = code;
    }

    public @NonNull String code() {
        return code;
    }

    /**
     * 解析查询参数。
     *
     * <p>宽容 {@code "mcp"} 别名（URL 友好）；大小写不敏感。
     *
     * @return 合法类型；无法识别时 {@code null}（控制器转 400）
     */
    public static @Nullable DependencyEntityType fromCode(@Nullable String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim();
        if (c.equalsIgnoreCase("mcp")) {
            return MCP_SERVER;
        }
        for (DependencyEntityType t : values()) {
            if (t.code.equalsIgnoreCase(c) || t.name().equalsIgnoreCase(c)) {
                return t;
            }
        }
        return null;
    }
}
