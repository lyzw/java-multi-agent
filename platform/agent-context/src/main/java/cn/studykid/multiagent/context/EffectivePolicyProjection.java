package cn.studykid.multiagent.context;

import java.util.Objects;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.context.EffectiveContextPolicy;

/**
 * {@link EffectiveContextPolicy}（{@code agent-ports} 中性投影）↔ {@link ContextPolicy}
 * （算法层策略）的<b>唯一逐字段映射</b>（ADR-013 D38 / M7-a）。
 *
 * <p>⚠️ <b>为什么需要它</b>：M6 冻结的 {@link EffectiveContextPolicy} 刻意用枚举<b>名字符串</b>
 * 承载 {@code strategy} / {@code carryOver}，以免端口层反向依赖算法层（ADR-011 / C1）。
 * 算法层 {@link CompactionPipeline} / {@link BudgetAllocator} / {@link WaterLevel} 只认
 * {@link ContextPolicy} 的强类型枚举。本类是两者之间唯一的桥，必须<b>全参逐字段带过</b>
 * （铁律 #10）：任一字段漏映射都会让「快照里裁决好的策略」在运行期静默回退成默认值，
 * 属「不报错、只是行为悄悄变了」的失真形态。
 *
 * <p>⚠️ <b>枚举 fail-closed</b>：非法 / 空枚举名一律抛
 * {@link ContextPolicyRejectedException}({@code CONTEXT_POLICY_INVALID})，
 * ⛔ 绝不退化成默认档——那会让拼错策略的运行期跑成另一套水位。
 *
 * <p>⚠️ 纯函数：零 Spring / 零 JDBC / 零 IO / 零时钟，同输入恒等输出。
 */
public final class EffectivePolicyProjection {

    private EffectivePolicyProjection() {
    }

    /**
     * 端口中性投影 → 算法层策略。全部 18 字段显式列出，不使用反射 / JSON（字段顺序即契约）。
     */
    public static @NonNull ContextPolicy toContextPolicy(@NonNull EffectiveContextPolicy p) {
        Objects.requireNonNull(p, "EffectivePolicyProjection.toContextPolicy 入参不得为 null");
        return new ContextPolicy(
                p.enabled(),
                p.maxInputTokens(),
                p.triggerRatio(),
                p.targetRatio(),
                p.reserveRatio(),
                p.recentTurns(),
                p.pinFirstUserTurn(),
                p.ragRatio(),
                p.memoryRatio(),
                p.summaryRatio(),
                strategy(p.strategy()),
                p.summarizerModel(),
                p.summaryMaxTokens(),
                p.summaryTimeoutMs(),
                p.maxCompactionsPerRun(),
                carryOver(p.carryOver()),
                p.checkpointTtlSeconds(),
                p.policyVersion());
    }

    /**
     * 算法层策略 → 端口中性投影（M7-b 由快照构造 {@code ContextSpec} 时使用）。
     * 与 {@link #toContextPolicy} 构成往返，防止两侧各自维护一份字段表而漂移。
     */
    public static @NonNull EffectiveContextPolicy toEffectivePolicy(@NonNull ContextPolicy p) {
        Objects.requireNonNull(p, "EffectivePolicyProjection.toEffectivePolicy 入参不得为 null");
        return new EffectiveContextPolicy(
                p.enabled(),
                p.maxInputTokens(),
                p.triggerRatio(),
                p.targetRatio(),
                p.reserveRatio(),
                p.recentTurns(),
                p.pinFirstUserTurn(),
                p.ragRatio(),
                p.memoryRatio(),
                p.summaryRatio(),
                p.strategy().name(),
                p.summarizerModel(),
                p.summaryMaxTokens(),
                p.summaryTimeoutMs(),
                p.maxCompactionsPerRun(),
                p.carryOver().name(),
                p.checkpointTtlSeconds(),
                p.policyVersion());
    }

    private static ContextPolicy.Strategy strategy(String name) {
        if (name == null || name.isBlank()) {
            throw invalid("strategy", name);
        }
        try {
            return ContextPolicy.Strategy.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw invalid("strategy", name);
        }
    }

    private static ContextPolicy.CarryOver carryOver(String name) {
        if (name == null || name.isBlank()) {
            throw invalid("carryOver", name);
        }
        try {
            return ContextPolicy.CarryOver.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw invalid("carryOver", name);
        }
    }

    private static @NonNull ContextPolicyRejectedException invalid(String field, String value) {
        return new ContextPolicyRejectedException(
                ContextPolicyRejectedException.Reason.CONTEXT_POLICY_INVALID,
                "EffectiveContextPolicy." + field + " 非法（无法映射为算法层枚举）：" + value);
    }
}
