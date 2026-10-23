package cn.studykid.multiagent.testsupport.acceptance;

import cn.studykid.multiagent.core.cap.AuthorizationDecision;
import cn.studykid.multiagent.core.cap.ReasonCode;

/**
 * 统一四字段断言框架（§5.1 / §9.1 · W-PERM-P6-T1）。
 *
 * <p>⚠️ <b>为什么需要「统一」而不是各测试各写各的</b>：
 * §9.1 要求每个行为场景断言<b>完整四字段</b>（authz / exec / status / reasonCode）。
 * 散落写法有三种典型退化：
 * <ul>
 *   <li>只断言 reasonCode —— 少了 authz/exec/status，</li>
 *   <li>只断言其中一两个字段 —— 少了「拒绝发生在哪一阶段」，
 *       而这正是 §5.1 区分「Phase A 拒绝」与「Phase B 准入拒绝」的<b>唯一依据</b>；</li>
 *   <li>断言写成 {@code assertThat(x).isEqualTo(y)} 不带说明 ——
 *       失败时看不出「期望什么、实际什么」。</li>
 * </ul>
 * 故本类把断言收敛到一处：<b>字段顺序固定、失败信息带场景语义</b>。
 *
 * <p>⚠️ <b>「拒绝」不是一种结论，是三种</b>：
 * <ul>
 *   <li><b>Phase A 拒绝</b>（授权就没通过）→ authz=DENY、exec=NOT_EVALUATED、status=NOT_STARTED；</li>
 *   <li><b>Phase B 准入拒绝</b>（授权过了，准入没过）→ authz=ALLOW、exec=DENY、status=NOT_STARTED；</li>
 *   <li><b>执行后失败/未知</b> → authz=ALLOW、exec=ALLOW、status=FAILED|UNKNOWN。</li>
 * </ul>
 * 把三者混成「DENY」会让「授权通过但准入拒绝」被读成「授权被拒」——
 * 08 §1 判定规则明令禁止这种笼统记账。
 */
public final class DecisionFields {

    private DecisionFields() {
    }

    /**
     * 断言完整四字段。
     *
     * @param decision   决策
     * @param authz      期望的授权结论
     * @param exec       期望的执行准入结论
     * @param status     期望的执行状态
     * @param reasonCode 期望的原因码
     */
    public static void assertFourFields(AuthorizationDecision decision,
                                        AuthorizationDecision.Authorization authz,
                                        AuthorizationDecision.Execution exec,
                                        AuthorizationDecision.Status status,
                                        ReasonCode reasonCode) {
        requireNonNull(decision);
        assertField("authorizationDecision", authz, decision.authorization());
        assertField("executionDecision", exec, decision.execution());
        assertField("executionStatus", status, decision.status());
        assertField("reasonCode", reasonCode, decision.reasonCode());
    }

    /**
     * 断言「Phase A 拒绝」：授权就没通过，Phase B 从未进入。
     *
     * @param decision 决策
     * @param expected 期望的原因码
     */
    public static void assertDeniedAtPhaseA(AuthorizationDecision decision, ReasonCode expected) {
        assertFourFields(decision,
                AuthorizationDecision.Authorization.DENY,
                AuthorizationDecision.Execution.NOT_EVALUATED,
                AuthorizationDecision.Status.NOT_STARTED,
                expected);
    }

    /**
     * 断言「Phase B 准入拒绝」：授权通过，但执行准入未通过。
     *
     * @param decision 决策
     * @param expected 期望的原因码
     */
    public static void assertDeniedAtPhaseB(AuthorizationDecision decision, ReasonCode expected) {
        assertFourFields(decision,
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.DENY,
                AuthorizationDecision.Status.NOT_STARTED,
                expected);
    }

    /**
     * 断言「执行成功」。
     *
     * @param decision 决策
     */
    public static void assertSucceeded(AuthorizationDecision decision) {
        assertFourFields(decision,
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.ALLOW,
                AuthorizationDecision.Status.SUCCEEDED,
                ReasonCode.SUCCESS);
    }

    /**
     * 断言「结果不确定」：唯一需要进对账的状态。
     *
     * @param decision 决策
     */
    public static void assertUnknown(AuthorizationDecision decision) {
        assertFourFields(decision,
                AuthorizationDecision.Authorization.ALLOW,
                AuthorizationDecision.Execution.ALLOW,
                AuthorizationDecision.Status.UNKNOWN,
                ReasonCode.EXEC_UNKNOWN);
    }

    private static void assertField(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("⛔ " + name + " 不符：期望 " + expected + "，实际 " + actual
                    + "（四字段必须完整断言：authz / exec / status / reasonCode —— "
                    + "只断言 reasonCode 会让「拒绝发生在哪一阶段」无从判断）");
        }
    }

    private static void requireNonNull(AuthorizationDecision decision) {
        if (decision == null) {
            throw new AssertionError("⛔ 决策为 null");
        }
    }
}
