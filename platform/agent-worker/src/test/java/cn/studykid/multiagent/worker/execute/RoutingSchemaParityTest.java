package cn.studykid.multiagent.worker.execute;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.orchestration.DelegationArguments;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.orchestration.DelegationToolSchema;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 「模型看到的」与「服务端判的」一致性测试（10 号设计 §9 · S1 出口条件）。
 *
 * <p>S1 的验收是一句话：<b>{@code enum} 与 {@code candidates} 同源同序</b>。
 * 这条不变式之所以要用<b>真实装配链</b>（装配器 → 编排解析 → Schema 渲染）验证，
 * 而不是单测一个方法：不一致的来源恰恰是<b>两处各写一份</b> ——
 * enum 取一份列表、candidates 另取一份，测试各测各的都会绿。
 *
 * <p>⚠️ <b>不一致的后果不是「不好看」</b>：
 * <ul>
 *   <li>{@code enum} 里有、{@code candidates} 里没有 → 模型被允许选一个
 *       <b>它无从判断该不该选</b>的目标（只能靠猜标识符字面意思）；</li>
 *   <li>{@code candidates} 里有、{@code enum} 里没有 → 模型按说明选了它，
 *       却被 {@code enum} 判成非法取值，白烧一轮调用。</li>
 * </ul>
 *
 * <p>顺带守住第二组「两套语义」：Schema 声明的入参字段必须就是
 * 闸门 {@link DelegationArguments} 接受的那两个字段。
 */
class RoutingSchemaParityTest {

    private static final String TENANT = "t-a";
    private static final String AGENT = "router-1";
    private static final int VERSION = 3;

    /** 三个子，各带一份路由契约（顺序即父声明顺序）。 */
    private static final String ROUTER_ORCHESTRATION = """
            {"mode":"ROUTER",
             "routingPolicyRef":"triage@2",
             "childAgentRefs":[
               {"id":"order-query","version":7,
                "routingContract":{"intent":"按订单号查询订单状态与物流进度",
                  "useWhen":["用户询问单个订单的状态/物流/发货时间"],
                  "avoidWhen":["批量统计、报表、财务对账"],
                  "capabilities":["order.read"],"inputContract":{"type":"object"},
                  "priority":10,"source":"INHERITED"}},
               {"id":"refund-apply","version":4,
                "routingContract":{"intent":"为已支付订单发起退款申请",
                  "useWhen":["用户明确要求退款"],
                  "capabilities":["refund.write"],"priority":20,"source":"OVERRIDE"}},
               {"id":"faq-answer","version":1}],
             "maxDelegationDepth":2,"maxChildInvocations":3,"timeoutMs":30000,
             "failurePolicy":"FAIL_FAST"}
            """;

    private static final class FixedAgentDefs implements CapAgentDefPort {
        @Override
        public boolean insert(@NonNull CapAgentDef def) {
            return true;
        }

        @Override
        public @NonNull Optional<CapAgentDef> find(@NonNull String tenantId,
                                                   @NonNull String agentId, int version) {
            return Optional.of(new CapAgentDef(TENANT, AGENT, VERSION, "分诊 Router", "描述",
                    List.of(), List.of(), List.of(), List.of(), "{}", ROUTER_ORCHESTRATION,
                    "fp", 1, Instant.EPOCH, Instant.EPOCH));
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatest(@NonNull String tenantId,
                                                         @NonNull String agentId) {
            return find(tenantId, agentId, VERSION);
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatestPublished(@NonNull String tenantId,
                                                                   @NonNull String agentId) {
            return find(tenantId, agentId, VERSION);
        }

        @Override
        public @NonNull List<CapAgentDef> listLatestPerAgent(@NonNull String tenantId) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tool(String key) {
        Map<String, Object> schemas =
                new DelegationToolSchemaAssembler(new FixedAgentDefs()).assemble(TENANT, AGENT, VERSION);
        return (Map<String, Object>) schemas.get(key == null ? DelegationToolSchema.TOOL_NAME : key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> enumValues() {
        Map<String, Object> inputSchema =
                (Map<String, Object>) tool(null).get("inputSchema");
        Map<String, Object> target =
                (Map<String, Object>) ((Map<String, Object>) inputSchema.get("properties"))
                        .get(DelegationToolSchema.PARAM_TARGET);
        return (List<String>) target.get("enum");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> candidates() {
        return (List<Map<String, Object>>) tool(null).get("candidates");
    }

    @Test
    @DisplayName("✅ enum 与 candidates 同源同序（S1 出口条件）")
    void enumAndCandidatesShareOneSourceList() {
        List<String> ids = candidates().stream().map(c -> (String) c.get("id")).toList();

        assertThat(enumValues())
                .as("⛔ enum 与 candidates 不同源同序：模型会被允许选一个"
                        + "它在 candidates 里看不到说明的目标")
                .containsExactlyElementsOf(ids);
        assertThat(ids).containsExactly("order-query", "refund-apply", "faq-answer");
    }

    @Test
    @DisplayName("✅ candidates 覆盖每一个已声明的子（不漏目标，也不多出未声明的目标）")
    void candidatesCoverExactlyTheDeclaredChildren() {
        List<Map<String, Object>> nodes = candidates();

        assertThat(nodes)
                .as("⛔ 候选数与 childAgentRefs 数不一致：有目标被漏下发或凭空多出一个")
                .hasSize(3);
        assertThat(nodes.stream().map(c -> (String) c.get("id")).distinct())
                .as("⛔ 同一目标出现两次：模型面对两份可能不同的说明")
                .hasSize(3);
    }

    @Test
    @DisplayName("✅ 契约字段逐项进候选（父声明什么，模型就看到什么）")
    void contractFieldsFlowIntoCandidates() {
        Map<String, Object> first = candidates().get(0);

        assertThat(first)
                .containsEntry("version", 7)
                .containsEntry("description", "按订单号查询订单状态与物流进度")
                .containsEntry("useWhen", List.of("用户询问单个订单的状态/物流/发货时间"))
                .containsEntry("avoidWhen", List.of("批量统计、报表、财务对账"))
                .containsEntry("capabilities", List.of("order.read"))
                .containsEntry("priority", 10);
    }

    /**
     * ⚠️ <b>无契约的候选不编造说明</b>。
     * 拿 Agent 名字里的只言片语拼一段「看起来像职责」的文案，
     * 模型会把它当成父的正式声明来依赖 —— 那又是一处「看起来生效、实际不生效」。
     */
    @Test
    @DisplayName("✅ 未声明契约的子：候选只有 id 与 version，没有凭空生成的说明")
    void childWithoutContractGetsBareCandidate() {
        assertThat(candidates().get(2))
                .as("⛔ 没有契约却被渲染出 description：模型据一句编造的说明选目标")
                .containsOnlyKeys("id", "version");
    }

    @Test
    @DisplayName("✅ Schema 的入参字段与闸门口径一致（防「两套语义」复发）")
    void parameterNamesMatchTheGate() {
        Map<String, Object> inputSchema = toolSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");

        assertThat(properties.keySet())
                .as("⛔ Schema 声明的字段与闸门接受的不是同一套："
                        + "模型按 Schema 发参数、闸门按另一套判，委派永远学不会")
                .containsExactlyInAnyOrder(DelegationToolSchema.PARAM_TARGET,
                        DelegationToolSchema.PARAM_INPUT);
        assertThat(inputSchema.get("additionalProperties"))
                .isEqualTo(false);
        assertThat(inputSchema.get("required"))
                .isEqualTo(List.of(DelegationToolSchema.PARAM_TARGET,
                        DelegationToolSchema.PARAM_INPUT));

        // 按 Schema 合法发出的参数，闸门必须接受（enum 的每个取值都试一遍）
        for (String target : enumValues()) {
            assertThatCode(() -> DelegationArguments.parse(
                    Map.of(DelegationToolSchema.PARAM_TARGET, target,
                            DelegationToolSchema.PARAM_INPUT, Map.of("topic", "order_status"))))
                    .as("⛔ 模型按 Schema 发出的委派被闸门拒收: target=" + target)
                    .doesNotThrowAnyException();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolSchema() {
        return (Map<String, Object>) tool(null).get("inputSchema");
    }
}
