package cn.studykid.rag.management.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import cn.studykid.rag.management.core.CorpusDocument;
import cn.studykid.rag.management.core.CorpusIssues;
import cn.studykid.rag.management.core.CorpusSource;
import cn.studykid.rag.management.core.CorpusStatistics;
import cn.studykid.rag.management.core.SourceQuery;
import cn.studykid.rag.management.core.SourceRegistration;
import cn.studykid.rag.management.core.VerificationDecision;
import cn.studykid.rag.management.ports.CorpusIndexingPort;
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
    private static final String DOCUMENT_ID = "55555555-5555-5555-5555-555555555555";
    private static final String REVIEWER = "33333333-3333-3333-3333-333333333333";

    private final CorpusManagementPort port = mock(CorpusManagementPort.class);
    private final CorpusIndexingPort indexingPort = mock(CorpusIndexingPort.class);
    private final RagManagementController controller =
            new RagManagementController(port, indexingPort);

    private static CorpusSource sampleSource() {
        Instant now = Instant.parse("2026-10-22T00:00:00Z");
        return new CorpusSource(
                SOURCE_ID, "教材", "人教版八年级上册数学", "人民教育出版社",
                "9787100000000", null, TrustTier.OFFICIAL_PUBLISHER,
                VerificationStatus.VERIFIED, VerificationMethod.ISBN,
                "ISBN:ref@2026-01-01", now.minusSeconds(3600), REVIEWER, 365,
                "LICENSED", "obj://book.pdf", "a".repeat(64), now, now);
    }

    private static CorpusDocument sampleDocument(VerificationStatus status, boolean retrievable) {
        Instant now = Instant.parse("2026-10-22T00:00:00Z");
        return new CorpusDocument(
                DOCUMENT_ID, SOURCE_ID, "66666666-6666-6666-6666-666666666666",
                "PDF", "人教版八年级上册数学（扫描版）", 120, "obj://scan.pdf",
                "CHUNKED", "local-parser-v1", "c".repeat(64), 30,
                status, retrievable,
                status == VerificationStatus.REVOKED ? now : null,
                status == VerificationStatus.REVOKED ? "权利人通知" : null,
                status == VerificationStatus.REVOKED ? "RESTRICTED" : "PENDING",
                now, now);
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

    // ---------------------------------------------------------------------
    // 载体级校验流转
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("载体不存在返回 404")
    void missingDocumentIsNotFound() {
        when(port.applyDocumentVerification(any(), eq(DOCUMENT_ID), any())).thenReturn(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.applyDocumentVerification(TENANT, null, DOCUMENT_ID,
                        documentRequest(VerificationStatus.IN_REVIEW)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("载体级非法状态转移返回 409")
    void illegalDocumentTransitionIsConflict() {
        when(port.applyDocumentVerification(any(), eq(DOCUMENT_ID), any()))
                .thenThrow(new IllegalStateException("非法的校验状态转移：REVOKED → VERIFIED"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.applyDocumentVerification(TENANT, null, DOCUMENT_ID,
                        documentRequest(VerificationStatus.VERIFIED)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    @DisplayName("载体高于来源返回 409，且消息点明是来源未通过校验")
    void documentAboveSourceIsConflict() {
        when(port.applyDocumentVerification(any(), eq(DOCUMENT_ID), any()))
                .thenThrow(new IllegalStateException("载体不得高于来源：来源当前校验状态为 PENDING，"
                        + "未通过校验的来源下的载体不允许置为 VERIFIED"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.applyDocumentVerification(TENANT, null, DOCUMENT_ID,
                        documentRequest(VerificationStatus.VERIFIED)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("来源"),
                "409 消息必须说清是来源未通过校验：" + ex.getReason());
    }

    @Test
    @DisplayName("目标状态 PENDING 属于入参形态问题 → 400")
    void pendingTargetIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.applyDocumentVerification(TENANT, null, DOCUMENT_ID,
                        documentRequest(VerificationStatus.PENDING)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("非法 documentId 返回 400（而不是让 PG 的 uuid 转换报 500）")
    void malformedDocumentIdIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.applyDocumentVerification(TENANT, null, "not-a-uuid",
                        documentRequest(VerificationStatus.IN_REVIEW)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("载体缺租户头返回 400")
    void documentVerificationWithoutTenantIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.applyDocumentVerification(null, null, DOCUMENT_ID,
                        documentRequest(VerificationStatus.IN_REVIEW)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("载体级流转成功后返回含治理字段的文档视图")
    void documentVerificationReturnsExtendedView() {
        when(port.applyDocumentVerification(any(), eq(DOCUMENT_ID), any()))
                .thenReturn(sampleDocument(VerificationStatus.VERIFIED, true));
        ManagementDtos.DocumentResponse response = controller.applyDocumentVerification(
                TENANT, null, DOCUMENT_ID, documentRequest(VerificationStatus.VERIFIED));
        assertEquals(DOCUMENT_ID, response.documentId());
        assertEquals("VERIFIED", response.verificationStatus());
        assertTrue(response.retrievable(), "VERIFIED 的载体必须可检索");
        assertEquals(null, response.revokedAt());
        assertEquals("PENDING", response.licenseStatus());
    }

    @Test
    @DisplayName("撤回后的载体视图带撤回时间与依据")
    void revokedDocumentViewCarriesReason() {
        when(port.applyDocumentVerification(any(), eq(DOCUMENT_ID), any()))
                .thenReturn(sampleDocument(VerificationStatus.REVOKED, false));
        ManagementDtos.DocumentResponse response = controller.applyDocumentVerification(
                TENANT, null, DOCUMENT_ID, documentRequest(VerificationStatus.REVOKED));
        assertEquals("REVOKED", response.verificationStatus());
        assertEquals(false, response.retrievable());
        assertNotNull(response.revokedAt());
        assertEquals("权利人通知", response.revokedReason());
        assertEquals("RESTRICTED", response.licenseStatus());
    }

    // ---------------------------------------------------------------------
    // 维护问题清单
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("问题清单缺租户头返回 400")
    void issuesWithoutTenantIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.issues(null, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("问题清单返回三类条目与合计")
    void issuesResponseCarriesGroupsAndTotals() {
        CorpusIssues issues = new CorpusIssues(
                List.of(new CorpusIssues.DocumentEmbeddingGap(
                        DOCUMENT_ID, SOURCE_ID, "扫描版", "PDF", "CHUNKED", 30, 12)),
                List.of(new CorpusIssues.DocumentParseFailure(
                        "77777777-7777-7777-7777-777777777777", SOURCE_ID, "损坏件", "PDF", "FAILED", 0)),
                List.of(new CorpusIssues.SourcePendingReview(
                        SOURCE_ID, "待审教材", "PENDING", false, "UNVERIFIED")));
        when(port.listIssues(any())).thenReturn(issues);

        ManagementDtos.IssuesResponse response = controller.issues(TENANT, null);

        assertEquals(1, response.documentsMissingEmbedding().size());
        assertEquals(12, response.documentsMissingEmbedding().get(0).missingEmbeddingCount());
        assertEquals(1, response.documentsFailedParse().size());
        assertEquals("FAILED", response.documentsFailedParse().get(0).parseStatus());
        assertEquals(1, response.sourcesPendingReview().size());
        assertEquals(false, response.sourcesPendingReview().get(0).stale());
        assertEquals(1, response.totals().documentsMissingEmbedding());
        assertEquals(1, response.totals().documentsFailedParse());
        assertEquals(1, response.totals().sourcesPendingReview());
    }

    // ---------------------------------------------------------------------
    // 重跑索引
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("重跑索引缺租户头返回 400")
    void reindexWithoutTenantIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reindex(null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("重跑索引 limit 越界返回 400（防止一次 HTTP 请求跑太久）")
    void reindexLimitOutOfRangeIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reindex(TENANT, null, new ManagementDtos.ReindexRequest(2001)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("重跑索引只作用于本租户，并返回处理数与耗时")
    void reindexTargetsCurrentTenantOnly() {
        when(indexingPort.reindex(any(), anyInt()))
                .thenReturn(new CorpusIndexingPort.ReindexOutcome(12, 12, 340));
        ManagementDtos.ReindexResponse response =
                controller.reindex(TENANT, null, new ManagementDtos.ReindexRequest(500));
        assertEquals(12, response.indexedChunks());
        assertEquals(12, response.scannedChunks());
        assertEquals(340, response.durationMs());
        verify(indexingPort).reindex(eq(TENANT), eq(500));
    }

    @Test
    @DisplayName("重跑索引未带请求体时用默认上限")
    void reindexWithoutBodyUsesDefaultLimit() {
        when(indexingPort.reindex(any(), anyInt()))
                .thenReturn(new CorpusIndexingPort.ReindexOutcome(0, 0, 5));
        controller.reindex(TENANT, null, null);
        verify(indexingPort).reindex(eq(TENANT), eq(500));
    }

    private ManagementDtos.DocumentVerificationRequest documentRequest(VerificationStatus target) {
        return new ManagementDtos.DocumentVerificationRequest(
                target, "ISBN:ref@2026-10-23", REVIEWER, VerificationMethod.ISBN,
                TrustTier.OFFICIAL_PUBLISHER, 365);
    }
}
