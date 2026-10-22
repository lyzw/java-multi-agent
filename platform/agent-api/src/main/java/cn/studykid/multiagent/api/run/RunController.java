package cn.studykid.multiagent.api.run;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.application.run.RunCreationService;
import cn.studykid.multiagent.core.compliance.DataClassification;
import cn.studykid.multiagent.core.compliance.ZeroRetentionGate;
import cn.studykid.multiagent.core.compliance.ZeroRetentionViolationException;
import cn.studykid.multiagent.core.idempotency.IdempotencyConflictException;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.security.AccessDeniedException;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * Run 创建端点（D1 / FR-003）。
 *
 * <p><b>执行链不可跳过任何一步</b>（P0-D03 §4 红线 9）：
 * <pre>
 *   请求校验 → 内容合规 → <b>执行时授权</b> → 用例（原子提交 + 幂等）
 * </pre>
 *
 * <p>⚠️ 授权发生在<b>此刻</b>而非 Run 创建时：权限可能已被撤销
 * （学生退课、教师离职、账号封禁）。若只在别处授权一次，
 * 攻击者可用一个<b>曾经合法</b>的 Run 长期越权。
 *
 * <p>⚠️ tenantId 取自 {@link TenantContext}，<b>永不取自请求体</b>。
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private static final Logger log = LoggerFactory.getLogger(RunController.class);

    private final RunCreationService runCreation;
    private final AuthorizationService authorization;
    private final ZeroRetentionGate retentionGate;
    private final CurrentPrincipal currentPrincipal;

    /**
     * 限流器（P3-D08 / D-2）。
     *
     * <p>⚠️ <b>默认桩 = 永不拒绝</b>：未装配时保持既有行为；
     * 装配为真实实现后即生效（fail-closed）。
     */
    private final RateLimitPort rateLimiter;

    /**
     * Prompt 注入防护（P3-D09 / FR-016）。
     *
     * <p>⚠️ <b>默认用标准上限</b>，不提供「无防护」构造路径。
     */
    private final cn.studykid.multiagent.application.prompt.PromptGuard promptGuard;

    /**
     * ⚠️ <b>测试与「限流未装配」场景的便捷构造器</b>（P3-D08）。
     *
     * <p>本类被 {@code @RestController} 组件扫描注册，<b>有两个构造器时
     * Spring 无法选择</b> → {@code No default constructor found} →
     * 上下文启动失败（与 P3-D07 F-6 同源）。
     * 故必须用 {@link org.springframework.beans.factory.annotation.Autowired}
     * 显式标记注入用的那个。
     */
    public RunController(RunCreationService runCreation,
                         AuthorizationService authorization,
                         ZeroRetentionGate retentionGate,
                         CurrentPrincipal currentPrincipal) {
        this(runCreation, authorization, retentionGate, currentPrincipal, NEVER_LIMIT);
    }

    public RunController(RunCreationService runCreation,
                         AuthorizationService authorization,
                         ZeroRetentionGate retentionGate,
                         CurrentPrincipal currentPrincipal,
                         RateLimitPort rateLimiter) {
        this(runCreation, authorization, retentionGate, currentPrincipal, rateLimiter,
                new cn.studykid.multiagent.application.prompt.PromptGuard());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RunController(RunCreationService runCreation,
                         AuthorizationService authorization,
                         ZeroRetentionGate retentionGate,
                         CurrentPrincipal currentPrincipal,
                         RateLimitPort rateLimiter,
                         cn.studykid.multiagent.application.prompt.PromptGuard promptGuard) {
        this.runCreation = runCreation;
        this.authorization = authorization;
        this.retentionGate = retentionGate;
        this.currentPrincipal = currentPrincipal;
        this.rateLimiter = rateLimiter;
        this.promptGuard = promptGuard;
    }

    /**
     * 默认桩：永不拒绝（限流未装配时保持既有行为）。
     *
     * <p>⚠️ <b>不可退化为 lambda</b>：{@link RateLimitPort} 有两个抽象方法
     * （{@code check} + {@code currentCount}），不是函数式接口。
     * 这正是 D4 把 {@code BudgetReserver}（返回 boolean）换成多方法
     * {@code BudgetPort} 的用意——<b>强制替身实现全部契约</b>，
     * 防止「恒真放行」的替身蒙混过关。
     */
    private static final RateLimitPort NEVER_LIMIT = new RateLimitPort() {
        @Override
        public Decision check(String tenantId, String bucketKey) {
            return Decision.ALLOWED;
        }

        @Override
        public long currentCount(String tenantId, String bucketKey) {
            return 0L;
        }
    };

    /**
     * 创建 Run。
     *
     * <p>⚠️ <b>无 tenantId 参数</b>：租户来自已验签令牌（{@code CurrentPrincipal}），
     * 不可由客户端指定。这是 D1 部署阻断的修复点。
     *
     * @param request 请求体（不含任何租户字段）
     */
    @PostMapping
    public ResponseEntity<CreateRunResponse> createRun(
            @RequestBody CreateRunRequest request) {

        // ⚠️ 0) 可信身份：唯一来源是已验签的令牌，
        //    绝不从请求参数或请求体取租户 —— 这是 D1 部署阻断的核心修复。
        TenantContext context = currentPrincipal.context();

        // 1) 内容合规：拒绝明显可识别信息（纵深防御）
        request.assertNoIdentifyingContent();

        // 1b) ⚠️ Prompt 注入防护（P3-D09 / FR-016）：长度/深度/元素数有界。
        //     ⚠️ 刻意放在**创建之前**：超限输入若先落库再被 Worker 拒绝，
        //     已经消耗了 DB 写入与 MQ 投递——检查必须早于任何成本。
        try {
            promptGuard.validateBounds(request.input());
        } catch (cn.studykid.multiagent.application.prompt.PromptRejectedException e) {
            log.warn("输入被 Prompt 防护拒绝: tenant={}, reason={}, msg={}",
                    context.tenantId(), e.reason(), e.getMessage());
            // ⚠️ 400 而非 429/500：这是**客户端输入**问题，不是服务端故障
            return ResponseEntity.badRequest().build();
        }

        // 2) 零保留闸门：未确认供应商时禁止真实学生数据
        //    ⚠️ 当前 P2 用 SYNTHETIC；接入真实数据时按 DataClassification 判定
        DataClassification classification = DataClassification.SYNTHETIC;
        retentionGate.assertAllowed(
                currentPrincipal.providerId(), requestText(request), classification);

        // 3) 执行时授权（红线 9）—— 在创建之前，而非之后
        authorization.requireAuthorized(context, AuthorizationService.Actions.RUN_CREATE, null);

        // 3b) ⚠️ 限流（P3-D08 / D-2）：预算约束管「总量」，限流管「速率」。
        //     攻击者以高 QPS 调用、每笔都远低于单次预留额时，
        //     budget_no_overspend 一次都不会触发，但预算会在几分钟内被打穿。
        //     ⚠️ fail-closed：REJECTED 与 FAIL_CLOSED **都**返回 429。
        RateLimitPort.Decision limit = rateLimiter.check(
                context.tenantId(), RateLimitPort.Buckets.RUN_CREATE);
        if (limit != RateLimitPort.Decision.ALLOWED) {
            log.warn("创建 Run 被限流拒绝: tenant={}, decision={}",
                    context.tenantId(), limit);
            // ⚠️ 429 + Retry-After：让客户端知道这是速率问题而非参数错误
            return ResponseEntity.status(429)
                    .header("Retry-After", "60")
                    .build();
        }

        // 4) 用例：原子提交 + 幂等判定
        //    ⚠️ 传入 request.agentId()（P4-D18 修复）：执行快照由用例在写事务内
        //    解析。此前这里传的是 currentPrincipal.snapshotId()——
        //    一个恒为随机 UUID 的值，必然违反 run_snapshot_fk → 端点恒 500。
        var result = runCreation.createRun(
                context,
                request.agentId(),
                IdempotencyKey.forRunCreate(request.idempotencyKey()),
                request.input());

        // 5) 幂等重放返回 200，新建返回 201 —— 让客户端能区分
        ResponseEntity.BodyBuilder builder = result.replayed()
                ? ResponseEntity.ok()
                : ResponseEntity.status(HttpStatus.CREATED);

        return builder.body(new CreateRunResponse(
                result.runId(), result.replayed(),
                "QUEUED", currentPrincipal.correlationId()));
    }

    private static String requestText(CreateRunRequest request) {
        return String.valueOf(request.input());
    }

    /**
     * 当前主体信息。
     *
     * <p><b>为何独立于 TenantContext</b>：TenantContext 是领域概念（租户+主体+角色），
     * 而 providerId / correlationId 是调用侧上下文。
     * 分开可避免领域层被 API 概念污染。
     *
     * <p><b>⚠️ 此处**曾经**有一个 {@code snapshotId()}，已删除（P4-D18）</b>：
     * 它返回 {@code UUID.randomUUID()}，而 {@code run.snapshot_id} 有外键
     * {@code run_snapshot_fk → execution_snapshot}，使
     * {@code POST /api/runs} <b>恒返回 500</b>（实测：任何 agentId 都失败）。
     * <b>删除而非修正</b>——一个叫「当前主体」却要靠随机数回答
     * 「该用哪个配置快照」的接口本身就是错误抽象；
     * 留着它只会诱使下一个接线的人再犯同样的错。
     * 快照现由 {@code RunCreationService} 在<b>写事务内</b>按
     * {@code request.agentId()} 解析。
     */
    public interface CurrentPrincipal {

        /**
         * 当前请求的<b>可信</b>租户上下文。
         *
         * <p>⚠️ <b>这是 D1 部署阻断的修复点</b>：此前上下文由方法参数
         * {@code @NonNull TenantContext context} 接收，Spring 会尝试
         * 从请求构造它 —— 任何调用方都能填入任意 {@code tenantId} 与
         * {@code roles}，使授权闸门形同虚设。
         *
         * <p>改为由本接口提供，实现方必须先验签再返回。
         * 生产实现见 {@code JwtCurrentPrincipal}（D-001）。
         *
         * @throws cn.studykid.multiagent.api.security.CurrentPrincipal.UnauthenticatedException
         *         无令牌或令牌无效（→ 401）
         */
        @NonNull TenantContext context();

        @NonNull String principalId();

        @NonNull String providerId();

        @NonNull String correlationId();
    }

    /** 统一错误响应。 */
    public record ErrorResponse(@NonNull String code, @NonNull String message) {
    }
}