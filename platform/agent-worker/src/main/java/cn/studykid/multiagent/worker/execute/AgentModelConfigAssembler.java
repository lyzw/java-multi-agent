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

        return Optional.of(new AgentModelSettings(
                modelId,
                systemPrompt(root),
                number(root.get("temperature"), 0.2),
                (int) number(root.get("maxOutputTokens"), 1024),
                Duration.ofMillis((long) number(root.get("timeoutMs"), 30_000)),
                toolSchemas(tenantId, agentId, agentVersion)));
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
     * 装配该 Agent <b>自己闭包</b>里的工具 Schema。
     *
     * <p>⚠️ <b>闭包解析失败 → 工具表为空</b>（不抛）：
     * 工具表为空只是「模型看不到那些工具」，闸门侧照常按授权判定；
     * 但让整个 Agent 因工具表不可读而完全不可用，
     * 是把「一条工具边读不出来」放大成「这个 Agent 全瘫」。
     */
    private @NonNull Map<String, Object> toolSchemas(@NonNull String tenantId,
                                                      @NonNull String agentId,
                                                      int agentVersion) {
        Map<String, Object> schemas = new LinkedHashMap<>();
        EffectiveClosure closure;
        try {
            closure = closureResolver.resolve(tenantId, agentId, agentVersion);
        } catch (RuntimeException e) {
            log.warn("Agent 闭包解析失败，工具表为空: agent={}, version={}",
                    agentId, agentVersion, e);
            return schemas;
        }
        for (EffectiveClosure.ToolBinding binding : closure.tools()) {
            toolDefs.find(binding.toolId(), binding.version()).ifPresent(tool -> {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("name", tool.id());
                schema.put("description", tool.name() == null ? tool.id() : tool.name());
                schema.put("inputSchema", parseSchema(tool.inputSchema()));
                schemas.put(tool.id(), schema);
            });
        }
        return schemas;
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
