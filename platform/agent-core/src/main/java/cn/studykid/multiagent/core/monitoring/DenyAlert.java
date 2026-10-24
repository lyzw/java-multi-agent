package cn.studykid.multiagent.core.monitoring;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.ReasonCode;

/**
 * 近期拒绝告警条目（管理面 P4 运行监控）。
 *
 * <p>本条目是 {@code cap_decision_log} 中一条 DENY 记录的<b>监控投影</b>，
 * 只携带<b>分诊所需的最小字段</b>：严重级别、原因码、上下文标识与时刻。
 *
 * <p>⚠️ <b>刻意不携带业务参数与结果内容</b>：
 * 与 {@code DecisionAuditEntry} 同源，决策日志本身不含参数/载荷/结果（NFR-06），
 * 本投影也<b>不得</b>额外引入。故本记录里没有 payload、没有错误消息原文，
 * 只有 {@code toolId} / {@code principalId} 这类<b>标识</b>（非内容）。
 *
 * <p>⚠️ <b>「近期」是口径而非存储</b>：本条目不带查询窗口，
 * 窗口由端口查询参数承载（{@code since}）。把窗口写进条目会让
 * 同一批条目在不同查询下含义漂移。
 *
 * @param severity     严重级别（由 {@link DenySeverity#of(ReasonCode)} 定档）
 * @param reasonCode   权威原因码
 * @param principalId  主体标识；发布类事件为 {@code null}
 * @param toolId       工具标识；未涉及工具时为 {@code null}
 * @param runId        Run 标识；未绑定 Run 时为 {@code null}
 * @param invocationId 调用标识（与账本 operationId 同值）
 * @param occurredAt   发生时刻
 */
public record DenyAlert(
        @NonNull DenySeverity severity,
        @NonNull ReasonCode reasonCode,
        @Nullable String principalId,
        @Nullable String toolId,
        @Nullable String runId,
        @NonNull String invocationId,
        @NonNull Instant occurredAt) {

    public DenyAlert {
        if (severity == null) {
            throw new IllegalArgumentException("severity 不能为空");
        }
        if (reasonCode == null) {
            throw new IllegalArgumentException("reasonCode 不能为空");
        }
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException("invocationId 不能为空");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt 不能为空");
        }
    }

    /**
     * @return 是否为需要立即处置的严重拒绝
     */
    public boolean isCritical() {
        return severity == DenySeverity.CRITICAL;
    }
}
