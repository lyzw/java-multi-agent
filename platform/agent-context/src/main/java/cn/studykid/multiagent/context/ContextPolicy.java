package cn.studykid.multiagent.context;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 压缩策略（Agent 级 {@code generation_params.context} 的运行期投影，ADR-013）。
 *
 * <p>⚠️ <b>全参构造是唯一构造</b>（铁律 #11）：本 record <b>不提供兼容构造器</b>。
 * 项目曾在 {@code CapAgentDef.withPublished} 上因「加了字段却留兼容构造器」
 * 把 instructions / modelRef / generationParams 静默清成空值 ——
 * 凡新增字段必须同步改所有拷贝点，且用全参构造逐字段带过。
 *
 * <p>⚠️ 「压缩比例」是三件套不是单旋钮（[D3]）：
 * {@code triggerRatio}（何时压）/ {@code targetRatio}（压到多少）/
 * 只读观测指标 {@code compressionRatio=after/before}。硬约束
 * {@code targetRatio < triggerRatio}，否则压完立刻再触发形成抖动风暴。
 *
 * <p>⚠️ <b>S1 影子期</b>：本对象只做配置解析与计量口径，
 * 不改变任何模型请求字节；构造期不抛校验异常，校验统一由
 * {@link ContextPolicyValidator} 在发布期与执行期各调一次。
 */
public record ContextPolicy(
        boolean enabled,
        int maxInputTokens,
        double triggerRatio,
        double targetRatio,
        double reserveRatio,
        int recentTurns,
        boolean pinFirstUserTurn,
        double ragRatio,
        double memoryRatio,
        double summaryRatio,
        @NonNull Strategy strategy,
        @Nullable String summarizerModel,
        int summaryMaxTokens,
        int summaryTimeoutMs,
        int maxCompactionsPerRun,
        @NonNull CarryOver carryOver,
        int checkpointTtlSeconds,
        @NonNull String policyVersion) {

    /** 压缩策略档位。 */
    public enum Strategy {
        /** 不压缩。 */
        OFF,
        /** 只跑 W1/W2（零 LLM）。 */
        TRIM_ONLY,
        /** 四级水位线全开。 */
        TIERED
    }

    /** SSE 压缩态载体。 */
    public enum CarryOver {
        /** 客户端携带（默认，不落库、零保留）。 */
        CLIENT,
        /** 服务端 checkpoint（显式例外，需租户 zeroRetention=false）。 */
        SERVER
    }

    public ContextPolicy {
        strategy = strategy == null ? Strategy.TIERED : strategy;
        carryOver = carryOver == null ? CarryOver.CLIENT : carryOver;
        policyVersion = policyVersion == null ? "" : policyVersion;
    }

    /**
     * 《方案》§3.2-B 的默认策略（写入即生效路径的兜底值）。
     *
     * <p>⚠️ 默认值在此处<b>逐字列出</b>，不从别处派生：
     * 配置门禁的单测用字面量断言这些值，绝不引用被测实现自己的常量。
     */
    public static @NonNull ContextPolicy defaults() {
        return new ContextPolicy(
                true,
                16_000,
                0.70,
                0.40,
                0.10,
                6,
                true,
                0.30,
                0.15,
                0.25,
                Strategy.TIERED,
                null,
                1_200,
                8_000,
                3,
                CarryOver.CLIENT,
                1_800,
                "cp_default");
    }

    /** 关闭态（[D11]：与「没有压缩功能」逐位一致）。 */
    public static @NonNull ContextPolicy off() {
        ContextPolicy d = defaults();
        return new ContextPolicy(
                false, d.maxInputTokens(), d.triggerRatio(), d.targetRatio(),
                d.reserveRatio(), d.recentTurns(), d.pinFirstUserTurn(),
                d.ragRatio(), d.memoryRatio(), d.summaryRatio(),
                Strategy.OFF, d.summarizerModel(), d.summaryMaxTokens(),
                d.summaryTimeoutMs(), d.maxCompactionsPerRun(), d.carryOver(),
                d.checkpointTtlSeconds(), "cp_off");
    }

    // ── 全参拷贝（铁律 #11：唯一拷贝点） ────────────────────────────

    /**
     * ⚠️ <b>本 record 所有投影 / 复制必须经过这里</b>。
     *
     * <p>把全参构造收敛到一处是有意的：日后新增字段时，
     * {@code copy} 的签名一变，下面每个 {@code withXxx} 都会<b>编译失败</b>，
     * 强制把新字段逐字段带过去；而若每个 with 方法各写一遍全参构造，
     * 漏改某处会静默清空字段（项目已有事故）。
     */
    private @NonNull ContextPolicy copy(
            boolean enabled, int maxInputTokens, double triggerRatio,
            double targetRatio, double reserveRatio, int recentTurns,
            boolean pinFirstUserTurn, double ragRatio, double memoryRatio,
            double summaryRatio, @NonNull Strategy strategy,
            @Nullable String summarizerModel, int summaryMaxTokens,
            int summaryTimeoutMs, int maxCompactionsPerRun,
            @NonNull CarryOver carryOver, int checkpointTtlSeconds,
            @NonNull String policyVersion) {
        return new ContextPolicy(enabled, maxInputTokens, triggerRatio,
                targetRatio, reserveRatio, recentTurns, pinFirstUserTurn,
                ragRatio, memoryRatio, summaryRatio, strategy, summarizerModel,
                summaryMaxTokens, summaryTimeoutMs, maxCompactionsPerRun,
                carryOver, checkpointTtlSeconds, policyVersion);
    }

    public @NonNull ContextPolicy withEnabled(boolean value) {
        return copy(value, maxInputTokens, triggerRatio, targetRatio, reserveRatio,
                recentTurns, pinFirstUserTurn, ragRatio, memoryRatio, summaryRatio,
                strategy, summarizerModel, summaryMaxTokens, summaryTimeoutMs,
                maxCompactionsPerRun, carryOver, checkpointTtlSeconds, policyVersion);
    }

    /** 有效窗口裁决后写回（三层 MIN，[D4]）。 */
    public @NonNull ContextPolicy withMaxInputTokens(int value) {
        return copy(enabled, value, triggerRatio, targetRatio, reserveRatio,
                recentTurns, pinFirstUserTurn, ragRatio, memoryRatio, summaryRatio,
                strategy, summarizerModel, summaryMaxTokens, summaryTimeoutMs,
                maxCompactionsPerRun, carryOver, checkpointTtlSeconds, policyVersion);
    }

    /** 模型能力未知时上调安全余量（R10）。 */
    public @NonNull ContextPolicy withReserveRatio(double value) {
        return copy(enabled, maxInputTokens, triggerRatio, targetRatio, value,
                recentTurns, pinFirstUserTurn, ragRatio, memoryRatio, summaryRatio,
                strategy, summarizerModel, summaryMaxTokens, summaryTimeoutMs,
                maxCompactionsPerRun, carryOver, checkpointTtlSeconds, policyVersion);
    }

    public @NonNull ContextPolicy withStrategy(@NonNull Strategy value) {
        return copy(enabled, maxInputTokens, triggerRatio, targetRatio, reserveRatio,
                recentTurns, pinFirstUserTurn, ragRatio, memoryRatio, summaryRatio,
                value, summarizerModel, summaryMaxTokens, summaryTimeoutMs,
                maxCompactionsPerRun, carryOver, checkpointTtlSeconds, policyVersion);
    }

    /** 由 {@link ContextPolicyResolver} 在 Run 创建期固化，其他调用点禁止写入。 */
    public @NonNull ContextPolicy withPolicyVersion(@NonNull String value) {
        return copy(enabled, maxInputTokens, triggerRatio, targetRatio, reserveRatio,
                recentTurns, pinFirstUserTurn, ragRatio, memoryRatio, summaryRatio,
                strategy, summarizerModel, summaryMaxTokens, summaryTimeoutMs,
                maxCompactionsPerRun, carryOver, checkpointTtlSeconds, value);
    }
}
