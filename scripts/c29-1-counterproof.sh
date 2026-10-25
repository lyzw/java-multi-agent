#!/usr/bin/env bash
# 第二十九批 C29-1 反证脚本：把管理台的「编排声明」与「契约登记」两处逐个摘掉，
# 确认对应用例变红。
#
# ⚠️ 本脚本要证伪的是一件事：
#   **多级 Agent 的编排声明与输出契约必须能从管理台真正提交上去**。
#   此前前端**完全没有**这两样东西：`PublishAgentSpec` 里没有 `orchestration`、
#   `SubmitPublishRequest` 里没有 `resultContracts`，也没有任何表单 ——
#   后端治理通道明明已经支持（`AgentRequest.orchestration` / `ResultContractRequest`），
#   管理台却**够不着**。结果是「多级 Agent 只能靠手写 SQL 配」，
#   也就是「治理通道存在，但没人走它」。
#
# ⚠️ 每处反证只动一个地方，然后跑相关测试：
#   被摘掉的那条能力，对应用例必须红；而**不该受影响**的用例必须保持绿。
#
# ⚠️ **判定标准**：每处反证都必须让用例**真的变红，且恰好红在预期的那几条**。
#   结论不变（仍全绿）= 无效反证；红了但**超出预期条数**同样判无效。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/admin-console
export PATH="$PATH"

FORM=src/pages/orchestration/orchestrationForm.ts
PAGE=src/pages/catalog/ResultContractPage.tsx
TYPES=src/api/types.ts

BAK=/tmp/c29_1_counterproof
mkdir -p "$BAK"
cp "$FORM" "$BAK/form.bak"
cp "$PAGE" "$BAK/page.bak"
cp "$TYPES" "$BAK/types.bak"

restore() {
  cp "$BAK/form.bak" "$FORM"
  cp "$BAK/page.bak" "$PAGE"
  cp "$BAK/types.bak" "$TYPES"
}

FAILED=0

# ⚠️ **只数 `×` 行**：vitest 对同一条失败用例会打**两行**
#    （`× <用例名>` 与 ` FAIL  <文件> > <用例名>`）。
#    两个模式都匹配会让计数**翻倍** —— 实测踩过（每条反证都「越界」）。
count_red() {
  grep -cE "^ *× " "$1"
}

report() {
  local label="$1" expect_red="$2" log="$3"
  echo "================ ${label} ================"
  if grep -q "Failed to resolve import\|SyntaxError\|Transform failed" "$log"; then
    echo "  ⛔ 反证无效：源码无法编译（编译不过不是用例红，它证明不了任何结论）"
    FAILED=1
    return
  fi
  grep -E "^ *× " "$log" | sed 's/^/  /' | head -12
  local red
  red=$(count_red "$log")
  if [ "$red" -eq "$expect_red" ]; then
    echo "  ✅ 结论已变化：恰好 ${red} 条红（预期 ${expect_red} 条，反证精准）"
  elif [ "$red" -gt "$expect_red" ]; then
    echo "  ⛔ 反证越界：红了 ${red} 条，预期 ${expect_red} 条 —— 误伤了不该受影响的用例"
    FAILED=1
  else
    echo "  ⛔ 反证无效：红了 ${red} 条，预期 ${expect_red} 条（结论未按预期变化）"
    FAILED=1
  fi
  echo
}

# ⚠️ **类型层反证用 tsc 做判据**：vitest 只做转译、**不做类型检查**，
#    所以「类型里删掉一个字段」在 vitest 里是**看不见**的
#    —— 实测踩过（反证 F 红了 0 条）。
#    类型层的唯一权威判据是 `tsc --noEmit`。
report_typecheck() {
  local label="$1" expect_err="$2"
  local log=/tmp/c29_1_tsc.log
  npx tsc --noEmit > "$log" 2>&1
  echo "================ ${label} ================"
  grep -E "error TS" "$log" | sed 's/^/  /' | head -6
  local errs
  errs=$(grep -cE "error TS" "$log")
  if [ "$errs" -eq "$expect_err" ]; then
    echo "  ✅ 结论已变化：恰好 ${errs} 条类型错误（预期 ${expect_err} 条，反证精准）"
  elif [ "$errs" -gt "$expect_err" ]; then
    echo "  ⛔ 反证越界：${errs} 条类型错误，预期 ${expect_err} 条"
    FAILED=1
  else
    echo "  ⛔ 反证无效：${errs} 条类型错误，预期 ${expect_err} 条（结论未按预期变化）"
    FAILED=1
  fi
  echo
}

run_front() {
  local label="$1" expect_red="$2"
  local log=/tmp/c29_1_case.log
  npx vitest run tests/orchestrationForm.test.tsx tests/resultContractPage.test.tsx \
      > "$log" 2>&1
  report "$label" "$expect_red" "$log"
}

restore

# ── 反证 A：编排声明的校验被摘掉（非法声明不再被拒）──────────────────
#    预期：LEAF 带子引用那条 + ROUTER 缺引用那条 + 子引用缺版本那条 红（3 条）。
python3 - "$FORM" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """  if (values.mode === 'LEAF' && children.length > 0) {
    return {
      ok: false,
      error: 'LEAF 模式不得声明子 Agent 引用（声明了即为配置错误）',
    }
  }"""
new = """  // 反证 A：LEAF 带子引用的校验被摘掉
  if (false) {
    return { ok: false, error: 'unused' }
  }"""
assert old in s, "A: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_front "反证 A：编排声明的校验被摘掉（非法声明不再被拒）" 1
restore

# ── 反证 B：固定版本校验被摘掉（latest 漂移别名不再被拒）───────────────
#    预期：latest 那条 + 裸 id 那条 红（2 条）。
python3 - "$FORM" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """  const at = raw.indexOf(REF_SEPARATOR)
  if (at <= 0 || at === raw.length - 1) {
    return {
      ok: false,
      error: `${fieldName} 必须是「id@version」形式（当前：${raw}）`,
    }
  }"""
new = """  // 反证 B：固定版本校验被摘掉
  const at = raw.indexOf(REF_SEPARATOR)
  if (at <= 0 || at === raw.length - 1) {
    return { ok: true }
  }"""
assert old in s, "B: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_front "反证 B：固定版本校验被摘掉（latest 漂移别名不再被拒）" 2
restore

# ── 反证 C：序列化不再带数值字段（默认值口径由前端说了算）──────────────
#    预期：「只写 mode」那条红（1 条）。
#    ⚠️ 这条守住的是「前端不得预填默认值」：默认值口径属**评审前置决策 2**
#    （最大深度与预算默认值，尚未闭环），前端一旦预填就成了第二个默认值来源。
python3 - "$FORM" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """  if (values.maxDelegationDepth !== null && values.maxDelegationDepth !== undefined) {
    root.maxDelegationDepth = values.maxDelegationDepth
  }"""
new = """  // 反证 C：前端擅自预填默认值
  root.maxDelegationDepth = values.maxDelegationDepth ?? 3"""
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_front "反证 C：序列化不再带数值字段（默认值口径由前端说了算）" 1
restore

# ── 反证 D：契约登记不再把契约放进 resultContracts ────────────────────
#    预期：「提交时把契约放进 resultContracts」那条红（1 条）。
#    ⚠️ 这正是「治理通道存在但没人走它」的形态：
#    契约被提交到了 agents 槽位里 —— 后端会把它当成 **Agent 草稿**处理，
#    结果是**契约根本没登记**，而页面却显示「提交成功」。
python3 - "$PAGE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        resultContracts: [spec],"""
new = """        agents: [{ agentId: spec.contractId, name: spec.contractId }],"""
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_front "反证 D：契约登记不再把契约放进 resultContracts" 1
restore

# ── 反证 E：契约定义的 JSON 校验被摘掉 ────────────────────────────────
#    预期：「不是合法 JSON → 不得提交」那条红（1 条）。
python3 - "$PAGE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """    if (!isValidJson(schemaJson)) {
      message.error('契约定义不是合法 JSON')
      return
    }"""
new = """    // 反证 E：契约定义的 JSON 校验被摘掉"""
assert old in s, "E: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_front "反证 E：契约定义的 JSON 校验被摘掉" 1
restore

# ── 反证 F：类型层不再声明 orchestration / resultContracts ────────────
#    预期：**2 条**类型错误（源码 1 条 + 测试 1 条）。
#    ⚠️ 这是**类型层**的反证：它证明「前端类型里有没有这两个字段」
#    确实被钉住 —— 否则字段被删了，TS 才会红，运行时却悄悄少传。
#    ⚠️ 判据是 `tsc --noEmit` 而**不是** vitest：vitest 只做转译、**不做类型检查**，
#    所以「类型里删掉一个字段」在 vitest 里是**看不见**的（实测踩过：红了 0 条）。
#    ⚠️ 测试那一条是**正确的连带**：测试也引用了该字段，删掉它必然一起报错。
python3 - "$TYPES" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """  /** 输出契约草稿（多级 Agent v1.1 · 第二十九批 · C29-1 · A6） */
  resultContracts?: PublishResultContractSpec[]"""
new = """  // 反证 F：类型层不再声明 resultContracts"""
assert old in s, "F: 未命中"
p.write_text(s.replace(old, new, 1))
PY
report_typecheck "反证 F：类型层不再声明 orchestration / resultContracts" 2
restore

echo "================ 恢复后复跑全绿 ================"
restore
run_front "恢复后复跑全绿" 0
report_typecheck "恢复后 typecheck 应干净" 0

if [ "$FAILED" -ne 0 ]; then
  echo "⛔ 存在无效反证（结论未变 / 编译失败 / 范围越界）"
  exit 1
fi
echo "✅ 全部反证有效"
