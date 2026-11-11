package cn.studykid.multiagent.application.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.config.ModelPricing;

/** 评估科目独立日预算：预留 / 真实结算 / 超限拒绝 / 跨日归零。 */
@DisplayName("L2 · 独立评估科目日预算")
class EvaluationCostBudgetTest {

    private static final ModelPricing PRICE =
            new ModelPricing(new BigDecimal("1"), new BigDecimal("2"));

    private static Clock fixed() {
        return Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("✅ 调用前预留最坏上限，调用后按真实 token 结算并释放预留")
    void reserveThenSettleWithRealTokens() {
        EvaluationCostBudget budget =
                new EvaluationCostBudget(new BigDecimal("1.00"), fixed());

        var decision = budget.tryReserve(PRICE, 100, 100);
        assertTrue(decision.allowed());
        assertEquals(0, budget.snapshot().spent().signum(), "调用前不得先记已花");
        assertMoney("0.300", budget.snapshot().reserved());

        // 真实用量 100 in / 50 out = 0.1 + 0.1 = 0.2 元
        budget.settle(decision, PRICE, 100, 50);

        assertMoney("0.200", budget.snapshot().spent());
        assertEquals(0, budget.snapshot().reserved().signum(), "结算后预留必须归零");
    }

    @Test
    @DisplayName("⛔ 最坏上限超日预算：直接拒绝，判定器不得调用模型")
    void overDailyLimitIsRejected() {
        EvaluationCostBudget budget =
                new EvaluationCostBudget(new BigDecimal("0.25"), fixed());

        var decision = budget.tryReserve(PRICE, 100, 100);

        assertFalse(decision.allowed());
        assertEquals("DAILY_LIMIT_REACHED", decision.reason());
        assertEquals(0, budget.snapshot().reserved().signum());
    }

    @Test
    @DisplayName("✅ 模型异常时释放预留：失败调用不记成本，也不永久占住预算")
    void failedCallReleasesReservation() {
        EvaluationCostBudget budget =
                new EvaluationCostBudget(new BigDecimal("1.00"), fixed());

        var first = budget.tryReserve(PRICE, 100, 100);
        budget.release(first);
        assertEquals(0, budget.snapshot().reserved().signum());
        assertEquals(0, budget.snapshot().spent().signum());

        // 释放后同一日仍可再次尝试（不是一次失败就永久封顶）
        assertTrue(budget.tryReserve(PRICE, 100, 100).allowed());
    }

    @Test
    @DisplayName("⛔ 当日已花会累加进上限判断：评估越勤越早降级，不超支")
    void spentAccumulatesTowardsCap() {
        EvaluationCostBudget budget =
                new EvaluationCostBudget(new BigDecimal("0.45"), fixed());

        var first = budget.tryReserve(PRICE, 100, 100);
        budget.settle(first, PRICE, 100, 50); // 已花 0.2
        var second = budget.tryReserve(PRICE, 100, 100); // 需再预留 0.3 → 0.5 > 0.45

        assertFalse(second.allowed());
        assertMoney("0.200", budget.snapshot().spent());
    }

    @Test
    @DisplayName("✅ UTC 自然日切换后预算归零（新的一天重新有额度）")
    void newUtcDayResets() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-11T23:59:00Z"));
        EvaluationCostBudget budget = new EvaluationCostBudget(new BigDecimal("0.45"), clock);

        var first = budget.tryReserve(PRICE, 100, 100);
        budget.settle(first, PRICE, 100, 50);
        assertMoney("0.200", budget.snapshot().spent());

        clock.instant = Instant.parse("2026-11-12T00:00:01Z");
        var nextDay = budget.tryReserve(PRICE, 100, 100);

        assertTrue(nextDay.allowed());
        assertEquals(LocalDate.parse("2026-11-12"), budget.snapshot().date());
        assertEquals(0, budget.snapshot().spent().signum(), "新日不得继承昨日已花");
    }

    @Test
    @DisplayName("⛔ 日预算 0 时任何需付费的 judge 调用都被拒（一分钱不许花）")
    void zeroLimitRejectsAnyPricedCall() {
        EvaluationCostBudget budget =
                new EvaluationCostBudget(BigDecimal.ZERO, fixed());

        assertFalse(budget.tryReserve(PRICE, 1, 1).allowed());
    }

    @Test
    @DisplayName("⛔ 负预算拒绝构造（配置写错要可见，不静默当 0）")
    void negativeLimitRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationCostBudget(new BigDecimal("-1"), fixed()));
    }

    /** 金额按数值比较：ModelPricing 统一保留 6 位小数（0.200000 ≡ 0.200）。 */
    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "金额不符: 期望 " + expected + "，实际 " + actual);
    }

    /** 可推进的 UTC 时钟，只用于跨日断言。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
