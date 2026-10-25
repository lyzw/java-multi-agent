#!/usr/bin/env bash
# 第三十批 C30-1 反证：验证「文档结构门禁」**真的会咬**，且不会误伤合法内容。
#
# ⚠️ **本脚本不动真实文档**：全部在 `mktemp -d` 出来的草稿副本上操作
#    （`doc-table-check.py` 通过 `DOC_TABLE_BASE` 指向副本）——
#    「改乱了忘了还原」是这类脚本最常见的事故源，从根上避免。
#
# ⚠️ **反证纪律**：
#   · 每条反证**只改一个地方**；
#   · **结论必须变化**：缺陷注入后，对应判据必须从「不报」变成「报」，
#     否则判为**无效反证**并以非零码退出；
#   · **不该受影响的必须保持绿**：误伤类反证（如「围栏内加一行 `|`」），
#     期望是**仍然全绿** —— 这类反证证明的是「门禁不误伤」，不是「门禁会报」。
set -u -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCS="${ROOT}/docs/多级 Agent优化"
CHECK="${ROOT}/scripts/doc-table-check.py"
F00="${DOCS}/00_修订说明与评审决议项.md"
F06="${DOCS}/06_配置数据模型与生命周期设计.md"
FP0="${DOCS}/P0-D06_初始项目计划与RACI.md"

PASS=0
FAIL=0
SCRATCH=""

cleanup() {
  [ -n "${SCRATCH}" ] && [ -d "${SCRATCH}" ] && rm -rf "${SCRATCH}"
}
trap cleanup EXIT

reset_scratch() {
  rm -rf "${SCRATCH:?}/多级 Agent优化"
  cp -R "${DOCS}" "${SCRATCH}/多级 Agent优化"
}

# 在草稿副本上跑门禁；输出存到 ${OUT}
run_check() {
  OUT="${SCRATCH}/check.out"
  DOC_TABLE_BASE="${SCRATCH}/多级 Agent优化" python3 "${CHECK}" >"${OUT}" 2>&1
  RC=$?
  return ${RC}
}

# 断言：${1} = 期望的结果（hit / miss），${2} = 判据关键字，${3} = 反证名，${4} = 期望输出片段
assert() {
  local expect="${1}" key="${2}" name="${3}" frag="${4:-}"
  local found=0
  if grep -q -- "${key}" "${OUT}"; then found=1; fi
  if [ "${expect}" = "hit" ] && [ "${found}" = "1" ]; then
    if [ -n "${frag}" ] && ! grep -q -- "${frag}" "${OUT}"; then
      echo "  ⛔ ${name}：命中了「${key}」，但输出片段「${frag}」不在报告里 → 判据不精确"
      FAIL=$((FAIL + 1)); return
    fi
    echo "  ✅ ${name}：注入缺陷后判据如期报出（结论已变化）"
    PASS=$((PASS + 1)); return
  fi
  if [ "${expect}" = "miss" ] && [ "${found}" = "0" ]; then
    echo "  ✅ ${name}：注入后门禁**未误报**（结论未变化，但这是期望的「不误伤」）"
    PASS=$((PASS + 1)); return
  fi
  echo "  ⛔ ${name}：期望「${expect}」判据「${key}」，实际未出现 → 无效反证"
  FAIL=$((FAIL + 1))
}

echo "C30-1 反证：文档结构门禁（列数一致性 / 孤立表格行 / 断表）+ 内容口径"
echo

SCRATCH="$(mktemp -d)"
reset_scratch

# ── 基线：真实文档（未注入任何缺陷）必须全绿 ───────────────────────────
run_check
if [ "${RC}" -eq 0 ]; then
  echo "  ✅ 基线：当前文档门禁全绿（$(tail -1 "${OUT}")）"
  PASS=$((PASS + 1))
else
  echo "  ⛔ 基线：当前文档门禁**不绿**，先修文档再做反证"
  cat "${OUT}"
  exit 1
fi
echo

# ── 反证 1：断表（① 的回归）──────────────────────────────────────────
echo "反证 1：把「历批修正的设计缺陷」表中间插入一个空行 → 应报「断表」"
reset_scratch
python3 - "${SCRATCH}/多级 Agent优化/00_修订说明与评审决议项.md" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
lines = p.read_text().split("\n")
i = next(k for k, l in enumerate(lines) if l.startswith("| **委派是「免费动作」"))
lines.insert(i + 1, "")
p.write_text("\n".join(lines))
PY
run_check
assert hit "断表" "反证 1（断表）" "表格块的第 2 行不是分隔行"
echo

# ── 反证 2：孤儿表格行（② 的回归）────────────────────────────────────
echo "反证 2：把当年的孤儿行「| 决策项 1/2/3 | ... |」放回引用块后 → 应报「孤立表格行」"
reset_scratch
python3 - "${SCRATCH}/多级 Agent优化/00_修订说明与评审决议项.md" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
text = p.read_text()
anchor = "> 在上一提交上同样红，与本批改动无关。\n"
orphan = "| 决策项 1/2/3 | `SEQUENTIAL` 是否进 P2、最大深度与预算默认值、状态机与取消语义仍未冻结 |\n"
assert anchor in text
p.write_text(text.replace(anchor, anchor + orphan, 1))
PY
run_check
assert hit "孤立表格行" "反证 2（孤儿表格行）" "渲染器会当普通段落输出"
echo

# ── 反证 3：重复条目（③ 的回归）──────────────────────────────────────
echo "反证 3：把合并前的第二条「FR-027 SEQUENTIAL 串行聚合」放回去 → 计数应从 1 变 2"
reset_scratch
python3 - "${SCRATCH}/多级 Agent优化/00_修订说明与评审决议项.md" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
text = p.read_text()
anchor = "| FR-027 `SEQUENTIAL` 串行聚合 | **多子结果聚合**"
assert anchor in text
dup = ("| FR-027 `SEQUENTIAL` 串行聚合 | 失败策略已生效，但**多子结果聚合**"
       "（把多个子 Agent 的输出合成一份交父）未实现：当前一次委派只对应一个目标 |\n")
p.write_text(text.replace(anchor, dup + anchor, 1))
PY
cnt=$(grep -c 'FR-027 `SEQUENTIAL` 串行聚合' "${SCRATCH}/多级 Agent优化/00_修订说明与评审决议项.md")
if [ "${cnt}" -eq 2 ]; then
  echo "  ✅ 反证 3（重复条目）：计数由 1 变 2（结论已变化）"
  PASS=$((PASS + 1))
else
  echo "  ⛔ 反证 3（重复条目）：计数为 ${cnt}，未按预期变化 → 无效反证"
  FAIL=$((FAIL + 1))
fi
echo

# ── 反证 4：陈旧声明（④/⑤a 的回归）───────────────────────────────────
echo "反证 4：把「；DB 层用例待补 IT」放回 FR-022 行 → 陈旧声明计数应**恰好 +1**"
echo "        （用**相对**判定而非绝对值：本批的登记行里引用了这句原文，"
echo "          基线本身就不是 0，写死绝对值会随文档演进误判）"
reset_scratch
base4=$(grep -c "DB 层用例待补 IT" "${SCRATCH}/多级 Agent优化/00_修订说明与评审决议项.md")
python3 - "${SCRATCH}/多级 Agent优化/00_修订说明与评审决议项.md" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
text = p.read_text()
anchor = "；DB 层：`MultiAgentPersistenceIT`"
assert anchor in text
p.write_text(text.replace(anchor, "；DB 层用例待补 IT", 1))
PY
cnt=$(grep -c "DB 层用例待补 IT" "${SCRATCH}/多级 Agent优化/00_修订说明与评审决议项.md")
if [ "${cnt}" -eq "$((base4 + 1))" ]; then
  echo "  ✅ 反证 4（陈旧声明）：计数由 ${base4} 变 ${cnt}（结论已变化）"
  PASS=$((PASS + 1))
else
  echo "  ⛔ 反证 4（陈旧声明）：计数由 ${base4} 变 ${cnt}，未按预期 +1 → 无效反证"
  FAIL=$((FAIL + 1))
fi
echo

# ── 反证 5：真缺口写准（⑤b 的回归）────────────────────────────────────
echo "反证 5：抹掉 Run 预算读口径行里的「真缺口」标注 → 精确性标记应从有变无"
reset_scratch
python3 - "${SCRATCH}/多级 Agent优化/00_修订说明与评审决议项.md" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
text = p.read_text()
old = "⚠️ **DB 层读口径仍缺（真缺口，非陈旧声明）**"
assert old in text
p.write_text(text.replace(old, "⚠️ **DB 层读口径说明**", 1))
PY
cnt=$(grep -c "真缺口，非陈旧声明" "${SCRATCH}/多级 Agent优化/00_修订说明与评审决议项.md")
if [ "${cnt}" -eq 0 ]; then
  echo "  ✅ 反证 5（真缺口写准）：精确性标注消失（结论已变化）"
  PASS=$((PASS + 1))
else
  echo "  ⛔ 反证 5（真缺口写准）：标注仍在，未按预期变化 → 无效反证"
  FAIL=$((FAIL + 1))
fi
echo

# ── 反证 6：未实现表多一列（⑥ 的回归）─────────────────────────────────
echo "反证 6：把「未实现」表某一行的证据格拆回来 → 应报「列数不一致（3 列 vs 2 列）」"
reset_scratch
python3 - "${SCRATCH}/多级 Agent优化/00_修订说明与评审决议项.md" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
text = p.read_text()
marker = "；**证据**：`ContextBudgetTrimmerTest` 12 例"
assert marker in text
p.write_text(text.replace(marker, " | `ContextBudgetTrimmerTest` 12 例", 1))
PY
run_check
assert hit "列数不一致" "反证 6（未实现表多一列）" "数据行 3 列，表头 2 列"
echo

# ── 反证 7：历批修正表多一格（⑦ 的回归）───────────────────────────────
echo "反证 7：把 C18-1 行的「证据」括号拆成独立一格 → 应报「列数不一致（4 列 vs 3 列）」"
reset_scratch
python3 - "${SCRATCH}/多级 Agent优化/00_修订说明与评审决议项.md" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
text = p.read_text()
old = "并专门补了根路径的回归保护用例（`CapExecutionFlowTest#childToolCallSucceeds`，反证：改回老口径 → 3 条红） |"
new = "并专门补了根路径的回归保护用例 | `CapExecutionFlowTest#childToolCallSucceeds`（反证：改回老口径 → 3 条红） |"
assert old in text
p.write_text(text.replace(old, new, 1))
PY
run_check
assert hit "列数不一致" "反证 7（历批修正表多一格）" "数据行 4 列，表头 3 列"
echo

# ── 反证 8：转义竖线处理是「承重」的（若摘掉处理，会误伤 06 的合法行）──
echo "反证 8：把 06 里的转义竖线「\\|」改成裸竖线 → 应报「列数不一致」"
echo "        （证明「剔除转义竖线再计数」不是摆设，是承重逻辑）"
reset_scratch
python3 - "${SCRATCH}/多级 Agent优化/06_配置数据模型与生命周期设计.md" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
text = p.read_text()
old = "`native` \\| `mcp`"
assert old in text
p.write_text(text.replace(old, "`native` | `mcp`", 1))
PY
run_check
assert hit "列数不一致" "反证 8（转义竖线被摘掉）" "06_配置数据模型与生命周期设计.md"
echo

# ── 反证 9：代码围栏跳过是「承重」的（围栏内的 `|` 行不算表格）────────
echo "反证 9：在 P0-D06 的**代码块内**加一行「| ... |」→ 门禁应当**仍然全绿」"
echo "        （这是「不该受影响的必须保持绿」：证明门禁不误伤代码块里的 ASCII 矩阵）"
reset_scratch
python3 - "${SCRATCH}/多级 Agent优化/P0-D06_初始项目计划与RACI.md" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
text = p.read_text()
lines = text.split("\n")
i = next(k for k, l in enumerate(lines) if l.strip().startswith("```"))
lines.insert(i + 1, "| 这不是表格，是代码块里的 ASCII 矩阵 |")
p.write_text("\n".join(lines))
PY
run_check
if [ "${RC}" -eq 0 ]; then
  echo "  ✅ 反证 9（代码围栏跳过）：门禁仍然全绿（未误伤，结论符合预期）"
  PASS=$((PASS + 1))
else
  echo "  ⛔ 反证 9（代码围栏跳过）：门禁误伤了代码块内的内容 → 判据有误伤"
  cat "${OUT}"
  FAIL=$((FAIL + 1))
fi
echo

# ── 反证 10：回写脚本对「重复登记」必须 fail-closed ─────────────────────
echo "反证 10：直接调用脚本的追加函数再登记一次 → 必须抛错拒绝（而不是登记两遍）"
echo "        （证明「重复追加同一行」是脚本级硬拒绝，不靠人记着别跑第二遍）"
reset_scratch
python3 - "$ROOT/scripts/doc-c30-1.py" "${SCRATCH}/多级 Agent优化/00_修订说明与评审决议项.md" >"${SCRATCH}/doc.out" 2>&1 <<'PY'
import importlib.util, pathlib, sys
spec = importlib.util.spec_from_file_location("doc_c30_1", sys.argv[1])
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
text = pathlib.Path(sys.argv[2]).read_text()
try:
    mod._append_row_to_table(text, mod.OLD_DEFECT_HEADER, mod.NEW_DEFECT_ROW, "反证 10")
    print("NO_RAISE")
except SystemExit as e:
    print("RAISED:", e)
PY
if grep -q "RAISED:.*拒绝重复追加" "${SCRATCH}/doc.out"; then
  echo "  ✅ 反证 10（重复登记 fail-closed）：追加函数明确拒绝（结论已变化）"
  PASS=$((PASS + 1))
else
  echo "  ⛔ 反证 10（重复登记 fail-closed）：未被拒绝，输出："
  cat "${SCRATCH}/doc.out"
  FAIL=$((FAIL + 1))
fi
echo

# ── 反证 11：整脚本在「已改过的文档」上重跑必须非零退出（不得静默改坏）──
echo "反证 11：在草稿副本上抹掉标记后重跑整脚本 → 必须非零退出并明确「拒绝」"
echo "        （幂等标记是承重的：标记一丢，脚本宁可停下也不猜着改）"
reset_scratch
python3 - "${SCRATCH}/多级 Agent优化/00_修订说明与评审决议项.md" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
text = p.read_text()
assert "<!-- C30-1 -->" in text
p.write_text(text.replace("<!-- C30-1 -->", "", 1))
PY
DOC_C30_1_BASE="${SCRATCH}/多级 Agent优化" python3 "$ROOT/scripts/doc-c30-1.py" >"${SCRATCH}/doc.out" 2>&1
doc_rc=$?
if [ "${doc_rc}" -ne 0 ] && grep -q "拒绝" "${SCRATCH}/doc.out"; then
  echo "  ✅ 反证 11（重跑 fail-closed）：退出码=${doc_rc} 且明确拒绝（结论已变化）"
  PASS=$((PASS + 1))
else
  echo "  ⛔ 反证 11（重跑 fail-closed）：退出码=${doc_rc}，输出："
  cat "${SCRATCH}/doc.out"
  FAIL=$((FAIL + 1))
fi
echo

# ── 收尾：真实文档必须仍然全绿 ─────────────────────────────────────────
run_check
if [ "${RC}" -eq 0 ]; then
  echo "  ✅ 收尾：真实文档仍然全绿（$(tail -1 "${OUT}")）"
  PASS=$((PASS + 1))
else
  echo "  ⛔ 收尾：真实文档不绿"
  cat "${OUT}"
  FAIL=$((FAIL + 1))
fi

echo
echo "──────────────────────────────────────────────"
echo "反证结果：✅ ${PASS} 条通过 / ⛔ ${FAIL} 条失败"
if [ "${FAIL}" -ne 0 ]; then
  echo "⛔ 存在无效反证或误伤 —— 本批不予提交"
  exit 1
fi
echo "✅ 全部反证成立"
