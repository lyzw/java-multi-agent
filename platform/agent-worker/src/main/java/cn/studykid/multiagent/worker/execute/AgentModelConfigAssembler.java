package cn.studykid.multiagent.worker.execute;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.cap.ClosureResolver;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.SkillDescription;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;

/**
 * 按<b>固定版本</b>装配 Agent 模型配置（多级 Agent v1.1 · 第二十一批 · FR-036）。
 *
 * <p>⚠️ <b>存在的唯一理由：消除「根路径与子路径两套语义」</b>。
 * 此前子 Agent 的模型配置由 {@code WorkerChildAgentModelConfigProvider} 装配，
 * 根 Agent 走的却是<b>硬编码占位</b>（{@code fake-model} + 写死的提示词）——
 * 于是同一个平台里，根与子对「模型是谁、提示词是什么、工具表长什么样」
 * 给出两套完全不同的答案。
 *
 * <p>⚠️ <b>本组件是根与子共用的唯一定义</b>：
 * 两处各写一份必然漂移，而漂移的后果是
 * 「根按占位配置跑、子按真实配置跑」——
 * 那正是本项目反复消除的「父子两套语义」本身。
 *
 * <p>⚠️ <b>绑定固定版本</b>：读取按调用方给定的 {@code agentVersion} 精确取那一版，
 * <b>不解析「当前已发布版本」</b> ——
 * 否则别人给该 Agent 发布新版本时，在途 Run 会静默漂移到新模型/新提示词（MA-T08）。
 *
 * <p>⚠️ <b>取不到模型标识即拒绝</b>：无法确定用哪个模型时返回空，
 * 由调用方按「确定失败」处理 —— 绝不随便挑一个默认模型。
 */
public class AgentModelConfigAssembler {

    private static final Logger log = LoggerFactory.getLogger(
            AgentModelConfigAssembler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentQueryPort agentQuery;
    private final ClosureResolver closureResolver;
    private final CapToolDefPort toolDefs;

    public AgentModelConfigAssembler(@NonNull AgentQueryPort agentQuery,
                                     @NonNull ClosureResolver closureResolver,
                                     @NonNull CapToolDefPort toolDefs) {
        this.agentQuery = agentQuery;
        this.closureResolver = closureResolver;
        this.toolDefs = toolDefs;
    }

    /**
     * 装配指定 Agent 固定版本的模型配置。
     *
     * @param tenantId     租户标识
     * @param agentId      Agent 标识
     * @param agentVersion 该 Agent 的<b>固定版本号</b>
     * @return 模型配置；该版本不可用时返回 {@link Optional#empty()}
     */
    public @NonNull Optional<AgentModelSettings> assemble(@NonNull String tenantId,
                                                           @NonNull String agentId,
                                                           int agentVersion) {
        Optional<String> definition = definitionOf(tenantId, agentId, agentVersion);
        if (definition.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> root;
        try {
            Map<String, Object> parsed = MAPPER.readValue(definition.get(),
                    new TypeReference<Map<String, Object>>() {
                    });
            root = parsed == null ? Map.of() : parsed;
        } catch (JacksonException e) {
            log.warn("Agent 定义解析失败: agent={}, version={}", agentId, agentVersion, e);
            return Optional.empty();
        }

        String modelId = text(root.get("modelRef"), root.get("modelId"));
        if (modelId == null) {
            // ⚠️ 无法确定模型 → 拒绝执行（不猜）
            log.warn("Agent 定义缺少模型标识: agent={}, version={}", agentId, agentVersion);
            return Optional.empty();
        }

        // ⚠️ 闭包只解析一次，同时供给「工具 Schema（1级）」与「Skill 指引（2级）」：
        //    两处各解析一次必然漂移（一边能读到描述、另一边读不到）——那正是「两套语义」。
        EffectiveClosure closure = resolveClosure(tenantId, agentId, agentVersion);

        return Optional.of(new AgentModelSettings(
                modelId,
                skillGuidance(systemPrompt(root), closure),
                number(root.get("temperature"), 0.2),
                (int) number(root.get("maxOutputTokens"), 1024),
                Duration.ofMillis((long) number(root.get("timeoutMs"), 30_000)),
                toolSchemas(closure)));
    }

    /**
     * 解析该 Agent 固定版本的闭包；失败时返回 {@code null}（不抛）。
     *
     * <p>⚠️ <b>闭包解析失败 → 工具表为空、指引为空</b>（不抛）：
     * 工具表为空只是「模型看不到那些工具」，闸门侧照常按授权判定；
     * 但让整个 Agent 因闭包不可读而完全不可用，
     * 是把「一条工具边读不出来」放大成「这个 Agent 全瘫」。
     */
    private @Nullable EffectiveClosure resolveClosure(@NonNull String tenantId,
                                                      @NonNull String agentId,
                                                      int agentVersion) {
        try {
            return closureResolver.resolve(tenantId, agentId, agentVersion);
        } catch (RuntimeException e) {
            log.warn("Agent 闭包解析失败，工具表为空: agent={}, version={}",
                    agentId, agentVersion, e);
            return null;
        }
    }

    /**
     * 追加 Skill 两级描述中的 <b>2级 usage 指引</b>（执行期注入）。
     *
     * <p>⚠️ <b>1级不用这里注入</b>：purpose 是「选型信号」，已并入工具 Schema 的
     * description（模型从工具列表挑工具时首先看到）—— 两级消费者不同。
     *
     * <p>⚠️ <b>only usage</b>：只有写了分场景指引（{@code when → how}）的 Skill 才成节；
     * 没有 2级内容的 Skill（存量版本行）提示词<b>一字不改</b>（向后兼容）。
     *
     * <p>⚠️ <b>指引不是授权</b>：提示词里明示「描述不是授权」，
     * 描述与声明冲突时以声明为准（fail-closed）；指引只是行为引导。
     */
    private static @NonNull String skillGuidance(@NonNull String basePrompt,
                                                 @Nullable EffectiveClosure closure) {
        if (closure == null) {
            return basePrompt;
        }
        StringBuilder section = new StringBuilder();
        for (EffectiveClosure.SkillBinding binding : closure.skills()) {
            SkillDescription description = binding.description();
            if (description == null || description.usage().isEmpty()) {
                continue;
            }
            if (section.isEmpty()) {
                section.append("【技能使用指引】（描述不是授权：仅引导已授权工具的使用方式，")
                        .append("未命中任何适用场景时不要强行调用）");
            }
            section.append("\n- 技能「").append(description.purpose()).append("」：");
            for (SkillDescription.SkillUsage usage : description.usage()) {
                section.append("\n  · 当 ").append(usage.when())
                        .append("：").append(usage.how());
            }
        }
        if (section.isEmpty()) {
            return basePrompt;
        }
        String base = basePrompt == null ? "" : basePrompt.trim();
        return base.isBlank() ? section.toString() : base + "\n\n" + section;
    }

    /**
     * 装配该 Agent <b>自己闭包</b>里的工具 Schema。
     *
     * <p>⚠️ <b>1级 purpose 是 Tool 的模型可见选型信号</b>：
     * 工具备自多个 Skill（同一 Tool 可被多个 Skill 声明）时，
     * 取<b>第一个</b>声明它的、写了两级描述的 Skill 的 purpose —— 闭包顺序即口径，
     * 否则每次取值随机漂移。全都没有时回退<b>工具名</b>（旧行为）。
     */
    private @NonNull Map<String, Object> toolSchemas(@Nullable EffectiveClosure closure) {
        Map<String, Object> schemas = new LinkedHashMap<>();
        if (closure == null) {
            return schemas;
        }
        Map<String, String> purposeByTool = purposesOf(closure);
        for (EffectiveClosure.ToolBinding binding : closure.tools()) {
            toolDefs.find(binding.toolId(), binding.version()).ifPresent(tool -> {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("name", tool.id());
                schema.put("description", purposeByTool.getOrDefault(
                        tool.id(), tool.name() == null ? tool.id() : tool.name()));
                schema.put("inputSchema", parseSchema(tool.inputSchema()));
                schemas.put(tool.id(), schema);
            });
        }
        return schemas;
    }

    /**
     * Tool id → 所属 Skill 的 1级 purpose（取<b>第一个</b>声明该 Tool 且有描述的 Skill）。
     */
    private static @NonNull Map<String, String> purposesOf(@NonNull EffectiveClosure closure) {
        Map<String, String> out = new LinkedHashMap<>();
        for (EffectiveClosure.SkillBinding binding : closure.skills()) {
            SkillDescription description = binding.description();
            if (description == null) {
                continue;
            }
            for (String toolId : binding.declaredTools()) {
                out.putIfAbsent(toolId, description.purpose());
            }
        }
        return out;
    }

    /**
     * 读取指定<b>版本</b>的 Agent 定义文本。
     */
    private @NonNull Optional<String> definitionOf(@NonNull String tenantId,
                                                   @NonNull String agentId,
                                                   int agentVersion) {
        try {
            return agentQuery.listVersions(tenantId, agentId).stream()
                    .filter(version -> version.versionNumber() == agentVersion)
                    .map(AgentQueryPort.AgentVersionItem::definition)
                    .findFirst();
        } catch (RuntimeException e) {
            log.warn("Agent 版本读取失败: agent={}, version={}", agentId, agentVersion, e);
            return Optional.empty();
        }
    }

    /**
     * 解析系统提示：兼容契约形态（{@code instructions.inlineInstructions}）
     * 与存量扁平形态（{@code systemPrompt}）。
     */
    private static @NonNull String systemPrompt(@NonNull Map<String, Object> root) {
        Object instructions = root.get("instructions");
        if (instructions instanceof Map<?, ?> map) {
            Object inline = map.get("inlineInstructions");
            if (inline instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        String flat = text(root.get("systemPrompt"));
        return flat == null ? "" : flat;
    }

    private static @NonNull Object parseSchema(@NonNull String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (JacksonException e) {
            // ⚠️ Schema 解析不了 → 退回空对象（工具侧会拒绝参数），不静默放行
            return Map.of();
        }
    }

    private static @Nullable String text(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return null;
    }

    private static double number(Object raw, double defaultValue) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Agent 模型配置（与「由谁执行」无关的纯配置）。
     *
     * @param modelId      模型标识
     * @param systemPrompt 系统提示
     * @param temperature  采样温度
     * @param maxTokens    最大输出 token
     * @param timeout      单次模型调用超时
     * @param toolSchemas  工具 Schema（按该 Agent <b>自己</b>的闭包）
     */
    public record AgentModelSettings(
            @NonNull String modelId,
            @NonNull String systemPrompt,
            double temperature,
            int maxTokens,
            @NonNull Duration timeout,
            @NonNull Map<String, Object> toolSchemas) {

        public AgentModelSettings {
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("modelId 不能为空");
            }
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            toolSchemas = toolSchemas == null ? Map.of() : Map.copyOf(toolSchemas);
        }
    }
}
