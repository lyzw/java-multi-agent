package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P3-1 ① 滑动窗口加权公式（纯函数）。
 *
 * <p>⚠️ <b>本类要钉住的边界</b>：固定窗口的缺口不在「限多少」，
 * 而在<b>窗口翻页的那一瞬间</b>——
 * 限额 30/60s 时，第 59.9 秒打满 30 次、第 60.0 秒窗口重置又来 30 次，
 * 客户端视角是「不到 0.2 秒内通过了 60 次」。
 * 滑动窗口近似要做的，就是让上一窗口的用量<b>按剩余时间衰减</b>后继续计入。
 */
class JdbcRateLimiterSlidingWindowTest {

    private static final long WINDOW = 60L;

    @Test
    @DisplayName("✅ 窗口刚翻页（elapsed=0）：上一窗口用量**全额**计入 —— 这正是边界突发的修复点")
    void windowJustRolledOverCarriesFullPreviousWeight() {
        // ⚠️ 修复前：此刻 current=0，与上一窗口无论打得多满都无关 → 立刻放行一整轮
        assertThat(JdbcRateLimiter.slidingCount(30, 0, 0, WINDOW))
                .as("上一窗口刚结束，其用量几乎还是「热」的，必须全额计入")
                .isEqualTo(30.0);
    }

    @Test
    @DisplayName("✅ 固定窗口原本的盲区：翻页瞬间连续两轮会被正确拦下")
    void boundaryBurstIsRejected() {
        long limit = 30;
        // 第 60.0 秒（新窗口第 0 秒）时：上一窗口 30 次 + 本窗口第 1 次
        double afterFirst = JdbcRateLimiter.slidingCount(30, 1, 0, WINDOW);
        assertThat(afterFirst)
                .as("边界处第 1 次请求就应已越过限额 30")
                .isGreaterThan(limit);
    }

    @Test
    @DisplayName("✅ 窗口过半：上一窗口用量只按剩余时间衰减计入（不得全额）")
    void halfWayDecaysPreviousWindow() {
        assertThat(JdbcRateLimiter.slidingCount(30, 0, 30, WINDOW))
                .as("上一窗口已过去半个窗口，衰减系数应为 0.5")
                .isEqualTo(15.0);
    }

    @Test
    @DisplayName("✅ 窗口末尾：上一窗口衰减到 0（否则限流会越用越紧，最终永久拒绝）")
    void windowEndDropsPreviousWindowEntirely() {
        assertThat(JdbcRateLimiter.slidingCount(30, 0, WINDOW, WINDOW))
                .as("衰减系数归零，否则上一窗口的影响永不消失，限流会单向收紧")
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("⛔ elapsed 越界要先夹取（时钟来自 DB，不应信任它一定落在窗口内）")
    void outOfRangeElapsedIsClamped() {
        assertThat(JdbcRateLimiter.slidingCount(30, 0, -5, WINDOW)).isEqualTo(30.0);
        assertThat(JdbcRateLimiter.slidingCount(30, 0, 999, WINDOW)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("⛔ 窗口长度非正 → 拒绝（除零会让衰减系数变成 NaN，静默放行一切）")
    void nonPositiveWindowIsRejected() {
        // ⚠️ 若不拦：decay = (0 - 0) / 0 = NaN，任何比较都为 false → 全部放行
        assertThatThrownBy(() -> JdbcRateLimiter.slidingCount(1, 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("窗口长度");
    }
}
