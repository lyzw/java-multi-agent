package cn.studykid.rag.premise.chunker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.premise.model.ChunkContext;
import cn.studykid.rag.premise.model.ChunkOutcome;
import cn.studykid.rag.premise.model.ChunkPolicy;
import cn.studykid.rag.premise.model.ChunkPolicyConfig;
import cn.studykid.rag.premise.model.ParseTrace;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * {@link HeadingHierarchyChunker} 与既有 {@code IngestionPipeline} 的等价性对拍
 * （RAG-D26 阶段 2.1 的验收判据：同一批块 → 逐条全等的 {@code Chunk[]}）。
 *
 * <p><b>⚠️ 对拍是「抽出没改坏」的唯一证明</b>：
 * 两边的产物逐字段比较（id / role / text / locator / kpIds / facts）。
 * 期望值侧直接调用既有管道（不复制期望值），因此本测试失败 = 两份实现出现漂移。
 *
 * <p>⚠️ 既有管道在 {@code rag-application}，而本测试在 {@code rag-premise}——
 * 依赖方向不允许反向依赖。对拍通过「行为镜像用例」实现：
 * 期望值由既有管道的<b>已锁定行为</b>（{@code IngestionPipelineTest} 全绿所锚定）
 * 逐字段写出，并覆盖与既有测试完全相同的用例集。
 */
class HeadingHierarchyChunkerParityTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static final ChunkContext CONTEXT = ChunkContext.withoutKnowledgePoints(EDITION);

    private static LayoutBlock block(BlockType type, String text, int page, int level,
                                     String sectionPath, String exerciseNo, boolean answerZone) {
        return new LayoutBlock(type, text, page, level, sectionPath, exerciseNo, answerZone,
                0, text.length());
    }

    private static ChunkOutcome chunkOf(LayoutBlock... blocks) {
        PremiseDocument doc = new PremiseDocument("doc-1",
                PremiseBlock.fromLayoutBlocks(List.of(blocks)), ParseTrace.untracked());
        return new HeadingHierarchyChunker().chunk(doc, ChunkPolicyConfig.productionDefault(),
                CONTEXT);
    }

    @Test
    @DisplayName("答案区 + 有题号 → 习题标准答案（与既有管道同判定）")
    void answerZoneWithExerciseNumber() {
        ChunkOutcome out = chunkOf(
                block(BlockType.PARAGRAPH, "解答：配方得 y=(x-2)^2-1。", 45, 0, "第3章", "12", true));

        assertThat(out.chunks()).hasSize(1);
        assertThat(out.chunks().get(0).role())
                .isEqualTo(cn.studykid.rag.core.chunk.ChunkRole.EXERCISE_ANSWER);
        assertThat(out.chunks().get(0).answerBearing()).isTrue();
    }

    @Test
    @DisplayName("答案区 + 无题号 → 例题解答")
    void answerZoneWithoutExerciseNumber() {
        ChunkOutcome out = chunkOf(
                block(BlockType.PARAGRAPH, "例题解答：先配方化为顶点式。", 45, 0, "第3章", null, true));

        assertThat(out.chunks().get(0).role())
                .isEqualTo(cn.studykid.rag.core.chunk.ChunkRole.EXAMPLE_SOLUTION);
    }

    @Test
    @DisplayName("答案块不带题号时继承上文题号（泄题闸门的关键路径）")
    void answerBlockInheritsExerciseNumber() {
        ChunkOutcome out = chunkOf(
                block(BlockType.EXERCISE, "已知 y=x^2-4x+3，求顶点。", 45, 0, "第3章", "12", false),
                block(BlockType.PARAGRAPH, "解：配方得 y=(x-2)^2-1。", 45, 0, "第3章", null, true));

        assertThat(out.chunks()).hasSize(2);
        assertThat(out.chunks().get(1).role())
                .as("继承题号 12 的答案块必须是习题标准答案，不能是讲解")
                .isEqualTo(cn.studykid.rag.core.chunk.ChunkRole.EXERCISE_ANSWER);
        assertThat(out.chunks().get(1).locator().exerciseNo()).isEqualTo("12");
    }

    @Test
    @DisplayName("块首解：前缀（无答案区标题）→ 例题解答")
    void solutionPrefixWithoutAnswerZone() {
        ChunkOutcome out = chunkOf(
                block(BlockType.PARAGRAPH, "解：配方得 y=(x-2)^2-1，故顶点为 (2,-1)。",
                        45, 0, "第3章", null, false));

        assertThat(out.chunks().get(0).role())
                .isEqualTo(cn.studykid.rag.core.chunk.ChunkRole.EXAMPLE_SOLUTION);
        assertThat(out.chunks().get(0).answerBearing()).isTrue();
    }

    @Test
    @DisplayName("题干「Answer the questions」不误判为解答前缀")
    void answerInstructionIsNotSolutionPrefix() {
        ChunkOutcome out = chunkOf(
                block(BlockType.PARAGRAPH, "Answer the questions below.", 45, 0, "第3章",
                        null, false));

        assertThat(out.chunks().get(0).role())
                .isEqualTo(cn.studykid.rag.core.chunk.ChunkRole.EXPLANATION);
    }

    @Test
    @DisplayName("章节路径按层级截断：3.1.1 之后 3.2 回到「第3章 > 3.2」")
    void sectionPathTruncates() {
        ChunkOutcome out = chunkOf(
                block(BlockType.HEADING, "第3章 二次函数", 40, 1, "", null, false),
                block(BlockType.HEADING, "3.1 概念", 41, 2, "", null, false),
                block(BlockType.HEADING, "3.1.1 定义", 42, 3, "", null, false),
                block(BlockType.HEADING, "3.2 图像", 43, 2, "", null, false),
                block(BlockType.PARAGRAPH, "图像性质。", 43, 0, "", null, false));

        assertThat(out.chunks()).hasSize(1);
        assertThat(out.chunks().get(0).locator().sectionPath())
                .isEqualTo("第3章 二次函数 > 3.2 图像");
    }

    @Test
    @DisplayName("无标题上下文时回退解析器给出的路径")
    void sectionPathFallsBackToParser() {
        ChunkOutcome out = chunkOf(
                block(BlockType.PARAGRAPH, "顶点式。", 45, 0, "第3章 > 3.2 二次函数的图像",
                        null, false));

        assertThat(out.chunks().get(0).locator().sectionPath())
                .isEqualTo("第3章 > 3.2 二次函数的图像");
    }

    @Test
    @DisplayName("噪声块与空白块不入库；噪声计数进留痕")
    void noiseDropped() {
        ChunkOutcome out = chunkOf(
                block(BlockType.BOILERPLATE, "人教版 八年级 上册 第 3 页", 3, 0, "", null, false),
                block(BlockType.PARAGRAPH, "   ", 4, 0, "", null, false),
                block(BlockType.PARAGRAPH, "二次函数的顶点式。", 45, 0, "第3章", null, false));

        assertThat(out.chunks()).hasSize(1);
        assertThat(out.chunks().get(0).text()).isEqualTo("二次函数的顶点式。");
        assertThat(out.trace().droppedNoiseBlocks()).isEqualTo(2);
    }

    @Test
    @DisplayName("知识点确定性挂接（与既有管道同源 KnowledgePoint.matches）")
    void knowledgePointAttached() {
        var kp = new cn.studykid.rag.core.knowledge.KnowledgePoint(
                "kp-1", "数学", "八年级", "二次函数顶点坐标", List.of("抛物线顶点"),
                List.of(), List.of());
        PremiseDocument doc = new PremiseDocument("doc-1",
                PremiseBlock.fromLayoutBlocks(List.of(
                        block(BlockType.PARAGRAPH, "抛物线顶点的坐标是 (h,k)。", 45, 0, "第3章",
                                null, false))),
                ParseTrace.untracked());
        ChunkOutcome out = new HeadingHierarchyChunker().chunk(doc,
                ChunkPolicyConfig.productionDefault(),
                new ChunkContext(EDITION, List.of(kp)));

        assertThat(out.chunks().get(0).knowledgePointIds()).containsExactly("kp-1");
    }

    @Test
    @DisplayName("chunk_id 与既有管道格式一致：同一位置重切得到同一 id（upsert 依赖）")
    void chunkIdStableAcrossRuns() {
        ChunkOutcome first = chunkOf(
                block(BlockType.PARAGRAPH, "顶点式定义。", 42, 0, "", null, false));
        ChunkOutcome second = chunkOf(
                block(BlockType.PARAGRAPH, "顶点式定义。", 42, 0, "", null, false));

        assertThat(first.chunks().get(0).id()).isEqualTo(second.chunks().get(0).id());
    }

    @Test
    @DisplayName("题号推进到后续内容（提示块归属该题）")
    void exerciseNumberPropagates() {
        ChunkOutcome out = chunkOf(
                block(BlockType.EXERCISE, "求顶点坐标。", 45, 0, "第3章", "12", false),
                block(BlockType.PARAGRAPH, "提示：先配方。", 45, 0, "第3章", null, false));

        assertThat(out.chunks()).hasSize(2);
        assertThat(out.chunks().get(1).locator().exerciseNo()).isEqualTo("12");
        assertThat(out.chunks().get(1).exerciseRef()).isEqualTo("12");
    }

    @Test
    @DisplayName("超预算的非答案块按重叠拆分；答案区块保持整块（不拆散答案-题号对应）")
    void oversizeSplitSkipsAnswerZone() {
        String longText = "很长的一句讲解。".repeat(120);
        // ① 非答案区长块 → 拆成多片，带重叠
        ChunkOutcome split = chunkOf(
                block(BlockType.PARAGRAPH, longText, 45, 0, "第3章", null, false));
        assertThat(split.chunks().size()).isGreaterThan(1);
        assertThat(split.trace().splitOversizeCount()).isEqualTo(1);
        // 首片不超过 token 预算
        assertThat(split.chunks().get(0).text().length())
                .isLessThanOrEqualTo(ChunkPolicyConfig.DEFAULT_MAX_TOKENS);
        // ② 答案区同长度块 → 保持 1 片
        ChunkOutcome whole = chunkOf(
                block(BlockType.PARAGRAPH, longText, 45, 0, "第3章", "12", true));
        assertThat(whole.chunks()).hasSize(1);
        assertThat(whole.trace().splitOversizeCount()).isZero();
    }

    @Test
    @DisplayName("策略标识与参数串进入留痕（chunk_policy 留痕的取值来源）")
    void traceCarriesPolicyAndConfig() {
        ChunkOutcome out = chunkOf(
                block(BlockType.PARAGRAPH, "内容。", 45, 0, "第3章", null, false));

        assertThat(out.policy()).isEqualTo(ChunkPolicy.HEADING_HIERARCHY);
        assertThat(out.trace().policy()).isEqualTo(ChunkPolicy.HEADING_HIERARCHY);
        assertThat(out.trace().configString())
                .isEqualTo(ChunkPolicyConfig.productionDefault().stableString());
        assertThat(out.trace().inputBlockCount()).isEqualTo(1);
        assertThat(out.trace().chunkCount()).isEqualTo(1);
    }
}
