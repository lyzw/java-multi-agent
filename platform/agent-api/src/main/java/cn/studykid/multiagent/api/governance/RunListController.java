package cn.studykid.multiagent.api.governance;

import java.time.Instant;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.persistence.RunListQueryPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * Run 列表端点（P4-D13 / G7 · FE-01 §P7 检索台）。
 *
 * <p><b>⚠️ 租户只来自已验签令牌</b>，绝不从查询参数取——
 * 与 {@code RunQueryController} 同一条铁律。
 *
 * <p>⛔ <b>不返回 {@code inputPayload} / {@code errorMessage} / {@code tenantId}</b>：
 * 前两者分别是学生提交的题目解答与内部错误细节。
 */
@RestController
public class RunListController {

    /**
     * {@code run_state} 的合法取值。
     *
     * <p>⚠️ <b>刻意不含 {@code UNKNOWN}</b>：P4-D10 §1.4 实测该枚举为
     * {@code QUEUED/RUNNING/CANCELLED/SUCCEEDED/FAILED/TIMED_OUT}。
     * 传 {@code state=UNKNOWN} 必须 400——让它返回空列表会把
     * 「状态不存在」伪装成「没有数据」。
     */
    private static final Set<String> RUN_STATES = Set.of(
            "QUEUED", "RUNNING", "CANCELLED", "SUCCEEDED", "FAILED", "TIMED_OUT");

    private final RunListQueryPort runList;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final CurrentPrincipal currentPrincipal;

    public RunListController(RunListQueryPort runList,
                             AuthorizationService authorization,
                             RateLimitPort rateLimiter,
                             CurrentPrincipal currentPrincipal) {
        this.runList = runList;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/api/runs")
    public ResponseEntity<GovernanceSupport.PageResponse<RunListItemResponse>> listRuns(
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable String createdFrom,
            @RequestParam(required = false) @Nullable String createdTo) {

        TenantContext context = currentPrincipal.context();
        // ⚠️ 执行时授权（红线 9）。资源标识为 null = 动作级授权，不针对单个 Run。
        authorization.requireAuthorized(context, AuthorizationService.Actions.RUN_READ, null);

        if (GovernanceSupport.rateLimited(rateLimiter, context,
                RateLimitPort.Buckets.GOVERNANCE_QUERY)) {
            return GovernanceSupport.tooManyRequests();
        }

        int pageSize = GovernanceSupport.limit(limit);
        GovernanceSupport.requireEnum(state, RUN_STATES, "state");
        Instant from = GovernanceSupport.instant(createdFrom, "createdFrom");
        Instant to = GovernanceSupport.instant(createdTo, "createdTo");
        GovernanceSupport.checkRange(from, to);

        var page = runList.list(context.tenantId(), state, from, to, cursor, pageSize);
        return ResponseEntity.ok(GovernanceSupport.PageResponse.of(
                page.items().stream().map(RunListItemResponse::of).toList(),
                page.nextCursor()));
    }

    /**
     * Run 列表条目。
     *
     * <p>⚠️ <b>字段名一律 camelCase</b>（Java record 直接序列化，零转换）。
     */
    public record RunListItemResponse(
            @NonNull String runId,
            @NonNull String state,
            @NonNull Instant createdAt,
            @Nullable Instant completedAt,
            int attempt,
            @Nullable String errorCode
    ) {
        static RunListItemResponse of(RunListQueryPort.RunListItem item) {
            return new RunListItemResponse(item.runId(), item.state(), item.createdAt(),
                    item.completedAt(), item.attempt(), item.errorCode());
        }
    }
}