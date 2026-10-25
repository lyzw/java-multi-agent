#!/usr/bin/env bash
# 第十六批 C16-1 反证脚本：摘掉委派的预算预留，确认对应用例变红。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

GATE=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ToolInvocationGate.java
BAK=/tmp/gate_c16.bak
cp "$GATE" "$BAK"
restore() { cp "$BAK" "$GATE"; }

run_case() {
  local label="$1"
  echo "================ ${label} ================"
  mvn -o -B -pl agent-worker -am -Dtest='DelegationBudgetChargeTest' \
      -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
    | grep -E "^\[ERROR\].*DelegationBudgetChargeTest|Tests run: [0-9]+, Failures" \
    | sed 's/^/  /' | tail -14
  echo
}

restore

# ── 反证 A：整体摘掉委派路径的预算判定与预留 ──────────────────────
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        String reservationId = reserveDelegationQuota(tenantId, task, invocationId,
                call.toolName(), result);
        if (reservationId == null) {"""
new = """        String reservationId = null;
        if (false) {"""
assert old in s, "A: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 A：摘掉委派路径的预算判定与预留（回到「委派免费」）"
restore

# ── 反证 B：只摘掉 Run 级金额判定（保留预留）──────────────────────
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (runBudget != null) {
            RunBudgetProbe.Verdict verdict = runBudget.check(tenantId, task.runId(),
                    reserveAmountPerToolCall);
            if (!verdict.allowed()) {"""
new = """        if (false) {
            RunBudgetProbe.Verdict verdict = runBudget.check(tenantId, task.runId(),
                    reserveAmountPerToolCall);
            if (!verdict.allowed()) {"""
assert old in s, "B: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 B：只摘掉 Run 级金额判定（只判方向不判量）"
restore

# ── 反证 C：把「被拒 → 释放」改成「被拒 → 结算」─────────────────
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                    releaseQuietly(tenantId, reservationId, "delegation-rejected");"""
new = """                    settleQuietly(tenantId, reservationId, call.toolName());"""
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 C：被拒的委派改为结算（零成本却把额度扣掉）"
restore

# ── 反证 D：把「执行失败 → 结算」改成「执行失败 → 释放」─────────
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                settleQuietly(tenantId, reservationId, call.toolName());
                result.recordFailed(call.toolName(), reasonCode.name());"""
new = """                releaseQuietly(tenantId, reservationId, "delegation-failed");
                result.recordFailed(call.toolName(), reasonCode.name());"""
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 D：执行失败的委派改为释放（已花的钱被白送回去）"
restore

# ── 反证 E：UNKNOWN 时也释放额度（违反 R-007 红线）────────────────
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                result.recordUnknown(call.toolName(), outcome.invocationId());"""
new = """                releaseQuietly(tenantId, reservationId, "delegation-unknown");
                result.recordUnknown(call.toolName(), outcome.invocationId());"""
assert old in s, "E: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 E：UNKNOWN 时释放额度（成本可能已发生却白送）"
restore

echo "================ 恢复后复跑 ================"
run_case "恢复修复后（应全绿）"
