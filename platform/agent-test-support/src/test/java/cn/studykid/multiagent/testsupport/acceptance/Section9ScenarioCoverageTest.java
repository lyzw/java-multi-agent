package cn.studykid.multiagent.testsupport.acceptance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.ReasonCode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §9 场景覆盖的<b>机器校验</b>（W-PERM-P6-T1/T5 · §8.3 / §10 / P6 §6）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：追踪矩阵如果只是 Markdown 表格，
 * 它就退化成了「手工勾选」—— 而 P6 §6 明确把「追踪矩阵虚标」列为风险，
 * 缓解措施是「§10 强制证据路径；CI 报告可关联，<b>禁止手工勾选代替</b>」。
 * 本类把「矩阵」变成<b>可执行断言</b>：
 *
 * <ol>
 *   <li><b>场景集合与文档一致</b>—— 注册表多一个少一个都红；</li>
 *   <li><b>四字段与文档逐字一致</b>—— 改了设计文档的期望而没改实现，红；</li>
 *   <li><b>证据方法确实存在</b>—— 测试被改名/删除，红；</li>
 *   <li><b>证据方法确实断言了那个原因码</b>—— 方法还在但断言被删，红。</li>
 * </ol>
 *
 * <p>⚠️ <b>第 4 条是本类最关键的一条</b>：
 * 只验证「方法存在」的话，把方法体清空也能通过 ——
 * 那正是「虚标」最容易发生的形态。
 * 故本类回到源码里，剥掉注释与字符串字面量后，检查方法体中<b>真的出现了</b>
 * 期望的原因码常量引用。
 */
class Section9ScenarioCoverageTest {

    // ── 1. 场景集合 ──────────────────────────────────────────────

    @Test
    @DisplayName("✅ §9.1 行为场景集合与设计文档完全一致（不多不少）")
    void behaviorScenarioSetMatchesDesignDoc() {
        Set<String> docKeys = new LinkedHashSet<>(Section9ScenarioSpec.behaviorScenarios().keySet());
        Set<String> registryKeys = new LinkedHashSet<>();

        for (Section9ScenarioRegistry.Scenario scenario : Section9ScenarioRegistry.scenarios()) {
            if (scenario.kind() == Section9ScenarioRegistry.Kind.BEHAVIOR) {
                registryKeys.add(scenario.key());
            }
        }

        assertThat(registryKeys)
                .as("⛔ §9.1 行为场景登记与设计文档不一致："
                        + "多出的=%s，缺失的=%s。冻结判定必须建立在**当前**文档上，"
                        + "不能靠「文档改了、矩阵没改」蒙混过关",
                        difference(registryKeys, docKeys), difference(docKeys, registryKeys))
                .containsExactlyInAnyOrderElementsOf(docKeys);
    }

    @Test
    @DisplayName("✅ §9.2 不变量 / 故障注入场景集合与设计文档完全一致")
    void invariantScenarioSetMatchesDesignDoc() {
        Set<String> docKeys = new LinkedHashSet<>(Section9ScenarioSpec.invariantScenarios().keySet());
        Set<String> registryKeys = new LinkedHashSet<>();

        for (Section9ScenarioRegistry.Scenario scenario : Section9ScenarioRegistry.scenarios()) {
            if (scenario.kind() != Section9ScenarioRegistry.Kind.BEHAVIOR) {
                registryKeys.add(scenario.key());
            }
        }

        assertThat(registryKeys)
                .as("⛔ §9.2 场景登记与设计文档不一致：多出的=%s，缺失的=%s",
                        difference(registryKeys, docKeys), difference(docKeys, registryKeys))
                .containsExactlyInAnyOrderElementsOf(docKeys);
    }

    // ── 2. 四字段与文档一致 ───────────────────────────────────────

    @Test
    @DisplayName("✅ 每个行为场景的四字段期望与设计文档逐字一致")
    void behaviorFourFieldsMatchDesignDoc() {
        Map<String, Section9ScenarioSpec.BehaviorScenario> doc =
                Section9ScenarioSpec.behaviorScenarios();

        for (Section9ScenarioRegistry.Scenario scenario : Section9ScenarioRegistry.scenarios()) {
            if (scenario.kind() != Section9ScenarioRegistry.Kind.BEHAVIOR) {
                continue;
            }
            Section9ScenarioSpec.BehaviorScenario spec = doc.get(scenario.key());
            assertThat(spec)
                    .as("⛔ 场景 %s 在设计文档中不存在", scenario.key())
                    .isNotNull();

            assertThat(scenario.expectations())
                    .as("⛔ 行为场景 %s 没有登记任何期望（四字段缺失即等于没验）", scenario.key())
                    .isNotEmpty();

            assertThat(new LinkedHashSet<>(scenario.expectations()))
                    .as("⛔ 场景 %s 登记的四字段期望与设计文档 §9.1 的表格不一致"
                            + "（文档：%s）。改了文档的期望而没同步实现或注册表，"
                            + "冻结判定就建立在过期数据上",
                            scenario.key(), spec.expectations())
                    .containsExactlyInAnyOrderElementsOf(spec.expectations());
        }
    }

    @Test
    @DisplayName("✅ 每个行为场景的原因码与设计文档一致（双向）")
    void behaviorReasonCodesMatchDesignDoc() {
        Map<String, Section9ScenarioSpec.BehaviorScenario> doc =
                Section9ScenarioSpec.behaviorScenarios();

        for (Section9ScenarioRegistry.Scenario scenario : Section9ScenarioRegistry.scenarios()) {
            if (scenario.kind() != Section9ScenarioRegistry.Kind.BEHAVIOR) {
                continue;
            }
            Section9ScenarioSpec.BehaviorScenario spec = doc.get(scenario.key());

            Set<ReasonCode> registered = new LinkedHashSet<>();
            scenario.expectations().forEach(e -> registered.add(e.reasonCode()));

            Set<ReasonCode> docCodes = new LinkedHashSet<>();
            spec.expectations().forEach(e -> docCodes.add(e.reasonCode()));

            assertThat(registered)
                    .as("⛔ 场景 %s 登记的原因码与设计文档不一致（文档：%s）",
                            scenario.key(), docCodes)
                    .containsExactlyInAnyOrderElementsOf(docCodes);
        }
    }

    // ── 3. 证据存在性 ────────────────────────────────────────────

    @Test
    @DisplayName("✅ 每个场景都有证据落点，且每个落点的方法确实存在")
    void everyEvidenceMethodExists() {
        List<String> missing = new ArrayList<>();

        for (Section9ScenarioRegistry.Scenario scenario : Section9ScenarioRegistry.scenarios()) {
            if (scenario.evidences().isEmpty()) {
                missing.add(scenario.key() + " → 没有任何证据落点");
                continue;
            }
            for (Section9ScenarioRegistry.Evidence evidence : scenario.evidences()) {
                Optional<String> body = TestSourceIndex.methodBody(
                        evidence.className(), evidence.methodName());
                if (body.isEmpty()) {
                    missing.add(scenario.key() + " → " + evidence.ref());
                }
            }
        }

        assertThat(missing)
                .as("⛔ 证据路径失效：登记的测试方法在源码中不存在。"
                        + "这正是「追踪矩阵虚标」的典型形态 —— 矩阵上的 ✅ 指向一条已经不存在的用例。"
                        + "修法：更新注册表（若用例确已废弃）或补回用例（若仍是必需场景）")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 每个行为场景的证据方法确实断言了期望的原因码")
    void everyBehaviorEvidenceAssertsItsReasonCode() {
        List<String> notAsserted = new ArrayList<>();

        for (Section9ScenarioRegistry.Scenario scenario : Section9ScenarioRegistry.scenarios()) {
            if (scenario.kind() != Section9ScenarioRegistry.Kind.BEHAVIOR) {
                continue;
            }
            for (Section9ScenarioRegistry.Expectation expectation : scenario.expectations()) {
                boolean asserted = scenario.evidences().stream()
                        .anyMatch(evidence -> TestSourceIndex.methodBodyContains(
                                evidence.className(), evidence.methodName(),
                                "ReasonCode." + expectation.reasonCode().name()));
                if (!asserted) {
                    notAsserted.add(scenario.key() + " → " + expectation.reasonCode()
                            + "（证据：" + scenario.evidences().stream()
                            .map(Section9ScenarioRegistry.Evidence::ref)
                            .toList() + "）");
                }
            }
        }

        assertThat(notAsserted)
                .as("⛔ 有行为场景的证据方法<b>没有真的断言</b>期望的原因码 —— "
                        + "「方法还在」不等于「场景被验过」。"
                        + "（断言文案里提到该码不算，必须是代码里的常量引用）")
                .isEmpty();
    }

    // ── 4. 注册表自检 ────────────────────────────────────────────

    @Test
    @DisplayName("✅ 注册表自检：无重复键、每条场景都有备注")
    void registryIsSelfConsistent() {
        Set<String> seen = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();

        for (Section9ScenarioRegistry.Scenario scenario : Section9ScenarioRegistry.scenarios()) {
            if (!seen.add(scenario.key())) {
                problems.add("重复键: " + scenario.key());
            }
            if (scenario.note() == null || scenario.note().isBlank()) {
                problems.add("缺备注: " + scenario.key()
                        + "（备注要如实写明「仅单测层」「间接证据」「缺口」等，"
                        + "空白备注会让读者默认它是完整覆盖）");
            }
            if (scenario.kind() == Section9ScenarioRegistry.Kind.BEHAVIOR
                    && scenario.expectations().isEmpty()) {
                problems.add("行为场景缺四字段期望: " + scenario.key());
            }
            if (scenario.kind() != Section9ScenarioRegistry.Kind.BEHAVIOR
                    && !scenario.expectations().isEmpty()) {
                problems.add("不变量/故障场景不应有四字段期望: " + scenario.key());
            }
        }

        assertThat(problems).as("⛔ 注册表自检未通过").isEmpty();
    }

    @Test
    @DisplayName("✅ 设计文档可解析：§9.1 与 §9.2 都能读出场景")
    void designDocIsParseable() {
        Map<String, Section9ScenarioSpec.BehaviorScenario> behavior =
                Section9ScenarioSpec.behaviorScenarios();
        Map<String, Section9ScenarioSpec.InvariantScenario> invariant =
                Section9ScenarioSpec.invariantScenarios();

        assertThat(behavior)
                .as("⛔ 设计文档 §9.1 解析不到任何场景 —— 解析器坏了或文档结构变了")
                .hasSizeGreaterThanOrEqualTo(20);
        assertThat(invariant)
                .as("⛔ 设计文档 §9.2 解析不到任何场景 —— 解析器坏了或文档结构变了")
                .hasSizeGreaterThanOrEqualTo(13);
        assertThat(behavior.get("S1"))
                .as("⛔ 场景 S1 解析异常")
                .isNotNull();
        assertThat(behavior.get("S1").expectations())
                .as("⛔ 场景 S1 的期望四字段解析异常")
                .containsExactlyInAnyOrder(new Section9ScenarioRegistry.Expectation(
                        AuthorizationDecision.Authorization.ALLOW,
                        AuthorizationDecision.Execution.ALLOW,
                        AuthorizationDecision.Status.SUCCEEDED,
                        ReasonCode.SUCCESS));
    }

    // ── 工具 ──────────────────────────────────────────────────────

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }
}
