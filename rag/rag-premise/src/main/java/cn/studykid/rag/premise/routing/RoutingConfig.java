package cn.studykid.rag.premise.routing;

import org.jspecify.annotations.NonNull;

/**
 * 页级路由与质量分的阈值配置（纯值对象；Spring 侧由装配方读取配置后构造）。
 *
 * <p><b>⚠️ 阈值是初始工程值，不是标定结论</b>：须在真实教材基准集上校准
 * （D08 SG-2）；调阈值必须留痕并重新跑路由反证，不得为了让某册通过而临时放水。
 *
 * <p>⚠️ 顶层类型（不嵌套）。
 *
 * @param minTextCharsForScan     低于该文本字符数且含图 → 疑似扫描件（隔离）
 * @param minConfidence           低于该置信度 → 复杂页升级
 * @param maxUnknownConfidenceRatio 未知置信度占比高于该值 → 按最严格档位处理
 * @param targetTextChars         质量分的文本充分量（达到即 textScore=1）
 * @param minQualityScore         质量分低于该值 → 升级或隔离
 */
public record RoutingConfig(
        int minTextCharsForScan,
        double minConfidence,
        double maxUnknownConfidenceRatio,
        int targetTextChars,
        double minQualityScore) {

    public RoutingConfig {
        if (minTextCharsForScan < 0 || targetTextChars <= 0) {
            throw new IllegalArgumentException("minTextCharsForScan 必须 >=0，targetTextChars 必须 >0");
        }
        if (Double.isNaN(minConfidence) || minConfidence < 0.0 || minConfidence > 1.0) {
            throw new IllegalArgumentException("minConfidence 必须在 [0,1]");
        }
        if (Double.isNaN(maxUnknownConfidenceRatio) || maxUnknownConfidenceRatio < 0.0
                || maxUnknownConfidenceRatio > 1.0) {
            throw new IllegalArgumentException("maxUnknownConfidenceRatio 必须在 [0,1]");
        }
        if (Double.isNaN(minQualityScore) || minQualityScore < 0.0 || minQualityScore > 1.0) {
            throw new IllegalArgumentException("minQualityScore 必须在 [0,1]");
        }
    }

    /** @return 初始工程阈值（待真实教材基准集标定） */
    public static @NonNull RoutingConfig productionDefault() {
        return new RoutingConfig(40, 0.80, 0.50, 600, 0.55);
    }
}
