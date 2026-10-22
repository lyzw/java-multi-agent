package cn.studykid.multiagent.core.cap;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 工具调用的确定结果事件（§6.1）。
 *
 * <p><b>存在理由</b>（§6.1）：承载 invocationId、确定结果、终态事件 id，
 * <b>供 POST 重试与崩溃恢复</b>。
 *
 * <p>⚠️ <b>关键不变式</b>：同一 invocation <b>最多一条</b>结果事件
 * （DB 层 {@code UNIQUE (tenant_id, invocation_id)} 兜底）。
 * 这条约束是 P3「确定成功但 POST 失败 → 只重试落账、<b>禁止</b>再次 execute」
 * 的存储层地基（§7.4 双维度结果）——
 * 没有它，「重试落账」随时可能退化成「重跑副作用」。
 *
 * <p>⚠️ {@code resultStatus=UNKNOWN} 表示<b>副作用已发生但响应丢失</b>：
 * 这类结果<b>不得</b>被自动重放，必须进对账（07 §7.4）。
 *
 * @param eventId          事件标识
 * @param tenantId         租户标识
 * @param runId            Run 标识
 * @param invocationId     调用标识（租户内唯一）
 * @param terminalEventId  终态事件 id
 * @param resultStatus     结果状态
 * @param resultPayload    结果载荷（JSON 文本）；UNKNOWN 时可为 {@code null}
 * @param reasonCode       原因码（失败/未知时使用）
 * @param createdAt        落盘时间
 */
public record ResultEvent(
        @NonNull String eventId,
        @NonNull String tenantId,
        @Nullable String runId,
        @NonNull String invocationId,
        @Nullable String terminalEventId,
        @NonNull Status resultStatus,
        @Nullable String resultPayload,
        @Nullable String reasonCode,
        @NonNull Instant createdAt
) {

    /** 结果状态。 */
    public enum Status {
        /** 明确成功。 */
        SUCCEEDED,
        /** 明确失败（可重试语义由调用方按原因码决定）。 */
        FAILED,
        /** <b>结果未知</b>：副作用已发生但响应丢失 —— 须进对账，禁止盲目重放。 */
        UNKNOWN
    }

    public ResultEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId 不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException("invocationId 不能为空");
        }
        if (resultStatus == null) {
            throw new IllegalArgumentException("resultStatus 不能为空");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt 不能为空");
        }
    }

    /**
     * @return 该结果是否为「不确定」，即必须进对账而不可自动重放
     */
    public boolean requiresReconciliation() {
        return resultStatus == Status.UNKNOWN;
    }
}
