package cn.studykid.multiagent.infrastructure.persistence.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.orchestration.AgentInvocation;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;
import cn.studykid.multiagent.core.orchestration.RoutingMode;
import cn.studykid.multiagent.routing.DecisionSnapshot;
import cn.studykid.multiagent.routing.EvaluationTraceEntry;
import cn.studykid.multiagent.routing.InputSchema;
import cn.studykid.multiagent.routing.Resolution;
import cn.studykid.multiagent.routing.RoutingDecision;
import cn.studykid.multiagent.routing.RoutingInput;

/**
 * 路由决策快照的<b>持久化与重放</b>集成测试
 * （多级 Agent v1.1 · 10 号设计 §6.1 / S3 出口条件）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：S3 的出口条件是一句<b>可测性质</b>——
 * 「同输入重放必得同目标」。它横跨三层：引擎给出决策（纯函数，{@code RoutingEngineTest} 管）、
 * 序列化成快照（{@code RouterDelegationTest} 管）、<b>落进 jsonb 再读回来</b>（只有真 PG 能管）。
 * 第三层缺席的后果不是报错，是<b>复核结论作废</b>：
 * jsonb 会重排对象键、重排空白（但保留数组顺序），按文本比快照的两次记录必然不等；
 * 而 {@code policy_ref} 若不能直接查，「拿同一份策略重放这批输入」就只能全表扫 + 应用侧解析。
 *
 * <p><b>本类的判据</b>：
 * <ul>
 *   <li>R-1：同输入重放 → 同一 {@code canonicalInputSha256} 与同一 {@code finalTarget}；</li>
 *   <li>R-2：schema 未声明的字段<b>不进摘要</b>（投影差异不得伪装成输入差异）；</li>
 *   <li>R-3：快照经 jsonb 往返<b>语义等价</b>，且轨迹数组顺序不被打乱；</li>
 *   <li>R-4：重放主键可<b>直接查</b>（{@code WHERE policy_ref = ?} 取到 {@code finalTarget}）；</li>
 *   <li>R-5：零保留 —— 输入的<b>明文值</b>在库里任何地方都不存在；</li>
 *   <li>R-6：跨租户读不到别人的快照（RLS）；</li>
 *   <li>R-7：迁移前的存量行三列为 NULL，语义是「当时未记录」。</li>
 * </ul>
 */
class RoutingReplayIT extends CapITBase {

    private static final String POLICY_REF = "orders-router@1";
    private static final String POLICY_FP = "fp-policy-orders-router-1";

    /**
     * ⚠️ {@code target_agent_id} 在库里是 <b>uuid</b>，所以这里必须是 UUID 文本。
     * 写成 {@code "child-agent"} 这类可读名，报的是「invalid input syntax for type uuid」——
     * 那是夹具没建起来，不是断言失败，报错点离真因很远。
     */
    private static final String TARGET = "7c9e6679-7425-40de-96b1-7aeafc501f7c";
    private static final LocalDate UTC_DAY =
            LocalDate.ofInstant(Instant.EPOCH, ZoneOffset.UTC);

    /** 路由输入词汇表：只声明 {@code topic} —— R-2 正是拿「未声明的字段」做文章。 */
    private static final InputSchema SCHEMA = parseSchema(
            "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}}}");

    private JdbcAgentInvocationRepository invocations;

    @BeforeEach
    void wireRepository() {
        invocations = new JdbcAgentInvocationRepository(appPool);
        // ⚠️ 上一例留下的行会让本例的「按 policy_ref 查」「行数」断言失真。
        adminExec("DELETE FROM agent_invocation WHERE tenant_id IN ('"
                + tenantA + "'::uuid, '" + tenantB + "'::uuid)");
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    private static InputSchema parseSchema(String json) {
        try {
            return InputSchema.from(new tools.jackson.databind.ObjectMapper().readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("夹具里的输入词汇表不合法", e);
        }
    }

    /** 两个 JSON 文本是否语义等价（忽略空白与键序）。 */
    private static boolean sameJson(String actual, String expected) {
        if (actual == null) {
            return false;
        }
        try {
            return new tools.jackson.databind.ObjectMapper()
                    .readTree(actual)
                    .equals(new tools.jackson.databind.ObjectMapper().readTree(expected));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 造一份决策快照（走<b>生产同款</b>序列化：{@link DecisionSnapshot#toJson()}）。
     *
     * <p>⚠️ 不在测试里手写 JSON 文本：手写的那份迟早和 {@code toJson()} 的键名分叉，
     *    而分叉的症状是「库里有快照、但按约定取不到字段」。
     */
    private static DecisionSnapshot snapshot(LocalDate utcDay, Map<String, Object> rawInput,
                                             String tenantId, String finalTarget) {
        RoutingInput input = RoutingInput.of(SCHEMA, rawInput, tenantId,
                null, null, 1, 1, utcDay);
        RoutingDecision decision = new RoutingDecision(RoutingMode.RULES_ONLY,
                Resolution.RULE_MATCH, finalTarget, "r-1",
                "{\"eq\":[\"in.topic\",\"grade-3\"]}", null, false,
                List.of(new EvaluationTraceEntry(1, "r-1",
                        EvaluationTraceEntry.OUTCOME_MATCH, null)), null);
        return DecisionSnapshot.of(POLICY_REF, POLICY_FP, input, decision);
    }

    /** 把一份快照按委派记录写进库，返回 invocationId（{@code null} = 不带快照的存量形态）。 */
    private String persist(String tenantId, DecisionSnapshot snapshot) {
        String id = uuid();
        String json = snapshot == null ? null : snapshot.toJson();
        assertThat(invocations.insert(new AgentInvocation(
                id, tenantId, uuid(), null, uuid(), 1, TARGET, 1, null,
                AgentInvocationStatus.SUCCEEDED, 1, "trace-" + id,
                "in-digest", "{}", Instant.EPOCH, Instant.EPOCH, null, 1L,
                json, json == null ? null : POLICY_REF,
                json == null ? null : POLICY_FP)))
                .as("带决策快照的委派记录应写入成功").isTrue();
        return id;
    }

    /** 读回库里的重放主键半边（{@code canonicalInputSha256}）。 */
    private String digestInDb(String invocationId) {
        return adminQuery("SELECT routing_decision ->> 'canonicalInputSha256' "
                + "FROM agent_invocation WHERE invocation_id = '" + invocationId + "'::uuid");
    }

    // ── R-1 / R-2：重放不变式 ───────────────────────────────────────

    @Test
    @DisplayName("R-1 ✅ 同输入重放 → 同一摘要、同一目标（两行独立落库后仍相等）")
    void sameInputReplaysToSameTarget() {
        var first = snapshot(UTC_DAY, Map.of("topic", "grade-3"), tenantA, TARGET);
        var second = snapshot(UTC_DAY, Map.of("topic", "grade-3"), tenantA, TARGET);

        String idA = persist(tenantA, first);
        String idB = persist(tenantA, second);

        assertThat(invocations.find(tenantA, idB).orElseThrow().routingDecision())
                .as("⚠️ 断言的是**读回来**的两行，不是内存里的两个对象 —— "
                        + "「两次 toJson() 恰好相等」证不了库里这两行相等")
                .isEqualTo(invocations.find(tenantA, idA).orElseThrow().routingDecision());
        assertThat(digestInDb(idB)).isEqualTo(digestInDb(idA));
        assertThat(first.canonicalInputSha256())
                .as("重放主键必须由输入决定，而不是由「第几次跑」决定")
                .isEqualTo(second.canonicalInputSha256());
    }

    @Test
    @DisplayName("R-2 ✅ schema 未声明的字段不进摘要：question 不同仍是「同一个输入」")
    void unschemaedFieldDoesNotChangeReplayKey() {
        String id1 = persist(tenantA, snapshot(UTC_DAY,
                Map.of("topic", "grade-3", "question", "为什么天是蓝的"), tenantA, TARGET));
        String id2 = persist(tenantA,
                snapshot(UTC_DAY, Map.of("topic", "grade-3"), tenantA, TARGET));

        assertThat(digestInDb(id1))
                .as("⚠️ 求值视野由 input_schema 界定（G-6）：schema 外的字段改了，"
                        + "路由判定根本不会变；若摘要跟着变，重放就把「同一个输入」当成两个")
                .isEqualTo(digestInDb(id2));
    }

    @Test
    @DisplayName("R-2b ✅ ctx.now.utcDate 进摘要：跨日重放理应得到不同主键，而不是假装输入相同")
    void utcDayIsPartOfTheReplayKey() {
        String id1 = persist(tenantA,
                snapshot(UTC_DAY, Map.of("topic", "grade-3"), tenantA, TARGET));
        String id2 = persist(tenantA,
                snapshot(UTC_DAY.plusDays(1), Map.of("topic", "grade-3"), tenantA, TARGET));

        assertThat(digestInDb(id1))
                .as("引用了时间的策略，其重放口径是「**同一 UTC 自然日内**可重放」——"
                        + "这个取舍必须由主键本身承载，而不是靠读者记得")
                .isNotEqualTo(digestInDb(id2));
    }

    // ── R-3 / R-4：jsonb 往返与可查询性 ─────────────────────────────

    @Test
    @DisplayName("R-3 ✅ 快照经 jsonb 往返语义等价，且轨迹数组顺序不被打乱")
    void snapshotSurvivesJsonbRoundTrip() {
        DecisionSnapshot snap = snapshot(UTC_DAY, Map.of("topic", "grade-3"), tenantA, TARGET);
        String id = persist(tenantA, snap);

        var row = invocations.find(tenantA, id).orElseThrow();
        assertThat(row.routingDecision()).isNotNull();
        // ⚠️ jsonb 会重排对象键、重排空白 → 只能比**解析后的 JSON**，按文本比必然假失败。
        assertThat(sameJson(row.routingDecision(), snap.toJson()))
                .as("读回的快照必须与写出的那份**是同一个快照**（语义等价，不是文本相等）")
                .isTrue();

        assertThat(row.policyRef())
                .as("重放主键的一半必须是独立列，而不是埋在 JSON 里靠约定俗成的表达式取")
                .isEqualTo(POLICY_REF);
        assertThat(row.policyFingerprint())
                .as("指纹是决策**当时**那份策略内容的冻结副本，不是外键")
                .isEqualTo(POLICY_FP);

        // ⚠️ jsonb 重排对象键但**保留**数组元素顺序。
        //    轨迹的语义正是「按 ordinal 的判定次序」；顺序一乱，
        //    「第二条规则为什么没生效」在库里就问不出来了。
        assertThat(adminQuery("SELECT routing_decision -> 'evaluationTrace' -> 0 ->> 'ruleId' "
                + "FROM agent_invocation WHERE invocation_id = '" + id + "'::uuid"))
                .isEqualTo("r-1");
    }

    @Test
    @DisplayName("R-4 ✅ 重放主键可直接查：WHERE policy_ref = ? 取到 finalTarget")
    void replayKeyIsDirectlyQueryable() {
        persist(tenantA, snapshot(UTC_DAY, Map.of("topic", "grade-3"), tenantA, TARGET));

        assertThat(adminQuery("SELECT routing_decision ->> 'finalTarget' FROM agent_invocation "
                + "WHERE policy_ref = '" + POLICY_REF + "'"))
                .as("⚠️ 「拿这份策略重放这批输入」若要靠应用侧解析 JSON 才能回答，"
                        + "键名一改就静默返回 0 行 —— 这条查询本身就是那道门禁")
                .isEqualTo(TARGET);
    }

    // ── R-5：零保留 ─────────────────────────────────────────────────

    @Test
    @DisplayName("R-5 ⛔⚠️ 输入的明文值不得出现在库里任何位置")
    void inputPlaintextIsNotStoredAnywhere() {
        String secret = "三年级数学";
        String id = persist(tenantA, snapshot(UTC_DAY, Map.of("topic", secret), tenantA, TARGET));

        // 查**整行**文本，不只查 routing_decision ——
        // 否则「哪天有人把 in.* 顺手抄进别的列」这条路径无人看守。
        String wholeRow = adminQuery("SELECT (t.*)::text FROM agent_invocation t "
                + "WHERE invocation_id = '" + id + "'::uuid");

        assertThat(wholeRow)
                .as("⚠️ 快照只存输入的 SHA-256：in.* 是业务字段（学段、订单号…），"
                        + "抄进 jsonb 等于为本表新开一条**不在既有脱敏与保留策略覆盖范围内**"
                        + "的明文旁路")
                .doesNotContain(secret);
        assertThat(wholeRow)
                .as("规范化输入的明文键也不许进快照（只许 canonicalInputSha256）")
                .doesNotContain("\"canonicalInput\"");
    }

    // ── R-6 / R-7：隔离与存量行 ─────────────────────────────────────

    @Test
    @DisplayName("R-6 ✅ RLS：跨租户读不到别人的决策快照")
    void snapshotRespectsRls() {
        String id = persist(tenantA, snapshot(UTC_DAY, Map.of("topic", "grade-3"),
                tenantA, TARGET));

        assertThat(invocations.find(tenantB, id))
                .as("快照里带着「这个租户的路由怎么判的」—— 这是策略资产，不是运行日志")
                .isEmpty();
    }

    @Test
    @DisplayName("R-7 ✅ 存量行语义为「当时未记录」：三列为 NULL，不得被填成空对象冒充")
    void legacyRowHasNoSnapshot() {
        String id = uuid();
        assertThat(invocations.insert(new AgentInvocation(
                id, tenantA, uuid(), null, uuid(), 1, TARGET, 1,
                AgentInvocationStatus.SUCCEEDED, 1, "trace-" + id,
                "in-digest", null, Instant.now(), null, null, 1L)))
                .as("兼容构造器（无快照）必须仍能写入 —— 否则回填前的整条多级链路全挂")
                .isTrue();

        var row = invocations.find(tenantA, id).orElseThrow();
        assertThat(row.routingDecision())
                .as("NULL 的语义是「当时未记录」；写成 '{}' 就是把「无从判断」冒充成「判过了」")
                .isNull();
        assertThat(row.policyRef()).isNull();
        assertThat(row.policyFingerprint()).isNull();
    }
}
