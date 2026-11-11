package cn.studykid.multiagent.ports.observer;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * 执行循环观测者（观测 Run 执行循环的生命周期）。
 *
 * <p><b>注入点</b>：{@code RunExecutionLoop.execute()} 各阶段。
 *
 * <p><b>关键纪律</b>：
 * <ul>
 *   <li>观测者异常<b>不得影响主流程</b>——异常只记 WARN，不冒泡；</li>
 *   <li>观测数据中的任何文本必须过 {@code CredentialRedactor} 脱敏；</li>
 *   <li>工具结果中的敏感信息只写模式代号，不写原文。</li>
 * </ul>
 *
 * <p><b>观测者链</b>：多个观测者按 Spring {@code @Order} 顺序执行。
 */
public interface ExecutionObserver {

    /**
     * 执行循环开始时触发。
     *
     * @param tenantId  租户标识
     * @param runId     Run 标识
     * @param maxSteps  最大轮次（来自 Run 配置快照）
     */
    void onLoopStart(@NonNull String tenantId, @NonNull String runId, int maxSteps);

    /**
     * 每轮迭代开始时触发。
     *
     * @param tenantId  租户标识
     * @param runId     Run 标识
     * @param step      当前轮次（从 1 开始）
     */
    void onStepStart(@NonNull String tenantId, @NonNull String runId, int step);

    /**
     * 模型调用完成后触发。
     *
     * @param tenantId    租户标识
     * @param runId       Run 标识
     * @param step        当前轮次
     * @param response    模型响应
     * @param latency     调用耗时
     */
    void onModelCallComplete(@NonNull String tenantId, @NonNull String runId, int step,
                             @NonNull ModelResponse response, @NonNull Duration latency);

    /**
     * 工具调用完成后触发。
     *
     * @param tenantId        租户标识
     * @param runId           Run 标识
     * @param step            当前轮次
     * @param toolResult      工具调用汇总结果（中性表示）
     * @param latency         工具调用总耗时
     */
    void onToolCallComplete(@NonNull String tenantId, @NonNull String runId, int step,
                            @NonNull ToolCallSummary toolResult, @NonNull Duration latency);

    /**
     * 裁剪发生时触发。
     *
     * @param tenantId      租户标识
     * @param runId         Run 标识
     * @param droppedRounds 被丢弃的轮数
     * @param droppedTokens 被丢弃内容的估算 token 数
     * @param droppedTools  被丢弃轮次里调用过的工具名（去重保序）
     */
    void onContextTrimmed(@NonNull String tenantId, @NonNull String runId,
                          int droppedRounds, int droppedTokens,
                          @NonNull List<String> droppedTools);

    /**
     * 上下文完成统一装配/压缩后触发（M6 契约；M7 起由统一装配入口调用）。
     *
     * <p>⚠️ <b>default 空实现是兼容性硬要求</b>：M6 只冻结契约、不接线，
     * 既有 {@link ExecutionObserver} 实现者零改动即可编译，运行时行为与今天逐位一致。
     * 旧 {@link #onContextTrimmed} <b>保留且不改语义</b>；[D11] 关闭态仍走旧路径（CH-8）。
     *
     * <p>⚠️ M7 接线纪律：开启态调用本方法时，{@code droppedTokens} 必须是
     * {@code max(0, beforeTokens - afterTokens)}，由调用方算好后传入；端口层不做
     * 隐式推导，避免「看到有数字就以为算对了」。需要轮次/工具名维度的观测者
     * 仍应消费旧 {@code onContextTrimmed}，本方法不伪造这两个维度。
     *
     * @param tier          触发水位档位（0=W0 未压缩，1/2/3=W1/W2/W3）
     * @param beforeTokens  压缩前全窗口估算 token
     * @param afterTokens   压缩后全窗口估算 token
     * @param droppedTokens 净减少 token（调用方 clamp 到 ≥0）
     * @param fallback      是否从 W3 摘要失败回落到 W2
     */
    default void onContextCompacted(@NonNull String tenantId, @NonNull String runId,
                                    int tier, int beforeTokens, int afterTokens,
                                    int droppedTokens, boolean fallback) {
        // M6 contract-only：默认无副作用，M7 起由需要该事件的观测者显式覆写。
    }

    /**
     * 执行循环完成时触发（无论成功/失败/取消）。
     *
     * @param tenantId   租户标识
     * @param runId      Run 标识
     * @param outcome    执行结局（中性表示）
     * @param totalSteps 实际执行的总轮次
     */
    void onLoopComplete(@NonNull String tenantId, @NonNull String runId,
                        @NonNull LoopOutcome outcome, int totalSteps);

    // ── 中性值对象（避免 ports 层依赖 worker 模块） ─────────────────────

    /**
     * 工具调用汇总结果（中性表示）。
     *
     * <p>从 {@code ToolInvocationGate.ToolInvocationResult} 适配而来，
     * 避免 ports 层依赖 worker 模块。
     */
    final class ToolCallSummary {
        private final List<String> succeededTools;
        private final List<String> failedTools;
        private final List<String> rejectedTools;
        private final boolean unknownResult;
        private final @Nullable String unknownOperationId;

        public ToolCallSummary(
                @NonNull List<String> succeededTools,
                @NonNull List<String> failedTools,
                @NonNull List<String> rejectedTools,
                boolean unknownResult,
                @Nullable String unknownOperationId) {
            this.succeededTools = List.copyOf(succeededTools);
            this.failedTools = List.copyOf(failedTools);
            this.rejectedTools = List.copyOf(rejectedTools);
            this.unknownResult = unknownResult;
            this.unknownOperationId = unknownOperationId;
        }

        public @NonNull List<String> succeededTools() { return succeededTools; }
        public @NonNull List<String> failedTools() { return failedTools; }
        public @NonNull List<String> rejectedTools() { return rejectedTools; }
        public boolean unknownResultOccurred() { return unknownResult; }
        public @Nullable String unknownOperationId() { return unknownOperationId; }
    }

    /**
     * 执行循环结局（中性表示）。
     *
     * <p>与 {@code RunExecutionLoop.ExecutionOutcome} 对齐，
     * 避免 ports 层依赖 worker 模块。
     */
    final class LoopOutcome {
        private final Kind kind;
        private final @Nullable String detail;

        public LoopOutcome(@NonNull Kind kind, @Nullable String detail) {
            this.kind = kind;
            this.detail = detail;
        }

        public @NonNull Kind kind() { return kind; }
        public @Nullable String detail() { return detail; }
        public boolean retryable() { return kind == Kind.RETRYABLE_FAILURE; }

        public enum Kind {
            SUCCEEDED,
            FAILED,
            RETRYABLE_FAILURE,
            UNKNOWN_RESULT,
            SKIPPED_STALE_GENERATION,
            CANCELLED,
            TENANT_BLOCKED,
            PARKED
        }
    }
}
