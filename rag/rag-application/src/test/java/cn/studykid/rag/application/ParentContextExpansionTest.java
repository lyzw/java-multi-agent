package cn.studykid.rag.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.application.retrieval.RetrievalOrchestrator;
import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.retrieval.RetrievalRequest;
import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.retrieval.RetrievalResult;
import cn.studykid.rag.core.retrieval.RetrievalResult.HitReason;
import cn.studykid.rag.core.retrieval.RetrievalResult.RetrievalChannel;
import cn.studykid.rag.core.retrieval.RetrievalResult.ScoredChunk;
import cn.studykid.rag.core.scope.RagScopeFilter;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.core.version.EditionScope;
import cn.studykid.rag.ports.RetrievalPort;

/**
 * 批 9 / P2-1：检索父块回带的<b>运行期可达性</b>测试。
 *
 * <p>⚠️ 核心命题：{@code RetrievalPolicy.expandParents} 此前只被单测直接调用，
 * 生产检索编排从不触发。本测试证明开启开关后，命中子块会真实回带父块、
 * 且父块<b>再过角色闸门</b>（讲解模式不因回带而泄题）。
 */
class ParentContextExpansionTest {

    private static final RagScopeFilter SCOPE =
            new RagScopeFilter.TenantWithGlobal("11111111-1111-1111-1111-111111111111");

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static final String PARENT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private static Chunk child(String id, String parentId, ChunkRole role) {
        return new Chunk(id, "doc-1", EDITION, role, "子块-" + id,
                new Chunk.Locator(10, "第3章", null, 0, 5), List.of(), null,
                "h-" + id, null, parentId, Chunk.LEVEL_CHILD);
    }

    private static Chunk parent(ChunkRole role) {
        return new Chunk(PARENT_ID, "doc-1", EDITION, role, "父块上下文",
                new Chunk.Locator(10, "第3章", null, 0, 6), List.of(), null,
                "h-parent", null, null, Chunk.LEVEL_PARENT);
    }

    /** 检索端口替身：search 返回子块，loadByIds 返回父块池。 */
    private static final class ParentStubPort implements RetrievalPort {
        private final List<ScoredChunk> hits;
        private final List<Chunk> parents;
        int loadCalls;

        ParentStubPort(List<Chunk> hits, List<Chunk> parents) {
            this.hits = hits.stream()
                    .map(c -> new ScoredChunk(c, 0.9, 0.02,
                            RetrievalChannel.DENSE_VECTOR, HitReason.DIRECT_MATCH))
                    .toList();
            this.parents = parents;
        }

        @Override
        public List<ScoredChunk> search(RagScopeFilter scope, RetrievalRequest request) {
            return hits;
        }

        @Override
        public List<Chunk> loadByIds(RagScopeFilter scope, List<String> chunkIds) {
            loadCalls++;
            // 只返回入参中请求的父块（模拟 RLS 隔离：不在池里的取不到）。
            return parents.stream().filter(p -> chunkIds.contains(p.id())).toList();
        }

        @Override
        public List<Chunk> findByKnowledgePoint(RagScopeFilter scope, String kpId, EditionScope es) {
            return List.of();
        }

        @Override
        public List<Chunk> findPrerequisites(RagScopeFilter scope, String kpId, EditionScope es) {
            return List.of();
        }

        @Override
        public List<Chunk> findSectionNeighbors(RagScopeFilter scope, Chunk c, int limit) {
            return List.of();
        }

        @Override
        public List<KnowledgePoint> resolveKnowledgePoints(RagScopeFilter scope, String q, String s) {
            return List.of();
        }
    }

    private static RetrievalRequest request(RetrievalMode mode) {
        return new RetrievalRequest("顶点式", mode, EditionScope.strict(EDITION),
                null, "数学", "八年级", 5);
    }

    @Test
    @DisplayName("开关开启：命中子块真实回带父块，loadByIds 被调用")
    void parentActuallyLoaded() {
        ParentStubPort port = new ParentStubPort(
                List.of(child("c1", PARENT_ID, ChunkRole.EXPLANATION)),
                List.of(parent(ChunkRole.EXPLANATION)));
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(port, 0.38, true);

        RetrievalResult result = orchestrator.retrieve(SCOPE, request(RetrievalMode.EXPLAIN));

        assertThat(port.loadCalls).isEqualTo(1);
        assertThat(result.chunks()).extracting(s -> s.chunk().id())
                .containsExactly("c1", PARENT_ID);
        ScoredChunk parentHit = result.chunks().get(1);
        assertThat(parentHit.channel()).isEqualTo(RetrievalChannel.PARENT_CONTEXT);
        assertThat(parentHit.reason()).isEqualTo(HitReason.PARENT_EXPANSION);
        // 父块回带不挤占 topK、不进 funnel：returned 仍按检索命中数计。
        assertThat(result.funnel().returned()).isEqualTo(1);
    }

    @Test
    @DisplayName("泄题闸门：回带父块为答案类时，EXPLAIN 模式拒绝回带")
    void parentRejectedByLeakGate() {
        ParentStubPort port = new ParentStubPort(
                List.of(child("c1", PARENT_ID, ChunkRole.EXPLANATION)),
                // 父块被误装配成答案承载角色 → 讲解模式必须挡住
                List.of(parent(ChunkRole.EXERCISE_ANSWER)));
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(port, 0.38, true);

        RetrievalResult result = orchestrator.retrieve(SCOPE, request(RetrievalMode.EXPLAIN));

        assertThat(result.chunks()).extracting(s -> s.chunk().id()).containsExactly("c1");
    }

    @Test
    @DisplayName("灰度过载：开关关闭时完全不触发 loadByIds（既有行为锚点）")
    void disabledNeverLoads() {
        ParentStubPort port = new ParentStubPort(
                List.of(child("c1", PARENT_ID, ChunkRole.EXPLANATION)),
                List.of(parent(ChunkRole.EXPLANATION)));
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(port, 0.38, false);

        RetrievalResult result = orchestrator.retrieve(SCOPE, request(RetrievalMode.EXPLAIN));

        assertThat(port.loadCalls).isZero();
        assertThat(result.chunks()).extracting(s -> s.chunk().id()).containsExactly("c1");
    }

    @Test
    @DisplayName("去重保序：多个子块命中同一父块只回带一次")
    void deduplicatesParent() {
        ParentStubPort port = new ParentStubPort(
                List.of(
                        child("c1", PARENT_ID, ChunkRole.EXPLANATION),
                        child("c2", PARENT_ID, ChunkRole.EXPLANATION)),
                List.of(parent(ChunkRole.EXPLANATION)));
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(port, 0.38, true);

        RetrievalResult result = orchestrator.retrieve(SCOPE, request(RetrievalMode.EXPLAIN));

        assertThat(result.chunks()).extracting(s -> s.chunk().id())
                .containsExactly("c1", "c2", PARENT_ID);
    }

    @Test
    @DisplayName("无父块的子块（顶层）不触发回带")
    void topLevelChunkSkipsExpansion() {
        // 顶层自然块（LEVEL_PARENT + parentId=null），非"子块缺 parentId"
        Chunk topLevel = new Chunk("c1", "doc-1", EDITION, ChunkRole.EXPLANATION, "顶层块",
                new Chunk.Locator(10, "第3章", null, 0, 4), List.of(), null,
                "h-c1", null, null, Chunk.LEVEL_PARENT);
        ParentStubPort port = new ParentStubPort(List.of(topLevel), List.of());
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(port, 0.38, true);

        RetrievalResult result = orchestrator.retrieve(SCOPE, request(RetrievalMode.EXPLAIN));

        assertThat(port.loadCalls).isZero();
        assertThat(result.chunks()).hasSize(1);
    }
}
