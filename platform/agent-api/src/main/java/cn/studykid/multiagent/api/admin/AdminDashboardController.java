package cn.studykid.multiagent.api.admin;

import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.monitoring.DashboardViewService;
import cn.studykid.multiagent.application.monitoring.DashboardViewService.DashboardSummary;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 管理控制台仪表盘概览（管理面 · M1 概览聚合）。
 *
 * <p>⚠️ <b>只读</b>：与 {@link AdminMonitoringController} 同一哲学 —— 聚合观测，不提供任何写入口。
 *
 * <p>⚠️ <b>鉴权口径</b>：租户作用域（按令牌租户），{@code AGENT_MANAGE} + 与治理查询共用限流桶。
 * 仪表盘是导航入口，不是决策依据，故<b>不 fail-closed</b>：个别计数源不可用只让该项记 0，不整页 503。
 */
@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    private final DashboardViewService dashboard;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public AdminDashboardController(@NonNull DashboardViewService dashboard,
                                    @NonNull AuthorizationService authorization,
                                    @NonNull RateLimitPort rateLimiter,
                                    @NonNull CurrentPrincipal currentPrincipal) {
        this.dashboard = dashboard;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    public ResponseEntity<DashboardSummary> summary() {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁");
        }
        return ResponseEntity.ok(dashboard.summary(context.tenantId()));
    }
}
