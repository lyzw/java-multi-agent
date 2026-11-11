package cn.studykid.multiagent.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.config.ModelPricingPolicy;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;

/** 装配层：默认 fail-closed、开关切换后是真实实现，配置错误显式暴露。 */
@DisplayName("L2 · 评估层装配")
class EvaluationConfigurationTest {

    private final EvaluationConfiguration config = new EvaluationConfiguration();

    @Test
    @DisplayName("⛔ 默认开关关闭：装配 NoReferenceAnswerSource，且不注册判定器 bean 由条件注解保证")
    void defaultUsesNoReferenceSource() {
        ReferenceAnswerSource source =
                config.referenceAnswerSource(false, "classpath:agent-eval/reference-answers.json");

        assertThat(source).isInstanceOf(NoReferenceAnswerSource.class);
        assertThat(source.resolve("Q-MATH-001")).isEmpty();
    }

    @Test
    @DisplayName("✅ 判分源开关打开：切到本地题库文件源，且真的能取到标准解析")
    void enabledUsesLocalFileSource() {
        ReferenceAnswerSource source =
                config.referenceAnswerSource(true, "classpath:agent-eval/reference-answers-test.json");

        assertThat(source).isInstanceOf(LocalFileReferenceAnswerSource.class);
        assertThat(source.resolve("Q-MATH-001")).isPresent();
    }

    @Test
    @DisplayName("✅ 评估科目日预算按配置装配（与业务预算完全无关）")
    void budgetUsesConfiguredLimit() {
        EvaluationCostBudget budget = config.evaluationCostBudget("1.25");

        assertThat(budget.snapshot().dailyLimit()).isEqualByComparingTo(new BigDecimal("1.25"));
        assertThat(budget.snapshot().spent()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("✅ 判定器 bean 是真实 MiniMax 判定器，不是 NoOp 占位")
    void judgeIsRealImplementation() {
        Map<String, Object> price = new LinkedHashMap<>();
        price.put("input-per-1k-tokens", "0.002100");
        price.put("output-per-1k-tokens", "0.008400");
        ModelPricingPolicy pricing = ModelPricingPolicy.of(Map.of("MiniMax-M2.5", price));

        AttributionJudge judge = config.attributionJudge(
                mock(ChatStreamGateway.class), pricing, config.evaluationCostBudget("1.00"),
                "MiniMax-M2.5", 0.0, 256, 10, 0.75);

        assertThat(judge).isInstanceOf(ModelAttributionJudge.class);
    }

    @Test
    @DisplayName("⛔ 日预算配置非法：装配期显式失败，不静默落回默认值")
    void invalidBudgetFailsFast() {
        assertThatThrownBy(() -> config.evaluationCostBudget("not-a-number"))
                .isInstanceOf(NumberFormatException.class);
    }
}
