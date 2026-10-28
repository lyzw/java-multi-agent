package cn.studykid.multiagent.routing;

import org.jspecify.annotations.NonNull;

/**
 * 上下文字段的<b>封闭词汇表</b>（10 号设计 §4.4：{@code ctx.*}）。
 *
 * <p>⚠️ <b>只有这六个，且一律由服务端注入</b>：
 * 模型产出的 {@code input} 里出现同名字段<b>不会</b>被读到 ——
 * 否则 {@code ctx.tenantId} 就成了「请求体里可以自带的租户标识」，
 * 而身份来自请求体是本项目反复消除的敞口（G-6）。
 *
 * <p>⚠️ <b>{@link #NOW_UTC_DATE} 是唯一的时间词汇，且只到天</b>：
 * 精确到秒的「现在」会让同一份策略在同一天里的两次调用给出不同判定，
 * 重放不变式当场失效。日粒度的代价是「跨自然日的重放结果可能不同」，
 * 这是<b>必须写进文档的取舍</b>，而不是含糊过去：
 * 策略不引用时间就完全确定；引用了时间，则「同一自然日内可重放」。
 * 无论哪种，该字段都必须进决策快照。
 */
public enum CtxName {

    /** 租户标识（字符串）。 */
    TENANT_ID("tenantId", Kind.STRING),

    /** 接入渠道（字符串，如 {@code app} / {@code web}）。 */
    CHANNEL("channel", Kind.STRING),

    /** 请求语言（字符串 BCP-47 片段）。 */
    LOCALE("locale", Kind.STRING),

    /** 本次 Run 的尝试序号（整数，从 1 起）。 */
    RUN_ATTEMPT("run.attempt", Kind.NUMBER),

    /** 当前委派深度（整数，根 Run = 0）。 */
    RUN_DEPTH("run.depth", Kind.NUMBER),

    /** UTC 自然日（{@code yyyy-MM-dd}）。⚠️ 见类注释关于重放的取舍。 */
    NOW_UTC_DATE("now.utcDate", Kind.DATE);

    /** 上下文值的种类（决定哪些比较是合法的）。 */
    public enum Kind {
        /** 字符串。 */
        STRING,
        /** 整数。 */
        NUMBER,
        /** 日粒度日期字符串。 */
        DATE
    }

    private final String wireName;
    private final Kind kind;

    CtxName(@NonNull String wireName, @NonNull Kind kind) {
        this.wireName = wireName;
        this.kind = kind;
    }

    /** @return 策略文本里的写法（{@code ctx.} 之后的那段） */
    public @NonNull String wireName() {
        return wireName;
    }

    public @NonNull Kind kind() {
        return kind;
    }

    /**
     * 按策略文本解析上下文项。
     *
     * @param wireName {@code ctx.} 之后的名字
     * @return 对应的枚举
     * @throws IllegalArgumentException 词汇表之外（<b>没有</b>「未知即透传」这条路）
     */
    public static @NonNull CtxName from(@NonNull String wireName) {
        for (CtxName name : values()) {
            if (name.wireName.equals(wireName)) {
                return name;
            }
        }
        throw new IllegalArgumentException("未知的上下文字段: ctx." + wireName
                + "（封闭词汇表只有 " + java.util.Arrays.stream(values())
                .map(CtxName::wireName).toList() + "）");
    }
}
