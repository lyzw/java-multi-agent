package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 审批范围定义测试（P3-T1 · §5.3.1）。
 *
 * <p>⚠️ <b>「范围定义」是审批的语义边界</b>：
 * 它既决定范围哈希，又决定消费时能否重验实际参数。
 * 若规范形式不稳定（同样内容算出不同哈希），审批永远匹配不上；
 * 若往返丢信息（解析回来与原定义不同），范围重验会静默放宽。
 */
class ApprovalScopeTest {

    private static ApprovalScope.Constraint eq(Object value) {
        return new ApprovalScope.Constraint("eq", value);
    }

    @Nested
    @DisplayName("规范形式")
    class CanonicalForm {

        @Test
        @DisplayName("⚠️ 构造顺序不同 → 同一哈希")
        void constructionOrderDoesNotAffectHash() {
            Map<String, ApprovalScope.Constraint> a = new LinkedHashMap<>();
            a.put("to", eq("a@example.com"));
            a.put("amount", new ApprovalScope.Constraint("lte", 100));

            Map<String, ApprovalScope.Constraint> b = new LinkedHashMap<>();
            b.put("amount", new ApprovalScope.Constraint("lte", 100));
            b.put("to", eq("a@example.com"));

            assertThat(ApprovalScope.of("tool", a).hash())
                    .as("⚠️ 同一份范围必须算出同一哈希，否则审批永远匹配不上")
                    .isEqualTo(ApprovalScope.of("tool", b).hash());
        }

        @Test
        @DisplayName("⚠️ 工具不同 → 不同哈希（范围绑定目标工具）")
        void toolIdParticipatesInHash() {
            Map<String, ApprovalScope.Constraint> c = Map.of("to", eq("a@example.com"));

            assertThat(ApprovalScope.of("tool-a", c).hash())
                    .isNotEqualTo(ApprovalScope.of("tool-b", c).hash());
        }

        @Test
        @DisplayName("⚠️ 有效参数集不同 → 不同哈希")
        void effectfulParamsParticipateInHash() {
            Map<String, ApprovalScope.Constraint> c = Map.of();

            ApprovalScope all = new ApprovalScope("tool", c, null, null, null);
            ApprovalScope limited = new ApprovalScope("tool", c, Set.of("a"), null, null);

            assertThat(all.hash()).isNotEqualTo(limited.hash());
        }
    }

    @Nested
    @DisplayName("往返（parse）")
    class RoundTrip {

        @Test
        @DisplayName("✅ 定义全文往返后哈希不变")
        void roundTripPreservesHash() {
            ApprovalScope scope = new ApprovalScope("tool",
                    Map.of("to", eq("a@example.com"),
                            "amount", new ApprovalScope.Constraint("lte", 100),
                            "tags", new ApprovalScope.Constraint("in", List.of("x", "y"))),
                    Set.of("to", "amount"),
                    Map.of("limit", 10),
                    Set.of("tags"));

            ApprovalScope parsed = ApprovalScope.parse(scope.canonicalText());

            assertThat(parsed.hash()).isEqualTo(scope.hash());
            assertThat(parsed.toolId()).isEqualTo("tool");
            assertThat(parsed.effectfulParams()).containsExactlyInAnyOrder("to", "amount");
            assertThat(parsed.setValuedParams()).containsExactly("tags");
        }

        @Test
        @DisplayName("⚠️ 约束值含空格/换行 → 往返不破坏（JSON 无歧义）")
        void valuesWithWhitespaceSurvive() {
            ApprovalScope scope = ApprovalScope.of("tool",
                    Map.of("note", eq("第一行\n第二行 with spaces")));

            ApprovalScope parsed = ApprovalScope.parse(scope.canonicalText());

            assertThat(parsed.hash()).isEqualTo(scope.hash());
            assertThat(parsed.constraints().get("note").value())
                    .isEqualTo("第一行\n第二行 with spaces");
        }

        @Test
        @DisplayName("⚠️ 数字类型往返保留（1 不得变成字符串 \"1\"）")
        void numericTypeSurvivesRoundTrip() {
            ApprovalScope scope = ApprovalScope.of("tool", Map.of("amount", eq(100)));

            ApprovalScope parsed = ApprovalScope.parse(scope.canonicalText());

            assertThat(parsed.constraints().get("amount").value())
                    .as("⚠️ 数字若退化成字符串，与 Double 实参比较会误判不等 → 范围重验失效")
                    .isInstanceOf(Number.class);
            assertThat(parsed.violatedConstraints(Map.of("amount", 100.0d)))
                    .as("100 与 100.0 必须判为满足同一约束")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️ 字符串 \"1\" 不得被当成数字 1")
        void numericStringStaysString() {
            ApprovalScope scope = ApprovalScope.of("tool", Map.of("code", eq("1")));

            ApprovalScope parsed = ApprovalScope.parse(scope.canonicalText());

            assertThat(parsed.constraints().get("code").value()).isInstanceOf(String.class);
        }

        @Test
        @DisplayName("⚠️ 定义损坏 → 抛异常，绝不返回空范围")
        void brokenDefinitionThrows() {
            assertThatThrownBy(() -> ApprovalScope.parse("not-json"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ApprovalScope.parse("{\"op\":\"eq\"}"))
                    .as("⚠️ 缺少 tool 的定义必须拒绝（空范围 = 无约束 = 形同虚设）")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("范围重验（消费时）")
    class Revalidation {

        @Test
        @DisplayName("✅ 落在范围内 → 无违反项")
        void satisfiedScopePasses() {
            ApprovalScope scope = ApprovalScope.of("tool",
                    Map.of("to", eq("a@example.com"),
                            "amount", new ApprovalScope.Constraint("lte", 100)));

            assertThat(scope.violatedConstraints(
                    Map.of("to", "a@example.com", "amount", 50))).isEmpty();
        }

        @Test
        @DisplayName("⚠️ 参数被替换 → 违反 eq")
        void replacedParameterViolatesEq() {
            ApprovalScope scope = ApprovalScope.of("tool", Map.of("to", eq("a@example.com")));

            assertThat(scope.violatedConstraints(Map.of("to", "b@example.com")))
                    .as("⚠️ 参数替换必须被范围重验捕获（防参数替换重放）")
                    .containsExactly("to");
        }

        @Test
        @DisplayName("⚠️ 缺失必填参数 → 违反")
        void missingRequiredParameterViolates() {
            ApprovalScope scope = ApprovalScope.of("tool", Map.of("to", eq("a@example.com")));

            assertThat(scope.violatedConstraints(Map.of())).containsExactly("to");
        }

        @Test
        @DisplayName("⚠️ in / neq / exists / not_exists")
        void allOperatorsBehave() {
            assertThat(ApprovalScope.of("t", Map.of("x",
                    new ApprovalScope.Constraint("in", List.of("a", "b"))))
                    .violatedConstraints(Map.of("x", "a"))).isEmpty();
            assertThat(ApprovalScope.of("t", Map.of("x",
                    new ApprovalScope.Constraint("in", List.of("a", "b"))))
                    .violatedConstraints(Map.of("x", "c"))).containsExactly("x");
            assertThat(ApprovalScope.of("t", Map.of("x",
                    new ApprovalScope.Constraint("neq", "a")))
                    .violatedConstraints(Map.of("x", "a"))).containsExactly("x");
            assertThat(ApprovalScope.of("t", Map.of("x",
                    new ApprovalScope.Constraint("exists", null)))
                    .violatedConstraints(Map.of())).containsExactly("x");
            assertThat(ApprovalScope.of("t", Map.of("x",
                    new ApprovalScope.Constraint("not_exists", null)))
                    .violatedConstraints(Map.of("x", 1))).containsExactly("x");
        }

        @Test
        @DisplayName("⚠️ 未知算子 → 判为违反（fail-closed）")
        void unknownOperatorFailsClosed() {
            ApprovalScope scope = ApprovalScope.of("t",
                    Map.of("x", new ApprovalScope.Constraint("matches", ".*")));

            assertThat(scope.violatedConstraints(Map.of("x", "anything")))
                    .as("⚠️ 未知算子必须判为不满足 —— 忽略等于把范围校验降级为空操作")
                    .containsExactly("x");
        }
    }

    @Nested
    @DisplayName("绑定服务（双哈希）")
    class BindingService {

        @Test
        @DisplayName("⚠️ 参数被替换 → APPROVAL_SCOPE_MISMATCH")
        void replacedArgumentIsRejected() {
            ApprovalBindingService service = new ApprovalBindingService();
            ApprovalScope scope = ApprovalScope.of("tool", Map.of("to", eq("a@example.com")));

            ApprovalBindingService.Binding binding =
                    service.generate(scope, Map.of("to", "a@example.com"));

            ApprovalBindingService.Verdict verdict = service.verify(scope, binding,
                    Map.of("to", "b@example.com"));

            assertThat(verdict.matched()).isFalse();
            assertThat(verdict.reasonCode())
                    .isEqualTo(cn.studykid.multiagent.core.cap.ReasonCode
                            .APPROVAL_SCOPE_MISMATCH);
        }

        @Test
        @DisplayName("✅ 参数未变 → 匹配")
        void unchangedArgumentMatches() {
            ApprovalBindingService service = new ApprovalBindingService();
            ApprovalScope scope = ApprovalScope.of("tool", Map.of("to", eq("a@example.com")));

            ApprovalBindingService.Binding binding =
                    service.generate(scope, Map.of("to", "a@example.com"));

            assertThat(service.verify(scope, binding, Map.of("to", "a@example.com")).matched())
                    .isTrue();
        }

        @Test
        @DisplayName("⚠️ 范围定义被改 → APPROVAL_SCOPE_MISMATCH")
        void tamperedScopeIsRejected() {
            ApprovalBindingService service = new ApprovalBindingService();
            ApprovalScope granted = ApprovalScope.of("tool", Map.of("to", eq("a@example.com")));
            ApprovalScope tampered = ApprovalScope.of("tool", Map.of("to", eq("b@example.com")));

            ApprovalBindingService.Binding binding =
                    service.generate(granted, Map.of("to", "a@example.com"));

            ApprovalBindingService.Verdict verdict = service.verify(tampered, binding,
                    Map.of("to", "a@example.com"));

            assertThat(verdict.matched()).isFalse();
            assertThat(verdict.reasonCode())
                    .isEqualTo(cn.studykid.multiagent.core.cap.ReasonCode
                            .APPROVAL_SCOPE_MISMATCH);
        }
    }
}
