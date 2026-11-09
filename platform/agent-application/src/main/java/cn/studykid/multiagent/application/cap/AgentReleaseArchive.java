package cn.studykid.multiagent.application.cap;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapAgentDef;

/**
 * Agent 发布归档内容的<b>派生器</b>（2026-11-09 · 权威源定 A）。
 *
 * <p>⚠️ <b>为什么归档内容必须由真源派生</b>：
 * 权威源定为 {@code cap_agent_def} 后，旧表 {@code agent_version} 降级为
 * <b>历史归档与 run 快照桥接</b>。若归档内容仍由客户端提交的 JSON 生成，
 * 就会出现「两份配置」—— 旧表那份会被运行期以外的读方（如回放、
 * 外部报表）当成配置真相，而它与真源可能已经分叉。
 *
 * <p>⚠️ <b>派生的是「这一版 Agent 长什么样」的快照</b>，
 * 不是「一份新的配置」：归档内容与真源<b>同源同刻</b>，
 * 且真源改版时归档不会跟着改（不可变版本行）。
 *
 * <p>⚠️ <b>JSON 拼装不引第三方库</b>：本仓的领域层禁止框架依赖，
 * 而本类需要出现在应用层之外可复用的位置 ——
 * 手写转义足够（键名与值都来自受控来源），且避免了「为了拼个 JSON 引入一个依赖」。
 */
public final class AgentReleaseArchive {

    private AgentReleaseArchive() {
    }

    /**
     * 派生归档用的 {@code agent_version.definition} 文本。
     *
     * @param agent 权威定义行（{@code cap_agent_def}）
     * @return 归档 JSON 文本
     */
    @NonNull
    public static String deriveDefinition(@NonNull CapAgentDef agent) {
        StringBuilder sb = new StringBuilder(320);
        sb.append("{\"tenantId\":").append(quote(agent.tenantId()))
                .append(",\"agentId\":").append(quote(agent.agentId()))
                .append(",\"version\":").append(agent.version())
                .append(",\"name\":").append(agent.name() == null ? "null" : quote(agent.name()))
                .append(",\"description\":")
                .append(agent.description() == null ? "null" : quote(agent.description()))
                .append(",\"roles\":").append(jsonArray(agent.roles()))
                .append(",\"enabledSkills\":").append(jsonArray(agent.enabledSkills()))
                .append(",\"skillBundles\":").append(jsonArray(agent.skillBundles()))
                .append(",\"capabilityTags\":").append(jsonArray(agent.capabilityTags()))
                .append(",\"overrides\":").append(agent.overrides())
                .append(",\"orchestration\":").append(agent.orchestration())
                .append(",\"instructions\":").append(agent.instructions())
                .append(",\"modelRef\":")
                .append(agent.modelRef() == null ? "null" : quote(agent.modelRef()))
                .append(",\"generationParams\":").append(agent.generationParams())
                .append("}");
        return sb.toString();
    }

    /**
     * 派生归档用的 {@code execution_snapshot.agent_config} 文本。
     *
     * <p>⚠️ 与 {@code definition} 的差别：{@code agent_config} 是
     * 「该 release 的运行期配置快照」，取<b>运行期真正消费的那几项</b>
     * （提示词 / 模型 / 生成参数），而不是整份定义 ——
     * 后者会让这一列与 {@code definition} 完全重复，读方分不清两者差别。
     *
     * @param agent 权威定义行（{@code cap_agent_def}）
     * @return 运行期配置快照 JSON 文本
     */
    @NonNull
    public static String deriveAgentConfig(@NonNull CapAgentDef agent) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"instructions\":").append(agent.instructions())
                .append(",\"modelRef\":")
                .append(agent.modelRef() == null ? "null" : quote(agent.modelRef()))
                .append(",\"generationParams\":").append(agent.generationParams())
                .append("}");
        return sb.toString();
    }

    @NonNull
    private static String jsonArray(@NonNull List<String> values) {
        StringBuilder sb = new StringBuilder(32);
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(values.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * JSON 字符串转义（只处理必须转义的字符）。
     *
     * @param raw 原文
     * @return 转义后的 JSON 字符串字面量（含引号）
     */
    @NonNull
    private static String quote(@NonNull String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 归档内容里 {@code modelRef} 的可读形式（供诊断输出）。
     *
     * @param agent 权威定义行
     * @return 模型引用或 {@code "(未绑定)"}
     */
    @NonNull
    public static String describeModel(@NonNull CapAgentDef agent) {
        String modelRef = agent.modelRef();
        return modelRef == null || modelRef.isBlank() ? "(未绑定)" : modelRef;
    }

    /**
     * 归档内容里提示词的可读形式（供诊断输出）。
     *
     * @param agent 权威定义行
     * @return 提示词声明原文或 {@code "{}"}
     */
    @NonNull
    public static String describeInstructions(@NonNull CapAgentDef agent) {
        String instructions = agent.instructions();
        return instructions == null || instructions.isBlank() ? "{}" : instructions;
    }

    /**
     * 未绑定模型时的诊断文案。
     *
     * @param agent 权威定义行
     * @return 诊断文案
     */
    @NonNull
    public static String describeUnboundModel(@NonNull CapAgentDef agent) {
        return "Agent 未绑定模型（modelRef 为空）: agent=" + agent.agentId()
                + ", version=" + agent.version();
    }

    /**
     * 提示词未声明时的诊断文案。
     *
     * @param agent 权威定义行
     * @return 诊断文案
     */
    @NonNull
    public static String describeMissingPrompt(@NonNull CapAgentDef agent) {
        return "Agent 未声明提示词（instructions 为空对象）: agent=" + agent.agentId()
                + ", version=" + agent.version();
    }

    /**
     * 归档用的可空字符串（供其他派生场景复用）。
     *
     * @param raw 原文（可空）
     * @return JSON 字面量（null → {@code null} 字面量）
     */
    @NonNull
    public static String nullableQuote(@Nullable String raw) {
        return raw == null ? "null" : quote(raw);
    }
}
