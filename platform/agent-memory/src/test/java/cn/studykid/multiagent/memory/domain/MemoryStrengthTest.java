package cn.studykid.multiagent.memory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 记忆强度模型（P1）。
 *
 * <p>⚠️ <b>本测试锁定的是"决策边界"，不是"数字"</b>：
 * 强度公式的常数可以调，但下面几条**行为断言**不能变 ——
 * 它们每一条都对应一个设计决策，改动等于改设计：
 * <ol>
 *   <li>{@code n = 1} 永远进不了上下文（防一次错误结论被固化）；</li>
 *   <li>{@code n = 2} 当天可以进（否则记忆功能等于恒不生效）；</li>
 *   <li>自然遗忘必须<b>两个条件同时</b>成立（强度低 且 久未出现）；</li>
 *   <li>未来时间戳按最悲观处理（防时钟回拨绕过全部衰减）。</li>
 * </ol>
 */
@DisplayName("P1 记忆强度模型")
class MemoryStrengthTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    @Nested
    @DisplayName("base(n)：证据次数 → 强度基线")
    class Base {

        @Test
        @DisplayName("n=1 的基线低于召回线 —— 一次错误绝不进上下文")
        void singleEvidenceCannotReachRecallLine() {
            double base = MemoryStrength.base(1);
            // 0.295 —— 精确锚定，改公式必然失败
            assertEquals(0.295, base, 0.001);
            assertTrue(base < MemoryStrength.RECALL_MIN_STRENGTH,
                    "n=1 的基线必须低于召回线：" + base);
        }

        @Test
        @DisplayName("n=2 的基线高于召回线 —— 两次独立证据即可召回")
        void twoEvidencesReachRecallLine() {
            double base = MemoryStrength.base(2);
            assertEquals(0.503, base, 0.001);
            assertTrue(base >= MemoryStrength.RECALL_MIN_STRENGTH,
                    "n=2 的基线必须达到召回线：" + base);
        }

        @Test
        @DisplayName("基线单调递增且始终 < 1 —— 不会出现强度 1.0 的绝对结论")
        void baseIsMonotonicAndBounded() {
            double previous = 0.0;
            for (int n = 1; n <= 100; n++) {
                double base = MemoryStrength.base(n);
                assertTrue(base > previous, "n=" + n + " 的基线必须大于 n=" + (n - 1));
                assertTrue(base < 1.0, "n=" + n + " 的基线必须严格小于 1");
                previous = base;
            }
        }

        @Test
        @DisplayName("n < 1 按 1 处理 —— 脏数据不产生负强度")
        void nonPositiveCountFallsBackToOne() {
            assertEquals(MemoryStrength.base(1), MemoryStrength.base(0), 1e-12);
            assertEquals(MemoryStrength.base(1), MemoryStrength.base(-5), 1e-12);
        }
    }

    @Nested
    @DisplayName("compute：读时衰减")
    class Compute {

        @Test
        @DisplayName("当天的强度等于基线 —— 刚出现不该被衰减")
        void todayEqualsBase() {
            assertEquals(MemoryStrength.base(3),
                    MemoryStrength.compute(3, NOW, NOW), 1e-12);
        }

        @Test
        @DisplayName("半衰期 ≈ 69 天 —— 强度衰减到基线的一半")
        void halfLifeIsAbout69Days() {
            double halfLife = MemoryStrength.halfLifeDays();
            assertEquals(69.3, halfLife, 0.2);

            Instant then = NOW.minus(Duration.ofDays(Math.round(halfLife)));
            double decayed = MemoryStrength.compute(5, then, NOW);
            assertEquals(MemoryStrength.base(5) * 0.5, decayed, 0.01);
        }

        @Test
        @DisplayName("n=5 的记忆在 180 天时衰减到自然遗忘线附近")
        void strongMemoryConvergesWithForgetThresholdAfter180Days() {
            Instant then = NOW.minus(Duration.ofDays(MemoryStrength.NATURAL_FORGET_DAYS));
            double decayed = MemoryStrength.compute(5, then, NOW);
            // 设计意图：两条规则在 180 天附近咬合（不是硬相等，但同一量级）
            assertEquals(MemoryStrength.MIN_STRENGTH, decayed, 0.05);
        }

        @Test
        @DisplayName("lastSeenAt 为 null 按最悲观处理（180 天前）")
        void nullLastSeenIsPessimistic() {
            double withNull = MemoryStrength.compute(2, null, NOW);
            double with180Days = MemoryStrength.compute(
                    2, NOW.minus(Duration.ofDays(180)), NOW);
            assertEquals(with180Days, withNull, 1e-9);
        }

        @Test
        @DisplayName("lastSeenAt == now → 按 0 天衰减（写入/读取时钟粒度差是常态）")
        void lastSeenNowMeansNoDecay() {
            // ⚠️ 这是一条**被实测打出来的断言**：初版实现把"时间戳不早于 now"
            //    一律当未来并取 180 天，结果刚写入的高强度记忆当场变成 0.107，
            //    永远进不了上下文 —— 症状是"记忆功能完全不生效"，
            //    而且只在真跑时出现（单元测试若只喂过去的时刻就永远不会发现）。
            double equal = MemoryStrength.compute(2, NOW, NOW);
            assertEquals(MemoryStrength.base(2), equal, 1e-12);
            assertTrue(equal >= MemoryStrength.RECALL_MIN_STRENGTH);
        }

        @Test
        @DisplayName("未来时间戳在容差内按 0 天；超出容差按最悲观 —— 防时钟回拨绕过衰减")
        void futureTimestampBeyondToleranceIsPessimistic() {
            // 容差内（毫秒级/秒级偏差）：视为刚出现
            double slightlyAhead = MemoryStrength.compute(2, NOW.plus(Duration.ofSeconds(30)), NOW);
            assertEquals(MemoryStrength.base(2), slightlyAhead, 1e-12);

            // 超出容差（真正的时钟错乱）：按最悲观处理
            double wayAhead = MemoryStrength.compute(2, NOW.plus(Duration.ofDays(30)), NOW);
            assertEquals(MemoryStrength.compute(2, null, NOW), wayAhead, 1e-9);
            assertTrue(wayAhead < MemoryStrength.base(2),
                    "超出容差的未来时间戳必须被衰减，否则一次时钟回拨等于绕过全部衰减");
        }

        @Test
        @DisplayName("结果恒落在 [0, 1] 内")
        void alwaysClamped() {
            assertTrue(MemoryStrength.compute(1, NOW.minus(Duration.ofDays(100_000)), NOW) >= 0.0);
            assertTrue(MemoryStrength.compute(1000, NOW, NOW) <= 1.0);
        }
    }

    @Nested
    @DisplayName("isRecallable：召回门槛")
    class Recallable {

        @Test
        @DisplayName("默认门槛即领域下限 0.40")
        void defaultThresholdIsDomainFloor() {
            assertTrue(MemoryStrength.isRecallable(0.40));
            assertFalse(MemoryStrength.isRecallable(0.399));
        }

        @Test
        @DisplayName("运行时门槛可上调，判定按上调后的值")
        void runtimeThresholdApplies() {
            assertTrue(MemoryStrength.isRecallable(0.62, MemoryStrength.RECALL_MIN_STRENGTH));
            assertFalse(MemoryStrength.isRecallable(0.62, 0.80));
        }
    }

    @Nested
    @DisplayName("isNaturallyForgotten：自然遗忘")
    class Forgotten {

        @Test
        @DisplayName("强度低 但 时间不够久 → 不遗忘")
        void lowStrengthButRecentIsKept() {
            // n=1 且 200 天前：强度约 0.039（很低），但……
            // 这里反过来测：n=1 且 100 天前，强度约 0.108 —— 低且时间不够
            assertFalse(MemoryStrength.isNaturallyForgotten(1, NOW.minus(Duration.ofDays(100)), NOW));
        }

        @Test
        @DisplayName("时间久 但 强度仍高 → 不遗忘")
        void oldButStrongIsKept() {
            // n=20 的基线约 0.999，180 天后约 0.165 > 0.15
            double s = MemoryStrength.compute(20, NOW.minus(Duration.ofDays(180)), NOW);
            assertTrue(s > MemoryStrength.MIN_STRENGTH, "前提：n=20 在 180 天后仍高于遗忘线: " + s);
            assertFalse(MemoryStrength.isNaturallyForgotten(20, NOW.minus(Duration.ofDays(180)), NOW));
        }

        @Test
        @DisplayName("强度低 且 时间久 → 遗忘")
        void lowAndOldIsForgotten() {
            assertTrue(MemoryStrength.isNaturallyForgotten(1, NOW.minus(Duration.ofDays(400)), NOW));
        }

        @Test
        @DisplayName("lastSeenAt 为 null → 视为满足时间条件")
        void nullLastSeenCountsAsOld() {
            assertTrue(MemoryStrength.isNaturallyForgotten(1, null, NOW));
        }
    }

    @Nested
    @DisplayName("MemoryEntry：值对象校验")
    class Entry {

        private static final String STU = "stu_0123456789abcdef";

        private static MemoryEntry valid() {
            return new MemoryEntry(STU, "一元二次方程", "概念不清", 0.62, 2,
                    NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(3)), null);
        }

        @Test
        @DisplayName("合法条目可构造，且知识点两端空白被归一化")
        void trimsKnowledgePoint() {
            MemoryEntry e = new MemoryEntry(STU, "  一元二次方程  ", "概念不清", 0.62, 2,
                    NOW, NOW, null);
            assertEquals("一元二次方程", e.knowledgePoint());
        }

        @Test
        @DisplayName("学生标识格式不合法 → 拒绝（与 DDL CHECK 同源）")
        void rejectsBadStudentId() {
            assertThrows(IllegalArgumentException.class, () -> new MemoryEntry(
                    "stu_XYZ", "kp", "概念不清", 0.5, 1, NOW, NOW, null));
            assertThrows(IllegalArgumentException.class, () -> new MemoryEntry(
                    "", "kp", "概念不清", 0.5, 1, NOW, NOW, null));
        }

        @Test
        @DisplayName("错误类型不在 6 值枚举内 → 拒绝")
        void rejectsUnknownErrorType() {
            assertThrows(IllegalArgumentException.class, () -> new MemoryEntry(
                    STU, "kp", "粗心", 0.5, 1, NOW, NOW, null));
        }

        @Test
        @DisplayName("强度越界 → 拒绝")
        void rejectsOutOfRangeStrength() {
            assertThrows(IllegalArgumentException.class, () -> new MemoryEntry(
                    STU, "kp", "概念不清", 1.5, 1, NOW, NOW, null));
            assertThrows(IllegalArgumentException.class, () -> new MemoryEntry(
                    STU, "kp", "概念不清", Double.NaN, 1, NOW, NOW, null));
        }

        @Test
        @DisplayName("evidenceCount < 1 → 拒绝")
        void rejectsZeroEvidence() {
            assertThrows(IllegalArgumentException.class, () -> new MemoryEntry(
                    STU, "kp", "概念不清", 0.5, 0, NOW, NOW, null));
        }

        @Test
        @DisplayName("lastSeenAt 早于 firstSeenAt → 拒绝")
        void rejectsInvertedTimestamps() {
            assertThrows(IllegalArgumentException.class, () -> new MemoryEntry(
                    STU, "kp", "概念不清", 0.5, 1, NOW, NOW.minus(Duration.ofDays(1)), null));
        }

        @Test
        @DisplayName("知识点超长 → 拒绝（与 DDL 的 100 字符上限同源）")
        void rejectsTooLongKnowledgePoint() {
            assertThrows(IllegalArgumentException.class, () -> new MemoryEntry(
                    STU, "知".repeat(101), "概念不清", 0.5, 1, NOW, NOW, null));
        }

        @Test
        @DisplayName("renderLine 单行、不含围栏 —— 围栏由 PromptGuard 统一包")
        void renderLineIsSingleLineWithoutFence() {
            String line = valid().renderLine(NOW);
            assertFalse(line.contains("\n"), "渲染片段不得含换行: " + line);
            assertFalse(line.contains("memory_context"), "渲染片段不得自带围栏: " + line);
            assertTrue(line.contains("一元二次方程"));
            assertTrue(line.contains("概念不清"));
            assertTrue(line.contains("出现 2 次"));
            assertTrue(line.contains("最近 3 天前"));
        }

        @Test
        @DisplayName("estimatedTokens 与 renderLine 的估算一致")
        void estimatedTokensMatchesRenderedText() {
            MemoryEntry e = valid();
            assertEquals(MemoryEntry.estimateTokens(e.renderLine(NOW)), e.estimatedTokens(NOW));
        }
    }

    @Nested
    @DisplayName("estimateTokens：中文 1 字 1 token，其余 3 字符 1 token")
    class Tokens {

        @Test
        @DisplayName("空文本 → 0")
        void emptyIsZero() {
            assertEquals(0, MemoryEntry.estimateTokens(null));
            assertEquals(0, MemoryEntry.estimateTokens(""));
        }

        @Test
        @DisplayName("纯中文按字数计")
        void cjkCountsPerChar() {
            assertEquals(6, MemoryEntry.estimateTokens("一元二次方程"));
        }

        @Test
        @DisplayName("纯英文按 3 字符 1 token（向上取整）")
        void latinCountsPerThreeChars() {
            assertEquals(2, MemoryEntry.estimateTokens("abcdef"));
            assertEquals(1, MemoryEntry.estimateTokens("ab"));
        }
    }
}
