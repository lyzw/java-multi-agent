package cn.studykid.rag.premise.chunker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.chunk.LayoutFacts;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.premise.normalize.ChunkIdentifiers;

/**
 * 父子块装配契约（RAG-D28 P2-1）。
 *
 * <p>重点守护两条不变量：① 同一小节多块才生成父块、子块有人物场指向；
 * ② <b>答案类子块绝不进父块</b>——否则父块（EXPLANATION）会绕过讲解模式泄题闸门。
 */
class ParentChildAssemblerTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static Chunk top(String id, String section, String text, ChunkRole role,
                             List<String> kpIds, int start, int end) {
        return new Chunk(id, "doc-1", EDITION, role, text,
                new Chunk.Locator(45, section, null, start, end), kpIds, null,
                ChunkIdentifiers.contentHash(text),
                new LayoutFacts(false, "PARAGRAPH", null));
    }

    @Test
    @DisplayName("同一小节两块 → 生成一个父块 + 两个子块，子块 parentId 指向父块")
    void groupsChildrenUnderParent() {
        List<Chunk> out = ParentChildAssembler.assemble("doc-1", List.of(
                top("a", "第3章 > 3.2", "顶点式定义：y=a(x-h)^2+k。", ChunkRole.EXPLANATION, List.of("kp-1"), 0, 20),
                top("b", "第3章 > 3.2", "其中 (h,k) 为顶点坐标。", ChunkRole.EXPLANATION, List.of("kp-2"), 20, 40)));

        assertThat(out).hasSize(3);
        Chunk parent = out.get(0);
        assertThat(parent.chunkLevel()).isEqualTo(Chunk.LEVEL_PARENT);
        assertThat(parent.parentId()).isNull();
        assertThat(parent.role()).isEqualTo(ChunkRole.EXPLANATION);
        assertThat(parent.knowledgePointIds()).containsExactly("kp-1", "kp-2");
        assertThat(parent.text()).contains("顶点式定义").contains("顶点坐标");
        assertThat(parent.id()).isEqualTo(ChunkIdentifiers.parentChunkId("doc-1", "第3章 > 3.2"));

        assertThat(out.get(1).isChild()).isTrue();
        assertThat(out.get(1).parentId()).isEqualTo(parent.id());
        assertThat(out.get(2).parentId()).isEqualTo(parent.id());
    }

    @Test
    @DisplayName("答案类子块不挂父块、不进父块正文（防父块结构性泄题）")
    void answerChildrenNeverEnterParent() {
        List<Chunk> out = ParentChildAssembler.assemble("doc-1", List.of(
                top("q", "第3章 > 3.2", "题干：求顶点坐标。", ChunkRole.EXERCISE, List.of(), 0, 10),
                top("ans", "第3章 > 3.2", "解：顶点是 (2,-1)。", ChunkRole.EXERCISE_ANSWER, List.of(), 10, 30),
                top("exp", "第3章 > 3.2", "配方得顶点式。", ChunkRole.EXPLANATION, List.of(), 30, 50)));

        // 两个非答案块 → 父块 + 2 子块；答案块保持顶层独立 = 共 4 块。
        assertThat(out).hasSize(4);
        Chunk parent = out.stream()
                .filter(c -> c.id().equals(ChunkIdentifiers.parentChunkId("doc-1", "第3章 > 3.2")))
                .findFirst().orElseThrow();
        assertThat(parent.text()).doesNotContain("解：").doesNotContain("(2,-1)");
        assertThat(parent.role()).isEqualTo(ChunkRole.EXPLANATION);

        Chunk answer = out.stream().filter(c -> c.id().equals("ans")).findFirst().orElseThrow();
        assertThat(answer.parentId()).as("答案块不得挂到讲解父块下").isNull();
        assertThat(answer.chunkLevel()).isEqualTo(Chunk.LEVEL_PARENT);
    }

    @Test
    @DisplayName("单块小节不生成父块（聚合不出额外上下文）")
    void singleBlockSectionStaysTopLevel() {
        List<Chunk> out = ParentChildAssembler.assemble("doc-1", List.of(
                top("only", "第3章 > 3.2", "唯一的讲解块。", ChunkRole.EXPLANATION, List.of(), 0, 10)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).isChild()).isFalse();
        assertThat(out.get(0).parentId()).isNull();
    }

    @Test
    @DisplayName("幂等：对已装配结果再次装配不产生新父块或重复子块")
    void assembleIsIdempotent() {
        List<Chunk> first = ParentChildAssembler.assemble("doc-1", List.of(
                top("a", "S", "讲解一。", ChunkRole.EXPLANATION, List.of(), 0, 10),
                top("b", "S", "讲解二。", ChunkRole.EXPLANATION, List.of(), 10, 20)));
        List<Chunk> second = ParentChildAssembler.assemble("doc-1", first);
        assertThat(second).hasSize(first.size());
        assertThat(second.stream().filter(Chunk::isChild).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("父子构造逐字段带过：版面事实与知识点不丢（铁律 #8）")
    void childKeepsAllFields() {
        Chunk original = top("a", "S", "讲解一。", ChunkRole.EXPLANATION, List.of("kp-9"), 3, 8);
        Chunk child = original.asChildOf("parent-1");
        assertThat(child.text()).isEqualTo(original.text());
        assertThat(child.locator().sectionPath()).isEqualTo(original.locator().sectionPath());
        assertThat(child.knowledgePointIds()).containsExactly("kp-9");
        assertThat(child.layoutFacts()).isEqualTo(original.layoutFacts());
        assertThat(child.contentHash()).isEqualTo(original.contentHash());
        assertThat(child.role()).isEqualTo(original.role());
    }
}
