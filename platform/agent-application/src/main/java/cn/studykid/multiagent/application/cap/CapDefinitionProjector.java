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
            @NonNull String instructions,
            @Nullable String modelRef,
            @NonNull String generationParams,
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
            instructions = instructions == null || instructions.isBlank()
                    ? "{}" : instructions;
            generationParams = generationParams == null || generationParams.isBlank()
                    ? "{}" : generationParams;
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

        List<String> roles;
        List<String> enabledSkills;
        List<String> skillBundles;
        List<String> capabilityTags;
        try {
            roles = stringList(root.get("roles"), "roles");
            enabledSkills = stringList(root.get("enabledSkills"), "enabledSkills");
            if (enabledSkills.isEmpty()) {
                enabledSkills = stringList(root.get("enabled_skills"), "enabled_skills");
            }
            skillBundles = stringList(root.get("skillBundles"), "skillBundles");
            capabilityTags = stringList(root.get("capabilityTags"), "capabilityTags");
        } catch (ProjectionException e) {
            // ⚠️ 元素形态解析不出 → **拒绝投影**，不降级（fail-closed）。
            //    降级等于把「声明了但解析不出」当成「没声明」——
            //    那样产出的 Agent 看起来发布成功、实际零能力。
            return Projection.rejected(e.getMessage());
        }

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

        // ⚠️ 提示词 / 模型 / 生成参数（2026-11-09 · 权威源定 A）：
        //    这三项是**运行期配置真源**的一部分，投影时必须一并带走 ——
        //    丢掉它们等于「旧定义投影成新定义后，Agent 变成零配置」。
        //    形态：{@code instructions} 对象 / {@code modelRef} 字符串 /
        //    {@code generationParams} 对象；缺失即缺省（未声明）。
        String instructions = "{}";
        Object rawInstructions = root.get("instructions");
        if (rawInstructions != null) {
            if (!(rawInstructions instanceof Map)) {
                return Projection.rejected("instructions 必须是对象");
            }
            try {
                instructions = MAPPER.writeValueAsString(rawInstructions);
            } catch (JacksonException e) {
                return Projection.rejected("instructions 序列化失败: " + e.getMessage());
            }
        }
        String modelRef = textOrNull(root.get("modelRef"));
        if (modelRef == null) {
            modelRef = textOrNull(root.get("modelId"));
        }
        String generationParams = "{}";
        Object rawGeneration = root.get("generationParams");
        if (rawGeneration != null) {
            if (!(rawGeneration instanceof Map)) {
                return Projection.rejected("generationParams 必须是对象");
            }
            try {
                generationParams = MAPPER.writeValueAsString(rawGeneration);
            } catch (JacksonException e) {
                return Projection.rejected("generationParams 序列化失败: " + e.getMessage());
            }
        }
        // ⚠️ 兼容存量扁平形态：旧定义的提示词/模型曾以顶层键存放
        //    （{@code systemPrompt} / {@code modelRef} / {@code temperature} …）。
        //    扁平形态<b>不是</b>契约形态，但存量数据就是那样写的 ——
        //    直接丢掉等于让存量 Agent 集体失去配置。
        if ("{}".equals(instructions)) {
            String flatPrompt = textOrNull(root.get("systemPrompt"));
            if (flatPrompt != null) {
                try {
                    instructions = MAPPER.writeValueAsString(
                            Map.of("inlineInstructions", flatPrompt));
                } catch (JacksonException e) {
                    return Projection.rejected("systemPrompt 序列化失败: " + e.getMessage());
                }
            }
        }
        if ("{}".equals(generationParams)) {
            Map<String, Object> flat = new java.util.LinkedHashMap<>();
            copyNumber(root, flat, "temperature");
            copyNumber(root, flat, "maxOutputTokens");
            copyNumber(root, flat, "timeoutMs");
            if (!flat.isEmpty()) {
                try {
                    generationParams = MAPPER.writeValueAsString(flat);
                } catch (JacksonException e) {
                    return Projection.rejected("生成参数序列化失败: " + e.getMessage());
                }
            }
        }

        String fingerprint = ProvisionalFingerprint.of(List.of(
                ProvisionalFingerprint.canonicalList(roles),
                ProvisionalFingerprint.canonicalList(enabledSkills),
                ProvisionalFingerprint.canonicalList(skillBundles),
                ProvisionalFingerprint.canonicalList(capabilityTags),
                overrides,
                orchestration,
                instructions,
                modelRef == null ? "" : modelRef,
                generationParams,
                name == null ? "" : name,
                description == null ? "" : description));

        return Projection.ok(new CapAgentDefView(
                name, description, roles, enabledSkills, skillBundles, capabilityTags,
                overrides, orchestration, instructions, modelRef, generationParams, fingerprint));
    }

    /**
     * 从自由 JSON 里抄一个数值项到扁平参数表（只抄数值型，不抄字符串）。
     *
     * <p>⚠️ 只抄数值：字符串型参数（如 "1.0"）多半是别的字段的误写，
     * 抄进来会让「生成参数」凭空多出一个语义不明的键。
     */
    private static void copyNumber(Map<String, Object> root, Map<String, Object> target,
                                   String key) {
        Object value = root.get(key);
        if (value instanceof Number number) {
            target.put(key, number);
        }
    }

    @Nullable
    private static String textOrNull(Object value) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    /**
     * 读一个字符串列表项。
     *
     * <p>⚠️ <b>兼容契约的 {@code $defs.ref} 形态</b>（2026-11-09）：
     * 契约要求 {@code enabledSkills} 的元素是 {@code {"id": ..., "version": ...}} 对象，
     * 而历史数据用的是裸字符串。两种都接受。
     *
     * <p>⚠️ <b>解析不出即报错，绝不静默丢弃</b>：
     * 此前实现只收集 {@code instanceof String} 的元素，其余<b>一律丢弃</b> ——
     * 于是按契约书写的一组 {@code {"id": ...}} 会被投影成<b>空集</b>：
     * 一个「看起来发布成功、实际零能力」的 Agent。
     * 静默丢弃 = 把「声明了但解析不出」当成「没声明」，
     * 与本仓 fail-closed 纪律直接矛盾。
     *
     * @param value 原始值（列表 / 单值）
     * @param field 字段名（用于报错定位）
     * @return 解析出的字符串列表
     * @throws ProjectionException 元素形态无法解析
     */
    @NonNull
    private static List<String> stringList(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String s) {
            return s.isBlank() ? List.of() : List.of(s.trim());
        }
        if (!(value instanceof List<?> list)) {
            throw new ProjectionException(field + " 必须是字符串或字符串数组");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
                continue;
            }
            if (item instanceof Map<?, ?> map) {
                // ⚠️ 契约形态：{"id": "...", "version": "..."} —— 取 id。
                Object id = map.get("id");
                if (!(id instanceof String sid) || sid.isBlank()) {
                    throw new ProjectionException(
                            field + " 的对象元素缺少非空 id 字段: " + map);
                }
                out.add(sid.trim());
                continue;
            }
            throw new ProjectionException(
                    field + " 的元素只能是字符串或 {id,version} 对象，实际: " + item);
        }
        return out;
    }

    /**
     * 投影期解析失败（<b>拒绝投影</b>，不降级）。
     */
    static final class ProjectionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ProjectionException(String message) {
            super(message);
        }
    }
}
