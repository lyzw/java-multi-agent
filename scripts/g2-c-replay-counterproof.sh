#!/usr/bin/env bash
# S2-G2-c 反证脚本：钉住「Rabbit + DB 双通道重投不重复副作用」不是假绿。
#
# ⚠️ 要证伪的是两个独立的假绿形态：
#
#   · **RV-G2-C-01（撤掉指标覆盖写）**：把 JdbcAgentMetricRepository.upsert 的
#     `ON CONFLICT (trace_id) DO UPDATE SET ... computed_at = now()`
#     改成 `ON CONFLICT (trace_id) DO NOTHING`。
#     第二次投递仍会被 ack / CLAIMED，但 `computed_at` 不再前移。
#     若 TraceMetricContentAccuracyIT / TraceCollectEndToEndIT 仍全绿，
#     说明「第二次投递真的被消费」的证据是空转，行数不增只是消息没消费。
#
#   · **RV-G2-C-02（撤掉工具明细先删后插）**：把 replaceToolCalls 的
#     `DELETE FROM platform.agent_trace_tool_call WHERE trace_id = ?`
#     改成永假条件。第二次采集会撞 (trace_id, tool_name) 主键异常 →
#     DB 通道 RETRY / 死信，Rabbit 通道 nack 重投。
#     若 TraceMetricContentAccuracyIT 仍全绿，说明工具明细幂等断言是空转。
#
# ⚠️ 判定：结论不变（仍全绿）或编译失败均判**无效反证**。
#    编译失败不算「用例红」——它证明不了任何运行时结论。
# ⚠️ 中断安全：SIGTERM/SIGINT 时先复原当前撤点文件再退出，
#    避免残留撤点源码造成后续静默失真。
set -u

ROOT=/Users/zhouwei/code-workspace/ai-tech/java-multi-agent
REPO_REL=platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcAgentMetricRepository.java
REPO="${ROOT}/${REPO_REL}"
REPORT_DIR="${ROOT}/platform/platform-bootstrap/target/failsafe-reports"
DB_CLASS=cn.studykid.multiagent.bootstrap.messaging.TraceMetricContentAccuracyIT
RABBIT_CLASS=cn.studykid.multiagent.bootstrap.messaging.TraceCollectEndToEndIT
BAK=/tmp/g2_c_replay_counterproof

OLD_UPSERT='                ON CONFLICT (trace_id) DO UPDATE SET
                    tenant_id = EXCLUDED.tenant_id,
                    run_id = EXCLUDED.run_id,
                    agent_id = EXCLUDED.agent_id,
                    agent_version = EXCLUDED.agent_version,
                    scenario = EXCLUDED.scenario,
                    model_id = EXCLUDED.model_id,
                    started_at = EXCLUDED.started_at,
                    ended_at = EXCLUDED.ended_at,
                    duration_ms = EXCLUDED.duration_ms,
                    terminal_state = EXCLUDED.terminal_state,
                    termination_reason = EXCLUDED.termination_reason,
                    input_tokens = EXCLUDED.input_tokens,
                    output_tokens = EXCLUDED.output_tokens,
                    cost_amount = EXCLUDED.cost_amount,
                    currency = EXCLUDED.currency,
                    tool_rounds = EXCLUDED.tool_rounds,
                    tool_call_total = EXCLUDED.tool_call_total,
                    tool_call_failed = EXCLUDED.tool_call_failed,
                    tool_call_invalid = EXCLUDED.tool_call_invalid,
                    tool_call_duplicate = EXCLUDED.tool_call_duplicate,
                    loop_suspected_count = EXCLUDED.loop_suspected_count,
                    max_rounds_reached = EXCLUDED.max_rounds_reached,
                    clarification_count = EXCLUDED.clarification_count,
                    think_ms_total = EXCLUDED.think_ms_total,
                    tool_ms_total = EXCLUDED.tool_ms_total,
                    computed_at = now()'
NEW_UPSERT='                ON CONFLICT (trace_id) DO NOTHING'

OLD_TOOL_DELETE='DELETE FROM platform.agent_trace_tool_call WHERE trace_id = ?'
NEW_TOOL_DELETE='DELETE FROM platform.agent_trace_tool_call WHERE trace_id = ? AND 1 = 0'

mkdir -p "${BAK}"
cp "${REPO}" "${BAK}/repo.bak"
MD5_REPO=$(md5 -q "${REPO}")
FAILED=0

restore() {
  cp "${BAK}/repo.bak" "${REPO}"
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

clear_report() {
  local cls="$1"
  local txt="${REPORT_DIR}/${cls}.txt"
  local xml="${REPORT_DIR}/TEST-${cls}.xml"
  # ⚠️ 必须删旧报告：否则编译失败时会读到上一次的报告，制造假红/假绿。
  rm -f "${txt}" "${xml}"
}

run_it() {
  local test="$1" cls="$2" log="$3"
  clear_report "${cls}"
  (
    cd "${ROOT}" || exit 1
    IT_HOST=127.0.0.1 IT_PORT=5433 IT_ADMIN_USER=agent source scripts/it-env.sh &&
      JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -o -f platform/pom.xml \
        -pl platform-bootstrap -am -Dtest=__none__ \
        -Dsurefire.failIfNoSpecifiedTests=false \
        -Dfailsafe.failIfNoSpecifiedTests=false \
        -Dit.test="${test}" verify
  ) > "${log}" 2>&1
  return 0
}

expect_red() {
  local label="$1" cls="$2" log="$3"
  local report="${REPORT_DIR}/${cls}.txt"
  if [ ! -f "${report}" ]; then
    echo "  ❌ ${label}：没有生成 IT 报告（编译失败或 Maven 未启动）——反证无效"
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
  local label="$1" cls="$2" log="$3"
  local report="${REPORT_DIR}/${cls}.txt"
  if [ ! -f "${report}" ]; then
    echo "  ❌ ${label}：没有生成 IT 报告"
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
  # ⚠️ 不能用 grep -cF：多行锚点会被拆成「任一行命中即计数」，复原校验假红。
  local file="$1" literal="$2"
  python3 - "${file}" "${literal}" <<'PY'
import sys
path, literal = sys.argv[1], sys.argv[2]
s = open(path, encoding='utf-8').read()
print(s.count(literal))
PY
}

marker_count() {
  grep -hE 'RV-G2-C-0[12]' "${REPO}" 2>/dev/null | wc -l | tr -d ' '
}

echo "════════ S2-G2-c 双通道重投 · 两点反证 ════════"

echo ""
echo "▶ RV-G2-C-01：撤掉指标覆盖写（ON CONFLICT DO NOTHING）"
restore
if inject "${REPO}" "${OLD_UPSERT}" "${NEW_UPSERT}"; then
  run_it "TraceMetricContentAccuracyIT#repeatedCollectIsIdempotent" "${DB_CLASS}" "${BAK}/rv01-db.log"
  expect_red "RV-G2-C-01 / DB（覆盖写撤掉必须真红）" "${DB_CLASS}" "${BAK}/rv01-db.log"
  run_it "TraceCollectEndToEndIT" "${RABBIT_CLASS}" "${BAK}/rv01-rabbit.log"
  expect_red "RV-G2-C-01 / Rabbit（覆盖写撤掉必须真红）" "${RABBIT_CLASS}" "${BAK}/rv01-rabbit.log"
else
  echo "  ❌ RV-G2-C-01：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-G2-C-02：撤掉工具明细先删后插（DELETE 恒假）"
if inject "${REPO}" "${OLD_TOOL_DELETE}" "${NEW_TOOL_DELETE}"; then
  run_it "TraceMetricContentAccuracyIT#repeatedCollectIsIdempotent" "${DB_CLASS}" "${BAK}/rv02-db.log"
  expect_red "RV-G2-C-02 / DB（工具明细不先删必须真红）" "${DB_CLASS}" "${BAK}/rv02-db.log"
else
  echo "  ❌ RV-G2-C-02：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ 收尾：恢复后双通道基线都必须全绿"
run_it "TraceMetricContentAccuracyIT#repeatedCollectIsIdempotent" "${DB_CLASS}" "${BAK}/baseline-db.log"
expect_green "基线 / DB（恢复后全绿）" "${DB_CLASS}" "${BAK}/baseline-db.log"
run_it "TraceCollectEndToEndIT" "${RABBIT_CLASS}" "${BAK}/baseline-rabbit.log"
expect_green "基线 / Rabbit（恢复后全绿）" "${RABBIT_CLASS}" "${BAK}/baseline-rabbit.log"

echo ""
echo "▶ 收尾三件套：old 锚点计数 + 残留标记 + md5 复原 + git diff --check"
c1=$(anchor_count "${REPO}" "${OLD_UPSERT}")
c2=$(anchor_count "${REPO}" "${OLD_TOOL_DELETE}")
markers=$(marker_count)
md5_repo_now=$(md5_of "${REPO}")
if [ "${c1}" -eq 1 ] && [ "${c2}" -eq 1 ] \
   && [ "${markers}" -eq 0 ] \
   && [ "${md5_repo_now}" = "${MD5_REPO}" ]; then
  echo "  ✅ 锚点 OLD_UPSERT=${c1} OLD_TOOL_DELETE=${c2}；残留 RV 标记=${markers}；md5 复原一致"
else
  echo "  ❌ 复原校验失败：OLD_UPSERT=${c1} OLD_TOOL_DELETE=${c2} 残留=${markers}"
  echo "     REPO md5 ${md5_repo_now} / ${MD5_REPO}"
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
  echo "✅ 双通道重投两点反证全部成立，撤点已复原"
  exit 0
else
  echo "❌ 存在无效反证或未复原 —— 见上"
  exit 1
fi
