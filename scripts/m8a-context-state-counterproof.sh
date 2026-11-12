#!/usr/bin/env bash
# S3-M8-a 反证脚本：钉住「不可信 contextState 静默丢弃」不是假绿。
#
# ⚠️ 要证伪的是八个独立的假绿形态（全部要求「编译通过 + 目标用例真红」）：
#
#   · RV-M8A-01：accepted 分支不下传已验 state，AgentChatOrchestratorTest 必须红。
#   · RV-M8A-02：discarded 分支保留原始 state（全量历史降级失效），行为测试必须红。
#   · RV-M8A-03：空 HMAC 密钥从 KEY_UNAVAILABLE 改成 Absent（fail-open），装配测试必须红。
#   · RV-M8A-04：exp 校验短路，过期 state 被接受，信封测试必须红。
#   · RV-M8A-05：全局关闭态不再零读取短路，策略裁决测试必须红。
#   · RV-M8A-06：Controller 把原始 state 改成 null，透传契约测试必须红。
#   · RV-M8A-07：运行期策略读点改读常量 JSON，M3/M8-a 共享裁决守卫必须红。
#   · RV-M8A-08：编排器提前发射 context.checkpoint，M8-a 源码守卫必须红。
#
# ⚠️ 判定：结论不变（仍全绿）或编译失败均判**无效反证**。
# ⚠️ 中断安全：SIGTERM/SIGINT 时先复原当前撤点文件再退出，
#    避免残留撤点源码造成后续静默失真。
set -u

ROOT=/Users/zhouwei/code-workspace/ai-tech/java-multi-agent

ORCHESTRATOR_REL=platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/agentchat/AgentChatOrchestrator.java
VERIFIER_ADAPTER_REL=platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextStateVerifierAdapter.java
ENVELOPE_REL=platform/agent-context/src/main/java/cn/studykid/multiagent/context/ContextStateEnvelope.java
VERSION_ADAPTER_REL=platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextPolicyVersionAdapter.java
CONTROLLER_REL=platform/agent-api/src/main/java/cn/studykid/multiagent/api/chat/AgentChatController.java
SUPPORT_REL=platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextPolicyResolutionSupport.java

ORCHESTRATOR="${ROOT}/${ORCHESTRATOR_REL}"
VERIFIER_ADAPTER="${ROOT}/${VERIFIER_ADAPTER_REL}"
ENVELOPE="${ROOT}/${ENVELOPE_REL}"
VERSION_ADAPTER="${ROOT}/${VERSION_ADAPTER_REL}"
CONTROLLER="${ROOT}/${CONTROLLER_REL}"
SUPPORT="${ROOT}/${SUPPORT_REL}"
PATCHED_FILES=("${ORCHESTRATOR}" "${VERIFIER_ADAPTER}" "${ENVELOPE}" \
  "${VERSION_ADAPTER}" "${CONTROLLER}" "${SUPPORT}")

ORCHESTRATOR_CLASS=cn.studykid.multiagent.worker.agentchat.AgentChatOrchestratorTest
VERIFIER_WIRING_CLASS=cn.studykid.multiagent.bootstrap.config.ContextStateVerifierWiringTest
ENVELOPE_CLASS=cn.studykid.multiagent.context.ContextStateEnvelopeTest
VERSION_ADAPTER_CLASS=cn.studykid.multiagent.bootstrap.config.ContextPolicyVersionAdapterTest
CONTROLLER_CLASS=cn.studykid.multiagent.api.chat.AgentChatControllerTest
M3_GUARD_CLASS=cn.studykid.multiagent.bootstrap.config.ContextCompressionM3GuardTest
M8A_GUARD_CLASS=cn.studykid.multiagent.bootstrap.config.ContextCompressionM8aGuardTest
BAK=/tmp/m8a_context_state_counterproof

OLD_ACCEPT='            if (result instanceof Accepted accepted) {
                return new ContextStateResolution(
                        withContextState(request, accepted.stateJson()),
                        accepted.stateJson(), accepted.digest(), accepted.policyVersion(),
                        accepted.upToTurn(), null);
            }'
NEW_ACCEPT='            if (result instanceof Accepted accepted) {
                return new ContextStateResolution(
                        withContextState(request, null),
                        null, null, null, null, null);
            }'

OLD_DISCARD='            return new ContextStateResolution(
                    withContextState(request, null), null, null, null, null, reason);'
NEW_DISCARD='            return new ContextStateResolution(
                    withContextState(request, request.contextState()),
                    null, null, null, null, reason);'

OLD_EMPTY_KEY='        if (hmacKey.length == 0) {
            return new Discarded(DropReason.KEY_UNAVAILABLE);
        }'
NEW_EMPTY_KEY='        if (hmacKey.length == 0) {
            return new Absent();
        }'

OLD_EXPIRY='            if (nowEpochSeconds >= expiresAt) {
                return discard(ContextStateVerifier.DropReason.EXPIRED);
            }'
NEW_EXPIRY='            if (false && nowEpochSeconds >= expiresAt) {
                return discard(ContextStateVerifier.DropReason.EXPIRED);
            }'

OLD_DISABLED='        if (!global.enabled()) {
            return null;
        }'
NEW_DISABLED='        if (false && !global.enabled()) {
            return null;
        }'

OLD_STATE_PASSTHROUGH='                safe.contextState());'
NEW_STATE_PASSTHROUGH='                null);'

OLD_POLICY_READ='        ContextPolicy agentPolicy =
                ContextPolicyReader.read(definition.get().generationParams());'
NEW_POLICY_READ='        ContextPolicy agentPolicy =
                ContextPolicyReader.read("{\"context\":{}}"); // RV-M8A-07'

OLD_USAGE_FRAME='            sink.accept(new AgentChatEvent.Usage(state.inputTokens(), state.outputTokens()));'
NEW_USAGE_FRAME='            sink.accept(new AgentChatEvent.Usage(state.inputTokens(), state.outputTokens()));
            sink.accept(new AgentChatEvent.ContextCheckpoint("t", 1, "cp_x", 2, false, "sha256:d")); // RV-M8A-08'

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

# ⚠️ 唯一锚点替换：锚点必须在目标文件里逐字出现且只出现一次；
#    多行锚点用 Python str.count 精确计数，不能用 grep -cF（会按行误计）。
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
    agent-context) echo "${ROOT}/platform/agent-context/target/surefire-reports" ;;
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
  grep -hE 'RV-M8A-0[1-8]' "${PATCHED_FILES[@]}" 2>/dev/null | wc -l | tr -d ' '
}

echo "════════ S3-M8-a contextState 静默丢弃 · 八点反证 ════════"

echo ""
echo "▶ RV-M8A-01：accepted 分支不下传已验 state"
restore
if inject "${ORCHESTRATOR}" "${OLD_ACCEPT}" "${NEW_ACCEPT}"; then
  run_unit agent-worker "AgentChatOrchestratorTest#acceptedContextStateIsSilentlyNormalized" \
    "${ORCHESTRATOR_CLASS}" "${BAK}/rv01.log"
  expect_red "RV-M8A-01 / 已验状态下传必须真红" \
    "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/rv01.log"
else
  echo "  ❌ RV-M8A-01：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8A-02：discarded 分支保留原始 state（全量历史降级失效）"
if inject "${ORCHESTRATOR}" "${OLD_DISCARD}" "${NEW_DISCARD}"; then
  run_unit agent-worker "AgentChatOrchestratorTest#discardedContextStateFallsBackToFullHistorySilently" \
    "${ORCHESTRATOR_CLASS}" "${BAK}/rv02.log"
  expect_red "RV-M8A-02 / 静默丢弃必须真红" \
    "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/rv02.log"
else
  echo "  ❌ RV-M8A-02：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8A-03：空 HMAC 密钥从 fail-closed 改成 Absent"
if inject "${VERIFIER_ADAPTER}" "${OLD_EMPTY_KEY}" "${NEW_EMPTY_KEY}"; then
  run_unit platform-bootstrap "ContextStateVerifierWiringTest#blankKeyOrUnresolvedPolicyIsFailClosed" \
    "${VERIFIER_WIRING_CLASS}" "${BAK}/rv03.log"
  expect_red "RV-M8A-03 / 空密钥 fail-closed 必须真红" \
    "$(report_dir platform-bootstrap)/${VERIFIER_WIRING_CLASS}.txt" "${BAK}/rv03.log"
else
  echo "  ❌ RV-M8A-03：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8A-04：exp 校验被短路（过期 state 可续用）"
if inject "${ENVELOPE}" "${OLD_EXPIRY}" "${NEW_EXPIRY}"; then
  run_unit agent-context "ContextStateEnvelopeTest#expiredPolicyTenantAndTurnCursorAreDroppedSilently" \
    "${ENVELOPE_CLASS}" "${BAK}/rv04.log"
  expect_red "RV-M8A-04 / TTL 丢弃必须真红" \
    "$(report_dir agent-context)/${ENVELOPE_CLASS}.txt" "${BAK}/rv04.log"
else
  echo "  ❌ RV-M8A-04：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8A-05：全局关闭态不再零读取短路"
if inject "${VERSION_ADAPTER}" "${OLD_DISABLED}" "${NEW_DISABLED}"; then
  run_unit platform-bootstrap "ContextPolicyVersionAdapterTest#disabledShortCircuitsWithoutAnyRead" \
    "${VERSION_ADAPTER_CLASS}" "${BAK}/rv05.log"
  expect_red "RV-M8A-05 / 关闭态零读取必须真红" \
    "$(report_dir platform-bootstrap)/${VERSION_ADAPTER_CLASS}.txt" "${BAK}/rv05.log"
else
  echo "  ❌ RV-M8A-05：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8A-06：Controller 不再逐字透传原始 state"
if inject "${CONTROLLER}" "${OLD_STATE_PASSTHROUGH}" "${NEW_STATE_PASSTHROUGH}"; then
  run_unit agent-api "AgentChatControllerTest#requestBodyFieldsPassedThrough" \
    "${CONTROLLER_CLASS}" "${BAK}/rv06.log"
  expect_red "RV-M8A-06 / 原始 state 逐字透传必须真红" \
    "$(report_dir agent-api)/${CONTROLLER_CLASS}.txt" "${BAK}/rv06.log"
else
  echo "  ❌ RV-M8A-06：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8A-07：运行期策略读点改读常量 JSON（第二策略真相）"
if inject "${SUPPORT}" "${OLD_POLICY_READ}" "${NEW_POLICY_READ}"; then
  run_unit platform-bootstrap "ContextCompressionM3GuardTest#m7RuntimeStillReadsGenerationParamsOnly" \
    "${M3_GUARD_CLASS}" "${BAK}/rv07.log"
  expect_red "RV-M8A-07 / M3+M8-a 同源裁决守卫必须真红" \
    "$(report_dir platform-bootstrap)/${M3_GUARD_CLASS}.txt" "${BAK}/rv07.log"
else
  echo "  ❌ RV-M8A-07：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8A-08：编排器提前发射 context.checkpoint"
if inject "${ORCHESTRATOR}" "${OLD_USAGE_FRAME}" "${NEW_USAGE_FRAME}"; then
  run_unit platform-bootstrap "ContextCompressionM8aGuardTest#orchestratorNeverEmitsCheckpointOrLeaksState" \
    "${M8A_GUARD_CLASS}" "${BAK}/rv08.log"
  expect_red "RV-M8A-08 / 不发射 checkpoint 守卫必须真红" \
    "$(report_dir platform-bootstrap)/${M8A_GUARD_CLASS}.txt" "${BAK}/rv08.log"
else
  echo "  ❌ RV-M8A-08：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ 收尾：恢复后八个目标用例都必须全绿"
run_unit agent-worker "AgentChatOrchestratorTest#acceptedContextStateIsSilentlyNormalized" \
  "${ORCHESTRATOR_CLASS}" "${BAK}/base01.log"
expect_green "基线 / accepted 下传" \
  "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/base01.log"
run_unit agent-worker "AgentChatOrchestratorTest#discardedContextStateFallsBackToFullHistorySilently" \
  "${ORCHESTRATOR_CLASS}" "${BAK}/base02.log"
expect_green "基线 / discarded 降级" \
  "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/base02.log"
run_unit platform-bootstrap "ContextStateVerifierWiringTest#blankKeyOrUnresolvedPolicyIsFailClosed" \
  "${VERIFIER_WIRING_CLASS}" "${BAK}/base03.log"
expect_green "基线 / 空密钥 fail-closed" \
  "$(report_dir platform-bootstrap)/${VERIFIER_WIRING_CLASS}.txt" "${BAK}/base03.log"
run_unit agent-context "ContextStateEnvelopeTest#expiredPolicyTenantAndTurnCursorAreDroppedSilently" \
  "${ENVELOPE_CLASS}" "${BAK}/base04.log"
expect_green "基线 / TTL 丢弃" \
  "$(report_dir agent-context)/${ENVELOPE_CLASS}.txt" "${BAK}/base04.log"
run_unit platform-bootstrap "ContextPolicyVersionAdapterTest#disabledShortCircuitsWithoutAnyRead" \
  "${VERSION_ADAPTER_CLASS}" "${BAK}/base05.log"
expect_green "基线 / 关闭态零读取" \
  "$(report_dir platform-bootstrap)/${VERSION_ADAPTER_CLASS}.txt" "${BAK}/base05.log"
run_unit agent-api "AgentChatControllerTest#requestBodyFieldsPassedThrough" \
  "${CONTROLLER_CLASS}" "${BAK}/base06.log"
expect_green "基线 / Controller 逐字透传" \
  "$(report_dir agent-api)/${CONTROLLER_CLASS}.txt" "${BAK}/base06.log"
run_unit platform-bootstrap "ContextCompressionM3GuardTest#m7RuntimeStillReadsGenerationParamsOnly" \
  "${M3_GUARD_CLASS}" "${BAK}/base07.log"
expect_green "基线 / M3 共享裁决守卫" \
  "$(report_dir platform-bootstrap)/${M3_GUARD_CLASS}.txt" "${BAK}/base07.log"
run_unit platform-bootstrap "ContextCompressionM8aGuardTest#orchestratorNeverEmitsCheckpointOrLeaksState" \
  "${M8A_GUARD_CLASS}" "${BAK}/base08.log"
expect_green "基线 / M8-a 不发射守卫" \
  "$(report_dir platform-bootstrap)/${M8A_GUARD_CLASS}.txt" "${BAK}/base08.log"

echo ""
echo "▶ 收尾三件套：old 锚点全量计数 + 残留标记 + md5 复原 + git diff --check"
c1=$(anchor_count "${ORCHESTRATOR}" "${OLD_ACCEPT}")
c2=$(anchor_count "${ORCHESTRATOR}" "${OLD_DISCARD}")
c3=$(anchor_count "${VERIFIER_ADAPTER}" "${OLD_EMPTY_KEY}")
c4=$(anchor_count "${ENVELOPE}" "${OLD_EXPIRY}")
c5=$(anchor_count "${VERSION_ADAPTER}" "${OLD_DISABLED}")
c6=$(anchor_count "${CONTROLLER}" "${OLD_STATE_PASSTHROUGH}")
c7=$(anchor_count "${SUPPORT}" "${OLD_POLICY_READ}")
c8=$(anchor_count "${ORCHESTRATOR}" "${OLD_USAGE_FRAME}")
markers=$(marker_count)
md5_now=$(md5_all)
if [ "${c1}" -eq 1 ] && [ "${c2}" -eq 1 ] && [ "${c3}" -eq 1 ] && [ "${c4}" -eq 1 ] \
   && [ "${c5}" -eq 1 ] && [ "${c6}" -eq 1 ] && [ "${c7}" -eq 1 ] && [ "${c8}" -eq 1 ] \
   && [ "${markers}" -eq 0 ] && [ "${md5_now}" = "${MD5_BEFORE}" ]; then
  echo "  ✅ 锚点 OLD_ACCEPT=${c1} OLD_DISCARD=${c2} OLD_EMPTY_KEY=${c3} OLD_EXPIRY=${c4}"
  echo "     OLD_DISABLED=${c5} OLD_STATE_PASSTHROUGH=${c6} OLD_POLICY_READ=${c7} OLD_USAGE_FRAME=${c8}"
  echo "     残留 RV 标记=${markers}；md5 复原一致"
else
  echo "  ❌ 复原校验失败： anchors=${c1}/${c2}/${c3}/${c4}/${c5}/${c6}/${c7}/${c8} 残留=${markers}"
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
  echo "✅ S3-M8-a 八点反证全部成立，撤点已复原"
  exit 0
else
  echo "❌ 存在无效反证或未复原 —— 见上"
  exit 1
fi
