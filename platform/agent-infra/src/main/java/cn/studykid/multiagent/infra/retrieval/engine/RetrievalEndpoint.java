package cn.studykid.multiagent.infra.retrieval.engine;

import java.time.Instant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * G3 检索端点（P3-D10）。
 *
 * <p>⚠️ 诚实边界：本端点为草稿接线，尚未接入真实认证上下文。
 * 生产态 {@link RetrievalService} 的 expectedTenantId 必须来自可信 JWT / 会话，
 * 不得直接使用请求体 tenant_id 作授权依据（见 TenantContext 不可妥协规则）。
 */
@RestController
@RequestMapping("/api/v3/retrieval")
public final class RetrievalEndpoint {

    private final RetrievalService retrievalService;

    public RetrievalEndpoint(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @GetMapping("/workspace")
    public ResponseEntity<RetrievalResponse> workspace(@RequestBody RetrievalRequest request) {
        if (request == null || request.tenantId() == null || request.tenantId().isBlank()) {
            return ResponseEntity.badRequest().body(RetrievalResponse.error("tenant_id 必填"));
        }
        RetrievalService.ContextRecord record = new RetrievalService.ContextRecord(
                request.workspaceId(), request.tenantId(), request.lastUpdated(), request.terms());
        RetrievalService.RetrievalResult result = retrievalService.retrieve(record);
        if (!result.passed()) {
            return ResponseEntity.ok(RetrievalResponse.failed(result.failureReason()));
        }
        return ResponseEntity.ok(RetrievalResponse.success(result.hybridScore()));
    }

    @PostMapping("/vector")
    public ResponseEntity<RetrievalResponse> vector(@RequestBody RetrievalRequest request) {
        // 草稿：向量检索复用同一 Authority Triple Gate（待接真实向量库）。
        return workspace(request);
    }

    /** 检索请求。 */
    public record RetrievalRequest(
            String tenantId,
            String workspaceId,
            Instant lastUpdated,
            String terms) {
    }

    /** 检索响应。 */
    public record RetrievalResponse(
            boolean passed,
            double score,
            String failureReason,
            String error) {

        static RetrievalResponse success(double score) {
            return new RetrievalResponse(true, score, null, null);
        }

        static RetrievalResponse failed(String reason) {
            return new RetrievalResponse(false, 0.0, reason, null);
        }

        static RetrievalResponse error(String error) {
            return new RetrievalResponse(false, 0.0, null, error);
        }
    }
}
