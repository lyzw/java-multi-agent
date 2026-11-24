package cn.studykid.rag.premise.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExerciseNoNormalizerTest {

    @Nested
    class SingleLevel {

        @Test
        void dotAndParenAndEnglishPrefix() {
            assertThat(ExerciseNoNormalizer.normalize("1.").canonical()).isEqualTo("Q001");
            assertThat(ExerciseNoNormalizer.normalize("（2）").canonical()).isEqualTo("Q002");
            assertThat(ExerciseNoNormalizer.normalize("Exercise 3").canonical()).isEqualTo("Q003");
            assertThat(ExerciseNoNormalizer.normalize("第12题").canonical()).isEqualTo("Q012");
        }

        @Test
        void canonicalIsIdempotent() {
            assertThat(ExerciseNoNormalizer.normalize("Q007").canonical()).isEqualTo("Q007");
            assertThat(ExerciseNoNormalizer.normalize("Q007").kind())
                    .isEqualTo(ExerciseKeyKind.SINGLE);
        }
    }

    @Nested
    class MultiLevel {

        @Test
        void fourShapesCollapseToOneSubKey() {
            assertThat(ExerciseNoNormalizer.normalize("3.(2)").canonical()).isEqualTo("Q003.S02");
            assertThat(ExerciseNoNormalizer.normalize("3.2").canonical()).isEqualTo("Q003.S02");
            assertThat(ExerciseNoNormalizer.normalize("第3题（2）问").canonical())
                    .isEqualTo("Q003.S02");
            assertThat(ExerciseNoNormalizer.normalize("Q003.S02").canonical())
                    .isEqualTo("Q003.S02");
        }

        @Test
        void subKeyIsNotConfusedWithSingle() {
            assertThat(ExerciseNoNormalizer.normalize("3.(2)").kind())
                    .isEqualTo(ExerciseKeyKind.SUB);
            assertThat(ExerciseNoNormalizer.normalize("3").kind())
                    .isEqualTo(ExerciseKeyKind.SINGLE);
        }
    }

    @Nested
    class Group {

        @Test
        void rangeShapesBecomeGroupKey() {
            assertThat(ExerciseNoNormalizer.normalize("5-7").canonical())
                    .isEqualTo("GRP:Q005-Q007");
            assertThat(ExerciseNoNormalizer.normalize("Questions 5-7").canonical())
                    .isEqualTo("GRP:Q005-Q007");
            assertThat(ExerciseNoNormalizer.normalize("第5～7题").canonical())
                    .isEqualTo("GRP:Q005-Q007");
            assertThat(ExerciseNoNormalizer.normalize("GRP:Q005-Q007").canonical())
                    .isEqualTo("GRP:Q005-Q007");
        }

        @Test
        void groupKeyKeepsBoundsAndNeverFlattens() {
            ExerciseKey key = ExerciseNoNormalizer.normalize("5-7");
            assertThat(key.kind()).isEqualTo(ExerciseKeyKind.GROUP);
            assertThat(key.groupStart()).isEqualTo(5);
            assertThat(key.groupEnd()).isEqualTo(7);
            assertThat(key.canonical()).isNotEqualTo("Q005");
        }
    }

    @Nested
    class FailClosed {

        @Test
        void unrecognisableInputsAreExplicitUnmatched() {
            for (String raw : new String[] {null, "", "   ", "abc", "0", "1000", "7-5",
                    "1-30", "3.100", "第0题"}) {
                ExerciseKey key = ExerciseNoNormalizer.normalize(raw);
                assertThat(key.kind()).as("raw=%s", raw).isEqualTo(ExerciseKeyKind.UNMATCHED);
                assertThat(key.canonical()).as("raw=%s", raw).isEqualTo("UNMATCHED");
                assertThat(key.matched()).as("raw=%s", raw).isFalse();
            }
        }

        @Test
        void unmatchedKeepsRawForAudit() {
            assertThat(ExerciseNoNormalizer.normalize("???").raw()).isEqualTo("???");
        }
    }

    @Nested
    class BlockTextExtraction {

        @Test
        void recoversFullKeyFromLeadingText() {
            assertThat(ExerciseNoNormalizer.normalizeFromText("3.(2) 求 y=x^2 的最小值。")
                    .canonical()).isEqualTo("Q003.S02");
            assertThat(ExerciseNoNormalizer.normalizeFromText("5-7 填空题")
                    .canonical()).isEqualTo("GRP:Q005-Q007");
            assertThat(ExerciseNoNormalizer.normalizeFromText("Exercise 3 Read and answer")
                    .canonical()).isEqualTo("Q003");
        }

        @Test
        void plainProseIsNotATaskNumber() {
            assertThat(ExerciseNoNormalizer.normalizeFromText("这是一段正文，第 3 题在后面。")
                    .kind()).isEqualTo(ExerciseKeyKind.UNMATCHED);
            assertThat(ExerciseNoNormalizer.normalizeFromText("2cm 的线段")
                    .kind()).isEqualTo(ExerciseKeyKind.UNMATCHED);
        }
    }

    @Nested
    class AnswerVariants {

        @Test
        void multipleSubQuestionsSplitIntoDistinctKeys() {
            List<ExerciseKey> keys = ExerciseNoNormalizer.normalizeAnswerVariants(
                    "3", "(1) 8cm (2) 12cm (3) 20cm");
            assertThat(keys).extracting(ExerciseKey::canonical)
                    .containsExactly("Q003.S01", "Q003.S02", "Q003.S03");
        }

        @Test
        void noMarkerDoesNotFallBackToParent() {
            assertThat(ExerciseNoNormalizer.normalizeAnswerVariants("3", "答案是 8cm")).isEmpty();
        }

        @Test
        void groupParentAndDuplicatesAreNotGuessed() {
            assertThat(ExerciseNoNormalizer.normalizeAnswerVariants("5-7", "(1) A")).isEmpty();
            assertThat(ExerciseNoNormalizer.normalizeAnswerVariants("3", "(1) A (1) B"))
                    .extracting(ExerciseKey::canonical).containsExactly("Q003.S01");
        }

        @Test
        void numberFollowedByUnitIsNotASubQuestion() {
            assertThat(ExerciseNoNormalizer.normalizeAnswerVariants("3", "长度 2cm，宽 5cm"))
                    .isEmpty();
        }
    }

    @Test
    void scopeRequiresEditionOrPaper() {
        ExerciseScope edition = ExerciseScope.ofEdition("ed-1", "第3章 > 3.2");
        ExerciseScope paper = ExerciseScope.ofPaper("paper-1");
        assertThat(edition.sameAs(ExerciseScope.ofEdition("ed-1", "第3章 > 3.2"))).isTrue();
        assertThat(edition.sameAs(ExerciseScope.ofEdition("ed-2", "第3章 > 3.2"))).isFalse();
        assertThat(edition.sameAs(ExerciseScope.ofEdition("ed-1", "第4章"))).isFalse();
        assertThat(paper.sameAs(ExerciseScope.ofPaper("paper-1"))).isTrue();
        assertThat(paper.sameAs(ExerciseScope.ofPaper("paper-2"))).isFalse();
        assertThat(paper.sameAs(edition)).isFalse();
    }
}
