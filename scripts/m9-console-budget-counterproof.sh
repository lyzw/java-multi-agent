#!/usr/bin/env bash
# S3-M9（#64）反证脚本：钉住「L3 Console W1/W2 预算保护真接线 + 关闭态逐位等价 +
# TRIM_ONLY 独立协议 + 唯一送模型请求 + fail-closed + 无会话态」不是空转、不是假绿。
#
# ⚠️ 要证伪的是六个独立形态（全部要求「编译通过 + 目标用例真红 + Skipped: 0」；
#    真红允许 Failures=1 或 Errors=1，但编译失败 / 无报告 / 全绿一律判无效反证）：
#
#   · RV-M9-01：adapter 关闭态零副作用短路被撤（if(false) 仍可编译）——
#               ConsoleBudgetAdapterTest#disabledShortCircuitsWithZeroInteractions 必须红。
#   · RV-M9-02：启用态 TRIM_ONLY 档位门禁被撤（if(false)）——
#               ContextCompressionM9GuardTest#soleAdapterShortCircuitsWhenDisabledAndFailsClosedWhenEnabled
#               必须红（证明 G1 A.5「Console 仍 contract-only → 中性预算端口」升级守卫不是空断言）。
#   · RV-M9-03：Controller 丢弃预算结果、回退 legacy 送模型——
#               ConsoleChatControllerTest#budgetedRequestIsTheOnlyRequestSentToGateway 必须红。
#   · RV-M9-04：预算异常 fail-closed 被撤（catch 内 return legacyRequest）——
#               ConsoleChatControllerTest#budgetPolicyRejectionReturns400BeforeEmitterAndNeverFallsBack
#               必须红。
#   · RV-M9-05：组合根唯一预算端口装配被撤（Bean 返回 null）——
#               ContextCompressionM9GuardTest#configurationWiresOnePortOnTheSingleKernel 必须红。
#   · RV-M9-06：末条 USER 重复发送（history 不再剔除 currentInput）——
#               ConsoleBudgetAdapterTest#enabledW0KeepsStablePrefixAndCopiesNonContextFields 必须红。
#
# ⚠️ 中断安全：SIGTERM/SIGINT 时先复原当前撤点文件再退出，
#    避免残留撤点源码造成后续静默失真（M10 曾吃过这个亏）。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

ADAPTER_REL=platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ConsoleBudgetAdapter.java
CONFIG_REL=platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ConsoleBudgetConfiguration.java
CONTROLLER_REL=platform/agent-api/src/main/java/cn/studykid/multiagent/api/chat/ConsoleChatController.java

ADAPTER="${ROOT}/${ADAPTER_REL}"
CONFIG="${ROOT}/${CONFIG_REL}"
CONTROLLER="${ROOT}/${CONTROLLER_REL}"
PATCHED_FILES=("${ADAPTER}" "${CONFIG}" "${CONTROLLER}")

ADAPTER_CLASS=cn.studykid.multiagent.bootstrap.config.ConsoleBudgetAdapterTest
GUARD_CLASS=cn.studykid.multiagent.bootstrap.config.ContextCompressionM9GuardTest
CONTROLLER_CLASS=cn.studykid.multiagent.api.chat.ConsoleChatControllerTest
BAK=/tmp/m9_console_budget_counterproof

# RV-M9-01：关闭态零副作用短路被撤（if(false) 让分支恒不命中，仍可编译）
OLD_DISABLED='''        if (!global.enabled()) {
            return BudgetResult.passthrough(legacy);
        }'''
NEW_DISABLED='''        if (false) { // RV-M9-01 模拟关闭态零副作用短路被撤
            return BudgetResult.passthrough(legacy);
        }'''

# RV-M9-02：启用态 TRIM_ONLY 档位门禁被撤（L3 可能滑入 W3 / HARD）
OLD_TRIM_ONLY='''            if (effective.strategy() != ContextPolicy.Strategy.TRIM_ONLY) {
                throw new ConsoleBudgetException(Reason.CONTEXT_POLICY_INVALID,
                        "L3 Console 预算保护只支持 TRIM_ONLY，实际为 " + effective.strategy());
            }'''
NEW_TRIM_ONLY='''            if (false) { // RV-M9-02 模拟 TRIM_ONLY 档位门禁被撤
                throw new ConsoleBudgetException(Reason.CONTEXT_POLICY_INVALID,
                        "L3 Console 预算保护只支持 TRIM_ONLY，实际为 " + effective.strategy());
            }'''

# RV-M9-03：唯一送模型请求被撤（预算结果丢弃，legacy 直接进网关）
OLD_RESULT='            return result.request();'
NEW_RESULT='            return legacyRequest; // RV-M9-03 模拟预算结果被丢弃、回退 legacy'

# RV-M9-04：fail-closed 被撤（预算异常变成静默回退，超窗请求悄悄进模型）
OLD_FAILCLOSED='''        } catch (ConsoleBudgetException e) {
            log.warn("对话上下文预算保护拒绝请求: caller={}, reason={}",
                    callerKey, e.reason().name(), e);
            throw new ResponseStatusException(statusOf(e),
                    "对话上下文预算保护失败: " + e.reason().name());
        }'''
NEW_FAILCLOSED='''        } catch (ConsoleBudgetException e) {
            log.warn("对话上下文预算保护拒绝请求: caller={}, reason={}",
                    callerKey, e.reason().name(), e);
            return legacyRequest; // RV-M9-04 模拟 fail-closed 被撤
        }'''

# RV-M9-05：组合根唯一预算端口装配被撤（Controller 注入 Optional.empty，W1/W2 空转）
OLD_BEAN='''        return new ConsoleBudgetAdapter(
                globalContextPolicy, contextAssembler, contextNoOpSummarizer, modelCatalogPort);'''
NEW_BEAN='        return null; // RV-M9-05 模拟组合根未装配 L3 预算端口'

# RV-M9-06：末条 USER 重复发送（history 不再剔除 currentInput）
OLD_PROJECTION='            return new MessageProjection(List.copyOf(all.subList(0, last)), tail.content());'
NEW_PROJECTION='            return new MessageProjection(List.copyOf(all), tail.content()); // RV-M9-06 模拟末条 USER 重复发送'

mkdir -p "${BAK}"

# ⚠️ 备份/恢复统一走 Python 文件 IO，不用 cp / grep / md5：
#    沙箱 file policy 会拦截 cp 跨 /tmp ↔ 工作区复制「未跟踪」文件，
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
for i in 0 1 2; do
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

report_dir() {
  case "$1" in
    agent-api) echo "${ROOT}/platform/agent-api/target/surefire-reports" ;;
    platform-bootstrap) echo "${ROOT}/platform/platform-bootstrap/target/surefire-reports" ;;
    *) echo "UNKNOWN_MODULE:$1" >&2; exit 2 ;;
  esac
}

clear_report() {
  local dir="$1" cls="$2"
  rm -f "${dir}/${cls}.txt" "${dir}/TEST-${cls}.xml"
}

run_unit() {
  local module="$1" test="$2" cls="$3" log="$4"
  local dir
  dir=$(report_dir "${module}")
  clear_report "${dir}" "${cls}"
  # ⚠️ 故意不 return mvn 的码：红/绿只以**报告**判定，编译失败由「无报告」拦截。
  JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -o -f "${ROOT}/platform/pom.xml" \
    -pl "${module}" -am clean test \
    -Dtest="${test}" -Dsurefire.failIfNoSpecifiedTests=false > "${log}" 2>&1
  return 0
}

expect_red() {
  local label="$1" report="$2" log="$3"
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
  local label="$1" report="$2" log="$3"
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
  # ⚠️ 只匹配「注释形态」的撤点标记（注入的 // RV-M9-0X），
  #    不匹配守卫 / 脚本里的 .contains("RV-M9-0X") 字面量——那是合法常驻纪律断言。
  python3 - "${PATCHED_FILES[@]}" <<'PY'
import pathlib
import re
import sys

total = 0
for name in sys.argv[1:]:
    total += len(re.findall(r'// RV-M9-0[1-6]', pathlib.Path(name).read_text(encoding='utf-8')))
print(total)
PY
}

echo "════════ S3-M9 ConsoleChat W1/W2 预算保护 · 六点反证 ════════"

echo ""
echo "▶ RV-M9-01：关闭态零副作用短路被撤"
if inject "${ADAPTER}" "${OLD_DISABLED}" "${NEW_DISABLED}"; then
  run_unit platform-bootstrap "ConsoleBudgetAdapterTest#disabledShortCircuitsWithZeroInteractions" \
    "${ADAPTER_CLASS}" "${BAK}/rv01.log"
  expect_red "RV-M9-01 / 关闭态短路必须真红" \
    "$(report_dir platform-bootstrap)/${ADAPTER_CLASS}.txt" "${BAK}/rv01.log"
else
  echo "  ❌ RV-M9-01：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M9-02：启用态 TRIM_ONLY 档位门禁被撤（A.5 升级守卫必须真红）"
if inject "${ADAPTER}" "${OLD_TRIM_ONLY}" "${NEW_TRIM_ONLY}"; then
  run_unit platform-bootstrap \
    "ContextCompressionM9GuardTest#soleAdapterShortCircuitsWhenDisabledAndFailsClosedWhenEnabled" \
    "${GUARD_CLASS}" "${BAK}/rv02.log"
  expect_red "RV-M9-02 / TRIM_ONLY 门禁守卫必须真红" \
    "$(report_dir platform-bootstrap)/${GUARD_CLASS}.txt" "${BAK}/rv02.log"
else
  echo "  ❌ RV-M9-02：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M9-03：Controller 丢弃预算结果、回退 legacy 送模型"
if inject "${CONTROLLER}" "${OLD_RESULT}" "${NEW_RESULT}"; then
  run_unit agent-api "ConsoleChatControllerTest#budgetedRequestIsTheOnlyRequestSentToGateway" \
    "${CONTROLLER_CLASS}" "${BAK}/rv03.log"
  expect_red "RV-M9-03 / 唯一送模型请求必须真红" \
    "$(report_dir agent-api)/${CONTROLLER_CLASS}.txt" "${BAK}/rv03.log"
else
  echo "  ❌ RV-M9-03：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M9-04：预算异常 fail-closed 被撤（静默回退 legacy）"
if inject "${CONTROLLER}" "${OLD_FAILCLOSED}" "${NEW_FAILCLOSED}"; then
  run_unit agent-api \
    "ConsoleChatControllerTest#budgetPolicyRejectionReturns400BeforeEmitterAndNeverFallsBack" \
    "${CONTROLLER_CLASS}" "${BAK}/rv04.log"
  expect_red "RV-M9-04 / fail-closed 必须真红" \
    "$(report_dir agent-api)/${CONTROLLER_CLASS}.txt" "${BAK}/rv04.log"
else
  echo "  ❌ RV-M9-04：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M9-05：组合根唯一预算端口装配被撤（Controller 空转）"
if inject "${CONFIG}" "${OLD_BEAN}" "${NEW_BEAN}"; then
  run_unit platform-bootstrap \
    "ContextCompressionM9GuardTest#configurationWiresOnePortOnTheSingleKernel" \
    "${GUARD_CLASS}" "${BAK}/rv05.log"
  expect_red "RV-M9-05 / 组合根唯一装配守卫必须真红" \
    "$(report_dir platform-bootstrap)/${GUARD_CLASS}.txt" "${BAK}/rv05.log"
else
  echo "  ❌ RV-M9-05：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M9-06：末条 USER 重复发送（会话投影失真）"
if inject "${ADAPTER}" "${OLD_PROJECTION}" "${NEW_PROJECTION}"; then
  run_unit platform-bootstrap \
    "ConsoleBudgetAdapterTest#enabledW0KeepsStablePrefixAndCopiesNonContextFields" \
    "${ADAPTER_CLASS}" "${BAK}/rv06.log"
  expect_red "RV-M9-06 / 末条 USER 唯一性必须真红" \
    "$(report_dir platform-bootstrap)/${ADAPTER_CLASS}.txt" "${BAK}/rv06.log"
else
  echo "  ❌ RV-M9-06：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ 收尾：恢复后六个基线用例都必须全绿"
run_unit platform-bootstrap "ConsoleBudgetAdapterTest#disabledShortCircuitsWithZeroInteractions" \
  "${ADAPTER_CLASS}" "${BAK}/base01.log"
expect_green "基线 / 关闭态零副作用" \
  "$(report_dir platform-bootstrap)/${ADAPTER_CLASS}.txt" "${BAK}/base01.log"
run_unit platform-bootstrap \
  "ContextCompressionM9GuardTest#soleAdapterShortCircuitsWhenDisabledAndFailsClosedWhenEnabled" \
  "${GUARD_CLASS}" "${BAK}/base02.log"
expect_green "基线 / TRIM_ONLY A.5 升级守卫" \
  "$(report_dir platform-bootstrap)/${GUARD_CLASS}.txt" "${BAK}/base02.log"
run_unit agent-api "ConsoleChatControllerTest#budgetedRequestIsTheOnlyRequestSentToGateway" \
  "${CONTROLLER_CLASS}" "${BAK}/base03.log"
expect_green "基线 / 唯一送模型请求" \
  "$(report_dir agent-api)/${CONTROLLER_CLASS}.txt" "${BAK}/base03.log"
run_unit agent-api \
  "ConsoleChatControllerTest#budgetPolicyRejectionReturns400BeforeEmitterAndNeverFallsBack" \
  "${CONTROLLER_CLASS}" "${BAK}/base04.log"
expect_green "基线 / fail-closed" \
  "$(report_dir agent-api)/${CONTROLLER_CLASS}.txt" "${BAK}/base04.log"
run_unit platform-bootstrap \
  "ContextCompressionM9GuardTest#configurationWiresOnePortOnTheSingleKernel" \
  "${GUARD_CLASS}" "${BAK}/base05.log"
expect_green "基线 / 组合根唯一装配" \
  "$(report_dir platform-bootstrap)/${GUARD_CLASS}.txt" "${BAK}/base05.log"
run_unit platform-bootstrap \
  "ConsoleBudgetAdapterTest#enabledW0KeepsStablePrefixAndCopiesNonContextFields" \
  "${ADAPTER_CLASS}" "${BAK}/base06.log"
expect_green "基线 / 末条 USER 唯一性" \
  "$(report_dir platform-bootstrap)/${ADAPTER_CLASS}.txt" "${BAK}/base06.log"

echo ""
echo "▶ 收尾三件套：old 锚点全量计数 + 残留标记 + md5 复原 + git diff --check"
c1=$(anchor_count "${ADAPTER}" "${OLD_DISABLED}")
c2=$(anchor_count "${ADAPTER}" "${OLD_TRIM_ONLY}")
c3=$(anchor_count "${CONTROLLER}" "${OLD_RESULT}")
c4=$(anchor_count "${CONTROLLER}" "${OLD_FAILCLOSED}")
c5=$(anchor_count "${CONFIG}" "${OLD_BEAN}")
c6=$(anchor_count "${ADAPTER}" "${OLD_PROJECTION}")
markers=$(marker_count)
md5_now=$(md5_all)
if [ "${c1}" -eq 1 ] && [ "${c2}" -eq 1 ] && [ "${c3}" -eq 1 ] \
   && [ "${c4}" -eq 1 ] && [ "${c5}" -eq 1 ] && [ "${c6}" -eq 1 ] \
   && [ "${markers}" -eq 0 ] && [ "${md5_now}" = "${MD5_BEFORE}" ]; then
  echo "  ✅ 锚点 OLD_DISABLED=${c1} OLD_TRIM_ONLY=${c2} OLD_RESULT=${c3}"
  echo "     OLD_FAILCLOSED=${c4} OLD_BEAN=${c5} OLD_PROJECTION=${c6}"
  echo "     残留 RV 标记=${markers}；md5 复原一致"
else
  echo "  ❌ 复原校验失败： anchors=${c1}/${c2}/${c3}/${c4}/${c5}/${c6} 残留=${markers}"
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
  echo "✅ S3-M9 六点反证全部成立，撤点已复原"
  exit 0
else
  echo "❌ 存在无效反证或未复原 —— 见上"
  exit 1
fi
