package cn.studykid.rag.application.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.application.generation.GroundedGenerationService.MissingBasisException;
import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.generation.GenerationRequest;
import cn.studykid.rag.core.generation.GenerationResult;
import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.core.version.EditionScope;
import cn.studykid.rag.ports.RetrievalPort;

/**
 * 接地生成用例的不变式（RAG-D00 §2.7 / B5）。
 *
 * <p><b>⚠️ 本测试类锁死四条不可协商的规则</b>：
 * <ol>
 *   <li><b>依据缺失必须显式失败</b>——静默丢弃会让调用方以为依据已被采用；</li>
 *   <li><b>泄题闸门在生成期同样生效</b>，且规则必须与检索期同源
 *       （改 {@code RetrievalPolicy} 两边一起变，不会只变一边）；</li>
 *   <li><b>讲解只含逐字原文</b>——不得出现任何推演或补充结论；</li>
 *   <li><b>「依据不足」是合法输出</b>——200 + 空 answer，不是异常。</li>
 * </ol>
 */
class GroundedGenerationServiceTest {

    private static final EditionCoordinate PEP_2024 = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");

    private static final EditionCoordinate PEP_2012 = new EditionCoordinate(
            EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
            EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2012");

    private static final String ID_A = "11111111-1111-1111-1111-111111111111";
    private static final String ID_B = "22222222-2222-2222-2222-222222222222";
    private static final String ID_C = "33333333-3333-3333-3333-333333333333";

    private static Chunk chunk(String id, EditionCoordinate edition, ChunkRole role, String text) {
        return new Chunk(id, "doc-1", edition, role, text,
                new Chunk.Locator(45, "第3章 > 3.2 二次函数的图像", "12", 0, text.length()),
                List.of(), null, "h-" + id);
    }

    /** 只按 ID 前缀匹配的检索端口替身（模拟 RLS + VERIFIED 收口）。 */
    private static final class StubPort implements RetrievalPort {
        private final List<Chunk> available;

        StubPort(List<Chunk> available) {
            this.available = available;
        }

        @Override
        public List<cn.studykid.rag.core.retrieval.RetrievalResult.ScoredChunk> search(
                cn.studykid.rag.core.retrieval.RetrievalRequest request) {
            return List.of();
        }

        @Override
        public List<Chunk> findByKnowledgePoint(String knowledgePointId, EditionScope editionScope) {
            return List.of();
        }

        @Override
        public List<Chunk> findPrerequisites(String knowledgePointId, EditionScope editionScope) {
            return List.of();
        }

        @Override
        public List<Chunk> findSectionNeighbors(Chunk chunk, int limit) {
            return List.of();
        }

        @Override
        public List<Chunk> loadByIds(List<String> chunkIds) {
            List<Chunk> out = new ArrayList<>();
            for (Chunk c : available) {
                if (chunkIds.contains(c.id())) {
                    out.add(c);
                }
            }
            return out;
        }

        @Override
        public List<cn.studykid.rag.core.knowledge.KnowledgePoint> resolveKnowledgePoints(
                String query, String subject) {
            return List.of();
        }
    }

    private static GenerationRequest request(RetrievalMode mode, EditionScope scope, String... ids) {
        return new GenerationRequest("二次函数的顶点怎么求", mode, scope, List.of(ids));
    }

    @Test
    @DisplayName("⛔ 依据缺失必须显式抛错，绝不静默丢弃")
    void missingBasisFailsLoudly() {
        GroundedGenerationService service = new GroundedGenerationService(
                new StubPort(List.of(chunk(ID_A, PEP_2024, ChunkRole.EXPLANATION, "顶点公式"))));

        assertThatThrownBy(() ->
                service.generate(request(RetrievalMode.EXPLAIN, EditionScope.strict(PEP_2024),
                        ID_A, ID_B)))
                .isInstanceOf(MissingBasisException.class)
                .hasMessageContaining(ID_B);
    }

    @Test
    @DisplayName("⛔ 非法 UUID 在进 SQL 前被拒（映射 400 而非 500）")
    void malformedUuidRejected() {
        GroundedGenerationService service = new GroundedGenerationService(new StubPort(List.of()));

        assertThatThrownBy(() ->
                service.generate(request(RetrievalMode.EXPLAIN, EditionScope.strict(PEP_2024),
                        "not-a-uuid")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-a-uuid");
    }

    @Test
    @DisplayName("⛔ EXPLAIN 模式：答案类依据被闸门滤除，且说明必须告知用户")
    void explainModeSuppressesAnswerBearingBasis() {
        GroundedGenerationService service = new GroundedGenerationService(new StubPort(List.of(
                chunk(ID_A, PEP_2024, ChunkRole.EXPLANATION, "顶点坐标公式"),
                chunk(ID_B, PEP_2024, ChunkRole.EXERCISE_ANSWER, "答案：x = 2"))));

        GenerationResult result = service.generate(
                request(RetrievalMode.EXPLAIN, EditionScope.strict(PEP_2024), ID_A, ID_B));

        assertThat(result.hasAnswer()).isTrue();
        assertThat(result.basis()).extracting(Chunk::id).containsExactly(ID_A);
        // ⛔ 答案正文不得出现在讲解里（逐字也不得）
        assertThat(result.answer()).doesNotContain("x = 2");
        // ⚠️ 但必须告知用户「有内容被策略排除了」——否则学生会以为语料里没有答案
        assertThat(result.answerContentSuppressed()).isTrue();
        assertThat(result.disclaimer()).contains("答案类内容");
    }

    @Test
    @DisplayName("⛔ 依据全被滤光时返回「依据不足」而非异常，也不是泛泛解释")
    void allBasisFilteredYieldsInsufficient() {
        GroundedGenerationService service = new GroundedGenerationService(new StubPort(List.of(
                chunk(ID_A, PEP_2024, ChunkRole.EXERCISE_ANSWER, "答案：x = 2"),
                chunk(ID_B, PEP_2024, ChunkRole.EXAMPLE_SOLUTION, "解：由配方法得"))));

        GenerationResult result = service.generate(
                request(RetrievalMode.EXPLAIN, EditionScope.strict(PEP_2024), ID_A, ID_B));

        assertThat(result.hasAnswer()).isFalse();
        assertThat(result.answer()).isEmpty();
        assertThat(result.basis()).isEmpty();
        assertThat(result.disclaimer()).contains("不可引用");
    }

    @Test
    @DisplayName("⛔ VERIFY 模式保留答案类依据（核对场景本就需要标准答案）")
    void verifyModeKeepsAnswerBearingBasis() {
        GroundedGenerationService service = new GroundedGenerationService(new StubPort(List.of(
                chunk(ID_A, PEP_2024, ChunkRole.EXERCISE_ANSWER, "答案：x = 2"))));

        GenerationResult result = service.generate(
                request(RetrievalMode.VERIFY, EditionScope.strict(PEP_2024), ID_A));

        assertThat(result.hasAnswer()).isTrue();
        assertThat(result.answer()).contains("答案：x = 2");
        assertThat(result.answerContentSuppressed()).isFalse();
    }

    @Test
    @DisplayName("⛔ 版本不一致的依据被滤除，并标注版本差异")
    void otherEditionBasisFiltered() {
        GroundedGenerationService service = new GroundedGenerationService(new StubPort(List.of(
                chunk(ID_A, PEP_2024, ChunkRole.EXPLANATION, "2024版讲法"),
                chunk(ID_B, PEP_2012, ChunkRole.EXPLANATION, "2012版讲法"))));

        GenerationResult result = service.generate(
                request(RetrievalMode.EXPLAIN, EditionScope.strict(PEP_2024), ID_A, ID_B));

        assertThat(result.basis()).extracting(Chunk::id).containsExactly(ID_A);
        assertThat(result.answer()).doesNotContain("2012版讲法");
        assertThat(result.versionMismatchDetected()).isTrue();
        assertThat(result.disclaimer()).contains("版本");
    }

    @Test
    @DisplayName("⛔ 讲解正文只含逐字原文与派生事实，不含任何推演")
    void answerContainsOnlyVerbatimText() {
        String body = "二次函数 y=ax²+bx+c 的顶点坐标为 (-b/2a, (4ac-b²)/4a)。";
        GroundedGenerationService service = new GroundedGenerationService(
                new StubPort(List.of(chunk(ID_A, PEP_2024, ChunkRole.EXPLANATION, body))));

        GenerationResult result = service.generate(
                request(RetrievalMode.EXPLAIN, EditionScope.strict(PEP_2024), ID_A));

        // 原文必须逐字出现
        assertThat(result.answer()).contains(body);
        // ⛔ 不得出现推演连接词——没有蕴含判定就没有结论
        for (String connective : new String[]{"因此", "所以", "综上", "由此可知", "由此可见"}) {
            assertThat(result.answer()).doesNotContain(connective);
        }
        // 引用必须可核对：版本名与页码都要在正文里
        assertThat(result.answer()).contains(PEP_2024.display());
        assertThat(result.answer()).contains("第 45 页");
    }

    @Test
    @DisplayName("⛔ 重复 hitIds 去重：同一段原文不得在讲解里出现两次")
    void duplicateHitIdsDeduplicated() {
        GroundedGenerationService service = new GroundedGenerationService(
                new StubPort(List.of(chunk(ID_A, PEP_2024, ChunkRole.EXPLANATION, "顶点公式"))));

        GenerationResult result = service.generate(
                request(RetrievalMode.EXPLAIN, EditionScope.strict(PEP_2024), ID_A, ID_A, ID_A));

        assertThat(result.basis()).hasSize(1);
        assertThat(result.answer()).containsOnlyOnce("顶点公式");
    }

    @Test
    @DisplayName("⛔ 生成方式必须如实标注，不得伪装成大模型生成")
    void generatorHonestlyLabelled() {
        GroundedGenerationService service = new GroundedGenerationService(
                new StubPort(List.of(chunk(ID_A, PEP_2024, ChunkRole.EXPLANATION, "顶点公式"))));

        GenerationResult result = service.generate(
                request(RetrievalMode.EXPLAIN, EditionScope.strict(PEP_2024), ID_A));

        assertThat(result.generator()).isEqualTo(GenerationResult.GENERATOR_EXCERPT_ASSEMBLY);
    }
}
