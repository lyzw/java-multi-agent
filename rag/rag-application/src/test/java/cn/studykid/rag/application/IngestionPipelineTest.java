package cn.studykid.rag.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.application.ingest.IngestionPipeline;
import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.retrieval.RetrievalPolicy;
import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.DocumentParserPort;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.ports.ParsedDocument;

/**
 * 入库切分的不变式（RAG-D04 §3、§5）。
 *
 * <p>⚠️ 本测试类覆盖 RAG-D08 §2.3 指出的两个零覆盖类之一。
 * 重点保护两条最容易在重构中被破坏的规则：
 * <b>答案区识别</b>（泄题源头）与<b>章节路径推进</b>（引用可核对性）。
 */
class IngestionPipelineTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static LayoutBlock block(BlockType type, String text, int page, int level,
                                    String sectionPath, String exerciseNo, boolean answerZone) {
        return new LayoutBlock(type, text, page, level, sectionPath, exerciseNo, answerZone, 0, text.length());
    }

    private static IngestionPipeline pipelineOf(LayoutBlock... blocks) {
        DocumentParserPort parser = (documentId, content) ->
                new ParsedDocument(documentId, List.of(blocks));
        return new IngestionPipeline(parser);
    }

    @Nested
    @DisplayName("答案区识别（泄题源头）")
    class AnswerZoneDetection {

        @Test
        @DisplayName("答案区 + 有题号 → 习题标准答案")
        void answerZoneWithExerciseNumberIsExerciseAnswer() {
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.PARAGRAPH, "解答：配方得 y=(x-2)^2-1。", 45, 0, "第3章", "12", true))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).role()).isEqualTo(ChunkRole.EXERCISE_ANSWER);
        }

        @Test
        @DisplayName("答案区 + 无题号 → 例题解答")
        void answerZoneWithoutExerciseNumberIsExampleSolution() {
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.PARAGRAPH, "例题解答：先配方化为顶点式。", 45, 0, "第3章", null, true))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            assertThat(chunks.get(0).role()).isEqualTo(ChunkRole.EXAMPLE_SOLUTION);
        }

        @Test
        @DisplayName("正文中的公式不属于答案类（不被误判为泄题内容）")
        void formulaOutsideAnswerZoneIsExplanation() {
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.FORMULA, "y = a(x-h)^2 + k", 45, 0, "第3章", null, false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            assertThat(chunks.get(0).role()).isEqualTo(ChunkRole.EXPLANATION);
        }

        @Test
        @DisplayName("答案区中的公式仍属答案承载内容")
        void formulaInsideAnswerZoneIsAnswerBearing() {
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.FORMULA, "x = 2", 45, 0, "第3章", "12", true))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            assertThat(chunks.get(0).role()).isEqualTo(ChunkRole.EXERCISE_ANSWER);
            assertThat(chunks.get(0).answerBearing()).isTrue();
        }

        @Test
        @DisplayName("⚠️ 答案块不带自己的题号时，归属上文那道题 → 习题标准答案")
        void answerBlockInheritsExerciseNumber() {
            // ⚠️ 真实版面：答案册里的答案块**通常不带题号**，
            //    它归属的是上文那道题。若只看块自身题号，
            //    会被判成 EXPLANATION → 讲解模式下被召回 → **泄题**。
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.EXERCISE, "已知 y=x^2-4x+3，求顶点。", 45, 0, "第3章", "12", false),
                    block(BlockType.PARAGRAPH, "解：配方得 y=(x-2)^2-1。", 45, 0, "第3章", null, true))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            assertThat(chunks).hasSize(2);
            assertThat(chunks.get(1).role())
                    .as("继承题号 12 的答案块必须是习题标准答案，不能是讲解")
                    .isEqualTo(ChunkRole.EXERCISE_ANSWER);
            assertThat(chunks.get(1).locator().exerciseNo()).isEqualTo("12");
        }

        @Test
        @DisplayName("⛔ 泄题闸门端到端：答案区内容不得出现在讲解模式的召回结果里")
        void answerZoneContentNeverSurvivesExplainMode() {
            // ⚠️ 这是本类**最关键**的一条：它把「角色判定」与「检索期过滤」串起来。
            //    只测角色（isAnswerBearing）不够——那只是判据；
            //    真正要证明的是「被判错的答案内容不会进入学生的上下文」。
            //    修复前：答案区段落被判为 EXPLANATION → 不是答案类 → **被召回**。
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.EXERCISE, "已知 y=x^2-4x+3，求顶点。", 45, 0, "第3章", "12", false),
                    block(BlockType.PARAGRAPH, "解：配方得 y=(x-2)^2-1，故顶点 (2,-1)。",
                            45, 0, "第3章", null, true),
                    block(BlockType.PARAGRAPH, "例题解答：先配方化为顶点式。", 45, 0, "第3章", null, true))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            List<Chunk> keptInExplain = RetrievalPolicy.applyRoleFilter(chunks, RetrievalMode.EXPLAIN);

            assertThat(keptInExplain)
                    .as("讲解模式下只能看到题干，不得看到任何答案区内容")
                    .extracting(Chunk::text)
                    .containsExactly("已知 y=x^2-4x+3，求顶点。");
        }

        @Test
        @DisplayName("题目块归类为 EXERCISE（讲解模式需要题目，不能一并滤掉）")
        void exerciseBlockIsExercise() {
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.EXERCISE, "已知 y=x^2-4x+3，求顶点。", 45, 0, "第3章", "12", false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            assertThat(chunks.get(0).role()).isEqualTo(ChunkRole.EXERCISE);
            assertThat(chunks.get(0).answerBearing()).isFalse();
        }
    }

    @Nested
    @DisplayName("噪声与结构")
    class NoiseAndStructure {

        @Test
        @DisplayName("页眉页脚等噪声块不入库")
        void boilerplateDropped() {
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.BOILERPLATE, "人教版 八年级 上册 第 3 页", 3, 0, "", null, false),
                    block(BlockType.PARAGRAPH, "二次函数的顶点式。", 45, 0, "第3章", null, false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).text()).isEqualTo("二次函数的顶点式。");
        }

        @Test
        @DisplayName("章节路径按层级截断而非追加（否则路径会累积成错误层级）")
        void sectionPathTruncatesOnLevelChange() {
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.HEADING, "第3章 二次函数", 40, 1, "", null, false),
                    block(BlockType.HEADING, "3.1 二次函数的概念", 41, 2, "", null, false),
                    block(BlockType.HEADING, "3.1.1 定义", 42, 3, "", null, false),
                    block(BlockType.PARAGRAPH, "顶点式定义。", 42, 0, "", null, false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            assertThat(chunks.get(0).locator().sectionPath())
                    .isEqualTo("第3章 二次函数 > 3.1 二次函数的概念 > 3.1.1 定义");
        }

        @Test
        @DisplayName("回到同级标题时路径被截断，不累积成 3.1 > 3.1.1 > 3.2")
        void sectionPathTruncatesWhenLevelDecreases() {
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.HEADING, "第3章 二次函数", 40, 1, "", null, false),
                    block(BlockType.HEADING, "3.1 概念", 41, 2, "", null, false),
                    block(BlockType.HEADING, "3.1.1 定义", 42, 3, "", null, false),
                    block(BlockType.HEADING, "3.2 图像", 43, 2, "", null, false),
                    block(BlockType.PARAGRAPH, "图像性质。", 43, 0, "", null, false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            assertThat(chunks.get(0).locator().sectionPath())
                    .isEqualTo("第3章 二次函数 > 3.2 图像");
        }

        @Test
        @DisplayName("题号在题目块之后被正确关联到后续内容")
        void exerciseNumberPropagates() {
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.EXERCISE, "求顶点坐标。", 45, 0, "第3章", "12", false),
                    block(BlockType.PARAGRAPH, "提示：先配方。", 45, 0, "第3章", null, false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            assertThat(chunks).hasSize(2);
            assertThat(chunks.get(1).locator().exerciseNo()).isEqualTo("12");
            assertThat(chunks.get(1).exerciseRef()).isEqualTo("12");
        }
    }

    @Nested
    @DisplayName("知识点挂接与定位")
    class KnowledgePointAndLocator {

        @Test
        @DisplayName("知识点按别名或规范名挂接")
        void knowledgePointAttached() {
            KnowledgePoint kp = new KnowledgePoint("kp-1", "数学", "八年级",
                    "二次函数顶点坐标", List.of("抛物线顶点"), List.of(), List.of());

            List<Chunk> chunks = pipelineOf(
                    block(BlockType.PARAGRAPH, "抛物线顶点的坐标是 (h,k)。", 45, 0, "第3章", null, false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of(kp));

            assertThat(chunks.get(0).knowledgePointIds()).containsExactly("kp-1");
        }

        @Test
        @DisplayName("未匹配时知识点为空（不做强行挂接）")
        void noKnowledgePointWhenUnmatched() {
            KnowledgePoint kp = new KnowledgePoint("kp-1", "数学", "八年级",
                    "二次函数顶点坐标", List.of("抛物线顶点"), List.of(), List.of());

            List<Chunk> chunks = pipelineOf(
                    block(BlockType.PARAGRAPH, "完全无关的内容。", 45, 0, "第3章", null, false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of(kp));

            assertThat(chunks.get(0).knowledgePointIds()).isEmpty();
        }

        @Test
        @DisplayName("定位信息完整：页码、章节路径、字符区间")
        void locatorComplete() {
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.PARAGRAPH, "顶点式。", 45, 0, "第3章 > 3.2", null, false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            Chunk.Locator locator = chunks.get(0).locator();
            assertThat(locator.pageNumber()).isEqualTo(45);
            assertThat(locator.sectionPath()).isEqualTo("第3章 > 3.2");
            assertThat(locator.charStart()).isZero();
            assertThat(locator.charEnd()).isEqualTo("顶点式。".length());
        }

        @Test
        @DisplayName("无标题上下文时用解析器给出的章节路径（否则定位退化为空串）")
        void sectionPathFallsBackToParser() {
            // ⚠️ 解析器是版面事实的权威：它知道每个块属于哪一节。
            //    若流水线只认「自己累积的标题路径」，在没有标题块的文档里
            //    路径恒为空 → 引用无法核对（RAG-D04 §5.2）。
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.PARAGRAPH, "顶点式。", 45, 0, "第3章 > 3.2 二次函数的图像", null, false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            assertThat(chunks.get(0).locator().sectionPath())
                    .isEqualTo("第3章 > 3.2 二次函数的图像");
        }

        @Test
        @DisplayName("标题上下文与解析器路径冲突时以标题累积路径为准（层级截断规则只在它上面实现）")
        void sectionPathPrefersHeadingAccumulation() {
            // ⚠️ §5.3 的「按层级截断而非追加」只存在于累积路径上；
            //    解析器路径不经过该规则。故两者并存时以累积路径为准。
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.HEADING, "第3章 二次函数", 40, 1, "解析器给的第1章", null, false),
                    block(BlockType.HEADING, "3.2 图像", 43, 2, "解析器给的第2章", null, false),
                    block(BlockType.PARAGRAPH, "图像性质。", 43, 0, "解析器给的第3章", null, false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            assertThat(chunks.get(0).locator().sectionPath())
                    .isEqualTo("第3章 二次函数 > 3.2 图像");
        }

        @Test
        @DisplayName("知识点挂接按「文本包含名称」判定（修复前恒为空 → 静默零挂接）")
        void knowledgePointAttachmentIsDirectional() {
            KnowledgePoint kp = new KnowledgePoint("kp-1", "数学", "八年级",
                    "二次函数顶点坐标", List.of("抛物线顶点"), List.of(), List.of());

            List<Chunk> hit = pipelineOf(
                    block(BlockType.PARAGRAPH, "抛物线顶点的坐标是 (h,k)。", 45, 0, "第3章", null, false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of(kp));
            List<Chunk> miss = pipelineOf(
                    block(BlockType.PARAGRAPH, "完全无关的内容。", 45, 0, "第3章", null, false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of(kp));

            assertThat(hit.get(0).knowledgePointIds()).containsExactly("kp-1");
            assertThat(miss.get(0).knowledgePointIds())
                    .as("未提到该知识点时不得强行挂接")
                    .isEmpty();
        }

        @Test
        @DisplayName("内容哈希可用于检测语料被静默替换")
        void contentHashPresent() {
            List<Chunk> chunks = pipelineOf(
                    block(BlockType.PARAGRAPH, "顶点式。", 45, 0, "第3章", null, false))
                    .ingest("doc-1", "人教版八上", EDITION, new byte[0], List.of());

            assertThat(chunks.get(0).contentHash()).hasSize(64).matches("[0-9a-f]{64}");
        }
    }
}
