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

    public RunController(RunCreationService runCreation,
                         AuthorizationService authorization,
                         ZeroRetentionGate retentionGate,
                         CurrentPrincipal currentPrincipal) {
        this.runCreation = runCreation;
        this.authorization = authorization;
        this.retentionGate = retentionGate;
        this.currentPrincipal = currentPrincipal;
    }

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

        // 2) 零保留闸门：未确认供应商时禁止真实学生数据
        //    ⚠️ 当前 P2 用 SYNTHETIC；接入真实数据时按 DataClassification 判定
        DataClassification classification = DataClassification.SYNTHETIC;
        retentionGate.assertAllowed(
                currentPrincipal.providerId(), requestText(request), classification);

        // 3) 执行时授权（红线 9）—— 在创建之前，而非之后
        authorization.requireAuthorized(context, AuthorizationService.Actions.RUN_CREATE, null);

        // 4) 用例：原子提交 + 幂等判定
        var result = runCreation.createRun(
                context,
                currentPrincipal.snapshotId(),
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
     * 而 providerId / snapshotId / correlationId 是调用侧上下文。
     * 分开可避免领域层被 API 概念污染。
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

        @NonNull String snapshotId();

        @NonNull String correlationId();
    }

    /** 统一错误响应。 */
    public record ErrorResponse(@NonNull String code, @NonNull String message) {
    }
}