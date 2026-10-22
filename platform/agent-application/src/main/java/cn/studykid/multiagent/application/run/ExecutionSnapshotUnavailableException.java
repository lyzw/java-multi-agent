package cn.studykid.multiagent.application.run;

import org.jspecify.annotations.NonNull;

/**
 * 执行快照不可解析（Agent 未发布 / 无快照 / ID 非法）。
 *
 * <p><b>⚠️ 为什么是 404 而不是 500</b>：
 * 这是<b>客户端引用了一个不可用的资源</b>——传了一个不存在的 {@code agentId}，
 * 或该 Agent 尚未发布任何版本。这类错误有确定解法（换一个已发布的 Agent），
 * 报 500 会让前端与运维都以为系统坏了从而反复重试，
 * 而重试永远不会成功。
 *
 * <p>这与本项目反复出现的「拒绝伪装成故障」是同一形态：
 * {@code ApiExceptionHandler} 的类注释已写明「异常与拒绝必须可区分」。
 *
 * <p>此前该情形以 {@code IllegalStateException("Run 原子写入失败")} 冒泡，
 * 被兜底处理器接住 → 500。实测症状：请求体里传任何 {@code agentId}
 * （包括一个完全正确的）都返回 500。
 */
public class ExecutionSnapshotUnavailableException extends RuntimeException {

    private final String tenantId;
    private final String agentId;

    public ExecutionSnapshotUnavailableException(@NonNull String tenantId, @NonNull String agentId) {
        super("执行快照不可解析（agentId=" + agentId + "）");
        this.tenantId = tenantId;
        this.agentId = agentId;
    }

    /** 租户 ID（来自认证上下文，仅供日志用，<b>不得</b>回显给客户端）。 */
    public @NonNull String tenantId() {
        return tenantId;
    }

    /** 客户端传入的 Agent ID。 */
    public @NonNull String agentId() {
        return agentId;
    }
}