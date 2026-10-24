package cn.studykid.multiagent.ports.monitoring;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 严重拒绝告警外发端口（管理面 P5 · 真实告警分发通道最小实现）。
 *
 * <p><b>契约（三条红线）</b>：
 * <ol>
 *   <li><b>best-effort，不抛异常</b>：外发失败只留日志，绝不影响决策主链路 ——
 *       告警是观测的副产品，让「告警通道挂了」拖垮工具调用是本末倒置。</li>
 *   <li><b>不阻塞主链路</b>：实现必须异步（或瞬时完成）；同步等一个外部 HTTP
 *       等于把外部系统的可用性串进审计写入路径。</li>
 *   <li><b>载荷最小化</b>：只有定位信息（谁/什么/为什么/何时），没有参数、
 *       没有凭据、没有业务载荷 —— 外发目标系统的信任级别低于本平台。</li>
 * </ol>
 *
 * <p><b>触发口径</b>：仅 {@code DENY} 且原因码定档为 {@code CRITICAL}
 * （越权尝试 / 授权依据失效 / 副作用结果不确定）才外发。
 * WARNING / INFO 走常规审计查询，不值得一条外发消息的噪声成本。
 */
public interface AlertWebhookPort {

    /**
     * 外发一条严重拒绝告警。
     *
     * <p>⚠️ <b>实现不得抛出任何运行时异常</b>：调用方（审计写入路径）
     * 不会为此做任何补偿。
     *
     * @param alert 告警载荷（最小化字段集）
     */
    void onCriticalDeny(@NonNull CriticalDenyAlert alert);

    /**
     * 严重拒绝告警载荷。
     *
     * ⚠️ <b>刻意不含</b>：业务参数、凭据引用、闭包指纹 —— 外发目标
     * （群机器人 / 事件总线）的信任级别低于平台本体，字段集遵循
     * 「外发即脱敏」：没有可泄漏的字段，比事后过滤更可靠。
     *
     * @param tenantId     租户标识
     * @param principalId  被拒主体；未知时为 {@code null}
     * @param toolId       目标工具；未知时为 {@code null}
     * @param reasonCode   权威原因码（§5.7）
     * @param invocationId 调用标识（回到平台查详情的主键）
     * @param occurredAt   发生时刻
     */
    record CriticalDenyAlert(
            @NonNull String tenantId,
            @Nullable String principalId,
            @Nullable String toolId,
            @NonNull String reasonCode,
            @NonNull String invocationId,
            @NonNull Instant occurredAt) {
    }
}
