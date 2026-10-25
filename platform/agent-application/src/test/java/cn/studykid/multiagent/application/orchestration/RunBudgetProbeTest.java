package cn.studykid.multiagent.application.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.persistence.RunBudgetPort;

/**
 * Run 级预算探针的**量级判定**（多级 Agent v1.1 · FR-024 · MA-T17）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：不可妥协约束之一是
 * 「<b>全调用树共享预算上限</b>」。此前探针只判「还有没有余额」（方向），
 * 于是剩余 10 元的 Run 仍能预留 50 元 ——
 * <b>单次调用即突破上限</b>，上限名存实亡。
 *
 * <p><b>本类的判据</b>：
 * <ul>
 *   <li>B-1：按<b>量</b>判 —— 剩余额度 &lt; 本次预留额 → 拒绝；</li>
 *   <li>B-2：恰好装下（剩余 == 本次）→ 放行（上限是「不超过」，不是「必须小于」）；</li>
 *   <li>B-3：<b>已结算</b>同样占用额度（不只是未结算的预留）；</li>
 *   <li>B-4：未声明额度 → 不额外限制（≠「额度为 0」）；</li>
 *   <li>B-5：读不到 → fail-closed，且原因与「额度不够」<b>可区分</b>
 *       （前者是基础设施故障，后者是业务约束，处置路径不同）。</li>
 * </ul>
 */
class RunBudgetProbeTest {

    private static final String TENANT = "t-a";
    private static final String RUN = "run-1";

    /** 构造一个探针：额度 / 未结算预留 / 已结算消耗。 */
    private static RunBudgetProbe probe(String cap, String reserved, String settled) {
        return new RunBudgetProbe((tenantId, runId) -> Optional.of(
                new RunBudgetPort.RunBudget(runId, new BigDecimal(cap),
                        new BigDecimal(reserved), new BigDecimal(settled))));
    }

    @Test
    @DisplayName("B-1 ⚠️⚠️ 剩余额度不足以容纳本次预留 → 必须拒绝（方向性判定会漏放）")
    void amountExceedingRemainingIsDenied() {
        // ⚠️ 本用例是整批的核心反证点：剩余 10、请求 50。
        //    方向性判定（只问「还有没有余额」）会放行 —— 那正是修复前的行为。
        var verdict = probe("100", "90", "0")
                .check(TENANT, RUN, new BigDecimal("50"));

        assertThat(verdict.allowed())
                .as("⚠️⚠️ 剩余 10 元却要预留 50 元 → 必须拒绝："
                        + "放行等于单次调用就突破 Run 上限，"
                        + "「全调用树共享预算上限」名存实亡")
                .isFalse();
        assertThat(verdict.denial())
                .as("拒绝原因必须是「额度不足」而非「读不到」——两者处置路径不同")
                .isEqualTo(RunBudgetProbe.Denial.AMOUNT_EXCEEDS_REMAINING);
        assertThat(verdict.reasonCode())
                .as("账本/拒绝记录用的稳定原因码")
                .isEqualTo("run-budget-exhausted");
    }

    @Test
    @DisplayName("B-2 ✅ 剩余额度恰好等于本次预留额 → 放行（上限是「不超过」）")
    void amountExactlyFittingIsAllowed() {
        var verdict = probe("100", "50", "0")
                .check(TENANT, RUN, new BigDecimal("50"));

        assertThat(verdict.allowed())
                .as("恰好用满额度是合法的；若这里拒绝，等于凭空多收一道手续费")
                .isTrue();
        assertThat(verdict.available()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("B-3 ⚠️ 已结算的消耗同样占用额度（不能只看未结算预留）")
    void settledAmountAlsoOccupiesTheCap() {
        // 额度 100、未结算预留 0、已结算 90 → 剩余 10
        var verdict = probe("100", "0", "90")
                .check(TENANT, RUN, new BigDecimal("50"));

        assertThat(verdict.allowed())
                .as("⚠️ 已结算的 90 元是真花出去的钱，必须计入占用；"
                        + "只算未结算预留会让上限随结算不断放宽")
                .isFalse();
    }

    @Test
    @DisplayName("B-4 ✅ 未声明 Run 级额度 → 不额外限制（≠「额度为 0」）")
    void undeclaredCapIsNotARestriction() {
        RunBudgetProbe probe = new RunBudgetProbe((tenantId, runId) -> Optional.empty());

        var verdict = probe.check(TENANT, RUN, new BigDecimal("1000"));

        assertThat(verdict.allowed())
                .as("没配预算 ≠ 预算为 0；把两者混同会把所有工具调用全拦掉")
                .isTrue();
        assertThat(verdict.denial()).isNull();
    }

    @Test
    @DisplayName("B-5 ⚠️ 预算读不到 → fail-closed，且原因与「额度不够」可区分")
    void readFailureIsDeniedWithDistinctReason() {
        RunBudgetProbe probe = new RunBudgetProbe((tenantId, runId) -> {
            throw new IllegalStateException("预算存储不可用");
        });

        var verdict = probe.check(TENANT, RUN, new BigDecimal("1"));

        assertThat(verdict.allowed())
                .as("⚠️ 读不到预算时放行 = 预算系统故障期间无限花钱（Sev-0）")
                .isFalse();
        assertThat(verdict.denial())
                .as("⚠️ 基础设施故障与业务约束必须可区分，否则排查方向会错")
                .isEqualTo(RunBudgetProbe.Denial.READ_FAILED);
        assertThat(verdict.reasonCode()).isEqualTo("run-budget-unavailable");
    }

    @Test
    @DisplayName("⚠️ 预留金额非法 → fail-closed 拒绝（不得按「未定义额度」放行）")
    void nonPositiveAmountIsDenied() {
        var verdict = probe("100", "0", "0").check(TENANT, RUN, BigDecimal.ZERO);

        assertThat(verdict.allowed())
                .as("金额非法说明调用方或配置出了问题，此时放行等于「按未定义的额度放行」")
                .isFalse();
        assertThat(verdict.denial())
                .isEqualTo(RunBudgetProbe.Denial.INVALID_AMOUNT);
    }

    @Test
    @DisplayName("⚠️ 方向性判定（hasRemaining）只能用于委派层，不得用于工具调用闸门")
    void directionalCheckWouldAllowOverspend() {
        // ⚠️ 本用例**固化**两者的区别，不是断言 hasRemaining 有错：
        //    委派层不预留额度，只能问方向；工具层必须问量级。
        RunBudgetProbe probe = probe("100", "90", "0");

        assertThat(probe.hasRemaining(TENANT, RUN))
                .as("方向性判定：还有余额 → true（委派层用）")
                .isTrue();
        assertThat(probe.check(TENANT, RUN, new BigDecimal("50")).allowed())
                .as("量级判定：装不下 → false（工具层用）")
                .isFalse();
    }
}
