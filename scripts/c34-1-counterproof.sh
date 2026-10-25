#!/usr/bin/env bash
# 第三十四批 C34-1 反证脚本：钉住评审前置决策 4 的语义
# （**`cap_agent_child_ref` 存储** —— 父子关系投影**必落库**；
#   投影端口未装配且本次发布**带了子引用** → **fail-closed 拒绝发布**）。
#
# ⚠️ 本脚本要证伪的是三件事，每件对应一个**独立的失效形态**：
#
#   · **反证 A（静默失败回归）**：把 `projectChildRefs` 里的
#     「投影未装配 → 抛异常拒绝」改回「投影未装配 → 直接 return」（静默跳过）
#     → `AgentChildRefProjectionTest` 必须**恰好红 1 条**
#     （正是钉住 fail-closed 的那条），且**其余用例保持绿**
#     （红了但超出预期条数同样判无效）。
#
#   · **反证 B（过杀防护）**：把判据从「**有没有边**」换成「**仓储装没装配**」
#     （即：仓储没装就一律拒绝发布，不管这次发布有没有子引用）
#     → `AgentChildRefProjectionTest` 必须**恰好红 1 条**，
#     且红的正是「LEAF 不该被拒」那条。
#     ⚠️ 这条反证保护的是**判据的边界**：只写「要拒绝」而不写「不该拒绝」，
#     实现就会被改成「仓储没装配 → 全拒」——
#     那会让投影能力成为整个平台级 Agent 发布的**隐形前置依赖**。
#     ⚠️ **实测记录**：这个注入在**全模块**视角下会红 **9 条** ——
#     也就是说「判据选错」会波及所有「未装配投影端口 + 本次发布没有子引用」的发布场景，
#     而不只是漏判的那一条。这正是判据必须按「有没有边」而不是
#     「仓储装没装配」划界的原因。
#     因此本反证**精确到单个测试类**计数（`AgentChildRefProjectionTest`），
#     全模块的「其余用例保持绿」由**基线**与**收尾恢复**两次全模块跑覆盖。
#
#   · **反证 C（读路径口径）**：把 `SQL_LIST_BY_PARENT_AGENT` 改成
#     「按父 release 版本过滤」 → 真实 PG 上的 `MultiAgentPersistenceIT`
#     必须**恰好红 1 条**（M-9：出向必须跨父 release 聚合）。
#     ⚠️ 这条证明「不限版本」是**真的不限**，而不是文档里的一句口号。
#     ⚠️ IT 受 `@EnabledIfEnvironmentVariable(IT_PG_URL)` 守门 ——
#     **没有真实 PG 时 IT 会被跳过**，跳过的用例**不算绿**，
#     本脚本据此判定「反证 C 无效」并失败退出（而不是假装通过）。
#
# ⚠️ **判定**：结论不变（仍全绿 / 结论没变）= 无效反证。
#   编译失败同样判无效（「编译不过」不是「用例红」，它证明不了任何结论）。
set -u

ROOT=/Users/zhouwei/code-workspace/ai-tech/java-multi-agent
PLATFORM="${ROOT}/platform"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="${JAVA_HOME}/bin:${PATH}"

PUBLISH="${PLATFORM}/agent-application/src/main/java/cn/studykid/multiagent/application/cap/ConfigPublishService.java"
REPO="${PLATFORM}/agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapAgentChildRefRepository.java"

BAK=/tmp/c34_1_counterproof
mkdir -p "${BAK}"
cp "${PUBLISH}" "${BAK}/publish.bak"
cp "${REPO}" "${BAK}/repo.bak"

FAILED=0

restore() {
  cp "${BAK}/publish.bak" "${PUBLISH}"
  cp "${BAK}/repo.bak" "${REPO}"
}
trap restore EXIT

# ⚠️ **只数单条用例的失败行**：surefire 还会额外打一行类汇总
#    （那行带 `<<< FAILURE!` 但不是一条用例 —— 算进去会让计数翻倍）。
count_red() {
  grep -cE "^\[ERROR\] +[A-Za-z0-9_.]+ -- Time elapsed" "${1}"
}

# ⚠️ **数「跑了多少条」**：用于确认 IT 没被整体跳过。
count_ran() {
  grep -cE "Tests run: .*Time elapsed" "${1}"
}

report() {
  local label="${1}" expect_red="${2}" log="${3}"
  local red
  red=$(count_red "${log}")
  if [ "${expect_red}" -eq 0 ]; then
    if [ "${red}" -eq 0 ]; then
      echo "  ✅ ${label}：全绿（符合预期）"
    else
      echo "  ⛔ ${label}：红了 ${red} 条，期望 0 条 → 反证范围超出预期，结论不纯"
      FAILED=$((FAILED + 1))
    fi
  else
    if [ "${red}" -eq "${expect_red}" ]; then
      echo "  ✅ ${label}：恰好红 ${red} 条（符合预期）"
    else
      echo "  ⛔ ${label}：红了 ${red} 条，期望恰好 ${expect_red} 条 → 无效反证"
      FAILED=$((FAILED + 1))
    fi
  fi
}

# ⚠️ **单类跑**：反证 A / B 的判据是「某一条用例该红 / 不该红」，
#    精确到**单个测试类**计数才不会把「波及面」混进「结论」里。
#    波及面由基线与收尾的全模块跑覆盖。
run_single_class() {
  local log="${1}"
  (cd "${PLATFORM}" && mvn -o -B -pl agent-application -am \
      -Dtest=AgentChildRefProjectionTest -Dsurefire.failIfNoSpecifiedTests=false test) \
      > "${log}" 2>&1
}

echo "C34-1 反证：决策 4 的语义（cap_agent_child_ref 存储 · 投影必落库 · 未装配即 fail-closed）"
echo

# ── 基线 ────────────────────────────────────────────────────────────────
echo "基线：未注入任何缺陷 → 后端全绿"
(cd "${PLATFORM}" && mvn -o -B -pl agent-application -am test) > "${BAK}/baseline.log" 2>&1
report "基线（后端 agent-application 全模块）" 0 "${BAK}/baseline.log"
if grep -q "BUILD FAILURE\|COMPILATION ERROR" "${BAK}/baseline.log"; then
  echo "  ⛔ 基线：编译失败 → 先修编译再做反证"
  FAILED=$((FAILED + 1))
fi
echo

# ── 反证 A：把 fail-closed 改回「静默跳过」 ──────────────────────────────
echo "反证 A：把 projectChildRefs 里的「投影未装配 → 拒绝」改回「静默 return」"
echo "        → AgentChildRefProjectionTest 必须恰好红 1 条"
python3 - "${PUBLISH}" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
old = """        if (childRefs == null) {
            throw new ConfigPublishRejectedException(ReasonCode.ORCHESTRATION_INVALID,
                    "orchestration",
                    "父子关系投影未装配（关系投影仓储缺失），拒绝发布带子 Agent 引用的 Agent："
                            + "关系边落不了库，「谁依赖谁」就查不出来，"
                            + "影响分析与删除守卫会因此放行「被引用对象被抽走」的事故");
        }"""
new = """        if (childRefs == null) {
            return;
        }"""
assert old in s, "锚点未命中（projectChildRefs 的 null 分支结构变了？）"
assert s.count(old) == 1, f"锚点不唯一（命中 {s.count(old)}）"
p.write_text(s.replace(old, new, 1), encoding='utf-8')
PY
run_single_class "${BAK}/inject_a.log"
if grep -q "COMPILATION ERROR" "${BAK}/inject_a.log"; then
  echo "  ⛔ 反证 A 无效：源码无法编译（编译不过不是用例红，它证明不了任何结论）"
  FAILED=$((FAILED + 1))
else
  report "反证 A（注入后 · 单类）" 1 "${BAK}/inject_a.log"
  if grep -q "projectionNotWiredRejectsPublishWithChildren" "${BAK}/inject_a.log"; then
    echo "  ✅ 反证 A：红的正是钉住 fail-closed 的那条用例"
  else
    echo "  ⛔ 反证 A：projectionNotWiredRejectsPublishWithChildren 没红 → 注入没打到点上"
    FAILED=$((FAILED + 1))
  fi
fi
restore
(cd "${PLATFORM}" && mvn -o -B -pl agent-application -am test) > "${BAK}/restored_a.log" 2>&1
report "反证 A 收尾（恢复后）" 0 "${BAK}/restored_a.log"
echo

# ── 反证 B：把判据从「有没有边」换成「仓储装没装配」 ─────────────────────
echo "反证 B：把判据从「有没有边」换成「仓储装没装配」"
echo "        （仓储没装配就一律拒绝，不管这次发布有没有子引用）"
echo "        → AgentChildRefProjectionTest 必须恰好红 1 条（且是 LEAF 那条）"
python3 - "${PUBLISH}" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
old = """        if (edges.isEmpty()) {
            return;
        }"""
new = """        if (edges.isEmpty() && childRefs != null) {
            return;
        }"""
assert old in s, "锚点未命中（edges.isEmpty 的判据结构变了？）"
assert s.count(old) == 1, f"锚点不唯一（命中 {s.count(old)}）"
p.write_text(s.replace(old, new, 1), encoding='utf-8')
PY
run_single_class "${BAK}/inject_b.log"
if grep -q "COMPILATION ERROR" "${BAK}/inject_b.log"; then
  echo "  ⛔ 反证 B 无效：源码无法编译（编译不过不是用例红，它证明不了任何结论）"
  FAILED=$((FAILED + 1))
else
  report "反证 B（注入后）" 1 "${BAK}/inject_b.log"
  if grep -q "projectionNotWiredStillAllowsLeafPublish" "${BAK}/inject_b.log"; then
    echo "  ✅ 反证 B：红的正是「LEAF 不该被过度拦截」那条"
  else
    echo "  ⛔ 反证 B：projectionNotWiredStillAllowsLeafPublish 没红 → 注入没打到点上"
    FAILED=$((FAILED + 1))
  fi
fi
restore
(cd "${PLATFORM}" && mvn -o -B -pl agent-application -am test) > "${BAK}/restored_b.log" 2>&1
report "反证 B 收尾（恢复后）" 0 "${BAK}/restored_b.log"
echo

# ── 反证 C：把出向查询改成按父 release 过滤 ──────────────────────────────
echo "反证 C：把 SQL_LIST_BY_PARENT_AGENT 改成按父 release 版本过滤"
echo "        → 真实 PG 上的 MultiAgentPersistenceIT 必须恰好红 1 条（M-9）"

: "${IT_PG_URL:=jdbc:postgresql://127.0.0.1:5433/agent_dev}"
: "${IT_PG_USER:=app_agent}"
: "${IT_PG_PASSWORD:=app_agent_pw}"
: "${IT_PG_ADMIN_USER:=agent}"
: "${IT_PG_ADMIN_PASSWORD:=agent_dev_pw}"
export IT_PG_URL IT_PG_USER IT_PG_PASSWORD IT_PG_ADMIN_USER IT_PG_ADMIN_PASSWORD

python3 - "${REPO}" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
old = """            WHERE tenant_id = ?::uuid AND parent_agent_id = ?::uuid
            ORDER BY parent_release_version ASC, ordinal ASC"""
new = """            WHERE tenant_id = ?::uuid AND parent_agent_id = ?::uuid
              AND parent_release_version = 1
            ORDER BY parent_release_version ASC, ordinal ASC"""
assert old in s, "锚点未命中（出向查询的 SQL 结构变了？）"
assert s.count(old) == 1, f"锚点不唯一（命中 {s.count(old)}）"
p.write_text(s.replace(old, new, 1), encoding='utf-8')
PY
(cd "${PLATFORM}" && mvn -o -B -pl agent-infrastructure -am \
    -Dtest=NoSuchUnitTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test=MultiAgentPersistenceIT -Dfailsafe.failIfNoSpecifiedTests=false verify) \
    > "${BAK}/inject_c.log" 2>&1
if grep -q "COMPILATION ERROR" "${BAK}/inject_c.log"; then
  echo "  ⛔ 反证 C 无效：源码无法编译（编译不过不是用例红，它证明不了任何结论）"
  FAILED=$((FAILED + 1))
else
  RAN=$(count_ran "${BAK}/inject_c.log")
  if [ "${RAN}" -eq 0 ]; then
    echo "  ⛔ 反证 C 无效：IT 一条都没跑（多半是没有真实 PG，@EnabledIfEnvironmentVariable 把它跳过了）"
    echo "     ⚠️ 跳过的用例不算绿 —— 结论未被证伪，故本次反证**无效**"
    FAILED=$((FAILED + 1))
  else
    report "反证 C（注入后）" 1 "${BAK}/inject_c.log"
    if grep -q "outboundQuerySpansParentVersions" "${BAK}/inject_c.log"; then
      echo "  ✅ 反证 C：红的正是 M-9（出向必须跨父 release 聚合）"
    else
      echo "  ⛔ 反证 C：outboundQuerySpansParentVersions 没红 → 注入没打到点上"
      FAILED=$((FAILED + 1))
    fi
  fi
fi
restore
echo

echo "──────────────────────────────────────────────"
if [ "${FAILED}" -ne 0 ]; then
  echo "⛔ 反证失败 ${FAILED} 项 —— 本批不予提交"
  exit 1
fi
echo "✅ 全部反证成立"
