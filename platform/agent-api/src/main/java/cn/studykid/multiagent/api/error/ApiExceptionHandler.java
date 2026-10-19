package cn.studykid.multiagent.api.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cn.studykid.multiagent.api.run.RunController.ErrorResponse;
import cn.studykid.multiagent.core.compliance.ZeroRetentionViolationException;
import cn.studykid.multiagent.core.idempotency.IdempotencyConflictException;
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
     * 兜底 → 500。
     *
     * <p>⚠️ 返回**通用**消息，不透出异常详情——
     * 堆栈与内部结构可能含路径、表名、数据。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "服务暂时不可用"));
    }
}