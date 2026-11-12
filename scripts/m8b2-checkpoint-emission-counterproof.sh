#!/usr/bin/env bash
# S3-M8-b-2（#73）反证脚本：钉住「L2 轮末发射 context.checkpoint + Done 版本 + carry-over 围栏」
# 不是空转、不是假绿、守卫升级不是删守卫了事。
#
# ⚠️ 要证伪的是六个独立形态（全部要求「编译通过 + 目标用例真红 + Skipped: 0」）：
#
#   · RV-M8B2-01：撤掉轮末 emitCheckpoint 调用（永远不发射第 14 事件）——
#               AgentChatOrchestratorTest#assembledStateEmitsCheckpointBeforeDoneWithVersion 必须红。
#   · RV-M8B2-02：Done 版本永远为 null（checkpoint 与 done 不绑定）——
#               同上用例的版本断言必须红。
#   · RV-M8B2-03：封包异常不再 fail-soft（抛出中断主流程）——
#               AgentChatOrchestratorTest#emitterFailureIsFailSoftAndNeverBreaksMainFlow 必须红。
#   · RV-M8B2-04：carry-over 围栏裸拼（绕过 PromptGuard.wrapCompactedContext）——
#               AgentChatOrchestratorTest#carryOverIsInjectedAsUntrustedUserFence 必须红。
#   · RV-M8B2-05：M6 守卫的「白名单内正面发射」升级被撤（加回旧负面「禁止发射」）——
#               ContextCompressionM6GuardTest#contextCheckpointIsEmittedInAllowListWithFrozenWireOrder 必须红。
#   · RV-M8B2-06：组合根 emitter adapter 空转（密钥被吞、永远返回 null）——
#               ContextCheckpointEmitterWiringTest#emittedStateIsVerifiedBySameRootVerifier 必须红。
#
# ⚠️ 判定：结论不变（仍全绿）或编译失败（无报告）均判**无效反证**。
# ⚠️ 中断安全：SIGTERM/SIGINT 时先复原当前撤点文件再退出，
#    避免残留撤点源码造成后续静默失真（M10 曾吃过这个亏）。
set -u

ROOT=/Users/zhouwei/code-workspace/ai-tech/java-multi-agent

ORCHESTRATOR_REL=platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/agentchat/AgentChatOrchestrator.java
M6_GUARD_REL=platform/platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/config/ContextCompressionM6GuardTest.java
ADAPTER_REL=platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextCheckpointEmitterAdapter.java

ORCHESTRATOR="${ROOT}/${ORCHESTRATOR_REL}"
M6_GUARD="${ROOT}/${M6_GUARD_REL}"
ADAPTER="${ROOT}/${ADAPTER_REL}"
PATCHED_FILES=("${ORCHESTRATOR}" "${M6_GUARD}" "${ADAPTER}")

ORCHESTRATOR_CLASS=cn.studykid.multiagent.worker.agentchat.AgentChatOrchestratorTest
M6_GUARD_CLASS=cn.studykid.multiagent.bootstrap.config.ContextCompressionM6GuardTest
WIRING_CLASS=cn.studykid.multiagent.bootstrap.config.ContextCheckpointEmitterWiringTest
BAK=/tmp/m8b2_checkpoint_emission_counterproof

# RV-M8B2-01：轮末永不发射（emitCheckpoint 调用改成 null）
OLD_EMIT='            CheckpointEmission checkpoint = emitCheckpoint(context, state, effective);'
NEW_EMIT='            CheckpointEmission checkpoint = null; // RV-M8B2-01'

# RV-M8B2-02：Done 版本永远为 null（checkpoint 与 done 不绑定）
OLD_VERSION='                    false, checkpoint == null ? null : checkpoint.version()));'
NEW_VERSION='                    false, null)); // RV-M8B2-02'

# RV-M8B2-03：封包异常不再 fail-soft（把静默 return null 改成抛出）
OLD_FAILSOFT='''        } catch (RuntimeException e) {
            // 可选增强失败静默降级；⛔ 不打印任何 state 正文 / 密钥，只记异常类型供排障。
            log.debug("L2 checkpoint 发射跳过（可选增强 fail-soft）: domain={}, kind={}",
                    context.domain(), e.getClass().getSimpleName());
            return null;
        }'''
NEW_FAILSOFT='''        } catch (RuntimeException e) {
            // 可选增强失败静默降级；⛔ 不打印任何 state 正文 / 密钥，只记异常类型供排障。
            log.debug("L2 checkpoint 发射跳过（可选增强 fail-soft）: domain={}, kind={}",
                    context.domain(), e.getClass().getSimpleName());
            throw e; // RV-M8B2-03
        }'''

# RV-M8B2-04：carry-over 围栏裸拼（绕过 PromptGuard.wrapCompactedContext）
OLD_FENCE='            messages.add(ModelMessage.user(RAG_GUARD.wrapCompactedContext(carryOverStateJson)));'
NEW_FENCE='            messages.add(ModelMessage.user(carryOverStateJson)); // RV-M8B2-04'

# RV-M8B2-05：M6 守卫升级被撤——在正面线序守卫里加回旧负面「禁止发射」，自相矛盾必红
OLD_GUARD='        int answer = orchestrator.indexOf("new AgentChatEvent.Answer(");'
NEW_GUARD='''        // RV-M8B2-05：模拟 G1 A.5 守卫升级被撤——加回旧负面「禁止发射」
        assertThat(orchestrator).doesNotContain("new AgentChatEvent.ContextCheckpoint(");
        int answer = orchestrator.indexOf("new AgentChatEvent.Answer(");'''

# RV-M8B2-06：组合根 emitter adapter 空转——密钥被吞，永远返回 null（配了等于没配的静空转）
# ⚠️ 必须连同链式 .getBytes(...) 一起替换，否则下一行悬空表达式会编译失败（编译失败 ≠ 有效反证）。
OLD_KEY='''        this.hmacKey = (stateHmacKey == null ? "" : stateHmacKey)
                .getBytes(StandardCharsets.UTF_8);'''
NEW_KEY='''        this.hmacKey = "".getBytes(StandardCharsets.UTF_8); // RV-M8B2-06 模拟 adapter 空转：吞掉配置的独立密钥'''

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
  # ⚠️ 只匹配「注释形态」的撤点标记（注入的 // RV-M8B2-0X），
  #    不匹配守卫纪律断言里的 .contains("RV-M8B2-0X") 字面量——那是合法常驻代码，不是残留。
  grep -hE '// RV-M8B2-0[1-6]' "${PATCHED_FILES[@]}" 2>/dev/null | wc -l | tr -d ' '
}

echo "════════ S3-M8-b-2 轮末 checkpoint 发射 · 六点反证 ════════"

echo ""
echo "▶ RV-M8B2-01：轮末永不发射（emitCheckpoint 调用改成 null）"
restore
if inject "${ORCHESTRATOR}" "${OLD_EMIT}" "${NEW_EMIT}"; then
  run_unit agent-worker "AgentChatOrchestratorTest#assembledStateEmitsCheckpointBeforeDoneWithVersion" \
    "${ORCHESTRATOR_CLASS}" "${BAK}/rv01.log"
  expect_red "RV-M8B2-01 / 第 14 事件发射必须真红" \
    "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/rv01.log"
else
  echo "  ❌ RV-M8B2-01：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8B2-02：Done 版本永远 null（checkpoint 与 done 不绑定）"
if inject "${ORCHESTRATOR}" "${OLD_VERSION}" "${NEW_VERSION}"; then
  run_unit agent-worker "AgentChatOrchestratorTest#assembledStateEmitsCheckpointBeforeDoneWithVersion" \
    "${ORCHESTRATOR_CLASS}" "${BAK}/rv02.log"
  expect_red "RV-M8B2-02 / Done 版本同源必须真红" \
    "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/rv02.log"
else
  echo "  ❌ RV-M8B2-02：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8B2-03：封包异常不再 fail-soft（抛出中断主流程）"
if inject "${ORCHESTRATOR}" "${OLD_FAILSOFT}" "${NEW_FAILSOFT}"; then
  run_unit agent-worker "AgentChatOrchestratorTest#emitterFailureIsFailSoftAndNeverBreaksMainFlow" \
    "${ORCHESTRATOR_CLASS}" "${BAK}/rv03.log"
  expect_red "RV-M8B2-03 / fail-soft 必须真红" \
    "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/rv03.log"
else
  echo "  ❌ RV-M8B2-03：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8B2-04：carry-over 围栏裸拼（绕过 PromptGuard）"
if inject "${ORCHESTRATOR}" "${OLD_FENCE}" "${NEW_FENCE}"; then
  run_unit agent-worker "AgentChatOrchestratorTest#carryOverIsInjectedAsUntrustedUserFence" \
    "${ORCHESTRATOR_CLASS}" "${BAK}/rv04.log"
  expect_red "RV-M8B2-04 / 不可信围栏注入必须真红" \
    "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/rv04.log"
else
  echo "  ❌ RV-M8B2-04：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8B2-05：M6 守卫升级被撤（加回旧负面「禁止发射」）"
if inject "${M6_GUARD}" "${OLD_GUARD}" "${NEW_GUARD}"; then
  run_unit platform-bootstrap "ContextCompressionM6GuardTest#contextCheckpointIsEmittedInAllowListWithFrozenWireOrder" \
    "${M6_GUARD_CLASS}" "${BAK}/rv05.log"
  expect_red "RV-M8B2-05 / M6 A.5 守卫升级必须真红" \
    "$(report_dir platform-bootstrap)/${M6_GUARD_CLASS}.txt" "${BAK}/rv05.log"
else
  echo "  ❌ RV-M8B2-05：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M8B2-06：组合根 emitter adapter 空转（吞掉独立密钥）"
if inject "${ADAPTER}" "${OLD_KEY}" "${NEW_KEY}"; then
  run_unit platform-bootstrap "ContextCheckpointEmitterWiringTest#emittedStateIsVerifiedBySameRootVerifier" \
    "${WIRING_CLASS}" "${BAK}/rv06.log"
  expect_red "RV-M8B2-06 / 组合根非空转必须真红" \
    "$(report_dir platform-bootstrap)/${WIRING_CLASS}.txt" "${BAK}/rv06.log"
else
  echo "  ❌ RV-M8B2-06：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ 收尾：恢复后四个基线用例都必须全绿"
run_unit agent-worker "AgentChatOrchestratorTest#assembledStateEmitsCheckpointBeforeDoneWithVersion" \
  "${ORCHESTRATOR_CLASS}" "${BAK}/base01.log"
expect_green "基线 / 第 14 事件线序 + Done 版本" \
  "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/base01.log"
run_unit agent-worker "AgentChatOrchestratorTest#emitterFailureIsFailSoftAndNeverBreaksMainFlow" \
  "${ORCHESTRATOR_CLASS}" "${BAK}/base03.log"
expect_green "基线 / 封包异常 fail-soft" \
  "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/base03.log"
run_unit agent-worker "AgentChatOrchestratorTest#carryOverIsInjectedAsUntrustedUserFence" \
  "${ORCHESTRATOR_CLASS}" "${BAK}/base04.log"
expect_green "基线 / 不可信围栏注入" \
  "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/base04.log"
run_unit platform-bootstrap "ContextCompressionM6GuardTest#contextCheckpointIsEmittedInAllowListWithFrozenWireOrder" \
  "${M6_GUARD_CLASS}" "${BAK}/base05.log"
expect_green "基线 / M6 A.5 守卫升级" \
  "$(report_dir platform-bootstrap)/${M6_GUARD_CLASS}.txt" "${BAK}/base05.log"
run_unit platform-bootstrap "ContextCheckpointEmitterWiringTest#emittedStateIsVerifiedBySameRootVerifier" \
  "${WIRING_CLASS}" "${BAK}/base06.log"
expect_green "基线 / 组合根 emit→verify 闭环" \
  "$(report_dir platform-bootstrap)/${WIRING_CLASS}.txt" "${BAK}/base06.log"

echo ""
echo "▶ 收尾三件套：old 锚点全量计数 + 残留标记 + md5 复原 + git diff --check"
c1=$(anchor_count "${ORCHESTRATOR}" "${OLD_EMIT}")
c2=$(anchor_count "${ORCHESTRATOR}" "${OLD_VERSION}")
c3=$(anchor_count "${ORCHESTRATOR}" "${OLD_FAILSOFT}")
c4=$(anchor_count "${ORCHESTRATOR}" "${OLD_FENCE}")
c5=$(anchor_count "${M6_GUARD}" "${OLD_GUARD}")
c6=$(anchor_count "${ADAPTER}" "${OLD_KEY}")
markers=$(marker_count)
md5_now=$(md5_all)
if [ "${c1}" -eq 1 ] && [ "${c2}" -eq 1 ] && [ "${c3}" -eq 1 ] && [ "${c4}" -eq 1 ] \
   && [ "${c5}" -eq 1 ] && [ "${c6}" -eq 1 ] \
   && [ "${markers}" -eq 0 ] && [ "${md5_now}" = "${MD5_BEFORE}" ]; then
  echo "  ✅ 锚点 OLD_EMIT=${c1} OLD_VERSION=${c2} OLD_FAILSOFT=${c3} OLD_FENCE=${c4} OLD_GUARD=${c5} OLD_KEY=${c6}"
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
  echo "✅ S3-M8-b-2 六点反证全部成立，撤点已复原"
  exit 0
else
  echo "❌ 存在无效反证或未复原 —— 见上"
  exit 1
fi
