#!/usr/bin/env bash
# S3-M13（#65）反证脚本：钉住「两前端同批发 committed contextState」不是空转、不是假绿。
#
# ⚠️ 要证伪的是六个独立形态（全部要求「编译通过 + 目标用例真红 + Skipped: 0」；
#    真红允许 Failures=1 或 Errors=1，但编译失败 / 无报告 / 全绿一律判无效反证）：
#
#   · RV-M13-01：两前端请求 DTO 的 contextState 字段被改名（请求侧无类型出口）——
#               ContextCompressionM13GuardTest#requestContractsCarryOnlyCommittedState 必须红。
#   · RV-M13-02：真实 JSON.stringify body 的 state 请求键被改名 / 丢掉——
#               ContextCompressionM6GuardTest#frontendProtocolV11SendsCommittedStateOnly 必须红。
#   · RV-M13-03：hook 把 committed state 换成伪造常量（页面可绕过提交闸门）——
#               ContextCompressionM13GuardTest#hooksInjectOnlyCommittedState 必须红。
#   · RV-M13-04：hook 不再从 currentContextCheckpoint 取 state——
#               ContextCompressionM6GuardTest#frontendProtocolV11SendsCommittedStateOnly 必须红。
#   · RV-M13-05：页面参数重新可传 contextState（Omit 白名单被撤）——
#               ContextCompressionM13GuardTest#hooksInjectOnlyCommittedState 必须红。
#   · RV-M13-06：STOP 不再 abort 在途请求（停止动作可能把半截上下文再送一次）——
#               ContextCompressionM6GuardTest#frontendProtocolV11SendsCommittedStateOnly 必须红。
#
# ⚠️ 中断安全：SIGTERM/SIGINT 时先复原当前撤点文件再退出，
#    避免残留撤点源码造成后续静默失真（M10 曾吃过这个亏）。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

TENANT_API_REL=frontend/src/api/agentChatApi.ts
ADMIN_API_REL=admin-console/src/api/agentChatApi.ts
TENANT_HOOK_REL=frontend/src/features/agentChat/useAgentChat.ts
ADMIN_HOOK_REL=admin-console/src/features/aiChat/useAgentChat.ts

TENANT_API="${ROOT}/${TENANT_API_REL}"
ADMIN_API="${ROOT}/${ADMIN_API_REL}"
TENANT_HOOK="${ROOT}/${TENANT_HOOK_REL}"
ADMIN_HOOK="${ROOT}/${ADMIN_HOOK_REL}"

TRANSPORTS=("${TENANT_API}" "${ADMIN_API}")
HOOKS=("${TENANT_HOOK}" "${ADMIN_HOOK}")
PATCHED_FILES=("${TENANT_API}" "${ADMIN_API}" "${TENANT_HOOK}" "${ADMIN_HOOK}")

GUARD13_CLASS=cn.studykid.multiagent.bootstrap.config.ContextCompressionM13GuardTest
GUARD6_CLASS=cn.studykid.multiagent.bootstrap.config.ContextCompressionM6GuardTest
BAK=/tmp/m13_frontend_context_state_counterproof

# RV-M13-01：两前端请求 DTO 的 state 字段被改名（body 仍引用旧字段，Java 守卫不编译 TS）
OLD_DTO='  contextState?: string'
NEW_DTO='  contextStateForged?: string // RV-M13-01 模拟请求 DTO 未开放 state 字段'

# RV-M13-02：真实请求体未携带 committed state（键被改成响应字段名）
OLD_BODY='            contextState: params.contextState,'
NEW_BODY='            contextStateVersion: params.contextState, // RV-M13-02 模拟请求体未携带 state 键'

# RV-M13-03：hook 不取 committed state，直接塞伪造常量
OLD_VALUE='            contextState: committed?.state,'
NEW_VALUE="            contextState: 'runtime-forged', // RV-M13-03 模拟 hook 未取 committed state"

# RV-M13-04：hook 不再从已提交 checkpoint 投影
OLD_SOURCE='          const committed = currentContextCheckpoint(stateRef.current)'
NEW_SOURCE='          const committed = null // RV-M13-04 模拟未从 committed checkpoint 取值'

# RV-M13-05：页面参数重新可传 state（Omit 白名单被撤）
OLD_OMIT="export type AgentChatParams = Omit<AgentChatRequest, 'message' | 'history' | 'contextState'>"
NEW_OMIT="export type AgentChatParams = Omit<AgentChatRequest, 'message' | 'history' | 'source'> // RV-M13-05 模拟页面可传 state"

# RV-M13-06：STOP 不再 abort 在途请求（保留 dispatch，避免混入其它失败形态）
OLD_STOP='''    controllerRef.current = null
    controller.abort()
    // 不 dispatch FAIL：用户主动停止是正常操作。'''
NEW_STOP='''    controllerRef.current = null
    void controller // RV-M13-06 模拟 STOP 未 abort 在途请求
    // 不 dispatch FAIL：用户主动停止是正常操作。'''

mkdir -p "${BAK}"

# ⚠️ 备份/恢复统一走 Python 文件 IO，不用 cp / grep / md5：
#    沙箱 file policy 会拦截 cp 跨 /tmp ↔ 工作区复制未跟踪文件，
#    曾导致备份从未生成、restore 静默 ENOENT、撤点残留源码而收尾误判。
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
    print(f'ANCHOR_MISMATCH {path} count={n}')
    sys.exit(3)
tmp = path + '.rvtmp'
with open(tmp, 'w', encoding='utf-8') as f:
    f.write(s.replace(old, new, 1))
os.replace(tmp, path)
PY
}

inject_both() {
  local old="$1" new="$2"
  local file
  for file in "${TRANSPORTS[@]}"; do
    inject "${file}" "${old}" "${new}" || return 1
  done
}

inject_hooks() {
  local old="$1" new="$2"
  local file
  for file in "${HOOKS[@]}"; do
    inject "${file}" "${old}" "${new}" || return 1
  done
}

REPORT_DIR="${ROOT}/platform/platform-bootstrap/target/surefire-reports"

clear_report() {
  local cls="$1"
  rm -f "${REPORT_DIR}/${cls}.txt" "${REPORT_DIR}/TEST-${cls}.xml"
}

run_guard() {
  local test="$1" cls="$2" log="$3"
  clear_report "${cls}"
  # ⚠️ 故意不 return mvn 的码：红/绿只以**报告**判定，编译失败由「无报告」拦截。
  JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -o -f "${ROOT}/platform/pom.xml" \
    -pl platform-bootstrap -am clean test \
    -Dtest="${test}" -Dsurefire.failIfNoSpecifiedTests=false > "${log}" 2>&1
  return 0
}

expect_red() {
  local label="$1" cls="$2" log="$3"
  local report="${REPORT_DIR}/${cls}.txt"
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
  local report="${REPORT_DIR}/${cls}.txt"
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
  # ⚠️ 只匹配注入的 // RV-M13-0X 注释，不匹配守卫 / 脚本里的合法字面量。
  python3 - "${PATCHED_FILES[@]}" <<'PY'
import pathlib
import re
import sys

total = 0
for name in sys.argv[1:]:
    total += len(re.findall(r'// RV-M13-0[1-6]', pathlib.Path(name).read_text(encoding='utf-8')))
print(total)
PY
}

echo "════════ S3-M13 两前端 committed contextState · 六点反证 ════════"

echo ""
echo "▶ RV-M13-01：请求 DTO 的 state 字段被改名（请求侧无类型出口）"
if inject_both "${OLD_DTO}" "${NEW_DTO}"; then
  run_guard "ContextCompressionM13GuardTest#requestContractsCarryOnlyCommittedState" \
    "${GUARD13_CLASS}" "${BAK}/rv01.log"
  expect_red "RV-M13-01 / DTO 正面字段必须真红" "${GUARD13_CLASS}" "${BAK}/rv01.log"
else
  echo "  ❌ RV-M13-01：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M13-02：真实 JSON.stringify body 的 state 请求键被撤"
if inject_both "${OLD_BODY}" "${NEW_BODY}"; then
  run_guard "ContextCompressionM6GuardTest#frontendProtocolV11SendsCommittedStateOnly" \
    "${GUARD6_CLASS}" "${BAK}/rv02.log"
  expect_red "RV-M13-02 / body 正面键必须真红" "${GUARD6_CLASS}" "${BAK}/rv02.log"
else
  echo "  ❌ RV-M13-02：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M13-03：hook 用伪造常量替代 committed state"
if inject_hooks "${OLD_VALUE}" "${NEW_VALUE}"; then
  run_guard "ContextCompressionM13GuardTest#hooksInjectOnlyCommittedState" \
    "${GUARD13_CLASS}" "${BAK}/rv03.log"
  expect_red "RV-M13-03 / hook 唯一注入口必须真红" "${GUARD13_CLASS}" "${BAK}/rv03.log"
else
  echo "  ❌ RV-M13-03：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M13-04：hook 不再从 committed checkpoint 投影"
if inject_hooks "${OLD_SOURCE}" "${NEW_SOURCE}"; then
  run_guard "ContextCompressionM6GuardTest#frontendProtocolV11SendsCommittedStateOnly" \
    "${GUARD6_CLASS}" "${BAK}/rv04.log"
  expect_red "RV-M13-04 / committed 投影锚点必须真红" "${GUARD6_CLASS}" "${BAK}/rv04.log"
else
  echo "  ❌ RV-M13-04：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M13-05：页面参数重新可传 contextState（Omit 白名单被撤）"
if inject_hooks "${OLD_OMIT}" "${NEW_OMIT}"; then
  run_guard "ContextCompressionM13GuardTest#hooksInjectOnlyCommittedState" \
    "${GUARD13_CLASS}" "${BAK}/rv05.log"
  expect_red "RV-M13-05 / 页面禁传 state 必须真红" "${GUARD13_CLASS}" "${BAK}/rv05.log"
else
  echo "  ❌ RV-M13-05：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M13-06：STOP 不再 abort 在途请求"
if inject_hooks "${OLD_STOP}" "${NEW_STOP}"; then
  run_guard "ContextCompressionM6GuardTest#frontendProtocolV11SendsCommittedStateOnly" \
    "${GUARD6_CLASS}" "${BAK}/rv06.log"
  expect_red "RV-M13-06 / STOP abort 必须真红" "${GUARD6_CLASS}" "${BAK}/rv06.log"
else
  echo "  ❌ RV-M13-06：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ 收尾：恢复后六个基线用例都必须全绿"
run_guard "ContextCompressionM13GuardTest#requestContractsCarryOnlyCommittedState" \
  "${GUARD13_CLASS}" "${BAK}/base01.log"
expect_green "基线 / DTO + body 白名单正面收发" "${GUARD13_CLASS}" "${BAK}/base01.log"
run_guard "ContextCompressionM6GuardTest#frontendProtocolV11SendsCommittedStateOnly" \
  "${GUARD6_CLASS}" "${BAK}/base02.log"
expect_green "基线 / 解码器 + body 正面收发" "${GUARD6_CLASS}" "${BAK}/base02.log"
run_guard "ContextCompressionM13GuardTest#hooksInjectOnlyCommittedState" \
  "${GUARD13_CLASS}" "${BAK}/base03.log"
expect_green "基线 / hook 唯一注入口" "${GUARD13_CLASS}" "${BAK}/base03.log"
run_guard "ContextCompressionM6GuardTest#frontendProtocolV11SendsCommittedStateOnly" \
  "${GUARD6_CLASS}" "${BAK}/base04.log"
expect_green "基线 / committed 投影锚点" "${GUARD6_CLASS}" "${BAK}/base04.log"
run_guard "ContextCompressionM13GuardTest#hooksInjectOnlyCommittedState" \
  "${GUARD13_CLASS}" "${BAK}/base05.log"
expect_green "基线 / 页面参数禁传 state" "${GUARD13_CLASS}" "${BAK}/base05.log"
run_guard "ContextCompressionM6GuardTest#frontendProtocolV11SendsCommittedStateOnly" \
  "${GUARD6_CLASS}" "${BAK}/base06.log"
expect_green "基线 / STOP abort" "${GUARD6_CLASS}" "${BAK}/base06.log"

echo ""
echo "▶ 收尾三件套：old 锚点全量计数 + 残留标记 + md5 复原 + git diff --check"
counts=""
ok=1
TRANSPORT_NAMES=(DTO BODY)
TRANSPORT_VALUES=("${OLD_DTO}" "${OLD_BODY}")
HOOK_ANCHOR_NAMES=(VALUE SOURCE OMIT STOP)
HOOK_ANCHOR_VALUES=("${OLD_VALUE}" "${OLD_SOURCE}" "${OLD_OMIT}" "${OLD_STOP}")
for file in "${TRANSPORTS[@]}"; do
  for i in 0 1; do
    c=$(anchor_count "${file}" "${TRANSPORT_VALUES[$i]}")
    counts="${counts} ${TRANSPORT_NAMES[$i]}($(basename "${file}"))=${c}"
    [ "${c}" -eq 1 ] || ok=0
  done
done
for file in "${HOOKS[@]}"; do
  for i in 0 1 2 3; do
    c=$(anchor_count "${file}" "${HOOK_ANCHOR_VALUES[$i]}")
    counts="${counts} ${HOOK_ANCHOR_NAMES[$i]}($(basename "${file}"))=${c}"
    [ "${c}" -eq 1 ] || ok=0
  done
done
markers=$(marker_count)
md5_now=$(md5_all)
if [ "${ok}" -eq 1 ] && [ "${markers}" -eq 0 ] && [ "${md5_now}" = "${MD5_BEFORE}" ]; then
  echo "  ✅ 锚点全量计数：${counts}"
  echo "     残留 RV 标记=${markers}；md5 复原一致"
else
  echo "  ❌ 复原校验失败：${counts}；残留=${markers}"
  echo "     md5 now / before:"
  echo "     ${md5_now}"
  echo "     ${MD5_BEFORE}"
  FAILED=1
fi
if git -C "${ROOT}" diff --check; then
  echo "  ✅ git diff --check PASS"
else
  echo "  ❌ git diff --check FAIL"
  FAILED=1
fi

echo ""
if [ "${FAILED}" -eq 0 ]; then
  echo "✅ S3-M13 六点反证全部成立，撤点已复原"
  exit 0
else
  echo "❌ 存在无效反证或未复原 —— 见上"
  exit 1
fi
