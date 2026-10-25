package cn.studykid.multiagent.worker.execute;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.cap.ClosureResolver;
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
        // ⚠️⚠️ 第二十一批：**解析逻辑下沉到共用装配器**。
        // 此前本类自己解析定义（modelRef / systemPrompt / temperature / …），
        // 根路径却走硬编码占位 —— 于是同一个平台里根与子对
        // 「模型是谁、提示词是什么」给出两套答案。
        // 现在两者共用 {@link AgentModelConfigAssembler}：**只有一份定义**。
        Optional<AgentModelConfigAssembler.AgentModelSettings> settings =
                assembler().assemble(tenantId, agentId, agentVersion);
        if (settings.isEmpty()) {
            return Optional.empty();
        }
        AgentModelConfigAssembler.AgentModelSettings s = settings.get();
        return Optional.of(new ChildModelConfig(s.modelId(), s.systemPrompt(),
                s.temperature(), s.maxTokens(), s.timeout(),
                toolSchemas(tenantId, agentId, agentVersion, s.toolSchemas())));
    }

    /**
     * 共用装配器（根路径与子路径<b>同一份</b>）。
     */
    private @NonNull AgentModelConfigAssembler assembler() {
        return new AgentModelConfigAssembler(agentQuery, closureResolver, toolDefs);
    }

    /**
     * 装配该 Agent <b>自己闭包</b>里的工具 Schema（并<b>并列</b>委派工具）。
     *
     * <p>⚠️ <b>第二十一批：闭包工具表已下沉到共用装配器</b>：
     * 根路径此前走硬编码占位，子路径在这里自己实现一份 ——
     * 两份实现必然漂移（改一处漏一处 → 「根能调的工具子调不了」）。
     * 现在两者<b>共用 {@link AgentModelConfigAssembler}</b>。
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
     *
     * <p>⚠️ <b>委派工具与闭包工具并列</b>：它不属于闭包，但同样出现在模型可见的工具表里。
     */
    private @NonNull Map<String, Object> toolSchemas(@NonNull String tenantId,
                                                      @NonNull String agentId,
                                                      int agentVersion,
                                                      @NonNull Map<String, Object> closureSchemas) {
        Map<String, Object> schemas = new LinkedHashMap<>(closureSchemas);
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
}
