package cn.studykid.rag.management.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 语料生命周期判定（交互文档 §4.7）。
 *
 * <p>⚠️ 守的核心是**与检索侧同一口径**：过期与否决定学生能否搜到。
 * 界面说「还有 1 天」而检索侧当天已排除，是最容易让人失去信任的一类不一致。
 */
class LifecycleRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private static Instant daysAgo(long d) {
        return NOW.minusSeconds(d * 86400L);
    }

    private static Instant daysAhead(long d) {
        return NOW.plusSeconds(d * 86400L);
    }

    @Test
    @DisplayName("从未通过校验 → 无有效期")
    void neverVerified() {
        LifecycleRules.Expiry e = LifecycleRules.compute(null, 365, NOW);

        assertNull(e.expiresAt());
        assertNull(e.daysUntilExpiry());
        assertFalse(e.stale());
        assertFalse(e.expiringSoon());
        assertEquals("尚未通过校验，没有有效期", LifecycleRules.describe(e));
    }

    @Test
    @DisplayName("有效期内 → 未过期，剩余天数正确")
    void withinValidity() {
        LifecycleRules.Expiry e = LifecycleRules.compute(daysAgo(100), 365, NOW);

        assertFalse(e.stale());
        assertEquals(265, e.daysUntilExpiry());
        assertTrue(LifecycleRules.describe(e).contains("剩余 265 天"));
    }

    @Test
    @DisplayName("已过到期日 → 过期（学生搜不到）")
    void expired() {
        LifecycleRules.Expiry e = LifecycleRules.compute(daysAgo(400), 365, NOW);

        assertTrue(e.stale());
        assertTrue(LifecycleRules.describe(e).contains("已过期"));
    }

    @Test
    @DisplayName("⚠️ 边界：到期时刻恰好等于 now → 检索侧判「仍有效」，此处也不得报过期")
    void boundaryEqualsNowIsStillValid() {
        // verified_at + 365 天 = NOW 的情况
        LifecycleRules.Expiry e = LifecycleRules.compute(daysAgo(365), 365, NOW);

        assertFalse(e.stale(), "到期时刻等于当前时刻时，按 >= 口径仍有效");
        assertEquals(0, e.daysUntilExpiry());
    }

    @Test
    @DisplayName("即将到期（30 天内）→ expiringSoon，文案提示尽快续期")
    void expiringSoon() {
        LifecycleRules.Expiry e = LifecycleRules.compute(daysAgo(345), 365, NOW);

        assertFalse(e.stale());
        assertEquals(20, e.daysUntilExpiry());
        assertTrue(e.expiringSoon());
        assertTrue(LifecycleRules.describe(e).contains("将于 20 天后过期"));
    }

    @Test
    @DisplayName("超过 30 天 → 不算即将到期（避免长期告警失效）")
    void notExpiringSoonFarOut() {
        LifecycleRules.Expiry e = LifecycleRules.compute(daysAgo(100), 365, NOW);

        assertFalse(e.expiringSoon());
    }

    @Test
    @DisplayName("阈值边界：恰好 30 天算「即将到期」")
    void exactlyThresholdIsSoon() {
        LifecycleRules.Expiry e = LifecycleRules.compute(daysAgo(335), 365, NOW);

        assertEquals(LifecycleRules.EXPIRING_SOON_DAYS, e.daysUntilExpiry());
        assertTrue(e.expiringSoon());
    }

    @Test
    @DisplayName("validDays ≤ 0 视为无效配置，按未通过处理")
    void invalidValidDays() {
        assertNull(LifecycleRules.compute(daysAgo(10), 0, NOW).expiresAt());
        assertNull(LifecycleRules.compute(daysAgo(10), -5, NOW).expiresAt());
    }
}
