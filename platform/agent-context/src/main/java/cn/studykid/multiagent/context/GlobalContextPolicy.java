package cn.studykid.multiagent.context;

import org.jspecify.annotations.NonNull;

/**
 * 全局层策略（{@code agent.context.*} 的运行期投影，ADR-013 / 《方案》§3.2-C）。
 *
 * <p>⚠️ 与 Agent 级策略的分工：全局层只提供<b>总熔断、硬顶、兜底默认</b>，
 * 不提供触发比例等业务旋钮；Agent 级未声明 {@code generation_params.context}
 * 时由解析器产出 {@link ContextPolicy#defaults()}，两层不得互相派生默认值，
 * 否则「改了全局默认却对某些 Agent 不生效」会静默发生。
 *
 * <p>⚠️ 纯 record、无 Spring 绑定：组合根在 S2 由
 * {@code @ConfigurationProperties} 构造本对象后注入 Resolver。
 */
public record GlobalContextPolicy(
        /** 总熔断；false = 与「没有压缩功能」逐位一致（[D11] / G1）。 */
        boolean enabled,
        /** 全窗口输入硬顶，任何 Agent 配置都不得突破。 */
        int hardCeilingTokens,
        /** 模型目录未登记窗口事实时的输入回落值。 */
        int defaultMaxInputTokens,
        /** 能力未知时上调到的安全余量下限（R10）。 */
        double unknownCapabilityReserveRatio) {

    public GlobalContextPolicy {
        if (hardCeilingTokens <= 0) {
            throw new IllegalArgumentException("全局硬顶必须为正数");
        }
        if (defaultMaxInputTokens <= 0) {
            throw new IllegalArgumentException("全局默认输入上限必须为正数");
        }
    }

    /** 《方案》§3.2-C 的默认全局策略。 */
    public static @NonNull GlobalContextPolicy defaults() {
        return new GlobalContextPolicy(false, 128_000, 16_000, 0.10);
    }

    /** 影子期专用：硬顶与回落值不变，仅关闭总开关。 */
    public static @NonNull GlobalContextPolicy shadowDisabled() {
        return defaults();
    }
}
