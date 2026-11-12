#!/usr/bin/env bash
# S1-M3 反证脚本：钉住 execution_snapshot.context_policy 双写入口不是假绿。
#
# ⚠️ 要证伪的是六个独立的假绿形态（全部要求「编译通过 + 目标用例真红」）：
#
#   · RV-M3-01（旧直发强制写 {}）：把 JdbcAgentManageRepository 的派生值硬编码成
#     "{}"，AgentRepositoryIT#publishVersionSnapshotsContextPolicy 必须红。
#     若仍绿，说明旧直发入口其实没有写规范快照。
#   · RV-M3-02（CAP 派生强制写 {}）：把 AgentReleaseArchive.deriveContextPolicy
#     改成返回 "{}"，ConfigPublishManagementIT#publishedAgentSnapshotsContextPolicy 必须红。
#     若仍绿，说明 CAP 入口没有走真源 generationParams。
#   · RV-M3-03（CAP 仓储不绑定参数）：把 JdbcAgentReleaseArchiveRepository 的
#     ps.setString(5, contextPolicy) 改成 "{}"，同一 CAP IT 必须红。
#     若仍绿，说明 SQL 第 5 个参数没有真正写列。
#   · RV-M3-04（撤掉 fail-closed 校验）：删掉 ContextPolicyReader.snapshotJson 中
#     ContextPolicyParser.parse(plain)，ContextPolicyReaderTest#snapshotDerivationIsFailClosed
#     必须红。若仍绿，说明非法策略会被静默写成 {}。
#   · RV-M3-05（07 升级脚本漂移）：把 07 里 ADD COLUMN 的
#     NOT NULL DEFAULT '{}'::jsonb 去掉，ContextCompressionM3GuardTest 的
#     ddlAndScriptSyncKeepContextPolicyColumn 必须红。若仍绿，说明 DDL/07 一致性守卫空转。
#   · RV-M3-06（M7 运行时被快照耦合）：把 ContextRequestAssemblyAdapter 里的
#     ContextPolicyReader.read(definition.get().generationParams()) 换成常量 JSON，
#     ContextCompressionM3GuardTest 的 m7RuntimeStillReadsGenerationParamsOnly 必须红。
#     若仍绿，说明「M7 不读快照」的边界守卫空转。
#
# ⚠️ 判定：结论不变（仍全绿）或编译失败均判**无效反证**。
# ⚠️ 中断安全：SIGTERM/SIGINT 时先复原当前撤点文件再退出，
#    避免残留撤点源码造成后续静默失真。
set -u

ROOT=/Users/zhouwei/code-workspace/ai-tech/java-multi-agent

MANAGE_REL=platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcAgentManageRepository.java
ARCHIVE_REL=platform/agent-application/src/main/java/cn/studykid/multiagent/application/cap/AgentReleaseArchive.java
REPO_REL=platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcAgentReleaseArchiveRepository.java
READER_REL=platform/agent-application/src/main/java/cn/studykid/multiagent/application/cap/ContextPolicyReader.java
SQL_REL=deploy/sql/platform/07_execution_snapshot_context_policy.sql
ADAPTER_REL=platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextRequestAssemblyAdapter.java

MANAGE="${ROOT}/${MANAGE_REL}"
ARCHIVE="${ROOT}/${ARCHIVE_REL}"
REPO="${ROOT}/${REPO_REL}"
READER="${ROOT}/${READER_REL}"
SQL="${ROOT}/${SQL_REL}"
ADAPTER="${ROOT}/${ADAPTER_REL}"
PATCHED_FILES=("${MANAGE}" "${ARCHIVE}" "${REPO}" "${READER}" "${SQL}" "${ADAPTER}")

IT_DIR="${ROOT}/platform/agent-persistence/target/failsafe-reports"
APP_DIR="${ROOT}/platform/agent-application/target/surefire-reports"
BOOT_DIR="${ROOT}/platform/platform-bootstrap/target/surefire-reports"
AGENT_REPO_CLASS=cn.studykid.multiagent.infrastructure.persistence.AgentRepositoryIT
CAP_CLASS=cn.studykid.multiagent.infrastructure.persistence.cap.ConfigPublishManagementIT
READER_CLASS=cn.studykid.multiagent.application.cap.ContextPolicyReaderTest
GUARD_CLASS=cn.studykid.multiagent.bootstrap.config.ContextCompressionM3GuardTest
BAK=/tmp/m3_context_policy_counterproof

OLD_MANAGE='        String contextPolicy = ContextPolicyReader.snapshotFromAgentConfig(definition);'
NEW_MANAGE='        String contextPolicy = "{}"; // RV-M3-01'

OLD_ARCHIVE='        return ContextPolicyReader.snapshotJson(agent.generationParams());'
NEW_ARCHIVE='        return "{}"; // RV-M3-02'

OLD_REPO_BIND='            ps.setString(5, contextPolicy);'
NEW_REPO_BIND='            ps.setString(5, "{}"); // RV-M3-03'

OLD_READER='        ContextPolicyParser.parse(plain);'
NEW_READER='        // RV-M3-04：撤掉与 read() 共用的 fail-closed 校验'

OLD_SQL='ALTER TABLE public.execution_snapshot
    ADD COLUMN IF NOT EXISTS context_policy jsonb NOT NULL DEFAULT '"'"'{}'"'"'::jsonb;'
NEW_SQL='ALTER TABLE public.execution_snapshot
    ADD COLUMN IF NOT EXISTS context_policy jsonb; -- RV-M3-05'

OLD_ADAPTER='                    ContextPolicyReader.read(definition.get().generationParams());'
NEW_ADAPTER='                    ContextPolicyReader.read("{\"context\":{}}"); // RV-M3-06'

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

clear_report() {
  local dir="$1" cls="$2"
  # ⚠️ 必须删旧报告：否则编译失败时会读到上一次的报告，制造假红/假绿。
  rm -f "${dir}/${cls}.txt" "${dir}/TEST-${cls}.xml"
}

run_mvn() {
  local log="$1"
  shift
  (
    cd "${ROOT}" || exit 1
    IT_HOST=127.0.0.1 IT_PORT=5433 IT_ADMIN_USER=agent source scripts/it-env.sh >/dev/null &&
      JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -o -f platform/pom.xml "$@"
  ) > "${log}" 2>&1
  # ⚠️ 故意不 return mvn 的码：红/绿只以**报告**判定，编译失败由「无报告」拦截。
  return 0
}

run_it() {
  local test="$1" cls="$2" log="$3"
  clear_report "${IT_DIR}" "${cls}"
  run_mvn "${log}" -pl agent-persistence -am clean verify \
    -Dtest=__none__ -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test="${test}"
}

run_app_unit() {
  local test="$1" cls="$2" log="$3"
  clear_report "${APP_DIR}" "${cls}"
  run_mvn "${log}" -pl agent-application -am clean test \
    -Dtest="${test}" -Dsurefire.failIfNoSpecifiedTests=false
}

run_boot_unit() {
  local test="$1" cls="$2" log="$3"
  clear_report "${BOOT_DIR}" "${cls}"
  run_mvn "${log}" -pl platform-bootstrap -am clean test \
    -Dtest="${test}" -Dsurefire.failIfNoSpecifiedTests=false
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
  grep -hE 'RV-M3-0[1-6]' "${PATCHED_FILES[@]}" 2>/dev/null | wc -l | tr -d ' '
}

echo "════════ S1-M3 context_policy 双写入口 · 六点反证 ════════"

echo ""
echo "▶ RV-M3-01：旧直发派生值硬编码 {}"
restore
if inject "${MANAGE}" "${OLD_MANAGE}" "${NEW_MANAGE}"; then
  run_it "AgentRepositoryIT#publishVersionSnapshotsContextPolicy" \
    "${AGENT_REPO_CLASS}" "${BAK}/rv01.log"
  expect_red "RV-M3-01 / 旧直发入口必须真红" \
    "${IT_DIR}/${AGENT_REPO_CLASS}.txt" "${BAK}/rv01.log"
else
  echo "  ❌ RV-M3-01：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M3-02：CAP 派生值硬编码 {}"
if inject "${ARCHIVE}" "${OLD_ARCHIVE}" "${NEW_ARCHIVE}"; then
  run_it "ConfigPublishManagementIT#publishedAgentSnapshotsContextPolicy" \
    "${CAP_CLASS}" "${BAK}/rv02.log"
  expect_red "RV-M3-02 / CAP 真源派生必须真红" \
    "${IT_DIR}/${CAP_CLASS}.txt" "${BAK}/rv02.log"
else
  echo "  ❌ RV-M3-02：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M3-03：CAP 仓储不绑定第 5 参数"
if inject "${REPO}" "${OLD_REPO_BIND}" "${NEW_REPO_BIND}"; then
  run_it "ConfigPublishManagementIT#publishedAgentSnapshotsContextPolicy" \
    "${CAP_CLASS}" "${BAK}/rv03.log"
  expect_red "RV-M3-03 / SQL 参数绑定必须真红" \
    "${IT_DIR}/${CAP_CLASS}.txt" "${BAK}/rv03.log"
else
  echo "  ❌ RV-M3-03：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M3-04：撤掉快照派生的 fail-closed 校验"
if inject "${READER}" "${OLD_READER}" "${NEW_READER}"; then
  run_app_unit "ContextPolicyReaderTest#snapshotDerivationIsFailClosed" \
    "${READER_CLASS}" "${BAK}/rv04.log"
  expect_red "RV-M3-04 / 非法策略不拒必须真红" \
    "${APP_DIR}/${READER_CLASS}.txt" "${BAK}/rv04.log"
else
  echo "  ❌ RV-M3-04：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M3-05：07 升级脚本丢掉 NOT NULL DEFAULT"
if inject "${SQL}" "${OLD_SQL}" "${NEW_SQL}"; then
  run_boot_unit "ContextCompressionM3GuardTest#ddlAndScriptSyncKeepContextPolicyColumn" \
    "${GUARD_CLASS}" "${BAK}/rv05.log"
  expect_red "RV-M3-05 / DDL 双脚本守卫必须真红" \
    "${BOOT_DIR}/${GUARD_CLASS}.txt" "${BAK}/rv05.log"
else
  echo "  ❌ RV-M3-05：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M3-06：M7 运行时读源被快照耦合"
if inject "${ADAPTER}" "${OLD_ADAPTER}" "${NEW_ADAPTER}"; then
  run_boot_unit "ContextCompressionM3GuardTest#m7RuntimeStillReadsGenerationParamsOnly" \
    "${GUARD_CLASS}" "${BAK}/rv06.log"
  expect_red "RV-M3-06 / M7 读源边界守卫必须真红" \
    "${BOOT_DIR}/${GUARD_CLASS}.txt" "${BAK}/rv06.log"
else
  echo "  ❌ RV-M3-06：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ 收尾：恢复后五个目标用例都必须全绿"
run_it "AgentRepositoryIT#publishVersionSnapshotsContextPolicy" \
  "${AGENT_REPO_CLASS}" "${BAK}/base01.log"
expect_green "基线 / 旧直发入口" "${IT_DIR}/${AGENT_REPO_CLASS}.txt" "${BAK}/base01.log"
run_it "ConfigPublishManagementIT#publishedAgentSnapshotsContextPolicy" \
  "${CAP_CLASS}" "${BAK}/base02.log"
expect_green "基线 / CAP 入口" "${IT_DIR}/${CAP_CLASS}.txt" "${BAK}/base02.log"
run_app_unit "ContextPolicyReaderTest#snapshotDerivationIsFailClosed" \
  "${READER_CLASS}" "${BAK}/base03.log"
expect_green "基线 / fail-closed" "${APP_DIR}/${READER_CLASS}.txt" "${BAK}/base03.log"
run_boot_unit "ContextCompressionM3GuardTest#ddlAndScriptSyncKeepContextPolicyColumn" \
  "${GUARD_CLASS}" "${BAK}/base04.log"
expect_green "基线 / DDL 双脚本守卫" "${BOOT_DIR}/${GUARD_CLASS}.txt" "${BAK}/base04.log"
run_boot_unit "ContextCompressionM3GuardTest#m7RuntimeStillReadsGenerationParamsOnly" \
  "${GUARD_CLASS}" "${BAK}/base05.log"
expect_green "基线 / M7 读源边界守卫" "${BOOT_DIR}/${GUARD_CLASS}.txt" "${BAK}/base05.log"

echo ""
echo "▶ 收尾三件套：old 锚点全量计数 + 残留标记 + md5 复原 + git diff --check"
c1=$(anchor_count "${MANAGE}" "${OLD_MANAGE}")
c2=$(anchor_count "${ARCHIVE}" "${OLD_ARCHIVE}")
c3=$(anchor_count "${REPO}" "${OLD_REPO_BIND}")
c4=$(anchor_count "${READER}" "${OLD_READER}")
c5=$(anchor_count "${SQL}" "${OLD_SQL}")
c6=$(anchor_count "${ADAPTER}" "${OLD_ADAPTER}")
markers=$(marker_count)
md5_now=$(md5_all)
if [ "${c1}" -eq 1 ] && [ "${c2}" -eq 1 ] && [ "${c3}" -eq 1 ] \
   && [ "${c4}" -eq 1 ] && [ "${c5}" -eq 1 ] && [ "${c6}" -eq 1 ] \
   && [ "${markers}" -eq 0 ] && [ "${md5_now}" = "${MD5_BEFORE}" ]; then
  echo "  ✅ 锚点 OLD_MANAGE=${c1} OLD_ARCHIVE=${c2} OLD_REPO_BIND=${c3}"
  echo "     OLD_READER=${c4} OLD_SQL=${c5} OLD_ADAPTER=${c6}；残留 RV 标记=${markers}；md5 复原一致"
else
  echo "  ❌ 复原校验失败： anchors=${c1}/${c2}/${c3}/${c4}/${c5}/${c6} 残留=${markers}"
  echo "     md5 now / before:"
  echo "     ${md5_now}"
  echo "     ${MD5_BEFORE}"
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
  echo "✅ S1-M3 六点反证全部成立，撤点已复原"
  exit 0
else
  echo "❌ 存在无效反证或未复原 —— 见上"
  exit 1
fi
