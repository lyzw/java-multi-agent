package cn.studykid.rag.premise.chunker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.ingest.rules.TocRules;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.model.ChunkContext;
import cn.studykid.rag.premise.model.ChunkOutcome;
import cn.studykid.rag.premise.model.ChunkPolicyConfig;
import cn.studykid.rag.premise.model.ParseTrace;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.normalize.AppendixLogicalUnitResolver;

/**
 * 附录逻辑单位端到端（RAG-D28 P2-4 验收）：「第五单元单词」查询在切分产物可见。
 *
 * <p>撤点反证：不注入 resolver → 词表页按版面原样切成一片，「第五单元」前缀消失，
 * 本测试的可见性断言立即转红。
 */
class AppendixChunkingEndToEndTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.ENGLISH, EditionCoordinate.GradeBand.GRADE_7,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.HUJIAO, "2024");

    private static final ChunkContext CONTEXT = ChunkContext.withoutKnowledgePoints(EDITION);

    private static final List<TocRules.TocUnit> UNITS = List.of(
            new TocRules.TocUnit(4, "Food", 38),
            new TocRules.TocUnit(5, "Green neighbourhood", 58));

    private static PremiseDocument appendixDocument() {
        String wordlist = "Words and expressions in each unit\n"
                + "Unit 4 food, 食物, 很多很多很多内容用于越过四十字符阈值。\n"
                + "Unit 5 neighbourhood, 街区, 同样需要足够长的正文内容来通过过滤。";
        LayoutBlock block = new LayoutBlock(BlockType.PARAGRAPH, wordlist, 134, 0,
                "附录", null, false, 0, wordlist.length());
        return new PremiseDocument("doc-appendix",
                List.of(PremiseBlock.fromLayoutBlock(block)), ParseTrace.untracked());
    }

    @Test
    @DisplayName("注入 resolver → 词表按 Unit 切，「第五单元单词」在 sectionPath 与正文中均可见")
    void fifthUnitWordlistVisible() {
        PremiseDocument doc = appendixDocument();
        ChunkOutcome out = new HeadingHierarchyChunker(new AppendixLogicalUnitResolver(UNITS))
                .chunk(doc, ChunkPolicyConfig.productionDefault(), CONTEXT);

        List<Chunk> bodies = out.chunks().stream()
                .filter(c -> c.text().contains("单词表：")).toList();
        assertThat(bodies).hasSize(2);
        assertThat(out.chunks()).anySatisfy(c -> {
            assertThat(c.text()).contains("第五单元").contains("单词表");
            assertThat(c.locator().sectionPath()).contains("Unit 5").contains("词表");
        });
        // 角色正确：词表是附录讲解，非答案承载（不会被讲解模式过滤）。
        assertThat(bodies).allSatisfy(c -> assertThat(c.role().isAnswerBearing()).isFalse());
    }

    @Test
    @DisplayName("撤点对拍：不注入 resolver → 无「第五单元」前缀（证明可见性确由接线器带来）")
    void withoutResolverNoBilingualPrefix() {
        ChunkOutcome out = new HeadingHierarchyChunker()
                .chunk(appendixDocument(), ChunkPolicyConfig.productionDefault(), CONTEXT);

        assertThat(out.chunks()).hasSize(1);
        assertThat(out.chunks().get(0).text()).doesNotContain("第五单元");
        assertThat(out.chunks().get(0).locator().sectionPath()).isEqualTo("附录");
    }
}
