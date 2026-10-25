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
 * <p>⚠️ <b>版本不在 Schema 里</b>：目标版本由服务端按父声明解析，
 * 模型<b>不得</b>指定版本 —— 否则模型就能要求漂移到别的 release
 * （与「固定 release」直接冲突）。
 *
 * @param allowedTargets 该 Agent <b>自己</b>声明的子 Agent 标识（可为空）
 */
public record DelegationToolSchema(@NonNull List<String> allowedTargets) {

    /** 委派工具名（与闸门分支、委派执行器同名）。 */
    public static final String TOOL_NAME = "delegate_to_agent";

    /** 委派参数：目标 Agent 标识。 */
    public static final String PARAM_TARGET = "targetAgentId";

    /** 委派参数：委派输入（最小必要上下文）。 */
    public static final String PARAM_INPUT = "input";

    public DelegationToolSchema {
        allowedTargets = allowedTargets == null ? List.of() : List.copyOf(allowedTargets);
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
        List<String> targets = new ArrayList<>();
        for (ChildAgentRef ref : orchestration.childAgentRefs()) {
            targets.add(ref.agentId());
        }
        return Optional.of(new DelegationToolSchema(targets));
    }

    /**
     * 渲染成模型可见的工具 Schema（{@code name} / {@code description} / {@code inputSchema}）。
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
                "把任务委派给已声明的子 Agent 执行（目标必须取自允许列表）。");
        schema.put("inputSchema", inputSchema());
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
        target.put("description", "目标子 Agent 标识（必须取自允许列表）");
        target.put("enum", List.copyOf(allowedTargets));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "object");
        input.put("description", "委派输入（最小必要上下文）");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PARAM_TARGET, target);
        properties.put(PARAM_INPUT, input);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(PARAM_TARGET));
        schema.put("additionalProperties", false);
        return schema;
    }
}
