package cn.studykid.rag.core;

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
 * 子块命中回带父块的契约（RAG-D28 P2-1）。
 *
 * <p>重点守护：① 回带的父块<b>必须过泄题闸门</b>（父块若为答案角色，讲解模式不得回带）；
 * ② 同一父块只回带一次；③ 孤儿子块不静默丢弃。
 */
class ParentChunkExpansionTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static Chunk chunk(String id, ChunkRole role, String parentId, int level) {
        return new Chunk(id, "doc-1", EDITION, role, "文本-" + id,
                new Chunk.Locator(45, "第3章", null, 0, 10), List.of(), null,
                "hash-" + id, null, parentId, level);
    }

    @Test
    @DisplayName("命中子块 → 回带父块，子块在前父块在后")
    void expandsParentAfterChild() {
        Chunk parent = chunk("parent", ChunkRole.EXPLANATION, null, Chunk.LEVEL_PARENT);
        Chunk child = chunk("child", ChunkRole.EXERCISE, "parent", Chunk.LEVEL_CHILD);

        List<Chunk> out = RetrievalPolicy.expandParents(
                List.of(child), List.of(parent, child), RetrievalMode.EXPLAIN);

        assertThat(out).extracting(Chunk::id).containsExactly("child", "parent");
    }

    @Test
    @DisplayName("同一父块被多个子块命中 → 只回带一次，保序")
    void deduplicatesParent() {
        Chunk parent = chunk("parent", ChunkRole.EXPLANATION, null, Chunk.LEVEL_PARENT);
        Chunk c1 = chunk("c1", ChunkRole.EXPLANATION, "parent", Chunk.LEVEL_CHILD);
        Chunk c2 = chunk("c2", ChunkRole.EXPLANATION, "parent", Chunk.LEVEL_CHILD);

        List<Chunk> out = RetrievalPolicy.expandParents(
                List.of(c1, c2), List.of(parent, c1, c2), RetrievalMode.EXPLAIN);

        assertThat(out).extracting(Chunk::id).containsExactly("c1", "c2", "parent");
    }

    @Test
    @DisplayName("反证：父块若为答案角色，讲解模式拒绝回带（回带不是绕过闸门的旁路）")
    void parentRoleFilterBlocksLeak() {
        Chunk badParent = chunk("bad-parent", ChunkRole.EXERCISE_ANSWER, null, Chunk.LEVEL_PARENT);
        Chunk child = chunk("child", ChunkRole.EXERCISE, "bad-parent", Chunk.LEVEL_CHILD);

        List<Chunk> explain = RetrievalPolicy.expandParents(
                List.of(child), List.of(badParent, child), RetrievalMode.EXPLAIN);
        assertThat(explain).extracting(Chunk::id).containsExactly("child")
                .as("讲解模式不得回带答案类父块");

        List<Chunk> verify = RetrievalPolicy.expandParents(
                List.of(child), List.of(badParent, child), RetrievalMode.VERIFY);
        assertThat(verify).extracting(Chunk::id).containsExactly("child", "bad-parent")
                .as("核对模式允许答案类父块");
    }

    @Test
    @DisplayName("孤儿子块（找不到父块）保留命中，不静默丢弃")
    void orphanChildKept() {
        Chunk orphan = chunk("orphan", ChunkRole.EXPLANATION, "missing-parent", Chunk.LEVEL_CHILD);
        List<Chunk> out = RetrievalPolicy.expandParents(
                List.of(orphan), List.of(orphan), RetrievalMode.EXPLAIN);
        assertThat(out).extracting(Chunk::id).containsExactly("orphan");
    }

    @Test
    @DisplayName("顶层块命中不触发回带（无 parentId）")
    void topLevelChunkNoExpansion() {
        Chunk top = chunk("top", ChunkRole.EXPLANATION, null, Chunk.LEVEL_PARENT);
        List<Chunk> out = RetrievalPolicy.expandParents(
                List.of(top), List.of(top), RetrievalMode.EXPLAIN);
        assertThat(out).extracting(Chunk::id).containsExactly("top");
    }
}
