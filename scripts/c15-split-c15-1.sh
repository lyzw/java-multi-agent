#!/usr/bin/env bash
# 把工作区切成「仅 C15-1」状态（C15-2 的改动临时摘掉），用于分批提交。
set -eu
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform

RUNNER=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/WorkerChildAgentRunner.java
GATE=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ToolInvocationGate.java
mkdir -p /tmp/c15_final /tmp/c15_1
cp "$RUNNER" /tmp/c15_final/WorkerChildAgentRunner.java
cp "$GATE" /tmp/c15_final/ToolInvocationGate.java

python3 - <<'PY'
import pathlib

root = pathlib.Path('/Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform')
runner = root / 'agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/WorkerChildAgentRunner.java'
gate = root / 'agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ToolInvocationGate.java'

# ── ToolInvocationGate：摘掉 C15-2 的「传根截止时刻」 ──────────────
s = gate.read_text()
old = """                        task.fencingToken(),
                        task.attempt(),
                        // ⚠️ 根 Run 的业务截止时刻**逐跳下传**（第十五批 · C15-2）：
                        //    task.deadlineAt 在根路径上就是 run.deadline_at，
                        //    在嵌套路径上由 WorkerChildAgentRunner 原样传下来 ——
                        //    它是整棵调用树共享的**绝对**时间上界。
                        //    只传「本跳剩余毫秒数」的话，每跳都会按自己的声明重新起算，
                        //    根 Run 的剩余时间在嵌套路径上会被重置。
                        task.deadlineAt()));"""
new = """                        task.fencingToken(),
                        task.attempt()));"""
assert old in s, 'gate 未命中'
gate.write_text(s.replace(old, new))

# ── WorkerChildAgentRunner：摘掉 C15-2 的 Clock 与根截止时刻封顶 ──
s = runner.read_text()

old = """    private final java.util.function.LongSupplier nanoTime;
    /**
     * 墙钟（用于把根 Run 的<b>绝对</b>业务截止时刻换算成剩余毫秒）。
     *
     * <p>⚠️ <b>与 {@link #nanoTime} 分工</b>：{@code nanoTime} 是<b>单调</b>时钟，
     * 适合测「本级预算还剩多少」；而根 Run 的业务截止时刻是<b>绝对时刻</b>
     * （{@code run.deadline_at}），必须用墙钟比对 ——
     * 两者混用会把「绝对上界」变成「相对时长」，等于把上界丢掉。
     */
    private final java.time.Clock clock;
"""
new = """    private final java.util.function.LongSupplier nanoTime;
"""
assert old in s, 'runner 字段未命中'
s = s.replace(old, new)

old = """    public WorkerChildAgentRunner(@NonNull ModelGateway modelGateway,
                                   @NonNull ToolInvocationGate toolGate,
                                   @NonNull ChildAgentModelConfigProvider configs,
                                   int maxChildSteps,
                                   CancellationProbe cancellation,
                                   java.util.function.LongSupplier nanoTime) {
        this(modelGateway, toolGate, configs, maxChildSteps, cancellation, nanoTime,
                java.time.Clock.systemUTC());
    }

    /**
     * 完整构造器（注入时钟源与墙钟，供确定性测试使用）。
     *
     * @param modelGateway   模型网关
     * @param toolGate       工具闸门
     * @param configs        子 Agent 模型配置来源
     * @param maxChildSteps  子执行最大轮次
     * @param cancellation   取消探针（{@code null} = 永不取消）
     * @param nanoTime       纳秒时钟源（单调）
     * @param clock          墙钟（用于比对根 Run 的绝对业务截止时刻）
     */
    public WorkerChildAgentRunner(@NonNull ModelGateway modelGateway,
                                   @NonNull ToolInvocationGate toolGate,
                                   @NonNull ChildAgentModelConfigProvider configs,
                                   int maxChildSteps,
                                   CancellationProbe cancellation,
                                   java.util.function.LongSupplier nanoTime,
                                   java.time.Clock clock) {
        if (maxChildSteps < 1) {
            throw new IllegalArgumentException("maxChildSteps 必须为正数，收到: " + maxChildSteps);
        }
        this.modelGateway = modelGateway;
        this.toolGate = toolGate;
        this.configs = configs;
        this.maxChildSteps = maxChildSteps;
        this.cancellation = cancellation;
        this.nanoTime = nanoTime;
        this.clock = clock;
    }"""
new = """    /**
     * 完整构造器（注入时钟源，供确定性测试使用）。
     *
     * @param modelGateway   模型网关
     * @param toolGate       工具闸门
     * @param configs        子 Agent 模型配置来源
     * @param maxChildSteps  子执行最大轮次
     * @param cancellation   取消探针（{@code null} = 永不取消）
     * @param nanoTime       纳秒时钟源
     */
    public WorkerChildAgentRunner(@NonNull ModelGateway modelGateway,
                                   @NonNull ToolInvocationGate toolGate,
                                   @NonNull ChildAgentModelConfigProvider configs,
                                   int maxChildSteps,
                                   CancellationProbe cancellation,
                                   java.util.function.LongSupplier nanoTime) {
        if (maxChildSteps < 1) {
            throw new IllegalArgumentException("maxChildSteps 必须为正数，收到: " + maxChildSteps);
        }
        this.modelGateway = modelGateway;
        this.toolGate = toolGate;
        this.configs = configs;
        this.maxChildSteps = maxChildSteps;
        this.cancellation = cancellation;
        this.nanoTime = nanoTime;
    }"""
assert old in s, 'runner 构造器未命中'
s = s.replace(old, new)

old = """        String input = renderInput(request.input());
        // ⚠️ 根 Run 的业务截止时刻（第十五批 · C15-2）：
        //    它是整棵调用树**共享**的**绝对**时间上界。
        //    此前这里恒传 {@code null}（= 不限时）→ 子 Agent 再发起嵌套委派时，
        //    闸门算出的剩余时间是 {@link Long#MAX_VALUE} →
        //    根 Run 的剩余时间在嵌套路径上**彻底失效**，
        //    「全调用树共享超时上限」被架空。
        Instant rootDeadline = request.rootDeadlineAt();
        RunClaimPort.LeasedTask task = new RunClaimPort.LeasedTask(
                request.runId(), request.fencingToken(), Instant.MAX, request.attempt(),
                rootDeadline);

        // ⚠️ 墙钟截止时间 = min(父给的链路预算, 根 Run 的剩余时间)，从**子执行启动
        //    那一刻**起计时。子执行**自己**实施它，而不是只做一次判定（MA-T09）。
        long effectiveTimeoutMs = request.timeoutMs();
        if (rootDeadline != null) {
            long rootRemainingMs = rootDeadline.toEpochMilli() - clock.millis();
            if (rootRemainingMs <= 0) {
                // ⚠️ 根 Run 的业务截止时刻已过 → 子执行**不得启动**：
                //    再跑下去就是「明知会超时还要先花钱」。
                log.warn("根 Run 业务截止时刻已过，子执行不启动: agent={}, deadline={}",
                        request.targetAgentId(), rootDeadline);
                return ChildAgentResult.timedOut(ReasonCode.DELEGATION_TIMEOUT.name());
            }
            effectiveTimeoutMs = Math.min(effectiveTimeoutMs, rootRemainingMs);
        }
        long deadlineNanos = nanoTime.getAsLong() + effectiveTimeoutMs * 1_000_000L;"""
new = """        String input = renderInput(request.input());
        RunClaimPort.LeasedTask task = new RunClaimPort.LeasedTask(
                request.runId(), request.fencingToken(), Instant.MAX, request.attempt());

        // ⚠️ 墙钟截止时间：本级声明的链路预算从这里开始计时。
        //    子执行**自己**实施它，而不是只做一次判定（MA-T09）。
        long deadlineNanos = nanoTime.getAsLong() + request.timeoutMs() * 1_000_000L;"""
assert old in s, 'runner 截止时间段未命中'
s = s.replace(old, new)

old = """                log.warn("子执行超时: agent={}, budgetMs={}",
                        request.targetAgentId(), effectiveTimeoutMs);"""
new = """                log.warn("子执行超时: agent={}, budgetMs={}",
                        request.targetAgentId(), request.timeoutMs());"""
assert old in s, 'runner 日志未命中'
s = s.replace(old, new)
runner.write_text(s)
print('C15-2 已从工作区摘除')
PY

# 端口层与执行器层整体回退到 HEAD（这两处全是 C15-2 的改动）
git checkout -- \
  agent-ports/src/main/java/cn/studykid/multiagent/ports/orchestration/ChildAgentRunner.java \
  agent-application/src/main/java/cn/studykid/multiagent/application/orchestration/DelegationExecutor.java
# C15-2 的测试类暂时移出工作区
mv agent-worker/src/test/java/cn/studykid/multiagent/worker/execute/NestedDeadlinePropagationTest.java \
   /tmp/c15_1/NestedDeadlinePropagationTest.java

echo "当前工作区 = 仅 C15-1"
git status --short
