#!/usr/bin/env bash
# 第三十二批 C32-1 反证脚本：钉住评审前置决策 1 的语义（`SEQUENTIAL` 不纳入 P2，
# 多子结果聚合**不由框架代劳**）。
#
# ⚠️ 本脚本要证伪的是一件事：
#   **框架只提供「子 Agent 集合 + 逐跳授权 + 逐跳资源封顶」三样，
#   串行与聚合是父 Agent（多轮执行体）自己的模型行为。**
#   此前 `OrchestrationMode.SEQUENTIAL` 的 javadoc 却承诺了
#   「按输出契约聚合结果（FR-027）」—— 框架**没有**这个动作，
#   与第十四批之前 ROUTER「名不副实」是同构缺陷。
#
# ⚠️ 每处反证只动一个地方：
#   · **反证 A（行为）**：临时把执行器改成「一次委派把父声明的其余子 Agent
#     也跑一遍」（= 模拟框架代劳聚合）→ 新增用例
#     `multipleDeclaredChildrenStillRunOnePerDelegation` 必须**恰好 1 条红**，
#     且**其余用例保持绿**（红了但超出预期条数同样判无效）。
#   · **反证 B（契约口径）**：把那句不实承诺重新写回代码/契约 → 口径检查必须
#     报出（证明它不是恒绿）。
#
# ⚠️ **判定**：结论不变（仍全绿）= 无效反证。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="${JAVA_HOME}/bin:${PATH}"

EXEC=agent-application/src/main/java/cn/studykid/multiagent/application/orchestration/DelegationExecutor.java
MODE=agent-core/src/main/java/cn/studykid/multiagent/core/orchestration/OrchestrationMode.java
SCHEMA_DIR=../docs/多级\ Agent优化/agent-capability-contracts-v1/schemas
ROOT=/Users/zhouwei/code-workspace/ai-tech/java-multi-agent

BAK=/tmp/c32_1_counterproof
mkdir -p "${BAK}"
cp "${EXEC}" "${BAK}/exec.bak"
cp "${MODE}" "${BAK}/mode.bak"

FAILED=0

restore() {
  cp "${BAK}/exec.bak" "${EXEC}"
  cp "${BAK}/mode.bak" "${MODE}"
}
trap restore EXIT

# ⚠️ 只数**单条用例**的失败行（surefire 还会额外打一行类汇总，
#    那行带 `<<< FAILURE!` 但不是一条用例 —— 算进去会让计数翻倍）。
count_red() {
  grep -cE "^\[ERROR\] +[A-Za-z0-9_.]+ -- Time elapsed" "${1}"
}

report() {
  local label="${1}" expect_red="${2}" log="${3}"
  local red
  red=$(count_red "${log}")
  if [ "${expect_red}" -eq 0 ]; then
    if [ "${red}" -eq 0 ]; then
      echo "  ✅ ${label}：全绿（符合预期）"
    else
      echo "  ⛔ ${label}：红了 ${red} 条，期望 0 条 → 反证范围超出预期，结论不纯"
      FAILED=$((FAILED + 1))
    fi
  else
    if [ "${red}" -eq "${expect_red}" ]; then
      echo "  ✅ ${label}：恰好红 ${red} 条（符合预期）"
    else
      echo "  ⛔ ${label}：红了 ${red} 条，期望恰好 ${expect_red} 条 → 无效反证"
      FAILED=$((FAILED + 1))
    fi
  fi
}

run_test() {
  mvn -o -B -pl agent-application -am -Dtest='DelegationExecutorTest' \
      -Dsurefire.failIfNoSpecifiedTests=false test > "${1}" 2>&1
}

echo "C32-1 反证：决策 1 的语义（框架不代劳多子结果聚合）"
echo

# ── 基线：干净代码必须全绿 ─────────────────────────────────────────────
echo "基线：未注入任何缺陷 → 必须全绿"
run_test "${BAK}/baseline.log"
report "基线" 0 "${BAK}/baseline.log"
echo

# ── 反证 A：模拟「框架代劳聚合」→ 新用例必须恰好 1 条红 ────────────────
echo "反证 A：把执行器改成「一次委派把父声明的其余子 Agent 也跑一遍」"
echo "        → 用例 multipleDeclaredChildrenStillRunOnePerDelegation 必须恰好红 1 条"
restore
python3 - "${EXEC}" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text()
anchor = "        // ── 8. 输出契约校验（MA-T16）────────────────────────────────"
assert anchor in s, "锚点未命中（执行器结构变了？）"
inject = """        // ⚠️ 反证注入（临时）：模拟「框架代劳多子结果聚合」——
        //    把父声明的其余子 Agent 也跑一遍。真实实现**不做**这件事。
        if (orchestration.mode() == OrchestrationMode.SEQUENTIAL) {
            for (ChildAgentRef extra : orchestration.childAgentRefs()) {
                if (!extra.agentId().equals(effectiveTargetId)) {
                    runner.run(new ChildAgentRunner.ChildAgentRequest(
                            command.tenantId(), command.runId(), command.invocationId(),
                            command.traceId(), parent.agentId(), parent.agentVersion(),
                            extra.agentId(), extra.releaseVersion(),
                            command.input(), childDepth, scope,
                            effectiveTimeoutMs, command.fencingToken(), command.attempt(),
                            command.rootDeadlineAt()));
                }
            }
        }

"""
p.write_text(s.replace(anchor, inject + anchor, 1))
PY
run_test "${BAK}/inject.log"
report "反证 A（注入后）" 1 "${BAK}/inject.log"
if grep -q "multipleDeclaredChildrenStillRunOnePerDelegation" "${BAK}/inject.log"; then
  echo "  ✅ 反证 A：红的正是新钉语义的那条用例（multipleDeclaredChildrenStillRunOnePerDelegation）"
else
  echo "  ⛔ 反证 A：红的不是新用例 → 注入没打到点上"
  FAILED=$((FAILED + 1))
fi
echo

# ── 反证 A 的收尾：恢复后必须重新变绿 ──────────────────────────────────
echo "反证 A 收尾：恢复执行器 → 必须重新全绿（证明注入是唯一变量）"
restore
run_test "${BAK}/restored.log"
report "反证 A 收尾" 0 "${BAK}/restored.log"
echo

# ── 反证 B：不实承诺的措辞必须被口径检查报出 ────────────────────────────
echo "反证 B：把「按输出契约聚合结果」这句不实承诺写回代码/契约 → 口径检查必须报出"
cat > "${BAK}/forbidden.txt" <<'EOF'
按输出契约聚合结果
按契约聚合
按声明顺序串行执行
EOF
# ⚠️ **语境感知**：一句话可能是「承诺」，也可能是「禁止再写这句话」——
#    把后者当成前者报出来，检查器就成了噪音（连自己的禁止性说明都会命中）。
#    故带否定/更正标记的行**跳过**。
check_forbidden() {
  python3 - "${ROOT}/platform" "${BAK}/forbidden.txt" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
phrases = [l.strip() for l in pathlib.Path(sys.argv[2]).read_text().splitlines() if l.strip()]
NEGATIONS = ("不再写", "不得", "没有", "此前", "已更正", "不实承诺", "反证注入", "明确不做")
hits = []
for pattern in ("*.java", "*.json"):
    for f in root.rglob(pattern):
        if "/target/" in str(f):
            continue
        for i, line in enumerate(f.read_text().splitlines(), 1):
            if any(n in line for n in NEGATIONS):
                continue
            for ph in phrases:
                if ph in line:
                    hits.append(f"{f.relative_to(root)}:{i}: {ph}")
print(len(hits))
for h in hits[:10]:
    print("   ", h)
PY
}
BASE_HITS=$(check_forbidden | head -1)
if [ "${BASE_HITS}" -eq 0 ]; then
  echo "  ✅ 基线：代码与契约里已无「框架代劳聚合」的措辞（0 处）"
else
  echo "  ⛔ 基线：仍有 ${BASE_HITS} 处不实承诺 → 先清干净再做反证"
  FAILED=$((FAILED + 1))
fi
# 注入 → 必须被检出
python3 - "${MODE}" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "    SEQUENTIAL\n}"
# ⚠️ 注入行**不得**含否定标记（否则会被检查器的语境过滤跳过，
#    那样测的就不是「检查器会咬」，而是「自己骗了自己」）。
new = ("     * <p>SEQUENTIAL = 按声明顺序串行执行子 Agent（V1 有界串行）。"
       "\n    SEQUENTIAL\n}")
assert old in s
p.write_text(s.replace(old, new, 1))
PY
INJ_HITS=$(check_forbidden | head -1)
restore
if [ "${INJ_HITS}" -gt "${BASE_HITS}" ]; then
  echo "  ✅ 反证 B（不实承诺被检出）：命中数由 ${BASE_HITS} 变 ${INJ_HITS}（结论已变化）"
else
  echo "  ⛔ 反证 B（不实承诺被检出）：命中数未变（${BASE_HITS} → ${INJ_HITS}）→ 检查器没在咬"
  FAILED=$((FAILED + 1))
fi
echo

echo "──────────────────────────────────────────────"
if [ "${FAILED}" -ne 0 ]; then
  echo "⛔ 反证失败 ${FAILED} 项 —— 本批不予提交"
  exit 1
fi
echo "✅ 全部反证成立"
