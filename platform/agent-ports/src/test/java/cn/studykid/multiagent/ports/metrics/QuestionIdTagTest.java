package cn.studykid.multiagent.ports.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 题目标识白名单守卫：它比 scenario 更严，绝不允许把题面原文当题库主键。 */
@DisplayName("L2 · questionId 显式准入规则")
class QuestionIdTagTest {

    @Test
    @DisplayName("✅ 稳定题库主键形态全部放行（含 UUID / 版本点号 / 分层冒号）")
    void acceptsStableIdentifiers() {
        for (String id : new String[]{
                "Q-MATH-001",
                "9f1c1e2a-3b4c-5d6e-7f80-90a1b2c3d4e5",
                "math.algebra.2026:q7",
                "v1.2.3_Q-9",
                "A" }) {
            assertEquals(id, QuestionIdTag.normalize(id), "合法主键不应被改写: " + id);
        }
    }

    @Test
    @DisplayName("✅ 首尾空白是容错，不是新标识")
    void trimsWhitespace() {
        assertEquals("Q-MATH-001", QuestionIdTag.normalize("  Q-MATH-001 \t"));
    }

    @Test
    @DisplayName("⛔ 缺题目标识、空串、纯空白一律 null（不猜默认题号）")
    void rejectsAbsent() {
        assertNull(QuestionIdTag.normalize(null));
        assertNull(QuestionIdTag.normalize(""));
        assertNull(QuestionIdTag.normalize("   "));
    }

    @Test
    @DisplayName("⛔ 题面原文 / 中文 / 空白 / 斜杠 / 句读一律 null（绝不退化成文本匹配）")
    void rejectsFreeText() {
        for (String raw : new String[]{
                "请帮我解方程 x^2 - 5x + 6 = 0，我不确定对不对",
                "数学：一元二次方程",
                "数学/一元二次方程",
                "Q MATH 001",
                "Q,MATH;001" }) {
            assertNull(QuestionIdTag.normalize(raw), "题面不是题目标识: " + raw);
        }
    }

    @Test
    @DisplayName("⛔ 超长标识拒绝，不截断（截断会把两道题并成一道）")
    void rejectsTooLong() {
        String longId = "Q".repeat(129);
        assertNull(QuestionIdTag.normalize(longId));
        assertEquals(128, QuestionIdTag.normalize("Q".repeat(128)).length());
    }

    @Test
    @DisplayName("⛔ 控制字符拒绝（防止用隐写字符绕过精确匹配）")
    void rejectsControlCharacters() {
        assertNull(QuestionIdTag.normalize("Q-MATH\u200B-001"));
        assertNull(QuestionIdTag.normalize("Q\nMATH"));
    }
}
