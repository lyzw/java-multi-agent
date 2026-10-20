package cn.studykid.multiagent.api.governance;

import java.time.Instant;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.AuditPort;
import cn.studykid.multiagent.ports.persistence.AuditQueryPort;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 审计查询端点（P4-D13 / G10 · FE-01 §P10 · P3-D07）。
 *
 * <p>⚠️ <b>只读</b>：{@code audit_record} 有 {@code forbid_audit_mutation} 触发器。
 * 本端点不提供任何删除/修改入口——「可追溯」的另一半是「不可篡改」。
 *
 * <p>⚠️ <b>查询审计这件事本身也留痕</b>：走 {@code recordAccess}（fail-open），
 * 避免把审计查询变成自递归的写入风暴。
 */
@RestController
public class AuditQueryController {

    private final AuditQueryPort audits;
    private final AuditPort auditPort;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public AuditQueryController(AuditQueryPort audits,
                                AuditPort auditPort,
                                AuthorizationService authorization,
                                RateLimitPort rateLimiter,
                                CurrentPrincipal currentPrincipal) {
        this.audits = audits;
        this.auditPort = auditPort;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/api/audit")
    public ResponseEntity<GovernanceSupport.PageResponse<AuditEntryResponse>> queryAudit(
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable String action,
            @RequestParam(required = false) @Nullable String resourceType,
            @RequestParam(required = false) @Nullable String resourceId,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to) {

        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AUDIT_READ, null);

        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            return GovernanceSupport.tooManyRequests();
        }

        int pageSize = GovernanceSupport.limit(limit);
        Instant fromAt = GovernanceSupport.instant(from, "from");
        Instant toAt = GovernanceSupport.instant(to, "to");
        GovernanceSupport.checkRange(fromAt, toAt);

        var page = audits.query(context.tenantId(), action, resourceType, resourceId,
                fromAt, toAt, cursor, pageSize);

        // ⚠️ fail-open：审计查询失败不应让查询本身失败（否则「查不了审计」本身就是事件）
        auditPort.recordAccess(context.tenantId(), currentPrincipal.principalId(),
                AuditPort.Actions.AUDIT_READ, "audit_record", null,
                Map.of("count", page.items().size()));

        return ResponseEntity.ok(GovernanceSupport.PageResponse.of(
                page.items().stream().map(AuditEntryResponse::of).toList(),
                page.nextCursor()));
    }

    /**
     * 审计条目。
     *
     * <p>⛔ <b>不返回 {@code tenantId}</b>。
     */
    public record AuditEntryResponse(
            @NonNull String auditId,
            @NonNull String actorId,
            @NonNull String action,
            @NonNull String resourceType,
            @Nullable String resourceId,
            @NonNull Instant createdAt,
            @Nullable String correlationId,
            @NonNull Map<String, Object> detail
    ) {
        static AuditEntryResponse of(AuditQueryPort.AuditEntry entry) {
            return new AuditEntryResponse(entry.auditId(), entry.actorId(), entry.action(),
                    entry.resourceType(), entry.resourceId(), entry.createdAt(),
                    entry.correlationId(), entry.detail());
        }
    }
}