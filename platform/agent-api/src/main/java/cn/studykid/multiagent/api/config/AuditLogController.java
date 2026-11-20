package cn.studykid.multiagent.api.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.cap.AuditLogService;
import cn.studykid.multiagent.core.cap.AuditLog;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 业务审计日志端点（只读，/api/cap/audit-logs）。
 *
 * <p>⚠️ 只读：不提供写接口；写入由草稿服务 / 发布工作流 / 生命周期服务内部完成。
 */
@RestController
@RequestMapping("/api/cap/audit-logs")
public class AuditLogController {

    private final AuditLogService audit;
    private final AuthorizationService authorization;
    private final CurrentPrincipal currentPrincipal;

    public AuditLogController(@NonNull AuditLogService audit,
                               @NonNull AuthorizationService authorization,
                               @NonNull CurrentPrincipal currentPrincipal) {
        this.audit = audit;
        this.authorization = authorization;
        this.currentPrincipal = currentPrincipal;
    }

    /** 通用查询（按事件类型 / 操作人 / 时间范围 / 资源，游标分页）。 */
    @GetMapping
    public ResponseEntity<GovernanceSupport.PageResponse<AuditLogResponse>> query(
            @Nullable @RequestParam String resourceKind,
            @Nullable @RequestParam String resourceId,
            @Nullable @RequestParam String eventType,
            @Nullable @RequestParam String operatorId,
            @Nullable @RequestParam String fromTime,
            @Nullable @RequestParam String toTime,
            @Nullable @RequestParam String cursor,
            @Nullable @RequestParam Integer limit) {
        TenantContext ctx = currentPrincipal.context();
        authorization.requireAuthorized(ctx, AuthorizationService.Actions.CONFIG_PUBLISH, null);
        Page<AuditLog> page;
        // 有资源限定 → list；否则通用 query
        if ((resourceKind != null && !resourceKind.isBlank())
                || (resourceId != null && !resourceId.isBlank())) {
            page = audit.list("TENANT", ctx.tenantId(), resourceKind, resourceId,
                    eventType, cursor, GovernanceSupport.limit(limit));
        } else {
            page = audit.query("TENANT", ctx.tenantId(), eventType, operatorId,
                    fromTime, toTime, cursor, GovernanceSupport.limit(limit));
        }
        return ResponseEntity.ok(GovernanceSupport.PageResponse.of(
                page.items().stream().map(AuditLogResponse::of).toList(), page.nextCursor()));
    }

    public record AuditLogResponse(
            String auditId, String scope, String tenantId,
            String resourceKind, String resourceId, String resourceName,
            String eventType, Integer fromVersion, Integer toVersion,
            String fromState, String toState,
            String beforeConfig, String afterConfig, String diffSummary,
            String operatorId, String reason, String createdAt
    ) {
        public static AuditLogResponse of(@NonNull AuditLog a) {
            return new AuditLogResponse(
                    a.auditId().toString(), a.scope(), a.tenantId(),
                    a.resourceKind(), a.resourceId(), a.resourceName(),
                    a.eventType(), a.fromVersion(), a.toVersion(),
                    a.fromState(), a.toState(),
                    a.beforeConfig(), a.afterConfig(), a.diffSummary(),
                    a.operatorId(), a.reason(), a.createdAt().toString()
            );
        }
    }
}
