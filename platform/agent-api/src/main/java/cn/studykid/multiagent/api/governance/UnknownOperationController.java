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
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.persistence.UnknownOperationPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * UNKNOWN 处置台端点（P4-D13 / G9 · FE-01 §P9）。
 *
 * <p>⛔⛔ <b>本端点不存在「重试」路径</b>（反证 A-5 锁定）：
 * 三个 {@code disposition} 取值全部是<b>终态归档</b>。
 * UNKNOWN 的含义是「副作用是否已经发生<b>不知道</b>」，
 * 此刻重放就是在「可能已经改了学生数据」之上再改一次——Sev-0。
 * FE-01 §P9 原文：「必须显式区分 UNKNOWN / RECONCILIATION_REQUIRED，
 * 禁止提供一键重试（盲目重放 = Sev-0）」。
 */
@RestController
public class UnknownOperationController {

    private static final Set<String> OPERATION_STATES = Set.of(
            "PENDING", "IN_FLIGHT", "SUCCEEDED", "FAILED", "UNKNOWN",
            "RECONCILED_SUCCEEDED", "RECONCILED_ABSENT", "MANUAL_DISPOSED");

    private final UnknownOperationPort operations;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public UnknownOperationController(UnknownOperationPort operations,
                                     AuthorizationService authorization,
                                     RateLimitPort rateLimiter,
                                     CurrentPrincipal currentPrincipal) {
        this.operations = operations;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/api/operations")
    public ResponseEntity<GovernanceSupport.PageResponse<OperationItemResponse>> listOperations(
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable String state) {

        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.UNKNOWN_DISPOSE, null);

        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            return GovernanceSupport.tooManyRequests();
        }

        int pageSize = GovernanceSupport.limit(limit);
        GovernanceSupport.requireEnum(state, OPERATION_STATES, "state");

        var page = operations.list(context.tenantId(), state, cursor, pageSize);
        return ResponseEntity.ok(GovernanceSupport.PageResponse.of(
                page.items().stream().map(OperationItemResponse::of).toList(),
                page.nextCursor()));
    }

    /**
     * 人工处置 UNKNOWN 操作。
     *
     * <p>⚠️ <b>处置人标识只来自令牌</b>，绝不从请求体取。
     */
    @PostMapping("/api/operations/{operationId}/disposition")
    public ResponseEntity<DispositionResponse> dispose(
            @PathVariable String operationId,
            @RequestBody DispositionRequest request) {

        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.UNKNOWN_DISPOSE, operationId);

        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_DECIDE)) {
            return GovernanceSupport.tooManyRequests();
        }

        // ⚠️ 非法取值 → 400（全局处理器），且**枚举里根本没有 RETRY**：
        //    想「重试」的调用方会在这里拿到 400，而不是悄悄重放。
        UnknownOperationPort.Disposition disposition;
        try {
            disposition = UnknownOperationPort.Disposition.valueOf(request.disposition());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "disposition 取值非法，允许值: " + java.util.Arrays.toString(
                            UnknownOperationPort.Disposition.values()));
        }

        try {
            var result = operations.dispose(
                    context.tenantId(), operationId, disposition,
                    currentPrincipal.principalId(), request.note());
            return ResponseEntity.ok(new DispositionResponse(
                    result.operationId(), result.newState(),
                    result.disposedBy(), result.disposedAt()));
        } catch (UnknownOperationPort.OperationNotFoundException e) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在");
        } catch (UnknownOperationPort.NotInUnknownStateException e) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * 处置请求。
     *
     * @param disposition 三取值之一；<b>无 RETRY</b>
     * @param note        处置说明（可选）
     */
    public record DispositionRequest(@Nullable String disposition, @Nullable String note) {
    }

    public record DispositionResponse(
            @NonNull String operationId,
            @NonNull String state,
            @NonNull String disposedBy,
            @NonNull Instant disposedAt
    ) {
    }

    /**
     * 操作条目。
     *
     * ⛔ <b>不含 {@code toolArguments} / {@code resultPayload}</b>——
     * 前者是学生提交内容，后者含内部细节。
     */
    public record OperationItemResponse(
            @NonNull String operationId,
            @NonNull String runId,
            @NonNull String toolName,
            @NonNull String riskLevel,
            @NonNull String state,
            @NonNull Instant createdAt,
            @NonNull Instant updatedAt,
            @Nullable String reconciliationSource,
            boolean manuallyDisposed
    ) {
        static OperationItemResponse of(UnknownOperationPort.OperationItem item) {
            return new OperationItemResponse(item.operationId(), item.runId(), item.toolName(),
                    item.riskLevel(), item.state(), item.createdAt(), item.updatedAt(),
                    item.reconciliationSource(), item.manuallyDisposed());
        }
    }
}