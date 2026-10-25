#!/usr/bin/env bash
# 第三十三批 C33-1 反证脚本：钉住评审前置决策 2 的语义（**取消平台默认值** ——
# 非 LEAF 必须显式声明最大深度 / 子调用数 / 超时，缺失即拒绝发布）。
#
# ⚠️ 本脚本要证伪的是一件事：
#   **平台不再提供委派限制的默认值**，非 LEAF 缺一个上限都必须**被拒**。
#   此前 `AgentOrchestration` 有三个常量
#   （DEFAULT_MAX_DELEGATION_DEPTH=2 / DEFAULT_MAX_CHILD_INVOCATIONS=8 /
#   DEFAULT_TIMEOUT_MS=60000），解析时缺字段就回落到它们。
#   ⚠️ 那三个数字**从未经过容量与业务风险评估** —— 只是示例值；
#   以「默认值」的形式存在就会被配置方**默默继承**，
#   然后在没人评审的情况下成为生产口径。
#
# ⚠️ 每处反证只动一个地方：
#   · **反证 A（后端行为）**：把 `AgentOrchestration` 构造器里的
#     「非 LEAF 必须显式声明」校验摘掉 → `AgentOrchestrationParserTest`
#     必须**恰好红 2 条**（正是那两条钉住本决策的新用例），
#     且**agent-application 全模块其余用例保持绿**
#     （红了但超出预期条数同样判无效）。
#   · **反证 B（契约）**：把 `agent.schema.json` 的 `allOf` 删掉 →
#     同一份「非 LEAF 缺限制」的编排，其 schema 校验结论必须
#     由「**无效**」变成「**有效**」（证明 `allOf` 真的在咬，
#     而不是一段没人看的装饰）。
#   · **反证 C（前端行为）**：把 `validateOrchestration` 里的
#     「非 LEAF 必须显式声明」判定摘掉 → `orchestrationForm.test.tsx`
#     必须**恰好红 2 条**。
#
# ⚠️ **判定**：结论不变（仍全绿 / 结论没变）= 无效反证。
#   编译失败同样判无效（「编译不过」不是「用例红」，它证明不了任何结论）。
set -u

ROOT=/Users/zhouwei/code-workspace/ai-tech/java-multi-agent
PLATFORM="${ROOT}/platform"
CONSOLE="${ROOT}/admin-console"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="${JAVA_HOME}/bin:${PATH}"

ORCH=agent-core/src/main/java/cn/studykid/multiagent/core/orchestration/AgentOrchestration.java
SCHEMA="${ROOT}/docs/多级 Agent优化/agent-capability-contracts-v1/schemas/agent.schema.json"
FORM="${CONSOLE}/src/pages/orchestration/orchestrationForm.ts"

BAK=/tmp/c33_1_counterproof
mkdir -p "${BAK}"
cp "${PLATFORM}/${ORCH}" "${BAK}/orch.bak"
cp "${SCHEMA}" "${BAK}/schema.bak"
cp "${FORM}" "${BAK}/form.bak"

FAILED=0

restore() {
  cp "${BAK}/orch.bak" "${PLATFORM}/${ORCH}"
  cp "${BAK}/schema.bak" "${SCHEMA}"
  cp "${BAK}/form.bak" "${FORM}"
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

# ── schema 契约检查：非 LEAF 缺三个限制 ⇒ 必须判为「无效」──────────────
# ⚠️ 优先用 `jsonschema` 库（权威校验器）；没装就退回**结构检查**
#    （只认 `allOf` + `if/then` 的存在与必填集合）—— 两条路都必须能咬。
schema_verdict() {
  python3 - "${SCHEMA}" <<'PY'
import json, pathlib, sys
schema_path = pathlib.Path(sys.argv[1])
base = schema_path.parent
agent = json.loads(schema_path.read_text(encoding='utf-8'))
common = json.loads((base / 'common.schema.json').read_text(encoding='utf-8'))
orch = agent['properties']['orchestration']
sample = {"mode": "SEQUENTIAL", "childAgentRefs": []}

try:
    import warnings
    # ⚠️ 必须在 import jsonschema **之前**静音 ——
    #    RefResolver 的弃用告警是在**导入时**发出的，事后 filter 已经来不及。
    warnings.filterwarnings('ignore')
    from jsonschema import Draft202012Validator, RefResolver
    resolver = RefResolver(base_uri=base.as_uri() + '/', referrer=orch,
                           store={'common.schema.json': common,
                                  'agent.schema.json': agent})
    errs = list(Draft202012Validator(orch, resolver=resolver).iter_errors(sample))
    print('INVALID' if errs else 'VALID')
    sys.exit(0)
except ImportError:
    pass

# 结构检查：ROUTER / SEQUENTIAL 两个分支都必须把三个限制列入 `then.required`
required = {'maxDelegationDepth', 'maxChildInvocations', 'timeoutMs'}
ok = True
for mode in ('ROUTER', 'SEQUENTIAL'):
    hit = False
    for branch in orch.get('allOf', []):
        cond = branch.get('if', {})
        then = branch.get('then', {})
        if cond.get('properties', {}).get('mode', {}).get('const') == mode \
                and required.issubset(set(then.get('required', []))):
            hit = True
    if not hit:
        ok = False
print('INVALID' if ok else 'VALID')
PY
}

echo "C33-1 反证：决策 2 的语义（取消平台默认值 · 非 LEAF 必须显式声明）"
echo

# ── 基线 ────────────────────────────────────────────────────────────────
echo "基线：未注入任何缺陷 → 后端全绿 / 前端全绿 / schema 契约咬住"
(cd "${PLATFORM}" && mvn -o -B -pl agent-application -am test) > "${BAK}/baseline.log" 2>&1
report "基线（后端 agent-application 全模块）" 0 "${BAK}/baseline.log"
(cd "${CONSOLE}" && npx vitest run tests/orchestrationForm.test.tsx) > "${BAK}/baseline_fe.log" 2>&1
FE_RED=$(grep -cE "^ *× " "${BAK}/baseline_fe.log")
if [ "${FE_RED}" -eq 0 ]; then
  echo "  ✅ 基线（前端 orchestrationForm）: 全绿（符合预期）"
else
  echo "  ⛔ 基线（前端 orchestrationForm）: 红了 ${FE_RED} 条 → 先清干净再做反证"
  FAILED=$((FAILED + 1))
fi
BASE_VERDICT=$(schema_verdict)
if [ "${BASE_VERDICT}" = "INVALID" ]; then
  echo "  ✅ 基线（schema 契约）: 非 LEAF 缺限制被判为 INVALID（符合预期）"
else
  echo "  ⛔ 基线（schema 契约）: 判为 ${BASE_VERDICT} → 契约没有咬住，先修再做反证"
  FAILED=$((FAILED + 1))
fi
echo

# ── 反证 A：摘掉后端构造器的「非 LEAF 必须显式声明」校验 ────────────────
echo "反证 A：把 AgentOrchestration 构造器里的「非 LEAF 必须显式声明」校验摘掉"
echo "        → AgentOrchestrationParserTest 必须恰好红 2 条（那两条新用例）"
python3 - "${PLATFORM}/${ORCH}" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
old = "        if (mode != OrchestrationMode.LEAF\n                && (maxDelegationDepth == null"
new = "        if (false\n                && (maxDelegationDepth == null"
assert old in s, "锚点未命中（构造器结构变了？）"
p.write_text(s.replace(old, new, 1), encoding='utf-8')
PY
(cd "${PLATFORM}" && mvn -o -B -pl agent-application -am test) > "${BAK}/inject_a.log" 2>&1
report "反证 A（注入后）" 2 "${BAK}/inject_a.log"
for name in nonLeafWithoutLimitsIsRejected eachMissingLimitIsRejectedIndividually; do
  if grep -q "${name}" "${BAK}/inject_a.log"; then
    echo "  ✅ 反证 A：红的正是钉住本决策的那条用例（${name}）"
  else
    echo "  ⛔ 反证 A：${name} 没红 → 注入没打到点上"
    FAILED=$((FAILED + 1))
  fi
done
restore
(cd "${PLATFORM}" && mvn -o -B -pl agent-application -am test) > "${BAK}/restored_a.log" 2>&1
report "反证 A 收尾（恢复后）" 0 "${BAK}/restored_a.log"
echo

# ── 反证 B：删掉 schema 的 allOf ───────────────────────────────────────
echo "反证 B：把 agent.schema.json 的 orchestration.allOf 删掉"
echo "        → 同一份「非 LEAF 缺限制」的编排，校验结论必须由 INVALID 变 VALID"
python3 - "${SCHEMA}" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
d = json.loads(p.read_text(encoding='utf-8'))
del d['properties']['orchestration']['allOf']
p.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding='utf-8')
PY
INJ_VERDICT=$(schema_verdict)
restore
if [ "${INJ_VERDICT}" != "${BASE_VERDICT}" ]; then
  echo "  ✅ 反证 B（契约真的在咬）：结论由 ${BASE_VERDICT} 变 ${INJ_VERDICT}（结论已变化）"
else
  echo "  ⛔ 反证 B（契约真的在咬）：结论未变（${BASE_VERDICT} → ${INJ_VERDICT}）→ allOf 没在咬"
  FAILED=$((FAILED + 1))
fi
AFTER_VERDICT=$(schema_verdict)
if [ "${AFTER_VERDICT}" = "${BASE_VERDICT}" ]; then
  echo "  ✅ 反证 B 收尾（恢复后）: 结论回到 ${AFTER_VERDICT}"
else
  echo "  ⛔ 反证 B 收尾（恢复后）: 结论为 ${AFTER_VERDICT}，期望 ${BASE_VERDICT}"
  FAILED=$((FAILED + 1))
fi
echo

# ── 反证 C：摘掉前端的「非 LEAF 必须显式声明」判定 ─────────────────────
echo "反证 C：把 validateOrchestration 里的「非 LEAF 必须显式声明」判定摘掉"
echo "        → orchestrationForm.test.tsx 必须恰好红 2 条"
python3 - "${FORM}" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
old = "  if (values.mode !== 'LEAF') {\n    for (const [field] of LIMIT_FIELDS) {"
new = "  if (false) {\n    for (const [field] of LIMIT_FIELDS) {"
assert old in s, "锚点未命中（表单逻辑结构变了？）"
p.write_text(s.replace(old, new, 1), encoding='utf-8')
PY
(cd "${CONSOLE}" && npx vitest run tests/orchestrationForm.test.tsx) > "${BAK}/inject_c.log" 2>&1
if grep -q "Failed to resolve import\|SyntaxError\|Transform failed" "${BAK}/inject_c.log"; then
  echo "  ⛔ 反证 C 无效：源码无法编译（编译不过不是用例红，它证明不了任何结论）"
  FAILED=$((FAILED + 1))
else
  FE_RED=$(grep -cE "^ *× " "${BAK}/inject_c.log")
  grep -E "^ *× " "${BAK}/inject_c.log" | sed 's/^/  /' | head -6
  if [ "${FE_RED}" -eq 2 ]; then
    echo "  ✅ 反证 C：恰好红 ${FE_RED} 条（符合预期）"
  else
    echo "  ⛔ 反证 C：红了 ${FE_RED} 条，期望恰好 2 条 → 无效反证"
    FAILED=$((FAILED + 1))
  fi
fi
restore
(cd "${CONSOLE}" && npx vitest run tests/orchestrationForm.test.tsx) > "${BAK}/restored_c.log" 2>&1
FE_RED=$(grep -cE "^ *× " "${BAK}/restored_c.log")
if [ "${FE_RED}" -eq 0 ]; then
  echo "  ✅ 反证 C 收尾（恢复后）: 全绿（证明注入是唯一变量）"
else
  echo "  ⛔ 反证 C 收尾（恢复后）: 仍红 ${FE_RED} 条"
  FAILED=$((FAILED + 1))
fi
echo

echo "──────────────────────────────────────────────"
if [ "${FAILED}" -ne 0 ]; then
  echo "⛔ 反证失败 ${FAILED} 项 —— 本批不予提交"
  exit 1
fi
echo "✅ 全部反证成立"
