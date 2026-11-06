package cn.studykid.multiagent.application.memory;

import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.memory.MemoryStrength;

/**
 * 跨 Run 记忆的运行期配置（P1）。
 *
 * <p><b>⚠️ 与 {@code RetentionPolicy} 的取舍不同，必须说清楚</b>：
 * 保留期缺失即拒绝启动（PIPL 两个方向都没有安全默认值）；而<b>记忆缺失
 * 可以安全地退化为"没有记忆"</b> —— 记忆是<b>增强</b>，不是合规义务。
 * 因此本类对 {@code enabled=false} 走"关闭"，而不是 fail-closed 拒绝启动。
 *
 * <p>但一旦 {@code enabled=true}，<b>所有数值项都必须真出现且落在区间内</b>：
 * 一个写着"已开启"却因为某项缺失而静默降级成"每次都没记忆"的系统，
 * 与"关闭"在外部完全无法区分 —— 这正是本项目反复治理的
 * "机制正确但从未接线"的变体。
 *
 * <p><b>⚠️ 为什么 {@code recall-min-strength} 只允许上调到
 * {@link MemoryStrength#MIN_STRENGTH} 以上</b>：把召回线调到自然遗忘线
 * （0.15）以下，等于"让即将被遗忘的记忆继续影响判断"——
 * 与"记忆会衰减"这个设计前提直接矛盾。
 */
public final class MemoryPolicy {

    /** 是否启用跨 Run 记忆。 */
    public static final String KEY_ENABLED = "agent.memory.enabled";

    /** 单次召回条数上限。 */
    public static final String KEY_RECALL_MAX_ITEMS = "agent.memory.recall-max-items";

    /** 单次召回的 token 上限。 */
    public static final String KEY_RECALL_MAX_TOKENS = "agent.memory.recall-max-tokens";

    /** 召回强度门槛。 */
    public static final String KEY_RECALL_MIN_STRENGTH = "agent.memory.recall-min-strength";

    /**
     * 裁剪预算下限（{@code ContextBudgetTrimmer} 的硬约束）。
     *
     * <p>⚠️ <b>这不是"建议值"，是防止 Run 全盘失败的必要夹紧</b>：
     * {@code ContextBudgetTrimmer.trim}（:127–130）在 {@code budget <= 0} 时
     * <b>直接抛异常</b>。而记忆预算是从上下文预算里<b>扣减</b>出来的 ——
     * 若记忆恰好占满全部预算，扣减结果就是 0，此后<b>每一次</b> Run
     * 都会在第一步抛 {@code IllegalArgumentException}。
     */
    public static final int MIN_TRIM_BUDGET = 2048;

    private MemoryPolicy() {
    }

    /**
     * 解析开关。
     *
     * <p>⚠️ 缺失视为<b>关闭</b>（而不是开启）：
     * 记忆会把学生历史数据注入模型上下文，缺配置时宁可不注入。
     *
     * @param raw 原始值
     * @return 是否启用
     */
    public static boolean parseEnabled(@Nullable Object raw) {
        if (raw == null) {
            return false;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return false;
        }
        if ("true".equalsIgnoreCase(s)) {
            return true;
        }
        if ("false".equalsIgnoreCase(s)) {
            return false;
        }
        throw new IllegalStateException(
                "记忆开关不是布尔值（fail-closed）：" + KEY_ENABLED + " = " + raw);
    }

    /**
     * 解析并校验召回条数上限。
     *
     * @param raw 原始值
     * @return 条数，落在 {@code [1, 20]}
     */
    public static int requireRecallMaxItems(@Nullable Object raw) {
        // ⚠️ 上限 20：超过 20 条历史弱点是噪声而不是信号 ——
        //    模型的注意力会被稀释，且 token 预算必然被记忆吃光。
        return (int) requireInt(raw, KEY_RECALL_MAX_ITEMS, 1, 20);
    }

    /**
     * 解析并校验召回 token 上限。
     *
     * @param raw 原始值
     * @return token 数，落在 {@code [64, 4000]}
     */
    public static int requireRecallMaxTokens(@Nullable Object raw) {
        // ⚠️ 上限 4000：与默认上下文预算 16000 相比，记忆最多占 1/4。
        //    再多就会把当次对话的推理空间挤掉 —— 历史记忆不该比本次任务更重要。
        return (int) requireInt(raw, KEY_RECALL_MAX_TOKENS, 64, 4000);
    }

    /**
     * 解析并校验召回强度门槛。
     *
     * @param raw 原始值
     * @return 门槛，落在 {@code [0.15, 1.0]}
     */
    public static double requireRecallMinStrength(@Nullable Object raw) {
        if (raw == null) {
            throw new IllegalStateException(
                    "记忆配置缺失（fail-closed，已声明 enabled=true）："
                            + KEY_RECALL_MIN_STRENGTH);
        }
        double value;
        try {
            value = Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "记忆配置不是数值（fail-closed）：" + KEY_RECALL_MIN_STRENGTH + " = " + raw, e);
        }
        if (Double.isNaN(value) || value < MemoryStrength.MIN_STRENGTH || value > 1.0) {
            throw new IllegalStateException(
                    "记忆召回门槛超出允许区间（fail-closed）：" + KEY_RECALL_MIN_STRENGTH
                            + " = " + value + "，允许区间 ["
                            + MemoryStrength.MIN_STRENGTH + ", 1.0]（下调到自然遗忘线以下"
                            + "等于让即将被遗忘的记忆继续影响判断）");
        }
        return value;
    }

    private static long requireInt(@Nullable Object raw, String key, long min, long max) {
        if (raw == null) {
            throw new IllegalStateException(
                    "记忆配置缺失（fail-closed，已声明 enabled=true）：" + key);
        }
        long value;
        try {
            value = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "记忆配置不是整数（fail-closed）：" + key + " = " + raw, e);
        }
        if (value < min || value > max) {
            throw new IllegalStateException(
                    "记忆配置超出允许区间（fail-closed）：" + key + " = " + value
                            + "，允许区间 [" + min + ", " + max + "]");
        }
        return value;
    }
}
