package cn.studykid.rag.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.retrieval.RetrievalPolicy;
import cn.studykid.rag.core.retrieval.RetrievalRequest;
import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.core.version.EditionScope;

/**
 * 习题与答案分离的不变式——教辅场景第二大事故（泄题）。
 *
 * <p>⚠️ 本测试类证明的核心命题：
 * <b>答案类内容在讲解模式下不会进入检索结果</b>，
 * 且该保证由<b>检索期硬过滤</b>提供，而非依赖 prompt 约束。
 */
class ExerciseAnswerSeparationTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static Chunk chunk(ChunkRole role, String id, String text) {
        return new Chunk(id, "doc-1", EDITION, role, text,
                new Chunk.Locator(12, "第3章", "12", 0, text.length()),
                List.of("kp-quadratic"), "12", "hash-" + id);
    }

    private static final Chunk EXERCISE = chunk(ChunkRole.EXERCISE, "c-q12",
            "已知二次函数 y=x^2-4x+3，求其顶点坐标。");
    private static final Chunk ANSWER = chunk(ChunkRole.EXERCISE_ANSWER, "c-a12",
            "解：配方得 y=(x-2)^2-1，故顶点为 (2,-1)。");
    private static final Chunk HINT = chunk(ChunkRole.EXERCISE_HINT, "c-h12",
            "提示：先配方化为顶点式，再读出顶点。");
    private static final Chunk EXAMPLE_SOLUTION = chunk(ChunkRole.EXAMPLE_SOLUTION, "c-es",
            "例题解法：配方 -> 顶点式 -> 读顶点。");
    private static final Chunk EXPLANATION = chunk(ChunkRole.EXPLANATION, "c-e",
            "二次函数的顶点式为 y=a(x-h)^2+k，顶点为 (h,k)。");

    @Nested
    @DisplayName("角色语义")
    class RoleSemantics {

        @Test
        @DisplayName("答案类角色：标准答案与例题解答")
        void answerBearingRoles() {
            assertThat(ChunkRole.EXERCISE_ANSWER.isAnswerBearing()).isTrue();
            assertThat(ChunkRole.EXAMPLE_SOLUTION.isAnswerBearing()).isTrue();
            assertThat(ChunkRole.EXERCISE_HINT.isAnswerBearing()).isFalse();
            assertThat(ChunkRole.EXPLANATION.isAnswerBearing()).isFalse();
        }

        @Test
        @DisplayName("标准答案不可作为讲解依据直接引用")
        void answerNotCitableAsExplanation() {
            assertThat(ChunkRole.EXERCISE_ANSWER.citableAsExplanation()).isFalse();
            assertThat(ChunkRole.EXAMPLE_SOLUTION.citableAsExplanation()).isFalse();
            assertThat(ChunkRole.EXPLANATION.citableAsExplanation()).isTrue();
        }
    }

    @Nested
    @DisplayName("检索期硬过滤")
    class RoleFilter {

        @Test
        @DisplayName("EXPLAIN 模式排除标准答案与例题解答，保留提示")
        void explainModeExcludesAnswers() {
            List<Chunk> all = List.of(EXERCISE, ANSWER, HINT, EXAMPLE_SOLUTION, EXPLANATION);

            List<Chunk> kept = RetrievalPolicy.applyRoleFilter(all, RetrievalMode.EXPLAIN);

            assertThat(kept).contains(EXERCISE, HINT, EXPLANATION);
            assertThat(kept).doesNotContain(ANSWER, EXAMPLE_SOLUTION);
        }

        @Test
        @DisplayName("EXPLAIN 模式下学生看到的题干完整保留（不能为了防泄题连题目一起滤掉）")
        void explainModeKeepsQuestion() {
            List<Chunk> kept = RetrievalPolicy.applyRoleFilter(
                    List.of(EXERCISE, ANSWER), RetrievalMode.EXPLAIN);
            assertThat(kept).hasSize(1);
            assertThat(kept.get(0).role()).isEqualTo(ChunkRole.EXERCISE);
        }

        @Test
        @DisplayName("SOLVE 模式排除标准答案但允许例题解答（示范是学习材料）")
        void solveModeExcludesOnlyStandardAnswer() {
            List<Chunk> kept = RetrievalPolicy.applyRoleFilter(
                    List.of(EXERCISE, ANSWER, EXAMPLE_SOLUTION, HINT), RetrievalMode.SOLVE);
            assertThat(kept).contains(EXAMPLE_SOLUTION, HINT, EXERCISE);
            assertThat(kept).doesNotContain(ANSWER);
        }

        @Test
        @DisplayName("VERIFY 模式保留全部——核对场景本就需要标准答案")
        void verifyModeKeepsAll() {
            List<Chunk> all = List.of(EXERCISE, ANSWER, HINT, EXAMPLE_SOLUTION, EXPLANATION);
            assertThat(RetrievalPolicy.applyRoleFilter(all, RetrievalMode.VERIFY)).hasSize(5);
        }

        @Test
        @DisplayName("可观测：模式过滤确实丢弃过答案（供审计说明「未给答案是策略主动为之」）")
        void detectsSuppression() {
            List<Chunk> all = List.of(EXERCISE, ANSWER, HINT);
            assertThat(RetrievalPolicy.detectSuppressedAnswers(all, RetrievalMode.EXPLAIN)).isTrue();
            assertThat(RetrievalPolicy.detectSuppressedAnswers(all, RetrievalMode.VERIFY)).isFalse();
        }
    }

    @Nested
    @DisplayName("知识点补全（碎片化）")
    class KnowledgePointCompletion {

        @Test
        @DisplayName("补全引入讲解与练习，但不得绕过泄题闸门引入答案")
        void completionRespectsRoleFilter() {
            RetrievalRequest request = new RetrievalRequest(
                    "二次函数顶点怎么求", RetrievalMode.EXPLAIN,
                    EditionScope.strict(EDITION), "kp-quadratic", "数学", "八年级", 10);

            List<Chunk> siblings = List.of(EXPLANATION, EXERCISE, HINT, ANSWER);

            List<Chunk> completed = RetrievalPolicy.applyKnowledgePointCompletion(
                    List.of(EXPLANATION), siblings, request);

            assertThat(completed).contains(EXERCISE, HINT);
            assertThat(completed).doesNotContain(ANSWER);
        }

        @Test
        @DisplayName("未指定知识点时不触发补全（避免无关内容涌入）")
        void noCompletionWithoutKnowledgePoint() {
            RetrievalRequest request = new RetrievalRequest(
                    "二次函数", RetrievalMode.EXPLAIN,
                    EditionScope.strict(EDITION), null, "数学", "八年级", 10);

            List<Chunk> completed = RetrievalPolicy.applyKnowledgePointCompletion(
                    List.of(EXPLANATION), List.of(EXERCISE, ANSWER), request);

            assertThat(completed).hasSize(1);
        }
    }
}
