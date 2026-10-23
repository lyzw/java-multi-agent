package cn.studykid.rag.api.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.rag.application.generation.GroundedGenerationService.MissingBasisException;
import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.generation.GenerationRequest;
import cn.studykid.rag.core.generation.GenerationResult;
import cn.studykid.rag.core.retrieval.RetrievalRequest.RetrievalMode;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.core.version.EditionScope;
import cn.studykid.rag.ports.TenantScopedGeneration;
import cn.studykid.rag.ports.TenantScopedRetrieval;

/**
 * 生成端点的契约测试（B5）。
 *
 * <p><b>⚠️ 覆盖重点是错误码语义而非业务逻辑</b>（与
 * {@code RagManagementControllerTest} 同一立场）：错误码是<b>可运维性契约</b>。
 * <ul>
 *   <li>缺/非法租户头 → 400：调用方配置错误，立刻暴露；</li>
 *   <li>依据缺失 → 400：调用方传了无效 ID，<b>不是 500</b>
 *       （报成 5xx 会让运维去查服务健康而不是查调用方参数）；</li>
 *   <li>依据被闸门滤光 → 200 + 空 answer：合法业务结果。</li>
 * </ul>
 */
class RagRetrievalControllerGenerateTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String HIT = "22222222-2222-2222-2222-222222222222";

    private final TenantScopedRetrieval retrieval = mock(TenantScopedRetrieval.class);
    private final TenantScopedGeneration generation = mock(TenantScopedGeneration.class);
    private final RagRetrievalController controller = new RagRetrievalController(retrieval, generation);

    private static EditionDto edition() {
        return new EditionDto("数学", "八年级", "上册", "人教版", "2024修订");
    }

    @Test
    @DisplayName("⛔ 缺租户头 → 400（配置错误，不静默返回空结果）")
    void missingTenantHeaderRejected() {
        GenerationRequestDto dto = new GenerationRequestDto(
                "顶点怎么求", RetrievalMode.EXPLAIN, edition(), null, List.of(HIT));

        assertThatThrownBy(() -> controller.generate(null, dto))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("⛔ 租户头不是合法 UUID → 400")
    void malformedTenantHeaderRejected() {
        GenerationRequestDto dto = new GenerationRequestDto(
                "顶点怎么求", RetrievalMode.EXPLAIN, edition(), null, List.of(HIT));

        assertThatThrownBy(() -> controller.generate("not-a-uuid", dto))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("⛔ 依据缺失 → 400（不是 500：这是调用方错误）")
    void missingBasisMappedToBadRequest() {
        GenerationRequestDto dto = new GenerationRequestDto(
                "顶点怎么求", RetrievalMode.EXPLAIN, edition(), null, List.of(HIT));
        when(generation.generate(eq(TENANT), any(GenerationRequest.class)))
                .thenThrow(new MissingBasisException(List.of(HIT)));

        assertThatThrownBy(() -> controller.generate(TENANT, dto))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("⛔ 连通性：租户与依据 ID 原样透传到端口（不得改写入参）")
    void tenantAndHitIdsPassedThrough() {
        GenerationRequestDto dto = new GenerationRequestDto(
                "顶点怎么求", RetrievalMode.EXPLAIN, edition(), null, List.of(HIT));
        when(generation.generate(eq(TENANT), any(GenerationRequest.class)))
                .thenReturn(GenerationResult.insufficient("test"));

        controller.generate(TENANT, dto);

        verify(generation).generate(eq(TENANT), any(GenerationRequest.class));
    }

    @Test
    @DisplayName("⛔ 依据被滤光 → 200 + 空 answer（合法业务结果，不当故障）")
    void insufficientBasisReturnsEmptyAnswer() {
        GenerationRequestDto dto = new GenerationRequestDto(
                "顶点怎么求", RetrievalMode.EXPLAIN, edition(), null, List.of(HIT));
        when(generation.generate(eq(TENANT), any(GenerationRequest.class)))
                .thenReturn(GenerationResult.insufficient("依据不足"));

        GenerationResultDto result = controller.generate(TENANT, dto);

        assertThat(result.answer()).isEmpty();
        assertThat(result.citations()).isEmpty();
        // ⚠️ 生成方式仍须如实标注，即便没有内容
        assertThat(result.generator()).isEqualTo(GenerationResult.GENERATOR_EXCERPT_ASSEMBLY);
    }

    @Test
    @DisplayName("⛔ 引用必须携带版本与页码，且通道标注为「生成依据」而非检索通道")
    void citationsCarryLocatorAndHonestChannel() {
        EditionCoordinate coordinate = new EditionCoordinate(
                EditionCoordinate.Subject.MATH, EditionCoordinate.GradeBand.GRADE_8,
                EditionCoordinate.Term.FIRST, EditionCoordinate.Publisher.PEP, "2024修订");
        Chunk chunk = new Chunk(HIT, "doc-1", coordinate, ChunkRole.EXPLANATION, "顶点公式",
                new Chunk.Locator(45, "第3章 > 3.2 二次函数的图像", "12", 0, 4),
                List.of(), null, "h");
        when(generation.generate(eq(TENANT), any(GenerationRequest.class)))
                .thenReturn(new GenerationResult("讲解", List.of(chunk),
                        GenerationResult.GENERATOR_EXCERPT_ASSEMBLY, "", false, false));

        GenerationResultDto result = controller.generate(TENANT, new GenerationRequestDto(
                "顶点怎么求", RetrievalMode.EXPLAIN, edition(), null, List.of(HIT)));

        assertThat(result.citations()).hasSize(1);
        RetrievalResultDto.HitDto hit = result.citations().get(0);
        assertThat(hit.chunkId()).isEqualTo(HIT);
        assertThat(hit.edition()).isEqualTo(coordinate.display());
        assertThat(hit.locator()).contains("第 45 页");
        // ⛔ 不得伪装成向量召回——「这条为什么出现」必须可解释
        assertThat(hit.channel()).isEqualTo("GENERATION_BASIS");
        assertThat(hit.reason()).isEqualTo("CITED_AS_BASIS");
    }
}
