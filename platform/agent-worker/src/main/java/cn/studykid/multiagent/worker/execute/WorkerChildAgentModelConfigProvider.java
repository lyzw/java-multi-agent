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
import cn.studykid.multiagent.core.orchestration.DelegationToolSchema;
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
    /**
     * 委派工具 Schema 装配器。
     *
     * ⚠️ <b>可为空</b>：未装配时<b>不装配委派工具</b>（见
     * {@link #toolSchemas}）—— 读不到编排声明就不知道有没有委派能力，
     * 此时<b>宁可不给</b>，也不凭猜测给一把委派枪。
     */
    private final @Nullable DelegationToolSchemaAssembler delegationSchemas;

    public WorkerChildAgentModelConfigProvider(@NonNull AgentQueryPort agentQuery,
                                               @NonNull ClosureResolver closureResolver,
                                               @NonNull CapToolDefPort toolDefs) {
        this(agentQuery, closureResolver, toolDefs, null);
    }

    /**
     * 最完整构造器（第二十批 · 增加委派工具装配）。
     *
     * @param agentQuery   Agent 版本查询（读模型配置定义）
     * @param closureResolver 闭包解析（读工具绑定）
     * @param toolDefs     工具定义仓储（读工具 Schema）
     * @param delegationSchemas 委派工具 Schema 装配器；
     *                     {@code null} = 不装配委派工具
     */
    public WorkerChildAgentModelConfigProvider(@NonNull AgentQueryPort agentQuery,
                                               @NonNull ClosureResolver closureResolver,
                                               @NonNull CapToolDefPort toolDefs,
                                               @Nullable DelegationToolSchemaAssembler
                                                       delegationSchemas) {
        this.agentQuery = agentQuery;
        this.closureResolver = closureResolver;
        this.toolDefs = toolDefs;
        this.delegationSchemas = delegationSchemas;
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
     *
     * <p>⚠️ <b>委派工具必须显式装配（第二十批）</b>：
     * 委派<b>不是</b>普通工具 —— 它<b>不进</b> Skill→Tool 能力闭包
     * （进了闭包就等于给每个 Agent 都发一把委派枪），
     * 因此闭包解析<b>永远给不出</b>它。
     * 修复前委派工具<b>从未进入任何模型的工具表</b> →
     * 运行期模型<b>物理上不可能</b>发起委派：
     * 守卫、预算、账本、契约全都齐备，却<b>没有触发点</b>。
     *
     * <p>⚠️ <b>只给「有委派能力」的 Agent</b>：编排声明缺省即 {@code LEAF}
     * （MA-T01），LEAF 或无子引用 → <b>不装配</b>。
     * 给没有委派能力的 Agent 发委派工具，等于让模型反复尝试必然被拒的调用。
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
        // ⚠️ 委派工具与闭包工具<b>并列</b>：它不属于闭包，但同样出现在模型可见的工具表里。
        schemas.putAll(delegationToolSchema(tenantId, agentId, agentVersion));
        return schemas;
    }

    /**
     * 装配委派工具 Schema（<b>按该 Agent 自己的编排声明</b>）。
     *
     * <p>⚠️ <b>实现委托给共用装配器</b>：根路径与子路径<b>共用同一份</b> ——
     * 两处各写一份必然漂移，而漂移的后果是
     * 「根能委派、子不能」（或反之）—— 那正是「两套语义」本身。
     */
    private @NonNull Map<String, Object> delegationToolSchema(@NonNull String tenantId,
                                                               @NonNull String agentId,
                                                               int agentVersion) {
        if (delegationSchemas == null) {
            // ⚠️ 未装配委派工具装配器 → 无从判断委派能力 → 不给委派工具。
            log.warn("未装配委派工具 Schema 装配器，子 Agent 不装配委派工具: agent={}", agentId);
            return Map.of();
        }
        return delegationSchemas.assemble(tenantId, agentId, agentVersion);
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
