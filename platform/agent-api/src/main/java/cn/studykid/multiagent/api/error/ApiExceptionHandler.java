package cn.studykid.multiagent.api.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.config.ConfigOverrideController;
import cn.studykid.multiagent.api.run.RunController.ErrorResponse;
import cn.studykid.multiagent.api.security.CurrentPrincipal.UnauthenticatedException;
import cn.studykid.multiagent.application.config.ConfigTighteningPolicy;
import cn.studykid.multiagent.application.monitoring.MonitoringViewService;
import cn.studykid.multiagent.application.run.ExecutionSnapshotUnavailableException;
import cn.studykid.multiagent.application.run.RunBudgetLimitInvalidException;
import cn.studykid.multiagent.core.compliance.ZeroRetentionViolationException;
import cn.studykid.multiagent.core.idempotency.IdempotencyConflictException;
import cn.studykid.multiagent.ports.persistence.PlatformOpsUnavailableException;
import cn.studykid.multiagent.ports.security.AccessDeniedException;

/**
 * 全局异常处理。
 *
 * <p><b>核心原则：错误消息不得泄漏内部结构</b>——
 * 表名、SQL、供应商类型、租户标识都不应出现在返回给客户端的消息里。
 * 详细信息记日志，返回给客户端的只有归一化的 code 与简述。
 *
 * <p>另一个原则：<b>异常与拒绝必须可区分</b>。
 * 「系统故障」返回 5xx，「明确拒绝」返回 4xx——
 * 把拒绝伪装成故障会导致监控误报，把故障伪装成拒绝会导致问题被忽略。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 授权拒绝 → 403。
     *
     * <p>⚠️ 消息中<b>不含</b>资源标识与租户，避免探测者据此枚举资源。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        log.warn("授权拒绝: principal={}, action={}",
                e.principalId(), e.action());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("ACCESS_DENIED", "无权执行该操作"));
    }

    /**
     * 执行快照不可解析 → 404（P4-D18）。
     *
     * <p><b>⚠️ 为什么必须是 404 而非 500</b>：传入了一个<b>不可用的资源</b>
     *（Agent 不存在 / 未发布 / 无快照），属客户端错误，且<b>有确定解法</b>——
     * 换一个已发布的 Agent。报 500 会让前端与运维都以为系统坏了从而反复重试，
     * 而重试永远不会成功（与本类「拒绝伪装成故障」的核心原则直接矛盾）。
     *
     * <p><b>修复前的真实症状</b>：{@code CurrentPrincipal.snapshotId()} 返回随机 UUID
     * → 外键 {@code run_snapshot_fk} 必然违反 → 冒泡为
     * {@code IllegalStateException("Run 原子写入失败")} → 被兜底处理器接住 → 500。
     * 即<b>一个完全正确的 agentId 也返回 500</b>。
     *
     * <p>⚠️ 消息固定、不回显 agentId：后者是客户端自己给的，
     * 回显无信息增益，却会让「枚举 Agent 是否存在」变得容易。
     */
    @ExceptionHandler(ExecutionSnapshotUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleSnapshotUnavailable(
            ExecutionSnapshotUnavailableException e) {
        log.warn("执行快照不可解析: tenant={}, agentId={}", e.tenantId(), e.agentId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("AGENT_SNAPSHOT_UNAVAILABLE",
                        "该 Agent 尚无可用的已发布版本"));
    }

    /**
     * 幂等冲突 → 409。
     *
     * <p>客户端复用了已占用的键却改了内容，属客户端错误。
     * 消息不包含键值与摘要（可能含业务信息）。
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException e) {
        log.warn("幂等冲突");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("IDEMPOTENCY_CONFLICT",
                        "该幂等键已被一个不同的请求占用"));
    }

    /**
     * Run 级额度声明不可用 → 400。
     *
     * <p>⚠️ <b>为什么是 400 而不是 500</b>：
     * 触发条件是「声明与现实不一致」（声明的 Run 额度大于租户总额度 /
     * 租户未开通预算账户）—— 属<b>客户端请求</b>问题，且有确定解法
     * （调小额度声明 / 开通账户）。
     *
     * <p>⚠️ <b>消息不回显具体金额</b>：租户额度属敏感经营数据，
     * 回显等于把「这个租户还剩多少钱」暴露给能构造请求的一方。
     */
    @ExceptionHandler(RunBudgetLimitInvalidException.class)
    public ResponseEntity<ErrorResponse> handleRunBudgetLimitInvalid(
            RunBudgetLimitInvalidException e) {
        log.warn("Run 级额度声明被拒: tenant={}, 声明={}", e.tenantId(), e.declaredAmount());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("RUN_BUDGET_LIMIT_INVALID",
                        "声明的 Run 级额度不可用（须不超过租户账户总额度，且租户已开通预算账户）"));
    }

    /**
     * 零保留闸门拦截 → 451 或 403。
     *
     * <p>用 451（Legal Obstacle）表达合规阻断：
     * 它比 403 更准确地说明「不是权限不足，是法律合规不允许」。
     */
    @ExceptionHandler(ZeroRetentionViolationException.class)
    public ResponseEntity<ErrorResponse> handleZeroRetention(ZeroRetentionViolationException e) {
        // ⚠️ 合规事件需留痕，但**不记录被拦截的数据内容**
        log.error("零保留闸门拦截: state={}, provider={}, classification={}",
                e.state(), e.providerId(), e.classification());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("COMPLIANCE_BLOCKED",
                        "当前合规状态不允许该请求"));
    }

    /**
     * 请求体不可读 → 400。
     *
     * <p>包括未知字段（如 {@code tenantId}）、类型错误、缺字段。
     * 明确拒绝而非忽略——忽略会掩盖越权尝试。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        log.debug("请求体不可读");
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST",
                        "请求体格式错误或包含不被接受的字段"));
    }

    /**
     * 参数校验失败 → 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST", e.getMessage()));
    }

    /**
     * 监控数据源不可用 → 503（管理面 P4 运行监控）。
     *
     * <p>⚠️ <b>必须是 503 而不是「返回空数据」或 500</b>：
     * <ul>
     *   <li><b>不返回空数据</b>：监控页的默认状态是「一切正常」。
     *       若在数据源故障时返回「0 个拒绝、0 个主体」，运维看到的是<b>绿色</b>，
     *       而真相是「我什么都看不到」—— 这是最危险的一种失败；</li>
     *   <li><b>不用 500</b>：500 表示「服务端坏了，重试可能有用」，
     *       而数据源不可用时重试同样会失败；503 明确表达
     *       「服务暂时不可用，稍后重试」。</li>
     * </ul>
     *
     * <p>⚠️ <b>与 403 严格区分</b>：本异常表示「我看不到」，
     * 403 表示「我看到了并拒绝了」。混用会让「监控挂了」被误读成「你在越权」。
     *
     * <p>⚠️ 消息固定、不回显 {@code getMessage()}：后者含底层异常摘要，
     * 可能泄漏表名与连接串。
     */
    @ExceptionHandler(MonitoringViewService.MonitoringUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleMonitoringUnavailable(
            MonitoringViewService.MonitoringUnavailableException e) {
        log.error("监控数据源不可用: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("MONITORING_UNAVAILABLE", "监控数据暂时不可用"));
    }

    /**
     * 兜底 → 500。
     *
     * <p>⚠️ 返回**通用**消息，不透出异常详情——
     * 堆栈与内部结构可能含路径、表名、数据。
     */
    /**
     * 媒体类型不支持 → 415。
     *
     * <p>⚠️ <b>这是端到端测试发现的真实缺陷</b>：原先落入兜底处理器返回
     * {@code 500}，让「客户端发错 Content-Type」被报成「服务端故障」。
     * 后果有二：客户端无法区分「我错了」与「服务端坏了」，
     * 监控上服务端错误率被客户端笔误污染。
     *
     * <p>⚠️ 响应同样不含 {@code getMessage()} —— 它会回显请求体的媒体类型细节。
     */
    /**
     * 平台运维角色（BYPASSRLS）未接入 → 503（fail-closed，重规划 §11 / P6R1）。
     *
     * <p>⚠️ <b>必须是专用处理器，而不是让控制器包一条
     * {@code ResponseStatusException(503)}</b>：
     * {@link #handleResponseStatus(ResponseStatusException)} 会把「控制器声明的
     * 5xx」一律吞成 500（P4-D09 护栏，防 404/403 语义被 5xx 误吞时顺带产生）。
     * 平台能力目录（/api/platform/v1/capabilities/*）曾因此把本应 fail-closed
     * 的 503 呈现成了 {@code INTERNAL_ERROR}。
     *
     * <p>与监控不可用同口径：<b>不静默空态</b>（目录页空表会让运维误以为
     * 「平台没有这些对象」），<b>不用 500</b>（重试无用，缺的是运维配置）。
     * 响应体同样不回显 {@link Exception#getMessage()}——
     * 它含有缺失的配置项名与底层异常摘要。
     */
    @ExceptionHandler(PlatformOpsUnavailableException.class)
    public ResponseEntity<ErrorResponse> handlePlatformOpsUnavailable(
            PlatformOpsUnavailableException e) {
        log.error("平台运维角色未接入（fail-closed）: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("PLATFORM_OPS_UNAVAILABLE", "平台运维通道未接入"));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            org.springframework.web.HttpMediaTypeNotSupportedException e) {
        log.warn("媒体类型不支持");
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "请求媒体类型不受支持"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "服务暂时不可用"));
    }

    // ── 以下两个是 P4-D09 端到端验证发现的真实缺陷 ──────────────────────

    /**
     * 控制器显式声明的 HTTP 状态 → 按声明返回。
     *
     * <p>⚠️⚠️ <b>这是 fail-open 缺陷的修复（P4-D09 实测）</b>：
     * {@link ResponseStatusException} 是「资源不存在 404」「参数格式非法 400」
     * 这类<b>刻意声明</b>的客户端可见状态。但 {@code @RestControllerAdvice}
     * 的 {@link ExceptionHandler} 解析器<b>优先于</b> Spring 内置的
     * {@code ResponseStatusExceptionResolver}，
     * 于是本类原有的兜底 {@code @ExceptionHandler(Exception.class)}
     * 会<b>抢先把它们吞成 500</b>。
     *
     * <p>危害（三重，且都属于「安全语义」）：
     * <ol>
     *   <li><b>状态码语义反转</b>：404 变 500，客户端无法区分
     *       「资源不存在」与「服务端故障」，会做出完全相反的重试决策；</li>
     *   <li><b>破坏存在性探测纪律</b>：404 与 500 的差异被抹平虽看似更"安全"，
     *       但同时把<b>真的 404 也变成了 500</b>——
     *       正确做法是让声明的状态码生效，由控制器自己保证
     *       「不存在」与「无权限」返回同一个码（见 {@code RunQueryController}）；</li>
     *   <li><b>监控污染</b>：用户数据错误被计入服务端错误率，
     *       真实故障被淹没——恰好与本类 Javadoc 声明的
     *       「异常与拒绝必须可区分」原则相反。</li>
     * </ol>
     *
     * <p>⚠️ <b>消息需归一化</b>：{@code getReason()} 由控制器编写，
     * 本项目约定其为不含内部结构的短句（如「资源不存在」）；
     * 但为防止后续新增端点误传敏感信息，此处<b>不回显原文</b>，
     * 仅按状态码给出固定文案。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        // ⚠️ 未映射到 HttpStatus 的状态码按 500 处理：
        //    它意味着控制器声明了一个不存在的状态，属编程错误，不能当 4xx 放行。
        if (status == null || status.is5xxServerError()) {
            log.error("控制器声明了非法或服务端错误状态: code={}", e.getStatusCode(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("INTERNAL_ERROR", "服务暂时不可用"));
        }
        log.info("控制器声明的状态: code={}", e.getStatusCode());
        return ResponseEntity.status(status)
                .body(new ErrorResponse(codeOf(status), messageOf(status)));
    }

    /**
 * L3 高危配置键试图直写（P4-D16 / C7 → 409）。
 *
 * <p>⚠️ <b>必须是 409 而非 500</b>：这不是服务端故障，
 * 而是一个<b>有确定解法</b>的状态——「请改走审批通道」。
 * 报 500 会让前端与运维都以为系统坏了，从而反复重试；
 * 而正确响应是引导用户换一条路。
 *
 * <p>⚠️ <b>与 {@link ConfigTighteningPolicy.ConfigRelaxationException} 分开</b>：
 * 二者对前端的行动建议完全不同——
 * 收紧被拒是「改小一点再试」，L3 直写被拒是「<b>换一条路</b>」。
 */
@ExceptionHandler(ConfigOverrideController.ApprovalRequiredException.class)
public ResponseEntity<ErrorResponse> handleApprovalRequired(
        ConfigOverrideController.ApprovalRequiredException e) {
    log.info("高危配置键试图直写（须走审批）: key={} tier={}", e.key(), e.tier());
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorResponse("CONFIG_APPROVAL_REQUIRED",
                    "该配置项为高危变更，须提交变更申请并经审批后生效"));
}

    /**
     * 配置变更被安全门禁拒绝（P4-D15 / C5）。
     *
     * <p>⚠️ <b>为什么单独一个处理器而不走 {@code ResponseStatusException}</b>：
     * {@code codeOf(CONFLICT)} 会把所有 409 映射成 {@code IDEMPOTENCY_CONFLICT}，
     * 而本条的语义是「<b>安全门禁降级</b>」——两者对前端的行动建议<b>完全相反</b>：
     * 幂等冲突的正确反应是「刷新后重试」，而门禁拒绝的正确反应是
     * 「<b>停下来</b>，别再重试，改为只收紧」。
     *
     * <p>若前端拿到 {@code IDEMPOTENCY_CONFLICT} 会反复刷新重试一个注定被拒的操作，
     * 而刷新永远不会让「只能收紧」变松——这是把安全拒绝伪装成可重试错误。
     */
    @ExceptionHandler(ConfigTighteningPolicy.ConfigRelaxationException.class)
    public ResponseEntity<ErrorResponse> handleConfigRelaxation(
            ConfigTighteningPolicy.ConfigRelaxationException e) {
        // ⚠️ 固定文案、不回显 e.getMessage()：后者含新旧值，可能携带用户输入。
        log.info("配置变更被收紧策略拒绝: key={}", e.key());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONFIG_RELAXATION_REJECTED",
                        "该变更被安全策略拒绝：配置只允许收紧，不允许放松"));
    }

    /**
     * 配置期门禁拒绝 → 422。
     *
     * <p><b>⚠️ 修复前是 500（真实缺陷）</b>：
     * {@link cn.studykid.multiagent.application.cap.ConfigPublishRejectedException}
     * 在全仓库<b>从未被任何 catch 捕获</b>，也没有对应的 handler →
     * 一路冒泡到兜底的 {@code @ExceptionHandler(Exception.class)} →
     * 返回 <b>500「服务暂时不可用」</b>。
     *
     * <p>后果与本类的核心原则（「异常与拒绝必须可区分」）直接相反：
     * 「配置非法」被伪装成「系统故障」→ 运维拿着发布失败的告警去查数据库与连接池，
     * 而真实原因只是某个 Tool 的 {@code risk_level} 写错了；
     * 同时用户配置错误被计入服务端错误率，把真实故障淹没。
     *
     * <p>该异常自带 {@link cn.studykid.multiagent.core.cap.ReasonCode} 与违规字段，
     * 本就为了「让失败原因直接可读」而设计 —— 此前却全被丢弃。
     *
     * <p>⚠️ 消息<b>固定</b>、不回显原文：原文含新旧配置值，可能携带用户输入。
     * 原因码与字段名是<b>枚举/标识</b>，回显无泄漏风险且对排障有用。
     */
    @ExceptionHandler(cn.studykid.multiagent.application.cap.ConfigPublishRejectedException.class)
    public ResponseEntity<ErrorResponse> handlePublishRejected(
            cn.studykid.multiagent.application.cap.ConfigPublishRejectedException e) {
        log.info("配置发布被拒: reasonCode={}, field={}",
                e.reasonCode(), e.field());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("CONFIG_PUBLISH_REJECTED",
                        "配置未通过发布门禁: " + e.reasonCode() + "（字段 " + e.field() + "）"));
    }

    /**
     * 配置发布并发冲突 → 409。
     *
     * <p>⚠️ 修复前同样落到兜底 → 500。
     * 「CAS 基线已被他人推进」是<b>可重试的竞争</b>，不是服务端故障。
     */
    @ExceptionHandler(cn.studykid.multiagent.application.cap.ConfigPublishConflictException.class)
    public ResponseEntity<ErrorResponse> handlePublishConflict(
            cn.studykid.multiagent.application.cap.ConfigPublishConflictException e) {
        log.info("配置发布冲突（基线已被推进）: expectedPublishSeq={}", e.expectedPublishSeq());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONFIG_PUBLISH_CONFLICT",
                        "配置已被他人变更，请重新读取当前状态后再发布"));
    }

    /**
     * 租户不匹配 → 403。
     *
     * <p>⚠️ 修复前落到兜底 → 500。
     * 租户不匹配是<b>明确拒绝</b>（越权尝试），不是故障。
     */
    @ExceptionHandler(cn.studykid.multiagent.core.tenant.TenantMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTenantMismatch(
            cn.studykid.multiagent.core.tenant.TenantMismatchException e) {
        log.warn("租户不匹配被拒: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("TENANT_MISMATCH", "无权访问该租户的资源"));
    }

    /**
     * Run / ToolOperation 非法状态转移 → 409。
     *
     * <p>⚠️ 修复前落到兜底 → 500。
     * 非法转移是<b>调用方的流程错误</b>（如对已终态的 Run 再次取消），
     * 有确定解法（换一个状态的对象），报 500 会诱导客户端反复重试。
     */
    @ExceptionHandler(cn.studykid.multiagent.core.run.IllegalStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalTransition(
            cn.studykid.multiagent.core.run.IllegalStateTransitionException e) {
        log.info("非法状态转移: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ILLEGAL_STATE_TRANSITION", "请求与当前资源状态冲突"));
    }

    /**
     * 配置发布申请单不存在 → 404。
     *
     * <p>⚠️ 修复前落到兜底 → 500。
     */
    @ExceptionHandler(cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort
            .NotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePublishRequestNotFound(
            cn.studykid.multiagent.ports.persistence.ConfigPublishRequestPort
                    .NotFoundException e) {
        log.info("配置发布申请单不存在: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", "资源不存在"));
    }

    /**
     * 配置变更申请不存在 → 404。
     *
     * <p>⚠️ 修复前落到兜底 → 500。
     */
    @ExceptionHandler(cn.studykid.multiagent.ports.persistence.ConfigChangePort
            .ChangeNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleConfigChangeNotFound(
            cn.studykid.multiagent.ports.persistence.ConfigChangePort
                    .ChangeNotFoundException e) {
        log.info("配置变更申请不存在: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", "资源不存在"));
    }

    /**
     * 缺少认证凭据 → 401。
     *
     * <p>⚠️ <b>同样是被兜底处理器吞掉的缺陷（P4-D09 实测）</b>：
     * {@link UnauthenticatedException} 由 {@code CurrentPrincipal.require()} 抛出，
     * 原本应映射 401。
     *
     * <p><b>为什么这条格外重要</b>：正常部署下 Spring Security 过滤器链
     * 会在请求抵达控制器<b>之前</b>返回 401，故这条路径平时不触发。
     * 但它<b>确实可达</b>——一旦请求通过了过滤器链却缺少可解析的租户上下文
     * （如令牌被中途清除、异步派发丢失 SecurityContext），
     * 没有本处理器时用户会看到 <b>500「服务暂时不可用」</b>，
     * 即<b>「鉴权失败」被伪装成「服务端故障」</b>——
     * 这与本类的核心原则直接矛盾，且会诱导客户端反复重试一个永远不会成功的请求。
     */
    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthenticated(UnauthenticatedException e) {
        // ⚠️ 消息固定为「未认证」，不回显 getMessage()（不含令牌内容但也无必要透出细节）
        log.info("缺少认证凭据: principal=匿名");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHENTICATED", "未认证"));
    }

    /**
     * 请求路径未映射到任何端点 → 404。
     *
     * <p>⚠️ <b>与 P4-D09 已修的 {@code ResponseStatusException} 同类的缺陷</b>：
     * Spring 6+ 起，路径不匹配静态资源与任何 {@code @RequestMapping} 时，
     * 抛的是 {@link org.springframework.web.servlet.resource.NoResourceFoundException}，
     * Spring 默认会把它映射成 <b>404</b>。
     * 但本类的兜底 {@code @ExceptionHandler(Exception.class)}
     * <b>优先于</b> Spring 内置的 {@code DefaultHandlerExceptionResolver}，
     * 于是未映射路径被<b>吞成 500</b>。
     *
     * <p>危害与 P4-D09 记录的一致，且此处更隐蔽：
     * <ul>
     *   <li><b>状态码语义反转</b>：404 变 500，客户端无法区分
     *       「路径不存在」与「服务端故障」，会做出完全相反的重试决策；</li>
     *   <li><b>监控污染</b>：用户把路径写错、客户端版本不匹配、
     *       前端调用了已下线的端点 —— 这些都是<b>客户端数据错误</b>，
     *       却被计入服务端错误率，把真实故障淹没。</li>
     * </ul>
     *
     * <p>⚠️ <b>实测证据</b>（2026-10-23 全栈 E2E）：{@code /api/nonexistent}、
     * {@code /api/cap/config/nope}、{@code /api/audit-records}、{@code /actuator/nope}
     * 全部返回 500；而「已映射但资源不存在」正确返回 404 —— 两者本应是同一个码。
     * 对照组：RAG 服务同场景<b>正确返 404</b>（它没有这个兜底处理器），故本条为 platform 特有。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        // ⚠️ 不回显 e.getMessage()：它含请求路径原文（可能含客户端数据）
        log.info("请求路径未映射到任何端点: path={}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(codeOf(HttpStatus.NOT_FOUND), messageOf(HttpStatus.NOT_FOUND)));
    }

    /** 4xx 状态 → 归一化 code。 */
    private static String codeOf(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "INVALID_REQUEST";
            case UNAUTHORIZED -> "UNAUTHENTICATED";
            case FORBIDDEN -> "ACCESS_DENIED";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "IDEMPOTENCY_CONFLICT";
            default -> "REQUEST_REJECTED";
        };
    }

    /** 4xx 状态 → 归一化消息（<b>不含</b>控制器传入的原文）。 */
    private static String messageOf(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "请求参数不合法";
            case UNAUTHORIZED -> "未认证";
            case FORBIDDEN -> "无权执行该操作";
            case NOT_FOUND -> "资源不存在";
            case CONFLICT -> "请求与当前资源状态冲突";
            default -> "请求被拒绝";
        };
    }
}