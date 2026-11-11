#!/usr/bin/env bash
# S2-G2-b 反证脚本：钉住「L1 真跑压缩闭环 IT」不是假绿。
#
# ⚠️ 要证伪的是三个独立的假绿形态：
#
#   · **RV-G2-01（固定 usage 桩）**：把模型桩的「按真实 ModelRequest 计费」
#     换成固定 usage=1000 → ContextCompactionL1RealRunIT 必须**恰好红 1 条**。
#     若仍全绿，说明 usage_record.input_tokens 下降与真实请求无关，
#     「压缩进请求」整条归因链是假的（Sev-0）。
#
#   · **RV-G2-02（实验组策略降级）**：把 TIERED 实验组的冻结策略改成 OFF
#     → 同一 IT 必须**恰好红 1 条**。若仍全绿，说明 onContextCompacted
#     与 token 下降断言是空转，OFF/TIERED 对照没有意义。
#
#   · **RV-G2-03（撤掉统一装配入口）**：把 L1 Worker 真正送模型的请求从
#     `assembly.request()` 改回 `legacyRequest`，但保留装配器调用 →
#     同一 IT 必须**恰好红 1 条**。若仍全绿，说明「生产接线真的用了装配结果」
#     这件事没有被验证，算法绿而请求字节没变（M7-b 接线失效）。
#
# ⚠️ 判定：结论不变（仍全绿）或编译失败均判**无效反证**。
#    编译失败不算「用例红」——它证明不了任何运行时结论。
# ⚠️ 中断安全：SIGTERM/SIGINT 时先复原当前撤点文件再退出，
#    避免残留撤点源码造成后续静默失真。
set -u

ROOT=/Users/zhouwei/code-workspace/ai-tech/java-multi-agent
IT_REL=platform/platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/context/ContextCompactionL1RealRunIT.java
LOOP_REL=platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RunExecutionLoop.java
IT="${ROOT}/${IT_REL}"
LOOP="${ROOT}/${LOOP_REL}"
REPORT="${ROOT}/platform/platform-bootstrap/target/failsafe-reports/cn.studykid.multiagent.bootstrap.context.ContextCompactionL1RealRunIT.txt"
REPORT_XML="${ROOT}/platform/platform-bootstrap/target/failsafe-reports/TEST-cn.studykid.multiagent.bootstrap.context.ContextCompactionL1RealRunIT.xml"
BAK=/tmp/g2_b_counterproof

OLD1='long inputTokens = TOKEN_COUNTER.count(RequestContextProjection.project(request));'
OLD2='policyJson(true, "TIERED"));'
OLD3='                RequestAssemblyResult assembly = assembleRequest(
                        tenantId, task, agentConfig, messages, memoryBlock, legacyRequest);
                ModelRequest request = assembly.request();'

mkdir -p "${BAK}"
cp "${IT}" "${BAK}/it.bak"
cp "${LOOP}" "${BAK}/loop.bak"
MD5_IT=$(md5 -q "${IT}")
MD5_LOOP=$(md5 -q "${LOOP}")
FAILED=0

restore() {
  cp "${BAK}/it.bak" "${IT}"
  cp "${BAK}/loop.bak" "${LOOP}"
}
trap restore EXIT
trap 'restore; exit 130' INT TERM

md5_of() {
  md5 -q "$1"
}

# ⚠️ 唯一锚点替换：锚点必须在目标文件里逐字出现且只出现一次。
inject() {
  local file="$1" old="$2" new="$3"
  python3 - "${file}" "${old}" "${new}" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding='utf-8').read()
n = s.count(old)
if n != 1:
    print(f'ANCHOR_MISMATCH count={n}')
    sys.exit(3)
open(path, 'w', encoding='utf-8').write(s.replace(old, new))
PY
}

run_it() {
  local log="$1"
  # ⚠️ 必须删旧报告：否则编译失败时会读到上一次的报告，制造假红/假绿。
  rm -f "${REPORT}" "${REPORT_XML}"
  (
    cd "${ROOT}" || exit 1
    IT_HOST=127.0.0.1 IT_PORT=5433 IT_ADMIN_USER=agent source scripts/it-env.sh &&
      JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -o -f platform/pom.xml \
        -pl platform-bootstrap -am -Dtest=__none__ \
        -Dsurefire.failIfNoSpecifiedTests=false \
        -Dfailsafe.failIfNoSpecifiedTests=false \
        -Dit.test=ContextCompactionL1RealRunIT verify
  ) > "${log}" 2>&1
  return 0
}

expect_red() {
  local label="$1" log="$2"
  if [ ! -f "${REPORT}" ]; then
    echo "  ❌ ${label}：没有生成 IT 报告（编译失败或 Maven 未启动）——反证无效"
    FAILED=1
    return
  fi
  if grep -qE 'Tests run: 1, Failures: 1, Errors: 0, Skipped: 0' "${REPORT}"; then
    echo "  ✅ ${label}：恰好红 1 条（Failures=1, Errors=0, Skipped=0）"
  elif grep -qE 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' "${REPORT}"; then
    echo "  ❌ ${label}：撤点后仍全绿 —— 反证无效（空断言）"
    FAILED=1
  else
    echo "  ❌ ${label}：失败形态不符（可能编译失败/多例失败/被跳过）"
    grep -aE 'Tests run:|BUILD (SUCCESS|FAILURE)|ERROR' "${log}" | tail -n 8
    FAILED=1
  fi
}

expect_green() {
  local label="$1" log="$2"
  if [ ! -f "${REPORT}" ]; then
    echo "  ❌ ${label}：没有生成 IT 报告"
    FAILED=1
    return
  fi
  if grep -qE 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' "${REPORT}"; then
    echo "  ✅ ${label}：恢复后全绿"
  else
    echo "  ❌ ${label}：恢复后未全绿"
    grep -aE 'Tests run:|BUILD (SUCCESS|FAILURE)|ERROR' "${log}" | tail -n 8
    FAILED=1
  fi
}

anchor_count() {
  # ⚠️ 不能用 grep -cF：多行锚点会被拆成「任一行命中即计数」，
  #    三段连续源码会被误计成 3，复原校验假红。用 Python 做精确子串计数。
  local file="$1" literal="$2"
  python3 - "${file}" "${literal}" <<'PY'
import sys
path, literal = sys.argv[1], sys.argv[2]
s = open(path, encoding='utf-8').read()
print(s.count(literal))
PY
}

marker_count() {
  grep -hE 'RV-G2-0[123]' "${IT}" "${LOOP}" 2>/dev/null | wc -l | tr -d ' '
}

echo "════════ S2-G2-b L1 真跑闭环 · 三点反证 ════════"

echo ""
echo "▶ RV-G2-01：把模型桩改成固定 usage（禁用按请求计费）"
restore
if inject "${IT}" "${OLD1}" 'long inputTokens = 1000L; // RV-G2-01 撤点：固定 usage（反证假绿）'; then
  run_it "${BAK}/rv01.log"
  expect_red "RV-G2-01（固定 usage 必须让 IT 真红）" "${BAK}/rv01.log"
else
  echo "  ❌ RV-G2-01：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-G2-02：把 TIERED 实验组降级成 OFF"
if inject "${IT}" "${OLD2}" 'policyJson(false, "OFF")); // RV-G2-02 撤点：实验组降级'; then
  run_it "${BAK}/rv02.log"
  expect_red "RV-G2-02（无压缩必须让 IT 真红）" "${BAK}/rv02.log"
else
  echo "  ❌ RV-G2-02：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-G2-03：撤掉统一装配入口，L1 回退 legacyRequest"
if inject "${LOOP}" "${OLD3}" '                // RV-G2-03 撤点：统一装配入口被撤掉，直接回退 legacy 请求
                RequestAssemblyResult assembly = RequestAssemblyResult.passthrough(legacyRequest);
                ModelRequest request = legacyRequest;'; then
  run_it "${BAK}/rv03.log"
  expect_red "RV-G2-03（撤掉统一装配入口必须让 IT 真红）" "${BAK}/rv03.log"
else
  echo "  ❌ RV-G2-03：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ 收尾：恢复后基线必须全绿"
run_it "${BAK}/baseline.log"
expect_green "基线（恢复后全绿）" "${BAK}/baseline.log"

echo ""
echo "▶ 收尾三件套：old 锚点计数 + 残留标记 + md5 复原 + git diff --check"
c1=$(anchor_count "${IT}" "${OLD1}")
c2=$(anchor_count "${IT}" "${OLD2}")
c3=$(anchor_count "${LOOP}" "${OLD3}")
markers=$(marker_count)
md5_it_now=$(md5_of "${IT}")
md5_loop_now=$(md5_of "${LOOP}")
if [ "${c1}" -eq 1 ] && [ "${c2}" -eq 1 ] && [ "${c3}" -eq 1 ] \
   && [ "${markers}" -eq 0 ] \
   && [ "${md5_it_now}" = "${MD5_IT}" ] && [ "${md5_loop_now}" = "${MD5_LOOP}" ]; then
  echo "  ✅ 锚点 OLD1=${c1} OLD2=${c2} OLD3=${c3}；残留 RV 标记=${markers}；md5 双文件复原一致"
else
  echo "  ❌ 复原校验失败：OLD1=${c1} OLD2=${c2} OLD3=${c3} 残留=${markers}"
  echo "     IT md5 ${md5_it_now} / ${MD5_IT}；LOOP md5 ${md5_loop_now} / ${MD5_LOOP}"
  FAILED=1
fi
if (cd "${ROOT}" && git diff --check); then
  echo "  ✅ git diff --check PASS"
else
  echo "  ❌ git diff --check FAIL"
  FAILED=1
fi

echo ""
if [ "${FAILED}" -eq 0 ]; then
  echo "✅ 三条反证全部成立，撤点已复原"
  exit 0
else
  echo "❌ 存在无效反证或未复原 —— 见上"
  exit 1
fi
