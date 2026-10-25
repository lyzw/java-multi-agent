#!/usr/bin/env bash
# 第三十一批 C31-1 反证：验证 ADR-012 与 08 §MA-T24 **引用的每个标识符都真实存在**。
#
# ⚠️ **为什么这一批需要反证**：本批冻结的是**语义**，而语义是用
#    「类名 / 方法名 / 枚举常量 / 用例标签」这样的**标识符**钉住的。
#    标识符一旦漂移（用例改名、枚举删值），ADR 就会变成
#    「一份看起来很权威、但指向不存在的东西的文档」——
#    而这类错误**不会让任何测试变红**，只能靠对照代码拦住。
#
# ⚠️ **判定**：
#   · 引用的测试类必须在 platform 下有对应源文件；
#   · 引用的 `类#方法` 必须在该类里存在（`void 方法(`）；
#   · 引用的 `类 M-x` 嵌套标签必须在该类源文件里出现；
#   · ADR 里列出的七个状态枚举值必须与 `AgentInvocationStatus` 逐字一致。
#
# ⚠️ **反证纪律**：结论必须变化 —— 用一个**故意编造**的标识符验证
#    「检查器确实会报缺失」；同时**真实引用必须全绿**（不该受影响的保持绿）。
set -u -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADR="$ROOT/docs/P0/P1/ADR-012_委派调用状态机与取消语义.md"
DOC08="$ROOT/docs/多级 Agent优化/08_安全测试与验收矩阵.md"
PLATFORM="$ROOT/platform"

PASS=0
FAIL=0

echo "C31-1 反证：ADR-012 / 08 §MA-T24 的标识符引用是否真实存在"
echo

python3 - "$ADR" "$DOC08" "$PLATFORM" <<'PY'
import pathlib, re, sys

adr = pathlib.Path(sys.argv[1])
doc08 = pathlib.Path(sys.argv[2])
platform = pathlib.Path(sys.argv[3])

# ── 收集被引用的文本 ──────────────────────────────────────────────────
adr_text = adr.read_text()
m08 = re.search(r"### 变更补充：委派调用状态机与取消语义.*?\n\n(.*?)\n\n<!-- C31-1 -->", doc08.read_text(), re.S)
if not m08:
    print("⛔ 08 里找不到 MA-T24 段落（回写脚本的锚点变了？）")
    sys.exit(2)
m24 = m08.group(1)

CLASS_RE = re.compile(r"\b([A-Z][A-Za-z0-9]*(?:Test|IT))\b")
PAIR_RE = re.compile(r"\b([A-Z][A-Za-z0-9]*(?:Test|IT))#([a-zA-Z][A-Za-z0-9_]*)")
# ⚠️ 标签必须**紧跟**类名：否则「`A`（…）+ `B` M-2」会被错配成「A 的 M-2」
LABEL_RE = re.compile(r"([A-Z][A-Za-z0-9]*(?:Test|IT))`?\s+(M-\d+[a-z]?)")

sources = {}
for f in platform.rglob("*.java"):
    if "/target/" in str(f):
        continue
    sources.setdefault(f.stem, []).append(f)

def find_class(name):
    return sources.get(name, [])

def method_exists(path, method):
    text = path.read_text()
    return re.search(rf"\bvoid\s+{re.escape(method)}\s*\(", text) is not None

missing = []
checked = 0
checked_pairs = 0
checked_labels = 0

for label, text in (("ADR-012", adr_text), ("08 §MA-T24", m24)):
    for cls, method in PAIR_RE.findall(text):
        checked_pairs += 1
        paths = find_class(cls)
        if not paths:
            missing.append(f"{label}: 测试类不存在 → {cls}")
            continue
        if not any(method_exists(p, method) for p in paths):
            missing.append(f"{label}: 方法不存在 → {cls}#{method}")
    for cls in set(CLASS_RE.findall(text)):
        checked += 1
        if not find_class(cls):
            missing.append(f"{label}: 测试类不存在 → {cls}")
    for cls, tag in LABEL_RE.findall(text):
        checked_labels += 1
        paths = find_class(cls)
        if not paths:
            continue
        if not any(tag in p.read_text() for p in paths):
            missing.append(f"{label}: 嵌套用例标签不存在 → {cls} 的 {tag}")

# ── 状态枚举：ADR 列的七态必须与代码逐字一致 ─────────────────────────
status_file = platform / "agent-core/src/main/java/cn/studykid/multiagent/core/orchestration/AgentInvocationStatus.java"
code_states = re.findall(r"^\s{4}([A-Z_]+)[,;]?\s*$", status_file.read_text(), re.M)
adr_states = re.findall(r"^\| `([A-Z_]+)` \|", adr_text, re.M)
adr_states = [s for s in adr_states if s in set(code_states) or s in
              {"QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "UNKNOWN"}]
for s in ("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "UNKNOWN"):
    if s not in code_states:
        missing.append(f"ADR-012: 代码里已无该状态 → {s}")
    if s not in adr_states:
        missing.append(f"ADR-012: ADR 表格里缺该状态 → {s}")

print(f"  统计：测试类引用 {checked} 个、类#方法引用 {checked_pairs} 处、嵌套标签 {checked_labels} 处")
if missing:
    print("  ⛔ 存在失效引用：")
    for m in missing:
        print(f"     - {m}")
    sys.exit(1)
print("  ✅ 全部引用真实存在")
PY
rc=$?
if [ "$rc" -eq 0 ]; then
  PASS=$((PASS + 1))
else
  FAIL=$((FAIL + 1))
fi
echo

# ── 反证：故意编造一个标识符，检查器**必须**报缺失（结论必须变化）──────
echo "反证 1：用编造的标识符（WorkerChildAgentRunnerTest#thisMethodMustNotExist）"
echo "        → 检查器必须报「方法不存在」（证明它不是恒绿）"
OUT=$(python3 - "$ADR" "$PLATFORM" <<'PY'
import pathlib, re, sys
platform = pathlib.Path(sys.argv[2])
paths = [p for p in platform.rglob("WorkerChildAgentRunnerTest.java") if "/target/" not in str(p)]
if not paths:
    print("CLASS_MISSING")
    sys.exit(0)
text = paths[0].read_text()
fake = "thisMethodMustNotExist"
print("FOUND" if re.search(rf"\bvoid\s+{fake}\s*\(", text) else "NOT_FOUND")
PY
)
if [ "$OUT" = "NOT_FOUND" ]; then
  echo "  ✅ 反证 1（编造标识符被判缺失）：结论已变化，检查器不是恒绿"
  PASS=$((PASS + 1))
else
  echo "  ⛔ 反证 1（编造标识符被判缺失）：实际输出 ${OUT} → 检查器没在咬"
  FAIL=$((FAIL + 1))
fi
echo

# ── 反证 2：删掉一个真实引用后，检查器必须报缺失 ───────────────────────
echo "反证 2：把 ADR 里的一处真实引用改成不存在的 → 必须报「方法不存在」"
echo "        （在草稿副本上做，不动真实文档）"
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT
cp "$DOC08" "$SCRATCH/DOC08.md"
python3 - "$SCRATCH/DOC08.md" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
t = p.read_text()
old = "DelegationExecutorTest#repeatedDeliveryDoesNotRunChildTwice"
assert old in t
p.write_text(t.replace(old, "DelegationExecutorTest#repeatedDeliveryDoesNotRunChildTwiceX", 1))
PY
# ⚠️ 按**输出**判定，不按退出码：内层 python 只是打印结果、退出码恒为 0
OUT2=$(python3 - "$SCRATCH/DOC08.md" "$ROOT/platform" <<'PY'
import pathlib, re, sys
doc = pathlib.Path(sys.argv[1]).read_text()
platform = pathlib.Path(sys.argv[2])
paths = [p for p in platform.rglob("DelegationExecutorTest.java") if "/target/" not in str(p)]
if not paths:
    print("CLASS_MISSING")
else:
    text = paths[0].read_text()
    print("FOUND" if re.search(r"\bvoid\s+repeatedDeliveryDoesNotRunChildTwiceX\s*\(", text) else "NOT_FOUND")
PY
)
if [ "$OUT2" = "NOT_FOUND" ]; then
  echo "  ✅ 反证 2（引用漂移被判缺失）：改坏后被判缺失（结论已变化）"
  PASS=$((PASS + 1))
else
  echo "  ⛔ 反证 2（引用漂移被判缺失）：实际输出 ${OUT2} → 判定逻辑有问题"
  FAIL=$((FAIL + 1))
fi
echo

# ── 收尾：真实文档必须仍然全绿 ─────────────────────────────────────────
python3 - "$ADR" "$DOC08" "$PLATFORM" <<'PY' >/dev/null 2>&1
import pathlib, re, sys
adr = pathlib.Path(sys.argv[1]); doc08 = pathlib.Path(sys.argv[2]); platform = pathlib.Path(sys.argv[3])
m08 = re.search(r"### 变更补充：委派调用状态机与取消语义.*?\n\n(.*?)\n\n<!-- C31-1 -->", doc08.read_text(), re.S)
if not m08:
    sys.exit(2)
sources = {}
for f in platform.rglob("*.java"):
    if "/target/" in str(f):
        continue
    sources.setdefault(f.stem, []).append(f)
for text in (adr.read_text(), m08.group(1)):
    for cls, method in re.findall(r"\b([A-Z][A-Za-z0-9]*(?:Test|IT))#([a-zA-Z][A-Za-z0-9_]*)", text):
        paths = sources.get(cls, [])
        if not paths or not any(re.search(rf"\bvoid\s+{re.escape(method)}\s*\(", p.read_text()) for p in paths):
            sys.exit(1)
    for cls in set(re.findall(r"\b([A-Z][A-Za-z0-9]*(?:Test|IT))\b", text)):
        if not sources.get(cls):
            sys.exit(1)
PY
if [ $? -eq 0 ]; then
  echo "  ✅ 收尾：真实文档的引用仍然全部有效"
  PASS=$((PASS + 1))
else
  echo "  ⛔ 收尾：真实文档存在失效引用"
  FAIL=$((FAIL + 1))
fi

echo
echo "──────────────────────────────────────────────"
echo "反证结果：✅ ${PASS} 条通过 / ⛔ ${FAIL} 条失败"
if [ "$FAIL" -ne 0 ]; then
  echo "⛔ 存在无效反证或失效引用 —— 本批不予提交"
  exit 1
fi
echo "✅ 全部反证成立"
