#!/usr/bin/env bash
# 第十七批 C17-1 反证脚本：摘掉幂等键的 Agent 维度，确认对应用例变红。
#
# ⚠️ 本脚本要证伪的是「按当前执行中的 Agent 分域」这件事本身：
#   每处反证都把 Agent 维度从键里摘掉（或让两条链路各算各的），
#   然后跑**成对断言**的新增用例 —— 不同 Agent 必须不同键、
#   同一 Agent 重复投递必须同键。两头都红才说明用例真的在测这件事。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

GATE=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ToolInvocationGate.java
KEY=agent-core/src/main/java/cn/studykid/multiagent/core/idempotency/IdempotencyKey.java
FLOW=agent-application/src/main/java/cn/studykid/multiagent/application/cap/CapExecutionFlow.java
BAK=/tmp/c17_1_counterproof
mkdir -p "$BAK"
cp "$GATE" "$BAK/gate.bak"
cp "$KEY" "$BAK/key.bak"
cp "$FLOW" "$BAK/flow.bak"
restore() {
  cp "$BAK/gate.bak" "$GATE"
  cp "$BAK/key.bak" "$KEY"
  cp "$BAK/flow.bak" "$FLOW"
}

run_worker_case() {
  local label="$1"
  echo "================ ${label} ================"
  mvn -o -B -pl agent-worker -am -Dtest='NestedToolIdempotencyTest' \
      -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
    | grep -E "^\[ERROR\].*NestedToolIdempotencyTest|Tests run: [0-9]+, Failures" \
    | sed 's/^/  /' | tail -14
  echo
}

run_app_case() {
  local label="$1"
  echo "================ ${label} ================"
  mvn -o -B -pl agent-application -am -Dtest='CapExecutionFlowTest' \
      -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
    | grep -E "^\[ERROR\].*CapExecutionFlowTest|Tests run: [0-9]+, Failures" \
    | sed 's/^/  /' | tail -14
  echo
}

restore

# ── 反证 A：闸门不解析 Agent 引用（回到「键里没有 Agent 维度」）────
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (scope != null) {
            return scope.agentRef();
        }
        if (delegationContext != null) {
            return delegationContext.agentRef();
        }
        return null;"""
new = """        return null;"""
assert old in s, "A: 未命中"
p.write_text(s.replace(old, new))
PY
run_worker_case "反证 A：闸门不解析 Agent 引用（父子/兄弟共用同一个键）"
restore

# ── 反证 B：Agent 维度被整体丢弃（键的形状回到历史）──────────────
python3 - "$KEY" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        String agentPart = agentRef == null || agentRef.isBlank()
                ? ""
                : requireAgentRef(agentRef) + SEPARATOR;"""
new = """        String agentPart = "";"""
assert old in s, "B: 未命中"
p.write_text(s.replace(old, new))
PY
run_worker_case "反证 B：幂等键整体丢弃 Agent 维度（键形状回到历史）"
restore

# ── 反证 C：只有执行作用域算数（委派上下文这条线索被丢掉）────────
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (delegationContext != null) {
            return delegationContext.agentRef();
        }
        return null;"""
new = """        return null;"""
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new))
PY
run_worker_case "反证 C：只认执行作用域，委派上下文这条线索被丢掉"
restore

# ── 反证 D：版本不参与（同一 Agent 的不同 release 共用同一个键）──
python3 - "$KEY" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        return agentId + "@" + agentVersion;"""
new = """        return agentId;"""
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new))
PY
run_worker_case "反证 D：Agent 引用不带版本（不同 release 共用一个键）"
restore

# ── 反证 E：CAP 链路自行派生 Agent 维度（与闸门算的不是同一个值）──
python3 - "$FLOW" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        return invoke(tenantId, task, call, handler, scope,
                scope == null ? null : scope.agentRef());"""
new = """        return invoke(tenantId, task, call, handler, scope, null);"""
assert old in s, "E: 未命中"
p.write_text(s.replace(old, new))
PY
run_app_case "反证 E：CAP 链路自行派生 Agent 维度（闸门透传的值被忽略）"
restore

# ── 反证 F：闸门把 Agent 维度算成全局常量（所有 Agent 共用一个键）──
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (scope != null) {
            return scope.agentRef();
        }"""
new = """        if (scope != null) {
            return "shared-agent";
        }"""
assert old in s, "F: 未命中"
p.write_text(s.replace(old, new))
PY
run_worker_case "反证 F：所有 Agent 共用同一个 Agent 段（分域形同虚设）"
restore

echo "================ 恢复后复跑 ================"
run_worker_case "恢复修复后 · 闸门侧（应全绿）"
run_app_case "恢复修复后 · CAP 侧（应全绿）"
