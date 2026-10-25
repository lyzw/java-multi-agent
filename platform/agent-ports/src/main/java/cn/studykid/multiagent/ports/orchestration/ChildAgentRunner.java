package cn.studykid.multiagent.ports.orchestration;

import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.ExecutionScope;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;

/**
 * 子 Agent 执行端口（多级 Agent v1.1 · P2-MA-01 / P2-MA-02）。
 *
 * <p>⚠️ <b>委派执行必须与工具执行一样受控</b>：本端口只提供「怎么把工作交给子 Agent」
 * 的执行能力，<b>不提供任何授权</b>。授权、边界与幂等判定在委派发起侧
 * （{@code DelegationGuard} + {@code DelegationExecutor}）完成，
 * 本端口只负责「已经被批准的那一次委派」的实际执行。
 *
 * <p>⚠️ <b>子 Agent 内部的工具调用仍走原闸门</b>（07 变更补充）：
 * 本端口的实现<b>不得</b>绕过 {@code ToolInvocationGate} 直接调用工具。
 */
public interface ChildAgentRunner {

    /**
     * 执行一次已批准的子 Agent 委派。
     *
     * @param request 子执行请求（含<b>子 Agent 自己的授权作用域</b>）
     * @return 子执行结果
     */
    @NonNull ChildAgentResult run(@NonNull ChildAgentRequest request);

    /**
     * 子执行请求。
     *
     * @param tenantId          租户标识
     * @param runId             根 Run 标识（预算/账本/事件共享域）
     * @param invocationId      委派调用标识（幂等键域）
     * @param traceId           链路追踪标识（父子共享）
     * @param parentAgentId     父 Agent 标识
     * @param parentAgentVersion 父 Agent 版本号
     * @param targetAgentId     目标 Agent 标识
     * @param targetAgentVersion 目标 Agent 的<b>固定版本号</b>
     * @param input             委派输入（<b>最小必要上下文</b>，不得是父的全部上下文）
     * @param depth             委派深度（直接子 = 2）
     * @param scope             子 Agent 的授权作用域（固定版本 + 闭包指纹）
     * @param timeoutMs         本级委派链路的<b>时间预算</b>（毫秒）——
     *                         子执行必须自行实施该预算（墙钟截止时间），
     *                         否则「超时」只是判定而没有强制力
     * @param fencingToken      根 Run 的世代令牌（子执行与根 Run 同世代）
     * @param attempt           根 Run 的尝试号
     */
    record ChildAgentRequest(
            @NonNull String tenantId,
            @NonNull String runId,
            @NonNull String invocationId,
            @NonNull String traceId,
            @NonNull String parentAgentId,
            int parentAgentVersion,
            @NonNull String targetAgentId,
            int targetAgentVersion,
            @NonNull Map<String, Object> input,
            int depth,
            @NonNull ExecutionScope scope,
            long timeoutMs,
            long fencingToken,
            int attempt) {

        public ChildAgentRequest {
            if (timeoutMs <= 0) {
                // ⚠️ 时间预算必须为正：0 或负数意味着「一进来就超时」，
                //    那会让子执行永远无法开始，且失败原因难以分辨。
                throw new IllegalArgumentException("子执行时间预算必须为正数，收到: " + timeoutMs);
            }
        }
    }

    /**
     * 子执行结果。
     *
     * @param status     终态（{@code UNKNOWN} = 结果不确定，须对账）
     * @param output     子 Agent 的输出文本（<b>不可信数据</b>，由调用方包裹后交父 Agent）
     * @param reasonCode 失败/终止原因码
     */
    record ChildAgentResult(
            @NonNull AgentInvocationStatus status,
            @NonNull String output,
            @Nullable String reasonCode) {

        public ChildAgentResult {
            if (status == null) {
                throw new IllegalArgumentException("子执行状态不能为空");
            }
            output = output == null ? "" : output;
        }

        /**
         * @param output 子执行输出
         * @return 成功结果
         */
        public static @NonNull ChildAgentResult succeeded(@NonNull String output) {
            return new ChildAgentResult(AgentInvocationStatus.SUCCEEDED, output, null);
        }

        /**
         * @param reasonCode 原因码
         * @param output     输出（可为诊断信息）
         * @return 确定失败结果
         */
        public static @NonNull ChildAgentResult failed(@NonNull String reasonCode,
                                                        @NonNull String output) {
            return new ChildAgentResult(AgentInvocationStatus.FAILED, output, reasonCode);
        }

        /**
         * @param reasonCode 原因码（通常是 {@code RUN_CANCELLED}）
         * @return 协作式取消结果
         *
         * <p>⚠️ <b>取消不是失败</b>：取消意味着「按协议停下」，
         * 与「执行了但出错」是两种事实，混在一起会让对账与审计失真。
         */
        public static @NonNull ChildAgentResult cancelled(@NonNull String reasonCode) {
            return new ChildAgentResult(AgentInvocationStatus.CANCELLED, "", reasonCode);
        }

        /**
         * @param reasonCode 原因码（通常是 {@code DELEGATION_TIMEOUT}）
         * @return 超时结果
         */
        public static @NonNull ChildAgentResult timedOut(@NonNull String reasonCode) {
            return new ChildAgentResult(AgentInvocationStatus.TIMED_OUT, "", reasonCode);
        }
    }
}
