package cn.studykid.rag.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.core.version.EditionScope;

/**
 * 版本坐标系与版本过滤不变式。
 *
 * <p>⚠️ 教辅场景第一大事故是<b>串版本</b>：学生拿着 2024 修订版，
 * 系统却按 2012 版讲解，页码对不上、内容也有出入。
 */
class EditionIsolationTest {

    private static final EditionCoordinate PEP_7A_2024 = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_7,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static final EditionCoordinate PEP_7A_2012 = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_7,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2012");

    private static final EditionCoordinate SUEP_7A_2024 = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_7,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.SUEP, "2024修订");

    private static Chunk chunk(EditionCoordinate edition, ChunkRole role, String text) {
        return new Chunk("c-" + text.hashCode(), "doc-1", edition, role, text,
                new Chunk.Locator(10, "第1章", null, 0, text.length()),
                List.of("kp-1"), null, "hash-" + text.hashCode());
    }

    @Nested
    @DisplayName("版本坐标")
    class Coordinate {

        @Test
        @DisplayName("缺版次即拒绝构造——缺一轴则引用不可验证")
        void rejectsMissingRevision() {
            assertThatThrownBy(() -> new EditionCoordinate(
                    EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_7,
                    EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("revisionCode");
        }

        @Test
        @DisplayName("版次不同即版本不同；同册不同版仍算同册")
        void distinguishesRevision() {
            assertThat(PEP_7A_2024.sameAs(PEP_7A_2012)).isFalse();
            assertThat(PEP_7A_2024.sameBook(PEP_7A_2012)).isTrue();
            assertThat(PEP_7A_2024.sameBook(SUEP_7A_2024)).isFalse();
        }
    }

    @Nested
    @DisplayName("版本范围")
    class Scope {

        @Test
        @DisplayName("STRICT：其他版本一律不接纳，且不允许回退")
        void strictRejectsOtherEditions() {
            EditionScope scope = EditionScope.strict(PEP_7A_2024);
            assertThat(scope.admits(PEP_7A_2024)).isTrue();
            assertThat(scope.admits(PEP_7A_2012)).isFalse();
            assertThat(scope.admits(SUEP_7A_2024)).isFalse();
        }

        @Test
        @DisplayName("PREFERRED_WITH_FALLBACK：同册其他版次可接纳但需标注差异")
        void fallbackAdmitsSameBookWithDisclaimer() {
            EditionScope scope = EditionScope.preferredWithFallback(PEP_7A_2024);
            assertThat(scope.admits(PEP_7A_2012)).isTrue();
            assertThat(scope.admits(SUEP_7A_2024)).isFalse();
            assertThat(scope.requiresVersionDisclaimer(PEP_7A_2012)).isTrue();
            assertThat(scope.requiresVersionDisclaimer(PEP_7A_2024)).isFalse();
        }

        @Test
        @DisplayName("CROSS_EDITION：全部接纳且全部需标注差异")
        void crossEditionAdmitsAll() {
            EditionScope scope = EditionScope.crossEdition(PEP_7A_2024);
            assertThat(scope.admits(SUEP_7A_2024)).isTrue();
            assertThat(scope.requiresVersionDisclaimer(SUEP_7A_2024)).isTrue();
        }

        @Test
        @DisplayName("排序惩罚：首选版本惩罚为 0，同册次之，跨出版社最高")
        void rankPenaltyOrdering() {
            EditionScope scope = EditionScope.preferredWithFallback(PEP_7A_2024);
            int preferred = scope.rankPenalty(PEP_7A_2024);
            int sameBook = scope.rankPenalty(PEP_7A_2012);
            int other = scope.rankPenalty(SUEP_7A_2024);
            assertThat(preferred).isZero();
            assertThat(sameBook).isGreaterThan(preferred);
            assertThat(other).isGreaterThan(sameBook);
        }
    }

    @Nested
    @DisplayName("版本过滤（RetrievalPolicy）")
    class Filtering {

        @Test
        @DisplayName("STRICT 下过滤掉所有非首选版本片段")
        void strictFilterRemovesOtherEditions() {
            List<Chunk> chunks = List.of(
                    chunk(PEP_7A_2024, ChunkRole.EXPLANATION, "正确版本内容"),
                    chunk(PEP_7A_2012, ChunkRole.EXPLANATION, "旧版本内容"),
                    chunk(SUEP_7A_2024, ChunkRole.EXPLANATION, "其他出版社内容"));

            List<Chunk> kept = cn.studykid.rag.core.retrieval.RetrievalPolicy
                    .applyEditionFilter(chunks, EditionScope.strict(PEP_7A_2024));

            assertThat(kept).hasSize(1);
            assertThat(kept.get(0).edition()).isEqualTo(PEP_7A_2024);
        }

        @Test
        @DisplayName("反证：若过滤失效（改为全部接纳），串版本内容会被混入")
        void falsificationRemovingFilterLeaksOtherEditions() {
            List<Chunk> chunks = List.of(
                    chunk(PEP_7A_2024, ChunkRole.EXPLANATION, "正确版本内容"),
                    chunk(PEP_7A_2012, ChunkRole.EXPLANATION, "旧版本内容"));

            // 反证：模拟「过滤被绕过」的情形
            List<Chunk> leaked = chunks.stream()
                    .filter(c -> true)
                    .toList();

            assertThat(leaked).hasSize(2);
            assertThat(leaked).anyMatch(c -> c.edition().equals(PEP_7A_2012));
        }
    }
}
