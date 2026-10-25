#!/usr/bin/env bash
# 第二十六批 C26-1 反证脚本：把「上下文按 token 预算裁剪」逐处摘掉，
# 确认对应用例变红。
#
# ⚠️ 本脚本要证伪的是一件事：
#   **裁剪必须既「真的裁」又「不改变语义」** ——
#   不裁 → 上下文顶爆窗口（报错长得像供应商故障）；
#   乱裁（留下孤立的工具结果、裁掉原始输入、不留痕）→ 模型基于残缺上下文决策，
#   最坏的一支是**重复调用已经调过的工具** → 重复副作用 = Sev-0。
#
# ⚠️ 每处反证只动一个地方，然后跑相关测试：
#   被摘掉的那条能力，对应用例必须红；而**不该受影响**的用例必须保持绿
#   （否则说明反证改动的范围比预想的大 —— 结论不纯）。
#
# ⚠️ **判定标准**：每处反证都必须让用例**真的变红**。
#   若改动后结论不变（仍然全绿），说明这条反证**无效** —— 它没咬到任何东西，
#   此时脚本会明确报「⛔ 反证无效」并以非零码退出。
#   编译失败同样判为无效 —— 「编译不过」不是「用例红」。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

TRIMMER=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/ContextBudgetTrimmer.java
ROOT=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RunExecutionLoop.java
CHILD=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/WorkerChildAgentRunner.java
BAK=/tmp/c26_1_counterproof
mkdir -p "$BAK"
cp "$TRIMMER" "$BAK/trimmer.bak"
cp "$ROOT" "$BAK/root.bak"
cp "$CHILD" "$BAK/child.bak"
restore() {
  cp "$BAK/trimmer.bak" "$TRIMMER"
  cp "$BAK/root.bak" "$ROOT"
  cp "$BAK/child.bak" "$CHILD"
}

FAILED=0

report() {
  local label="$1" expect="$2" log="$3"
  echo "================ ${label} ================"
  if grep -q "COMPILATION ERROR\|Failed to execute goal .*compile" "$log"; then
    echo "  ⛔ 反证无效：编译失败（编译不过不是用例红，它证明不了任何结论）"
    FAILED=1
    return
  fi
  grep -E "^\[ERROR\] +[A-Za-z]+(Test|IT)[.:]|Tests run: [0-9]+, Failures" "$log" \
    | sed 's/^/  /' | tail -14
  local red green
  red=$(grep -cE "<<< (FAILURE|ERROR)!" "$log")
  green=$(grep -cE "Tests run: [0-9]+, Failures: 0, Errors: 0" "$log")
  if [ "$expect" = "red" ]; then
    if [ "$red" -gt 0 ]; then
      echo "  ✅ 结论已变化：${red} 处变红（反证有效）"
    else
      echo "  ⛔ 反证无效：结论未变（用例仍全绿）"
      FAILED=1
    fi
  else
    if [ "$green" -gt 0 ]; then
      echo "  ✅ 结论未变：保持全绿（反证范围准确）"
    else
      echo "  ⛔ 反证越界：不该受影响的用例也变红了"
      FAILED=1
    fi
  fi
  echo
}

run_unit() {
  local label="$1" tests="$2" expect="$3"
  local log=/tmp/c26_1_case.log
  mvn -o -B -pl agent-worker -am -Dtest="$tests" \
      -Dsurefire.failIfNoSpecifiedTests=false test > "$log" 2>&1
  report "$label" "$expect" "$log"
}

restore

# ── 反证 A：根路径不裁剪（把裁剪结果换回原序列）──────────────────────
#    预期：根路径「上下文超预算 → 裁剪」那条用例红。
python3 - "$ROOT" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                        trimmed.messages());"""
new = """                        messages);"""
assert old in s, "A: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 A：根路径不裁剪（上下文仍会顶爆窗口）" \
    'RunExecutionLoopTest,ContextBudgetTrimmerTest,WorkerChildAgentRunnerTest' red
restore

# ── 反证 B：子路径不裁剪（父子两套语义）──────────────────────────────
#    预期：子路径那条用例红；根路径那条**仍绿**（证明两处独立）。
python3 - "$CHILD" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                        trimmed.messages()));"""
new = """                        messages));"""
assert old in s, "B: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 B：子路径不裁剪（父子两套语义）" \
    'RunExecutionLoopTest,ContextBudgetTrimmerTest,WorkerChildAgentRunnerTest' red
restore

# ── 反证 C：裁剪不留痕（模型会以为工具根本没调用过 → 重复副作用）──────
#    预期：留痕相关用例红。
python3 - "$TRIMMER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        String notice = droppedRounds == 0 ? null : notice(droppedRounds, droppedTokens, tools);"""
new = """        String notice = null;"""
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 C：裁剪不留痕（重复副作用的入口）" \
    'RunExecutionLoopTest,ContextBudgetTrimmerTest,WorkerChildAgentRunnerTest' red
restore

# ── 反证 D：按消息下标切（丢弃点可能落在「意图与结果」之间）──────────
#    预期：留下孤立 TOOL 消息那条用例红。
#    ⚠️ 这条反证必须落在**丢弃点**上：只要丢弃的永远是一个前缀，
#    就不可能出现「意图被丢、结果被留」的孤儿 —— 真正的保护是
#    「丢弃点必须落在轮边界」。
python3 - "$TRIMMER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        List<ModelMessage> kept = new ArrayList<>();
        for (int i = keepFrom; i < rounds.size(); i++) {
            kept.addAll(rounds.get(i));
        }"""
new = """        // 反证 D：按消息下标切（丢弃点可能落在「意图与结果」之间）
        List<ModelMessage> kept = new ArrayList<>();
        kept.addAll(messages.subList(Math.min(1 + keepFrom, messages.size()),
                messages.size()));"""
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 D：按消息逐条裁（会留下孤立的工具结果）" \
    'RunExecutionLoopTest,ContextBudgetTrimmerTest,WorkerChildAgentRunnerTest' red
restore

# ── 反证 E：裁剪把原始输入也裁掉（等于改了任务本身）────────────────────
#    预期：「原始输入永不被裁」那条用例红。
python3 - "$TRIMMER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        List<ModelMessage> out = new ArrayList<>(kept.size() + 1);
        out.add(head);
        out.addAll(kept);"""
new = """        List<ModelMessage> out = new ArrayList<>(kept.size());
        out.addAll(kept);"""
assert old in s, "E: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 E：原始输入也被裁掉（等于悄悄改了任务）" \
    'RunExecutionLoopTest,ContextBudgetTrimmerTest,WorkerChildAgentRunnerTest' red
restore

# ── 反证 F：token 估算偏乐观（英文按 4 字符/token）─────────────────────
#    预期：估算保守性那条用例红。
python3 - "$TRIMMER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """    private static final int CHARS_PER_TOKEN = 3;"""
new = """    private static final int CHARS_PER_TOKEN = 4;"""
assert old in s, "F: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 F：token 估算偏乐观（实际 token 会超出预算）" \
    'RunExecutionLoopTest,ContextBudgetTrimmerTest,WorkerChildAgentRunnerTest' red
restore

# ── 反证 G：裁剪就地改写调用方的累积序列（裁一次就永久丢历史）──────────
#    预期：「不改累积序列」那条用例红。
python3 - "$TRIMMER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        return new Trimmed(out, notice, droppedRounds, droppedTokens, List.copyOf(tools),
                used, used <= budget);"""
new = """        // 反证 G：就地改写调用方的累积序列
        messages.clear();
        messages.addAll(out);
        return new Trimmed(out, notice, droppedRounds, droppedTokens, List.copyOf(tools),
                used, used <= budget);"""
assert old in s, "G: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 G：裁剪就地改写累积序列（裁一次就永久丢历史）" \
    'RunExecutionLoopTest,ContextBudgetTrimmerTest,WorkerChildAgentRunnerTest' red
restore

echo "================ 恢复后复跑全绿 ================"
restore
mvn -o -B -pl agent-worker -am \
    -Dtest='RunExecutionLoopTest,ContextBudgetTrimmerTest,WorkerChildAgentRunnerTest' \
    -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
  | grep -E "Tests run: [0-9]+, Failures" | sed 's/^/  /' | tail -8

if [ "$FAILED" -ne 0 ]; then
  echo "⛔ 存在无效反证（结论未变 / 编译失败 / 范围越界）"
  exit 1
fi
echo "✅ 全部反证有效"
