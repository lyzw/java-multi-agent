package cn.studykid.rag.management.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.rag.core.scope.RagCorpusScope;
import cn.studykid.rag.core.scope.RagScope;
import cn.studykid.rag.core.security.RagCallerAttributes;
import cn.studykid.rag.management.core.RagManagementContext;
import cn.studykid.rag.management.core.ReviewerRosterEntry;
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
import cn.studykid.rag.management.ports.DictionaryPort;
import cn.studykid.rag.management.ports.ObjectStorePort;

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

    /**
     * 带真实 PDF 魔数（{@code %PDF-1.7}）的测试字节。
     *
     * <p>⚠️ V1.0.2 §4 Z1 引入魔数与后缀一致性校验后，夹具**不能**再用
     * {@code "dummy-pdf-bytes"} 这类假内容——否则测的是「魔数校验拒收」，
     * 而不是「合法上传成功」这条本该验证的路径。
     */
    private static final byte[] PDF_MAGIC = "%PDF-1.7\n%test\n".getBytes(
            java.nio.charset.StandardCharsets.US_ASCII);
    private static final String DOCUMENT_ID = "55555555-5555-5555-5555-555555555555";
    private static final String REVIEWER = "33333333-3333-3333-3333-333333333333";

    /**
     * 作用域头的三个取值（缺省档写成字面量 {@code null}，
     * 与「调用方没带头」的真实形态一致）。
     */
    private static final String SCOPE_TENANT = "TENANT";
    private static final String SCOPE_PLATFORM = "PLATFORM";
    private static final String SCOPE_ALL = "ALL";

    /** 调用方域：管理台令牌（{@code aud=admin-console-api}）解析出的平台域。 */
    private static final String PLATFORM_CALLER = RagCallerAttributes.DOMAIN_PLATFORM;
    /** 调用方域：租户令牌解析出的租户域。 */
    private static final String TENANT_CALLER = RagCallerAttributes.DOMAIN_TENANT;
    /** 调用方域：鉴权逃生门（未验证）——既不是租户也不是平台。 */
    private static final String UNVERIFIED_CALLER = RagCallerAttributes.DOMAIN_UNVERIFIED;

    private final CorpusManagementPort port = mock(CorpusManagementPort.class);
    private final CorpusIndexingPort indexingPort = mock(CorpusIndexingPort.class);
    private final DictionaryPort dictionaryPort = mock(DictionaryPort.class);
    private final ObjectStorePort objectStore = mock(ObjectStorePort.class);
    private final RagManagementController controller =
            new RagManagementController(port, indexingPort, dictionaryPort, objectStore);

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
                () -> controller.listSources(null, null, null, PLATFORM_CALLER, null, null, null, false, 50, 0));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertNotNull(ex.getReason());
    }

    @Test
    @DisplayName("非法租户 UUID 返回 400")
    void malformedTenantIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listSources("not-a-uuid", null, null, PLATFORM_CALLER, null, null, null, false, 50, 0));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("来源不存在返回 404")
    void missingSourceIsNotFound() {
        when(port.findSource(any(), eq(SOURCE_ID))).thenReturn(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSource(TENANT, null, null, PLATFORM_CALLER, SOURCE_ID));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("非法状态转移返回 409（治理规则被违反，不是服务故障）")
    void illegalTransitionIsConflict() {
        when(port.applyVerification(any(), eq(SOURCE_ID), any()))
                .thenThrow(new IllegalStateException("非法的校验状态转移：REJECTED → VERIFIED"));
        // ⚠️ V1.0.2 S3-2：VerificationRequest 新增 verifiedBy（双人复核的第二个主体）
        ManagementDtos.VerificationRequest request = new ManagementDtos.VerificationRequest(
                VerificationStatus.VERIFIED, null, "ISBN:ref@2026-01-01", REVIEWER,
                VerificationMethod.ISBN, TrustTier.OFFICIAL_PUBLISHER, 365);
        // ⚠️ 本用例验证的是「非法状态转移 → 409」。它捕获的是端口抛出的 IllegalStateException，
        //    而端口调用位于**角色闸门之后**，所以必须给一个能过闸门的角色
        //    （否则会先在角色闸门得到 403，测不到状态机那条路径）。
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.applyVerification(TENANT, null, SCOPE_TENANT, SOURCE_ID,
                        // 参数序：domain, ticketScope, principalAttr, rolesAttr, principalTypeAttr, body
                        PLATFORM_CALLER, null, null, "TENANT_REVIEWER", null, request));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    @DisplayName("片段 limit 越界返回 400（防止一次性拉走整册）")
    void chunkLimitOutOfRangeIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listChunks(TENANT, null, null, PLATFORM_CALLER, SOURCE_ID, 9999, null));
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
        ManagementDtos.SourceResponse response = controller.registerSource(TENANT, null, SCOPE_TENANT, PLATFORM_CALLER, null, request);
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
                controller.listSources(TENANT, null, null, PLATFORM_CALLER, null, null, null, false, 50, 0);
        assertEquals(1, responses.size());
        assertEquals(false, responses.get(0).stale());
        assertEquals(true, responses.get(0).admissible(),
                "VERIFIED + 未过期 + 版权放行 → 准入（以最低门槛判定）");
    }

    // ---------------------------------------------------------------------
    // 批量登记（数据导入）
    // ---------------------------------------------------------------------

    private static ManagementDtos.BulkSourceItem bulkItem() {
        return new ManagementDtos.BulkSourceItem(
                "教材", "人教版八年级上册数学", "人民教育出版社",
                "9787100000000", null, "obj://book.pdf", "b".repeat(64));
    }

    @Test
    @DisplayName("批量导入：成功行入库、构造失败行归入失败列表且不下传端口")
    void bulkImportMixOfSuccessAndInvalid() {
        when(port.registerSources(any(), any(), eq(REVIEWER)))
                .thenReturn(new CorpusManagementPort.BulkRegistrationResult(List.of(
                        new CorpusManagementPort.BulkItemResult(0, true, SOURCE_ID, null))));
        ManagementDtos.BulkRegisterRequest request = new ManagementDtos.BulkRegisterRequest(
                REVIEWER,
                List.of(
                        bulkItem(),
                        // 缺 ISBN 与 DOI → 构造期校验失败（SourceRegistration 要求至少其一）
                        new ManagementDtos.BulkSourceItem(
                                "教材", "无标识来源", "人民教育出版社", null, null, null, null)));
        ManagementDtos.BulkRegisterResponse response =
                controller.bulkRegisterSources(TENANT, null, SCOPE_TENANT, PLATFORM_CALLER, null, request);
        assertEquals(2, response.total());
        assertEquals(1, response.succeeded());
        assertEquals(1, response.failed());
        assertTrue(response.items().stream().anyMatch(i -> i.ok() && SOURCE_ID.equals(i.sourceId())),
                "成功行应带 sourceId");
        assertTrue(response.items().stream().anyMatch(i -> !i.ok() && i.error() != null),
                "构造失败行应带错误信息且不下传端口");
        verify(port).registerSources(any(), argThat(list -> list.size() == 1), eq(REVIEWER));
    }

    @Test
    @DisplayName("批量导入缺租户头返回 400")
    void bulkImportWithoutTenantIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.bulkRegisterSources(null, null, SCOPE_TENANT, PLATFORM_CALLER, null,
                        new ManagementDtos.BulkRegisterRequest(REVIEWER, List.of(bulkItem()))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("⛔ 批量导入：内容校验和格式非法的行必须被拒，且不得整批报废")
    void bulkImportRejectsMalformedContentHashPerRow() {
        when(port.registerSources(any(), any(), eq(REVIEWER)))
                .thenReturn(new CorpusManagementPort.BulkRegistrationResult(List.of(
                        new CorpusManagementPort.BulkItemResult(0, true, SOURCE_ID, null))));
        ManagementDtos.BulkRegisterRequest request = new ManagementDtos.BulkRegisterRequest(
                REVIEWER,
                List.of(
                        bulkItem(),
                        // ⚠️ contentHash 非 64 位十六进制 —— 修复前会一路进库
                        //    （rag_source.content_hash 是 CHAR(64)，
                        //     短值会被 PG 静默空格填充，不报错）
                        new ManagementDtos.BulkSourceItem(
                                "教材", "哈希非法的来源", "人民教育出版社",
                                "9787100000001", null, "obj://book2.pdf", "NOT-A-HASH")));
        ManagementDtos.BulkRegisterResponse response =
                controller.bulkRegisterSources(TENANT, null, SCOPE_TENANT, PLATFORM_CALLER, null, request);

        assertEquals(2, response.total());
        assertEquals(1, response.succeeded(),
                "合法行仍应入库（逐条成败语义不被破坏）");
        assertEquals(1, response.failed());
        ManagementDtos.BulkItemResultDto badRow = response.items().stream()
                .filter(i -> !i.ok())
                .findFirst()
                .orElseThrow(() -> new AssertionError("应有一行因 contentHash 非法而失败"));
        assertTrue(badRow.error() != null && badRow.error().contains("contentHash"),
                "失败原因应指明是 contentHash 问题，实际: " + badRow.error());
        verify(port).registerSources(any(), argThat(list -> list.size() == 1), eq(REVIEWER));
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
        ManagementDtos.StatisticsResponse response = controller.statistics(TENANT, null, null, PLATFORM_CALLER);
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
                () -> controller.applyDocumentVerification(TENANT, null, SCOPE_TENANT, DOCUMENT_ID, PLATFORM_CALLER, null,
                        documentRequest(VerificationStatus.IN_REVIEW)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("载体级非法状态转移返回 409")
    void illegalDocumentTransitionIsConflict() {
        when(port.applyDocumentVerification(any(), eq(DOCUMENT_ID), any()))
                .thenThrow(new IllegalStateException("非法的校验状态转移：REVOKED → VERIFIED"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.applyDocumentVerification(TENANT, null, SCOPE_TENANT, DOCUMENT_ID, PLATFORM_CALLER, null,
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
                () -> controller.applyDocumentVerification(TENANT, null, SCOPE_TENANT, DOCUMENT_ID, PLATFORM_CALLER, null,
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
                () -> controller.applyDocumentVerification(TENANT, null, SCOPE_TENANT, DOCUMENT_ID, PLATFORM_CALLER, null,
                        documentRequest(VerificationStatus.PENDING)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("非法 documentId 返回 400（而不是让 PG 的 uuid 转换报 500）")
    void malformedDocumentIdIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.applyDocumentVerification(TENANT, null, SCOPE_TENANT, "not-a-uuid", PLATFORM_CALLER, null,
                        documentRequest(VerificationStatus.IN_REVIEW)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("载体缺租户头返回 400")
    void documentVerificationWithoutTenantIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.applyDocumentVerification(null, null, SCOPE_TENANT, DOCUMENT_ID, PLATFORM_CALLER, null,
                        documentRequest(VerificationStatus.IN_REVIEW)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("载体级流转成功后返回含治理字段的文档视图")
    void documentVerificationReturnsExtendedView() {
        when(port.applyDocumentVerification(any(), eq(DOCUMENT_ID), any()))
                .thenReturn(sampleDocument(VerificationStatus.VERIFIED, true));
        ManagementDtos.DocumentResponse response = controller.applyDocumentVerification(
                TENANT, null, SCOPE_TENANT, DOCUMENT_ID, PLATFORM_CALLER, null, documentRequest(VerificationStatus.VERIFIED));
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
                TENANT, null, SCOPE_TENANT, DOCUMENT_ID, PLATFORM_CALLER, null, documentRequest(VerificationStatus.REVOKED));
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
                () -> controller.issues(null, null, null, null));
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

        ManagementDtos.IssuesResponse response = controller.issues(TENANT, null, null, PLATFORM_CALLER);

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
                () -> controller.reindex(null, null, SCOPE_TENANT, null, null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("重跑索引 limit 越界返回 400（防止一次 HTTP 请求跑太久）")
    void reindexLimitOutOfRangeIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reindex(TENANT, null, SCOPE_TENANT, null, null,
                        new ManagementDtos.ReindexRequest(2001), null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("重跑索引只作用于本租户，并返回处理数与耗时")
    void reindexTargetsCurrentTenantOnly() {
        when(indexingPort.reindex(any(), anyInt()))
                .thenReturn(new CorpusIndexingPort.ReindexOutcome(12, 12, 340));
        ManagementDtos.ReindexResponse response =
                controller.reindex(TENANT, null, SCOPE_TENANT, null, null,
                        new ManagementDtos.ReindexRequest(500), null);
        assertEquals(12, response.indexedChunks());
        assertEquals(12, response.scannedChunks());
        assertEquals(340, response.durationMs());
        verify(indexingPort).reindex(eq(TENANT), eq(500));
    }

    @Test
    @DisplayName("可复核审核员下拉：排除本人与采集人（两人后端都会拒绝）")
    void coSignersExcludeSelfAndRegistrant() {
        when(port.findRegisteredBy(any(), eq("s-1"))).thenReturn("u-creator");
        when(port.listCoSigners(any(), anyCollection()))
                .thenReturn(java.util.List.of(
                        new ReviewerRosterEntry("u-reviewer", "教研组-李老师", true, true, true)));

        java.util.List<ManagementDtos.ReviewerItem> items = controller.listCoSigners(
                TENANT, null, SCOPE_TENANT, null, "u-self", "s-1");

        assertEquals(1, items.size());
        assertEquals("教研组-李老师", items.get(0).displayName());
        // ⚠️ 必须把「本人」与「采集人」都排除掉：
        //    后端 SoD 会拒绝这两类人做复核，下拉里留着他们等于让人必然 409。
        verify(port).listCoSigners(any(), argThat(ids ->
                ids.contains("u-self") && ids.contains("u-creator")));
    }

    @Test
    @DisplayName("读不到采集人时不阻断列表（SOD 对 null 不判自审）")
    void coSignersToleratesMissingRegistrant() {
        when(port.findRegisteredBy(any(), eq("s-1"))).thenThrow(new IllegalStateException("boom"));
        when(port.listCoSigners(any(), anyCollection()))
                .thenReturn(java.util.List.of());

        java.util.List<ManagementDtos.ReviewerItem> items = controller.listCoSigners(
                TENANT, null, SCOPE_TENANT, null, "u-self", "s-1");

        assertEquals(0, items.size());
    }

    @Test
    @DisplayName("登记审核员：可复核必须同时可审核（矛盾状态提前拦下）")
    void upsertReviewerRejectsCoSignWithoutReview() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.upsertReviewer(TENANT, null, SCOPE_TENANT, null, null,
                        new ManagementDtos.UpsertReviewerRequest(
                                "u-1", "教研组-王老师", false, true, true)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("必须也能审核"));
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("登记审核员：默认 canReview=true、enabled=true")
    void upsertReviewerDefaults() {
        when(port.upsertReviewer(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        ManagementDtos.ReviewerItem item = controller.upsertReviewer(
                TENANT, null, SCOPE_TENANT, null, null,
                new ManagementDtos.UpsertReviewerRequest("u-1", "教研组-王老师", null, null, null));

        assertEquals("u-1", item.principalId());
        assertTrue(item.canReview());
        assertFalse(item.canCoSign());
        assertTrue(item.enabled());
    }

    @Test
    @DisplayName("重处理 REINDEX_MISSING：复用 reindex，只补缺失向量")
    void reprocessReindexMissingDelegatesToReindex() {
        when(indexingPort.reindex(any(), anyInt()))
                .thenReturn(new CorpusIndexingPort.ReindexOutcome(7, 10, 120));

        ManagementDtos.ReprocessResponse resp = controller.reprocessSource(
                TENANT, null, SCOPE_TENANT, null, null, "s-1",
                new ManagementDtos.ReprocessRequest("REINDEX_MISSING"));

        assertTrue(resp.accepted());
        assertEquals("REINDEX_MISSING", resp.mode());
        /*
          ⚠️ 断言必须是消息里**真实存在**的子串。
              此处曾写成 contains("不影响")，而文案是「检索不【受】影响」——
              「受」字插在中间，故 "不影响" 并非其子串，断言恒为 false。
              是测试写错、不是代码写错；靠打印运行时码点才看清。
        */
        assertTrue(resp.message().contains("不受影响"), "消息应说明检索不受影响");
        verify(indexingPort).reindex(eq(TENANT), anyInt());
    }

    @Test
    @DisplayName("⛔ 重处理 RESPLIT：明确 501，绝不静默成功")
    void reprocessResplitIsNotImplemented() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reprocessSource(
                        TENANT, null, SCOPE_TENANT, null, null, "s-1",
                        new ManagementDtos.ReprocessRequest("RESPLIT")));

        // ⚠️ 静默成功会让人以为「重新切分过了」而实际什么都没发生
        assertEquals(HttpStatus.NOT_IMPLEMENTED, ex.getStatusCode());
        assertTrue(ex.getReason().contains("暂不可用"));
        // 不得偷偷调用 reindex（那是另一种模式的能力）
        verify(indexingPort, never()).reindex(any(), anyInt());
    }

    @Test
    @DisplayName("⛔ 重处理 REPLACE_FILE：明确 501 并给出替代路径")
    void reprocessReplaceFileIsNotImplemented() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reprocessSource(
                        TENANT, null, SCOPE_TENANT, null, null, "s-1",
                        new ManagementDtos.ReprocessRequest("REPLACE_FILE")));

        assertEquals(HttpStatus.NOT_IMPLEMENTED, ex.getStatusCode());
        // 必须给出可执行的替代方案，而不是只说「不行」
        assertTrue(ex.getReason().contains("新建一条来源"));
    }

    @Test
    @DisplayName("未知重处理模式返回 400 并列出合法值")
    void reprocessUnknownModeIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reprocessSource(
                        TENANT, null, SCOPE_TENANT, null, null, "s-1",
                        new ManagementDtos.ReprocessRequest("MAGIC")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("REINDEX_MISSING"));
    }

    @Test
    @DisplayName("重跑索引未带请求体时用默认上限")
    void reindexWithoutBodyUsesDefaultLimit() {
        when(indexingPort.reindex(any(), anyInt()))
                .thenReturn(new CorpusIndexingPort.ReindexOutcome(0, 0, 5));
        controller.reindex(TENANT, null, SCOPE_TENANT, null, null, null, null);
        verify(indexingPort).reindex(eq(TENANT), eq(500));
    }

    @Test
    @DisplayName("重跑索引的 ?limit= 查询参数生效（此前被静默忽略，回落成 500）")
    void reindexHonoursQueryParameterLimit() {
        when(indexingPort.reindex(any(), anyInt()))
                .thenReturn(new CorpusIndexingPort.ReindexOutcome(4, 4, 80));
        controller.reindex(TENANT, null, SCOPE_TENANT, null, null, null, "4");
        verify(indexingPort).reindex(eq(TENANT), eq(4));
    }

    @Test
    @DisplayName("重跑索引的查询参数 limit 同样受 1～2000 闸门约束")
    void reindexQueryParameterLimitOutOfRangeIsBadRequest() {
        ResponseStatusException tooBig = assertThrows(ResponseStatusException.class,
                () -> controller.reindex(TENANT, null, SCOPE_TENANT, null, null, null, "2001"));
        assertEquals(HttpStatus.BAD_REQUEST, tooBig.getStatusCode());
        ResponseStatusException tooSmall = assertThrows(ResponseStatusException.class,
                () -> controller.reindex(TENANT, null, SCOPE_TENANT, null, null, null, "0"));
        assertEquals(HttpStatus.BAD_REQUEST, tooSmall.getStatusCode());
        verifyNoInteractions(indexingPort);
    }

    @Test
    @DisplayName("重跑索引的查询参数 limit 非整数返回 400，而不是把脏值放进端口层")
    void reindexNonNumericQueryParameterLimitIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reindex(TENANT, null, SCOPE_TENANT, null, null, null, "abc"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(indexingPort);
    }

    @Test
    @DisplayName("重跑索引同时给请求体与查询参数时以请求体为准")
    void reindexPrefersBodyLimitOverQueryParameter() {
        when(indexingPort.reindex(any(), anyInt()))
                .thenReturn(new CorpusIndexingPort.ReindexOutcome(7, 7, 90));
        controller.reindex(TENANT, null, SCOPE_TENANT, null, null,
                new ManagementDtos.ReindexRequest(7), "99");
        verify(indexingPort).reindex(eq(TENANT), eq(7));
    }

    // ----- 平台全局档重索引（第二刀 2.5：换通道，不换口径）---------------------

    /** 平台档写所需的最小参数组：域=平台、票据=PLATFORM、不带租户头。 */
    private static final String PLATFORM_TICKET = SCOPE_PLATFORM;

    @Test
    @DisplayName("平台档重索引走全局通道，且不再按租户调用端口")
    void reindexInPlatformScopeRoutesToGlobalChannel() {
        when(indexingPort.reindexGlobalCorpus(anyInt()))
                .thenReturn(new CorpusIndexingPort.ReindexOutcome(9, 9, 120));

        ManagementDtos.ReindexResponse response = controller.reindex(
                null, null, SCOPE_PLATFORM, PLATFORM_CALLER, PLATFORM_TICKET,
                new ManagementDtos.ReindexRequest(64), null);

        assertEquals(9, response.indexedChunks());
        verify(indexingPort).reindexGlobalCorpus(eq(64));
        verify(indexingPort, never()).reindex(any(), anyInt());
    }

    @Test
    @DisplayName("⛔ 平台档重索引无治理票据 403，且一次连接都不发起")
    void reindexInPlatformScopeWithoutTicketIsForbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reindex(null, null, SCOPE_PLATFORM, PLATFORM_CALLER, null,
                        new ManagementDtos.ReindexRequest(64), null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("治理票据"), ex.getReason());
        verifyNoInteractions(indexingPort);
    }

    @Test
    @DisplayName("⛔ 平台档重索引拒绝租户域令牌（登录身份不够格）")
    void reindexInPlatformScopeRejectsTenantCaller() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reindex(null, null, SCOPE_PLATFORM, TENANT_CALLER,
                        PLATFORM_TICKET, null, "64"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(indexingPort);
    }

    @Test
    @DisplayName("⛔ 平台档重索引不收租户头（「索引全局桶的某个租户」不是任何一档的定义）")
    void reindexInPlatformScopeWithTenantHeaderIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reindex(TENANT, null, SCOPE_PLATFORM, PLATFORM_CALLER,
                        PLATFORM_TICKET, null, "64"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(indexingPort);
    }

    @Test
    @DisplayName("⛔ 盘点档（ALL）重索引返回 409：不存在「往全部租户写」这种动作")
    void reindexInAllScopeIsConflict() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reindex(null, null, SCOPE_ALL, PLATFORM_CALLER, SCOPE_ALL,
                        null, "64"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verifyNoInteractions(indexingPort);
    }

    @Test
    @DisplayName("租户档重索引不受平台通道影响：域/票据属性缺席时行为零变化")
    void reindexInTenantScopeIgnoresCallerDomainAndTicket() {
        when(indexingPort.reindex(any(), anyInt()))
                .thenReturn(new CorpusIndexingPort.ReindexOutcome(1, 1, 3));

        controller.reindex(TENANT, null, SCOPE_TENANT, PLATFORM_CALLER, PLATFORM_TICKET,
                null, "8");

        verify(indexingPort).reindex(eq(TENANT), eq(8));
        verify(indexingPort, never()).reindexGlobalCorpus(anyInt());
    }

    private ManagementDtos.DocumentVerificationRequest documentRequest(VerificationStatus target) {
        return new ManagementDtos.DocumentVerificationRequest(
                target, "ISBN:ref@2026-10-23", REVIEWER, VerificationMethod.ISBN,
                TrustTier.OFFICIAL_PUBLISHER, 365);
    }

    // ---------------------------------------------------------------------
    // 字典（Point 2：枚举下拉的数据源）
    // ---------------------------------------------------------------------

    private static final String SOURCE_TYPE = DictionaryPort.TYPE_SOURCE_TYPE;

    private static ManagementDtos.UpsertDictionaryEntryRequest entryRequest() {
        return new ManagementDtos.UpsertDictionaryEntryRequest(
                "TEXTBOOK", "教材", 0, true);
    }

    @Test
    @DisplayName("字典列表返回四类字典，且只有 SOURCE_TYPE 可编辑")
    void dictionaryListMarksEditable() {
        when(dictionaryPort.isEditable(SOURCE_TYPE)).thenReturn(true);
        when(dictionaryPort.isEditable(DictionaryPort.TYPE_TRUST_TIER)).thenReturn(false);
        when(dictionaryPort.isEditable(DictionaryPort.TYPE_VERIFICATION_METHOD)).thenReturn(false);
        when(dictionaryPort.isEditable(DictionaryPort.TYPE_LICENSE_STATUS)).thenReturn(false);
        when(dictionaryPort.entriesOf(any(), eq(SOURCE_TYPE)))
                .thenReturn(List.of(new DictionaryPort.DictEntry(
                        SOURCE_TYPE, "TEXTBOOK", "教材", 0, true)));

        List<ManagementDtos.DictionaryTypeDto> types =
                controller.listDictionaries(TENANT, null, null, PLATFORM_CALLER);

        assertEquals(4, types.size());
        ManagementDtos.DictionaryTypeDto source = types.stream()
                .filter(t -> SOURCE_TYPE.equals(t.type())).findFirst().orElseThrow();
        assertEquals(true, source.editable(), "SOURCE_TYPE 应可维护");
        assertEquals(1, source.entries().size());
        assertTrue(types.stream().filter(t -> !SOURCE_TYPE.equals(t.type())).allMatch(t -> !t.editable()),
                "派生字典应只读");
    }

    @Test
    @DisplayName("派生字典不允许前端写入 → 409（治理规则被违反）")
    void derivedDictionaryUpsertIsConflict() {
        when(dictionaryPort.isEditable(DictionaryPort.TYPE_TRUST_TIER)).thenReturn(false);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.upsertDictionaryEntry(TENANT, null, SCOPE_TENANT,
                        DictionaryPort.TYPE_TRUST_TIER, entryRequest()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    @DisplayName("派生字典不允许前端删除 → 409")
    void derivedDictionaryDeleteIsConflict() {
        when(dictionaryPort.isEditable(DictionaryPort.TYPE_TRUST_TIER)).thenReturn(false);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteDictionaryEntry(TENANT, null, SCOPE_TENANT,
                        DictionaryPort.TYPE_TRUST_TIER, "ISBN"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    @DisplayName("可编辑字典写入后返回写入的条目")
    void editableDictionaryUpsertReturnsEntry() {
        when(dictionaryPort.isEditable(SOURCE_TYPE)).thenReturn(true);
        ManagementDtos.DictionaryEntryDto dto = controller.upsertDictionaryEntry(
                TENANT, null, SCOPE_TENANT, SOURCE_TYPE, entryRequest());
        assertEquals("TEXTBOOK", dto.code());
        assertEquals("教材", dto.label());
        verify(dictionaryPort).upsertEntry(any(), argThat(e ->
                "TEXTBOOK".equals(e.code()) && "教材".equals(e.label())));
    }

    @Test
    @DisplayName("字典端点缺租户头返回 400")
    void dictionaryWithoutTenantIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listDictionaries(null, null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("空字典类型返回 400（防止 SQL 误删/误查全表）")
    void blankDictionaryTypeIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listDictionaryEntries(TENANT, null, null, PLATFORM_CALLER, "  "));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    // ---------------------------------------------------------------------
    // 作用域与平台域闸门（RAG-D11 第四刀 · 第一刀 1.2）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("非法作用域头返回 400（不静默回落 TENANT）")
    void malformedScopeHeaderIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listSources(null, null, "PLATFORME", PLATFORM_CALLER,
                        null, null, null, false, 50, 0));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("PLATFORME"),
                "400 消息要带回原始错值，否则调用方无从定位");
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("平台全局档拒绝租户域令牌（403，不是 400）")
    void platformScopeRejectsTenantCaller() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listSources(null, null, SCOPE_PLATFORM, TENANT_CALLER,
                        null, null, null, false, 50, 0));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode(),
                "身份不够格是授权问题（403），不是入参形态问题（400）");
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("平台全局档拒绝未验证域（鉴权逃生门不够格）")
    void platformScopeRejectsUnverifiedCaller() {
        for (String domain : java.util.Arrays.asList(UNVERIFIED_CALLER, null, "  ", "PLATFORM-guess")) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.listSources(null, null, SCOPE_PLATFORM, domain,
                            null, null, null, false, 50, 0),
                    "域值「" + domain + "」不该被当作平台域放行");
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("平台全局档不收租户头，下传哨兵查询批次且不建立会话租户")
    void platformScopeQueriesSentinelWithoutSessionTenant() {
        when(port.listSources(any(), any())).thenReturn(List.of());
        controller.listSources(null, null, SCOPE_PLATFORM, PLATFORM_CALLER,
                null, null, null, false, 50, 0);

        ArgumentCaptor<RagManagementContext> captor =
                ArgumentCaptor.forClass(RagManagementContext.class);
        verify(port).listSources(captor.capture(), any());
        RagManagementContext ctx = captor.getValue();
        assertEquals(RagCorpusScope.PLATFORM, ctx.scope());
        assertEquals(null, ctx.tenant(), "平台档不携带租户上下文");
        assertEquals(RagScope.GLOBAL_TENANT_ID, ctx.queryTenantId(),
                "读侧谓词绑定全局哨兵——这就是「这一批行是平台全局语料」的声明");
        assertEquals(null, ctx.sessionTenantId(),
                "会话租户必须保持未设置：把哨兵写进 app.tenant_id 会让 rag_current_tenant() 抛 42501");
    }

    @Test
    @DisplayName("平台全局档同时提供租户头返回 400（两种身份互斥）")
    void platformScopeWithTenantHeaderIsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listSources(TENANT, null, SCOPE_PLATFORM, PLATFORM_CALLER,
                        null, null, null, false, 50, 0));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("跨租户盘点档返回 501（需运维数据源，属第三刀）")
    void allScopeIsNotImplemented() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listSources(null, null, SCOPE_ALL, PLATFORM_CALLER,
                        null, null, null, false, 50, 0));
        assertEquals(HttpStatus.NOT_IMPLEMENTED, ex.getStatusCode());
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("⛔ 盘点档（ALL）写请求返回 409：没有「往全部租户写」这种动作")
    void allScopeWriteIsConflict() {
        ManagementDtos.RegisterSourceRequest request = new ManagementDtos.RegisterSourceRequest(
                "教材", "人教版八年级上册数学", "人民教育出版社",
                "9787100000000", null, "obj://book.pdf", "b".repeat(64), REVIEWER);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.registerSource(TENANT, null, SCOPE_ALL,
                        PLATFORM_CALLER, "ALL", request));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode(),
                "即使票据授权了 ALL，写端点也不该受理——那是语义错误，不是缺授权");
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("⛔ 平台档写：租户域令牌 403（域都不够格，与有没有票据无关）")
    void platformWriteWithTenantCallerIsForbidden() {
        ManagementDtos.RegisterSourceRequest request = new ManagementDtos.RegisterSourceRequest(
                "教材", "人教版八年级上册数学", "人民教育出版社",
                "9787100000000", null, "obj://book.pdf", "b".repeat(64), REVIEWER);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.registerSource(null, null, SCOPE_PLATFORM,
                        TENANT_CALLER, SCOPE_PLATFORM, request));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("⛔ 平台档写：平台域<b>登录</b>令牌（无票据声明）403")
    void platformWriteWithoutTicketIsForbidden() {
        ManagementDtos.RegisterSourceRequest request = new ManagementDtos.RegisterSourceRequest(
                "教材", "人教版八年级上册数学", "人民教育出版社",
                "9787100000000", null, "obj://book.pdf", "b".repeat(64), REVIEWER);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.registerSource(null, null, SCOPE_PLATFORM,
                        PLATFORM_CALLER, null, request));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("治理票据"),
                "403 必须说清缺的是票据而不是身份：" + ex.getReason());
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("⛔ 平台档写：票据档位不符（拿 TENANT 票写全局）403")
    void platformWriteWithMismatchedTicketIsForbidden() {
        ManagementDtos.RegisterSourceRequest request = new ManagementDtos.RegisterSourceRequest(
                "教材", "人教版八年级上册数学", "人民教育出版社",
                "9787100000000", null, "obj://book.pdf", "b".repeat(64), REVIEWER);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.registerSource(null, null, SCOPE_PLATFORM,
                        PLATFORM_CALLER, SCOPE_TENANT, request));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("⛔ 平台档写同时带租户头返回 400（两种身份互斥，写侧同读侧纪律）")
    void platformWriteWithTenantHeaderIsBadRequest() {
        ManagementDtos.RegisterSourceRequest request = new ManagementDtos.RegisterSourceRequest(
                "教材", "人教版八年级上册数学", "人民教育出版社",
                "9787100000000", null, "obj://book.pdf", "b".repeat(64), REVIEWER);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.registerSource(TENANT, null, SCOPE_PLATFORM,
                        PLATFORM_CALLER, SCOPE_PLATFORM, request));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("✅ 平台档写：票据齐备时下传 PLATFORM 作用域且不带会话租户")
    void platformWriteWithTicketReachesPort() {
        when(port.registerSource(any(), any(), eq(REVIEWER))).thenReturn(sampleSource());
        ManagementDtos.RegisterSourceRequest request = new ManagementDtos.RegisterSourceRequest(
                "教材", "平台公共教材索引", "平台", 
                "9787100000000", null, "obj://global.pdf", "b".repeat(64), REVIEWER);
        controller.registerSource(null, null, SCOPE_PLATFORM,
                PLATFORM_CALLER, SCOPE_PLATFORM, request);

        ArgumentCaptor<RagManagementContext> captor =
                ArgumentCaptor.forClass(RagManagementContext.class);
        verify(port).registerSource(captor.capture(), any(), eq(REVIEWER));
        RagManagementContext ctx = captor.getValue();
        assertEquals(RagCorpusScope.PLATFORM, ctx.scope());
        assertNull(ctx.sessionTenantId(),
                "平台档绝不能带会话租户：带上了就是把全局语料写进某个租户的桶");
    }

    @Test
    @DisplayName("租户档即使带平台域令牌仍要求租户头（缺租户 400 的既有纪律不变）")
    void tenantScopeStillRequiresTenantHeader() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listSources(null, null, SCOPE_TENANT, PLATFORM_CALLER,
                        null, null, null, false, 50, 0));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode(),
                "平台身份不等于「知道了要查哪个租户」，缺租户头不能静默变成全局查询");
    }

    @Test
    @DisplayName("租户档的会话租户与查询批次同为该租户（与本刀之前一致）")
    void tenantScopeBindsTheSameTenantForSessionAndQuery() {
        when(port.listSources(any(), any())).thenReturn(List.of());
        controller.listSources(TENANT, null, SCOPE_TENANT, PLATFORM_CALLER,
                null, null, null, false, 50, 0);

        ArgumentCaptor<RagManagementContext> captor =
                ArgumentCaptor.forClass(RagManagementContext.class);
        verify(port).listSources(captor.capture(), any());
        RagManagementContext ctx = captor.getValue();
        assertEquals(RagCorpusScope.TENANT, ctx.scope());
        assertEquals(TENANT, ctx.sessionTenantId());
        assertEquals(TENANT, ctx.queryTenantId());
        assertEquals(ctx.requireTenant().tenantId(), TENANT, "租户档的写入口照常可用");
    }

    @Test
    @DisplayName("字典读端点走同一道闸门（平台档可看全局字典）")
    void dictionaryReadSharesTheScopeGate() {
        when(dictionaryPort.entriesOf(any(), any())).thenReturn(List.of());
        controller.listDictionaryEntries(null, null, SCOPE_PLATFORM, PLATFORM_CALLER,
                DictionaryPort.TYPE_SOURCE_TYPE);
        ArgumentCaptor<RagManagementContext> captor =
                ArgumentCaptor.forClass(RagManagementContext.class);
        verify(dictionaryPort).entriesOf(captor.capture(), eq(DictionaryPort.TYPE_SOURCE_TYPE));
        assertEquals(RagScope.GLOBAL_TENANT_ID, captor.getValue().queryTenantId());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listDictionaryEntries(null, null, SCOPE_PLATFORM, TENANT_CALLER,
                        DictionaryPort.TYPE_SOURCE_TYPE));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("字典写端点同样拒绝非租户作用域")
    void dictionaryWriteRejectsNonTenantScope() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.upsertDictionaryEntry(TENANT, null, SCOPE_PLATFORM,
                        DictionaryPort.TYPE_SOURCE_TYPE, entryRequest()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verifyNoInteractions(dictionaryPort);
    }

    // ---- 载体上传端点（真实文件录入，而非手填 objectRef）----

    @Test
    @DisplayName("上传合法 PDF：服务端重算哈希、落桶、登记载体，返回 201")
    void importDocumentValidPdfReturnsCreated() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "math-book.pdf", "application/pdf", PDF_MAGIC);
        when(objectStore.sha256(any())).thenReturn("d".repeat(64));
        when(objectStore.store(any(), any())).thenReturn("math-book.pdf/uuid.pdf");
        CorpusDocument saved = sampleDocument(VerificationStatus.PENDING, false);
        when(port.importDocument(any(), eq(SOURCE_ID), any(),
                eq(REVIEWER))).thenReturn(saved);

        ResponseEntity<ManagementDtos.DocumentResponse> resp = controller.importDocument(
                TENANT, null, SCOPE_TENANT, SOURCE_ID, TENANT_CALLER, null, REVIEWER,
                file, null, null);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertNotNull(resp.getBody());
        verify(objectStore).sha256(any());
        verify(objectStore).store(any(), any());
        ArgumentCaptor<CorpusManagementPort.DocumentImportCommand> cmdCaptor =
                ArgumentCaptor.forClass(CorpusManagementPort.DocumentImportCommand.class);
        verify(port).importDocument(any(), eq(SOURCE_ID), cmdCaptor.capture(), eq(REVIEWER));
        CorpusManagementPort.DocumentImportCommand cmd = cmdCaptor.getValue();
        assertEquals("PDF", cmd.carrier());
        assertEquals("math-book.pdf", cmd.title());
        assertEquals("d".repeat(64), cmd.contentHash());
        assertEquals("math-book.pdf/uuid.pdf", cmd.objectRef());
    }

    @Test
    @DisplayName("空文件上传返回 400（不落桶、不写库）")
    void importDocumentEmptyFileIsBadRequest() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.importDocument(TENANT, null, SCOPE_TENANT, SOURCE_ID,
                        TENANT_CALLER, null, REVIEWER, file, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(objectStore, port);
    }

    @Test
    @DisplayName("来源不存在返回 404")
    void importDocumentMissingSourceIsNotFound() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "math-book.pdf", "application/pdf", PDF_MAGIC);
        when(objectStore.sha256(any())).thenReturn("e".repeat(64));
        when(objectStore.store(any(), any())).thenReturn("math-book.pdf/uuid.pdf");
        when(port.importDocument(any(), eq(SOURCE_ID), any(), any())).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.importDocument(TENANT, null, SCOPE_TENANT, SOURCE_ID,
                        TENANT_CALLER, null, REVIEWER, file, null, null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("无法从后缀推断载体且未显式给 carrier 时返回 400")
    void importDocumentUnrecognizedExtensionWithoutCarrierIsBadRequest() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.xyz", "application/octet-stream", "bytes".getBytes());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.importDocument(TENANT, null, SCOPE_TENANT, SOURCE_ID,
                        TENANT_CALLER, null, REVIEWER, file, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(objectStore, port);
    }
}
