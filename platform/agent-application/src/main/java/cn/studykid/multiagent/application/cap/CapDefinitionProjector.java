package cn.studykid.multiagent.application.cap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.application.orchestration.AgentOrchestrationParser;
import cn.studykid.multiagent.application.orchestration.OrchestrationConfigException;

/**
 * 把<b>自由 JSONB</b> 的 {@code agent_version.definition} 投影为
 * <b>结构化</b>的 {@code cap_agent_def}（06 §1.4 / §3.3 迁移期双写）。
 *
 * <p><b>为什么需要投影而不是直接拷贝</b>：
 * 存量 {@code agent_version.definition} 是<b>自由 schema</b> 的 JSONB
 * （现网样例：{@code {"kind":"tutor"}}），而 {@code cap_agent_def} 是
 * <b>结构化</b>的（enabledSkills / roles / overrides …）。
 * 两者不是同构的，迁移期必须有一层<b>确定性</b>的映射，
 * 否则同一份存量数据每次映射结果可能不同，双写比对工具将无法判定一致性。
 *
 * <p><b>映射规则（V1）</b>：
 * <table border="1">
 *   <caption>字段映射</caption>
 *   <tr><th>cap_agent_def</th><th>definition 来源键</th><th>缺失时</th></tr>
 *   <tr><td>name</td><td>{@code name}</td><td>沿用 agent.name</td></tr>
 *   <tr><td>description</td><td>{@code description}</td><td>沿用 agent.description</td></tr>
 *   <tr><td>roles</td><td>{@code roles}</td><td>[]</td></tr>
 *   <tr><td>enabled_skills</td><td>{@code enabledSkills} / {@code enabled_skills}</td><td>[]</td></tr>
 *   <tr><td>skill_bundles</td><td>{@code skillBundles}</td><td>[]（V1 忽略语义）</td></tr>
 *   <tr><td>capability_tags</td><td>{@code capabilityTags}</td><td>[]（V1 忽略语义）</td></tr>
 *   <tr><td>overrides</td><td>{@code overrides}</td><td>{}</td></tr>
 * </table>
 *
 * <p>⚠️ <b>{@code inherits} 触发拒绝而非降级</b>（04 §3 / N1）：
 * 存量定义若带 {@code inherits}（嵌套 Skill），V1 <b>写入即拒</b>——
 * 嵌套闭包易抬权（02 P3）。
 * 这里<b>不能</b>「丢掉 inherits 字段照常投影」：
 * 那会让两套表示<b>语义不一致</b>却看起来迁移成功，
 * 后续读路径切到 {@code cap_agent_def} 时能力闭包会与实际授权面不符。
 * 正确处置是<b>跳过并登记</b>（06 §3.3 第 2 步的「迁移报告」）。
 */
public final class CapDefinitionProjector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 结果：要么投影成功，要么被拒绝（附拒绝原因）。 */
    public record Projection(@NonNull Optional<CapAgentDefView> definition,
                              @Nullable String rejectionReason) {

        public static @NonNull Projection ok(@NonNull CapAgentDefView view) {
            return new Projection(Optional.of(view), null);
        }

        public static @NonNull Projection rejected(@NonNull String reason) {
            return new Projection(Optional.empty(), reason);
        }

        public boolean isRejected() {
            return definition.isEmpty();
        }
    }

    /**
     * 投影结果的视图（尚未落库）。
     *
     * @param name              名称
     * @param description       描述
     * @param roles             角色范围
     * @param enabledSkills     显式启用的 Skill
     * @param skillBundles      预留（V1 忽略语义）
     * @param capabilityTags    预留（V1 忽略语义）
     * @param overrides         收紧覆盖
     * @param configFingerprint 临时内容指纹
     */
    public record CapAgentDefView(
            @Nullable String name,
            @Nullable String description,
            @NonNull List<String> roles,
            @NonNull List<String> enabledSkills,
            @NonNull List<String> skillBundles,
            @NonNull List<String> capabilityTags,
            @NonNull String overrides,
            @NonNull String orchestration,
            @NonNull String configFingerprint
    ) {
        public CapAgentDefView {
            roles = roles == null ? List.of() : List.copyOf(roles);
            enabledSkills = enabledSkills == null ? List.of() : List.copyOf(enabledSkills);
            skillBundles = skillBundles == null ? List.of() : List.copyOf(skillBundles);
            capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
            overrides = overrides == null ? "{}" : overrides;
            // ⚠️ 缺省 LEAF（MA-T01）：缺字段 ≠ 不限制，而是「不允许委派」。
            orchestration = orchestration == null || orchestration.isBlank()
                    ? "{}" : orchestration;
        }
    }

    private CapDefinitionProjector() {
    }

    /**
     * 投影一份自由 JSON 定义。
     *
     * @param definitionText  {@code agent_version.definition} 的 JSON 文本
     * @param fallbackName    存量 definition 无 name 时回退的 Agent 名称
     * @param fallbackDescription 存量 definition 无 description 时回退的描述
     * @return 投影结果；被拒绝时含拒绝原因
     */
    public static @NonNull Projection project(@Nullable String definitionText,
                                              @Nullable String fallbackName,
                                              @Nullable String fallbackDescription) {
        Map<String, Object> root;
        if (definitionText == null || definitionText.isBlank()) {
            root = Map.of();
        } else {
            try {
                Map<String, Object> parsed = MAPPER.readValue(
                        definitionText, new TypeReference<Map<String, Object>>() {
                        });
                root = parsed == null ? Map.of() : parsed;
            } catch (JacksonException e) {
                // ⚠️ 无法解析 → 拒绝并登记（06 §3.3 第 2 步）。
                //    不能当成空对象照常投影：那样会把「解析失败」伪装成
                //    「这个 Agent 没有配置任何 Skill」，读路径切换后授权面被无声清空。
                return Projection.rejected("definition 非法 JSON: " + e.getMessage());
            }
        }

        if (root.containsKey("inherits")) {
            return Projection.rejected(
                    "V1 拒绝嵌套 Skill（inherits），需人工处理（04 §3 / N1）");
        }

        String name = textOrNull(root.get("name"));
        if (name == null) {
            name = fallbackName;
        }
        String description = textOrNull(root.get("description"));
        if (description == null) {
            description = fallbackDescription;
        }

        List<String> roles = stringList(root.get("roles"));
        List<String> enabledSkills = stringList(root.get("enabledSkills"));
        if (enabledSkills.isEmpty()) {
            enabledSkills = stringList(root.get("enabled_skills"));
        }
        List<String> skillBundles = stringList(root.get("skillBundles"));
        List<String> capabilityTags = stringList(root.get("capabilityTags"));

        String overrides = "{}";
        Object rawOverrides = root.get("overrides");
        if (rawOverrides instanceof Map) {
            try {
                overrides = MAPPER.writeValueAsString(rawOverrides);
            } catch (JacksonException e) {
                return Projection.rejected("overrides 序列化失败: " + e.getMessage());
            }
        }

        // ⚠️ 编排声明（多级 Agent v1.1）：缺失 → LEAF（MA-T01）；存在但非法 → 拒绝投影。
        //    绝不能「丢掉 orchestration 字段照常投影」—— 那会让两套表示语义不一致
        //    却看起来迁移成功，读路径切到结构化真源后委派能力被无声清空。
        String orchestration = "{}";
        Object rawOrchestration = root.get("orchestration");
        if (rawOrchestration != null) {
            if (!(rawOrchestration instanceof Map)) {
                return Projection.rejected("orchestration 必须是对象");
            }
            try {
                orchestration = MAPPER.writeValueAsString(rawOrchestration);
            } catch (JacksonException e) {
                return Projection.rejected("orchestration 序列化失败: " + e.getMessage());
            }
            try {
                // 复用编排解析器的校验口径：非法编排在配置期就拒，不进库。
                AgentOrchestrationParser.parse(orchestration);
            } catch (OrchestrationConfigException e) {
                return Projection.rejected("orchestration 非法: " + e.getMessage());
            }
        }

        String fingerprint = ProvisionalFingerprint.of(List.of(
                ProvisionalFingerprint.canonicalList(roles),
                ProvisionalFingerprint.canonicalList(enabledSkills),
                ProvisionalFingerprint.canonicalList(skillBundles),
                ProvisionalFingerprint.canonicalList(capabilityTags),
                overrides,
                orchestration,
                name == null ? "" : name,
                description == null ? "" : description));

        return Projection.ok(new CapAgentDefView(
                name, description, roles, enabledSkills, skillBundles, capabilityTags,
                overrides, orchestration, fingerprint));
    }

    @Nullable
    private static String textOrNull(Object value) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    @NonNull
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }
}
