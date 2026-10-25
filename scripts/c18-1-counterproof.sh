#!/usr/bin/env bash
# 第十八批 C18-1 反证脚本：把「固定 release」与「快照按自己的 Agent 比对」摘掉，
# 确认对应用例变红。
#
# ⚠️ 本脚本要证伪的是两件事：
#   ① 子 Agent 路径可用（此前被准入检查整体拒绝）；
#   ② 子 Agent 绑定固定 release、且**不放宽**根路径的既有防线。
# 每处反证只动一个地方，然后跑 CapExecutionFlowTest 的第十八批用例 ——
# 「可用」的用例必须红，而「防线仍在」的用例**必须保持绿**（否则说明修复把防线拆了）。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

VALIDATOR=agent-application/src/main/java/cn/studykid/multiagent/application/cap/InvocationValidator.java
ADMIT=agent-application/src/main/java/cn/studykid/multiagent/application/cap/ExecutionAdmissionChecker.java
FLOW=agent-application/src/main/java/cn/studykid/multiagent/application/cap/CapExecutionFlow.java
BAK=/tmp/c18_1_counterproof
mkdir -p "$BAK"
cp "$VALIDATOR" "$BAK/validator.bak"
cp "$ADMIT" "$BAK/admit.bak"
cp "$FLOW" "$BAK/flow.bak"
restore() {
  cp "$BAK/validator.bak" "$VALIDATOR"
  cp "$BAK/admit.bak" "$ADMIT"
  cp "$BAK/flow.bak" "$FLOW"
}

run_case() {
  local label="$1"
  echo "================ ${label} ================"
  mvn -o -B -pl agent-application -am -Dtest='CapExecutionFlowTest' \
      -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
    | grep -E "^\[ERROR\]   CapExecutionFlowTest|Tests run: [0-9]+, Failures" \
    | sed 's/^/  /' | tail -14
  echo
}

restore

# ── 反证 A：快照复核改回「拿 Phase A 基线比快照」───────────────────
#    （= 修复前的口径：拿子 Agent 的基线去比根 Agent 的快照 → 必然不等）
python3 - "$ADMIT" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        String snapshotAgentId = snapshot.get().agentId();
        String snapshotFingerprint;
        if (snapshotAgentId.equals(request.agentId())) {
            // 同一执行主体（根路径的常见情形）→ 复用刚算出的当前指纹，不重复解析。
            snapshotFingerprint = currentFingerprint;
        } else {
            try {
                snapshotFingerprint = ClosureFingerprint.of(
                        closureResolver.resolveCurrent(request.tenantId(), snapshotAgentId));
            } catch (RuntimeException e) {
                log.warn("准入检查：快照 Agent 的闭包不可解析，fail-closed: tenant={}, run={}, "
                        + "snapshotAgent={}, err={}", request.tenantId(), request.runId(),
                        snapshotAgentId, e.getMessage());
                return AdmissionOutcome.rejected(ReasonCode.EXEC_ADMISSION_STALE,
                        currentVersion, currentFingerprint, now);
            }
        }"""
new = """        String snapshotAgentId = snapshot.get().agentId();
        String snapshotFingerprint = request.fingerprintAtPhaseA();"""
assert old in s, "A: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 A：快照复核改回「Phase A 基线 vs 快照」（子路径被误拒）"
restore

# ── 反证 B：校验器漂移回「当前已发布版本」──────────────────────────
python3 - "$VALIDATOR" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """            closure = request.agentVersion() != null
                    ? resolver.resolve(request.context().tenantId(), request.agentId(),
                            request.agentVersion())
                    : resolver.resolveCurrent(request.context().tenantId(), request.agentId());"""
new = """            closure = resolver.resolveCurrent(request.context().tenantId(), request.agentId());"""
assert old in s, "B: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 B：校验器按「当前已发布版本」解析（子 Agent 漂移到新 release）"
restore

# ── 反证 C：准入漂移回「当前已发布版本」────────────────────────────
python3 - "$ADMIT" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """            EffectiveClosure closure = request.agentVersion() != null
                    ? closureResolver.resolve(request.tenantId(), request.agentId(),
                            request.agentVersion())
                    : closureResolver.resolveCurrent(request.tenantId(), request.agentId());"""
new = """            EffectiveClosure closure = closureResolver.resolveCurrent(
                    request.tenantId(), request.agentId());"""
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 C：准入按「当前已发布版本」解析（子 Agent 漂移到新 release）"
restore

# ── 反证 D：快照复核整体跳过（把「根漂移仍阻断子路径」这条防线拆掉）──
python3 - "$ADMIT" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (!snapshot.get().configFingerprint().equals(snapshotFingerprint)) {"""
new = """        if (false && !snapshot.get().configFingerprint().equals(snapshotFingerprint)) {"""
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 D：快照复核整体跳过（根的闭包漂移不再阻断子路径）"
restore

# ── 反证 E：Phase A 不把固定版本下传（校验器与准入都拿不到版本）──────
python3 - "$FLOW" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                agentVersion = fixedVersion;"""
new = """                agentVersion = null;"""
assert old in s, "E: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 E：Phase A 不下传固定版本（固定 release 约束断链）"
restore

echo "================ 恢复后复跑 ================"
run_case "恢复修复后（应全绿）"
