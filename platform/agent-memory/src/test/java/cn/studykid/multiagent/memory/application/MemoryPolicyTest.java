package cn.studykid.multiagent.memory.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.memory.domain.MemoryStrength;

/**
 * 记忆配置解析（P1）。
 *
 * <p>⚠️ <b>本类的取舍与保留期配置不同，必须被测试固定住</b>：
 * 保留期缺失 → 拒绝启动（PIPL 无安全默认值）；
 * 记忆缺失 → <b>可以</b>安全退化为"关闭"（记忆是增强，不是合规义务）。
 * 但"已声明 enabled=true"之后，所有数值项都必须真出现且合法 ——
 * 否则"配好了"与"配错了静默降级"在外部无法区分。
 */
@DisplayName("P1 记忆配置解析")
class MemoryPolicyTest {

    @Test
    @DisplayName("开关：缺失/空 → 关闭（宁可不注入）")
    void enabledDefaultsToFalse() {
        assertFalse(MemoryPolicy.parseEnabled(null));
        assertFalse(MemoryPolicy.parseEnabled(""));
        assertFalse(MemoryPolicy.parseEnabled("  "));
        assertFalse(MemoryPolicy.parseEnabled("false"));
        assertFalse(MemoryPolicy.parseEnabled("FALSE"));
        assertTrue(MemoryPolicy.parseEnabled("true"));
        assertTrue(MemoryPolicy.parseEnabled("TRUE"));
    }

    @Test
    @DisplayName("开关：非法值 → fail-closed")
    void invalidEnabledRejected() {
        assertThrows(IllegalStateException.class, () -> MemoryPolicy.parseEnabled("yes"));
        assertThrows(IllegalStateException.class, () -> MemoryPolicy.parseEnabled("1"));
    }

    @Test
    @DisplayName("召回条数：区间 [1, 20]")
    void recallMaxItemsRange() {
        assertEquals(1, MemoryPolicy.requireRecallMaxItems("1"));
        assertEquals(5, MemoryPolicy.requireRecallMaxItems("5"));
        assertEquals(20, MemoryPolicy.requireRecallMaxItems("20"));
        assertThrows(IllegalStateException.class, () -> MemoryPolicy.requireRecallMaxItems("0"));
        assertThrows(IllegalStateException.class, () -> MemoryPolicy.requireRecallMaxItems("21"));
        assertThrows(IllegalStateException.class, () -> MemoryPolicy.requireRecallMaxItems(null));
        assertThrows(IllegalStateException.class, () -> MemoryPolicy.requireRecallMaxItems("abc"));
    }

    @Test
    @DisplayName("召回 token：区间 [64, 4000]")
    void recallMaxTokensRange() {
        assertEquals(64, MemoryPolicy.requireRecallMaxTokens("64"));
        assertEquals(800, MemoryPolicy.requireRecallMaxTokens("800"));
        assertEquals(4000, MemoryPolicy.requireRecallMaxTokens("4000"));
        assertThrows(IllegalStateException.class, () -> MemoryPolicy.requireRecallMaxTokens("63"));
        assertThrows(IllegalStateException.class, () -> MemoryPolicy.requireRecallMaxTokens("4001"));
        assertThrows(IllegalStateException.class, () -> MemoryPolicy.requireRecallMaxTokens(null));
    }

    @Test
    @DisplayName("召回门槛：不得低于自然遗忘线 0.15 —— 否则「该忘的还在影响判断」")
    void recallMinStrengthFloorIsForgetLine() {
        assertEquals(MemoryStrength.MIN_STRENGTH,
                MemoryPolicy.requireRecallMinStrength("0.15"), 1e-9);
        assertEquals(0.40, MemoryPolicy.requireRecallMinStrength("0.40"), 1e-9);
        assertEquals(1.0, MemoryPolicy.requireRecallMinStrength("1.0"), 1e-9);

        assertThrows(IllegalStateException.class,
                () -> MemoryPolicy.requireRecallMinStrength("0.10"));
        assertThrows(IllegalStateException.class,
                () -> MemoryPolicy.requireRecallMinStrength("1.5"));
        assertThrows(IllegalStateException.class,
                () -> MemoryPolicy.requireRecallMinStrength("NaN"));
        assertThrows(IllegalStateException.class,
                () -> MemoryPolicy.requireRecallMinStrength(null));
    }

    @Test
    @DisplayName("裁剪预算下限必须足以让 trim 不抛异常（> 0）")
    void minTrimBudgetIsPositive() {
        // ContextBudgetTrimmer.trim:127–130 在 budget <= 0 时直接抛异常。
        // 记忆扣减若把预算压到 0，此后每一个 Run 都会在第一步失败。
        assertTrue(MemoryPolicy.MIN_TRIM_BUDGET > 0);
        assertTrue(MemoryPolicy.MIN_TRIM_BUDGET >= 512, "下限过小会让裁剪退化为永远丢光上下文");
    }
}
