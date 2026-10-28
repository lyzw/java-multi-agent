package cn.studykid.multiagent.core.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由契约的上界与不可变性测试（10 号设计 §3.4 · S1）。
 *
 * <p>⚠️ <b>上限即语义，不是排版建议</b>：这些字段要进 prompt，
 * 超限的代价不是「不好看」，而是<b>注意力被稀释后路由质量下降</b> ——
 * 而那只会表现为「Router 最近选得不准」，没有人会去查配置。
 * 所以超量必须在<b>构造期</b>拒掉，让配置者当场看见。
 */
class RoutingContractTest {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object");

    private static RoutingContract of(List<String> useWhen, List<String> avoidWhen,
                                      List<String> capabilities) {
        return new RoutingContract("负责解题", useWhen, avoidWhen, capabilities,
                SCHEMA, RoutingContract.MIN_PRIORITY, RoutingContract.Source.INHERITED);
    }

    @Test
    @DisplayName("✅ 合法契约：场景可为空，但 intent 与 source 必须在")
    void validContractIsAccepted() {
        RoutingContract contract = of(List.of("学生提出题目"), List.of("纯闲聊"),
                List.of("math.read"));

        assertThat(contract.hasRoutingHints()).isTrue();
        assertThat(contract.useWhen()).containsExactly("学生提出题目");
    }

    @Test
    @DisplayName("⛔ intent 缺失/空白/超长 → 拒绝")
    void intentMustBePresentAndBounded() {
        assertThatThrownBy(() -> new RoutingContract("  ", List.of(), List.of(), List.of(),
                SCHEMA, 0, RoutingContract.Source.INHERITED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intent");
        assertThatThrownBy(() -> new RoutingContract("长".repeat(RoutingContract.MAX_INTENT_LENGTH + 1),
                List.of(), List.of(), List.of(), SCHEMA, 0, RoutingContract.Source.INHERITED))
                .as("⚠️ intent 进 prompt：超长不是「啰嗦」，是稀释模型对其它信号的注意力")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(RoutingContract.MAX_INTENT_LENGTH));
    }

    @Test
    @DisplayName("⛔ 场景条目：条数、单条长度、空白项三条上界逐个生效")
    void scenarioItemsAreBounded() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= RoutingContract.MAX_SCENARIO_ITEMS; i++) {
            tooMany.add("场景" + i);
        }
        assertThatThrownBy(() -> of(tooMany, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("useWhen");

        assertThatThrownBy(() -> of(List.of("甲", "  "), List.of(), List.of()))
                .as("⚠️ 空白条目会被渲染成一行噪声，看起来像「声明过但没内容」")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空白");

        assertThatThrownBy(() -> of(List.of("长".repeat(RoutingContract.MAX_SCENARIO_ITEM_LENGTH + 1)),
                List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("useWhen");

        assertThatThrownBy(() -> of(List.of(), List.of("甲", ""), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("avoidWhen");
    }

    @Test
    @DisplayName("⛔ 能力标签必须命中词表（词汇表失控 = 规则引擎的字段对齐失去意义）")
    void capabilitiesMustMatchVocabulary() {
        assertThat(of(List.of(), List.of(), List.of("order.read", "a-b_c.d")).capabilities())
                .hasSize(2);

        for (String illegal : List.of("Math", "1st", "-lead", "带中文", "a b")) {
            assertThatThrownBy(() -> of(List.of(), List.of(), List.of(illegal)))
                    .as("⚠️ 不符合 ^[a-z][a-z0-9._-]+$ 的标签必须拒绝: %s", illegal)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("能力标签");
        }

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= RoutingContract.MAX_CAPABILITY_ITEMS; i++) {
            tooMany.add("cap." + i);
        }
        assertThatThrownBy(() -> of(List.of(), List.of(), tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capabilities");
    }

    /**
     * ⚠️ {@code null} 与「空对象」必须是两件事。
     *
     * <p>{@code Map.of()} 表达「父声明了契约、但对 input 不加约束」；
     * {@code null} 表达「没声明」—— 若允许 null，两者在指纹里塌成同一个值，
     * 「撤掉约束」与「从未约束过」就再也分不开。
     */
    @Test
    @DisplayName("⛔ inputContract 不得为 null；空对象是合法的「不加约束」")
    void inputContractDistinguishesAbsentFromEmpty() {
        assertThatThrownBy(() -> new RoutingContract("负责解题", List.of(), List.of(), List.of(),
                null, 0, RoutingContract.Source.INHERITED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");

        RoutingContract unconstrained = new RoutingContract("负责解题", List.of(), List.of(),
                List.of(), Map.of(), 0, RoutingContract.Source.INHERITED);
        assertThat(unconstrained.inputContract()).isEmpty();
    }

    @Test
    @DisplayName("⛔ inputContract 的顶层关键字必须在白名单内（不再造一套 Schema 方言）")
    void inputContractUsesTheSharedDialect() {
        assertThatThrownBy(() -> new RoutingContract("负责解题", List.of(), List.of(), List.of(),
                Map.of("type", "object", "minLength", 3), 0, RoutingContract.Source.INHERITED))
                .as("⛔ 白名单外的关键字：解析器不认识它，于是它**永远不生效**，"
                        + "而配置者以为 input 被约束住了")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minLength");

        // 白名单与子输出契约同源（§3.4「复用 ChildResultContractValidator 的口径」）
        assertThat(ContractSchemaKeywords.SUPPORTED)
                .contains("type", "required", "properties", "additionalProperties", "items",
                        "enum", "description", "title");
    }

    @Test
    @DisplayName("⛔ priority 非负、source 必填")
    void priorityAndSourceAreValidated() {
        assertThatThrownBy(() -> new RoutingContract("负责解题", List.of(), List.of(), List.of(),
                SCHEMA, -1, RoutingContract.Source.INHERITED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priority");
        assertThatThrownBy(() -> new RoutingContract("负责解题", List.of(), List.of(), List.of(),
                SCHEMA, 0, null))
                .as("⚠️ 来源不明 = 无法判断这份契约是子的底稿还是父的覆盖")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("来源");
    }

    @Test
    @DisplayName("✅ 深度不可变：外部改动列表或 Map 不会影响已构造的契约")
    void contractIsImmutable() {
        List<String> useWhen = new ArrayList<>();
        useWhen.add("原始场景");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        RoutingContract contract = new RoutingContract("负责解题", useWhen, List.of(),
                List.of("math"), schema, 0, RoutingContract.Source.INHERITED);

        useWhen.add("事后追加");
        schema.put("required", List.of("topic"));

        assertThat(contract.useWhen()).containsExactly("原始场景");
        assertThat(contract.inputContract()).containsOnlyKeys("type");
        assertThatThrownBy(() -> contract.capabilities().add("late"))
                .as("⚠️ 契约进了闭包与指纹：可变就意味着「算完指纹后内容还能变」")
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
