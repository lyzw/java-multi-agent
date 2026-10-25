#!/usr/bin/env bash
# 第二十二批 C22-1 反证脚本：把「委派预算预留的 UNKNOWN 对账通道」逐处摘掉，
# 确认对应用例变红。
#
# ⚠️ 本脚本要证伪的是一件事：
#   **委派预留的额度必须能被找到、能被按正确口径处置** ——
#   修复前，委派的预留记在 agent_invocation 里、没有 tool_operation 行，
#   而预算对账的两条查询都用 INNER JOIN tool_operation →
#   那些额度**被占着，却查无实据**，连「转人工清单」都进不去。
#
# ⚠️ 每处反证只动一个地方，然后跑相关测试：
#   被摘掉的那条能力，对应用例必须红；而**不该受影响**的用例必须保持绿
#   （否则说明反证改动的范围比预想的大 —— 结论不纯）。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
# ⚠️ DB 层 IT 需要真实 PG17（非特权角色，才能验到 RLS 真实效果）
export IT_PG_URL="${IT_PG_URL:-jdbc:postgresql://127.0.0.1:5433/agent_dev}"
export IT_PG_USER="${IT_PG_USER:-app_agent}"
export IT_PG_PASSWORD="${IT_PG_PASSWORD:-app_agent_pw}"
export IT_PG_ADMIN_USER="${IT_PG_ADMIN_USER:-agent}"
export IT_PG_ADMIN_PASSWORD="${IT_PG_ADMIN_PASSWORD:-agent_dev_pw}"

REPO=agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcDelegationBudgetRepository.java
SVC=agent-application/src/main/java/cn/studykid/multiagent/application/orchestration/DelegationBudgetReconciliationService.java
BAK=/tmp/c22_1_counterproof
mkdir -p "$BAK"
cp "$REPO" "$BAK/repo.bak"
cp "$SVC" "$BAK/service.bak"
restore() {
  cp "$BAK/repo.bak" "$REPO"
  cp "$BAK/service.bak" "$SVC"
}

run_case() {
  local label="$1" module="$2" tests="$3"
  echo "================ ${label} ================"
  mvn -o -B -pl "$module" -am -Dtest="$tests" \
      -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
    | grep -E "^\[ERROR\]   [A-Za-z]+(Test|IT)|Tests run: [0-9]+, Failures" \
    | sed 's/^/  /' | tail -14
  echo
}

restore

# ── 反证 A：反查退回「只认 tool_operation 锚点」（= 修复前的形状）────
#    预期：委派侧的预留**全部查不到** → DB 层用例大面积变红。
python3 - "$REPO" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "AND NOT EXISTS ("
new = "AND EXISTS ("
assert s.count(old) == 2, f"A: 期望命中 2 处，实际 {s.count(old)}"
p.write_text(s.replace(old, new))
PY
run_case "反证 A：反查只认 tool_operation 锚点（= 修复前：委派额度查无实据）" \
    agent-infrastructure 'DelegationBudgetReconciliationIT'
restore

# ── 反证 B：反查用 INNER JOIN agent_invocation（丢掉「无锚点」那一类）──
#    预期：「连委派记录都没有的预留也能被反查」那条用例红。
python3 - "$REPO" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "LEFT JOIN agent_invocation i"
new = "JOIN agent_invocation i"
assert s.count(old) == 2, f"B: 期望命中 2 处，实际 {s.count(old)}"
p.write_text(s.replace(old, new))
PY
run_case "反证 B：无锚点的委派预留被丢掉（额度泄漏又变成查无实据）" \
    agent-infrastructure 'DelegationBudgetReconciliationIT'
restore

# ── 反证 C：去掉「工具侧」排除条件（两条通道重叠）────────────────────
#    预期：「工具侧预留不被本通道反查」那条用例红（一次对账会处置两遍）。
python3 - "$REPO" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """               AND NOT EXISTS (
                   SELECT 1 FROM tool_operation o
                    WHERE o.tenant_id = r.tenant_id
                      AND o.operation_id = r.operation_id
               )
"""
assert s.count(old) == 2, f"C: 期望命中 2 处，实际 {s.count(old)}"
p.write_text(s.replace(old, ""))
PY
run_case "反证 C：委派通道与工具侧通道重叠（同一笔预留被处置两遍）" \
    agent-infrastructure 'DelegationBudgetReconciliationIT'
restore

# ── 反证 D：UNKNOWN 也被自动结算（R-007 被拆）────────────────────────
#    预期：「UNKNOWN 绝不被自动处置」那条用例红（成本未知却被认定已花）。
python3 - "$SVC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """            } else if (r.unknownHeld()) {
                unknownBuf.add(r);"""
new = """            } else if (r.unknownHeld()) {
                terminalBuf.add(r);"""
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 D：UNKNOWN 被自动结算（R-007：成本未知既不能结算也不能释放）" \
    agent-application 'DelegationBudgetReconciliationServiceTest'
restore

# ── 反证 E：终态委派改成释放而不是结算（白送额度）────────────────────
#    预期：「终态必须结算」那条用例红。
python3 - "$SVC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """            if (budget.settle(tenantId, r.reservationId(), r.reservedAmount(),
                    "reconcile:delegation-terminal")) {"""
new = """            if (budget.release(tenantId, r.reservationId(),
                    "reconcile:delegation-terminal")) {"""
assert old in s, "E: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 E：终态委派被释放而不是结算（子执行已跑过，额度被白送）" \
    agent-application 'DelegationBudgetReconciliationServiceTest'
restore

# ── 反证 F：在途的委派预留也被自动处置（子执行可能还在跑）────────────
#    预期：「在途绝不被自动处置」那条用例红。
python3 - "$SVC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """            } else if (r.inFlight()) {
                inFlightBuf.add(r);"""
new = """            } else if (r.inFlight()) {
                terminalBuf.add(r);"""
assert old in s, "F: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 F：在途的委派预留被自动结算（子执行可能还在跑）" \
    agent-application 'DelegationBudgetReconciliationServiceTest'
restore

# ── 反证 G：人工处置入口对「非收敛状态」也放行 ───────────────────────
#    预期：「只有对账收敛结果才可处置」那条用例红。
python3 - "$SVC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (reconciled != ToolOperationState.RECONCILED_SUCCEEDED
                && reconciled != ToolOperationState.RECONCILED_ABSENT) {
            throw new IllegalArgumentException(
                    "只有对账收敛结果可触发预算处置，收到: " + reconciled);
        }
"""
assert old in s, "G: 未命中"
p.write_text(s.replace(old, ""))
PY
run_case "反证 G：拿「本来就未知」当结论也放行（越过对账直接改账）" \
    agent-application 'DelegationBudgetReconciliationServiceTest'
restore

# ── 反证 H：人工处置对「确定终态」的预留也放行 ───────────────────────
#    预期：「终态拒绝人工处置」那条用例红（已计的费用会被再退一次）。
python3 - "$SVC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "        if (r.definitelyTerminal()) {"
new = "        if (false) {"
assert old in s, "H: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 H：人工结论可覆盖已终态的预留（已计费用被再退一次）" \
    agent-application 'DelegationBudgetReconciliationServiceTest'
restore

# ── 反证 I：结算额超出预留也照结 ─────────────────────────────────────
#    预期：「结算额超预留 → fail-closed」那条用例红。
python3 - "$SVC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "            if (amount.signum() < 0 || amount.compareTo(r.reservedAmount()) > 0) {"
new = "            if (false) {"
assert old in s, "I: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 I：结算额超预留也照结（凭空多计费）" \
    agent-application 'DelegationBudgetReconciliationServiceTest'
restore

# ── 反证 J：needsAttention 不计 UNKNOWN 与无锚点 ──────────────────────
#    预期：「UNKNOWN 与无锚点都要算进去」那条用例红。
python3 - "$SVC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "            return !unknown.isEmpty() || !unanchored.isEmpty();"
new = "            return false;"
assert old in s, "J: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 J：UNKNOWN 与无锚点不被计入「需人工介入」（运维看不到异常）" \
    agent-application 'DelegationBudgetReconciliationServiceTest'
restore

echo "================ 恢复后复跑全绿 ================"
restore
mvn -o -B -pl agent-application,agent-infrastructure -am \
    -Dtest='DelegationBudgetReconciliationServiceTest,DelegationBudgetReconciliationIT' \
    -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
  | grep -E "Tests run: [0-9]+, Failures" | sed 's/^/  /' | tail -8
