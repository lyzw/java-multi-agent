package cn.studykid.multiagent.api.governance;

import java.time.Instant;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.ApprovalQueryPort;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 审批台端点（P4-D13 / G8 · FE-01 §P8 · FR-014）。
 *
 * <p>⚠️ <b>过期审批必须拒绝，不得静默通过</b>（FE-01 §P8 明确要求）。
 * 审批的意义在于「人对特定时刻的特定操作投信任」，
 * 过了有效期它就不是同一个决定了。
 */
@RestController
public class ApprovalController {

    private static final Set<String> APPROVAL_STATES = Set.of(
            "PENDING", "APPROVED", "REJECTED", "EXPIRED", "CONSUMED");

    private final ApprovalQueryPort approvals;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public ApprovalController(ApprovalQueryPort approvals,
                              AuthorizationService authorization,
                              RateLimitPort rateLimiter,
                              CurrentPrincipal currentPrincipal) {
        this.approvals = approvals;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/api/approvals")
    public ResponseEntity<GovernanceSupport.PageResponse<ApprovalItemResponse>> listApprovals(
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable String state) {

        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.APPROVAL_CONSUME, null);

        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            return GovernanceSupport.tooManyRequests();
        }

        int pageSize = GovernanceSupport.limit(limit);
        GovernanceSupport.requireEnum(state, APPROVAL_STATES, "state");

        var page = approvals.list(context.tenantId(), state, cursor, pageSize);
        return ResponseEntity.ok(GovernanceSupport.PageResponse.of(
                page.items().stream().map(ApprovalItemResponse::of).toList(),
                page.nextCursor()));
    }

    /**
     * 记录审批决定。
     *
     * <p>⚠️ <b>审批人标识只来自令牌</b>（{@code currentPrincipal.principalId()}），
     * 绝不从请求体取——否则任何人都能冒充审批人留痕。
     */
    @PostMapping("/api/approvals/{approvalId}/decision")
    public ResponseEntity<DecisionResponse> decide(
            @PathVariable String approvalId,
            @RequestBody DecisionRequest request) {

        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.APPROVAL_CONSUME, approvalId);

        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_DECIDE)) {
            return GovernanceSupport.tooManyRequests();
        }

        try {
            var decision = approvals.decide(
                    context.tenantId(), approvalId, request.approve(),
                    currentPrincipal.principalId(), request.reason());
            return ResponseEntity.ok(new DecisionResponse(
                    decision.approvalId(), decision.newState()));
        } catch (ApprovalQueryPort.ApprovalNotFoundException e) {
            // ⚠️ 404 **不区分**「不存在」与「无权限」——区分即构成存在性探测通道
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在");
        } catch (ApprovalQueryPort.ApprovalDecisionException e) {
            // ⚠️ 过期 / 已被决定 → 409 Conflict：
            //    资源当前状态与请求冲突（不是客户端格式错，也不是服务端故障）
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * 审批决定请求。
     *
     * @param decision {@code APPROVE} 或 {@code DENY}
     * @param reason   拒绝理由；<b>{@code DENY} 时必填</b>
     */
    public record DecisionRequest(@Nullable String decision, @Nullable String reason) {

        public boolean approve() {
            // ⚠️ 缺省不是「批准」而是「拒绝」：字段缺失时 fail-closed。
            //    默认 true 会让一个空 body {} 变成一次批准。
            return "APPROVE".equalsIgnoreCase(decision == null ? "" : decision.trim());
        }

        public @NonNull String normalizedDecision() {
            return approve() ? "APPROVE" : "DENY";
        }
    }

    public record DecisionResponse(@NonNull String approvalId, @NonNull String state) {
    }

    /**
     * 审批条目。
     *
     * @param argumentDigest <b>参数摘要</b>，不是参数原文（FR-014 要求看摘要）
     * @param expired        <b>服务端计算</b>的过期标志
     */
    public record ApprovalItemResponse(
            @NonNull String approvalId,
            @NonNull String operationId,
            @NonNull String runId,
            @NonNull String state,
            @NonNull String requirement,
            @NonNull String argumentDigest,
            @NonNull String targetResource,
            @NonNull String approverScope,
            @NonNull Instant expiresAt,
            @Nullable String approverId,
            @Nullable Instant decidedAt,
            @NonNull Instant createdAt,
            boolean expired
    ) {
        static ApprovalItemResponse of(ApprovalQueryPort.ApprovalItem item) {
            return new ApprovalItemResponse(item.approvalId(), item.operationId(), item.runId(),
                    item.state(), item.requirement(), item.argumentDigest(),
                    item.targetResource(), item.approverScope(), item.expiresAt(),
                    item.approverId(), item.decidedAt(), item.createdAt(), item.expired());
        }
    }
}