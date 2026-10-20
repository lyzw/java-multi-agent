package cn.studykid.multiagent.api.run;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.persistence.RunQueryPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * Run 状态查询端点（P4-D09 / G3）。
 *
 * <p><b>为什么需要它</b>（实测）：
 * {@code run_event} 表<b>只有 {@code RUN_CREATED} 由生产代码写入</b>，Worker 不写该表
 * → 进度页<b>长期拿不到执行中事件</b>，只能靠 SSE 推断状态。
 * Run 的权威状态在 {@code run.state} 列，本端点是它<b>唯一的对外出口</b>。
 *
 * <p>⚠️ <b>404 不区分「不存在」与「无权限」</b>：
 * 若分别返回 404 与 403，攻击者可通过状态码差异<b>枚举 Run 是否存在</b>。
 * 故两种情形统一返回 404「资源不存在」。
 */
@RestController
public class RunQueryController {

    private static final Logger log = LoggerFactory.getLogger(RunQueryController.class);

    private final RunQueryPort runQuery;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final RunController.CurrentPrincipal currentPrincipal;

    public RunQueryController(RunQueryPort runQuery,
                              AuthorizationService authorization,
                              RateLimitPort rateLimiter,
                              RunController.CurrentPrincipal currentPrincipal) {
        this.runQuery = runQuery;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 查询 Run 状态。
     *
     * @param runId Run 标识
     */
    @GetMapping("/api/runs/{runId}")
    public ResponseEntity<RunStatusResponse> getRunStatus(@PathVariable String runId) {
        // ⚠️ 租户来自已验签令牌，绝不从请求参数取
        TenantContext context = currentPrincipal.context();

        // ⚠️ 执行时授权（红线 9）
        authorization.requireAuthorized(
                context, AuthorizationService.Actions.RUN_READ, runId);

        // ⚠️ 限流：状态查询是高频轮询动作，不限流会被打穿。
        //    复用 EVENT_QUERY 桶（与 SSE 续传同属「只读轮询」类），
        //    不新增桶——避免改动既有限流配置的语义与配额基线。
        RateLimitPort.Decision limit = rateLimiter.check(
                context.tenantId(), RateLimitPort.Buckets.EVENT_QUERY);
        if (limit != RateLimitPort.Decision.ALLOWED) {
            return ResponseEntity.status(429).header("Retry-After", "60").build();
        }

        RunQueryPort.RunStatus status = runQuery.findStatus(context.tenantId(), runId);
        if (status == null) {
            // ⚠️ 不存在与「不属于该租户」返回同一个 404：
            //    区分即构成存在性探测通道。
            log.info("Run 状态查询未命中: tenant={}, runId={}", context.tenantId(), runId);
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在");
        }

        return ResponseEntity.ok(new RunStatusResponse(
                status.runId(),
                status.state(),
                status.createdAt(),
                status.completedAt(),
                status.attempt(),
                status.errorCode()));
    }

    /**
     * Run 状态响应。
     *
     * <p>⚠️ <b>字段名一律 camelCase</b>：后端无任何
     * {@code property-naming-strategy} 配置，Java record 组件名直接序列化为 camelCase，
     * 前端零转换取值（对齐 {@code CreateRunResponse} / {@code ConfigResponse}）。
     *
     * @param runId       Run 标识
     * @param state       Run 状态（{@code run_state} 枚举；<b>绝不含 UNKNOWN</b>）
     * @param createdAt   创建时刻
     * @param completedAt 完成时刻；非终态为 {@code null}
     * @param attempt     领取次数
     * @param errorCode   失败原因码；<b>不含 error_message 原文</b>
     */
    public record RunStatusResponse(
            @NonNull String runId,
            @NonNull String state,
            @NonNull Instant createdAt,
            @Nullable Instant completedAt,
            int attempt,
            @Nullable String errorCode
    ) {
    }
}
