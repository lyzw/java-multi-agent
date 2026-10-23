package cn.studykid.rag.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.application.grounding.GroundingService;
import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.citation.Citation;
import cn.studykid.rag.core.grounding.GroundingPolicy;
import cn.studykid.rag.core.retrieval.RetrievalResult.HitReason;
import cn.studykid.rag.core.retrieval.RetrievalResult.RetrievalChannel;
import cn.studykid.rag.core.retrieval.RetrievalResult.ScoredChunk;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * 接地校验用例（RAG-D06 §4）——防幻觉的最终裁决。
 *
 * <p>⚠️ 本测试类覆盖 RAG-D08 §2.3 指出的第二个零覆盖类。
 * 核心命题：**不满足接地条件的回答，一律不得交付**。
 */
class GroundingServiceTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static final Chunk EXPLAIN_CHUNK = new Chunk("c-1", "doc-1", EDITION,
            ChunkRole.EXPLANATION, "二次函数的顶点式为 y=a(x-h)^2+k，顶点坐标为 (h,k)。",
            new Chunk.Locator(45, "第3章 > 3.2 二次函数的图像", "12", 100, 160),
            List.of("kp-quadratic"), "12", "hash-1");

    private static final Chunk ANSWER_CHUNK = new Chunk("c-2", "doc-1", EDITION,
            ChunkRole.EXERCISE_ANSWER, "解：配方得 y=(x-2)^2-1，故顶点为 (2,-1)。",
            new Chunk.Locator(120, "答案册", "12", 10, 60),
            List.of("kp-quadratic"), "12", "hash-2");

    private static ScoredChunk scored(Chunk chunk) {
        return new ScoredChunk(chunk, 0.9, 0.9, RetrievalChannel.DENSE_VECTOR, HitReason.DIRECT_MATCH);
    }

    /** 可控的蕴含判定端口替身。 */
    private static final class StubEntailmentPort implements cn.studykid.rag.ports.EntailmentPort {
        private double score = 0.95;
        private boolean existenceOk = true;
        private String conflict;

        @Override
        public double entailment(String claim, String evidence) {
            return score;
        }

        @Override
        public List<cn.studykid.rag.core.grounding.GroundingVerdict> judge(
                List<String> claims, java.util.Map<String, List<Citation>> candidates) {
            return List.of();
        }

        @Override
        public boolean verifyExistence(List<Citation> citations) {
            return existenceOk;
        }

        @Override
        public String detectConflict(List<Citation> citations) {
            return conflict;
        }
    }

    @Nested
    @DisplayName("存在性校验（第一道闸门，不依赖模型）")
    class ExistenceCheck {

        @Test
        @DisplayName("引用指向不存在的片段 → 拒绝交付")
        void rejectsCitationToUnknownChunk() {
            StubEntailmentPort entailment = new StubEntailmentPort();
            GroundingService service = new GroundingService(entailment, GroundingPolicy.productionDefault());

            Citation bogus = new Citation("c-does-not-exist", EDITION, "人教版八上",
                    EXPLAIN_CHUNK.locator(), "顶点坐标为 (h,k)", "顶点公式是什么");

            var result = service.verify(List.of("顶点公式是什么"), List.of(bogus),
                    List.of(scored(EXPLAIN_CHUNK)));

            assertThat(result.deliverable()).isFalse();
            assertThat(result.reason()).contains("不存在");
        }

        @Test
        @DisplayName("引文必须来自被引片段（构造期已保证，这里验证端到端一致）")
        void acceptsValidCitation() {
            StubEntailmentPort entailment = new StubEntailmentPort();
            GroundingService service = new GroundingService(entailment, GroundingPolicy.productionDefault());

            Citation citation = Citation.of(EXPLAIN_CHUNK, "人教版八上",
                    "顶点坐标为 (h,k)", "顶点公式是什么");

            var result = service.verify(List.of("顶点公式是什么"), List.of(citation),
                    List.of(scored(EXPLAIN_CHUNK)));

            assertThat(result.deliverable()).isTrue();
        }
    }

    @Nested
    @DisplayName("角色校验（泄题闸门）")
    class RoleCheck {

        @Test
        @DisplayName("讲解模式下引用了标准答案 → 拒绝交付")
        void rejectsAnswerCitationInExplainMode() {
            StubEntailmentPort entailment = new StubEntailmentPort();
            GroundingService service = new GroundingService(entailment, GroundingPolicy.productionDefault());

            Citation answerCitation = Citation.of(ANSWER_CHUNK, "人教版八上答案册",
                    "故顶点为 (2,-1)", "顶点坐标是什么");

            var result = service.verify(List.of("顶点坐标是什么"), List.of(answerCitation),
                    List.of(scored(ANSWER_CHUNK)));

            assertThat(result.deliverable()).isFalse();
            assertThat(result.reason()).contains("禁止");
        }
    }

    @Nested
    @DisplayName("冲突检测")
    class ConflictCheck {

        @Test
        @DisplayName("引用间存在冲突 → 拒绝交付并说明（不得静默择一）")
        void rejectsConflictingCitations() {
            StubEntailmentPort entailment = new StubEntailmentPort();
            entailment.conflict = "两个版本对顶点坐标的结论不一致";
            GroundingService service = new GroundingService(entailment, GroundingPolicy.productionDefault());

            Citation citation = Citation.of(EXPLAIN_CHUNK, "人教版八上",
                    "顶点坐标为 (h,k)", "顶点公式是什么");

            var result = service.verify(List.of("顶点公式是什么"), List.of(citation),
                    List.of(scored(EXPLAIN_CHUNK)));

            assertThat(result.deliverable()).isFalse();
            assertThat(result.reason()).contains("冲突");
        }
    }

    @Nested
    @DisplayName("蕴含判定与整体裁决")
    class EntailmentAndDecision {

        @Test
        @DisplayName("无引用支撑的断言 → 拒绝交付（fail-closed）")
        void rejectsUngroundedClaim() {
            StubEntailmentPort entailment = new StubEntailmentPort();
            GroundingService service = new GroundingService(entailment, GroundingPolicy.productionDefault());

            var result = service.verify(List.of("顶点坐标是 (2,-1)"), List.of(),
                    List.of(scored(EXPLAIN_CHUNK)));

            assertThat(result.deliverable()).isFalse();
            assertThat(result.flaggedClaims()).contains("顶点坐标是 (2,-1)");
        }

        @Test
        @DisplayName("蕴含度低于阈值 → 判为部分接地并标注")
        void flagsLowEntailment() {
            StubEntailmentPort entailment = new StubEntailmentPort();
            entailment.score = 0.5;
            GroundingService service = new GroundingService(entailment, GroundingPolicy.productionDefault());

            Citation citation = Citation.of(EXPLAIN_CHUNK, "人教版八上",
                    "顶点坐标为 (h,k)", "某个断言");

            var result = service.verify(List.of("某个断言"), List.of(citation),
                    List.of(scored(EXPLAIN_CHUNK)));

            assertThat(result.deliverable()).isTrue();
            assertThat(result.flaggedClaims()).contains("某个断言");
        }

        @Test
        @DisplayName("全部接地 → 正常交付")
        void deliversWhenFullyGrounded() {
            StubEntailmentPort entailment = new StubEntailmentPort();
            GroundingService service = new GroundingService(entailment, GroundingPolicy.productionDefault());

            Citation citation = Citation.of(EXPLAIN_CHUNK, "人教版八上",
                    "顶点坐标为 (h,k)", "顶点公式是什么");

            var result = service.verify(List.of("顶点公式是什么"), List.of(citation),
                    List.of(scored(EXPLAIN_CHUNK)));

            assertThat(result.deliverable()).isTrue();
            assertThat(result.reason()).contains("接地");
        }
    }
}
