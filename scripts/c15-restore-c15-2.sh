#!/usr/bin/env bash
# 恢复 C15-2 的改动（与 c15-split-c15-1.sh 的「摘除」互为逆操作）。
set -eu
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform

# ① 恢复两个被 python 摘改过的文件
cp /tmp/c15_final/WorkerChildAgentRunner.java \
   agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/WorkerChildAgentRunner.java
cp /tmp/c15_final/ToolInvocationGate.java \
   agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ToolInvocationGate.java

# ② 恢复端口层与执行器层（这两个文件在切分时被整体 checkout 回 HEAD）
python3 - <<'PY'
import pathlib

root = pathlib.Path('/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform')

# ── ChildAgentRunner.ChildAgentRequest ────────────────────────────
p = root / 'agent-ports/src/main/java/cn/studykid/multiagent/ports/orchestration/ChildAgentRunner.java'
s = p.read_text()

old = "import java.util.Map;"
new = "import java.time.Instant;\nimport java.util.Map;"
assert old in s
s = s.replace(old, new, 1)

old = """     * @param fencingToken      根 Run 的世代令牌（子执行与根 Run 同世代）
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
    }"""
new = """     * @param fencingToken      根 Run 的世代令牌（子执行与根 Run 同世代）
     * @param attempt           根 Run 的尝试号
     * @param rootDeadlineAt    <b>根 Run 的业务截止时刻</b>（绝对时刻）；
     *                          {@code null} = 根 Run 未声明截止时间（不限时）
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
            int attempt,
            @Nullable Instant rootDeadlineAt) {

        public ChildAgentRequest {
            if (timeoutMs <= 0) {
                // ⚠️ 时间预算必须为正：0 或负数意味着「一进来就超时」，
                //    那会让子执行永远无法开始，且失败原因难以分辨。
                throw new IllegalArgumentException("子执行时间预算必须为正数，收到: " + timeoutMs);
            }
        }

        /**
         * 兼容构造：<b>不知根 Run 的业务截止时刻</b>（第十五批之前的形态）。
         *
         * <p>⚠️ {@code rootDeadlineAt = null} 的语义是「根 Run 未声明截止时间 → 不限时」，
         * <b>不是</b>「跳过检查」——两者在行为上等价，但必须写清楚：
         * 否则调用方会以为「传 null = 跳过超时约束」，从而把一个
         * <b>没有强制力</b>的超时当成有强制力的。
         */
        public ChildAgentRequest(@NonNull String tenantId,
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
            this(tenantId, runId, invocationId, traceId, parentAgentId, parentAgentVersion,
                    targetAgentId, targetAgentVersion, input, depth, scope, timeoutMs,
                    fencingToken, attempt, null);
        }
    }"""
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)

# ── DelegationExecutor.DelegationCommand ──────────────────────────
p = root / 'agent-application/src/main/java/cn/studykid/multiagent/application/orchestration/DelegationExecutor.java'
s = p.read_text()

old = """     * @param remainingMillis     根 Run 剩余可用时间
     * @param fencingToken        根 Run 的世代令牌（子执行与根 Run 同世代）
     * @param attempt             根 Run 的尝试号
     */
    public record DelegationCommand(
            @NonNull String tenantId,
            @NonNull String runId,
            @Nullable String parentAgentId,
            @Nullable Integer parentAgentVersion,
            @Nullable String parentInvocationId,
            int parentDepth,
            @NonNull String targetAgentId,
            @Nullable Integer targetAgentVersion,
            @NonNull Map<String, Object> input,
            @NonNull String invocationId,
            @NonNull String traceId,
            long remainingMillis,
            long fencingToken,
            int attempt) {
    }"""
new = """     * @param remainingMillis     根 Run 剩余可用时间
     * @param fencingToken        根 Run 的世代令牌（子执行与根 Run 同世代）
     * @param attempt             根 Run 的尝试号
     * @param rootDeadlineAt      <b>根 Run 的业务截止时刻</b>（绝对时刻）；
     *                            {@code null} = 根 Run 未声明截止时间（不限时）
     */
    public record DelegationCommand(
            @NonNull String tenantId,
            @NonNull String runId,
            @Nullable String parentAgentId,
            @Nullable Integer parentAgentVersion,
            @Nullable String parentInvocationId,
            int parentDepth,
            @NonNull String targetAgentId,
            @Nullable Integer targetAgentVersion,
            @NonNull Map<String, Object> input,
            @NonNull String invocationId,
            @NonNull String traceId,
            long remainingMillis,
            long fencingToken,
            int attempt,
            @Nullable Instant rootDeadlineAt) {

        /**
         * 兼容构造：<b>不知根 Run 的业务截止时刻</b>（第十五批之前的形态）。
         *
         * <p>⚠️ {@code rootDeadlineAt = null} 的语义是「根 Run 未声明截止时间 → 不限时」，
         * <b>不是</b>「跳过检查」。这与根路径下
         * {@code RunClaimPort.LeasedTask.deadlineAt = null} 的语义完全一致。
         */
        public DelegationCommand(@NonNull String tenantId,
                                 @NonNull String runId,
                                 @Nullable String parentAgentId,
                                 @Nullable Integer parentAgentVersion,
                                 @Nullable String parentInvocationId,
                                 int parentDepth,
                                 @NonNull String targetAgentId,
                                 @Nullable Integer targetAgentVersion,
                                 @NonNull Map<String, Object> input,
                                 @NonNull String invocationId,
                                 @NonNull String traceId,
                                 long remainingMillis,
                                 long fencingToken,
                                 int attempt) {
            this(tenantId, runId, parentAgentId, parentAgentVersion, parentInvocationId,
                    parentDepth, targetAgentId, targetAgentVersion, input, invocationId,
                    traceId, remainingMillis, fencingToken, attempt, null);
        }
    }"""
assert old in s
s = s.replace(old, new, 1)

old = """                    effectiveTimeoutMs,
                    command.fencingToken(), command.attempt()));"""
new = """                    effectiveTimeoutMs,
                    command.fencingToken(), command.attempt(),
                    // ⚠️ 根 Run 的业务截止时刻**逐跳下传**（第十五批 · C15-2）：
                    //    它是整棵调用树**共享**的时间上界，必须以**绝对时刻**传递 ——
                    //    只传「本级剩余毫秒数」的话，每一跳都会重新按自己的声明起算，
                    //    根 Run 的剩余时间在嵌套路径上被重置成无限。
                    command.rootDeadlineAt()));"""
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)
print('C15-2 已恢复')
PY

# ③ 把测试类放回工作区
mv /tmp/c15_1/NestedDeadlinePropagationTest.java \
   agent-worker/src/test/java/cn/studykid/multiagent/worker/execute/NestedDeadlinePropagationTest.java

git status --short
