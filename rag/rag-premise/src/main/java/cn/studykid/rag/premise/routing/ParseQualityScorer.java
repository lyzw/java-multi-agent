package cn.studykid.rag.premise.routing;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.premise.model.PageSignals;

/**
 * 轻量通道的页级解析质量分（D28 P1-2 契约）。
 *
 * <p><b>⚠️ 三个输入维度</b>（与 D28 组件契约一致）：文本层覆盖率、公式/表格密度、图像占比。
 * 另把置信度计入——「后端自己说不准」是比块数更直接的质量信号。
 *
 * <p><b>⚠️ 分数是路由输入，不是对外质量指标</b>：它衡量的是<b>轻量通道这一遍解析</b>
 * 是否可信，用于决定升级/隔离；不得与检索侧的 0.38 相关性门禁混为一谈，
 * 也不得作为 D08 SG 指标上报。
 *
 * <p>公式（确定性、可单测）：
 * <pre>
 *   textScore  = min(1, textCharCount / targetTextChars)
 *   confScore  = hasConfidence ? meanConfidence : 0.5
 *   complexity = min(1, table*0.30 + formula*0.30 + figure*0.15)
 *   score      = clamp(0.50*textScore + 0.35*confScore + 0.15*(1-complexity), 0, 1)
 * </pre>
 *
 * <p>未知置信度取 0.5（中性值，不偏袒放行也不一棍子打死）；
 * 真正的「未知占比过高」由 {@link PageRouter} 按最严格档位兜底，
 * <b>不靠分数小改动蒙混过关</b>。
 */
public final class ParseQualityScorer {

    private final RoutingConfig config;

    public ParseQualityScorer(@NonNull RoutingConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        this.config = config;
    }

    /**
     * 计算页级质量分。
     *
     * @param signal 页级信号
     * @return [0,1] 的分数
     */
    public double score(@NonNull PageSignals signal) {
        if (signal == null) {
            throw new IllegalArgumentException("signal 不能为空");
        }
        double textScore = Math.min(1.0,
                (double) signal.textCharCount() / config.targetTextChars());
        double confScore = signal.hasConfidence() ? signal.meanConfidence() : 0.5;
        double complexity = Math.min(1.0,
                signal.tableCount() * 0.30
                        + signal.formulaCount() * 0.30
                        + signal.figureCount() * 0.15);
        double raw = 0.50 * textScore + 0.35 * confScore + 0.15 * (1.0 - complexity);
        return Math.max(0.0, Math.min(1.0, raw));
    }

    /** @return 低于阈值需升级或隔离 */
    public boolean belowThreshold(double score) {
        return score < config.minQualityScore();
    }

    /** @return 当前阈值 */
    public @NonNull RoutingConfig config() {
        return config;
    }
}
