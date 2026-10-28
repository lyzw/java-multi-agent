package cn.studykid.multiagent.core.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 委派工具的 Schema 定义（多级 Agent v1.1 · 第二十批 · FR-035）。
 *
 * <p>⚠️ <b>本类存在的唯一理由：让模型「看得见」委派能力</b>。
 * 修复前委派工具只出现在闸门分支里，**从未进入任何模型的工具表** ——
 * 于是运行期模型<b>物理上不可能</b>发起委派：
 * 多级 Agent 的守卫、预算、账本、契约全都齐备，却<b>没有触发点</b>。
 *
 * <p>⚠️ <b>委派不是普通工具</b>：它<b>不进</b> Skill→Tool 能力闭包
 * （进了闭包就等于给每个 Agent 都发一把委派枪），
 * 因此闭包解析<b>永远给不出</b>它 —— 必须<b>显式装配</b>。
 *
 * <p>⚠️ <b>参数口径必须与闸门严格一致</b>：
 * 本 Schema 声明 {@code additionalProperties:false} 且只允许
 * {@code targetAgentId} + {@code input} 两个字段，
 * 与 {@code DelegationArguments.parse} 的口径逐字对齐 ——
 * 否则模型按 Schema 合法发出的参数会被闸门判成「多传字段」而拒绝，
 * 那就是<b>协议两套语义</b>：模型永远学不会正确调用。
 *
 * <p>⚠️ <b>目标必须枚举</b>：{@code targetAgentId} 的取值限定在
 * <b>该 Agent 自己声明的子 Agent</b> 内（{@code childAgentRefs}）。
 * 理由有二：
 * <ul>
 *   <li><b>可用性</b>：模型不必猜标识 —— 猜错会被守卫拒（越界试探），
 *       白白浪费一轮模型调用；</li>
 *   <li><b>边界</b>：Schema 里不出现未声明的目标，
 *       就不给模型「试探越界目标」的机会 ——
 *       但这<b>不是</b>授权：枚举只是<b>提示</b>，
 *       真正的授权判定在闸门（{@code DelegationGuard}），
 *       两者都成立才算合法（<b>委派关系 ≠ 授权关系</b>）。</li>
 * </ul>
 *
 * <p><b>富候选</b>（10 号设计 §4 · S1）：只有裸 id 的 {@code enum} 等于让模型
 * <b>凭标识符的字面意思猜目标</b> —— 猜错要付一轮调用的钱，猜对了也只是运气。
 * 故 {@code render()} 额外给出 {@code candidates}：每个候选带父侧声明的
 * 职责说明、适用/不适用场景与能力标签（来自 {@link RoutingContract}）。
 * ⚠️ {@code enum} 与 {@code candidates} <b>同源同序</b>（都由 {@link #candidates()}
 * 的同一份列表派生）—— 出现「enum 里有但 candidates 里没有」的目标，
 * 就等于给了模型一个它无从判断该不该用的取值。
 *
 * <p>⚠️ <b>候选的可见性随路由 mode 收窄</b>（§4.1）：
 * {@code RULES_ONLY} 下模型<b>无权选目标</b>（它只产出 {@code input}），
 * 因此那种形态的 Schema <b>不得</b>携带 {@code candidates}。
 * 该门控依赖路由策略的 mode 字段，随 S2/S3 的策略模型一并落地；
 * 在此之前三种 mode 都还不存在，候选一律可见不构成回归。
 *
 * <p>⚠️ <b>版本不在<b>入参</b>里</b>：{@code targetAgentId} 的取值只有标识，
 * 目标版本由服务端按父声明解析 —— 模型若能指定版本，就能要求漂移到别的 release
 * （与「固定 release」直接冲突）。候选里<b>可以</b>展示版本：
 * 那是父已固定、模型改不了的<b>说明</b>，不是可提交的字段。
 *
 * @param candidates 该 Agent <b>自己</b>声明的子 Agent 候选（可为空，顺序 = 父声明顺序）
 */
public record DelegationToolSchema(@NonNull List<Candidate> candidates) {

    /** 委派工具名（与闸门分支、委派执行器同名）。 */
    public static final String TOOL_NAME = "delegate_to_agent";

    /** 委派参数：目标 Agent 标识。 */
    public static final String PARAM_TARGET = "targetAgentId";

    /** 委派参数：委派输入（最小必要上下文）。 */
    public static final String PARAM_INPUT = "input";

    /** 候选列表里的说明字段（与 {@link RoutingContract#intent()} 同源）。 */
    public static final String CANDIDATE_DESCRIPTION = "description";

    public DelegationToolSchema {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /**
     * 一个委派候选：模型看得见「这个目标是谁、在做什么」。
     *
     * @param agentId        子 Agent 标识（与 {@code enum} 里的取值同一个）
     * @param releaseVersion 父已固定的目标版本（<b>说明</b>，不是入参）
     * @param contract       父侧声明的路由契约；{@code null} = 该子<b>没有</b>契约，
     *                       此时候选只给出 id 与版本（<b>不编造</b>说明）
     */
    public record Candidate(
            @NonNull String agentId,
            int releaseVersion,
            @Nullable RoutingContract contract) {

        public Candidate {
            if (agentId == null || agentId.isBlank()) {
                throw new IllegalArgumentException("委派候选的标识不能为空");
            }
        }
    }

    /**
     * @return 允许委派的目标标识列表（{@code enum} 的取值）——
     *         与 {@link #candidates()} <b>同源同序</b>，不是第二份数据
     */
    public @NonNull List<String> allowedTargets() {
        List<String> targets = new ArrayList<>(candidates.size());
        candidates.forEach(candidate -> targets.add(candidate.agentId()));
        return List.copyOf(targets);
    }

    /**
     * 按编排声明装配委派工具 Schema。
     *
     * <p>⚠️ <b>只有「有委派能力」的 Agent 才拿得到</b>：
     * 编排声明缺省即 {@code LEAF}（MA-T01），
     * 而 {@code LEAF} 或无子引用 → <b>不装配</b>。
     * 给没有委派能力的 Agent 发委派工具，等于让模型反复尝试必然被拒的调用。
     *
     * @param orchestration 该 Agent <b>自己</b>的编排声明
     * @return 委派工具 Schema；该 Agent 无委派能力时返回空
     */
    public static @NonNull Optional<DelegationToolSchema> forOrchestration(
            @Nullable AgentOrchestration orchestration) {
        if (orchestration == null || orchestration.isLeaf() || !orchestration.hasChildren()) {
            // ⚠️ 缺省 LEAF = 无委派能力（MA-T01），不是「不限制」。
            return Optional.empty();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (ChildAgentRef ref : orchestration.childAgentRefs()) {
            candidates.add(new Candidate(ref.agentId(), ref.releaseVersion(),
                    ref.routingContract()));
        }
        return Optional.of(new DelegationToolSchema(candidates));
    }

    /**
     * 渲染成模型可见的工具 Schema（{@code name} / {@code description} /
     * {@code inputSchema} / {@code candidates}）。
     *
     * <p>⚠️ <b>形态与普通工具一致</b>：模型网关按同一约定消费
     * （{@code WorkerChildAgentModelConfigProvider} 的普通工具也是这三个键），
     * 形态不一致会让网关把委派工具当成「没有参数定义的工具」。
     *
     * @return 工具 Schema（键为工具名）
     */
    public @NonNull Map<String, Object> render() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", TOOL_NAME);
        schema.put("description",
                "把任务委派给已声明的子 Agent 执行（目标必须取自 candidates；"
                        + "input 必须符合 inputSchema）。");
        schema.put("inputSchema", inputSchema());
        schema.put("candidates", candidateNodes());
        return Map.of(TOOL_NAME, schema);
    }

    /**
     * 渲染参数 Schema（JSON Schema 子集）。
     *
     * <p>⚠️ <b>{@code additionalProperties:false}</b>：
     * 多传字段必须被模型自己看见「不被允许」——
     * 否则模型按 Schema 发出多余字段，闸门却拒收，那是两套语义。
     */
    private @NonNull Map<String, Object> inputSchema() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "string");
        target.put("description", "目标子 Agent 标识（必须取自 candidates）");
        target.put("enum", allowedTargets());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "object");
        input.put("description", "委派输入（最小必要上下文）");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PARAM_TARGET, target);
        properties.put(PARAM_INPUT, input);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        // ⚠️ {@code input} 与 {@code targetAgentId} 同为必填（10 号文档 §8 · G-4）：
        //    闸门已不再把缺失的 input 兜底成空 Map，Schema 就必须同步说清「必须给」。
        //    两边不一致的话，模型会按 Schema 省略 input、闸门却拒收 ——
        //    「两套语义」正是本 Schema 上方注释要防的那类缺陷。
        schema.put("required", List.of(PARAM_TARGET, PARAM_INPUT));
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 渲染候选列表：<b>逐项从 {@link #candidates()} 派生</b>，
     * 与 {@code enum} 同序（一致性由「同一份列表」保证，而不是靠两处对齐）。
     *
     * <p>⚠️ <b>无契约的候选不编造说明</b>：只给 id 与版本。
     * 拿 Agent 名字里的只言片语拼一段「看起来像职责」的文案，
     * 会让模型把它当成父的正式声明来依赖。
     */
    private @NonNull List<Map<String, Object>> candidateNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", candidate.agentId());
            node.put("version", candidate.releaseVersion());
            RoutingContract contract = candidate.contract();
            if (contract != null) {
                node.put(CANDIDATE_DESCRIPTION, contract.intent());
                node.put("useWhen", contract.useWhen());
                node.put("avoidWhen", contract.avoidWhen());
                node.put("capabilities", contract.capabilities());
                node.put("priority", contract.priority());
            }
            nodes.add(Map.copyOf(node));
        }
        return List.copyOf(nodes);
    }
}
