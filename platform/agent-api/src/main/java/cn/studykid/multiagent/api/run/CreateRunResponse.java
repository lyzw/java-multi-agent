package cn.studykid.multiagent.api.run;

import org.jspecify.annotations.NonNull;

/**
 * Run 创建响应。
 *
 * @param runId        Run 标识
 * @param replayed     是否为幂等重放（true 表示未重复创建）
 * @param state        初始状态
 * @param correlationId 链路追踪标识
 */
public record CreateRunResponse(
        @NonNull String runId,
        boolean replayed,
        @NonNull String state,
        @NonNull String correlationId
) {

    public CreateRunResponse {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state 不能为空");
        }
    }
}