package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * UNKNOWN 操作查询与人工处置端口（P4-D13 / G9 · FE-01 §P9 UNKNOWN 处置台）。
 *
 * <p><b>⛔ 本端口刻意不提供「重试」</b>：
 * FE-01 §P9 明确「必须显式区分 UNKNOWN / RECONCILIATION_REQUIRED，
 * 禁止提供一键重试（<b>盲目重放 = Sev-0</b>）」。
 * UNKNOWN 的含义恰恰是「<b>不知道副作用是否已经发生</b>」——
 * 此时重放就是在「可能已经改了学生数据」的基础上再改一次。
 * 三个处置取值全部是<b>终态归档</b>，没有一条会把操作退回 {@code PENDING}。
 */
public interface UnknownOperationPort {

    /**
     * 查询操作列表（游标分页）。
     *
     * @param state 状态过滤；{@code null} 表示不限
     */
    @NonNull Page<OperationItem> list(@NonNull String tenantId,
                                      @Nullable String state,
                                      @Nullable String cursor,
                                      int limit);

    /**
     * 查询单条操作。
     *
     * @return 存在且属于该租户时返回；否则 {@code null}
     */
    @Nullable OperationItem find(@NonNull String tenantId, @NonNull String operationId);

    /**
     * 人工处置 UNKNOWN 操作。
     *
     * @param operatorId 处置人标识（来自认证上下文，<b>不得</b>取自请求体）
     * @param disposition 取值见 {@link Disposition}
     * @param note       处置说明
     * @throws NotInUnknownStateException 操作当前不处于 {@code UNKNOWN} 态
     */
    @NonNull DispositionResult dispose(@NonNull String tenantId,
                                      @NonNull String operationId,
                                      @NonNull Disposition disposition,
                                      @NonNull String operatorId,
                                      @Nullable String note);

    /**
     * 处置方式。
     *
     * <p>⚠️ <b>枚举里刻意没有 RETRY</b>——见类型注释。
     * 新增值等于新增一个「盲目重放」的入口，必须走独立的 Sev-0 评审。
     */
    enum Disposition {
        /** 人工核实：副作用<b>已发生</b>。 */
        RECONCILED_SUCCEEDED,
        /** 人工核实：副作用<b>未发生</b>。 */
        RECONCILED_ABSENT,
        /** <b>无法判定</b>，人工结案归档。 */
        MANUAL_DISPOSED
    }

    /** 处置结果。 */
    record DispositionResult(
            @NonNull String operationId,
            @NonNull String newState,
            @NonNull String disposedBy,
            @NonNull Instant disposedAt
    ) {
    }

    /**
     * 操作视图。
     *
     * <p>⛔ <b>不含 {@code tool_arguments} 与 {@code result_payload}</b>——
     * 工具入参含学生提交内容，回包含内部细节；处置台只需要「发生了什么」，
     * 不需要「参数是什么」。
     */
    record OperationItem(
            @NonNull String operationId,
            @NonNull String runId,
            @NonNull String toolName,
            @NonNull String riskLevel,
            @NonNull String state,
            @NonNull Instant createdAt,
            @NonNull Instant updatedAt,
            @Nullable String reconciliationSource,
            boolean manuallyDisposed
    ) {
    }

    /** 操作不处于 {@code UNKNOWN} 态（已自动对账或已被处置）。 */
    class NotInUnknownStateException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String currentState;

        public NotInUnknownStateException(String currentState) {
            super("操作当前状态为 " + currentState + "，不是 UNKNOWN，无法人工处置");
            this.currentState = currentState;
        }

        public @NonNull String currentState() {
            return currentState;
        }
    }

    /**
     * 操作不存在或不属于该租户。
     *
     * <p>⚠️ 与 {@link ApprovalQueryPort.ApprovalNotFoundException} 同理：
     * 定义在端口层，避免 {@code agent-api} 反向依赖基础设施实现。
     */
    class OperationNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public OperationNotFoundException(String operationId) {
            super("操作不存在: " + operationId);
        }
    }
}