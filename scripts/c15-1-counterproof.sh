#!/usr/bin/env bash
# 第十五批 C15-1 反证脚本：逐条摘掉修复，确认对应用例变红。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

GATE=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ToolInvocationGate.java
BAK=/tmp/gate_c15.bak
cp "$GATE" "$BAK"

restore() { cp "$BAK" "$GATE"; }

run_case() {
  local label="$1"; shift
  echo "================ ${label} ================"
  mvn -o -B -pl agent-worker -am -Dtest='NestedDelegationGateTest' \
      -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
    | grep -E "^\[ERROR\].*NestedDelegationGateTest|Tests run:.*(Failures|Errors): [1-9]|Tests run: [0-9]+, Failures: 0, Errors: 0" \
    | sed 's/^/  /' | tail -20
  echo
}

restore

# ── 反证 A：父身份/深度回退为「恒按根 Agent」─────────────────────
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (delegationContext != null) {
            parentAgentId = delegationContext.agentId();
            parentAgentVersion = delegationContext.agentVersion();
            parentInvocationId = delegationContext.invocationId();
            parentDepth = delegationContext.depth();
        }"""
new = """        if (false) {
            parentAgentId = delegationContext.agentId();
            parentAgentVersion = delegationContext.agentVersion();
            parentInvocationId = delegationContext.invocationId();
            parentDepth = delegationContext.depth();
        }"""
assert old in s, "A: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 A：父身份/深度回退为恒按根 Agent（越权 + 深度失效）"
restore

# ── 反证 B：invocationId 派生回退为「父调用 id 恒 null」──────────
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                .DelegationInvocationId.derive(task.runId(),
                        delegationContext == null ? null : delegationContext.invocationId(),
                        call.callId());"""
new = """                .DelegationInvocationId.derive(task.runId(), null, call.callId());"""
assert old in s, "B: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 B：invocationId 派生回退为父调用 id 恒 null（幂等撞号）"
restore

# ── 反证 C：去掉 scope/ctx 一致性校验 ────────────────────────────
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        boolean contextMismatch = scope != null && delegationContext != null
                && (!scope.agentId().equals(delegationContext.agentId())
                        || scope.agentVersion() != delegationContext.agentVersion());"""
new = """        boolean contextMismatch = false;"""
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 C：去掉 scope/ctx 一致性校验（上下文串了仍放行）"
restore

echo "================ 恢复后复跑 ================"
run_case "恢复修复后（应全绿）"
