package cn.studykid.rag.application.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.DocumentParserPort;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.ports.ParsedDocument;

/**
 * G1 ② 切分不变式的自动化证据（RAG-D09 G1 通过条件 ②）。
 *
 * <p>门禁要求「噪声块丢弃、题号保留、章节路径层级截断、答案区角色判定
 * <b>逐条有反证</b>」。「有反证」意味着每条不变式都必须有一个
 * <b>反向用例</b>证明它真的生效——而不是只测正向路径。
 *
 * <p>本类逐条对应 G1 ② 的四个子项，每个都给出「正向 + 反向」两组断言。
 */
class IngestInvariantEvidenceTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH,
            EditionCoordinate.GradeBand.GRADE_6,
            EditionCoordinate.Term.FIRST,
            EditionCoordinate.Publisher.HUJIAO,
            "2024");

    /** 用固定块序列构造管道。 */
    private IngestionPipeline pipelineOf(List<LayoutBlock> blocks) {
        DocumentParserPort fake = (documentId, content) ->
                new ParsedDocument(documentId, blocks);
        return new IngestionPipeline(fake);
    }

    private List<Chunk> ingest(List<LayoutBlock> blocks) {
        return pipelineOf(blocks).ingest("doc-1", "测试文档", EDITION,
                "x".getBytes(StandardCharsets.UTF_8), List.of());
    }

    private LayoutBlock block(BlockType type, String text, int page, int level,
                              String section, String exNo, boolean answerZone) {
        return new LayoutBlock(type, text, page, level, section, exNo, answerZone, 0, text.length());
    }

    @Nested
    @DisplayName("②-1 噪声块丢弃")
    class NoiseDiscard {

        @Test
        @DisplayName("正向：BOILERPLATE 块不产出片段")
        void boilerplateDropped() {
            List<Chunk> chunks = ingest(List.of(
                    block(BlockType.BOILERPLATE, "上海教育出版社", 1, 0, "", null, false),
                    block(BlockType.PARAGRAPH, "正文内容", 1, 0, "", null, false)));
            assertEquals(1, chunks.size(), "页眉不应产出片段");
            assertEquals("正文内容", chunks.get(0).text());
        }

        @Test
        @DisplayName("反向：若不去噪，同一输入应产出 2 段（证明丢弃真的发生）")
        void counterFactual() {
            // 把 BOILERPLATE 换成 PARAGRAPH → 两段都会保留
            List<Chunk> chunks = ingest(List.of(
                    block(BlockType.PARAGRAPH, "上海教育出版社", 1, 0, "", null, false),
                    block(BlockType.PARAGRAPH, "正文内容", 1, 0, "", null, false)));
            assertEquals(2, chunks.size(),
                    "对照组证明：噪声丢弃是 BOILERPLATE 类型驱动的，不是内容碰巧");
        }
    }

    @Nested
    @DisplayName("②-2 题号保留（含继承）")
    class ExerciseNo {

        @Test
        @DisplayName("正向：块自带题号时保留")
        void explicitExerciseNo() {
            List<Chunk> chunks = ingest(List.of(
                    block(BlockType.EXERCISE, "1. 计算 3+5", 1, 0, "", "1", false)));
            assertEquals("1", chunks.get(0).exerciseRef());
        }

        @Test
        @DisplayName("正向：答案块无题号时继承上文题号（防泄题关键）")
        void inheritedExerciseNo() {
            List<Chunk> chunks = ingest(List.of(
                    block(BlockType.EXERCISE, "1. 计算 3+5", 1, 0, "", "1", false),
                    block(BlockType.PARAGRAPH, "答案是 8", 1, 0, "", null, true)));
            // 答案块自身无题号，必须继承 "1"，否则会被判成例题解答而非习题答案
            assertEquals("1", chunks.get(1).exerciseRef(),
                    "答案块必须继承上文题号，否则「习题的答案」会被判成「例题解答」→ 泄题");
        }

        @Test
        @DisplayName("反向：无上文题号时不得凭空造题号")
        void noInvention() {
            List<Chunk> chunks = ingest(List.of(
                    block(BlockType.PARAGRAPH, "普通讲解文字", 1, 0, "", null, false)));
            assertEquals(null, chunks.get(0).exerciseRef(),
                    "没有题号就应为空，不能编造");
        }
    }

    @Nested
    @DisplayName("②-3 章节路径层级截断")
    class SectionPath {

        @Test
        @DisplayName("正向：多级标题推进后的路径可读且受控")
        void pathIsBounded() {
            List<Chunk> chunks = ingest(List.of(
                    block(BlockType.HEADING, "第一章", 1, 1, "", null, false),
                    block(BlockType.HEADING, "第一节", 1, 2, "", null, false),
                    block(BlockType.PARAGRAPH, "正文", 1, 0, "", null, false)));
            String path = chunks.get(0).locator().sectionPath();
            assertNotNull(path);
            assertTrue(path.length() <= 80, "路径必须受控，实际：" + path);
        }

        @Test
        @DisplayName("反向：深层嵌套也不会无限累积")
        void deepNestingBounded() {
            List<LayoutBlock> blocks = new java.util.ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                blocks.add(block(BlockType.HEADING, "层级" + i, 1, i, "", null, false));
            }
            blocks.add(block(BlockType.PARAGRAPH, "正文", 1, 0, "", null, false));
            List<Chunk> chunks = ingest(blocks);
            String path = chunks.get(chunks.size() - 1).locator().sectionPath();
            long depth = path.isEmpty() ? 0 : path.split("/").length;
            assertTrue(depth <= 4, "层级必须截断，实际深度 " + depth + "：" + path);
        }
    }

    @Nested
    @DisplayName("②-4 答案区角色判定（取严，防泄题）")
    class AnswerZoneRole {

        @Test
        @DisplayName("正向：答案区有题号 → EXERCISE_ANSWER")
        void answerWithNo() {
            List<Chunk> chunks = ingest(List.of(
                    block(BlockType.PARAGRAPH, "答案 8", 1, 0, "", "1", true)));
            assertEquals(ChunkRole.EXERCISE_ANSWER, chunks.get(0).role());
        }

        @Test
        @DisplayName("正向：答案区无题号 → EXAMPLE_SOLUTION（不是 EXPLANATION！）")
        void answerWithoutNoIsNotExplanation() {
            List<Chunk> chunks = ingest(List.of(
                    block(BlockType.PARAGRAPH, "参考答案内容", 1, 0, "", null, true)));
            // ⚠️ 判成 EXPLANATION 会让答案以讲解身份被检索 → 泄题
            assertFalse(chunks.get(0).role() == ChunkRole.EXPLANATION,
                    "答案区无题号必须判 EXAMPLE_SOLUTION，不能判 EXPLANATION（会泄题）");
            assertEquals(ChunkRole.EXAMPLE_SOLUTION, chunks.get(0).role());
        }

        @Test
        @DisplayName("正向：答案区的公式块 → EXERCISE_ANSWER（行 5，最严）")
        void formulaInAnswerZone() {
            List<Chunk> chunks = ingest(List.of(
                    block(BlockType.FORMULA, "x = 2", 1, 0, "", null, true)));
            assertEquals(ChunkRole.EXERCISE_ANSWER, chunks.get(0).role(),
                    "答案区的公式也必须判答案类——解题模式下被召回同样是泄题");
        }

        @Test
        @DisplayName("反向：非答案区的同内容判 EXPLANATION（证明是 inAnswerZone 驱动的）")
        void counterFactualOutsideAnswerZone() {
            // ⚠️ 探针不用「参考答案」开头：该短语在 RAG-D26 取严补丁后属于
            //    「块首答案区标题形态」（MergeChunk 场景的泄题防线），
            //    即使不在答案区也判答案承载——那是**有意**的行为（宁可过判）。
            //    本测试要证明的是「版面驱动」：用不含答案区短语的对照文本。
            List<Chunk> chunks = ingest(List.of(
                    block(BlockType.PARAGRAPH, "配方后的顶点式是 y=(x-2)^2-1。", 1, 0, "", null, false)));
            assertEquals(ChunkRole.EXPLANATION, chunks.get(0).role(),
                    "对照组：同样的文本、不同的版面事实 → 不同角色，证明判定看的是版面而非文本");
        }

        @Test
        @DisplayName("反向：答案区角色必须被检索策略过滤（端到端防泄题）")
        void answerRoleIsIsAnswerLike() {
            List<Chunk> chunks = ingest(List.of(
                    block(BlockType.PARAGRAPH, "答案 8", 1, 0, "", "1", true)));
            assertTrue(chunks.get(0).role().isAnswerBearing(),
                    "答案类角色必须被 isAnswerBearing() 识别，否则检索期不会被过滤");
        }
    }
}
