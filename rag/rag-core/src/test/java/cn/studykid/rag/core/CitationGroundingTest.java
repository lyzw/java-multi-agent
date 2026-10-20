package cn.studykid.rag.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.citation.Citation;
import cn.studykid.rag.core.citation.Citation.CitationSet;
import cn.studykid.rag.core.grounding.GroundingPolicy;
import cn.studykid.rag.core.grounding.GroundingVerdict;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * 引用与接地判定不变式——防幻觉核心。
 *
 * <p>⚠️ 与主平台 P3-D11 的关系：主平台的 {@code HallucinationDetector}
 * 在「无语料」前提下把<b>任何</b>引用形态判为编造。本项目引入真实语料后，
 * 该规则必须<b>反转为「强制可核验引用」</b>——
 * 判据从「模式匹配」升级为「存在性 + 位置 + 蕴含」，<b>判据更强</b>。
 */
class CitationGroundingTest {

    private static final EditionCoordinate EDITION = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static final Chunk CHUNK = new Chunk("c-1", "doc-1", EDITION,
            ChunkRole.EXPLANATION, "二次函数的顶点式为 y=a(x-h)^2+k，顶点坐标为 (h,k)。",
            new Chunk.Locator(45, "第3章 > 3.2 二次函数的图像", "12", 100, 160),
            List.of("kp-quadratic"), "12", "hash-1");

    @Nested
    @DisplayName("引用构造")
    class Construction {

        @Test
        @DisplayName("引文必须是被引片段的子串——防止「引用了 A 却引了 B 的内容」")
        void quoteMustBeSubstringOfChunk() {
            assertThatThrownBy(() -> Citation.of(CHUNK, "人教版八上",
                    "这是一段不存在于片段中的文字", "顶点坐标是什么"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("子串");
        }

        @Test
        @DisplayName("无逐字引文的引用不可构造——无引文即不可核对")
        void quoteIsMandatory() {
            assertThatThrownBy(() -> new Citation("c-1", EDITION, "人教版八上",
                    CHUNK.locator(), "  ", "顶点坐标是什么"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("verbatimQuote");
        }

        @Test
        @DisplayName("引用可定位到具体位置，供人工核对")
        void citationCarriesLocator() {
            Citation citation = Citation.of(CHUNK, "人教版八上",
                    "顶点坐标为 (h,k)", "顶点坐标是什么");

            assertThat(citation.display()).contains("人教版八上").contains("第 45 页").contains("第 12 题");
            assertThat(citation.pointsTo(CHUNK)).isTrue();
        }

        @Test
        @DisplayName("引用指向错配的片段时 pointsTo 为 false")
        void detectsMismatchedTarget() {
            Chunk other = new Chunk("c-2", "doc-1", EDITION, ChunkRole.EXPLANATION,
                    "另一段内容", new Chunk.Locator(46, "第3章", null, 0, 5),
                    List.of(), null, "hash-2");
            Citation citation = Citation.of(CHUNK, "人教版八上", "顶点坐标为 (h,k)", "声明");

            assertThat(citation.pointsTo(other)).isFalse();
        }
    }

    @Nested
    @DisplayName("接地策略（fail-closed）")
    class Policy {

        @Test
        @DisplayName("无引用支撑的声明 → 拒绝交付")
        void rejectsUngroundedClaims() {
            GroundingPolicy policy = GroundingPolicy.productionDefault();
            GroundingVerdict ungrounded = new GroundingVerdict(
                    GroundingVerdict.Verdict.UNGROUNDED, "顶点坐标是 (2,-1)", List.of(),
                    "无引用支撑", 0.0);

            GroundingPolicy.DeliveryDecision decision = policy.decide(List.of(ungrounded));

            assertThat(decision.deliverable()).isFalse();
            assertThat(decision.flaggedClaims()).contains("顶点坐标是 (2,-1)");
        }

        @Test
        @DisplayName("全部声明均接地 → 允许交付")
        void acceptsFullyGroundedClaims() {
            GroundingPolicy policy = GroundingPolicy.productionDefault();
            Citation citation = Citation.of(CHUNK, "人教版八上", "顶点坐标为 (h,k)", "顶点公式");
            GroundingVerdict grounded = new GroundingVerdict(
                    GroundingVerdict.Verdict.GROUNDED, "顶点公式是 y=a(x-h)^2+k",
                    List.of(citation), null, 0.92);

            assertThat(policy.decide(List.of(grounded)).deliverable()).isTrue();
        }

        @Test
        @DisplayName("部分接地 → 允许交付但须标记（不整段作废，避免诱发「无引用空话」）")
        void partialGroundingIsFlaggedNotRejected() {
            GroundingPolicy policy = GroundingPolicy.productionDefault();
            Citation citation = Citation.of(CHUNK, "人教版八上", "顶点坐标为 (h,k)", "声明");
            GroundingVerdict partial = new GroundingVerdict(
                    GroundingVerdict.Verdict.PARTIALLY_GROUNDED, "某断言", List.of(citation),
                    null, 0.5);

            GroundingPolicy.DeliveryDecision decision = policy.decide(List.of(partial));

            assertThat(decision.deliverable()).isTrue();
            assertThat(decision.flaggedClaims()).contains("某断言");
        }

        @Test
        @DisplayName("默认策略禁止在讲解模式引用答案类内容")
        void defaultPolicyBlocksAnswerBearingCitations() {
            GroundingPolicy policy = GroundingPolicy.productionDefault();
            assertThat(policy.allowsRole(ChunkRole.EXPLANATION)).isTrue();
            assertThat(policy.allowsRole(ChunkRole.EXERCISE_ANSWER)).isFalse();
            assertThat(policy.allowsRole(ChunkRole.EXAMPLE_SOLUTION)).isFalse();
        }

        @Test
        @DisplayName("反证：若放开答案类引用，泄题闸门即失效")
        void falsificationOpeningAnswerPolicyBreaksLeakGate() {
            GroundingPolicy permissive = new GroundingPolicy(0.75, true, true, true, 0.35);
            assertThat(permissive.allowsRole(ChunkRole.EXERCISE_ANSWER)).isTrue();
        }
    }

    @Nested
    @DisplayName("引用集合")
    class Set {

        @Test
        @DisplayName("集合可检出版本混用（跨版本对比场景须显式处理）")
        void detectsMixedEditions() {
            EditionCoordinate other = new EditionCoordinate(
                    EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
                    EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.SUEP, "2024修订");
            Chunk otherChunk = new Chunk("c-2", "doc-2", other, ChunkRole.EXPLANATION,
                    "另一版本内容", new Chunk.Locator(45, "第3章", null, 0, 6),
                    List.of(), null, "hash-2");

            CitationSet set = new CitationSet(List.of(
                    Citation.of(CHUNK, "人教版八上", "顶点坐标为 (h,k)", "声明A"),
                    Citation.of(otherChunk, "苏教八上", "另一版本内容", "声明B")));

            assertThat(set.hasMixedEditions()).isTrue();
            assertThat(set.editions()).hasSize(2);
        }

        @Test
        @DisplayName("可按声明反查支撑引用")
        void findsSupportingCitations() {
            Citation a = Citation.of(CHUNK, "人教版八上", "顶点坐标为 (h,k)", "声明A");
            Citation b = Citation.of(CHUNK, "人教版八上", "顶点坐标为 (h,k)", "声明B");

            CitationSet set = new CitationSet(List.of(a, b));

            assertThat(set.supporting("声明A")).containsExactly(a);
            assertThat(set.supporting("声明C")).isEmpty();
        }
    }
}
