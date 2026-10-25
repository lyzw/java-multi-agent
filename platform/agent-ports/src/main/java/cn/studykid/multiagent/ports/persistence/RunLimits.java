package cn.studykid.multiagent.ports.persistence;

import java.math.BigDecimal;
import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Run 的<b>资源上限</b>（多级 Agent v1.1 · FR-024 · MA-T17）。
 *
 * <p>⚠️ <b>为什么两个上限要合成一个对象</b>：
 * 「根 Run 最多跑到什么时候」与「根 Run 最多花多少钱」是<b>同一件事的两面</b> ——
 * 它们都是<b>整棵委派调用树的共同上界</b>，逐跳只能收窄、不得放宽
 * （不可妥协约束：全调用树共享预算/超时/次数）。
 * 合成一个对象有两个好处：
 * <ol>
 *   <li>端口签名不必随限制种类增长（避免「加一个参数改一批实现」）；</li>
 *   <li><b>不可能只传一半</b> —— 分成两个参数时，
 *       「写了截止时间却漏了预算」这种半拉子状态会被静默接受。</li>
 * </ol>
 *
 * <p>⚠️ <b>两个字段都可空 = 不限制</b>：
 * {@code null} 的含义是「<b>未声明</b>」，不是「额度为 0」。
 * 「未声明预算」≠「余额为 0」—— 前者是不额外限制，后者是必须拒绝。
 * 两者混淆会直接变成「所有调用被拒」或「所有调用被放行」的双向事故。
 *
 * @param deadlineAt   业务截止时刻（{@code null} = 不限时）
 * @param budgetAmount 该 Run 的总预算额度（{@code null} = 不额外限制）
 */
public record RunLimits(@Nullable Instant deadlineAt, @Nullable BigDecimal budgetAmount) {

    /**
     * 不限制（无截止时间、无 Run 级预算上限）。
     *
     * <p>⚠️ 这是<b>显式声明</b>的「不限时、不限额」，不是「默认值」——
     * 创建方想要什么样的上限必须自己说清楚。
     */
    public static final RunLimits UNLIMITED = new RunLimits(null, null);

    public RunLimits {
        // ⚠️ 额度校验**独立于**截止时刻：两者互不依赖，
        //    「额度为 0」无论有没有声明截止时刻都必须被拒 ——
        //    把两个条件绑在一起会漏放「只声明额度且额度为 0」的组合。
        if (budgetAmount != null && budgetAmount.signum() <= 0) {
            // ⚠️ 额度为 0 或负数 = 「一个 Run 都不许花」，
            //    那不是「未声明」，而是「明确禁止」—— 语义完全不同，不能放行。
            throw new IllegalArgumentException(
                    "预算额度必须为正数（0/负数表示明确禁止，不能当作「未声明」）");
        }
    }

    /** @return 是否有任何一项限制。 */
    public boolean anyLimit() {
        return deadlineAt != null || budgetAmount != null;
    }

    /**
     * @param amount 额度
     * @return 带预算上限、无限时的新限制
     */
    public static @NonNull RunLimits withBudget(@NonNull BigDecimal amount) {
        return new RunLimits(null, amount);
    }

    /**
     * @param deadline 截止时刻
     * @return 有限时、无预算上限的新限制
     */
    public static @NonNull RunLimits withDeadline(@NonNull Instant deadline) {
        return new RunLimits(deadline, null);
    }
}
