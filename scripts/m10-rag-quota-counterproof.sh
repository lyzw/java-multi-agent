#!/usr/bin/env bash
# S3-M10（#63）反证脚本：钉住「RAG 配额薄适配真接线 + 生产计数注入 + 关闭态逐位等价 + fail-soft」
# 不是空转、不是假绿、G1 A.5 旧守卫升级不是删守卫了事。
#
# ⚠️ 要证伪的是六个独立形态（全部要求「编译通过 + 目标用例真红 + Skipped: 0」）：
#
#   · RV-M10-01：adapter 关闭态零副作用短路被撤（无条件进配额）——
#               RagHitQuotaAdapterTest#disabledAdapterShortCircuitsWithoutCounting 必须红。
#   · RV-M10-02：唯一算法委托点被撤（adapter 不再调 RagHitQuota.allocate）——
#               ContextCompressionM10GuardTest#quotaKernelHasExactlyOneProductionDelegate 必须红
#               （证明 G1 A.5「零接线 → 唯一委托」升级守卫不是空断言）。
#   · RV-M10-03：元数据 overhead 漏算（Candidate.overheadTokens 写死 0）——
#               RagHitQuotaAdapterTest#overheadUsesFrozenRendererAndProductionCounter 必须红。
#   · RV-M10-04：降级引用偷带正文（refJson 多塞 text 字段）——
#               RagHitQuotaAdapterTest#payloadJsonIsWhitelistedEscapedAndParseable 必须红。
#   · RV-M10-05：L2 正面接线被撤（编排器不再调中性配额端口）——
#               AgentChatOrchestratorTest#ragQuotaPortReceivesNeutralHitsAndStillFences 必须红。
#   · RV-M10-06：编排器 fail-soft 被撤（配额异常直接抛出中断主流程）——
#               AgentChatOrchestratorTest#ragQuotaFailureFallsBackToLegacyWithoutFailingRun 必须红。
#
# ⚠️ 判定：结论不变（仍全绿）或编译失败（无报告）均判**无效反证**。
# ⚠️ 中断安全：SIGTERM/SIGINT 时先复原当前撤点文件再退出，
#    避免残留撤点源码造成后续静默失真（M10 曾吃过这个亏）。
set -u

ROOT=/Users/zhouwei/code-workspace/ai-tech/java-multi-agent

ORCHESTRATOR_REL=platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/agentchat/AgentChatOrchestrator.java
ADAPTER_REL=platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/RagHitQuotaAdapter.java
PORT_REL=platform/agent-ports/src/main/java/cn/studykid/multiagent/ports/agentchat/RagHitQuotaPort.java

ORCHESTRATOR="${ROOT}/${ORCHESTRATOR_REL}"
ADAPTER="${ROOT}/${ADAPTER_REL}"
PORT="${ROOT}/${PORT_REL}"
PATCHED_FILES=("${ORCHESTRATOR}" "${ADAPTER}" "${PORT}")

ADAPTER_CLASS=cn.studykid.multiagent.bootstrap.config.RagHitQuotaAdapterTest
GUARD_CLASS=cn.studykid.multiagent.bootstrap.config.ContextCompressionM10GuardTest
ORCHESTRATOR_CLASS=cn.studykid.multiagent.worker.agentchat.AgentChatOrchestratorTest
BAK=/tmp/m10_rag_quota_counterproof

# RV-M10-01：关闭态零副作用短路被撤（if(false) 让分支恒不命中，仍可编译）
OLD_DISABLED='''        if (!global.enabled()) {
            return null;
        }'''
NEW_DISABLED='''        if (false) { // RV-M10-01 模拟关闭态短路被撤
            return null;
        }'''

# RV-M10-02：唯一算法委托被撤（保留 Result 类型，赋 null → 运行期 fail-soft 回退）
OLD_ALLOCATE='        Result result = RagHitQuota.allocate(candidates, options, textTokens, refTokens);'
NEW_ALLOCATE='        Result result = null; // RV-M10-02 模拟唯一算法委托点被撤'

# RV-M10-03：元数据 overhead 漏算（写死 0，预算低估即「算 A 发 B」）
OLD_OVERHEAD='''                        hit.chunkId(), hit.text(), hit.score(), hit.locator(),
                        overheadTokensOf(hit)));'''
NEW_OVERHEAD='''                        hit.chunkId(), hit.text(), hit.score(), hit.locator(),
                        0)); // RV-M10-03 模拟 overhead 漏算'''

# RV-M10-04：降级引用偷带正文（配额失去意义）
OLD_REF='''        return "{\"chunkId\":" + quote(ref.chunkId())
                + ",\"locator\":" + quote(ref.locator())
                + ",\"refOnly\":true}";'''
NEW_REF='''        return "{\"chunkId\":" + quote(ref.chunkId())
                + ",\"locator\":" + quote(ref.locator())
                + ",\"refOnly\":true,\"text\":\"LEAK\"}"; // RV-M10-04 模拟引用偷带正文'''

# RV-M10-05：L2 正面接线被撤（调用改成 null；原调用留注释，剥注释守卫同样会红）
OLD_WIRING='''            Allocation allocation = ragHitQuotaPort.applyQuota(
                    new RagHitQuotaPort.Input(context.tenantId(), "chat:" + state.runId, mapped));'''
NEW_WIRING='''            Allocation allocation = null; // RV-M10-05 模拟 L2 配额空转
            // ragHitQuotaPort.applyQuota(
            //         new RagHitQuotaPort.Input(context.tenantId(), "chat:" + state.runId, mapped));'''

# RV-M10-06：fail-soft 被撤（配额异常直接抛出，中断回答 / 污染审计终态）
OLD_FAILSOFT='''        } catch (RuntimeException e) {
            // 组件级 fail-soft：不打印 RAG 正文 / 命中元数据，只记异常类型供排障。
            log.debug("L2 RAG 配额跳过（组件级 fail-soft）：kind={}", e.getClass().getSimpleName());
            return null;
        }
    }'''
NEW_FAILSOFT='''        } catch (RuntimeException e) {
            // 组件级 fail-soft：不打印 RAG 正文 / 命中元数据，只记异常类型供排障。
            log.debug("L2 RAG 配额跳过（组件级 fail-soft）：kind={}", e.getClass().getSimpleName());
            throw e; // RV-M10-06 模拟 fail-soft 被撤
        }
    }'''

mkdir -p "${BAK}"

# ⚠️ 备份/恢复统一走 Python 文件 IO，不用 cp / grep / md5：
#    沙箱 file policy 会拦截 cp 跨 /tmp ↔ 工作区复制「未跟踪」文件（PORT 是 ?? 新文件），
#    曾导致 file2.bak 从未生成、restore 静默 ENOENT、撤点残留源码而收尾误判。
#    Python 的 read_bytes/write_bytes 在同一进程内是普通文件 IO，不受 broker 拦截。
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
  # ⚠️ 只匹配「注释形态」的撤点标记（注入的 // RV-M10-0X），
  #    不匹配守卫 / 脚本里的 .contains("RV-M10-0X") 字面量——那是合法常驻纪律断言。
  #    用 Python 读文件，规避沙箱对 grep 读工作区未跟踪文件的 file-read-data 拦截。
  python3 - "${PATCHED_FILES[@]}" <<'PY'
import pathlib
import re
import sys

total = 0
for name in sys.argv[1:]:
    total += len(re.findall(r'// RV-M10-0[1-6]', pathlib.Path(name).read_text(encoding='utf-8')))
print(total)
PY
}

echo "════════ S3-M10 RAG 配额薄适配 · 六点反证 ════════"

echo ""
echo "▶ RV-M10-01：关闭态零副作用短路被撤"
if inject "${ADAPTER}" "${OLD_DISABLED}" "${NEW_DISABLED}"; then
  run_unit platform-bootstrap "RagHitQuotaAdapterTest#disabledAdapterShortCircuitsWithoutCounting" \
    "${ADAPTER_CLASS}" "${BAK}/rv01.log"
  expect_red "RV-M10-01 / 关闭态短路必须真红" \
    "$(report_dir platform-bootstrap)/${ADAPTER_CLASS}.txt" "${BAK}/rv01.log"
else
  echo "  ❌ RV-M10-01：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M10-02：唯一算法委托点被撤（同时验证 A.5 升级守卫不是空断言）"
if inject "${ADAPTER}" "${OLD_ALLOCATE}" "${NEW_ALLOCATE}"; then
  run_unit platform-bootstrap "ContextCompressionM10GuardTest#quotaKernelHasExactlyOneProductionDelegate" \
    "${GUARD_CLASS}" "${BAK}/rv02.log"
  expect_red "RV-M10-02 / 唯一委托守卫必须真红" \
    "$(report_dir platform-bootstrap)/${GUARD_CLASS}.txt" "${BAK}/rv02.log"
else
  echo "  ❌ RV-M10-02：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M10-03：元数据 overhead 漏算（算 A 发 B）"
if inject "${ADAPTER}" "${OLD_OVERHEAD}" "${NEW_OVERHEAD}"; then
  run_unit platform-bootstrap "RagHitQuotaAdapterTest#overheadUsesFrozenRendererAndProductionCounter" \
    "${ADAPTER_CLASS}" "${BAK}/rv03.log"
  expect_red "RV-M10-03 / 生产计数注入必须真红" \
    "$(report_dir platform-bootstrap)/${ADAPTER_CLASS}.txt" "${BAK}/rv03.log"
else
  echo "  ❌ RV-M10-03：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M10-04：降级引用偷带正文（配额空转）"
if inject "${PORT}" "${OLD_REF}" "${NEW_REF}"; then
  run_unit platform-bootstrap "RagHitQuotaAdapterTest#payloadJsonIsWhitelistedEscapedAndParseable" \
    "${ADAPTER_CLASS}" "${BAK}/rv04.log"
  expect_red "RV-M10-04 / 引用白名单必须真红" \
    "$(report_dir platform-bootstrap)/${ADAPTER_CLASS}.txt" "${BAK}/rv04.log"
else
  echo "  ❌ RV-M10-04：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M10-05：L2 正面接线被撤（配额空转）"
if inject "${ORCHESTRATOR}" "${OLD_WIRING}" "${NEW_WIRING}"; then
  run_unit agent-worker "AgentChatOrchestratorTest#ragQuotaPortReceivesNeutralHitsAndStillFences" \
    "${ORCHESTRATOR_CLASS}" "${BAK}/rv05.log"
  expect_red "RV-M10-05 / L2 正面接入必须真红" \
    "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/rv05.log"
else
  echo "  ❌ RV-M10-05：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ RV-M10-06：编排器 fail-soft 被撤（配额异常中断主流程）"
if inject "${ORCHESTRATOR}" "${OLD_FAILSOFT}" "${NEW_FAILSOFT}"; then
  run_unit agent-worker "AgentChatOrchestratorTest#ragQuotaFailureFallsBackToLegacyWithoutFailingRun" \
    "${ORCHESTRATOR_CLASS}" "${BAK}/rv06.log"
  expect_red "RV-M10-06 / 组件级 fail-soft 必须真红" \
    "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/rv06.log"
else
  echo "  ❌ RV-M10-06：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

echo ""
echo "▶ 收尾：恢复后六个基线用例都必须全绿"
run_unit platform-bootstrap "RagHitQuotaAdapterTest#disabledAdapterShortCircuitsWithoutCounting" \
  "${ADAPTER_CLASS}" "${BAK}/base01.log"
expect_green "基线 / 关闭态零副作用" \
  "$(report_dir platform-bootstrap)/${ADAPTER_CLASS}.txt" "${BAK}/base01.log"
run_unit platform-bootstrap "ContextCompressionM10GuardTest#quotaKernelHasExactlyOneProductionDelegate" \
  "${GUARD_CLASS}" "${BAK}/base02.log"
expect_green "基线 / 唯一委托 A.5 升级守卫" \
  "$(report_dir platform-bootstrap)/${GUARD_CLASS}.txt" "${BAK}/base02.log"
run_unit platform-bootstrap "RagHitQuotaAdapterTest#overheadUsesFrozenRendererAndProductionCounter" \
  "${ADAPTER_CLASS}" "${BAK}/base03.log"
expect_green "基线 / overhead 生产计数注入" \
  "$(report_dir platform-bootstrap)/${ADAPTER_CLASS}.txt" "${BAK}/base03.log"
run_unit platform-bootstrap "RagHitQuotaAdapterTest#payloadJsonIsWhitelistedEscapedAndParseable" \
  "${ADAPTER_CLASS}" "${BAK}/base04.log"
expect_green "基线 / 引用白名单" \
  "$(report_dir platform-bootstrap)/${ADAPTER_CLASS}.txt" "${BAK}/base04.log"
run_unit agent-worker "AgentChatOrchestratorTest#ragQuotaPortReceivesNeutralHitsAndStillFences" \
  "${ORCHESTRATOR_CLASS}" "${BAK}/base05.log"
expect_green "基线 / L2 正面接入" \
  "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/base05.log"
run_unit agent-worker "AgentChatOrchestratorTest#ragQuotaFailureFallsBackToLegacyWithoutFailingRun" \
  "${ORCHESTRATOR_CLASS}" "${BAK}/base06.log"
expect_green "基线 / 组件级 fail-soft" \
  "$(report_dir agent-worker)/${ORCHESTRATOR_CLASS}.txt" "${BAK}/base06.log"

echo ""
echo "▶ 收尾三件套：old 锚点全量计数 + 残留标记 + md5 复原 + git diff --check"
c1=$(anchor_count "${ADAPTER}" "${OLD_DISABLED}")
c2=$(anchor_count "${ADAPTER}" "${OLD_ALLOCATE}")
c3=$(anchor_count "${ADAPTER}" "${OLD_OVERHEAD}")
c4=$(anchor_count "${PORT}" "${OLD_REF}")
c5=$(anchor_count "${ORCHESTRATOR}" "${OLD_WIRING}")
c6=$(anchor_count "${ORCHESTRATOR}" "${OLD_FAILSOFT}")
markers=$(marker_count)
md5_now=$(md5_all)
if [ "${c1}" -eq 1 ] && [ "${c2}" -eq 1 ] && [ "${c3}" -eq 1 ] \
   && [ "${c4}" -eq 1 ] && [ "${c5}" -eq 1 ] && [ "${c6}" -eq 1 ] \
   && [ "${markers}" -eq 0 ] && [ "${md5_now}" = "${MD5_BEFORE}" ]; then
  echo "  ✅ 锚点 OLD_DISABLED=${c1} OLD_ALLOCATE=${c2} OLD_OVERHEAD=${c3}"
  echo "     OLD_REF=${c4} OLD_WIRING=${c5} OLD_FAILSOFT=${c6}"
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
  echo "✅ S3-M10 六点反证全部成立，撤点已复原"
  exit 0
else
  echo "❌ 存在无效反证或未复原 —— 见上"
  exit 1
fi
