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
import cn.studykid.multiagent.application.modelcatalog.ModelResolver;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.cap.SkillDescription;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;
import cn.studykid.multiagent.ports.modelcatalog.ModelScenario;
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
    /**
     * 统一模型目录解析器（可空）。
     *
     * <p>⚠️ <b>可空</b>：未注入时（如单测直接构造、或目录尚未启用）按历史语义
     * 把 Agent 定义里的 {@code modelRef} 原样当作模型名——保证向后兼容、不破坏既有测试。
     * 注入后，{@code modelRef} 视为目录模型名，经 {@link ModelResolver} 解析出真实模型标识
     * （命中则取其 {@code modelName}；目录为空/未命中且无默认则回落字面名并告警）。
     */
    private final @Nullable ModelResolver resolver;

    public AgentModelConfigAssembler(@NonNull AgentQueryPort agentQuery,
                                     @NonNull ClosureResolver closureResolver,
                                     @NonNull CapToolDefPort toolDefs) {
        this(agentQuery, closureResolver, toolDefs, null);
    }

    /**
     * 完整构造器（接入统一模型目录）。
     *
     * @param agentQuery        Agent 版本查询（读模型配置定义）
     * @param closureResolver   闭包解析（读工具绑定）
     * @param toolDefs          工具定义仓储（读工具 Schema）
     * @param resolver          统一模型目录解析器；{@code null} = 不解析、原样用 modelRef
     */
    public AgentModelConfigAssembler(@NonNull AgentQueryPort agentQuery,
                                     @NonNull ClosureResolver closureResolver,
                                     @NonNull CapToolDefPort toolDefs,
                                     @Nullable ModelResolver resolver) {
        this.agentQuery = agentQuery;
        this.closureResolver = closureResolver;
        this.toolDefs = toolDefs;
        this.resolver = resolver;
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
        // ⚠️ 统一模型目录：modelRef 视为目录模型名，解析出真实模型标识。
        //   目录未注入 / 未命中且无默认时回落字面名（向后兼容历史数据 + 不破坏测试）。
        String effectiveModelName = resolveModelName(modelId);

        // ⚠️ 闭包只解析一次，同时供给「工具 Schema（1级）」与「Skill 指引（2级）」：
        //    两处各解析一次必然漂移（一边能读到描述、另一边读不到）——那正是「两套语义」。
        EffectiveClosure closure = resolveClosure(tenantId, agentId, agentVersion);

        return Optional.of(new AgentModelSettings(
                effectiveModelName,
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
     * 追加 Skill 三级描述中的 <b>2级 usage 指引 + 3级 content 详细描述</b>（执行期注入）。
     *
     * <p>⚠️ <b>1级不用这里注入</b>：purpose 是「选型信号」，已并入工具 Schema 的
     * description（模型从工具列表挑工具时首先看到）—— 三级消费者不同。
     *
     * <p>⚠️ <b>usage + content</b>：只有写了分场景指引（{@code when → how}）
     * 或详细描述的 Skill 才成节；没有 2/3 级内容的 Skill（存量版本行）提示词<b>一字不改</b>（向后兼容）。
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
            // ⚠️ 无描述的 Skill（存量/遗留版本）跳过：提示词一字不改，向后兼容
            //     （直接 description.usage() 会在 description 为 null 时 NPE，
            //       导致整个 Agent 配置装配崩溃——legacySkillFallsBackToToolName 即覆盖此路径）
            if (description == null) {
                continue;
            }
            boolean hasUsage = !description.usage().isEmpty();
            boolean hasContent = !description.content().isBlank();
            if (!hasUsage && !hasContent) {
                continue;
            }
            if (section.isEmpty()) {
                section.append("【技能使用指引】（描述不是授权：仅引导已授权工具的使用方式，")
                        .append("未命中任何适用场景时不要强行调用）");
            }
            section.append("\n- 技能「").append(description.purpose()).append("」：");
            if (hasUsage) {
                for (SkillDescription.SkillUsage usage : description.usage()) {
                    section.append("\n  · 当 ").append(usage.when())
                            .append("：").append(usage.how());
                }
            }
            if (hasContent) {
                section.append("\n  · 详细描述：").append(description.content());
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

    /**
     * 工具输入 Schema 的文本 → 登记表值。
     *
     * <p>⚠️ <b>必须返回 {@code Map}，不能返回 {@code JsonNode}</b>
     * （2026-10-28 真实模型端到端实测后修正）：登记表的下游消费者
     * （模型网关的 {@code tools[].function.parameters}）按 {@code Map} 取值，
     * 拿到 {@code JsonNode} 会走「形态不符」分支，
     * 于是<b>每个工具的参数定义都被静默替换成空 Schema</b> ——
     * 症状是模型照提示词喊出工具名、却交不出任何参数，
     * 而现场只看到一句「缺少必填参数」。
     */
    private static @NonNull Map<String, Object> parseSchema(@NonNull String raw) {
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw,
                    new TypeReference<Map<String, Object>>() {
                    });
            return parsed == null ? Map.of() : parsed;
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

    /**
     * 把 Agent 定义里的 {@code modelRef} 解析为真实模型标识。
     *
     * <p><b>解析优先级</b>：
     * <ol>
     *   <li><b>目录 id 优先</b>：若 {@code modelRef} 形如目录主键（UUID），走
     *       {@link ModelResolver#resolveById(String)} 精确取回该记录的 {@code modelName}
     *       （Per-Agent 绑目录 id：换模型只需改目录记录或重指 id，不必硬编码模型名）；</li>
     *   <li><b>目录模型名</b>：否则视为目录模型名，经
     *       {@link ModelResolver#resolve(String, String)} 在 {@link ModelScenario#AGENT_CHAT}
     *       场景内解析（命中含场景默认回落）取 {@code modelName}；</li>
     *   <li><b>安全回落（fail-soft）</b>：目录未注入 / 该 id 或名称未命中且无默认时，
     *       回落为字面 {@code modelRef}（保留历史语义、不破坏既有数据与测试）。</li>
     * </ol>
     */
    private @NonNull String resolveModelName(@NonNull String rawModelRef) {
        if (resolver == null) {
            return rawModelRef;
        }
        // ① 目录 id（UUID）优先：Agent 直接绑定目录里的某条模型记录
        if (isCatalogId(rawModelRef)) {
            Optional<ModelDefinition> byId = resolver.resolveById(rawModelRef);
            if (byId.isPresent()) {
                return byId.get().modelName();
            }
            log.warn("Agent 模型引用 [{}] 形如 catalog id 但目录无此启用记录，回落按名称解析",
                    rawModelRef);
        }
        // ② 按名称解析（剥离 provider: 前缀）
        String ref = stripProviderPrefix(rawModelRef);
        Optional<ModelDefinition> resolved = resolver.resolve(ModelScenario.AGENT_CHAT, ref);
        if (resolved.isPresent()) {
            return resolved.get().modelName();
        }
        log.warn("Agent 模型 [{}] 未在统一目录(agent-chat)命中且无默认，回落字面模型名"
                + "（建议迁移到管理后台模型目录）", rawModelRef);
        return rawModelRef;
    }

    /** 判断字符串是否形如目录主键 UUID（Per-Agent 绑目录 id 的识别依据）。 */
    private static boolean isCatalogId(@NonNull String value) {
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 剥离可选的 {@code provider:} 前缀（与网关 {@code resolveModelName} 同口径）：
     * 目录里的 {@code modelName} 是不带供应商前缀的真实模型标识。
     */
    private static @NonNull String stripProviderPrefix(@NonNull String modelId) {
        int idx = modelId.indexOf(':');
        return idx >= 0 ? modelId.substring(idx + 1) : modelId;
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
