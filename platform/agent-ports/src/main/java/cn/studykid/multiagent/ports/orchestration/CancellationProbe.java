package cn.studykid.multiagent.ports.orchestration;

import org.jspecify.annotations.NonNull;

/**
 * Run 取消探针（多级 Agent v1.1 · P2-MA-02 · MA-T11）。
 *
 * <p>⚠️ <b>取消必须传播到子执行内部</b>：父 Run 被取消时，
 * 正在运行的子 Agent 若继续跑，它调用的工具仍会产生副作用 ——
 * 「取消」就退化成了「不再发起新委派」，而已启动的子调用仍在烧预算、做副作用。
 *
 * <p>⚠️ <b>取消不等于撤销</b>：协作式取消只保证「停止启动新的副作用」，
 * <b>不能保证</b>已发出的外部副作用被撤销（07 变更补充「状态与失败语义」）。
 * 已启动的 ToolOperation 仍须进入可解释状态（成功 / 失败 / 待对账）。
 *
 * <p>⚠️ <b>探针不得抛异常</b>：实现方读不到取消状态时必须返回 {@code false}
 * 并告警，<b>不得</b>抛异常把取消变成「整个 Run 崩掉」。
 */
@FunctionalInterface
public interface CancellationProbe {

    /**
     * @param tenantId 租户标识
     * @param runId    根 Run 标识（取消是 Run 级状态，父子共享）
     * @return 该 Run 是否已取消
     */
    boolean isCancelled(@NonNull String tenantId, @NonNull String runId);
}
