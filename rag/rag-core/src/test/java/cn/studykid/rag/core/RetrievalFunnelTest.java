package cn.studykid.rag.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.retrieval.RetrievalResult;
import cn.studykid.rag.core.retrieval.RetrievalResult.RetrievalFunnel;

/**
 * 过滤漏斗的不变式（RAG-D05 §7 / RAG-D12 靶点 D）。
 *
 * <p>⚠️ 本测试证明的核心命题：
 * <b>「没有结果」这件事必须可被归因</b>——是语料里没有（召回不足），
 * 还是被版本/角色/门禁滤掉了（过滤过严）。两者处置完全不同，
 * 若漏斗计数本身不可信，诊断结论也会跟着失真。
 */
class RetrievalFunnelTest {

    @Nested
    @DisplayName("漏斗的单调性")
    class Monotonicity {

        @Test
        @DisplayName("正常收窄的漏斗被接受")
        void acceptsNarrowingFunnel() {
            RetrievalFunnel f = new RetrievalFunnel(100, 72, 54, 38, 10);
            assertThat(f.candidates()).isEqualTo(100);
            assertThat(f.returned()).isEqualTo(10);
        }

        @Test
        @DisplayName("某层比上一层多 → 拒绝（计数点写错，不能静默传播）")
        void rejectsNonMonotonicFunnel() {
            // 版本过滤后比候选还多，说明计数点取错了 List
            assertThatThrownBy(() -> new RetrievalFunnel(10, 20, 20, 20, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("单调不增");
        }

        @Test
        @DisplayName("返回数超过门禁后数量 → 拒绝")
        void rejectsReturnedExceedingGate() {
            assertThatThrownBy(() -> new RetrievalFunnel(100, 72, 54, 38, 50))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("单调不增");
        }

        @Test
        @DisplayName("相等是合法的（某层未剔除任何内容）")
        void allowsEqualCounts() {
            RetrievalFunnel f = new RetrievalFunnel(10, 10, 10, 10, 10);
            assertThat(f.droppedByVersion()).isZero();
            assertThat(f.droppedByRole()).isZero();
            assertThat(f.droppedByGate()).isZero();
        }
    }

    @Nested
    @DisplayName("剔除数与主因归因")
    class Attribution {

        @Test
        @DisplayName("各层剔除数正确")
        void computesDropCounts() {
            RetrievalFunnel f = new RetrievalFunnel(100, 72, 54, 38, 10);
            assertThat(f.droppedByVersion()).isEqualTo(28);
            assertThat(f.droppedByRole()).isEqualTo(18);
            assertThat(f.droppedByGate()).isEqualTo(16);
        }

        @Test
        @DisplayName("角色过滤拦下最多 → 归因 ROLE（多为语料答案占比高）")
        void roleIsDominant() {
            // 版本只剔 2 条、角色剔 40 条
            RetrievalFunnel f = new RetrievalFunnel(100, 98, 58, 50, 10);
            assertThat(f.dominantDrop()).isEqualTo("ROLE");
        }

        @Test
        @DisplayName("版本过滤拦下最多 → 归因 VERSION（学生用的版本可能没入语料）")
        void versionIsDominant() {
            RetrievalFunnel f = new RetrievalFunnel(100, 40, 38, 36, 10);
            assertThat(f.dominantDrop()).isEqualTo("VERSION");
        }

        @Test
        @DisplayName("门禁拦下最多 → 归因 GATE（语料有但都不够相关）")
        void gateIsDominant() {
            RetrievalFunnel f = new RetrievalFunnel(100, 95, 92, 30, 10);
            assertThat(f.dominantDrop()).isEqualTo("GATE");
        }

        @Test
        @DisplayName("三层都没剔除 → 归因 NONE")
        void noneWhenNothingDropped() {
            RetrievalFunnel f = new RetrievalFunnel(10, 10, 10, 10, 5);
            assertThat(f.dominantDrop()).isEqualTo("NONE");
        }

        @Test
        @DisplayName("空漏斗各层相等、归因为 NONE（未采集时不误导）")
        void emptyFunnelIsNeutral() {
            RetrievalFunnel f = RetrievalFunnel.empty(0);
            assertThat(f.candidates()).isZero();
            assertThat(f.dominantDrop()).isEqualTo("NONE");
        }
    }

    @Nested
    @DisplayName("RetrievalResult 的向后兼容")
    class BackwardCompat {

        @Test
        @DisplayName("旧构造器（无漏斗）仍可用，漏斗退化为空")
        void legacyConstructorStillWorks() {
            RetrievalResult result = new RetrievalResult(
                    List.of(), List.of(),
                    new cn.studykid.rag.core.version.EditionCoordinate(
                            cn.studykid.rag.core.version.EditionCoordinate.Subject.MATH,
                            cn.studykid.rag.core.version.EditionCoordinate.GradeBand.GRADE_7,
                            cn.studykid.rag.core.version.EditionCoordinate.Term.FIRST,
                            cn.studykid.rag.core.version.EditionCoordinate.Publisher.PEP,
                            "2024修订"),
                    false, false);
            // ⚠️ 既有调用方（测试/主干路径）不传漏斗时，不应因缺字段而抛异常
            assertThat(result.funnel()).isNotNull();
            assertThat(result.funnel().dominantDrop()).isEqualTo("NONE");
        }
    }
}
