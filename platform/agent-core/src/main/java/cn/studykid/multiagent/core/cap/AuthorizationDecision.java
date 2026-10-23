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
 *
 * <p>⚠️ <b>Phase B 的工厂方法（P2 新增）</b>：
 * {@link #executionDeny(ReasonCode)} / {@link #executionSucceeded(Ledger)} /
 * {@link #executionFailed(Ledger, ReasonCode)} / {@link #executionUnknown(Ledger, ReasonCode)}。
 * 它们与 Phase A 的工厂方法<b>组合不同</b>：准入拒绝时授权仍然通过，
 * 故 {@code authorizationDecision} 保持 {@code ALLOW}
 * （08 §1：S6/S7 必须验证「授权通过、仅执行准入拒绝」）。
 *
 * <p>⚠️ <b>{@link #allow()} 的语义边界</b>：它表示「Phase A 全因子通过」，
 * 其 {@code status=SUCCEEDED} 是<b>全流程成功</b>的终态取值，
 * 在 Phase A 阶段被当作「通过信号」使用（调用方读 {@link #isAllowed()}）。
 * Phase B 未进入前<b>不应</b>对外发布本决策 —— 真正的终态由上述 Phase B 工厂产生。
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

    // ── Phase B 工厂（P2）──────────────────────────────────────────
    //
    // ⚠️ Phase B 的组合与 Phase A **不同**：
    //    审批/预算/账本/准入失败时，**授权已经通过**，
    //    故 authorizationDecision 仍为 ALLOW，只是 executionDecision=DENY。
    //    （08 §1 判定规则：S6/S7 必须验证「授权通过、仅执行准入拒绝」，
    //    不得笼统记为 authorizationDecision=DENY。）

    /**
     * 构造执行准入拒绝决策（Phase B）。
     *
     * <p>§5.1：审批/预算等失败 → {@code ALLOW + DENY + NOT_STARTED}。
     *
     * <p>⚠️ 副作用<b>未启动</b>：{@code NOT_STARTED} 是这里的关键 ——
     * 准入拒绝意味着外部调用根本没发出，不得记成 FAILED/UNKNOWN
     * （那会凭空造出待对账的幽灵操作）。
     *
     * @param reasonCode 原因码（§5.7，阶段 B / 准入）
     * @return 执行准入拒绝决策
     */
    public static @NonNull AuthorizationDecision executionDeny(@NonNull ReasonCode reasonCode) {
        return new AuthorizationDecision(Authorization.ALLOW, Execution.DENY,
                Status.NOT_STARTED, Ledger.PENDING, reasonCode);
    }

    /**
     * 构造执行成功决策（Phase B · 步骤 14/14a/15）。
     *
     * @param ledger 终态落账状态（§7.4 双维度）
     * @return ALLOW + ALLOW + SUCCEEDED + ledger + {@link ReasonCode#SUCCESS}
     */
    public static @NonNull AuthorizationDecision executionSucceeded(@NonNull Ledger ledger) {
        return new AuthorizationDecision(Authorization.ALLOW, Execution.ALLOW,
                Status.SUCCEEDED, ledger, ReasonCode.SUCCESS);
    }

    /**
     * 构造执行确定失败决策（Phase B · 步骤 14）。
     *
     * <p>⚠️ <b>与 UNKNOWN 严格区分</b>（§5.1）：确定失败 = 副作用结果已知且为失败；
     * UNKNOWN = 副作用结果不确定。把确定失败报成 UNKNOWN 会凭空造出待对账的幽灵操作。
     *
     * @param ledger     终态落账状态
     * @param reasonCode 原因码（通常为 {@link ReasonCode#TOOL_EXECUTION_FAILED}）
     * @return ALLOW + ALLOW + FAILED + ledger + reasonCode
     */
    public static @NonNull AuthorizationDecision executionFailed(@NonNull Ledger ledger,
                                                                 @NonNull ReasonCode reasonCode) {
        return new AuthorizationDecision(Authorization.ALLOW, Execution.ALLOW,
                Status.FAILED, ledger, reasonCode);
    }

    /**
     * 构造执行结果未知决策（Phase B · 步骤 14）。
     *
     * <p>⚠️ <b>这是唯一需要进对账的状态</b>（§7.4 / §9.1 S8）：
     * 外部调用可能已发生，只是响应丢失。
     *
     * @param ledger     终态落账状态
     * @param reasonCode 原因码（§5.7 中唯一语义匹配的是 {@link ReasonCode#EXEC_UNKNOWN}）
     * @return ALLOW + ALLOW + UNKNOWN + ledger + reasonCode
     */
    public static @NonNull AuthorizationDecision executionUnknown(@NonNull Ledger ledger,
                                                                   @NonNull ReasonCode reasonCode) {
        return new AuthorizationDecision(Authorization.ALLOW, Execution.ALLOW,
                Status.UNKNOWN, ledger, reasonCode);
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
