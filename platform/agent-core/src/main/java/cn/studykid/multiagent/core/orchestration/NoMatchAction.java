package cn.studykid.multiagent.core.orchestration;

import org.jspecify.annotations.NonNull;

/**
 * 规则全部未命中时的处置（10 号设计 §4.2 的 {@code no_match_action}）。
 *
 * <p>⚠️ <b>只有两值，且没有「静默挑一个」这一档</b>：
 * 无命中是一个<b>真实发生的业务事件</b>，必须落到稳定码上（
 * {@link Resolution#NO_MATCH_REJECTED} 或 {@link Resolution#DEFAULT_TARGET}），
 * 否则「策略一条都没中」就会被伪装成「策略工作正常」——
 * 这是 §8 六条缺口共同指向的那类缺陷。
 *
 * <p>选 {@link #DEFAULT_TARGET} 时 {@code default_target} 必填（DDL 的
 * {@code default_target_ck} 与守卫 V2 同口径：兜底目标同样必须是父声明的子，
 * <b>准入校验对兜底路径不打折</b>）。
 */
public enum NoMatchAction {

    /** 拒：本次委派失败，稳定码 {@code NO_MATCH_REJECTED}。 */
    REJECT,

    /** 走 {@code default_target}（该目标仍须通过拓扑/授权/预算准入）。 */
    DEFAULT_TARGET;

    /**
     * 按策略文本解析。
     *
     * @param wireName 策略文本里的写法
     * @return 枚举
     * @throws IllegalArgumentException 词汇表之外
     */
    public static @NonNull NoMatchAction from(@NonNull String wireName) {
        for (NoMatchAction action : values()) {
            if (action.name().equals(wireName)) {
                return action;
            }
        }
        throw new IllegalArgumentException("未知的 no_match_action: " + wireName + "（只有 "
                + java.util.Arrays.stream(values()).map(Enum::name).toList() + "）");
    }
}
