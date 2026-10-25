package cn.studykid.multiagent.core.orchestration;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 一次委派调用的运行记录（06 变更补充「关系数据」· FR-022）。
 *
 * <p>⚠️ <b>不保存不必要的敏感输入/输出明文</b>：本对象只保留
 * {@code inputDigest} / {@code outputDigest}（摘要），不保存 Prompt、工具入参、
 * 子 Agent 输出的原文。需要原文时引用受控存储，并按原读取授权规则处理。
 *
 * <p>⚠️ <b>fencingToken 是接管安全的线性化依据</b>：Worker 崩溃后由新世代接管，
 * 旧世代带着过期 token 提交结果必须失败（MA-T12）。
 *
 * @param invocationId        调用标识（幂等键域：重复投递不重复创建逻辑调用）
 * @param tenantId            租户标识
 * @param runId               所属根 Run（预算/超时在整棵调用树共享）
 * @param parentInvocationId  父调用标识；<b>根调用为空</b>
 * @param parentAgentId       父 Agent 标识
 * @param parentAgentVersion  父 Agent 的固定版本号
 * @param targetAgentId       目标 Agent 标识
 * @param targetAgentVersion  目标 Agent 的固定版本号（委派目标必须固定 release）
 * @param status              当前状态
 * @param depth               委派深度（根调用 = 1，直接子调用 = 2）
 * @param traceId             链路追踪标识（父子调用共享同一 traceId）
 * @param inputDigest         委派输入摘要（非明文）
 * @param outputDigest        委派输出摘要（非明文）
 * @param startedAt           开始时间
 * @param finishedAt          结束时间；未结束时为 {@code null}
 * @param reasonCode          失败/终止原因码（稳定机器可读）
 * @param fencingToken        世代令牌（接管安全）
 */
public record AgentInvocation(
        @NonNull String invocationId,
        @NonNull String tenantId,
        @NonNull String runId,
        @Nullable String parentInvocationId,
        @NonNull String parentAgentId,
        int parentAgentVersion,
        @NonNull String targetAgentId,
        int targetAgentVersion,
        @NonNull AgentInvocationStatus status,
        int depth,
        @NonNull String traceId,
        @Nullable String inputDigest,
        @Nullable String outputDigest,
        @Nullable Instant startedAt,
        @Nullable Instant finishedAt,
        @Nullable String reasonCode,
        long fencingToken
) {

    public AgentInvocation {
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException("调用标识不能为空");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("租户标识不能为空");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Run 标识不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("调用状态不能为空");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("追踪标识不能为空");
        }
        if (depth <= 0) {
            throw new IllegalArgumentException("委派深度必须为正数，收到: " + depth);
        }
        if (parentAgentVersion <= 0 || targetAgentVersion <= 0) {
            throw new IllegalArgumentException("Agent 版本号必须为正数");
        }
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("世代令牌必须为正数，收到: " + fencingToken);
        }
    }

    /**
     * @return 是否为根调用（无父调用）
     */
    public boolean isRoot() {
        return parentInvocationId == null || parentInvocationId.isBlank();
    }
}
