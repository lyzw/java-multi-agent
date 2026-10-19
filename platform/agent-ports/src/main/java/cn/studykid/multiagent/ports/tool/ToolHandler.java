package cn.studykid.multiagent.ports.tool;

import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.config.ToolRiskLevel;

/**
 * 工具处理器端口（Tool SPI）。
 *
 * <p><b>不可妥协的安全边界</b>（P0-D03 §2 / FR-008）：
 * 模型<b>不得</b>绕过服务端授权直接执行工具。执行链必须为：
 * <pre>
 *   模型请求调用 → Schema 校验 → <b>执行时重新授权</b> → 审批检查 → 预算预留 → 执行 → 账本写入
 * </pre>
 * 授权检查发生在<b>执行时刻</b>而非快照创建时刻：租户权限可能已被撤销，
 * 当前授权必须在旧 Run 恢复与工具执行时重新验证（P0-D03 §4 红线 9）。
 *
 * <p>ADR-011 关联：Spring AI 2.0 的自动工具执行循环会绕过上述闸门，
 * 因此 infrastructure 层适配器<b>必须禁用</b>其自动执行路径，改由本端口驱动。
 */
public interface ToolHandler {

    /** @return 工具名，与 {@code agent.tool.risk-level-map} 的键一致。 */
    @NonNull String toolName();

    /** @return 输入 JSON Schema，用于执行前校验。 */
    @NonNull String inputSchema();

    /** @return 风险级别，决定审批要求与是否需幂等键。 */
    @NonNull ToolRiskLevel riskLevel();

    /**
     * 校验输入。Schema 错误必须拒绝，不得「尽力解析」。
     *
     * @throws ToolValidationException 校验失败
     */
    void validate(@NonNull Map<String, Object> arguments);

    /**
     * 执行工具。<b>调用方保证已完成授权、审批与预算检查</b>。
     *
     * @param context 执行上下文（含 tenantId、runId、principal）
     * @return 执行结果
     * @throws ToolExecutionException 执行失败；结果不确定时须抛
     *         {@link ToolExecutionException#unknownResult()} 以驱动 UNKNOWN 状态
     */
    @NonNull ToolResult execute(@NonNull ToolExecutionContext context,
                                 @NonNull Map<String, Object> arguments);

    /**
     * 对账查询：用于 UNKNOWN 状态下确定该逻辑操作的实际结果。
     *
     * <p>返回 {@code null} 表示<b>下游不可查</b>，须转人工队列而非重试。
     */
    default @Nullable ToolResult reconcile(@NonNull ToolExecutionContext context,
                                           @NonNull String idempotencyKey) {
        return null;
    }

    /** 执行上下文。所有授权判定的依据。 */
    record ToolExecutionContext(
            @NonNull String tenantId,
            @NonNull String runId,
            @NonNull String principalId,
            @NonNull String idempotencyKey,
            int attemptNumber
    ) {
    }

    /** 工具执行结果。 */
    record ToolResult(@NonNull String output, @NonNull Map<String, Object> metadata) {
        public ToolResult {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public static @NonNull ToolResult of(@NonNull String output) {
            return new ToolResult(output, Map.of());
        }
    }
}
