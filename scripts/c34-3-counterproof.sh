#!/usr/bin/env bash
# 第三十四批 C34-3 反证脚本：钉住「管理台前端改造」的六条性质
# （Agent / Skill / Tool / MCP 四类对象的详细数据增删改查 + 关系维护 + 委派拓扑）。
#
# ⚠️ 本脚本要证伪的是六件事，每件对应一个**独立的失效形态**：
#
#   · **反证 A（回填不全不得静默清空关系）**：把「取不到权威定义时置
#     definitionUnavailable」这行摘掉 → `agentDefinitionBackfill.test.tsx`
#     必须**恰好红 3 条**。
#     ⚠️ 这条保护的是「改一下就把关系清了」：权威定义（cap_agent_def）取不到时，
#     关系字段（角色 / 启用技能 / 技能包 / 能力标签 / 编排声明 / overrides）
#     无从回填，若允许保存就等于把它们**覆盖成空**（对非 LEAF = 取消全部委派），
#     而用户**看不出来**。
#
#   · **反证 B（三态不可归一化）**：把 `fromAllowlistTriState` 的 EMPTY 分支
#     从 `[]` 改成 `null` → `platformObjectForm.test.ts` + `platformCatalog.test.tsx`
#     必须**恰好红 6 条**（含 2 条页面级闭环）。
#     ⚠️ 这条保护的是「null ≠ []」：`declared_mcp[].tool_allowlist` 与
#     `exposed_tool_allowlist` 的三态里，`null` = 未配置（取全集）、
#     `[]` = 显式空（**不允许任何操作**）。把「显式空」归一成「未配置」
#     等于**权限放宽**（fail-open）。
#
#   · **反证 C（403 不得当成普通失败）**：把 `describePlatformError` 的 403 分支
#     改成 400 的文案 → `platformCatalog.test.tsx` 必须**恰好红 1 条**。
#     ⚠️ 这条保护的是「闸门语义可分辨」：403 = 不是 PLATFORM_ADMIN
#     （作者改不了，得换人）、400 = 参数非法（作者能改）、503 = 运维通道未接入
#     （**环境问题，不是用户的错**）。合并成一句「操作失败」后，
#     操作者会去改自己的填法，而真因在权限或环境。
#
#   · **反证 D（凭据引用不得被静默丢弃）**：把 Skill 页的
#     `credentialRefLossBlocker` 阻断摘掉 → `platformCatalog.test.tsx`
#     必须**恰好红 1 条**。
#     ⚠️ 这条保护的是「显式授权材料被静默清空」：闭包补齐会把既有 MCP Server
#     一并重发，而目录接口**从不回显凭据定位符**（只给 `hasCredentialRef` 布尔）
#     ⇒ 重发出来的新版本**没有凭据引用** ⇒ 后端建新版本行时凭据为 null
#     ⇒ 指针移过去后该 Server 在**全平台**的生效配置里认证失效。
#     一个「改 Skill 的声明清单」的操作，静默改掉了全平台的 MCP 认证。
#
#   · **反证 E（不得有物理删除入口）**：在停用卡片里注入一个「删除」按钮
#     → `platformCatalog.test.tsx` 必须**恰好红 1 条**。
#     ⚠️ 这条保护的是「删除 = 停用」：已发布行不可变（DB 触发器），
#     物理删除会让引用它的 Skill 变成悬空引用。
#     ⚠️ **这条曾经红不了**：原断言写的是 `queryByRole('button', { name: /^删除/ })`，
#     而 antd 会给**两字中文**按钮名中间插空格（「删除」渲染成「删 除」），
#     正则**永远匹配不上** —— 那是一条**空断言**（即使真有删除按钮也照样绿）。
#     现已改为「扫全部按钮、取归一化文本」，并附证「停用入口在」。
#
#   · **反证 F（传递闭包不得省）**：把 `planClosure` 里「MCP 型 Tool 绑定的
#     Server 也要纳入发布集合」那一段摘掉 → `platformObjectForm.test.ts`
#     必须**恰好红 2 条**。
#     ⚠️ 这条保护的是「发布集合自洽」：后端 `ConfigValidationGate` 要求
#     MCP 型 Tool 绑定的 Server 也出现在同一次发布集合里，否则以
#     `MCP_NOT_BOUND` 拒 —— 而被拒的那个对象根本不在用户眼前。
#
# ⚠️ **判定**：结论不变（仍全绿 / 结论没变）= 无效反证。
#   编译失败同样判无效（「编译不过」不是「用例红」，它证明不了任何结论）。
set -u

ROOT=/Users/zhouwei/code-workspace/ai-tech/java-multi-agent
CONSOLE="${ROOT}/admin-console"

FORM="${CONSOLE}/src/pages/catalog/platformObjectForm.ts"
AGENT="${CONSOLE}/src/pages/AgentDetailPage.tsx"
PANEL="${CONSOLE}/src/pages/catalog/PlatformPublishPanel.tsx"
SKILL="${CONSOLE}/src/pages/catalog/SkillDetailPage.tsx"
DEPRECATE="${CONSOLE}/src/pages/catalog/PlatformDeprecateCard.tsx"

BAK=/tmp/c34_3_counterproof
mkdir -p "${BAK}"
cp "${FORM}" "${BAK}/form.bak"
cp "${AGENT}" "${BAK}/agent.bak"
cp "${PANEL}" "${BAK}/panel.bak"
cp "${SKILL}" "${BAK}/skill.bak"
cp "${DEPRECATE}" "${BAK}/deprecate.bak"

FAILED=0

restore() {
  cp "${BAK}/form.bak" "${FORM}"
  cp "${BAK}/agent.bak" "${AGENT}"
  cp "${BAK}/panel.bak" "${PANEL}"
  cp "${BAK}/skill.bak" "${SKILL}"
  cp "${BAK}/deprecate.bak" "${DEPRECATE}"
}
trap restore EXIT

# ⚠️ **只数单条用例的失败行**：vitest 还会额外打一行汇总
#    （`Failed Tests N`），算进去会让计数翻倍。
count_red() {
  grep -cE "^\s+× " "${1}"
}

report() {
  local label="${1}" expect_red="${2}" log="${3}"
  local red
  red=$(count_red "${log}")
  if [ "${expect_red}" -eq 0 ]; then
    if [ "${red}" -eq 0 ]; then
      echo "  ✅ ${label}：全绿（符合预期）"
    else
      echo "  ❌ ${label}：预期 0 条红，实际 ${red} 条 —— 反证无效"
      FAILED=1
    fi
  else
    if [ "${red}" -eq "${expect_red}" ]; then
      echo "  ✅ ${label}：恰好红 ${red} 条（符合预期）"
    else
      echo "  ❌ ${label}：预期 ${expect_red} 条红，实际 ${red} 条 —— 反证无效"
      FAILED=1
    fi
  fi
}

# ⚠️ **反证的注入一律用 python 做「唯一锚点」替换**：
#    锚点必须与实际文本**逐字一致**且在文件里**只出现一次**，
#    否则替换会命中多处或零处 —— 那就不是一个「只动一个地方」的反证了。
inject() {
  local file="${1}" old="${2}" new="${3}"
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

run_tests() {
  local out="${1}"
  shift
  (cd "${CONSOLE}" && npx vitest run "$@" --reporter=basic > "${out}" 2>&1)
  return 0
}

echo "════════ 第三十四批 · C34-3 前端反证 ════════"

# ── 反证 A：回填不全不得静默清空关系 ──
echo ""
echo "▶ 反证 A：摘掉「取不到权威定义 → definitionUnavailable」标记"
restore
if inject "${AGENT}" \
"      setAgentDefinition(null)
      setDefinitionUnavailable(true)
      setAcknowledgeClearRelations(false)" \
"      setAgentDefinition(null)
      setAcknowledgeClearRelations(false)"; then
  run_tests "${BAK}/a.log" tests/agentDefinitionBackfill.test.tsx
  report "反证 A（回填不全不得静默清空关系）" 3 "${BAK}/a.log"
else
  echo "  ❌ 反证 A：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

# ── 反证 B：三态不可归一化 ──
echo ""
echo "▶ 反证 B：把 allowlist 的 EMPTY 归一成 null"
if inject "${FORM}" \
"  if (state === 'UNSET') return null
  if (state === 'EMPTY') return []
  return [...(list ?? [])]" \
"  if (state === 'UNSET') return null
  if (state === 'EMPTY') return null
  return [...(list ?? [])]"; then
  run_tests "${BAK}/b.log" tests/platformObjectForm.test.ts tests/platformCatalog.test.tsx
  report "反证 B（三态不可归一化）" 6 "${BAK}/b.log"
else
  echo "  ❌ 反证 B：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

# ── 反证 C：403 不得当成普通失败 ──
echo ""
echo "▶ 反证 C：把 403 的提示并入 400"
if inject "${PANEL}" \
"    if (e.status === 403) return '权限不足（403）：平台级配置仅限 PLATFORM_ADMIN'" \
"    if (e.status === 403) return '申请被门禁预检拒绝（400）：请检查填写内容与引用完整性'"; then
  run_tests "${BAK}/c.log" tests/platformCatalog.test.tsx
  report "反证 C（403 不得当成普通失败）" 1 "${BAK}/c.log"
else
  echo "  ❌ 反证 C：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

# ── 反证 D：凭据引用不得被静默丢弃 ──
echo ""
echo "▶ 反证 D：摘掉「闭包补齐会丢凭据引用」的 fail-closed 阻断"
if inject "${SKILL}" \
"    const credentialBlocker = credentialRefLossBlocker(credentialRisks)
    if (credentialBlocker) {
      return { error: credentialBlocker }
    }" \
"    void credentialRisks
    void credentialRefLossBlocker"; then
  run_tests "${BAK}/d.log" tests/platformCatalog.test.tsx
  report "反证 D（凭据引用不得被静默丢弃）" 1 "${BAK}/d.log"
else
  echo "  ❌ 反证 D：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

# ── 反证 E：不得有物理删除入口 ──
echo ""
echo "▶ 反证 E：在停用卡片里注入一个「删除」按钮"
python3 - "${DEPRECATE}" <<'PY'
import re, sys
path = sys.argv[1]
s = open(path, encoding='utf-8').read()
m = re.search(r'\n(\s*)<Button', s)
if not m:
    print('ANCHOR_MISMATCH')
    sys.exit(3)
ins = "\n%s<Button danger onClick={() => undefined}>删除</Button>" % m.group(1)
open(path, 'w', encoding='utf-8').write(s[:m.start()] + ins + s[m.start():])
PY
if [ $? -eq 0 ]; then
  run_tests "${BAK}/e.log" tests/platformCatalog.test.tsx
  report "反证 E（不得有物理删除入口）" 1 "${BAK}/e.log"
else
  echo "  ❌ 反证 E：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

# ── 反证 F：传递闭包不得省 ──
echo ""
echo "▶ 反证 F：摘掉「MCP 型 Tool 绑定的 Server 也要纳入发布集合」"
if inject "${FORM}" \
"  for (const tool of tools) {
    if (tool.kind !== 'MCP') continue
    const serverId = tool.mcpServerId
    if (!serverId || seenServers.has(serverId)) continue
    seenServers.add(serverId)
    transitiveServers.push(serverId)
    const def = resolveServer(serverId)
    if (def) servers.push(def)
    else unresolvedServers.push(serverId)
  }" \
"  // 反证注入：去掉传递闭包"; then
  run_tests "${BAK}/f.log" tests/platformObjectForm.test.ts
  report "反证 F（传递闭包不得省）" 2 "${BAK}/f.log"
else
  echo "  ❌ 反证 F：锚点不唯一 —— 反证无效"
  FAILED=1
fi
restore

# ── 收尾：基线必须恢复为全绿 ──
echo ""
echo "▶ 收尾：恢复后基线必须全绿"
run_tests "${BAK}/baseline.log" tests/platformObjectForm.test.ts tests/platformCatalog.test.tsx \
  tests/agentDefinitionBackfill.test.tsx tests/delegationTopology.test.tsx tests/orchestrationForm.test.tsx
report "基线（恢复后全绿）" 0 "${BAK}/baseline.log"

echo ""
if [ "${FAILED}" -eq 0 ]; then
  echo "✅ 六条反证全部成立"
  exit 0
else
  echo "❌ 存在无效反证 —— 见上"
  exit 1
fi
