package cn.studykid.multiagent.application.config;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/**
 * 预算预留额配置（P3-D04 §2.6 D-6 / FR-012）。
 *
 * <p><b>配置只作上限输入</b>（P0-D00 §决策）：
 * 「预算预留/结算仍以 <b>DB 原子条件更新</b>为准，配置只作上限输入」。
 * 本类只提供「预留多少」的输入值，**能否预留由 DB 裁决**
 * （{@code budget_no_overspend} CHECK + 条件 UPDATE）。
 *
 * <p><b>⚠️ 为何不按工具查表 / 不采信工具自报金额</b>：
 * 工具自报预留额等于「让被约束方申报约束额度」——
 * 一个有 bug 或恶意的工具可以声明「我只要 0.01 元」，
 * 而它实际触发的是按 token 计费的模型调用。未登记工具同理：
 * <b>不按 0</b>，一律用保守默认值（fail-closed 的同源思路）。
 *
 * <p><b>⚠️ 本类不持有可变静态状态</b>：预留额经构造函数注入
 * {@link cn.studykid.multiagent.worker.execute.ToolInvocationGate}，
 * 装配期校验、运行期只读。可变静态配置会让「未装配」与
 * 「已装配为 0」无法区分，且在并发装配下不安全。
 */
public final class BudgetPolicy {

    private BudgetPolicy() {
    }

    /** 单次工具调用预留额配置键。 */
    public static final String KEY_RESERVE_PER_TOOL_CALL =
            "agent.budget.reserve-per-tool-call";

    /**
     * 保守初值（元）。
     *
     * <p>⚠️ 仅用于生成配置模板与文档标注，<b>不参与运行时兜底</b>。
     * 运行时缺配置即拒绝工具执行（P0-D03 §4 红线 15 / R-020）。
     */
    public static final BigDecimal DEFAULT_RESERVE_AMOUNT = new BigDecimal("0.50");

    /**
     * 解析并校验预留额。
     *
     * @param raw 配置原值
     * @return 解析成功返回正值
     * @throws IllegalArgumentException 配置缺失、为 null、非数值或非正数
     *         —— <b>fail-closed</b>，调用方不得退回默认常量放行
     */
    public static BigDecimal requireReserveAmount(@Nullable Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException(
                    KEY_RESERVE_PER_TOOL_CALL + " 缺失。"
                            + "⚠️ 禁止退回默认值放行——那等于让工具按未经配置的额度花钱。");
        }
        BigDecimal value;
        try {
            value = raw instanceof BigDecimal b
                    ? b
                    : new BigDecimal(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    KEY_RESERVE_PER_TOOL_CALL + " 非数值：" + raw, e);
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(
                    KEY_RESERVE_PER_TOOL_CALL + " 必须为正数，收到：" + value);
        }
        return value;
    }
}
