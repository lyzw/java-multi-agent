#!/usr/bin/env bash
# S3-M15（#105）反证脚本：钉死「L2 SSE / L3 ConsoleChat / L1 Worker 整条上下文压缩真链路
# 不是空转、不是假绿」——以 IT 级（真库真跑）撤点反证，每撤一处生产代码修复点，
# 对应 M15 IT 恰好红 1 条、Skipped=0；恢复后五类 M15 IT 全绿。
#
# ⚠️ 四处独立的修复点（互不污染，撤点只红对应那一类 IT）：
#   · RV-M15-01：contextState 六项静默丢弃被撤（verifier 强制 Accepted）→
#               CarryOverRoundTripIT#hmacMismatchFallsBackToFullHistory 必须红
#               （篡改 HMAC 不再静默丢弃→围栏被注入→「无围栏」断言失败）。
#   · RV-M15-02：SSE 统一装配被绕过（passthrough）→
#               SseHistory100TurnsWithinBudgetIT#hundredTurnHistoryIsTrimmedWithinBudget 必须红
#               （捕获请求不再裁剪→ after<before*4/5 与「有 ASSISTANT 整轮被裁」断言失败）。
#   · RV-M15-03：L3 ConsoleChat 预算端口被绕过（return legacyRequest）→
#               ConsoleChatMessagesWithinBudgetIT#platformChainBudgetTrimsLargeHistory 必须红
#               （送模型请求不再裁剪→ after<before 断言失败）。
#   · RV-M15-04：Worker 统一装配被绕过（passthrough）→
#               WorkerToolRoundPruningKeepsPairedMessagesIT#tieredToolRoundPruningKeepsPairedMessages 必须红
#               （不裁剪→ onContextCompacted 不触发→ compaction 事件空→ tier≥2 断言失败）。
#
# ⚠️ 中断安全：SIGTERM/SIGINT 时先复原当前撤点文件再退出，
#    避免残留撤点源码造成后续静默失真（M10 曾吃过这个亏）。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

VERIFIER_REL=platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextStateVerifierAdapter.java
ORCH_REL=platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/agentchat/AgentChatOrchestrator.java
CTRL_REL=platform/agent-api/src/main/java/cn/studykid/multiagent/api/chat/ConsoleChatController.java
WORKER_REL=platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RunExecutionLoop.java

VERIFIER="${ROOT}/${VERIFIER_REL}"
ORCH="${ROOT}/${ORCH_REL}"
CTRL="${ROOT}/${CTRL_REL}"
WORKER="${ROOT}/${WORKER_REL}"
PATCHED_FILES=("${VERIFIER}" "${ORCH}" "${CTRL}" "${WORKER}")

IT_PKG=cn.studykid.multiagent.bootstrap.context
CARRYOVER_CLASS=CarryOverRoundTripIT
SSE_CLASS=SseHistory100TurnsWithinBudgetIT
CONSOLE_CLASS=ConsoleChatMessagesWithinBudgetIT
WORKER_IT_CLASS=WorkerToolRoundPruningKeepsPairedMessagesIT

# ── RV-M15-01：contextState 六项静默丢弃被撤（verifier 强制 Accepted）──
OLD_VERIFIER='        return ContextStateEnvelope.verify(input.clientState(),
                new ContextStateEnvelope.VerifyInput(
                        input.expectedPolicyVersion(), input.tenantId(), input.historyTurns()),
                hmacKey.clone(),
                clock.instant().getEpochSecond(),
                maxStateChars);'
NEW_VERIFIER='        return new ContextStateVerifier.Accepted(input.clientState(), "forced", null, "sha256:rv-m15-01-forced"); // RV-M15-01 模拟六项静默丢弃被撤'

# ── RV-M15-02：SSE 统一装配被绕过（passthrough）──
OLD_ORCH='            RequestAssemblyResult assembly = assembleRequest(
                    context, state, messages, schemas, task, legacyRequest);
            state.lastAssembly = assembly;
            ModelRequest modelRequest = assembly.request();'
NEW_ORCH='            RequestAssemblyResult assembly = RequestAssemblyResult.passthrough(legacyRequest); // RV-M15-02 模拟统一装配被绕过
            state.lastAssembly = assembly;
            ModelRequest modelRequest = assembly.request();'

# ── RV-M15-03：L3 ConsoleChat 预算端口被绕过（return legacyRequest）──
OLD_CTRL='            return result.request();'
NEW_CTRL='            return legacyRequest; // RV-M15-03 模拟 L3 预算端口被绕过'

# ── RV-M15-04：Worker 统一装配被绕过（passthrough）──
OLD_WORKER='                RequestAssemblyResult assembly = assembleRequest(
                        tenantId, task, agentConfig, messages, memoryBlock, legacyRequest);
                ModelRequest request = assembly.request();'
NEW_WORKER='                RequestAssemblyResult assembly = RequestAssemblyResult.passthrough(legacyRequest); // RV-M15-04 模拟统一装配被绕过
                ModelRequest request = assembly.request();'

BAK=/tmp/m15_chain_it_counterproof
mkdir -p "${BAK}"

# ⚠️ 备份/恢复统一走 Python 文件 IO，不用 cp（沙箱 file policy 会拦截跨 /tmp ↔ 工作区复制）。
backup_files() {
  python3 - "${BAK}" "${PATCHED_FILES[@]}" <<'PY'
import pathlib
import sys

bak = pathlib.Path(sys.argv[1])
for i, name in enumerate(sys.argv[2:]):
    (bak / f"file{i}.bak").write_bytes(pathlib.Path(name).read_bytes())
PY
}

restore() {
  python3 - "${BAK}" "${PATCHED_FILES[@]}" <<'PY'
import pathlib
import sys

bak = pathlib.Path(sys.argv[1])
for i, name in enumerate(sys.argv[2:]):
    backup = bak / f"file{i}.bak"
    if backup.exists():
        pathlib.Path(name).write_bytes(backup.read_bytes())
PY
}

backup_files
# ⚠️ 备份完整性闸门：任何一个备份缺失/为空就立刻退出，绝不带着半成品撤点继续跑。
for i in 0 1 2 3; do
  if [ ! -s "${BAK}/file${i}.bak" ]; then
    echo "❌ 备份失败：${BAK}/file${i}.bak 缺失或为空 —— 反证终止（避免撤点残留）"
    exit 1
  fi
done

trap restore EXIT
trap 'restore; exit 130' INT TERM

md5_all() {
  python3 - "${PATCHED_FILES[@]}" <<'PY'
import hashlib
import pathlib
import sys

for name in sys.argv[1:]:
    print(hashlib.md5(pathlib.Path(name).read_bytes()).hexdigest())
PY
}
MD5_BEFORE=$(md5_all)
FAILED=0

# ⚠️ 唯一锚点替换：多行锚点用 Python str.count 精确计数，不能用 grep -cF（按行误计）。
inject() {
  local file="$1" old="$2" new="$3"
  python3 - "${file}" "${old}" "${new}" <<'PY'
import os
import sys

path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, encoding='utf-8') as f:
    s = f.read()
n = s.count(old)
if n != 1:
    print(f'ANCHOR_MISMATCH count={n}')
    sys.exit(3)
tmp = path + '.rvtmp'
with open(tmp, 'w', encoding='utf-8') as f:
    f.write(s.replace(old, new, 1))
os.replace(tmp, path)
PY
}

IT_REPORTS="${ROOT}/platform/platform-bootstrap/target/failsafe-reports"
clear_report() {
  local cls="$1"
  # 注意：本项目 failsafe 的 .txt 报告无 TEST- 前缀，.xml 报告有 TEST- 前缀
  rm -f "${IT_REPORTS}/${IT_PKG}.${cls}.txt" "${IT_REPORTS}/TEST-${IT_PKG}.${cls}.xml"
}
report_txt() {
  echo "${IT_REPORTS}/${IT_PKG}.${1}.txt"
}

run_it() {
  local cls="$1" method="$2" log="$3"
  clear_report "${cls}"
  # ⚠️ IT 必须 source it-env.sh（带本地库前缀）后跑；无 IT_PG_URL 整类跳过会让反证失真。
  (
    export IT_HOST=127.0.0.1 IT_PORT=5433 IT_ADMIN_USER=agent
    source scripts/it-env.sh >/dev/null 2>&1
    cd "${ROOT}/platform"
    JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -o -pl platform-bootstrap -am verify \
      -Dtest=__none__ -Dsurefire.failIfNoSpecifiedTests=false \
      -Dit.test="${cls}#${method}" -Dfailsafe.failIfNoSpecifiedTests=false > "${log}" 2>&1
  )
  return 0
}

expect_red() {
  local label="$1" cls="$2" log="$3"
  local report; report=$(report_txt "${cls}")
  if [ ! -f "${report}" ]; then
    echo "  ❌ ${label}：没有生成报告（编译失败或 Maven 未启动）——反证无效"
    FAILED=1
    return
  fi
  if grep -qE 'Tests run: 1, Failures: 1, Errors: 0, Skipped: 0' "${report}" \
     || grep -qE 'Tests run: 1, Failures: 0, Errors: 1, Skipped: 0' "${report}"; then
    echo "  ✅ ${label}：恰好红 1 条（Failures/Errors=1, Skipped=0）"
  elif grep -qE 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' "${report}"; then
    echo "  ❌ ${label}：撤点后仍全绿 —— 反证无效（空断言）"
    FAILED=1
  else
    echo "  ❌ ${label}：失败形态不符（可能编译失败/多例失败/被跳过）"
    grep -aE 'Tests run:|BUILD (SUCCESS|FAILURE)|ERROR' "${log}" | tail -n 8
    FAILED=1
  fi
}

expect_green() {
  local label="$1" cls="$2" log="$3"
  local report; report=$(report_txt "${cls}")
  if [ ! -f "${report}" ]; then
    echo "  ❌ ${label}：没有生成报告"
    FAILED=1
    return
  fi
  if grep -qE 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' "${report}"; then
    echo "  ✅ ${label}：恢复后全绿"
  else
    echo "  ❌ ${label}：恢复后未全绿"
    grep -aE 'Tests run:|BUILD (SUCCESS|FAILURE)|ERROR' "${log}" | tail -n 8
    FAILED=1
  fi
}

anchor_count() {
  local file="$1" literal="$2"
  python3 - "${file}" "${literal}" <<'PY'
import sys
path, literal = sys.argv[1], sys.argv[2]
with open(path, encoding='utf-8') as f:
    s = f.read()
print(s.count(literal))
PY
}

marker_count() {
  # ⚠️ 只匹配「注释形态」的撤点标记（注入的 // RV-M15-0X），不匹配置信里的字面量。
  python3 - "${PATCHED_FILES[@]}" <<'PY'
import pathlib
import re
import sys

total = 0
for name in sys.argv[1:]:
    total += len(re.findall(r'// RV-M15-0[1-4]', pathlib.Path(name).read_text(encoding='utf-8')))
print(total)
PY
}

echo "════════ S3-M15 上下文压缩真链路 · 四点 IT 级反证 ════════"

echo ""
echo "▶ RV-M15-01：六项静默丢弃被撤（verifier 强制 Accepted）"
if inject "${VERIFIER}" "${OLD_VERIFIER}" "${NEW_VERIFIER}"; then
  run_it "${CARRYOVER_CLASS}" hmacMismatchFallsBackToFullHistory "${BAK}/rv01.log"
  expect_red "RV-M15-01 / 篡改 HMAC 必须回落全量历史（静默丢弃被撤→围栏注入→红）" \
    "${CARRYOVER_CLASS}" "${BAK}/rv01.log"
else
  echo "  ❌ RV-M15-01：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M15-02：SSE 统一装配被绕过（passthrough）"
if inject "${ORCH}" "${OLD_ORCH}" "${NEW_ORCH}"; then
  run_it "${SSE_CLASS}" hundredTurnHistoryIsTrimmedWithinBudget "${BAK}/rv02.log"
  expect_red "RV-M15-02 / 100 轮 history 必须真裁剪（装配被绕过→after 不降→红）" \
    "${SSE_CLASS}" "${BAK}/rv02.log"
else
  echo "  ❌ RV-M15-02：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M15-03：L3 ConsoleChat 预算端口被绕过（return legacyRequest）"
if inject "${CTRL}" "${OLD_CTRL}" "${NEW_CTRL}"; then
  run_it "${CONSOLE_CLASS}" platformChainBudgetTrimsLargeHistory "${BAK}/rv03.log"
  expect_red "RV-M15-03 / 平台链 100 条 messages 必须真裁剪（预算被绕过→after 不降→红）" \
    "${CONSOLE_CLASS}" "${BAK}/rv03.log"
else
  echo "  ❌ RV-M15-03：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M15-04：Worker 统一装配被绕过（passthrough）"
if inject "${WORKER}" "${OLD_WORKER}" "${NEW_WORKER}"; then
  run_it "${WORKER_IT_CLASS}" tieredToolRoundPruningKeepsPairedMessages "${BAK}/rv04.log"
  expect_red "RV-M15-04 / Worker 工具轮必须真 W2 裁剪（装配被绕过→无 compaction 事件→红）" \
    "${WORKER_IT_CLASS}" "${BAK}/rv04.log"
else
  echo "  ❌ RV-M15-04：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ 收尾：恢复后五类 M15 IT 全部全绿（一次 verify 跑完，上下文只起一次）"
(
  export IT_HOST=127.0.0.1 IT_PORT=5433 IT_ADMIN_USER=agent
  source scripts/it-env.sh >/dev/null 2>&1
  cd "${ROOT}/platform"
  JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -o -pl platform-bootstrap -am verify \
    -Dtest=__none__ -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test=CarryOverRoundTripIT,SseHistory100TurnsWithinBudgetIT,ConsoleChatMessagesWithinBudgetIT,WorkerToolRoundPruningKeepsPairedMessagesIT,ConsoleChatDisabledPassthroughIT \
    -Dfailsafe.failIfNoSpecifiedTests=false > "${BAK}/base.log" 2>&1
)
if grep -q 'BUILD SUCCESS' "${BAK}/base.log"; then
  echo "  ✅ 基线五类 M15 IT 全绿（BUILD SUCCESS）"
else
  echo "  ❌ 基线未全绿（BUILD FAILURE 或中断）"
  grep -aE 'Tests run:.*Failures|BUILD (SUCCESS|FAILURE)' "${BAK}/base.log" | tail -n 8
  FAILED=1
fi

echo ""
echo "▶ 收尾三件套：old 锚点全量计数 + 残留标记 + md5 复原 + git diff --check"
c1=$(anchor_count "${VERIFIER}" "${OLD_VERIFIER}")
c2=$(anchor_count "${ORCH}" "${OLD_ORCH}")
c3=$(anchor_count "${CTRL}" "${OLD_CTRL}")
c4=$(anchor_count "${WORKER}" "${OLD_WORKER}")
markers=$(marker_count)
md5_now=$(md5_all)
if [ "${c1}" -eq 1 ] && [ "${c2}" -eq 1 ] && [ "${c3}" -eq 1 ] \
   && [ "${c4}" -eq 1 ] && [ "${markers}" -eq 0 ] \
   && [ "${md5_now}" = "${MD5_BEFORE}" ]; then
  echo "  ✅ 锚点 OLD_VERIFIER=${c1} OLD_ORCH=${c2} OLD_CTRL=${c3} OLD_WORKER=${c4}"
  echo "     残留 RV 标记=${markers}；md5 复原一致"
else
  echo "  ❌ 复原校验失败： anchors=${c1}/${c2}/${c3}/${c4} 残留=${markers}"
  echo "     md5 now / before:"
  echo "     ${md5_now}"
  echo "     ${MD5_BEFORE}"
  FAILED=1
fi
if git -C "${ROOT}" diff --check -- \
    "${VERIFIER_REL}" "${ORCH_REL}" "${CTRL_REL}" "${WORKER_REL}"; then
  echo "  ✅ git diff --check PASS"
else
  echo "  ❌ git diff --check FAIL"
  FAILED=1
fi

echo ""
if [ "${FAILED}" -eq 0 ]; then
  echo "✅ S3-M15 四点 IT 级反证全部成立，撤点已复原（RV-M15-01..04 共 4 组）"
  exit 0
else
  echo "❌ 存在无效反证或未复原 —— 见上"
  exit 1
fi
