package cn.studykid.multiagent.core.config;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.jspecify.annotations.NonNull;

/**
 * 单个模型的计价规则（P0-3b · 成本入账）。
 *
 * <p><b>为什么需要它</b>：此前 {@code usage_record} 里记的
 * {@code cost_amount} 是「工具调用次数 × ¥0.50」的<b>固定代理值</b>，
 * {@code input_tokens} / {@code output_tokens} 两列<b>无任何写入方</b> ——
 * 于是「单 Run 的模型开销」这件事在账上完全不存在，
 * 「单任务平均费用」（P7 COST-3）这类指标算出来的是错的。
 *
 * <p><b>计价口径</b>：按<b>每千 token</b>计价（{@code per-1k}），
 * 与主流模型供应商的公开计价单位一致，且避免把单价写成极小的十进制数
 * （{@code 0.000015} 这种数在配置里既难读又易错位）。
 *
 * <p><b>⚠️ 金额一律按 {@link RoundingMode#HALF_UP} 保留 6 位小数</b>：
 * 与 {@code usage_record.cost_amount numeric(18,6)} 的列精度一致，
 * 避免「DB 截断、内存是另一个数」的对不上账。
 *
 * @param inputPerThousandTokens  每千输入 token 单价（元）
 * @param outputPerThousandTokens 每千输出 token 单价（元）
 */
public record ModelPricing(
        @NonNull BigDecimal inputPerThousandTokens,
        @NonNull BigDecimal outputPerThousandTokens
) {

    /** token → 金额的换算基数（每千 token）。 */
    private static final BigDecimal PER_THOUSAND = new BigDecimal("1000");

    /** 金额精度：与 {@code usage_record.cost_amount numeric(18,6)} 对齐。 */
    private static final int SCALE = 6;

    public ModelPricing {
        if (inputPerThousandTokens == null || outputPerThousandTokens == null) {
            throw new IllegalArgumentException("模型单价不能为空");
        }
        if (inputPerThousandTokens.signum() < 0 || outputPerThousandTokens.signum() < 0) {
            throw new IllegalArgumentException("模型单价不能为负: input="
                    + inputPerThousandTokens + ", output=" + outputPerThousandTokens);
        }
    }

    /**
     * 计算一次模型调用的真实成本。
     *
     * <p>⚠️ <b>token 数为 0 时成本为 0</b>：这不是「免费」，
     * 而是「本次调用没有可计量的 token」——
     * 上游若未回传用量（{@code ModelUsage} 为 0），账上只能记 0，
     * 把它当成「已知价格下花了 0 元」是<b>事实陈述</b>，
     * 而把它当成「免费所以可以随便调」是<b>错误推理</b>，两者必须分开。
     *
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     * @return 成本金额（元，6 位小数）
     */
    public @NonNull BigDecimal cost(long inputTokens, long outputTokens) {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token 数不能为负: input="
                    + inputTokens + ", output=" + outputTokens);
        }
        BigDecimal in = inputPerThousandTokens
                .multiply(BigDecimal.valueOf(inputTokens))
                .divide(PER_THOUSAND, SCALE, RoundingMode.HALF_UP);
        BigDecimal out = outputPerThousandTokens
                .multiply(BigDecimal.valueOf(outputTokens))
                .divide(PER_THOUSAND, SCALE, RoundingMode.HALF_UP);
        return in.add(out).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
