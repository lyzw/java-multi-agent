#!/usr/bin/env bash
# 第三十四批 C34-2 反证脚本：钉住「平台通道」的四条性质
# （管理台改造 · 平台级对象 Tool / Skill / MCP Server 的写入口）。
#
# ⚠️ 本脚本要证伪的是四件事，每件对应一个**独立的失效形态**：
#
#   · **反证 A（闸门）**：把 `PlatformCapabilityController.requirePlatformAdmin()`
#     里的 `PLATFORM_ADMIN` 角色判定摘掉 → `PlatformCapabilityControllerTest`
#     必须**恰好红 2 条**（「非 PLATFORM_ADMIN 被拒」+「空角色集被拒」），
#     且其余用例保持绿。
#     ⚠️ 这条保护的是「默认拒绝」：**平台身份表是租户内的，没有跨租户主体**，
#     没有这道闸门，任一租户管理员自授角色即可改全平台配置。
#
#   · **反证 B（申请单作用域）**：把平台申请单的 `tenant_id` 从 NULL 改成一个
#     编造的租户 id → `PlatformCapabilityPublishServiceTest` 必须**恰好红 1 条**
#     （`requestPublishUsesPlatformScope`）。
#     ⚠️ 这条保护的是「平台申请单不落到任何租户的名下」——
#     落进去的话，它会出现在那个租户的申请单清单里，而租户根本无权审批平台级变更。
#
#   · **反证 C（作用域不得混装）**：把平台发布的 `scope` 从 PLATFORM 改成 TENANT
#     → 必须**恰好红 1 条**（`platformPublishCarriesNoTenantObjects`）。
#     ⚠️ 这条保护的是「发布头的作用域与内容自相矛盾」——
#     一个 TENANT 作用域的发布头里装着平台对象，追溯与回滚全乱。
#
#   · **反证 D（运维通道 fail-closed）**：把平台通道的「运维会话」包装摘掉
#     （直接调用申请单仓储，不再经过会话闸门）→ 必须**恰好红 1 条**
#     （`unavailableOpsChannelFailsClosed`）。
#     ⚠️ 这条保护的是「运维角色未接入时明确失败」——
#     摘掉会话包装后，运维角色未接入的环境会**直接落到应用角色连接**上，
#     而申请单表的 RLS 读写策略是严格相等 → 平台申请单根本写不进去，
#     报错点落在 RLS 策略上，离「这个环境没配运维凭据」这一真因极远。
#
# ⚠️ **判定**：结论不变（仍全绿 / 结论没变）= 无效反证。
#   编译失败同样判无效（「编译不过」不是「用例红」，它证明不了任何结论）。
set -u

ROOT=/Users/zhouwei/code-workspace/ai-tech/java-multi-agent
PLATFORM="${ROOT}/platform"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="${JAVA_HOME}/bin:${PATH}"

CTRL="${PLATFORM}/agent-api/src/main/java/cn/studykid/multiagent/api/config/PlatformCapabilityController.java"
SVC="${PLATFORM}/agent-application/src/main/java/cn/studykid/multiagent/application/cap/PlatformCapabilityPublishService.java"

BAK=/tmp/c34_2_counterproof
mkdir -p "${BAK}"
cp "${CTRL}" "${BAK}/ctrl.bak"
cp "${SVC}" "${BAK}/svc.bak"

FAILED=0

restore() {
  cp "${BAK}/ctrl.bak" "${CTRL}"
  cp "${BAK}/svc.bak" "${SVC}"
}
trap restore EXIT

# ⚠️ **只数单条用例的失败行**：surefire 还会额外打一行类汇总
#    （那行带 `<<< FAILURE!` 但不是一条用例 —— 算进去会让计数翻倍）。
count_red() {
  grep -cE "^\[ERROR\] +[A-Za-z0-9_.]+ -- Time elapsed" "${1}"
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

run_api_test() {
  local log="${1}"
  (cd "${PLATFORM}" && mvn -o -B -pl agent-api -am \
      -Dtest=PlatformCapabilityControllerTest -Dsurefire.failIfNoSpecifiedTests=false test) \
      > "${log}" 2>&1
}

run_app_test() {
  local log="${1}"
  (cd "${PLATFORM}" && mvn -o -B -pl agent-application -am \
      -Dtest=PlatformCapabilityPublishServiceTest -Dsurefire.failIfNoSpecifiedTests=false test) \
      > "${log}" 2>&1
}

echo "C34-2 反证：平台通道的四条性质（闸门 / 申请单作用域 / 作用域不得混装 / 运维通道 fail-closed）"
echo

# ── 基线 ────────────────────────────────────────────────────────────────
echo "基线：未注入任何缺陷 → 两条测试全绿"
run_api_test "${BAK}/baseline_api.log"
report "基线（API 层 · 平台闸门）" 0 "${BAK}/baseline_api.log"
run_app_test "${BAK}/baseline_app.log"
report "基线（应用层 · 平台发布服务）" 0 "${BAK}/baseline_app.log"
echo

# ── 反证 A：摘掉 PLATFORM_ADMIN 闸门 ─────────────────────────────────────
echo "反证 A：把 requirePlatformAdmin() 里的 PLATFORM_ADMIN 角色判定摘掉"
echo "        → PlatformCapabilityControllerTest 必须恰好红 2 条"
python3 - "${CTRL}" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
old = """        if (!context.hasRole("PLATFORM_ADMIN")) {"""
new = """        if (false) {"""
assert old in s, "锚点未命中（闸门判定结构变了？）"
assert s.count(old) == 1, f"锚点不唯一（命中 {s.count(old)}）"
p.write_text(s.replace(old, new, 1), encoding='utf-8')
PY
run_api_test "${BAK}/inject_a.log"
if grep -q "COMPILATION ERROR" "${BAK}/inject_a.log"; then
  echo "  ⛔ 反证 A 无效：源码无法编译（编译不过不是用例红，它证明不了任何结论）"
  FAILED=$((FAILED + 1))
else
  report "反证 A（注入后）" 2 "${BAK}/inject_a.log"
  for name in nonPlatformAdminIsForbidden emptyRolesAreForbidden; do
    if grep -q "${name}" "${BAK}/inject_a.log"; then
      echo "  ✅ 反证 A：红的正是「非平台管理员被拒」那条（${name}）"
    else
      echo "  ⛔ 反证 A：${name} 没红 → 注入没打到点上"
      FAILED=$((FAILED + 1))
    fi
  done
fi
restore
run_api_test "${BAK}/restored_a.log"
report "反证 A 收尾（恢复后）" 0 "${BAK}/restored_a.log"
echo

# ── 反证 B：给平台申请单编一个 tenant_id ─────────────────────────────────
echo "反证 B：把平台申请单的 tenant_id 从 NULL 改成一个编造的租户 id"
echo "        → PlatformCapabilityPublishServiceTest 必须恰好红 1 条"
python3 - "${SVC}" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
old = """        return session.inPlatformScopeSession(() -> requests.insert(
                null, payload.operation(), payload.toJson(), reason, requesterId));"""
new = """        return session.inPlatformScopeSession(() -> requests.insert(
                "00000000-0000-0000-0000-00000000ffff", payload.operation(), payload.toJson(),
                reason, requesterId));"""
assert old in s, "锚点未命中（insert 调用结构变了？）"
assert s.count(old) == 1, f"锚点不唯一（命中 {s.count(old)}）"
p.write_text(s.replace(old, new, 1), encoding='utf-8')
PY
run_app_test "${BAK}/inject_b.log"
if grep -q "COMPILATION ERROR" "${BAK}/inject_b.log"; then
  echo "  ⛔ 反证 B 无效：源码无法编译"
  FAILED=$((FAILED + 1))
else
  report "反证 B（注入后）" 1 "${BAK}/inject_b.log"
  if grep -q "requestPublishUsesPlatformScope" "${BAK}/inject_b.log"; then
    echo "  ✅ 反证 B：红的正是「申请单作用域必须是 PLATFORM」那条"
  else
    echo "  ⛔ 反证 B：requestPublishUsesPlatformScope 没红 → 注入没打到点上"
    FAILED=$((FAILED + 1))
  fi
fi
restore
run_app_test "${BAK}/restored_b.log"
report "反证 B 收尾（恢复后）" 0 "${BAK}/restored_b.log"
echo

# ── 反证 C：把平台发布的 scope 改成 TENANT ───────────────────────────────
echo "反证 C：把平台发布命令的 scope 从 PLATFORM 改成 TENANT"
echo "        → PlatformCapabilityPublishServiceTest 必须恰好红 1 条"
python3 - "${SVC}" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
old = """        return new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null, baseline,"""
new = """        return new ConfigPublishCommand(ConfigRelease.Scope.TENANT, "tenant-x", baseline,"""
assert old in s, "锚点未命中（发布命令构造处变了？）"
assert s.count(old) == 1, f"锚点不唯一（命中 {s.count(old)}）"
p.write_text(s.replace(old, new, 1), encoding='utf-8')
PY
run_app_test "${BAK}/inject_c.log"
if grep -q "COMPILATION ERROR" "${BAK}/inject_c.log"; then
  echo "  ⛔ 反证 C 无效：源码无法编译"
  FAILED=$((FAILED + 1))
else
  report "反证 C（注入后）" 1 "${BAK}/inject_c.log"
  if grep -q "platformPublishCarriesNoTenantObjects" "${BAK}/inject_c.log"; then
    echo "  ✅ 反证 C：红的正是「平台发布不得携带租户级对象 / 作用域不得混装」那条"
  else
    echo "  ⛔ 反证 C：platformPublishCarriesNoTenantObjects 没红 → 注入没打到点上"
    FAILED=$((FAILED + 1))
  fi
fi
restore
run_app_test "${BAK}/restored_c.log"
report "反证 C 收尾（恢复后）" 0 "${BAK}/restored_c.log"
echo

# ── 反证 D：摘掉运维会话包装 ─────────────────────────────────────────────
echo "反证 D：把平台通道的「运维会话」包装摘掉（直接调用申请单仓储）"
echo "        → PlatformCapabilityPublishServiceTest 必须恰好红 1 条"
python3 - "${SVC}" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
old = """        return session.inPlatformScopeSession(() -> requests.insert(
                null, payload.operation(), payload.toJson(), reason, requesterId));"""
new = """        return requests.insert(
                null, payload.operation(), payload.toJson(), reason, requesterId);"""
assert old in s, "锚点未命中（insert 的会话包装结构变了？）"
assert s.count(old) == 1, f"锚点不唯一（命中 {s.count(old)}）"
p.write_text(s.replace(old, new, 1), encoding='utf-8')
PY
run_app_test "${BAK}/inject_d.log"
if grep -q "COMPILATION ERROR" "${BAK}/inject_d.log"; then
  echo "  ⛔ 反证 D 无效：源码无法编译"
  FAILED=$((FAILED + 1))
else
  report "反证 D（注入后）" 1 "${BAK}/inject_d.log"
  if grep -q "unavailableOpsChannelFailsClosed" "${BAK}/inject_d.log"; then
    echo "  ✅ 反证 D：红的正是「运维通道未接入必须 fail-closed」那条"
  else
    echo "  ⛔ 反证 D：unavailableOpsChannelFailsClosed 没红 → 注入没打到点上"
    FAILED=$((FAILED + 1))
  fi
fi
restore
run_app_test "${BAK}/restored_d.log"
report "反证 D 收尾（恢复后）" 0 "${BAK}/restored_d.log"
echo

echo "──────────────────────────────────────────────"
if [ "${FAILED}" -ne 0 ]; then
  echo "⛔ 反证失败 ${FAILED} 项 —— 本批不予提交"
  exit 1
fi
echo "✅ 全部反证成立"
