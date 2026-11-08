package cn.studykid.multiagent.ports.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 对话回放读取端口（P1 · G1 闭环）。
 *
 * <p><b>用途</b>：为「回放端点」提供数据源 ——
 * {@code GET /api/runs/{runId}/transcript}（租户侧）与
 * {@code GET /api/platform/v1/runs/{runId}/transcript}（平台侧），
 * 让「这次辅导究竟说了什么」第一次变得可复盘。
 *
 * <p><b>⚠️ RLS</b>：{@code run_message} 与 {@code tool_operation} 都带 FORCE RLS，
 * 实现必须在事务内绑定租户上下文后读取。用系统连接直读会<b>静默返回空集</b>
 * （不是报错）——表现为「回放永远是空的」，且没有任何异常线索。
 *
 * <p><b>⚠️ 跨租户</b>：本端口的所有方法都<b>要求传入 tenantId</b>，
 * 且实现必须在 SQL 里显式携带 {@code tenant_id} 谓词（纵深防御：
 * 即便 RLS 策略被误删，SQL 谓词仍能挡住跨租户读取）。
 * 「按 runId 反查租户」不属于本端口职责（那是平台侧的编排动作）。
 */
public interface TraceReplayPort {

    /**
     * 拉取某 Run 的完整消息时间线。
     *
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @return 按插入序（{@code seq} 升序）排列的消息；无消息时返回空列表（<b>不是 null</b>）
     */
    @NonNull
    List<MessageView> messages(@NonNull String tenantId, @NonNull String runId);

    /**
     * 拉取某工具操作的完整内容（回放 TOOL 消息时按需展开）。
     *
     * @param tenantId    租户标识
     * @param operationId 工具操作标识
     * @return 工具操作详情；不存在或不属于该租户时返回 {@link Optional#empty()}
     */
    @NonNull
    Optional<Map<String, Object>> toolOperation(@NonNull String tenantId,
                                                 @NonNull String operationId);

    /**
     * 读取 Run 的元信息（回放头部）。
     *
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @return Run 元信息；不存在或不属于该租户时返回 {@link Optional#empty()}
     */
    @NonNull
    Optional<RunMeta> runMeta(@NonNull String tenantId, @NonNull String runId);

    /**
     * 读取 Run 的执行步骤（每轮计时）。
     *
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @return 步骤列表（按 step_number 升序）；无步骤时返回空列表
     */
    @NonNull
    List<StepView> steps(@NonNull String tenantId, @NonNull String runId);

    /**
     * 读取 Run 的不可变配置快照（回放要能回答「当时按哪版配置跑的」）。
     *
     * @param tenantId   租户标识
     * @param snapshotId 快照标识
     * @return 快照内容；不存在或不属于该租户时返回 {@link Optional#empty()}
     */
    @NonNull
    Optional<Map<String, Object>> snapshot(@NonNull String tenantId,
                                           @NonNull String snapshotId);

    /**
     * 读取 Run 的 token 与成本汇总。
     *
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @return 汇总；无用量记录时返回 {@link Optional#empty()}
     */
    @NonNull
    Optional<TokenTotal> tokenTotal(@NonNull String tenantId, @NonNull String runId);

    /**
     * 按 runId 反查所属租户（<b>仅供平台侧编排使用</b>）。
     *
     * <p>⚠️ <b>这是本端口唯一的「跨租户」能力，且只返回租户标识</b>：
     * 平台侧拿到的是<b>平台令牌</b>，不绑任何租户；
     * 若没有这一步，平台侧就无法定位要回放哪个租户的 Run。
     *
     * <p>⚠️ <b>调用方必须先过平台权限闸门</b>（{@code platform.resource.read}），
     * 本方法本身<b>不做鉴权</b>——它是数据访问原语，不是授权点。
     *
     * <p>⚠️⚠️ <b>数据来源是平台域宽表 {@code platform.agent_trace_metric}</b>，
     * 不是 {@code public.run}：后者带 FORCE RLS，应用角色 {@code app_agent}
     * 未绑租户时查询<b>静默返回 0 行</b>，而平台侧恰恰不知道该绑哪个租户。
     * 旧实现因此对所有 Run 返回 empty，平台回放端点恒 404（功能全程空转）。
     *
     * <p>⚠️ <b>语义收窄</b>：只有已被 P0 采集过指标的终态 Run 能仅凭 runId 反查；
     * 未采集的 Run 返回 empty，调用方须<b>显式传 tenantId</b>（平台端点已支持）。
     * 端点不得把这种 empty 呈现成「Run 不存在」——那是把「无法定位租户」伪装成事实。
     *
     * @param runId Run 标识
     * @return 所属租户；无法定位时返回 {@link Optional#empty()}
     */
    @NonNull
    Optional<String> resolveTenantId(@NonNull String runId);

    /**
     * Run 元信息（回放头部）。
     *
     * @param runId       Run 标识
     * @param traceId     链路标识
     * @param state       Run 状态
     * @param snapshotId  配置快照标识
     * @param createdAt   创建时刻
     * @param completedAt 完成时刻；非终态为 {@code null}
     * @param durationMs  耗时（毫秒）；未完成时为 {@code null}
     */
    record RunMeta(
            @NonNull String runId,
            @Nullable String traceId,
            @NonNull String state,
            @Nullable String snapshotId,
            @NonNull Instant createdAt,
            @Nullable Instant completedAt,
            @Nullable Long durationMs
    ) {
        public RunMeta {
            state = state == null || state.isBlank() ? "UNKNOWN" : state;
        }
    }

    /**
     * 执行步骤（回放用）。
     *
     * @param stepNumber  轮次号
     * @param stepType    步骤类型（THINK / TOOL）
     * @param durationMs  耗时（毫秒）
     */
    record StepView(int stepNumber, @NonNull String stepType, @Nullable Long durationMs) {
        public StepView {
            stepType = stepType == null || stepType.isBlank() ? "UNKNOWN" : stepType;
        }
    }

    /**
     * token 与成本汇总（回放用）。
     *
     * @param inputTokens  输入 token 合计
     * @param outputTokens 输出 token 合计
     * @param costAmount   成本合计（元）
     */
    record TokenTotal(long inputTokens, long outputTokens, @Nullable BigDecimal costAmount) {
    }

    /**
     * 一条回放消息。
     *
     * @param seq              插入序（回放排序依据，单调递增）
     * @param role             消息角色
     * @param stepNumber       所属轮次
     * @param content          内容（jsonb 语义，<b>已是摘要态</b>）
     * @param toolOperationId  关联的工具操作标识
     * @param modelId          模型标识
     * @param inputTokens      输入 token
     * @param outputTokens     输出 token
     * @param latencyMs        耗时（毫秒）
     * @param createdAt        产生时刻
     */
    record MessageView(
            long seq,
            @NonNull String role,
            @Nullable Integer stepNumber,
            @NonNull Map<String, Object> content,
            @Nullable String toolOperationId,
            @Nullable String modelId,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Long latencyMs,
            @NonNull Instant createdAt
    ) {
        public MessageView {
            role = role == null || role.isBlank() ? "UNKNOWN" : role;
            content = content == null ? Map.of() : Map.copyOf(content);
        }
    }
}
