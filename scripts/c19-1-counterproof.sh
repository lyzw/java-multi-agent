#!/usr/bin/env bash
# 第十九批 C19-1 反证脚本：把「委派分支两条执行链路共用」逐处摘掉，
# 确认对应用例变红。
#
# ⚠️ 本脚本要证伪的是一件事：
#   **同一个委派调用在两条链路上必须给出同一个结论。**
#   修复前委派分支只存在于 CAP 链路，既有链路把委派当成「未登记的工具」拒掉 ——
#   而既有链路正是生产默认路径（config.flag.cap-authorization-enforced 默认 false），
#   于是「多级 Agent 在生产上根本不可用」，而代码看上去是齐的。
#
# ⚠️ 每处反证只动一个地方，然后跑 DelegationGateBranchTest：
#   被摘掉的那条能力，对应用例必须红；而**不该受影响**的用例必须保持绿 ——
#   否则说明反证改动的范围比预想的大（结论不纯）。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

GATE=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ToolInvocationGate.java
BAK=/tmp/c19_1_counterproof
mkdir -p "$BAK"
cp "$GATE" "$BAK/gate.bak"
restore() {
  cp "$BAK/gate.bak" "$GATE"
}

run_case() {
  local label="$1"
  echo "================ ${label} ================"
  mvn -o -B -pl agent-worker -am -Dtest='DelegationGateBranchTest' \
      -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
    | grep -E "^\[ERROR\]   DelegationGateBranchTest|Tests run: [0-9]+, Failures" \
    | sed 's/^/  /' | tail -16
  echo
}

# ⚠️ 只在**指定方法体**内替换：
#    invokeAllCap 与 invokeAllLegacy 的委派分支文本完全相同，
#    全文替换会连另一条链路一起改掉 → 结论不纯（看不出是哪条链路的问题）。
replace_in_method() {
  local method="$1" old="$2" new="$3"
  python3 - "$GATE" "$method" "$old" "$new" <<'PY'
import sys, pathlib
path, method, old, new = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
p = pathlib.Path(path)
s = p.read_text()
start = s.index("    private ToolInvocationResult " + method + "(")
end = s.index("\n    /**", start)
body = s[start:end]
assert old in body, method + ": 未命中"
body = body.replace(old, new, 1)
p.write_text(s[:start] + body + s[end:])
PY
}

restore

# ── 反证 A：既有链路的委派分支整段摘掉（= 修复前的状态）────────────
#    预期：**只有 LEGACY 相关用例红** —— 委派被当成「未登记的工具」拒掉。
#    CAP 链路的用例必须保持绿（证明改动范围只落在既有链路）。
replace_in_method invokeAllLegacy \
'            if (isDelegationCall(call)) {
                if (handleDelegationCall(tenantId, task, call, result, scope,
                        delegationContext, delegationHalted)) {
                    delegationHalted = true;
                }
                continue;
            }
' \
''
run_case "反证 A：既有链路摘掉委派分支（= 修复前：委派被当成「未登记的工具」）"
restore

# ── 反证 B：委派判定排到「未登记的工具」判定之后 ──────────────────
#    真正的「顺序错误」形态：先查 handler，handler == null 就按「未登记的工具」
#    **直接拒掉并 continue** —— 委派分支排在它之后，永远轮不到。
#    （委派不是注册的工具，handler 必然为 null → 委派必然被挡）
#    预期：**只有 LEGACY 相关用例红**（顺序错了 → 委派被挡）。
replace_in_method invokeAllLegacy \
'            if (isDelegationCall(call)) {
                if (handleDelegationCall(tenantId, task, call, result, scope,
                        delegationContext, delegationHalted)) {
                    delegationHalted = true;
                }
                continue;
            }
            ToolHandler handler = findHandler(call.toolName());
            if (handler == null) {
                // 未登记工具：拒绝，并把错误回给模型让它自我纠正
                // ⚠️ 不静默忽略 —— 模型可能因此陷入循环
                log.warn("未登记的工具被拒绝: tool={}, runId={}", call.toolName(), task.runId());
                result.recordRejected(call.toolName(), "未登记的工具");
                continue;
            }' \
'            ToolHandler handler = findHandler(call.toolName());
            if (handler == null) {
                // 未登记工具：拒绝，并把错误回给模型让它自我纠正
                // ⚠️ 不静默忽略 —— 模型可能因此陷入循环
                log.warn("未登记的工具被拒绝: tool={}, runId={}", call.toolName(), task.runId());
                result.recordRejected(call.toolName(), "未登记的工具");
                continue;
            }
            if (isDelegationCall(call)) {
                if (handleDelegationCall(tenantId, task, call, result, scope,
                        delegationContext, delegationHalted)) {
                    delegationHalted = true;
                }
                continue;
            }'
run_case "反证 B：委派判定排到「未登记的工具」之后（顺序错了 → 委派被挡）"
restore

# ── 反证 C：上下文一致性校验摘掉（只比 agentId，不比 version）──────
#    预期：**版本不一致**的用例红（漂移入口漏网），标识不一致的用例仍绿。
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (scope != null && delegationContext != null
                && (!scope.agentId().equals(delegationContext.agentId())
                        || scope.agentVersion() != delegationContext.agentVersion())) {"""
new = """        if (scope != null && delegationContext != null
                && !scope.agentId().equals(delegationContext.agentId())) {"""
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 C：一致性校验只比 agentId 不比 version（漂移入口漏网）"
restore

# ── 反证 D：上下文一致性校验整体摘掉（两条链路都变成「挑一个信的用」）──
#    预期：标识不一致 + 版本不一致的用例**都红**（越权路径被打开）。
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (scope != null && delegationContext != null
                && (!scope.agentId().equals(delegationContext.agentId())
                        || scope.agentVersion() != delegationContext.agentVersion())) {"""
new = """        if (false) {"""
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 D：上下文一致性校验整体摘掉（越权路径被打开）"
restore

# ── 反证 E：FAIL_FAST 的停止语义摘掉（两条链路都继续跑后续委派）─────
#    预期：FAIL_FAST 用例红 —— 失败后仍启动后续子 Agent。
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (delegationHalted) {
            log.info("失败策略 FAIL_FAST：跳过本轮后续委派: tool={}, runId={}","""
new = """        if (false && delegationHalted) {
            log.info("失败策略 FAIL_FAST：跳过本轮后续委派: tool={}, runId={}","""
assert old in s, "E: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 E：FAIL_FAST 停止语义摘掉（失败后仍启动后续子 Agent）"
restore

# ── 反证 F：既有链路绕过共用的 handleDelegationCall（本地复制一份）──
#    这是「共用」被破坏时最可能的真实形态：有人为了少改代码在既有链路里
#    自己写一份委派分支 —— 结果既有链路的**一致性校验没了**。
#    预期：**只有 LEGACY 的一致性用例红**，CAP 链路的仍绿。
replace_in_method invokeAllLegacy \
'                if (handleDelegationCall(tenantId, task, call, result, scope,
                        delegationContext, delegationHalted)) {' \
'                if (invokeDelegation(tenantId, task, call, result, delegationContext)) {'
run_case "反证 F：既有链路绕过共用实现（本地复制一份 → 一致性校验失效）"
restore

# ── 反证 G：未装配执行器时静默放行（isDelegationCall 不看执行器）─────
#    预期：未装配执行器的用例红 —— 委派被当成「已登记的工具」放行。
python3 - "$GATE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        return delegation != null
                && cn.studykid.multiagent.application.orchestration.DelegationExecutor
                        .DELEGATION_TOOL_NAME.equals(call.toolName());"""
new = """        return cn.studykid.multiagent.application.orchestration.DelegationExecutor
                .DELEGATION_TOOL_NAME.equals(call.toolName());"""
assert old in s, "G: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 G：未装配执行器时不再回落（「未登记的工具」防线失效）"
restore

echo "================ 恢复后复跑 ================"
run_case "恢复修复后（应全绿）"
