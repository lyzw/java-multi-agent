package cn.studykid.multiagent.application.orchestration;

import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * 委派调用的参数解析（多级 Agent v1.1 · P2-MA-02）。
 *
 * <p>⚠️ <b>参数口径全仓只有这一处</b>：闸门分支、未来的其他入口都走它，
 * 避免「两处各写一遍三态规则」导致语义漂移。
 *
 * <p>⚠️ <b>{@code additionalProperties:false}</b>：多传字段必须拒绝 ——
 * 否则「顺手塞进来的字段」会被静默忽略，掩盖越界输入。
 */
public final class DelegationArguments {

    /** 允许的参数名。 */
    private static final String TARGET = "targetAgentId";

    /** 允许的参数名。 */
    private static final String INPUT = "input";

    private DelegationArguments() {
    }

    /**
     * 解析委派参数。
     *
     * @param arguments 模型给出的委派参数
     * @return 解析结果
     * @throws IllegalArgumentException 参数非法（缺字段 / 多字段 / 类型不符）
     */
    public static @NonNull Parsed parse(@NonNull Map<String, Object> arguments) {
        for (String key : arguments.keySet()) {
            if (!TARGET.equals(key) && !INPUT.equals(key)) {
                throw new IllegalArgumentException("不被接受的参数: " + key);
            }
        }
        Object rawTarget = arguments.get(TARGET);
        if (rawTarget == null || String.valueOf(rawTarget).isBlank()) {
            throw new IllegalArgumentException("缺少必填参数 " + TARGET);
        }
        Object rawInput = arguments.get(INPUT);
        if (rawInput == null) {
            // ⚠️ **不得**兜底成空 Map（10 号文档 §8 · G-4）：
            //    缺 input 时兜底 = 让「模型没产出路由输入」看起来像
            //    「产出了一个空输入」—— 规则引擎对两者都会 NO_MATCH，
            //    于是配置缺陷被伪装成「规则没命中」，现场无人察觉。
            //    允许显式传空对象 {@code {}}（那是「确实没有业务输入」的真实声明）。
            throw new IllegalArgumentException("缺少必填参数 " + INPUT);
        }
        if (!(rawInput instanceof Map)) {
            throw new IllegalArgumentException(INPUT + " 必须是对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) rawInput;
        return new Parsed(String.valueOf(rawTarget).trim(), input);
    }

    /**
     * 解析结果。
     *
     * @param targetAgentId 目标 Agent 标识（<b>候选</b>；固定版本由服务端按父声明解析）
     * @param input         委派输入（最小必要上下文）
     */
    public record Parsed(@NonNull String targetAgentId, @NonNull Map<String, Object> input) {
    }
}
