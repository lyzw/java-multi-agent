package cn.studykid.multiagent.application.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.metrics.ScenarioTag;

/**
 * 场景标签准入规则的行为单测。
 *
 * <p>⚠️ 断言一律用<b>硬编码字面量</b>，不引用 {@code ScenarioTag} 自己的常量 ——
 * 编译期内联会让「抽掉修复点」时断言跟着漂，造成反向验证假绿（项目实测教训）。
 *
 * <p>要防的失真形态：把学生提问原文当成场景标签复制进指标宽表，
 * 于是「按场景拆分」看板出现一 Run 一个分组，且合规上等于多存了一份对话原文。
 */
class ScenarioTagTest {

    @Test
    @DisplayName("未标注（null / 空串 / 全空白）一律保持 null，绝不编造默认分组")
    void absentStaysNull() {
        assertNull(ScenarioTag.normalize(null));
        assertNull(ScenarioTag.normalize(""));
        assertNull(ScenarioTag.normalize("   "));
        assertNull(ScenarioTag.normalize("\t\n"));
    }

    @Test
    @DisplayName("英文分类码原样保留")
    void asciiCodeIsAccepted() {
        assertEquals("MATH_ALGEBRA", ScenarioTag.normalize("MATH_ALGEBRA"));
    }

    @Test
    @DisplayName("中文学科/知识点分层标签原样保留（真实业务最可能的形态）")
    void chineseLayeredTagIsAccepted() {
        assertEquals("数学:一元二次方程", ScenarioTag.normalize("数学:一元二次方程"));
    }

    @Test
    @DisplayName("前后空白去除，但标签中间不得出现空白")
    void trimsOuterWhitespace() {
        assertEquals("数学:因式分解", ScenarioTag.normalize("  数学:因式分解  "));
    }

    @Test
    @DisplayName("合法分层/版本符号（/ . : + - _）可用")
    void layeredSymbolsAreAccepted() {
        assertEquals("math/quadratic:v2.1+extra_a-b",
                ScenarioTag.normalize("math/quadratic:v2.1+extra_a-b"));
    }

    @Test
    @DisplayName("整段学生提问（含空格与句读）必须被拒 —— 那是原文不是分类码")
    void freeTextQuestionIsRejected() {
        assertNull(ScenarioTag.normalize(
                "请帮我分析这道题：解方程 x^2 - 5x + 6 = 0，我算出两个解但不确定对不对"));
        assertNull(ScenarioTag.normalize("解方程 x^2"));
        assertNull(ScenarioTag.normalize("数学 一元二次方程"));
    }

    @Test
    @DisplayName("JSON 结构文本必须被拒（防止 object/array 被 ->> 转成原文进宽表）")
    void jsonStructureIsRejected() {
        assertNull(ScenarioTag.normalize("{\"subject\":\"数学\"}"));
        assertNull(ScenarioTag.normalize("[\"数学\",\"物理\"]"));
    }

    @Test
    @DisplayName("换行/控制字符一律拒绝")
    void controlCharactersAreRejected() {
        assertNull(ScenarioTag.normalize("数学\n一元二次方程"));
        assertNull(ScenarioTag.normalize("数学\t物理"));
    }

    @Test
    @DisplayName("64 字符可用、65 字符拒绝（分类码不需要一整句话）")
    void lengthCeiling() {
        String atLimit = "a".repeat(64);
        String overLimit = "a".repeat(65);
        assertEquals(atLimit, ScenarioTag.normalize(atLimit));
        assertNull(ScenarioTag.normalize(overLimit));
    }

    @Test
    @DisplayName("注入样式字符串被白名单挡住，不进看板分组")
    void injectionLikeTextIsRejected() {
        assertNull(ScenarioTag.normalize("'; DROP TABLE run; --"));
        assertNull(ScenarioTag.normalize("<script>alert(1)</script>"));
    }

    @Test
    @DisplayName("纯数字/字母数字标签可用（不做语义猜测，只做形状校验）")
    void alphanumericTagIsAccepted() {
        assertEquals("42", ScenarioTag.normalize("42"));
        assertEquals("v2", ScenarioTag.normalize("v2"));
    }
}
