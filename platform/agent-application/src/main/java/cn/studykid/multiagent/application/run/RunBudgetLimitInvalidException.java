package cn.studykid.multiagent.application.run;

import java.math.BigDecimal;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Run 资源上限声明**不可用**（多级 Agent v1.1 · FR-024 · MA-T17）。
 *
 * <p>⚠️ <b>为什么这是客户端错误而不是服务端故障</b>：
 * 触发条件都是「<b>声明与现实不一致</b>」——
 * 声明的 Run 额度大于租户账户总额度、租户根本没开通预算账户、
 * 或者<b>无法读取</b>租户账户（后者是基础设施故障，同样拒绝创建，
 * 但同样不该让调用方无限重试）。
 *
 * <p>⚠️ <b>为什么必须拒绝而不是「截断到租户上限」</b>：
 * 截断会让调用方以为「我申请了 100，实际生效 10」——
 * 而 API 响应里没有这个信息，调用方无从得知，
 * 于是「这一棵调用树最多花多少」变成了一个<b>没人知道真实值的承诺</b>。
 * 明确拒绝至少让偏差可见。
 */
public class RunBudgetLimitInvalidException extends RuntimeException {

    private final String tenantId;
    private final BigDecimal declaredAmount;
    private final BigDecimal accountLimit;

    /**
     * @param tenantId       租户标识
     * @param declaredAmount 声明的 Run 级额度
     * @param accountLimit   租户账户总额度（{@code null} = 租户未开通预算账户）
     * @param reason         原因说明
     */
    public RunBudgetLimitInvalidException(@NonNull String tenantId,
                                          @NonNull BigDecimal declaredAmount,
                                          @Nullable BigDecimal accountLimit,
                                          @NonNull String reason) {
        super(reason);
        this.tenantId = tenantId;
        this.declaredAmount = declaredAmount;
        this.accountLimit = accountLimit;
    }

    /** @return 租户标识。 */
    public @NonNull String tenantId() {
        return tenantId;
    }

    /** @return 声明的 Run 级额度。 */
    public @NonNull BigDecimal declaredAmount() {
        return declaredAmount;
    }

    /**
     * @return 租户账户总额度；{@code null} 表示租户未开通预算账户
     */
    public @Nullable BigDecimal accountLimit() {
        return accountLimit;
    }
}
