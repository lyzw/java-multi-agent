package cn.studykid.multiagent.application.orchestration;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.orchestration.AgentOrchestration;
import cn.studykid.multiagent.core.orchestration.DelegationFailurePolicy;
import cn.studykid.multiagent.core.orchestration.OrchestrationMode;
import cn.studykid.multiagent.core.orchestration.RoutingContract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编排声明解析与规范化测试（多级 Agent v1.1 · P1-MA-01 · MA-T01 / MA-T02）。
 */
class AgentOrchestrationParserTest {

    @Test
    @DisplayName("缺省（null / 空 / {}）→ 按 LEAF 兼容解析（MA-T01）")
    void missingOrchestrationIsLeaf() {
        assertThat(AgentOrchestrationParser.parse(null).isLeaf()).isTrue();
        assertThat(AgentOrchestrationParser.parse("   ").isLeaf()).isTrue();
        assertThat(AgentOrchestrationParser.parse("{}").isLeaf()).isTrue();
    }

    @Test
    @DisplayName("子 Agent 引用 latest → 配置期拒绝（MA-T02）")
    void latestChildRefIsRejected() {
        assertThatThrownBy(() -> AgentOrchestrationParser.parse("""
                {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child","version":"latest"}],
                 "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                """))
                .isInstanceOf(OrchestrationConfigException.class)
                .hasMessageContaining("latest");
    }

    @Test
    @DisplayName("ROUTER 模式缺路由策略 → 配置期拒绝")
    void routerWithoutPolicyIsRejected() {
        assertThatThrownBy(() -> AgentOrchestrationParser.parse("""
                {"mode":"ROUTER",
                 "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                """))
                .isInstanceOf(OrchestrationConfigException.class)
                .hasMessageContaining("路由策略");
    }

    @Test
    @DisplayName("LEAF 携带子引用 → 配置期拒绝（不得静默忽略）")
    void leafWithChildrenIsRejected() {
        assertThatThrownBy(() -> AgentOrchestrationParser.parse("""
                {"mode":"LEAF","childAgentRefs":[{"id":"child","version":1}]}
                """))
                .isInstanceOf(OrchestrationConfigException.class);
    }

    @Test
    @DisplayName("未知模式 → 配置期拒绝")
    void unknownModeIsRejected() {
        assertThatThrownBy(() -> AgentOrchestrationParser.parse("""
                {"mode":"PARALLEL"}
                """))
                .isInstanceOf(OrchestrationConfigException.class)
                .hasMessageContaining("未知");
    }

    @Test
    @DisplayName("路由策略引用必须是固定版本（id@version），禁止 latest")
    void routingPolicyRefMustBePinned() {
        assertThatThrownBy(() -> AgentOrchestrationParser.parse("""
                {"mode":"ROUTER","routingPolicyRef":"policy@latest",
                 "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                """))
                .isInstanceOf(OrchestrationConfigException.class)
                .hasMessageContaining("latest");

        assertThatThrownBy(() -> AgentOrchestrationParser.parse("""
                {"mode":"ROUTER","routingPolicyRef":"policy",
                 "maxDelegationDepth":3,"maxChildInvocations":8,"timeoutMs":30000}
                """))
                .isInstanceOf(OrchestrationConfigException.class)
                .hasMessageContaining("id@version");
    }

    @Test
    @DisplayName("SEQUENTIAL 正常解析：模式 / 子引用 / 边界默认值")
    void sequentialIsParsed() {
        AgentOrchestration orchestration = AgentOrchestrationParser.parse("""
                {"mode":"SEQUENTIAL",
                 "childAgentRefs":[{"id":"child-a","version":2},{"id":"child-b","version":1,"required":false}],
                 "maxDelegationDepth":3,"maxChildInvocations":5,"timeoutMs":15000,
                 "failurePolicy":"CONTINUE_ON_ERROR"}
                """);

        assertThat(orchestration.mode()).isEqualTo(OrchestrationMode.SEQUENTIAL);
        assertThat(orchestration.childAgentRefs()).hasSize(2);
        assertThat(orchestration.child("child-a")).isPresent();
        assertThat(orchestration.child("child-a").orElseThrow().releaseVersion()).isEqualTo(2);
        assertThat(orchestration.child("child-a").orElseThrow().required()).isTrue();
        assertThat(orchestration.child("child-b").orElseThrow().required()).isFalse();
        assertThat(orchestration.maxDelegationDepth()).isEqualTo(3);
        assertThat(orchestration.maxChildInvocations()).isEqualTo(5);
        assertThat(orchestration.timeoutMs()).isEqualTo(15000L);
        assertThat(orchestration.failurePolicy()).isEqualTo(DelegationFailurePolicy.CONTINUE_ON_ERROR);
    }

    /**
     * 非 LEAF 缺委派限制 → **拒绝**（评审前置决策 2：取消平台默认值）。
     *
     * <p>⚠️ **为什么必须是拒绝而不是兜底**：原先三个限制有平台默认值
     * （深度 2 / 次数 8 / 超时 60s），但那三个数字**从未经过容量与业务风险评估** ——
     * 它们只是示例值。一旦以「默认值」的形式存在，就会被配置方**默默继承**，
     * 然后在没人评审的情况下成为生产口径。
     * 现在：**非 LEAF 必须在发布时显式声明**，缺失即拒绝发布（fail-closed）。
     */
    @Test
    @DisplayName("⛔ 非 LEAF 缺委派限制 → 拒绝解析（平台默认值已取消，评审前置决策 2）")
    void nonLeafWithoutLimitsIsRejected() {
        OrchestrationConfigException error = org.junit.jupiter.api.Assertions.assertThrows(
                OrchestrationConfigException.class,
                () -> AgentOrchestrationParser.parse("""
                        {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child","version":1}]}
                        """));

        assertThat(error.getMessage())
                .as("⚠️ 拒绝理由必须点名「必须显式声明」与「平台不提供默认值」——"
                        + "只说「非法」会让人以为是格式问题，去改格式而不是去声明")
                .contains("必须显式声明")
                .contains("平台不提供默认值");
    }

    /**
     * 三个限制**分别**缺失都要被拒（缺一个和缺三个同样危险）。
     *
     * <p>⚠️ 少一个上限就等于「这一跳没有上限」，而在多级场景里上限会指数放大
     * （每层各自的上限相乘）—— 所以不能只校验「三个都在」。
     */
    @Test
    @DisplayName("⛔ 非 LEAF 缺任一委派限制 → 逐个都被拒（缺一个 = 少一道上限）")
    void eachMissingLimitIsRejectedIndividually() {
        String[] incomplete = {
            """
                    {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child","version":1}],
                     "maxChildInvocations":3,"timeoutMs":30000}
                    """,
            """
                    {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child","version":1}],
                     "maxDelegationDepth":2,"timeoutMs":30000}
                    """,
            """
                    {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child","version":1}],
                     "maxDelegationDepth":2,"maxChildInvocations":3}
                    """,
        };
        for (String json : incomplete) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    OrchestrationConfigException.class,
                    () -> AgentOrchestrationParser.parse(json),
                    "⚠️ 缺一个限制也必须拒绝 —— 少一道上限就是多级场景里的指数放大");
        }
    }

    /**
     * LEAF 的三个限制为 {@code null}（**不适用**，不是 0、也不是默认值）。
     *
     * <p>⚠️ 用 0 或某个默认值表达「LEAF 没有限制」都会制造第二种解读：
     * 「0 次子调用」看起来像个真实上限，而「深度 2」看起来像「可以委派两层」。
     * {@code null} 才准确表达「这个字段对 LEAF 不适用」。
     */
    @Test
    @DisplayName("✅ LEAF 的三个委派限制为 null（不适用），不是 0 也不是默认值")
    void leafLimitsAreNull() {
        AgentOrchestration leaf = AgentOrchestrationParser.parse("{}");

        assertThat(leaf.isLeaf()).isTrue();
        assertThat(leaf.maxDelegationDepth())
                .as("⚠️ LEAF 不参与委派 ⇒ 限制无意义 ⇒ null（不是 0、不是默认值）")
                .isNull();
        assertThat(leaf.maxChildInvocations()).isNull();
        assertThat(leaf.timeoutMs()).isNull();
        assertThat(leaf.failurePolicy()).isEqualTo(DelegationFailurePolicy.FAIL_FAST);
    }

    /** 缺省编排（无 orchestration 字段）同样按 LEAF 解析，且限制为 null（MA-T01）。 */
    @Test
    @DisplayName("✅ 缺省 orchestration → LEAF 且限制为 null（MA-T01 · 历史 Agent 兼容）")
    void missingOrchestrationIsLeafWithNullLimits() {
        AgentOrchestration leaf = AgentOrchestrationParser.parse(null);

        assertThat(leaf.isLeaf()).isTrue();
        assertThat(leaf.maxDelegationDepth()).isNull();
        assertThat(leaf.maxChildInvocations()).isNull();
        assertThat(leaf.timeoutMs()).isNull();
    }

    @Test
    @DisplayName("序列化 → 再解析保持等价（往返稳定）")
    void roundTripIsStable() {
        String json = """
                {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-a","version":2}],
                 "maxDelegationDepth":3,"maxChildInvocations":5,"timeoutMs":15000,
                 "failurePolicy":"FAIL_FAST"}
                """;

        AgentOrchestration parsed = AgentOrchestrationParser.parse(json);
        AgentOrchestration reparsed = AgentOrchestrationParser.parse(
                AgentOrchestrationParser.toJson(parsed));

        assertThat(reparsed).isEqualTo(parsed);
    }

    // ── 父侧路由契约（10 号设计 §3.1 · S1）────────────────────────────

    /** 「没写契约」是合法状态（拓扑照旧），字段缺省按空列表 / 空 Schema / INHERITED / 0 补齐。 */
    @Test
    @DisplayName("✅ 只声明 intent 的契约：其余字段取空值而不是 null")
    void minimalContractIsParsed() {
        AgentOrchestration orchestration = AgentOrchestrationParser.parse(
                contractJson("""
                        "intent":"负责把应用题拆成算式"
                        """));

        RoutingContract contract = orchestration.child("child-a")
                .orElseThrow().routingContract();
        assertThat(contract).isNotNull();
        assertThat(contract.intent()).isEqualTo("负责把应用题拆成算式");
        assertThat(contract.useWhen()).isEmpty();
        assertThat(contract.avoidWhen()).isEmpty();
        assertThat(contract.capabilities()).isEmpty();
        assertThat(contract.inputContract())
                .as("⚠️ 空 Schema 是「不加约束」的显式声明，不能是 null")
                .isEmpty();
        assertThat(contract.priority()).isEqualTo(RoutingContract.MIN_PRIORITY);
        assertThat(contract.source()).isEqualTo(RoutingContract.Source.INHERITED);
    }

    @Test
    @DisplayName("✅ 完整契约逐字段解析")
    void fullContractIsParsed() {
        AgentOrchestration orchestration = AgentOrchestrationParser.parse(
                contractJson("""
                        "intent":"负责几何题的作图与证明",
                        "useWhen":["学生给出图形或要求证明"],
                        "avoidWhen":["纯代数计算"],
                        "capabilities":["geometry","proof"],
                        "inputContract":{"type":"object","required":["stem"]},
                        "priority":3,"source":"OVERRIDE"
                        """));

        RoutingContract contract = orchestration.child("child-a")
                .orElseThrow().routingContract();
        assertThat(contract.intent()).isEqualTo("负责几何题的作图与证明");
        assertThat(contract.useWhen()).containsExactly("学生给出图形或要求证明");
        assertThat(contract.avoidWhen()).containsExactly("纯代数计算");
        assertThat(contract.capabilities()).containsExactly("geometry", "proof");
        assertThat(contract.inputContract())
                .containsEntry("type", "object")
                .containsEntry("required", List.of("stem"));
        assertThat(contract.priority()).isEqualTo(3);
        assertThat(contract.source()).isEqualTo(RoutingContract.Source.OVERRIDE);
    }

    /**
     * ⚠️ <b>写了但写错，绝不降级成「没写契约」</b>。
     *
     * <p>降级会让 Router 安静地回到「只看见一个 id」，而配置者以为契约已生效 ——
     * 这正是本项目反复出现的「看起来生效、实际不生效」。
     */
    @Test
    @DisplayName("⛔ 非法契约逐个被拒，不会被当成「没有契约」")
    void malformedContractIsRejectedNotIgnored() {
        String[] illegal = {
            // 缺 intent
            "\"useWhen\":[\"题目\"]",
            // intent 空白
            "\"intent\":\"  \"",
            // 能力标签不符合词表（大写）
            "\"intent\":\"解题\",\"capabilities\":[\"Math\"]",
            // Schema 用了白名单外的关键字
            "\"intent\":\"解题\",\"inputContract\":{\"type\":\"object\",\"minLength\":3}",
            // 场景条目含空白项
            "\"intent\":\"解题\",\"useWhen\":[\"甲\",\"\"]",
            // priority 为负
            "\"intent\":\"解题\",\"priority\":-1",
            // priority 非整数
            "\"intent\":\"解题\",\"priority\":1.5",
            // source 未知
            "\"intent\":\"解题\",\"source\":\"INVENTED\"",
            // inputContract 不是对象
            "\"intent\":\"解题\",\"inputContract\":\"object\"",
        };
        for (String body : illegal) {
            assertThatThrownBy(() -> AgentOrchestrationParser.parse(contractJson(body)))
                    .as("⚠️ 非法契约必须拒绝发布，收到: %s", body)
                    .isInstanceOf(OrchestrationConfigException.class);
        }

        // 契约整体不是对象
        assertThatThrownBy(() -> AgentOrchestrationParser.parse("""
                {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-a","version":1,
                 "routingContract":"负责解题"}],
                 "maxDelegationDepth":3,"maxChildInvocations":5,"timeoutMs":15000}
                """))
                .isInstanceOf(OrchestrationConfigException.class)
                .hasMessageContaining("必须是对象");
    }

    /**
     * 键名写错 = 该字段没写？—— 不允许。
     *
     * <p>宽松解析会让 {@code use_when} 这类拼写错误变成「useWhen 为空」，
     * 于是路由少了一批判据且<b>没有任何一步会报错</b>。
     */
    @Test
    @DisplayName("⛔ 契约与子引用对象里的未知键 → 配置期拒绝")
    void unknownKeysAreRejected() {
        assertThatThrownBy(() -> AgentOrchestrationParser.parse(
                contractJson("\"intent\":\"解题\",\"use_when\":[\"题目\"]")))
                .as("⛔ use_when 被当成「没写 useWhen」：拼写错误必须报错，不能静默吞掉")
                .isInstanceOf(OrchestrationConfigException.class)
                .hasMessageContaining("use_when");
        assertThatThrownBy(() -> AgentOrchestrationParser.parse(
                contractJson("\"intent\":\"解题\"").replace(
                        "\"routingContract\":{", "\"routingContractX\":{")))
                .isInstanceOf(OrchestrationConfigException.class)
                .hasMessageContaining("routingContractX");
    }

    /**
     * ⚠️ 契约必须<b>回写</b>：只解析不回写，管理台「读出来 → 保存回去」
     * 就会把契约抹掉 —— 编辑一次等于删掉路由语义，且看起来是正常保存。
     */
    @Test
    @DisplayName("✅ 契约随 toJson 往返（编辑一轮不会丢路由语义）")
    void contractSurvivesRoundTrip() {
        AgentOrchestration parsed = AgentOrchestrationParser.parse(contractJson("""
                "intent":"负责几何题的作图与证明",
                "useWhen":["学生给出图形或要求证明"],
                "avoidWhen":["纯代数计算"],
                "capabilities":["geometry"],
                "inputContract":{"type":"object","required":["stem"]},
                "priority":2,"source":"OVERRIDE"
                """));

        String json = AgentOrchestrationParser.toJson(parsed);
        assertThat(json)
                .as("⛔ toJson 不回写契约：管理台保存一次就把路由契约抹了")
                .contains("routingContract")
                .contains("geometry")
                .contains("OVERRIDE");
        assertThat(AgentOrchestrationParser.parse(json)).isEqualTo(parsed);
    }

    /** 未声明契约的子引用保持 null（历史 Agent 的定义行不因新字段而改写）。 */
    @Test
    @DisplayName("✅ 未声明契约 → routingContract 为 null（兼容读）")
    void absentContractStaysNull() {
        AgentOrchestration orchestration = AgentOrchestrationParser.parse("""
                {"mode":"SEQUENTIAL","childAgentRefs":[{"id":"child-a","version":1}],
                 "maxDelegationDepth":2,"maxChildInvocations":3,"timeoutMs":30000}
                """);

        assertThat(orchestration.child("child-a").orElseThrow().routingContract()).isNull();
    }

    private static final String CONTRACT_TAIL =
            ",\"maxDelegationDepth\":3,\"maxChildInvocations\":5,\"timeoutMs\":15000}";

    /** 把一段契约体拼进 SEQUENTIAL 声明的 {@code childAgentRefs[0].routingContract}。 */
    private static @NonNull String contractJson(@NonNull String contractBody) {
        return "{\"mode\":\"SEQUENTIAL\",\"childAgentRefs\":[{\"id\":\"child-a\",\"version\":1,"
                + "\"routingContract\":{" + contractBody + "}}]" + CONTRACT_TAIL;
    }
}
