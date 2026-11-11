package cn.studykid.multiagent.context;

import org.jspecify.annotations.NonNull;

/**
 * 四级水位线判定（《方案》§2.3，M5-a）。纯函数，只按 {@code usage = before / window} 分级。
 *
 * <p>⚠️ <b>阈值注入而非硬编码</b>：{@link Thresholds} 由调用方显式传入。
 * 接线层（M7）必须用 {@code AgentContextProperties} 的
 * {@code tierSnipRatio / tierSummaryRatio / minNewTokensRatio} 真实值构造本对象；
 * ⛔ 不得用 {@link Thresholds#defaults()} 长期旁路 yml，否则
 * 「改了 {@code AGENT_CONTEXT_TIER_SNIP} 却不生效」是静默失真。
 * {@code defaults()} 仅供单测与算法自洽，值与 yml / properties 逐字对齐。
 *
 * <p>⚠️ <b>W2 分母是 triggerRatio，W1/W3 来自全局阈值</b>：
 * {@code triggerRatio} 是 Agent 级旋钮（来自快照策略），snip/summary 是全局熔断线。
 * {@code targetRatio < triggerRatio} 已由 {@link ContextPolicyResolver} 在 Run 创建期拒绝反转。
 *
 * <p>⚠️ <b>判定顺序固定</b>：HARD → W3 → W2 → W1 → W0，先命中即返回，
 * 绝不让高水位被低水位的分支吞掉。
 */
public final class WaterLevel {

    public enum Tier {
        /** 不压缩（仅计量）。 */
        W0,
        /** 老 TOOL 结果可逆外置（零 LLM）。 */
        W1,
        /** 整轮裁剪 + 占位符（零 LLM）。 */
        W2,
        /** 增量结构化摘要（一次 LLM）。 */
        W3,
        /** 固定块溢出 / 超总窗：fail-closed。 */
        HARD
    }

    /**
     * 水位触发阈值（全部为 0..1 比例）。
     *
     * @param snipRatio          W1 触发线（yml {@code AGENT_CONTEXT_TIER_SNIP}，默认 0.60）
     * @param summaryRatio       W3 触发线（yml {@code AGENT_CONTEXT_TIER_SUMMARY}，默认 0.90）
     * @param minNewTokensRatio  压缩后再次触发前的新增 token 冷却比例（默认 0.10）
     */
    public record Thresholds(double snipRatio, double summaryRatio, double minNewTokensRatio) {
        public Thresholds {
            if (!(snipRatio > 0 && snipRatio < 1.0)) {
                throw new IllegalArgumentException("snipRatio 必须在 (0,1): " + snipRatio);
            }
            if (!(summaryRatio > 0 && summaryRatio < 1.0)) {
                throw new IllegalArgumentException("summaryRatio 必须在 (0,1): " + summaryRatio);
            }
            if (snipRatio >= summaryRatio) {
                throw new IllegalArgumentException(
                        "snipRatio 必须严格小于 summaryRatio: " + snipRatio + " >= " + summaryRatio);
            }
            if (!(minNewTokensRatio >= 0 && minNewTokensRatio < 1.0)) {
                throw new IllegalArgumentException(
                        "minNewTokensRatio 必须在 [0,1): " + minNewTokensRatio);
            }
        }

        /** 值与 {@code application.yml} / {@code AgentContextProperties} 逐字对齐。 */
        public static @NonNull Thresholds defaults() {
            return new Thresholds(0.60, 0.90, 0.10);
        }
    }

    private WaterLevel() {
    }

    /**
     * 按全窗口用量判定水位。
     *
     * @param beforeTokens 全窗口估算（system + tools + memory + rag + history + current + guards）
     * @param window       有效输入硬顶（{@code ContextBudget.window}）
     * @param policy       已裁决策略（提供 {@code triggerRatio}）
     * @param thresholds   全局水位阈值
     * @return 命中水位（先命中高水位）
     */
    public static @NonNull Tier decide(int beforeTokens, int window,
                                       @NonNull ContextPolicy policy,
                                       @NonNull Thresholds thresholds) {
        if (window <= 0) {
            return Tier.HARD;
        }
        double usage = (double) beforeTokens / window;
        // ⚠️ 顺序固定：HARD → W3 → W2 → W1 → W0。
        if (usage >= 1.0) {
            return Tier.HARD;
        }
        if (usage >= thresholds.summaryRatio()) {
            return Tier.W3;
        }
        if (usage >= policy.triggerRatio()) {
            return Tier.W2;
        }
        if (usage >= thresholds.snipRatio()) {
            return Tier.W1;
        }
        return Tier.W0;
    }
}
