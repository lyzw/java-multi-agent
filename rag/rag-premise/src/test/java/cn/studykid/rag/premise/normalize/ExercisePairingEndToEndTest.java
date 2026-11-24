package cn.studykid.rag.premise.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.chunker.HeadingHierarchyChunker;
import cn.studykid.rag.premise.model.ChunkContext;
import cn.studykid.rag.premise.model.ChunkOutcome;
import cn.studykid.rag.premise.model.ChunkPolicyConfig;
import cn.studykid.rag.premise.model.ParseTrace;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;

/**
 * P2-2 + P2-3 的链路级验收：真实切分产物 → 题号规范化 → 跨册配对 → 回指重写。
 *
 * <p>⚠️ 这里不宣称生产摄取链已接线——本测试证明的是「能力接在真实切分产物上可用」，
 * 而不是「IngestJobOrchestrator 已调用它」（见 D28 可达性缺口登记）。
 */
class ExercisePairingEndToEndTest {

    private static final EditionCoordinate ED = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");
    private static final ChunkContext CONTEXT = ChunkContext.withoutKnowledgePoints(ED);

    private static LayoutBlock block(BlockType type, String text, int page, int level,
                                     String sectionPath, String exerciseNo, boolean answerZone) {
        return new LayoutBlock(type, text, page, level, sectionPath, exerciseNo, answerZone,
                0, text.length());
    }

    private static List<Chunk> chunk(String documentId, LayoutBlock... blocks) {
        PremiseDocument doc = new PremiseDocument(documentId,
                PremiseBlock.fromLayoutBlocks(List.of(blocks)), ParseTrace.untracked());
        ChunkOutcome outcome = new HeadingHierarchyChunker()
                .chunk(doc, ChunkPolicyConfig.productionDefault(), CONTEXT);
        return outcome.chunks();
    }

    @Test
    void crossBookSubQuestionGetsCanonicalRef() {
        List<Chunk> questions = chunk("book",
                block(BlockType.HEADING, "第3章", 10, 1, "第3章", null, false),
                block(BlockType.PARAGRAPH, "3.(2) 求 y=x^2 的最小值。", 10, 0, "第3章",
                        "3.(2)", false));
        List<Chunk> answers = chunk("answers",
                block(BlockType.HEADING, "第3章", 88, 1, "第3章", null, false),
                block(BlockType.HEADING, "参考答案", 88, 2, "第3章", null, true),
                block(BlockType.PARAGRAPH, "3.(2) 配方得最小值为 0。", 88, 0, "第3章",
                        null, true));

        Chunk question = questions.get(0);
        Chunk answer = answers.get(0);
        // ⚠️ 上游 extractExerciseNo 只给主编号，这里从块首文本恢复完整多级题号。
        PairingResult result = QuestionAnswerPairer.pair(List.of(
                PairingItem.question(question,
                        ExerciseNoNormalizer.normalizeFromText(question.text())),
                PairingItem.answer(answer,
                        ExerciseNoNormalizer.normalizeFromText(answer.text()))));

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.links().get(0).status()).isEqualTo(PairingStatus.CROSS_DOCUMENT);
        assertThat(result.links().get(0).key().canonical()).isEqualTo("Q003.S02");

        Chunk rewritten = QuestionAnswerPairer.rewriteExerciseRefs(answers, result).get(0);
        assertThat(question.exerciseRef()).isEqualTo("3.(2)");
        assertThat(rewritten.exerciseRef()).isEqualTo("Q003.S02");
        assertThat(rewritten.id()).isEqualTo(answer.id());
        assertThat(rewritten.layoutFacts()).isEqualTo(answer.layoutFacts());
    }

    @Test
    void unmatchedAnswerIsNotGivenAGuessedRef() {
        List<Chunk> questions = chunk("book",
                block(BlockType.PARAGRAPH, "3. 求 y=x^2 的最小值。", 10, 0, "第3章",
                        "3", false));
        List<Chunk> answers = chunk("answers",
                block(BlockType.PARAGRAPH, "99. 另一道题的答案。", 88, 0, "第3章",
                        "99", true));

        PairingResult result = QuestionAnswerPairer.pair(List.of(
                PairingItem.question(questions.get(0),
                        ExerciseNoNormalizer.normalizeFromText(questions.get(0).text())),
                PairingItem.answer(answers.get(0),
                        ExerciseNoNormalizer.normalizeFromText(answers.get(0).text()))));

        assertThat(result.unmatchedCount()).isEqualTo(1);
        assertThat(QuestionAnswerPairer.rewriteExerciseRefs(answers, result).get(0).exerciseRef())
                .isNull();
    }

    @Test
    void answerVariantsFromTextSplitSubQuestions() {
        List<ExerciseKey> keys = ExerciseNoNormalizer.normalizeAnswerVariants(
                "3", "(1) 顶点 (1,0)  (2) 最小值 0  (3) 对称轴 x=1");
        assertThat(keys).extracting(ExerciseKey::canonical)
                .containsExactly("Q003.S01", "Q003.S02", "Q003.S03");
    }
}
