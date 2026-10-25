#!/usr/bin/env bash
# 第十五批 C15-2 反证脚本：逐条摘掉修复，确认对应用例变红。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

RUNNER=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/WorkerChildAgentRunner.java
EXEC=agent-application/src/main/java/cn/studykid/multiagent/application/orchestration/DelegationExecutor.java
BAK_R=/tmp/runner_c15.bak
BAK_E=/tmp/exec_c15.bak
cp "$RUNNER" "$BAK_R"
cp "$EXEC" "$BAK_E"

restore() { cp "$BAK_R" "$RUNNER"; cp "$BAK_E" "$EXEC"; }

run_case() {
  local label="$1"
  echo "================ ${label} ================"
  mvn -o -B -pl agent-worker -am -Dtest='NestedDeadlinePropagationTest' \
      -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
    | grep -E "^\[ERROR\].*NestedDeadlinePropagationTest|Tests run: [0-9]+, Failures" \
    | sed 's/^/  /' | tail -12
  echo
}

restore

# ── 反证 D：子执行构造闸门任务时丢掉根截止时刻 ────────────────────
python3 - "$RUNNER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        RunClaimPort.LeasedTask task = new RunClaimPort.LeasedTask(
                request.runId(), request.fencingToken(), Instant.MAX, request.attempt(),
                rootDeadline);"""
new = """        RunClaimPort.LeasedTask task = new RunClaimPort.LeasedTask(
                request.runId(), request.fencingToken(), Instant.MAX, request.attempt(),
                null);"""
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 D：子执行丢掉落根截止时刻（嵌套预算被重置 + 截止时刻断链）"
restore

# ── 反证 E：执行器不把根截止时刻下传给子执行请求 ──────────────────
python3 - "$EXEC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                    command.fencingToken(), command.attempt(),
                    // ⚠️ 根 Run 的业务截止时刻**逐跳下传**（第十五批 · C15-2）：
                    //    它是整棵调用树**共享**的时间上界，必须以**绝对时刻**传递 ——
                    //    只传「本级剩余毫秒数」的话，每一跳都会重新按自己的声明起算，
                    //    根 Run 的剩余时间在嵌套路径上被重置成无限。
                    command.rootDeadlineAt()));"""
new = """                    command.fencingToken(), command.attempt(), null));"""
assert old in s, "E: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 E：执行器不下传根截止时刻（整条链断在第一跳）"
restore

# ── 反证 F：子执行不按根截止时刻封顶自己的墙钟 ────────────────────
python3 - "$RUNNER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        long effectiveTimeoutMs = request.timeoutMs();
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
        }"""
new = """        long effectiveTimeoutMs = request.timeoutMs();"""
assert old in s, "F: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 F：子执行不按根截止时刻封顶墙钟（明知会超时还要先花钱）"
restore

echo "================ 恢复后复跑 ================"
run_case "恢复修复后（应全绿）"
