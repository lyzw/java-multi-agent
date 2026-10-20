package cn.studykid.multiagent.ports.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Run 结果查询端口（P4-D10 / G5）。
 *
 * <p><b>用途</b>：为「运行结果读取端点」（{@code GET /api/runs/{runId}/result}）提供数据源。
 * 与 {@link RunQueryPort} 的分工：后者读<b>状态快照</b>（进度页），
 * 本端口读<b>结果内容</b>（结果页）。
 *
 * <p>⚠️ <b>实现约束（全部来自 P4-D10 §2.3 契约冻结，逐条可断言）</b>：
 * <ol>
 *   <li><b>{@code tenant_id} 条件不可删除</b>——即便 RLS 已隔离，
 *       应用侧条件仍是第一道防线；若某天 RLS 策略被误删，显式条件仍能挡住跨租户读取。</li>
 *   <li><b>不得返回 {@code input_payload}</b>——它含学生提交的题目与解答，属敏感载荷。</li>
 *   <li><b>失败时不得返回 {@code result_payload} 原文</b>——
 *       失败载荷的形状是 {@code {"reason":"<内部失败原因>"}}，
 *       原因文本属内部错误细节（与 {@code error_message} 同级处置）。</li>
 *   <li><b>不得返回 {@code error_message}</b>——只返回 {@code error_code}。</li>
 *   <li><b>不得返回任何引用/页码/出处字段</b>——C5 硬红线（DEC-5 不做 RAG）。</li>
 *   <li><b>集合字段恒为列表</b>——空即空列表，<b>不可为 null</b>：
 *       前端必须能区分「没有数据」与「接口坏了」。</li>
 *   <li><b>待核对分区恒存在</b>——P0-D00 §3.4 要求未决 UNKNOWN
 *       <b>必须独立暴露</b>，不得「有才显示」。</li>
 * </ol>
 */
public interface ResultQueryPort {

    /**
     * 读取 Run 的结果。
     *
     * @param tenantId 租户标识；<b>必须来自认证上下文</b>
     * @param runId    Run 标识
     * @return 存在且属于该租户时返回结果；不存在或不属于该租户时返回 {@code null}
     */
    @Nullable RunResult findResult(@NonNull String tenantId, @NonNull String runId);

    /**
     * Run 结果快照。
     *
     * @param runId        Run 标识
     * @param state        Run 状态（{@code run_state} 枚举字符串）
     * @param completedAt  完成时刻；<b>非终态为 {@code null}</b>（不可退化为 EPOCH）
     * @param errorCode    失败原因<b>码</b>；成功或未失败为 {@code null}（<b>不是</b>错误原文）
     * @param explanation  讲解文本；<b>仅成功时有值</b>，失败时为 {@code null}
     * @param weaknesses   错因与知识点（结构化）；空即空列表
     * @param steps        解题步骤；空即空列表
     * @param reconciliation 待核对分区；<b>恒非 null</b>
     */
    record RunResult(
            @NonNull String runId,
            @NonNull String state,
            @Nullable Instant completedAt,
            @Nullable String errorCode,
            @Nullable String explanation,
            @NonNull List<Weakness> weaknesses,
            @NonNull List<Step> steps,
            @NonNull Reconciliation reconciliation
    ) {
        public RunResult {
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("runId 不能为空");
            }
            if (state == null || state.isBlank()) {
                // ⚠️ 空状态无业务含义：前端无法映射 state → 终态判定失效
                throw new IllegalArgumentException("state 不能为空");
            }
            weaknesses = weaknesses == null ? List.of() : List.copyOf(weaknesses);
            steps = steps == null ? List.of() : List.copyOf(steps);
            reconciliation = reconciliation == null
                    ? new Reconciliation(false, List.of())
                    : reconciliation;
        }
    }

    /**
     * 错因与知识点。
     *
     * @param knowledgePoint 知识点（受 V2 枚举约束，非自由文本）
     * @param errorType      错误类型（受 V2 枚举约束）
     * @param confidence     置信度；<b>仅供参考，不参与任何判定</b>
     */
    record Weakness(
            @NonNull String knowledgePoint,
            @NonNull String errorType,
            @Nullable BigDecimal confidence
    ) {
        public Weakness {
            if (knowledgePoint == null || knowledgePoint.isBlank()) {
                throw new IllegalArgumentException("knowledgePoint 不能为空");
            }
            if (errorType == null || errorType.isBlank()) {
                throw new IllegalArgumentException("errorType 不能为空");
            }
        }
    }

    /**
     * 解题步骤。
     *
     * @param number   步骤序号（从 1 开始）
     * @param state    步骤状态
     * @param stepType 步骤类型
     * @param output   步骤产出；无内容为 {@code null}
     */
    record Step(
            int number,
            @NonNull String state,
            @NonNull String stepType,
            @Nullable String output
    ) {
        public Step {
            if (number <= 0) {
                throw new IllegalArgumentException("number 必须 >= 1");
            }
            if (state == null || state.isBlank()) {
                throw new IllegalArgumentException("state 不能为空");
            }
            if (stepType == null || stepType.isBlank()) {
                throw new IllegalArgumentException("stepType 不能为空");
            }
        }
    }

    /**
     * 待核对分区（P0-D00 §3.4）。
     *
     * <p>⚠️ <b>权威来源是 {@code tool_operation.state='UNKNOWN'}，不是 {@code run.state}</b>：
     * 工具结果未知时，Run **不会进入终态**，它停留在 {@code RUNNING} 等待对账
     * （重投 = 重复副作用 = Sev-0，故一律 ack 丢弃）。
     * {@code run_state} 枚举中<b>不存在</b> {@code RECONCILIATION_REQUIRED}。
     *
     * @param pending    是否存在未决操作
     * @param operations 未决操作明细
     */
    record Reconciliation(
            boolean pending,
            @NonNull List<UnknownOperation> operations
    ) {
        public Reconciliation {
            operations = operations == null ? List.of() : List.copyOf(operations);
        }
    }

    /**
     * 未决（UNKNOWN）工具操作。
     *
     * @param operationId 操作标识
     * @param toolName    工具名
     * @param state       操作状态（当前仅 {@code UNKNOWN}）
     */
    record UnknownOperation(
            @NonNull String operationId,
            @NonNull String toolName,
            @NonNull String state
    ) {
        public UnknownOperation {
            if (operationId == null || operationId.isBlank()) {
                throw new IllegalArgumentException("operationId 不能为空");
            }
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName 不能为空");
            }
            if (state == null || state.isBlank()) {
                throw new IllegalArgumentException("state 不能为空");
            }
        }
    }
}
