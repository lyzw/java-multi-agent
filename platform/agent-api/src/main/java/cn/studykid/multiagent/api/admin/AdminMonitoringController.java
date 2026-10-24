package cn.studykid.multiagent.api.admin;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.governance.GovernanceSupport;
import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.monitoring.MonitoringViewService;
import cn.studykid.multiagent.core.monitoring.DenyAlert;
import cn.studykid.multiagent.core.monitoring.MonitoringHealth;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 运行监控只读端点（管理面 P4 运行监控）。
 *
 * <p>⚠️ <b>为什么只有 GET</b>：与 {@code AdminDependencyController} 同一哲学 ——
 * 监控是<b>观测</b>通道，不是变更通道。运维看到「有严重拒绝」后的正确动作是
 * 去查审计、查配置、查发布记录，而不是「在监控页上点一下把它消掉」。
 * 提供任何写操作都会把「观测」异化为「处置」，且处置的正确入口在别处。
 *
 * <p>⚠️ <b>鉴权口径与能力目录一致</b>（{@code AGENT_MANAGE} + 同一限流桶）：
 * 拒绝告警包含 {@code principalId} 与 {@code toolId}，
 * 是「谁在撞权限边界」的直接线索，与 {@code /api/agents}、{@code /api/cap/catalog}
 * 同信任级别。
 *
 * <p>⚠️ <b>租户作用域</b>：全部按<b>调用方租户</b>计算，
 * 租户来自已验签令牌（{@code CurrentPrincipal}），<b>不接受请求参数指定</b>。
 */
@RestController
@RequestMapping("/api/admin/monitoring")
public class AdminMonitoringController {

    private final MonitoringViewService monitoring;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public AdminMonitoringController(@NonNull MonitoringViewService monitoring,
                                     @NonNull AuthorizationService authorization,
                                     @NonNull RateLimitPort rateLimiter,
                                     @NonNull CurrentPrincipal currentPrincipal) {
        this.monitoring = monitoring;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 租户运行健康快照（聚合视图）。
     *
     * @param windowHours 监控窗口（小时），默认 24，上限 168
     * @throws ResponseStatusException 400 窗口非法；403 无权；429 限流；503 数据源不可用
     */
    @GetMapping("/health")
    public ResponseEntity<MonitoringHealth> health(
            @RequestParam(required = false) @Nullable Integer windowHours) {
        TenantContext context = authorize();
        int window = windowHours == null
                ? MonitoringViewService.DEFAULT_WINDOW_HOURS
                : windowHours;
        return ResponseEntity.ok(monitoring.health(context.tenantId(), window));
    }

    /**
     * 近期拒绝告警列表（下钻视图）。
     *
     * @param windowHours 监控窗口（小时），默认 24，上限 168
     * @param limit       最大条数，默认 50，上限 200
     */
    @GetMapping("/denies")
    public ResponseEntity<List<DenyAlert>> denies(
            @RequestParam(required = false) @Nullable Integer windowHours,
            @RequestParam(required = false) @Nullable Integer limit) {
        TenantContext context = authorize();
        int window = windowHours == null
                ? MonitoringViewService.DEFAULT_WINDOW_HOURS
                : windowHours;
        int pageSize = limit == null ? 50 : limit;
        return ResponseEntity.ok(monitoring.denies(context.tenantId(), window, pageSize));
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /**
     * 鉴权 + 限流（与 {@code AdminDependencyController#authorize} 同口径）。
     *
     * <p>⚠️ <b>限流桶与治理查询共用</b>：监控查询虽只读，但会扫决策日志，
     * 是重查询；与 {@code GOVERNANCE_QUERY} 共桶可避免有人用监控端点
     * 绕开治理查询的限流。
     */
    private @NonNull TenantContext authorize() {
        TenantContext context = currentPrincipal.context();
        authorization.requireAuthorized(context,
                AuthorizationService.Actions.AGENT_MANAGE, null);
        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁");
        }
        return context;
    }
}
