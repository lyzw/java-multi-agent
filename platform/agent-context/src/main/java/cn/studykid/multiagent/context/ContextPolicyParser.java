package cn.studykid.multiagent.context;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * {@code generation_params.context} → {@link ContextPolicy} 的<b>唯一</b>解析实现
 * （ADR-013 / 《方案》§3.4、§5.8）。
 *
 * <p>⚠️ <b>发布期与执行期共用本类</b>：{@code ConfigValidationGate} 调
 * {@link ContextPolicyValidator#validate(Map)} 拒非法配置，执行期由本类读同一份
 * 键值快照。禁止任何一侧另写解析器或默认值字面量，否则会出现
 * 「发布时判合法、跑起来是另一套值」的静默漂移。
 *
 * <p>⚠️ <b>未声明 = 全局默认，不是关闭</b>：{@code context} 为 null / 空对象时返回
 * {@link ContextPolicy#defaults()}（旧 Agent 版本行为不变）；显式
 * {@code enabled=false} 才返回关闭态。
 *
 * <p>⚠️ <b>policyVersion 不由本类派生</b>：解析结果固定为空串，由
 * {@link ContextPolicyResolver} 在 Run 创建期结合模型能力与 Agent 版本固化，
 * 避免「同一份配置在两台机器上算出不同版本号」。
 */
public final class ContextPolicyParser {

    private ContextPolicyParser() {
    }

    /**
     * 解析 context 子对象；非法值直接抛 {@link ContextPolicyRejectedException}。
     *
     * @param context 已归一化的键值（值类型限定 Number / Boolean / String / null）
     */
    public static @NonNull ContextPolicy parse(@Nullable Map<String, Object> context) {
        List<ContextPolicyValidator.Violation> violations =
                ContextPolicyValidator.validate(context);
        if (!violations.isEmpty()) {
            throw new ContextPolicyRejectedException(
                    ContextPolicyRejectedException.Reason.CONTEXT_POLICY_INVALID,
                    violations.toString());
        }

        ContextPolicy d = ContextPolicy.defaults();
        Map<String, Object> c = context == null ? Map.of() : context;
        boolean enabled = bool(c, "enabled", d.enabled());
        ContextPolicy parsed = new ContextPolicy(
                enabled,
                integer(c, "maxInputTokens", d.maxInputTokens()),
                number(c, "triggerRatio", d.triggerRatio()),
                number(c, "targetRatio", d.targetRatio()),
                number(c, "reserveRatio", d.reserveRatio()),
                integer(c, "recentTurns", d.recentTurns()),
                bool(c, "pinFirstUserTurn", d.pinFirstUserTurn()),
                number(c, "ragRatio", d.ragRatio()),
                number(c, "memoryRatio", d.memoryRatio()),
                number(c, "summaryRatio", d.summaryRatio()),
                enumeration(c, "strategy", ContextPolicy.Strategy.class,
                        ContextPolicy.Strategy.TIERED),
                text(c, "summarizerModel", d.summarizerModel()),
                integer(c, "summaryMaxTokens", d.summaryMaxTokens()),
                integer(c, "summaryTimeoutMs", d.summaryTimeoutMs()),
                integer(c, "maxCompactionsPerRun", d.maxCompactionsPerRun()),
                enumeration(c, "carryOver", ContextPolicy.CarryOver.class,
                        ContextPolicy.CarryOver.CLIENT),
                integer(c, "checkpointTtlSeconds", d.checkpointTtlSeconds()),
                "");   // policyVersion：交给 Resolver 固化

        return enabled ? parsed : parsed.withEnabled(false);
    }

    /** 显式关闭态（供灰度 / 回滚使用，保留其余字段便于对照）。 */
    public static @NonNull ContextPolicy off() {
        return ContextPolicy.off();
    }

    private static boolean bool(Map<String, Object> c, String key, boolean fallback) {
        Object v = c.get(key);
        return v instanceof Boolean b ? b : fallback;
    }

    private static int integer(Map<String, Object> c, String key, int fallback) {
        Object v = c.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static double number(Map<String, Object> c, String key, double fallback) {
        Object v = c.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static @Nullable String text(Map<String, Object> c, String key,
                                         @Nullable String fallback) {
        Object v = c.get(key);
        return v instanceof String s ? s : fallback;
    }

    private static <E extends Enum<E>> @NonNull E enumeration(Map<String, Object> c, String key,
                                                              Class<E> type, @NonNull E fallback) {
        Object v = c.get(key);
        if (!(v instanceof String s)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, s);
        } catch (IllegalArgumentException e) {
            // Validator 已先拒过非法枚举；此处兜底只防「Map 被外部改动」。
            throw new ContextPolicyRejectedException(
                    ContextPolicyRejectedException.Reason.CONTEXT_POLICY_INVALID,
                    key + " 非法枚举值：" + s);
        }
    }
}
