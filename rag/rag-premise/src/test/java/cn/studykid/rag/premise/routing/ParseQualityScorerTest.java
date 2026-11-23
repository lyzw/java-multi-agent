package cn.studykid.rag.premise.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.premise.model.PageSignals;

class ParseQualityScorerTest {

    private final ParseQualityScorer scorer =
            new ParseQualityScorer(RoutingConfig.productionDefault());

    @Test
    @DisplayName("干净页质量分高；复杂页分数被公式/表格密度拉低（但由独立规则升级）")
    void scoreReflectsComplexity() {
        double clean = scorer.score(new PageSignals(1, 3, 900, 0, 0, 0, 0.95, 0.0));
        double complex = scorer.score(new PageSignals(2, 6, 900, 0, 2, 3, 0.95, 0.0));
        assertThat(clean).isGreaterThan(0.9);
        assertThat(complex).isLessThan(clean).isGreaterThan(scorer.config().minQualityScore());
        // 复杂页由 PageRouter 的 formulaCount/tableCount 规则强制升级，
        // 不依赖质量分阈值——这里只验证密度确实反映在分数里。
    }

    @Test
    @DisplayName("文本稀疏 + 低置信 → 质量分低于阈值，触发升级兜底")
    void sparseAndLowConfidenceBelowThreshold() {
        double poor = scorer.score(new PageSignals(1, 2, 60, 1, 0, 0, 0.50, 0.0));
        assertThat(poor).isLessThan(scorer.config().minQualityScore());
        assertThat(scorer.belowThreshold(poor)).isTrue();
    }

    @Test
    @DisplayName("文本层覆盖率不足拉低分数")
    void scoreReflectsTextCoverage() {
        double sparse = scorer.score(new PageSignals(1, 1, 60, 0, 0, 0, 0.95, 0.0));
        double full = scorer.score(new PageSignals(2, 1, 600, 0, 0, 0, 0.95, 0.0));
        assertThat(sparse).isLessThan(full);
        assertThat(scorer.belowThreshold(sparse)).isTrue();
        assertThat(scorer.belowThreshold(full)).isFalse();
    }

    @Test
    @DisplayName("置信度未知取中性 0.5，不冒充高分也不一棍子打死")
    void unknownConfidenceIsNeutral() {
        double unknown = scorer.score(new PageSignals(1, 1, 600, 0, 0, 0, Double.NaN, 1.0));
        double low = scorer.score(new PageSignals(2, 1, 600, 0, 0, 0, 0.3, 0.0));
        double high = scorer.score(new PageSignals(3, 1, 600, 0, 0, 0, 0.95, 0.0));
        assertThat(unknown).isBetween(low, high);
    }

    @Test
    @DisplayName("分数恒在 [0,1]：极端输入被夹紧")
    void scoreIsClamped() {
        double zero = scorer.score(new PageSignals(1, 0, 0, 0, 0, 0, 0.0, 0.0));
        double one = scorer.score(new PageSignals(2, 1, 10000, 0, 0, 0, 1.0, 0.0));
        assertThat(zero).isGreaterThanOrEqualTo(0.0).isLessThanOrEqualTo(1.0);
        assertThat(one).isGreaterThanOrEqualTo(0.0).isLessThanOrEqualTo(1.0);
    }
}
