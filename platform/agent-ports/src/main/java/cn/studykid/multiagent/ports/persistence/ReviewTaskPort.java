package cn.studykid.multiagent.ports.persistence;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 人工审查队列端口（P2 · G3 内容级评估的「人审」半边）。
 *
 * <p><b>用途</b>：告警 / 异常 / 抽样自动产出的待审任务在此排队；
 * reviewer 认领 → 复盘（对话回放）→ 归因（rootCause）→ 关闭或驳回。
 *
 * <p><b>域边界</b>：队列放 {@code platform.review_task}（平台域、无 RLS）——
 * reviewer 是平台/租户管理员，跨租户可见；隔离靠授权
 * （读 {@code platform.resource.read} / 写 {@code platform.resource.manage}），
 * 不靠行策略。与 {@link AgentMetricPort} 的「读在租户域、审在平台域」边界一致。
 *
 * <p><b>无物理删除</b>：只走状态机流转（PENDING → IN_REVIEW → RESOLVED / DISMISSED），
 * 保留期清理走 {@code agent_retention} 带外通道（与 ErasurePort 纪律一致）。
 */
public interface ReviewTaskPort {

    /** 创建一条待审任务，返回 {@code task_id}。 */
    @NonNull String create(@NonNull ReviewTaskSpec spec);

    /** 认领：置 {@code IN_REVIEW} 并登记认领人。 */
    void assign(@NonNull String taskId, @NonNull String assignee);

    /** 归因关闭：置 {@code RESOLVED}，登记根因与备注。 */
    void resolve(@NonNull String taskId, @Nullable String rootCause, @Nullable String note);

    /** 驳回：置 {@code DISMISSED}（如误报），登记备注。 */
    void dismiss(@NonNull String taskId, @Nullable String note);

    /**
     * 该 Trace 是否已有未终结的任务（PENDING / IN_REVIEW）。
     *
     * <p><b>⚠️ 去重闸门</b>：告警联动与重试都会重复触发，
     * 没有这道闸就会出现「同一个问题在队列里躺 N 份」——
     * reviewer 挨个点开才发现是同一个，队列的信噪比直接崩掉。
     *
     * @return 存在未终结任务时返回 true
     */
    boolean hasOpenTask(@NonNull String traceId);

    /**
     * 按状态拉取队列（平台级跨租户）。
     *
     * @param status   为 null 时返回 PENDING / IN_REVIEW 两态（待处理视图）
     * @param tenantId 为 null 时不按租户过滤（平台全量）
     * @param limit    页大小上界（服务端再做一次钳制，防全表拖出）
     */
    @NonNull List<ReviewTaskView> list(@Nullable String status,
                                       @Nullable String tenantId,
                                       int limit);

    /** 创建任务的入参。字段合法性由实现侧校验（CHECK 约束兜底）。 */
    record ReviewTaskSpec(@NonNull String traceId,
                          @NonNull String tenantId,
                          @NonNull String runId,
                          @NonNull Source source,
                          @NonNull Priority priority,
                          @Nullable String note) {
    }

    /** 列表视图（不含 note 全文——详情走回放端点，列表只做分桶）。 */
    record ReviewTaskView(@NonNull String taskId,
                          @NonNull String traceId,
                          @NonNull String tenantId,
                          @NonNull String runId,
                          @NonNull String source,
                          @NonNull String priority,
                          @NonNull String status,
                          @Nullable String assignedTo,
                          @Nullable String rootCause,
                          @NonNull String createdAt) {
    }

    /** 任务来源：告警 / 异常 / 随机抽样 / L1 判分缺口 / 人工。 */
    enum Source {
        ALERT, ANOMALY, SAMPLE_RANDOM, L1_GAP, MANUAL
    }

    /** 优先级。 */
    enum Priority {
        HIGH, NORMAL, LOW
    }

    /** 归因根因（与 DDL CHECK 约束同清单）。 */
    enum RootCause {
        MODEL, PROMPT, TOOL, DATA, CONFIG, UNKNOWN
    }
}
