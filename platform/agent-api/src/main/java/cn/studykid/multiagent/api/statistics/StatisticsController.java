package cn.studykid.multiagent.api.statistics;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.security.AuthorizationService;
import cn.studykid.multiagent.ports.statistics.StatisticsPort;
import cn.studykid.multiagent.ports.statistics.StatisticsPort.DailyStatistics;

/**
 * 工作台统计端点（报表中心后端 · FE 报表中心页）。
 *
 * <p><b>⚠️ 租户只来自已验签令牌</b>，与 {@code RunListController} 同一铁律——
 * 绝不从查询参数取 {@code tenantId}。
 *
 * <p><b>⚠️ 执行时授权（红线 9）</b>：复用 {@code RUN_READ} 动作，
 * 与「检索记录」同权——任何已登录用户可见本租户聚合（不暴露个体）。
 *
 * <p><b>⚠️ 不做速率限制</b>：报表为低频只读，且聚合查询已显式带租户条件，
 * 不必挤占治理桶配额。
 */
@RestController
public class StatisticsController {

    private final StatisticsPort statistics;
    private final AuthorizationService authorization;
    private final CurrentPrincipal currentPrincipal;

    public StatisticsController(StatisticsPort statistics,
                               AuthorizationService authorization,
                               CurrentPrincipal currentPrincipal) {
        this.statistics = statistics;
        this.authorization = authorization;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 取最近 {@code days} 天的报表中心聚合数据。
     *
     * @param days 天数（1～90，默认 30）
     */
    @GetMapping("/api/statistics/daily")
    public ResponseEntity<DailyStatistics> daily(
            @RequestParam(value = "days", defaultValue = "30") long days) {
        TenantContext context = currentPrincipal.context();
        // ⚠️ 执行时授权：即便会话建立时有权，此刻也可能已被撤权。
        authorization.requireAuthorized(context, AuthorizationService.Actions.RUN_READ, null);

        long safeDays = Math.min(Math.max(days, 1L), 90L);
        return ResponseEntity.ok(statistics.daily(safeDays, context.tenantId()));
    }
}
