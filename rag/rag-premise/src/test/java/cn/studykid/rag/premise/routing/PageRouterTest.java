package cn.studykid.rag.premise.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.premise.model.PageRoute;
import cn.studykid.rag.premise.model.PageSignals;
import cn.studykid.rag.premise.model.RouteProfile;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.model.PremiseBlock;

/**
 * 页级路由反证测试（D27 §4.1 / D28 P1-1）。
 *
 * <p>每条规则都有「正向 + 反向」：撤掉任何一个判定分支，对应用例必须转红。
 */
class PageRouterTest {

    private final RoutingConfig config = RoutingConfig.productionDefault();
    private final PageRouter router = new PageRouter(config);

    private static PageSignals signals(int page, int chars, int figures, int tables,
                                       int formulas, Double confidence) {
        int blocks = 1 + figures + tables + formulas;
        double mean = confidence == null ? Double.NaN : confidence;
        double unknown = confidence == null ? 1.0 : 0.0;
        return new PageSignals(page, blocks, chars, figures, tables, formulas, mean, unknown);
    }

    @Test
    @DisplayName("干净页走轻量通道：文本充分、置信足够、无复杂块")
    void cleanPageGoesLightweight() {
        PageRoute route = router.route(List.of(signals(3, 800, 0, 0, 0, 0.95))).get(0);
        assertThat(route.profile()).isEqualTo(RouteProfile.LIGHTWEIGHT);
        assertThat(route.reason()).isEqualTo("clean");
        assertThat(route.qualityScore()).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("公式/表格密集页强制升级，reason 可解释")
    void formulaAndTablePagesEscalate() {
        PageRoute formula = router.route(List.of(signals(12, 900, 0, 0, 2, 0.95))).get(0);
        assertThat(formula.profile()).isEqualTo(RouteProfile.HEAVY);
        assertThat(formula.reason()).contains("formula=2");

        PageRoute table = router.route(List.of(signals(13, 900, 0, 1, 0, 0.95))).get(0);
        assertThat(table.profile()).isEqualTo(RouteProfile.HEAVY);
        assertThat(table.reason()).contains("table=1");
        assertThat(table.traceLine()).contains("profile=HEAVY").contains("reason=");
    }

    @Test
    @DisplayName("疑似扫描件走隔离而不是升级（D29 DEC-P1）")
    void scanSuspectedPageQuarantines() {
        PageRoute route = router.route(List.of(signals(7, 12, 3, 0, 0, 0.95))).get(0);
        assertThat(route.profile()).isEqualTo(RouteProfile.QUARANTINE);
        assertThat(route.reason()).startsWith("scan_suspected:");

        // 完全无块也是扫描件信号
        PageSignals empty = new PageSignals(8, 0, 0, 0, 0, 0, Double.NaN, 0.0);
        assertThat(router.route(List.of(empty)).get(0).profile())
                .isEqualTo(RouteProfile.QUARANTINE);
    }

    @Test
    @DisplayName("置信度未知按最严格档位：不拿不准就放行")
    void unknownConfidenceEscalates() {
        PageRoute route = router.route(List.of(signals(9, 800, 0, 0, 0, null))).get(0);
        assertThat(route.profile()).isEqualTo(RouteProfile.HEAVY);
        assertThat(route.reason()).startsWith("confidence_unknown:");
    }

    @Test
    @DisplayName("低置信与高未知占比都触发升级")
    void lowConfidenceAndHighUnknownRatioEscalate() {
        PageRoute low = router.route(List.of(signals(10, 800, 0, 0, 0, 0.62))).get(0);
        assertThat(low.profile()).isEqualTo(RouteProfile.HEAVY);
        assertThat(low.reason()).isEqualTo("low_confidence=0.62");

        PageSignals mixed = new PageSignals(11, 10, 800, 0, 0, 0, 0.97, 0.8);
        PageRoute unknown = router.route(List.of(mixed)).get(0);
        assertThat(unknown.profile()).isEqualTo(RouteProfile.HEAVY);
        assertThat(unknown.reason()).startsWith("unknown_confidence_ratio=");
    }

    @Test
    @DisplayName("多图页升级；单图干净页不升级（避免误伤）")
    void figureDensityThreshold() {
        assertThat(router.route(List.of(signals(20, 800, 3, 0, 0, 0.95))).get(0).profile())
                .isEqualTo(RouteProfile.HEAVY);
        assertThat(router.route(List.of(signals(21, 800, 1, 0, 0, 0.95))).get(0).profile())
                .isEqualTo(RouteProfile.LIGHTWEIGHT);
    }

    @Test
    @DisplayName("页级独立决策：整册中只升级复杂页，不把最复杂档位套给所有页")
    void routingIsPerPage() {
        List<PageRoute> routes = router.route(List.of(
                signals(1, 800, 0, 0, 0, 0.95),
                signals(2, 900, 0, 0, 3, 0.95),
                signals(3, 12, 4, 0, 0, 0.95),
                signals(4, 700, 0, 0, 0, 0.91)));
        assertThat(routes).extracting(PageRoute::profile).containsExactly(
                RouteProfile.LIGHTWEIGHT, RouteProfile.HEAVY,
                RouteProfile.QUARANTINE, RouteProfile.LIGHTWEIGHT);
        assertThat(routes).extracting(PageRoute::pageNumber).containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("严格度有序：升级不可逆的比较基础")
    void strictnessOrdering() {
        assertThat(RouteProfile.LIGHTWEIGHT.strictness())
                .isLessThan(RouteProfile.HEAVY.strictness());
        assertThat(RouteProfile.HEAVY.strictness())
                .isLessThan(RouteProfile.QUARANTINE.strictness());
        assertThat(RouteProfile.HEAVY.escalates()).isTrue();
        assertThat(RouteProfile.QUARANTINE.quarantines()).isTrue();
        assertThat(RouteProfile.LIGHTWEIGHT.escalates()).isFalse();
    }

    @Test
    @DisplayName("PageSignals 从真实块推导：文本/图/表/公式计数与置信度口径")
    void pageSignalsFromBlocks() {
        LayoutBlock text = new LayoutBlock(BlockType.PARAGRAPH, "一二三四五六七八", 5, 0,
                "章>节", null, false, 0, 8);
        LayoutBlock table = new LayoutBlock(BlockType.TABLE, "表格内容", 5, 0,
                "章>节", null, false, 8, 12);
        PageSignals derived = PageSignals.of(5, List.of(
                new PremiseBlock(text, null, 0.93),
                new PremiseBlock(table, null, Double.NaN)));
        assertThat(derived.textCharCount()).isEqualTo(12);
        assertThat(derived.tableCount()).isEqualTo(1);
        assertThat(derived.figureCount()).isZero();
        assertThat(derived.meanConfidence()).isEqualTo(0.93);
        assertThat(derived.unknownConfidenceRatio()).isEqualTo(0.5);
        assertThat(derived.hasConfidence()).isTrue();

        PageSignals noConfidence = PageSignals.of(6, List.of(
                PremiseBlock.fromLayoutBlock(text)));
        assertThat(noConfidence.hasConfidence()).isFalse();
        assertThat(noConfidence.meanConfidence()).isNaN();
    }

    @Test
    @DisplayName("不变量守卫：页码 1 起、比例在 [0,1]、reason 非空")
    void invariantsFailClosed() {
        assertThatThrownBy(() -> new PageSignals(0, 1, 10, 0, 0, 0, 0.9, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageSignals(1, 1, 10, 0, 0, 0, 0.9, 1.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageRoute(1, RouteProfile.HEAVY, "", 0.9, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageRoute(1, RouteProfile.HEAVY, "x", 1.2, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
