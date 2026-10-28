package cn.studykid.multiagent.core.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * 父侧声明的<b>路由契约</b>（10 §3.1 · S1 语义基线）。
 *
 * <p><b>它补的是「路由目标只有 id」这个洞</b>：委派拓扑此前只到
 * {@code agentId@version}，Router 既不知道子能做什么、也不知道什么该派给它，
 * 于是「按语义选目标」只能靠模型猜 —— 猜的结果不可复现，也不可在发布期校验。
 * 契约把「这个子在我这个父的路由语境里扮演什么」写成<b>结构化声明</b>，
 * 让规则引擎有字段可比、让语义引擎有说明可读、让决策快照有版本可引。
 *
 * <p>⚠️ <b>权威源在父侧</b>（§3.2）：同一个子在不同 Router 下职责可以不同，
 * 且父的发布闭包必须自洽 —— 不能因为子重新发布就让父的路由行为漂移。
 * 子的定义只提供<b>默认底稿</b>，父可以 {@link Source#OVERRIDE}。
 *
 * <p>⚠️ <b>上限即语义</b>（§3.4）：这些字段要进 prompt，
 * 超限的代价不是「不好看」而是<b>注意力被稀释后路由质量下降</b>，
 * 所以在构造期就拒掉，而不是等运行期表现变差再回头查。
 *
 * @param intent        一句话职责说明（≤{@value #MAX_INTENT_LENGTH} 字符，进 prompt）
 * @param useWhen       「什么情况该派给它」（≤{@value #MAX_SCENARIO_ITEMS} 条，
 *                      每条 ≤{@value #MAX_SCENARIO_ITEM_LENGTH} 字符）
 * @param avoidWhen     「什么情况别派给它」（同上上限；与 useWhen 分开是因为
 *                      「不适用」比「适用」更难让模型推断）
 * @param capabilities  能力标签（≤{@value #MAX_CAPABILITY_ITEMS} 项，
 *                      词表见 {@link ContractSchemaKeywords#isValidCapability}）
 * @param inputContract 派给这个子时 input 的结构约束（顶层关键字口径与
 *                      子输出契约<b>同一份白名单</b>）
 * @param priority      {@code SEMANTIC} 模式的稳定决胜键（越小越优先；
 *                      有它才不会「同分时结果取决于遍历顺序」）
 * @param source        契约来源：继承子的底稿还是父覆盖
 */
public record RoutingContract(
        @NonNull String intent,
        @NonNull List<String> useWhen,
        @NonNull List<String> avoidWhen,
        @NonNull List<String> capabilities,
        @NonNull Map<String, Object> inputContract,
        int priority,
        @NonNull Source source
) {

    /** {@code intent} 长度上限（进 prompt，§3.4）。 */
    public static final int MAX_INTENT_LENGTH = 200;

    /** 场景条目（useWhen / avoidWhen）条数上限。 */
    public static final int MAX_SCENARIO_ITEMS = 8;

    /** 单条场景条目长度上限。 */
    public static final int MAX_SCENARIO_ITEM_LENGTH = 200;

    /** 能力标签项数上限。 */
    public static final int MAX_CAPABILITY_ITEMS = 32;

    /** {@code priority} 下限（0 = 最高优先）。 */
    public static final int MIN_PRIORITY = 0;

    /** 契约来源（§3.1）。 */
    public enum Source {
        /** 继承子 Agent 的默认底稿。 */
        INHERITED,
        /** 父在本路由语境下覆盖子的底稿。 */
        OVERRIDE
    }

    public RoutingContract {
        if (intent == null || intent.isBlank()) {
            throw new IllegalArgumentException("路由契约必须声明 intent（子在本父语境下做什么）");
        }
        if (intent.length() > MAX_INTENT_LENGTH) {
            throw new IllegalArgumentException("intent 超过 " + MAX_INTENT_LENGTH
                    + " 字符（进 prompt 的字段，过长会稀释注意力）: " + intent.length());
        }
        useWhen = boundedItems(useWhen, "useWhen");
        avoidWhen = boundedItems(avoidWhen, "avoidWhen");
        capabilities = boundedCapabilities(capabilities);
        if (inputContract == null) {
            // ⚠️ 空 Map 是合法声明（「对 input 结构不加约束」），null 不是：
            //    null 会让「没写」与「写了但为空」在指纹里塌成同一个值。
            throw new IllegalArgumentException("inputContract 不能为 null（不加约束请给空对象）");
        }
        List<String> unsupported = ContractSchemaKeywords.unsupported(inputContract.keySet());
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException(
                    "inputContract 包含不支持的关键字: " + unsupported);
        }
        inputContract = Map.copyOf(inputContract);
        if (priority < MIN_PRIORITY) {
            throw new IllegalArgumentException("priority 不得为负数，收到: " + priority);
        }
        if (source == null) {
            throw new IllegalArgumentException("必须声明契约来源（INHERITED / OVERRIDE）");
        }
    }

    /**
     * @return 是否至少声明了一项可路由的信息（intent 之外的场景/能力）
     */
    public boolean hasRoutingHints() {
        return !useWhen.isEmpty() || !avoidWhen.isEmpty() || !capabilities.isEmpty();
    }

    /**
     * 场景列表的上界校验（条数 + 单条长度 + 非空条目）。
     */
    private static @NonNull List<String> boundedItems(@NonNull List<String> items,
                                                       @NonNull String field) {
        List<String> copy = List.copyOf(items == null ? List.of() : items);
        if (copy.size() > MAX_SCENARIO_ITEMS) {
            throw new IllegalArgumentException(field + " 超过 " + MAX_SCENARIO_ITEMS
                    + " 条: " + copy.size());
        }
        List<String> checked = new ArrayList<>(copy.size());
        for (String item : copy) {
            if (item == null || item.isBlank()) {
                throw new IllegalArgumentException(field + " 不得含空白条目");
            }
            if (item.length() > MAX_SCENARIO_ITEM_LENGTH) {
                throw new IllegalArgumentException(field + " 单条超过 "
                        + MAX_SCENARIO_ITEM_LENGTH + " 字符: " + item.length());
            }
            checked.add(item);
        }
        return List.copyOf(checked);
    }

    /** 能力标签的上界与词表校验（词表不可控 = 规则引擎的字段对齐失去意义）。 */
    private static @NonNull List<String> boundedCapabilities(@NonNull List<String> capabilities) {
        List<String> copy = List.copyOf(capabilities == null ? List.of() : capabilities);
        if (copy.size() > MAX_CAPABILITY_ITEMS) {
            throw new IllegalArgumentException("capabilities 超过 " + MAX_CAPABILITY_ITEMS
                    + " 项: " + copy.size());
        }
        for (String capability : copy) {
            if (capability == null || !ContractSchemaKeywords.isValidCapability(capability)) {
                throw new IllegalArgumentException("能力标签不符合 ^[a-z][a-z0-9._-]{1,63}$: "
                        + capability);
            }
        }
        return copy;
    }
}
