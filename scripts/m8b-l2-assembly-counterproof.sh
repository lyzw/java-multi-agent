#!/usr/bin/env bash
# S3-M8-b-1（#72）反证脚本：钉住「L2 SSE 接入统一装配入口」不是空转、不是假绿。
#
# ⚠️ 要证伪的是五个独立形态（全部要求「编译通过 + 目标用例真红 + Skipped: 0」）：
#
#   · RV-M8B-01：撤掉 assembly.request()（把送模型的请求改回 legacy）——
#               AgentChatOrchestratorTest#assembledStateSendsAssemblyRequestAndRecordsRealFacts 必须红。
#   · RV-M8B-02：装配态不记 CONTEXT_ASSEMBLED 真实事实（改成关闭态那样静默）——
#               同上的「数字算对」断言必须红。
#   · RV-M8B-03：装配拒绝不 fail-closed（吞掉异常、回退 legacy）——
#               AgentChatOrchestratorTest#assemblyRejectionFailsClosedWithStableCode 必须红。
#   · RV-M8B-04：carry-over 来源被篡改（不再逐字取 M8-a 已验载荷）——
#               AgentChatOrchestratorTest#assemblyCarryOverUsesVerifiedStateOnly 必须红。
#   · RV-M8B-05：S1 守卫白名单升级被撤（L2 加回「禁止 RequestAssemblyPort」）——
#               ContextCompressionS1GuardTest#closedEquivalenceHasNoAlgorithmLayerLeak 必须红。
#
# ⚠️ 判定：结论不变（仍全绿）或编译失败（无报告）均判**无效反证**。
# ⚠️ 中断安全：SIGTERM/SIGINT 时先复原当前撤点文件再退出，
#    避免残留撤点源码造成后续静默失真（M10 曾吃过这个亏）。
set -u

ROOT=/Users/zhouwei/code-workspace/ai-tech/java-multi-agent

ORCHESTRATOR_REL=platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/agentchat/AgentChatOrchestrator.java
S1_GUARD_REL=platform/platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/config/ContextCompressionS1GuardTest.java

ORCHESTRATOR="${ROOT}/${ORCHESTRATOR_REL}"
S1_GUARD="${ROOT}/${S1_GUARD_REL}"
PATCHED_FILES=("${ORCHESTRATOR}" "${S1_GUARD}")

ORCHESTRATOR_CLASS=cn.studykid.multiagent.worker.agentchat.AgentChatOrchestratorTest
S1_GUARD_CLASS=cn.studykid.multiagent.bootstrap.config.ContextCompressionS1GuardTest
BAK=/tmp/m8b_l2_assembly_counterproof

# RV-M8B-01：唯一送模型请求改回 legacy（装配层空转）
OLD_REQUEST='            ModelRequest modelRequest = assembly.request();'
NEW_REQUEST='            ModelRequest modelRequest = legacyRequest; // RV-M8B-01'

# RV-M8B-02：装配态审计事实改为关闭态全 0（tier/token 不再真实）
OLD_FACTS='                state.events().add(event("CONTEXT_ASSEMBLED", Map.of(
                        "tier", a.tier(),
                        "fallback", a.fallback(),
                        "beforeTokens", a.beforeTokens(),
                        "afterTokens", a.afterTokens(),
                        "compactionCount", a.compactionCount())));'
NEW_FACTS='                state.events().add(event("CONTEXT_ASSEMBLED", Map.of(
                        "tier", 0, "fallback", false, "beforeTokens", 0,
                        "afterTokens", 0, "compactionCount", 0))); // RV-M8B-02'

# RV-M8B-03：装配拒绝被吞 → 稳定码丢失、静默回退
OLD_REJECT='        RequestAssemblyException assembly = findCause(e, RequestAssemblyException.class);
        if (assembly != null) {
            return assembly.reason().name();
        }'
NEW_REJECT='        RequestAssemblyException assembly = findCause(e, RequestAssemblyException.class);
        if (false && assembly != null) {
            return assembly.reason().name();
        } // RV-M8B-03'

# RV-M8B-04：carry-over 来源被篡改（对 M8-a 已验载荷拼接后缀，等于不逐字取自已验载荷）
OLD_CARRY='                state.carryOver == null ? null : state.carryOver.stateJson(),'
NEW_CARRY='                state.carryOver == null ? null : state.carryOver.stateJson() + "_tampered", // RV-M8B-04'

# RV-M8B-05：S1 守卫白名单升级被撤——把 L2 加回「禁止 RequestAssemblyPort」
OLD_GUARD='        assertThat(console)
                .as("⛔ L3 Console 结构证据边界被扩大：Console 仍 contract-only，不得接统一装配入口")
                .doesNotContain("RequestAssemblyPort");'
NEW_GUARD='        assertThat(console)
                .as("⛔ L3 Console 结构证据边界被扩大：Console 仍 contract-only，不得接统一装配入口")
                .doesNotContain("RequestAssemblyPort");
        // RV-M8B-05：模拟守卫升级被撤——重新禁止 L2 接统一装配入口
        assertThat(orchestrator).doesNotContain("RequestAssemblyPort");'

mkdir -p "${BAK}"
i=0
for file in "${PATCHED_FILES[@]}"; do
  cp "${file}" "${BAK}/file${i}.bak"
  i=$((i + 1))
done

restore() {
  local n=0
  for file in "${PATCHED_FILES[@]}"; do
    cp "${BAK}/file${n}.bak" "${file}"
    n=$((n + 1))
  done
}
trap restore EXIT
trap 'restore; exit 130' INT TERM

md5_all() {
  local file
  for file in "${PATCHED_FILES[@]}"; do
    md5 -q "${file}"
  done
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
    agent-worker) echo "${ROOT}/platform/agent-worker/target/surefire-reports" ;;
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
  if grep -qE 'Tests run: 1, Failures: 1, Errors: 0, Skipped: 0' "${report}"; then
    echo "  ✅ ${label}：恰好红 1 条（Failures=1, Errors=0, Skipped=0）"
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
  # ⚠️ 只匹配「注释形态」的撤点标记（注入的 // RV-M8B-0X），
  #    不匹配守卫纪律断言里的 .contains("RV-M8B-0X") 字面量——那是合法常驻代码，不是残留。
  grep -hE '// RV-M8B-0[1-5]' "${PATCHED_FILES[@]}" 2>/dev/null | wc -l | tr -d ' '
}

echo "════════ S3-M8-b-1 L2 SSE 统一装配 · 五点反证 ════════"

echo ""
echo "▶ RV-M8B-01：送模型请求改回 legacy（装配层空转）"
restore
if inject "${ORCHESTRATOR}" "${OLD_REQUEST}" "${NEW_REQUEST}"; then
  run_unit agent-worker "AgentChatOrchestratorTest#assembledStateSendsAssemblyRequestAndRecordsRealFacts" \
    "${ORCHESTRATOR_CLASS}" "${BAK}/rv01.log"
  expect_red "RV-M8B-01 / 唯一送模型请求必须真红" \
    "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/rv01.log"
else
  echo "  ❌ RV-M8B-01：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8B-02：装配态不记真实 tier/token 事实（静默降级）"
if inject "${ORCHESTRATOR}" "${OLD_FACTS}" "${NEW_FACTS}"; then
  run_unit agent-worker "AgentChatOrchestratorTest#assembledStateSendsAssemblyRequestAndRecordsRealFacts" \
    "${ORCHESTRATOR_CLASS}" "${BAK}/rv02.log"
  expect_red "RV-M8B-02 / 真实装配事实必须真红" \
    "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/rv02.log"
else
  echo "  ❌ RV-M8B-02：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8B-03：装配拒绝被吞（fail-open 回退 legacy）"
if inject "${ORCHESTRATOR}" "${OLD_REJECT}" "${NEW_REJECT}"; then
  run_unit agent-worker "AgentChatOrchestratorTest#assemblyRejectionFailsClosedWithStableCode" \
    "${ORCHESTRATOR_CLASS}" "${BAK}/rv03.log"
  expect_red "RV-M8B-03 / fail-closed 稳定码必须真红" \
    "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/rv03.log"
else
  echo "  ❌ RV-M8B-03：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8B-04：carry-over 来源被篡改（不逐字取 M8-a 已验载荷）"
if inject "${ORCHESTRATOR}" "${OLD_CARRY}" "${NEW_CARRY}"; then
  run_unit agent-worker "AgentChatOrchestratorTest#assemblyCarryOverUsesVerifiedStateOnly" \
    "${ORCHESTRATOR_CLASS}" "${BAK}/rv04.log"
  expect_red "RV-M8B-04 / carry-over 只取已验载荷必须真红" \
    "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/rv04.log"
else
  echo "  ❌ RV-M8B-04：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8B-05：S1 守卫白名单升级被撤（L2 加回禁止）"
if inject "${S1_GUARD}" "${OLD_GUARD}" "${NEW_GUARD}"; then
  run_unit platform-bootstrap "ContextCompressionS1GuardTest#closedEquivalenceHasNoAlgorithmLayerLeak" \
    "${S1_GUARD_CLASS}" "${BAK}/rv05.log"
  expect_red "RV-M8B-05 / S1 白名单升级守卫必须真红" \
    "$(report_dir platform-bootstrap)/${S1_GUARD_CLASS}.txt" "${BAK}/rv05.log"
else
  echo "  ❌ RV-M8B-05：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ 收尾：恢复后五个目标用例都必须全绿"
run_unit agent-worker "AgentChatOrchestratorTest#assembledStateSendsAssemblyRequestAndRecordsRealFacts" \
  "${ORCHESTRATOR_CLASS}" "${BAK}/base01.log"
expect_green "基线 / 装配态送模型 + 真实事实" \
  "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/base01.log"
run_unit agent-worker "AgentChatOrchestratorTest#assemblyRejectionFailsClosedWithStableCode" \
  "${ORCHESTRATOR_CLASS}" "${BAK}/base03.log"
expect_green "基线 / 装配拒绝 fail-closed" \
  "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/base03.log"
run_unit agent-worker "AgentChatOrchestratorTest#assemblyCarryOverUsesVerifiedStateOnly" \
  "${ORCHESTRATOR_CLASS}" "${BAK}/base04.log"
expect_green "基线 / carry-over 只取已验载荷" \
  "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/base04.log"
run_unit platform-bootstrap "ContextCompressionS1GuardTest#closedEquivalenceHasNoAlgorithmLayerLeak" \
  "${S1_GUARD_CLASS}" "${BAK}/base05.log"
expect_green "基线 / S1 白名单升级守卫" \
  "$(report_dir platform-bootstrap)/${S1_GUARD_CLASS}.txt" "${BAK}/base05.log"

echo ""
echo "▶ 收尾三件套：old 锚点全量计数 + 残留标记 + md5 复原 + git diff --check"
c1=$(anchor_count "${ORCHESTRATOR}" "${OLD_REQUEST}")
c2=$(anchor_count "${ORCHESTRATOR}" "${OLD_FACTS}")
c3=$(anchor_count "${ORCHESTRATOR}" "${OLD_REJECT}")
c4=$(anchor_count "${ORCHESTRATOR}" "${OLD_CARRY}")
c5=$(anchor_count "${S1_GUARD}" "${OLD_GUARD}")
markers=$(marker_count)
md5_now=$(md5_all)
if [ "${c1}" -eq 1 ] && [ "${c2}" -eq 1 ] && [ "${c3}" -eq 1 ] && [ "${c4}" -eq 1 ] \
   && [ "${c5}" -eq 1 ] && [ "${markers}" -eq 0 ] && [ "${md5_now}" = "${MD5_BEFORE}" ]; then
  echo "  ✅ 锚点 OLD_REQUEST=${c1} OLD_FACTS=${c2} OLD_REJECT=${c3} OLD_CARRY=${c4} OLD_GUARD=${c5}"
  echo "     残留 RV 标记=${markers}；md5 复原一致"
else
  echo "  ❌ 复原校验失败： anchors=${c1}/${c2}/${c3}/${c4}/${c5} 残留=${markers}"
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
  echo "✅ S3-M8-b-1 五点反证全部成立，撤点已复原"
  exit 0
else
  echo "❌ 存在无效反证或未复原 —— 见上"
  exit 1
fi
