package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Run 状态查询端口（P4-D09 / G3）。
 *
 * <p><b>用途</b>：为「运行状态查询端点」（{@code GET /api/runs/{runId}}）提供数据源。
 * 与 {@link RunEventQueryPort} 的分工：后者读<b>事件流</b>（SSE 的唯一来源），
 * 本端口读<b>Run 的当前状态快照</b>。
 *
 * <p><b>为什么两者都需要</b>（实测）：
 * {@code run_event} 表<b>只有 {@code RUN_CREATED} 由生产代码写入</b>，Worker 不写该表
 * → 进度页<b>长期拿不到执行中事件</b>，只能靠 SSE 推断状态。
 * Run 的权威状态在 {@code run.state} 列，本端口是它的唯一读取出口。
 *
 * <p>⚠️ <b>实现约束</b>：
 * <ul>
 *   <li>SQL <b>必须显式携带 {@code tenant_id} 条件</b>——即便 RLS 已隔离，
 *       应用侧条件仍是第一道防线；若某天 RLS 策略被误删，显式条件仍能挡住跨租户读取。</li>
 *   <li><b>不得返回 {@code error_message} 原文</b>——只返回 {@code error_code}，
 *       避免内部细节泄漏到客户端。</li>
 *   <li><b>不得返回 {@code input_payload}</b>——它含学生提交的题目与解答，
 *       属敏感载荷，只读状态不需要。</li>
 * </ul>
 */
public interface RunQueryPort {

    /**
     * 读取 Run 的当前状态。
     *
     * @param tenantId 租户标识；<b>必须来自认证上下文</b>
     * @param runId    Run 标识
     * @return 存在且属于该租户时返回状态快照；不存在或不属于该租户时返回 {@code null}
     */
    @Nullable RunStatus findStatus(@NonNull String tenantId, @NonNull String runId);

    /**
     * Run 状态快照。
     *
     * @param runId       Run 标识
     * @param state       Run 状态（{@code run_state} 枚举字符串）
     * @param createdAt   创建时刻
     * @param completedAt 完成时刻；非终态为 {@code null}
     * @param attempt     领取次数
     * @param errorCode   失败原因码；成功或未失败为 {@code null}
     */
    record RunStatus(
            @NonNull String runId,
            @NonNull String state,
            @NonNull Instant createdAt,
            @Nullable Instant completedAt,
            int attempt,
            @Nullable String errorCode
    ) {
        public RunStatus {
            if (state == null || state.isBlank()) {
                // ⚠️ 空状态无业务含义：静默接受会让前端拿到无法映射的 state，
                //    终态判定失效 → 轮询永不停止。
                throw new IllegalArgumentException("state 不能为空");
            }
        }
    }
}
