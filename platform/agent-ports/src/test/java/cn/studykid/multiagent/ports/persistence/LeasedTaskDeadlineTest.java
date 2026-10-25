package cn.studykid.multiagent.ports.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 已领取任务的<b>业务截止时间</b>语义测试（多级 Agent v1.1 · FR-024 · MA-T09）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：{@code leaseExpiresAt}（租约到期）与
 * {@code deadlineAt}（业务截止）是<b>两件不同的事</b>：
 * <ul>
 *   <li>租约到期 = 「这个 worker 该换手了」，另一个 worker 接手<b>继续跑</b>；</li>
 *   <li>业务截止 = 「这个 Run 该结束了」。</li>
 * </ul>
 *
 * <p>混用会出两个错：把「换手」误判成「超时」（白停一个还在正常跑的 Run），
 * 或让<b>已超时</b>的 Run 因租约续期而无限跑下去 ——
 * 后者正是多级 Agent「全调用树共享超时上限」要防的事。
 */
class LeasedTaskDeadlineTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("✅ 无截止时间 → 不限时（返回上界，不得当成「剩余 0」）")
    void noDeadlineMeansUnlimited() {
        var task = new RunClaimPort.LeasedTask("run-1", 1L, NOW.plusSeconds(60), 1);

        assertThat(task.deadlineAt()).isNull();
        assertThat(task.remainingMillis(NOW))
                .as("⚠️ 未声明截止时间的 Run 不限时 —— "
                        + "当成 0 会把所有委派都误判成超时")
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("✅ 有截止时间 → 返回真实剩余毫秒")
    void remainingIsComputedFromDeadline() {
        var task = new RunClaimPort.LeasedTask("run-1", 1L, NOW.plusSeconds(60), 1,
                NOW.plusSeconds(30));

        assertThat(task.remainingMillis(NOW)).isEqualTo(30_000L);
        assertThat(task.remainingMillis(NOW.plusSeconds(20)))
                .as("剩余时间必须随时间推移而减少")
                .isEqualTo(10_000L);
    }

    @Test
    @DisplayName("✅ 已过截止时刻 → 剩余 0（不得返回负数）")
    void expiredDeadlineYieldsZero() {
        var task = new RunClaimPort.LeasedTask("run-1", 1L, NOW.plusSeconds(60), 1,
                NOW.minusSeconds(5));

        assertThat(task.remainingMillis(NOW))
                .as("⚠️ 已过期必须是 0 —— 负数会让下游做减法时越算越远")
                .isZero();
    }

    @Test
    @DisplayName("✅ 租约到期与业务截止互不干扰")
    void leaseAndDeadlineAreIndependent() {
        // 租约 1 秒后到期，但业务截止在 60 秒后
        var task = new RunClaimPort.LeasedTask("run-1", 1L, NOW.plusSeconds(1), 1,
                NOW.plusSeconds(60));

        assertThat(task.isLeaseExpired(NOW.plusSeconds(2)))
                .as("租约确实过期了（换手场景）")
                .isTrue();
        assertThat(task.remainingMillis(NOW.plusSeconds(2)))
                .as("⚠️ 但业务截止还没到 → 剩余时间必须照常算，不能被租约影响")
                .isEqualTo(58_000L);
    }
}
