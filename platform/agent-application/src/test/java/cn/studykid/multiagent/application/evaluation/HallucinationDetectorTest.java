package cn.studykid.multiagent.application.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编造出处检测单测（P1-D 扩面）。
 *
 * <p><b>本类锁的是「陈述无法核实的依据 = 编造」这条属性</b>：
 * DEC-5 首期不做 RAG，项目<b>没有任何教材语料</b>，
 * 因此模型不管是写「人教版第 3 章」还是写「如我们教材所言」，
 * 都是在凭空声称存在某个依据 —— 两者必须同等对待。
 *
 * <p>⚠️ 此前只有「形式化引用」被识别，「口语化的出处诉求」100% 放行，
 * 属<b>可构造绕过</b>；对应的逆向（不得误杀）用例见 {@link CleanMath}。
 */
@DisplayName("HallucinationDetector · 编造出处检测")
class HallucinationDetectorTest {

    @Nested
    @DisplayName("既有形式化引用（不得因扩面而失灵）")
    class FormalCitations {

        @Test
        @DisplayName("⚠️ 章节引用被判编造")
        void chapterCitation() {
            assertThat(HallucinationDetector.detect("这道题见第 3 章的讲解"))
                    .contains("H-1a");
        }

        @Test
        @DisplayName("⚠️ 教材名被判编造")
        void textbookName() {
            assertThat(HallucinationDetector.detect("人教版课本里就是这么讲的"))
                    .isNotNull();
        }

        @Test
        @DisplayName("⚠️ assertNoFabricatedSource 命中即抛（fail-closed）")
        void assertThrows() {
            assertThatThrownBy(() -> HallucinationDetector
                    .assertNoFabricatedSource("参见人教版第 3 章"))
                    .isInstanceOf(HallucinatedSourceException.class);
        }
    }

    @Nested
    @DisplayName("P1-D 新增：无引用的出处诉求（修复前的绕过口）")
    class UncitedAppeals {

        @Test
        @DisplayName("⚠️⚠️ 「如我们教材所言」必须被抓（报告点名的绕过样例）")
        void informalAppealToTextbookCaught() {
            assertThat(HallucinationDetector.detect("如我们教材所言，二次函数的顶点在 x=-b/2a"))
                    .as("⚠️⚠️ 不写引用形式却在声称依据 = 同样的编造，此前完全放行")
                    .isNotNull();
        }

        @Test
        @DisplayName("⚠️⚠️ 「研究表明 / 教育部规定」等诉诸权威被抓")
        void appealToAuthorityCaught() {
            assertThat(HallucinationDetector.detect("研究表明每天学习两小时效果最好"))
                    .isNotNull();
            assertThat(HallucinationDetector.detect("教育部规定初中不得布置额外作业"))
                    .isNotNull();
        }

        @Test
        @DisplayName("⚠️ 「老师上课讲过」「书上写的」被抓")
        void appealToClassroomCaught() {
            assertThat(HallucinationDetector.detect("这道题老师上课讲过"))
                    .isNotNull();
            assertThat(HallucinationDetector.detect("书上写的答案就是 42"))
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("干净输出：讲题本身不得被误判（否则正常讲题会被误拒）")
    class CleanMath {

        @Test
        @DisplayName("✅ 纯数学推导正常放行")
        void pureMathExplanationAllowed() {
            assertThat(HallucinationDetector.detect(
                    "由判别式 Δ=b²-4ac 可知 Δ>0 时方程有两个不等实根。"))
                    .isNull();
        }

        @Test
        @DisplayName("✅ 含数字、括号、常量的答案不得误判")
        void numbersAndParensAllowed() {
            assertThat(HallucinationDetector.detect("解为 x₁=2，x₂=3（验算：代入原式成立）"))
                    .isNull();
            assertThat(HallucinationDetector.detect("第 1 步：移项；第 2 步：合并同类项"))
                    .isNull();
        }

        @Test
        @DisplayName("✅ null / 空 / 空白视为无编造")
        void blankAllowed() {
            assertThat(HallucinationDetector.detect(null)).isNull();
            assertThat(HallucinationDetector.detect("   ")).isNull();
            assertThatCode(() -> HallucinationDetector.assertNoFabricatedSource("  "))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("✅ 诉诸公式而非资料的表述正常放行")
        void citingFormulaNotSourceAllowed() {
            assertThat(HallucinationDetector.detect("根据韦达定理，两根之和为 -b/a"))
                    .as("⚠️ 定理/公式不是「资料出处」，误判会让正常讲题全被拒")
                    .isNull();
        }
    }

    @Test
    @DisplayName("⚠️ 规则集不得被悄悄删空（否则整层静默失效）")
    void ruleSetNotEmpty() {
        assertThat(HallucinationDetector.ruleNames())
                .as("⚠️ 规则被清空会让编造检测形同虚设且无任何报错")
                .hasSizeGreaterThanOrEqualTo(8);
    }
}
