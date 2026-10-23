package cn.studykid.rag.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.application.retrieval.RetrievalOrchestrator;
import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.retrieval.RetrievalRequest;
import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.retrieval.RetrievalResult;
import cn.studykid.rag.core.retrieval.RetrievalResult.HitReason;
import cn.studykid.rag.core.retrieval.RetrievalResult.RetrievalChannel;
import cn.studykid.rag.core.retrieval.RetrievalResult.ScoredChunk;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.core.version.EditionScope;
import cn.studykid.rag.ports.RetrievalPort;

/**
 * 检索编排顺序的不变式。
 *
 * <p>⚠️ 核心命题：<b>版本与角色过滤发生在重排/截断之前</b>。
 * 若顺序颠倒，被过滤掉的内容已流入上下文，闸门形同虚设。
 */
class RetrievalOrchestratorTest {

    private static final EditionCoordinate PEP_2024 = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static final EditionCoordinate PEP_2012 = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2012");

    private static Chunk chunk(String id, EditionCoordinate edition, ChunkRole role) {
        return new Chunk(id, "doc-1", edition, role, "片段内容-" + id,
                new Chunk.Locator(10, "第1章", null, 0, 8), List.of(), null, "h-" + id);
    }

    /**
     * 可控的检索端口替身。
     *
     * ⚠️ 必须返回<b>带分数</b>的片段：门禁作用于检索器的真实相关性分数，
     * 若由编排层用排名反推，top-1 恒为满分，「找不到依据」将永不可能发生。
     */
    private static final class StubRetrievalPort implements RetrievalPort {
        private final List<ScoredChunk> searchResults;
        private final List<Chunk> knowledgePointResults;
        private final double score;

        StubRetrievalPort(List<Chunk> searchResults, List<Chunk> knowledgePointResults) {
            this(searchResults, knowledgePointResults, 0.9);
        }

        StubRetrievalPort(List<Chunk> searchResults, List<Chunk> knowledgePointResults, double score) {
            this.searchResults = searchResults.stream()
                    .map(c -> new ScoredChunk(c, score, RetrievalChannel.DENSE_VECTOR, HitReason.DIRECT_MATCH))
                    .toList();
            this.knowledgePointResults = knowledgePointResults;
            this.score = score;
        }

        @Override
        public List<ScoredChunk> search(RetrievalRequest request) {
            return searchResults;
        }

        @Override
        public List<Chunk> findByKnowledgePoint(String knowledgePointId, EditionScope editionScope) {
            return knowledgePointResults;
        }

        @Override
        public List<Chunk> findPrerequisites(String knowledgePointId, EditionScope editionScope) {
            return List.of();
        }

        @Override
        public List<Chunk> findSectionNeighbors(Chunk chunk, int limit) {
            return List.of();
        }

        /**
         * B5 新增方法的替身实现。
         *
         * <p>⚠️ 本测试类只覆盖检索编排，不覆盖生成路径；
         * 这里返回空列表仅为满足接口契约，生成期行为由
         * {@code GroundedGenerationServiceTest} 单独覆盖。
         */
        @Override
        public List<Chunk> loadByIds(List<String> chunkIds) {
            return List.of();
        }

        @Override
        public List<cn.studykid.rag.core.knowledge.KnowledgePoint> resolveKnowledgePoints(
                String query, String subject) {
            return List.of();
        }
    }

    @Test
    @DisplayName("EXPLAIN 模式：答案类内容不会进入最终结果")
    void explainModeExcludesAnswerContent() {
        List<Chunk> candidates = new ArrayList<>(List.of(
                chunk("c-exp", PEP_2024, ChunkRole.EXPLANATION),
                chunk("c-ans", PEP_2024, ChunkRole.EXERCISE_ANSWER),
                chunk("c-ex", PEP_2024, ChunkRole.EXERCISE)));

        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(
                new StubRetrievalPort(candidates, List.of()), 0.0);

        RetrievalResult result = orchestrator.retrieve(new RetrievalRequest(
                "顶点公式", RetrievalMode.EXPLAIN, EditionScope.strict(PEP_2024),
                null, "数学", "八年级", 10));

        assertThat(result.chunks()).extracting(s -> s.chunk().id())
                .containsExactlyInAnyOrder("c-exp", "c-ex");
        assertThat(result.answerContentSuppressed()).isTrue();
    }

    @Test
    @DisplayName("版本过滤：非首选版本内容不会进入结果")
    void otherEditionExcluded() {
        List<Chunk> candidates = new ArrayList<>(List.of(
                chunk("c-new", PEP_2024, ChunkRole.EXPLANATION),
                chunk("c-old", PEP_2012, ChunkRole.EXPLANATION)));

        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(
                new StubRetrievalPort(candidates, List.of()), 0.0);

        RetrievalResult result = orchestrator.retrieve(new RetrievalRequest(
                "顶点公式", RetrievalMode.EXPLAIN, EditionScope.strict(PEP_2024),
                null, "数学", "八年级", 10));

        assertThat(result.chunks()).extracting(s -> s.chunk().id()).containsExactly("c-new");
    }

    @Test
    @DisplayName("分数门禁：低于阈值的结果被丢弃，使「找不到」成为合法输出")
    void scoreGateDropsWeakMatches() {
        List<Chunk> candidates = List.of(chunk("c-1", PEP_2024, ChunkRole.EXPLANATION));

        // 检索得分 0.9，门禁 0.95 → 结果为空（「找不到依据」成为合法输出）
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(
                new StubRetrievalPort(candidates, List.of(), 0.9), 0.95);

        RetrievalResult result = orchestrator.retrieve(new RetrievalRequest(
                "完全无关的问题", RetrievalMode.EXPLAIN, EditionScope.strict(PEP_2024),
                null, "数学", "八年级", 10));

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("知识点补全：讲解与练习成组出现，但答案不因补全而混入")
    void knowledgePointCompletionAddsPracticeNotAnswer() {
        List<Chunk> searchHits = List.of(chunk("c-exp", PEP_2024, ChunkRole.EXPLANATION));
        List<Chunk> siblings = List.of(
                chunk("c-exp", PEP_2024, ChunkRole.EXPLANATION),
                chunk("c-ex", PEP_2024, ChunkRole.EXERCISE),
                chunk("c-ans", PEP_2024, ChunkRole.EXERCISE_ANSWER));

        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(
                new StubRetrievalPort(searchHits, siblings), 0.0);

        RetrievalResult result = orchestrator.retrieve(new RetrievalRequest(
                "顶点公式", RetrievalMode.EXPLAIN, EditionScope.strict(PEP_2024),
                "kp-1", "数学", "八年级", 10));

        List<String> ids = result.chunks().stream().map(s -> s.chunk().id()).toList();
        assertThat(ids).contains("c-exp", "c-ex");
        assertThat(ids).doesNotContain("c-ans");
    }

    @Test
    @DisplayName("版本范围为回退模式时，异版本内容被保留但被标记为需标注差异")
    void fallbackKeepsOtherEditionWithMismatchFlag() {
        List<Chunk> candidates = List.of(
                chunk("c-new", PEP_2024, ChunkRole.EXPLANATION),
                chunk("c-old", PEP_2012, ChunkRole.EXPLANATION));

        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(
                new StubRetrievalPort(candidates, List.of()), 0.0);

        RetrievalResult result = orchestrator.retrieve(new RetrievalRequest(
                "顶点公式", RetrievalMode.EXPLAIN,
                EditionScope.preferredWithFallback(PEP_2024), null, "数学", "八年级", 10));

        assertThat(result.versionMismatchDetected()).isTrue();
        assertThat(result.chunks()).extracting(s -> s.chunk().id()).contains("c-old");
    }
}
