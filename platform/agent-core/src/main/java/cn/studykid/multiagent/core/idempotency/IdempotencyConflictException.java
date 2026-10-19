package cn.studykid.multiagent.core.idempotency;

/**
 * 幂等冲突 —— 同一幂等键被用于<b>不同</b>请求。
 *
 * <p>归类为客户端错误（4xx），归因到调用方：它复用了已占用的键却改了内容。
 * <b>不是</b>服务端故障，也<b>不应</b>被当作可重试错误。
 *
 * <p>错误消息<b>不</b>包含具体键值与摘要内容，避免键值中的业务信息泄漏到日志
 * （P0-D05 R-015 脱敏要求）。
 */
public class IdempotencyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException() {
        super("幂等冲突：该幂等键已被一个不同的请求占用。"
                + "请改用新的幂等键，或使用原始请求参数重试");
    }
}