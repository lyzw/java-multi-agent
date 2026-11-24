package cn.studykid.rag.premise.role;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.retrieval.RetrievalPolicy;
import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * HINT 稳定来源判定契约（RAG-D28 P2-5）。
 *
 * <p>守护三条：① 有块首提示引导语且不含答案 → EXERCISE_HINT；
 * ② 无稳定来源不编造（普通段落/题号上下文一律 EXPLANATION）；
 * ③ 提示块夹带解答引导语 → 取严为答案类（防讲解模式泄题）；
 * ④ forbidRoles 守卫（EXPLAIN 排除答案类）不误伤纯 HINT。
 */
class HintRoleResolverTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    @Test
    @DisplayName("块首提示引导语 → EXERCISE_HINT（中英双形）")
    void hintPrefixYieldsHintRole() {
        assertThat(BlockRoleResolver.resolveHintByContent("提示：先配方化为顶点式。", "12"))
                .isEqualTo(ChunkRole.EXERCISE_HINT);
        assertThat(BlockRoleResolver.resolveHintByContent("思路点拨：观察对称轴。", "12"))
                .isEqualTo(ChunkRole.EXERCISE_HINT);
        assertThat(BlockRoleResolver.resolveHintByContent("Hint: try completing the square.", null))
                .isEqualTo(ChunkRole.EXERCISE_HINT);
    }

    @Test
    @DisplayName("无稳定来源不编造：普通段落/题号上下文/正文提字 → null（回落 EXPLANATION）")
    void noSourceNeverFabricates() {
        assertThat(BlockRoleResolver.resolveHintByContent("先配方化为顶点式。", "12")).isNull();
        assertThat(BlockRoleResolver.resolveHintByContent("本题提示了判别式用法。", null)).isNull();
        assertThat(BlockRoleResolver.resolveHintByContent("想一想这道题的思路。", "12")).isNull();
        assertThat(BlockRoleResolver.resolveHintByContent(null, "12")).isNull();
        assertThat(BlockRoleResolver.resolveHintByContent("   ", "12")).isNull();
    }

    @Test
    @DisplayName("反证：提示块夹带解答引导语 → 取严为答案类（不判 HINT，防泄题）")
    void hintWithSolutionTakesStrictest() {
        assertThat(BlockRoleResolver.resolveHintByContent("提示：先配方。解：得 x=2。", "12"))
                .isEqualTo(ChunkRole.EXERCISE_ANSWER);
        assertThat(BlockRoleResolver.resolveHintByContent("提示：先配方。答案：x=2。", null))
                .isEqualTo(ChunkRole.EXAMPLE_SOLUTION);
    }

    @Test
    @DisplayName("forbidRoles 守卫不误伤：EXPLAIN 排除答案类但保留纯 HINT")
    void roleFilterKeepsPureHint() {
        Chunk hint = new Chunk("hint", "doc-1", EDITION, ChunkRole.EXERCISE_HINT,
                "提示：先配方。", new Chunk.Locator(45, "第3章", "12", 0, 10),
                List.of(), "12", "hash-hint", null);
        Chunk answer = new Chunk("ans", "doc-1", EDITION, ChunkRole.EXERCISE_ANSWER,
                "解：x=2。", new Chunk.Locator(45, "第3章", "12", 10, 20),
                List.of(), "12", "hash-ans", null);

        List<Chunk> kept = RetrievalPolicy.applyRoleFilter(List.of(hint, answer), RetrievalMode.EXPLAIN);
        assertThat(kept).containsExactly(hint)
                .as("讲解模式必须保留提示、排除答案——守卫不误伤 HINT");
    }
}
