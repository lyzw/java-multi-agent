package cn.studykid.multiagent.ports.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Run 资源上限的语义测试（多级 Agent v1.1 · FR-024 · MA-T17）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：{@code null} 的含义是「<b>未声明</b>」，
 * 不是「额度为 0」。两者混淆会双向出事：
 * <ul>
 *   <li>「未声明」被当成「0」→ 所有调用被拒（功能不可用）；</li>
 *   <li>「0」被当成「未声明」→ 明确禁止花钱的意图失效（约束形同虚设）。</li>
 * </ul>
 */
class RunLimitsTest {

    @Test
    @DisplayName("✅ 未声明任何限制 → 两者皆空，且 anyLimit() 为假")
    void unlimitedHasNoLimit() {
        assertThat(RunLimits.UNLIMITED.deadlineAt()).isNull();
        assertThat(RunLimits.UNLIMITED.budgetAmount()).isNull();
        assertThat(RunLimits.UNLIMITED.anyLimit()).isFalse();
    }

    @Test
    @DisplayName("✅ 额度为 0 或负数 → 构造即拒（那是「明确禁止」，不是「未声明」）")
    void nonPositiveBudgetIsRejected() {
        assertThatThrownBy(() -> new RunLimits(null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预算额度");
        assertThatThrownBy(() -> new RunLimits(null, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ 额度为正 → 接受；仅声明额度时截止时刻仍为空")
    void positiveBudgetIsAccepted() {
        var limits = RunLimits.withBudget(new BigDecimal("10"));

        assertThat(limits.budgetAmount()).isEqualByComparingTo("10");
        assertThat(limits.deadlineAt()).isNull();
        assertThat(limits.anyLimit()).isTrue();
    }

    @Test
    @DisplayName("✅ 截止时刻可单独声明；两者可同时声明")
    void deadlineAndBudgetCanBeCombined() {
        Instant deadline = Instant.parse("2026-01-01T00:00:00Z");
        var onlyDeadline = RunLimits.withDeadline(deadline);
        var both = new RunLimits(deadline, new BigDecimal("5"));

        assertThat(onlyDeadline.deadlineAt()).isEqualTo(deadline);
        assertThat(onlyDeadline.budgetAmount()).isNull();
        assertThat(both.deadlineAt()).isEqualTo(deadline);
        assertThat(both.budgetAmount()).isEqualByComparingTo("5");
    }
}
