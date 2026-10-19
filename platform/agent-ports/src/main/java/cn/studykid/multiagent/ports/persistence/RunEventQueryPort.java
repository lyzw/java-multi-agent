package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Run 事件查询端口（P2-D05 / FR-014）。
 *
 * <p><b>用途</b>：SSE 事件流的唯一数据来源。
 * 刻意<b>只读</b>——事件由执行侧写入，本端口不提供写方法。
 * 写入路径属 Worker 与 Run 创建事务，不在查询端口中开口子。
 *
 * <p><b>为什么「读数据库」而不是「订阅内存事件」</b>（P2-D05 §3.3）：
 * 多实例部署时内存事件不跨实例，Worker 写在 A 实例、订阅方连在 B 实例，
 * 事件永远收不到 —— 这是<b>静默失败</b>，不报错只是丢事件。
 * 直接读库意味着「所见即所存」，无补偿问题。
 *
 * <p>⚠️ <b>实现约束</b>：
 * <ul>
 *   <li>SQL <b>必须显式携带 {@code tenant_id} 条件</b>。
 *       即便 RLS 已隔离，应用侧条件仍是第一道防线；
 *       若某天 RLS 策略被误删，显式条件仍能挡住跨租户读取。</li>
 *   <li>必须<b>按 {@code sequence_no} 升序</b>返回 —— 续传依赖单调有序。</li>
 *   <li>不得返回 Prompt 原文或学生可识别信息（载荷最小化）。</li>
 * </ul>
 */
public interface RunEventQueryPort {

    /**
     * 读取指定游标之后的事件。
     *
     * <p><b>续传语义</b>：{@code afterSequenceNo} 为「已消费到的序号」，
     * 返回 {@code sequence_no > afterSequenceNo} 的事件。
     * 首次订阅传 {@code 0} 表示从头读取。
     *
     * @param tenantId  租户标识；<b>必须来自认证上下文</b>
     * @param runId     Run 标识
     * @param afterSequenceNo 已消费到的序号（独占下界）
     * @param limit     单次最大返回条数，防止长 Run 一次性拉取过量
     * @return 按序号升序的事件列表
     */
    @NonNull List<RunEvent> findAfter(@NonNull String tenantId,
                                       @NonNull String runId,
                                       long afterSequenceNo,
                                       int limit);

    /**
     * 判断 Run 是否存在且属于该租户。
     *
     * <p>⚠️ <b>存在的理由是「区分 404 与空事件」</b>：
     * 一个刚创建的 Run 尚无事件，与一个不存在的 Run 都返回空列表，
     * 若不查存在性，攻击者可通过「空 vs 非空」差异推断 Run 是否存在
     * ——这正是 P2-D05 §2 D-3 要防的探测通道。
     *
     * @return 存在且属于该租户时返回当前状态，否则 {@code null}
     */
    @Nullable String findState(@NonNull String tenantId, @NonNull String runId);

    /**
     * 单条 Run 事件。
     *
     * @param sequenceNo 单调递增序号，即 SSE 的 {@code id}
     * @param eventType  事件类型
     * @param payload    载荷；<b>不含 Prompt 与学生可识别信息</b>
     * @param createdAt  发生时刻
     */
    record RunEvent(
            long sequenceNo,
            @NonNull String eventType,
            @NonNull Map<String, Object> payload,
            @NonNull Instant createdAt
    ) {
        public RunEvent {
            if (sequenceNo <= 0) {
                // ⚠️ 表上有 CHECK (sequence_no > 0)。
                //    在类型层面拦住，避免非法值流到 SQL 层才暴露。
                throw new IllegalArgumentException("sequenceNo 必须为正数: " + sequenceNo);
            }
            eventType = eventType == null ? "UNKNOWN" : eventType;
            payload = payload == null ? Map.of() : Map.copyOf(payload);
            createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        }
    }
}