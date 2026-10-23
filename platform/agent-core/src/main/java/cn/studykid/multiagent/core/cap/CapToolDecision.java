package cn.studykid.multiagent.core.cap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 单次工具调用的决策记录（P2 · §5.1 统一决策状态模型 · §5.3 唯一协议）。
 *
 * <p>把「哪个工具」与「四字段结论」绑定成一条不可拆分的记录，
 * 是 §8.3 冻结门槛「§9 行为测试四字段」与 08 §7「决策日志四字段完整率 100%」
 * 的载体 —— 没有这条记录，调用方只能拿到「成功/失败」这类粗粒度结论，
 * 无法逐字段断言 reasonCode。
 *
 * <p>⚠️ <b>决策与副作用必须成对产生</b>：每一次 {@code ToolHandler.execute}
 * 都必须有一条对应的 {@code CapToolDecision}，且其
 * {@code authorizationDecision / executionDecision / executionStatus / reasonCode}
 * 必须是该次调用的<b>终态</b>，不得是中途值。
 *
 * @param toolName    工具名（与 {@code ToolHandler.toolName()} 同源）
 * @param operationId 账本操作标识（账本登记之前为 {@code null}）
 * @param decision    四字段决策结论
 */
public record CapToolDecision(
        @NonNull String toolName,
        @Nullable String operationId,
        @NonNull AuthorizationDecision decision) {

    public CapToolDecision {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision 不能为空");
        }
    }

    /**
     * @return 是否为「授权拒绝」（Phase A 未通过，从未进入 Phase B）
     */
    public boolean isAuthorizationDenied() {
        return decision.authorization() == AuthorizationDecision.Authorization.DENY;
    }

    /**
     * @return 是否为「执行准入拒绝」（授权通过，但 Phase B 拒绝，副作用未启动）
     */
    public boolean isExecutionDenied() {
        return decision.authorization() == AuthorizationDecision.Authorization.ALLOW
                && decision.execution() == AuthorizationDecision.Execution.DENY;
    }

    /**
     * @return 外部副作用结果是否不确定（须进对账，§7.4）
     */
    public boolean requiresReconciliation() {
        return decision.requiresReconciliation();
    }
}
