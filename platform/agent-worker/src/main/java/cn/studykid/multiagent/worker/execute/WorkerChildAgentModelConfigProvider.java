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
import cn.studykid.multiagent.ports.orchestration.ChildAgentModelConfigProvider;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;

/**
 * 子 Agent 模型配置装配（多级 Agent v1.1 · P2-MA-02）。
 *
 * <p>⚠️ <b>绑定固定版本</b>：委派目标在发布时已固定 release，
 * 本装配按<b>那个版本</b>的 {@code agent_version.definition} 读取模型配置 ——
 * 子 Agent 发布新版本后，在途委派不会静默漂移到新模型/新提示词（MA-T08）。
 *
 * <p>⚠️ <b>工具 Schema 来自子 Agent 自己的闭包</b>：模型能看到的工具
 * 就是它被授权的工具，不多不少（父 Agent 的工具不会出现在子 Agent 的工具表里）。
 *
 * <p>⚠️ <b>取不到模型标识即拒绝</b>：无法确定用哪个模型时返回空，
 * 由调用方按「确定失败」处理 —— 绝不随便挑一个默认模型。
 */
public class WorkerChildAgentModelConfigProvider implements ChildAgentModelConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(
            WorkerChildAgentModelConfigProvider.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentQueryPort agentQuery;
    private final ClosureResolver closureResolver;
    private final CapToolDefPort toolDefs;

    public WorkerChildAgentModelConfigProvider(@NonNull AgentQueryPort agentQuery,
                                               @NonNull ClosureResolver closureResolver,
                                               @NonNull CapToolDefPort toolDefs) {
        this.agentQuery = agentQuery;
        this.closureResolver = closureResolver;
        this.toolDefs = toolDefs;
    }

    @Override
    public @NonNull Optional<ChildModelConfig> configFor(@NonNull String tenantId,
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
            log.warn("子 Agent 定义解析失败: agent={}, version={}", agentId, agentVersion, e);
            return Optional.empty();
        }

        String modelId = text(root.get("modelRef"), root.get("modelId"));
        if (modelId == null) {
            // ⚠️ 无法确定模型 → 拒绝执行（不猜）
            log.warn("子 Agent 定义缺少模型标识: agent={}, version={}", agentId, agentVersion);
            return Optional.empty();
        }
        String systemPrompt = systemPrompt(root);
        double temperature = number(root.get("temperature"), 0.2);
        int maxTokens = (int) number(root.get("maxOutputTokens"), 1024);
        Duration timeout = Duration.ofMillis((long) number(root.get("timeoutMs"), 30_000));

        return Optional.of(new ChildModelConfig(modelId, systemPrompt, temperature,
                maxTokens, timeout, toolSchemas(tenantId, agentId, agentVersion)));
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
            log.warn("子 Agent 版本读取失败: agent={}, version={}", agentId, agentVersion, e);
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
     * 装配子 Agent 的工具 Schema（<b>按它自己的闭包</b>）。
     */
    private @NonNull Map<String, Object> toolSchemas(@NonNull String tenantId,
                                                      @NonNull String agentId,
                                                      int agentVersion) {
        Map<String, Object> schemas = new LinkedHashMap<>();
        EffectiveClosure closure;
        try {
            closure = closureResolver.resolve(tenantId, agentId, agentVersion);
        } catch (RuntimeException e) {
            log.warn("子 Agent 闭包解析失败，工具表为空: agent={}, version={}",
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
}
