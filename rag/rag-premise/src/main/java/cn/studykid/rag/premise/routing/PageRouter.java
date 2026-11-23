package cn.studykid.rag.premise.routing;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.premise.model.PageRoute;
import cn.studykid.rag.premise.model.PageSignals;
import cn.studykid.rag.premise.model.RouteProfile;

/**
 * 页级分类路由（D27 §4.1 / D28 P1-1）：干净页走轻量、复杂页升级、扫描件隔离。
 *
 * <p><b>⚠️ 三条不可协商的纪律</b>：
 * <ol>
 *   <li><b>页级而非整册一刀切</b>：每页独立决策，不把整册最复杂页的档位套给所有页；</li>
 *   <li><b>不确定的页按最严格档位</b>：置信度缺失/低置信/信号冲突时一律升级或隔离，
 *       ⛔ 禁止「拿不准就用轻量通道碰碰运气」——那正是泄题与错位引用的来源；</li>
 *   <li><b>升级不可逆</b>：本类只输出首次决策；{@code RoutingParseBackend} 合并时
 *       保证已升级页不被轻量结果覆盖（见其类注释与反证测试）。</li>
 * </ol>
 *
 * <p><b>⚠️ 纯 Java、零框架依赖</b>：输入是已经统计好的 {@link PageSignals}，
 * 由调用方从轻量通道产物推导（见 {@link PageSignals#of}）。这样路由规则可单测、
 * 可反证，不需要 PDF 服务或 Spring 上下文。
 */
public final class PageRouter {

    private final RoutingConfig config;
    private final ParseQualityScorer scorer;

    public PageRouter(@NonNull RoutingConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        this.config = config;
        this.scorer = new ParseQualityScorer(config);
    }

    /**
     * 对每页做出路由决策。
     *
     * @param signals 页级信号（按页码升序；允许为空）
     * @return 与输入等长、同序的路由决策
     */
    public @NonNull List<PageRoute> route(@NonNull List<PageSignals> signals) {
        if (signals == null) {
            throw new IllegalArgumentException("signals 不能为空");
        }
        List<PageRoute> out = new ArrayList<>(signals.size());
        for (PageSignals signal : signals) {
            out.add(routeOne(signal));
        }
        return List.copyOf(out);
    }

    /**
     * 单页决策。判定顺序即严格度顺序：先排除扫描件，再排除复杂页，最后才放行轻量。
     *
     * <p>顺序不能颠倒——若先判「文本少 → 复杂页升级」，扫描件会被送去 VLM 而非隔离，
     * 违反 D29 DEC-P1「扫描件不静默当文本 PDF 处理」。
     */
    private @NonNull PageRoute routeOne(@NonNull PageSignals signal) {
        double quality = scorer.score(signal);
        int page = signal.pageNumber();

        // ① 扫描件嫌疑：文本层稀疏 + 有图（或无块）。
        //    注意 blockCount==0 也是扫描件信号：后端可能整页提不出任何块。
        if (signal.textCharCount() < config.minTextCharsForScan()
                && (signal.figureCount() > 0 || signal.blockCount() == 0)) {
            return new PageRoute(page, RouteProfile.QUARANTINE,
                    "scan_suspected:text_chars=" + signal.textCharCount()
                            + ",figures=" + signal.figureCount()
                            + ",blocks=" + signal.blockCount(),
                    0.95, quality);
        }

        // ② 置信度完全缺失：信号不足以放行 → 按最严格档位升级（不是隔离，
        //    因为可能是正常页但后端未给置信度；升级后由 VLM 结果决定）。
        if (signal.blockCount() > 0 && !signal.hasConfidence()) {
            return new PageRoute(page, RouteProfile.HEAVY,
                    "confidence_unknown:ratio=" + signal.unknownConfidenceRatio(),
                    0.90, quality);
        }

        // ③ 未知置信度占比过高：同样按最严格档位处理。
        if (signal.unknownConfidenceRatio() > config.maxUnknownConfidenceRatio()) {
            return new PageRoute(page, RouteProfile.HEAVY,
                    "unknown_confidence_ratio=" + signal.unknownConfidenceRatio(),
                    0.85, quality);
        }

        // ④ 低置信。
        if (signal.hasConfidence() && signal.meanConfidence() < config.minConfidence()) {
            return new PageRoute(page, RouteProfile.HEAVY,
                    "low_confidence=" + signal.meanConfidence(),
                    0.85, quality);
        }

        // ⑤ 复杂块密集：公式 / 表格 / 多图。
        if (signal.formulaCount() > 0 || signal.tableCount() > 0) {
            return new PageRoute(page, RouteProfile.HEAVY,
                    "complex_blocks:formula=" + signal.formulaCount()
                            + ",table=" + signal.tableCount(),
                    0.90, quality);
        }
        if (signal.figureCount() >= 2) {
            return new PageRoute(page, RouteProfile.HEAVY,
                    "figure_dense=" + signal.figureCount(),
                    0.80, quality);
        }

        // ⑥ 质量分不足（文本层覆盖差但还没到扫描件程度）：升级复核。
        if (quality < config.minQualityScore()) {
            return new PageRoute(page, RouteProfile.HEAVY,
                    "low_quality=" + quality, 0.70, quality);
        }

        return new PageRoute(page, RouteProfile.LIGHTWEIGHT, "clean", 0.95, quality);
    }

    /** @return 质量分计算器（与路由共用同一份阈值） */
    public @NonNull ParseQualityScorer scorer() {
        return scorer;
    }

    /** @return 当前阈值（留痕 / 自检用） */
    public @NonNull RoutingConfig config() {
        return config;
    }
}
