package cn.studykid.multiagent.core.orchestration;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 单条规则的求值失败处置（10 号设计 §4.3 的 {@code onError}，<b>只有两个值</b>）。
 *
 * <p>⚠️ <b>没有第三值「当不匹配处理」</b>，且这不是取舍而是铁律：
 * 把求值异常折算成 FALSE 会让「字段名写错的条件」看起来像「条件不成立」，
 * 于是策略永远走兜底、而现场无人察觉 —— 这正是本项目反复消除的
 * 「看起来生效、实际不生效」（§8 G-4 同源）。
 * 因此 {@link #SKIP} 的语义是「<b>跳过本条并留痕</b>」，不是「本条不成立」。
 *
 * <p><b>默认 {@code SKIP}</b>：一条规则的字段写错不应让整个 Run 失败；
 * 但它必须在决策快照里留下 {@code EVAL_ERROR_SKIPPED}，否则错误就被静默吞掉。
 */
public enum OnError {

    /** 跳过本条规则继续判后面的，trace 记 {@code EVAL_ERROR_SKIPPED}。 */
    SKIP,

    /** 整次路由失败，不触发任何子执行。 */
    REJECT;

    /**
     * 从策略文本解析。
     *
     * <p>⚠️ 未提供时用默认值 {@link #SKIP}；<b>提供了但不认识就报错</b> ——
     * 拼错 {@code "REJECTT"} 若被静默当成默认值，等于把「本条应中止」的配置意图改掉。
     *
     * @param wireName 策略文本里的写法（可为 {@code null} = 未声明）
     * @return 枚举
     * @throws IllegalArgumentException 词汇表之外
     */
    public static @NonNull OnError from(@Nullable String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return SKIP;
        }
        for (OnError value : values()) {
            if (value.name().equalsIgnoreCase(wireName.trim())) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的 onError 取值: " + wireName
                + "（只有 " + java.util.Arrays.stream(values()).map(Enum::name).toList()
                + "；「当不匹配处理」不在其中，那是 ERROR≠MATCH 铁律）");
    }
}
