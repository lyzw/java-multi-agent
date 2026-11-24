package cn.studykid.rag.premise.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.chunk.LayoutFacts;
import cn.studykid.rag.core.version.EditionCoordinate;

class QuestionAnswerPairerTest {

    private static final EditionCoordinate ED = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");
    private static final EditionCoordinate OTHER_ED = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_9,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static Chunk chunk(String id, String docId, EditionCoordinate ed,
                               String sectionPath, String rawRef, ChunkRole role,
                               LayoutFacts facts) {
        return new Chunk(id, docId, ed, role, "题目或答案正文",
                new Chunk.Locator(7, sectionPath, rawRef, 12, 30),
                List.of("kp-1"), rawRef, "hash-" + id, facts);
    }

    private static PairingItem question(Chunk c, String no) {
        return PairingItem.question(c, ExerciseNoNormalizer.normalize(no));
    }

    private static PairingItem answer(Chunk c, String no) {
        return PairingItem.answer(c, ExerciseNoNormalizer.normalize(no));
    }

    @Nested
    class SameDocumentFirst {

        @Test
        void picksSameBookQuestionWhenBothBooksHaveIt() {
            Chunk qSame = chunk("q-same", "doc-1", ED, "第3章", "3", ChunkRole.EXERCISE, null);
            Chunk qOther = chunk("q-other", "doc-2", ED, "第3章", "3", ChunkRole.EXERCISE, null);
            Chunk a = chunk("a-1", "doc-1", ED, "第3章", "3", ChunkRole.EXERCISE_ANSWER, null);

            PairingResult result = QuestionAnswerPairer.pair(List.of(
                    question(qSame, "3"), question(qOther, "3"), answer(a, "3")));

            assertThat(result.matchedCount()).isEqualTo(1);
            AnswerLink link = result.links().get(0);
            assertThat(link.status()).isEqualTo(PairingStatus.SAME_DOCUMENT);
            assertThat(link.questionChunkId()).isEqualTo("q-same");
        }
    }

    @Nested
    class CrossDocument {

        @Test
        void matchesOnlyWhenEditionAndSectionBothAgree() {
            Chunk q = chunk("q-1", "book", ED, "第3章 > 3.2", "3.(2)",
                    ChunkRole.EXERCISE, null);
            Chunk a = chunk("a-1", "answers", ED, "第3章 > 3.2", "3.(2)",
                    ChunkRole.EXERCISE_ANSWER, null);

            PairingResult result = QuestionAnswerPairer.pair(List.of(question(q, "3.(2)"),
                    answer(a, "3.(2)")));

            assertThat(result.links().get(0).status()).isEqualTo(PairingStatus.CROSS_DOCUMENT);
            assertThat(result.links().get(0).questionChunkId()).isEqualTo("q-1");
        }

        @Test
        void differentEditionOrSectionNeverMatches() {
            Chunk qEd = chunk("q-ed", "book", OTHER_ED, "第3章", "3", ChunkRole.EXERCISE, null);
            Chunk qSection = chunk("q-sec", "book", ED, "第4章", "3", ChunkRole.EXERCISE, null);
            Chunk a = chunk("a-1", "answers", ED, "第3章", "3",
                    ChunkRole.EXERCISE_ANSWER, null);

            PairingResult byEdition = QuestionAnswerPairer.pair(
                    List.of(question(qEd, "3"), answer(a, "3")));
            PairingResult bySection = QuestionAnswerPairer.pair(
                    List.of(question(qSection, "3"), answer(a, "3")));

            assertThat(byEdition.links().get(0).status()).isEqualTo(PairingStatus.UNMATCHED);
            assertThat(bySection.links().get(0).status()).isEqualTo(PairingStatus.UNMATCHED);
            assertThat(byEdition.links().get(0).questionChunkId()).isNull();
            assertThat(bySection.links().get(0).questionChunkId()).isNull();
        }
    }

    @Nested
    class FailClosed {

        @Test
        void ambiguousCandidatesAreNotBound() {
            Chunk q1 = chunk("q-1", "doc-1", ED, "第3章", "3", ChunkRole.EXERCISE, null);
            Chunk q2 = chunk("q-2", "doc-1", ED, "第3章", "3", ChunkRole.EXERCISE, null);
            Chunk a = chunk("a-1", "doc-1", ED, "第3章", "3", ChunkRole.EXERCISE_ANSWER, null);

            PairingResult result = QuestionAnswerPairer.pair(
                    List.of(question(q1, "3"), question(q2, "3"), answer(a, "3")));

            assertThat(result.links().get(0).status()).isEqualTo(PairingStatus.UNMATCHED);
            assertThat(result.links().get(0).reason()).contains("歧义");
        }

        @Test
        void unmatchedExerciseKeyIsExplicit() {
            Chunk a = chunk("a-1", "doc-1", ED, "第3章", "???", ChunkRole.EXERCISE_ANSWER, null);
            PairingResult result = QuestionAnswerPairer.pair(List.of(answer(a, "???")));
            assertThat(result.unmatchedCount()).isEqualTo(1);
            assertThat(result.links().get(0).key().canonical()).isEqualTo("UNMATCHED");
        }

        @Test
        void groupKeyNeverBindsToSingleQuestion() {
            Chunk q = chunk("q-1", "doc-1", ED, "第3章", "5", ChunkRole.EXERCISE, null);
            Chunk a = chunk("a-1", "doc-1", ED, "第3章", "5-7", ChunkRole.EXERCISE_ANSWER, null);
            PairingResult result = QuestionAnswerPairer.pair(List.of(question(q, "5"),
                    answer(a, "5-7")));
            assertThat(result.links().get(0).status()).isEqualTo(PairingStatus.UNMATCHED);
        }

        @Test
        void unmatchedLinkCannotCarryQuestionId() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            new AnswerLink("a", "q", ExerciseKey.unmatched("?"),
                                    PairingStatus.UNMATCHED, "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Rewrite {

        @Test
        void carriesEveryFieldExceptExerciseRef() {
            LayoutFacts facts = new LayoutFacts(true, "PARAGRAPH", "解：");
            Chunk q = chunk("q-1", "book", ED, "第3章", "3", ChunkRole.EXERCISE, null);
            Chunk a = chunk("a-1", "answers", ED, "第3章", "3",
                    ChunkRole.EXERCISE_ANSWER, facts);
            PairingResult result = QuestionAnswerPairer.pair(List.of(question(q, "3"),
                    answer(a, "3")));

            List<Chunk> rewritten = QuestionAnswerPairer.rewriteExerciseRefs(List.of(q, a), result);

            assertThat(rewritten.get(0)).isSameAs(q);
            Chunk answer = rewritten.get(1);
            assertThat(answer.exerciseRef()).isEqualTo("Q003");
            assertThat(answer.id()).isEqualTo("a-1");
            assertThat(answer.documentId()).isEqualTo("answers");
            assertThat(answer.edition()).isEqualTo(ED);
            assertThat(answer.role()).isEqualTo(ChunkRole.EXERCISE_ANSWER);
            assertThat(answer.text()).isEqualTo(a.text());
            assertThat(answer.locator()).isEqualTo(a.locator());
            assertThat(answer.knowledgePointIds()).containsExactly("kp-1");
            assertThat(answer.contentHash()).isEqualTo("hash-a-1");
            assertThat(answer.layoutFacts()).isEqualTo(facts);
        }

        @Test
        void unmatchedAnswerGetsNoRef() {
            Chunk a = chunk("a-1", "answers", ED, "第3章", "42", ChunkRole.EXERCISE_ANSWER, null);
            PairingResult result = QuestionAnswerPairer.pair(List.of(answer(a, "42")));
            Chunk rewritten = QuestionAnswerPairer.rewriteExerciseRefs(List.of(a), result).get(0);
            assertThat(rewritten.exerciseRef()).isNull();
        }

        @Test
        void multipleSubQuestionsCollapseToParent() {
            Chunk q1 = chunk("q-s1", "book", ED, "第3章", "3.(1)", ChunkRole.EXERCISE, null);
            Chunk q2 = chunk("q-s2", "book", ED, "第3章", "3.(2)", ChunkRole.EXERCISE, null);
            Chunk a = chunk("a-1", "answers", ED, "第3章", "3", ChunkRole.EXERCISE_ANSWER, null);
            PairingResult result = QuestionAnswerPairer.pair(List.of(
                    question(q1, "3.(1)"), question(q2, "3.(2)"),
                    PairingItem.answer(a, ExerciseNoNormalizer.normalize("3.(1)")),
                    PairingItem.answer(a, ExerciseNoNormalizer.normalize("3.(2)"))));

            List<Chunk> rewritten = QuestionAnswerPairer.rewriteExerciseRefs(List.of(a), result);
            assertThat(rewritten.get(0).exerciseRef()).isEqualTo("Q003");
        }
    }
}
