package cn.studykid.rag.management.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;
import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.management.core.CorpusSource;
import cn.studykid.rag.management.core.CorpusStatistics;
import cn.studykid.rag.management.core.SourceQuery;
import cn.studykid.rag.management.core.SourceRegistration;
import cn.studykid.rag.management.core.VerificationDecision;
import cn.studykid.rag.management.ports.CorpusManagementPort;

/**
 * 管理端点的契约测试（RAG-D10）。
 *
 * <p><b>⚠️ 覆盖重点</b>：<b>错误语义</b>而非业务逻辑。
 * 管理面的错误码选择是<b>治理契约</b>的一部分：
 * <ul>
 *   <li>缺租户头 → 400（配置错误，必须立刻暴露，不能静默返回空）；</li>
 *   <li>非法状态转移 → 409（治理规则被违反，不是服务故障）；</li>
 *   <li>资源不存在 → 404。</li>
 * </ul>
 * 若错误码退化（如非法转移被报成 500），运维就无法区分
 * 「流程走错了」与「服务坏了」——这是可运维性的直接损失。
 */
class RagManagementControllerTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String SOURCE_ID = "22222222-2222-2222-2222-222222222222";
    private static final String REVIEWER = "33333333-3333-3333-3333-333333333333";

    private final CorpusManagementPort port = mock(CorpusManagementPort.class);
    private final RagManagementController controller = new RagManagementController(port);

    private static CorpusSource sampleSource() {
        Instant now = Instant.parse("2026-10-22T00:00:00Z");
        return new CorpusSource(
                SOURCE_ID, "教材", "人教版八年级上册数学", "人民教育出版社",
                "9787100000000", null, TrustTier.OFFICIAL_PUBLISHER,
                VerificationStatus.VERIFIED, VerificationMethod.ISBN,
                "ISBN:ref@2026-01-01", now.minusSeconds(3600), REVIEWER, 365,
                "LICENSED", "obj://book.pdf", "a".repeat(64), now, now);
    }

    @Test
    @DisplayName("缺租户头时返回 400 而不是静默返回空（fail-closed）")
    void missingTenantHeaderIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listSources(null, null, null, null, null, false, 50, 0));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertNotNull(ex.getReason());
    }

    @Test
    @DisplayName("非法租户 UUID 返回 400")
    void malformedTenantIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listSources("not-a-uuid", null, null, null, null, false, 50, 0));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("来源不存在返回 404")
    void missingSourceIsNotFound() {
        when(port.findSource(any(), eq(SOURCE_ID))).thenReturn(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSource(TENANT, null, SOURCE_ID));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("非法状态转移返回 409（治理规则被违反，不是服务故障）")
    void illegalTransitionIsConflict() {
        when(port.applyVerification(any(), eq(SOURCE_ID), any()))
                .thenThrow(new IllegalStateException("非法的校验状态转移：REJECTED → VERIFIED"));
        ManagementDtos.VerificationRequest request = new ManagementDtos.VerificationRequest(
                VerificationStatus.VERIFIED, "ISBN:ref@2026-01-01", REVIEWER,
                VerificationMethod.ISBN, TrustTier.OFFICIAL_PUBLISHER, 365);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.applyVerification(TENANT, null, SOURCE_ID, request));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    @DisplayName("片段 limit 越界返回 400（防止一次性拉走整册）")
    void chunkLimitOutOfRangeIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listChunks(TENANT, null, SOURCE_ID, 9999));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("登记请求正确映射到端口命令")
    void registerMapsToPortCommand() {
        when(port.registerSource(any(), any(), eq(REVIEWER)))
                .thenReturn(sampleSource());
        ManagementDtos.RegisterSourceRequest request = new ManagementDtos.RegisterSourceRequest(
                "教材", "人教版八年级上册数学", "人民教育出版社",
                "9787100000000", null, "obj://book.pdf", "b".repeat(64), REVIEWER);
        ManagementDtos.SourceResponse response = controller.registerSource(TENANT, null, request);
        assertNotNull(response);
        assertEquals(SOURCE_ID, response.sourceId());
        assertEquals("VERIFIED", response.verificationStatus());
        assertEquals(false, response.stale());
    }

    @Test
    @DisplayName("来源列表返回状态事实，并计算过期标记")
    void listSourcesReturnsStaleFlag() {
        when(port.listSources(any(), any())).thenReturn(List.of(sampleSource()));
        List<ManagementDtos.SourceResponse> responses =
                controller.listSources(TENANT, null, null, null, null, false, 50, 0);
        assertEquals(1, responses.size());
        assertEquals(false, responses.get(0).stale());
        assertEquals(true, responses.get(0).admissible(),
                "VERIFIED + 未过期 + 版权放行 → 准入（以最低门槛判定）");
    }

    @Test
    @DisplayName("统计端点返回分组数据与缺向量比例")
    void statisticsResponseCarriesGroups() {
        CorpusStatistics stats = new CorpusStatistics(
                5, 3, 2, 4, 100, 60, 10,
                java.util.Map.of("EXPLANATION", 80L),
                java.util.Map.of("VERIFIED", 3L, "PENDING", 2L),
                java.util.Map.of("CHUNKED", 4L));
        when(port.statistics(any())).thenReturn(stats);
        ManagementDtos.StatisticsResponse response = controller.statistics(TENANT, null);
        assertEquals(100, response.totalChunks());
        assertEquals(60, response.retrievableChunks());
        assertEquals(0.1, response.missingEmbeddingRatio(), 1e-9);
        assertEquals(3L, response.sourcesByVerificationStatus().get("VERIFIED"));
    }
}
