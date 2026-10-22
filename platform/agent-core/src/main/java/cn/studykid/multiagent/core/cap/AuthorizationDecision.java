package cn.studykid.multiagent.core.cap;

import org.jspecify.annotations.NonNull;

/**
 * 授权决策（§5.1 统一决策状态模型）。
 *
 * <p>⚠️ <b>四字段必须整体构造，不允许分别设置</b>：§5.1 的规则把
 * {@code authorizationDecision} / {@code executionDecision} / {@code executionStatus}
 * 绑定成不可拆分的组合 ——
 * <ul>
 *   <li>授权失败 → {@code DENY + NOT_EVALUATED + NOT_STARTED}；</li>
 *   <li>审批/预算等失败 → {@code ALLOW + DENY + NOT_STARTED}；</li>
 *   <li>成功 → {@code ALLOW + ALLOW + SUCCEEDED}。</li>
 * </ul>
 * 若允许分别赋值，就可能出现「DENY + NOT_STARTED + SUCCEEDED」这种
 * 「拒绝了却已执行完」的自相矛盾状态 —— 那正是 §9.1 要逐字段断言的东西。
 * 故本类<b>只提供工厂方法</b>，不暴露会破坏组合不变量的构造路径。
 *
 * <p>失败路径统一由 {@link #deny(ReasonCode)} 产生，成功路径由 {@link #allow()} 产生。
 */
public record AuthorizationDecision(
        @NonNull Authorization authorization,
        @NonNull Execution execution,
        @NonNull Status status,
        @NonNull Ledger ledger,
        @NonNull ReasonCode reasonCode) {

    /** 授权阶段结论（Phase A）。 */
    public enum Authorization {
        /** 授权通过。 */
        ALLOW,
        /** 授权拒绝。 */
        DENY
    }

    /** 执行准入结论（Phase B）。 */
    public enum Execution {
        /** 准入通过。 */
        ALLOW,
        /** 准入拒绝。 */
        DENY,
        /** 未进入 Phase B（Phase A 已拒绝，或尚未进入准入）。 */
        NOT_EVALUATED
    }

    /** 外部副作用结果是否已知。 */
    public enum Status {
        /** 尚未开始。 */
        NOT_STARTED,
        /** 执行中。 */
        RUNNING,
        /** 已成功。 */
        SUCCEEDED,
        /** 已失败（结果确定）。 */
        FAILED,
        /** 结果不确定（<b>唯一</b>需要进对账的状态）。 */
        UNKNOWN
    }

    /** 终态是否已可靠写入账本。 */
    public enum Ledger {
        /** 尚未落账。 */
        PENDING,
        /** 已可靠落账。 */
        RECORDED,
        /** 落账失败，重试中。 */
        RETRYING,
        /** 需要人工处置。 */
        NEEDS_MANUAL
    }

    /**
     * 构造成功决策。
     *
     * @return ALLOW + ALLOW + SUCCEEDED + ledger=PENDING + {@link ReasonCode#SUCCESS}
     */
    public static @NonNull AuthorizationDecision allow() {
        return new AuthorizationDecision(Authorization.ALLOW, Execution.ALLOW,
                Status.SUCCEEDED, Ledger.PENDING, ReasonCode.SUCCESS);
    }

    /**
     * 构造授权拒绝决策（Phase A）。
     *
     * <p>§5.1：授权失败 → {@code DENY + NOT_EVALUATED + NOT_STARTED}。
     *
     * @param reasonCode 原因码（§5.7）
     * @return 拒绝决策
     */
    public static @NonNull AuthorizationDecision deny(@NonNull ReasonCode reasonCode) {
        return new AuthorizationDecision(Authorization.DENY, Execution.NOT_EVALUATED,
                Status.NOT_STARTED, Ledger.PENDING, reasonCode);
    }

    /**
     * @return 是否为授权通过
     */
    public boolean isAllowed() {
        return authorization == Authorization.ALLOW;
    }

    /**
     * @return 是否需要进对账（§7.4：仅 {@link Status#UNKNOWN} 需要）
     */
    public boolean requiresReconciliation() {
        return status == Status.UNKNOWN;
    }
}
