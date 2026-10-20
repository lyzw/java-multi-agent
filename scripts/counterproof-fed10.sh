#!/usr/bin/env bash
# FE-D10 反证执行器（B-1 / B-2 / T-A / T-B / T-C）
#
# 与 P4-D13 同构：注入 → 跑指定测试 → 期望如期转红 → 还原。
# ⚠️ 沿用 P4-D13 抓到的两条教训：
#    ① 不用 `-q`（会抑制 Tests run 汇总 → grep 永不匹配 → 默认 GREEN）
#    ② 断言本轮**确实跑了用例**，否则判「无效」而非「绿」
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../frontend" || exit 1

# $1=测试名过滤 $2=测试文件
run_case() {
  npx vitest run "$2" -t "$1" --reporter=dot >/tmp/fe-cp.log 2>&1
  local ran
  ran=$(grep -oE "Tests +[0-9]+ (failed|passed)" /tmp/fe-cp.log | head -1 | grep -oE "[0-9]+")
  if [ -z "${ran:-}" ] || [ "$ran" = "0" ]; then
    echo "     ⛔ 本轮**没有用例被执行**——判定无效"
    tail -12 /tmp/fe-cp.log
    return 2
  fi
  echo "     （本轮实跑 $ran 项）"
  return 0
}

is_red() { grep -qE "Tests +[0-9]+ failed" /tmp/fe-cp.log; }

report() { # $1=编号 $2=期望 $3=实际
  if [ "$3" = "INVALID" ]; then
    printf '%-5s ⛔ 判定无效（测试未执行）\n' "$1"; return
  fi
  if [ "$2" = "$3" ]; then printf '%-5s ✅ 如期 %s\n' "$1" "$3"
  else printf '%-5s ❓ 未如期（期望 %s，实得 %s）\n' "$1" "$2" "$3"; fi
}

RED='RED'; GREEN='GREEN'; INVALID='INVALID'
GOV=src/pages/governance/GovernancePages.tsx
SEARCH=src/pages/business/RunSearchPage.tsx

# ── B-1 给 P9 加一个「重试」按钮 → 禁重试用例必须红 ──
echo "── B-1 P9 增加「重试」按钮"
cp "$GOV" /tmp/B1.bak
python3 - <<'PY'
p='src/pages/governance/GovernancePages.tsx'
s=open(p,encoding='utf-8').read()
old = """                  ) : actionable ? (
                    <button type="button" onClick={() => setSelected(item.operationId)}>
                      记录核对结论
                    </button>"""
new = """                  ) : actionable ? (
                    <>
                    <button type="button" onClick={() => setSelected(item.operationId)}>
                      记录核对结论
                    </button>
                    <button type="button">重试</button>
                    </>"""
assert old in s, 'B-1 注入锚点未找到'
open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
PY
B1=$INVALID
if run_case "不得出现任何重试" tests/governance.page.test.tsx; then
  is_red && B1=$RED || B1=$GREEN
fi
cp /tmp/B1.bak "$GOV"
report B-1 "$RED" "$B1"

# ── B-2 过期审批也给批准按钮 → T-6 必须红 ──
echo "── B-2 过期审批也提供批准按钮"
cp "$GOV" /tmp/B2.bak
python3 - <<'PY'
p='src/pages/governance/GovernancePages.tsx'
s=open(p,encoding='utf-8').read()
old = "const actionable = item.state === 'PENDING' && !item.expired"
new = "const actionable = item.state === 'PENDING' // 注入：过期也给按钮"
assert old in s, 'B-2 注入锚点未找到'
open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
PY
B2=$INVALID
if run_case "过期审批" tests/governance.page.test.tsx; then
  is_red && B2=$RED || B2=$GREEN
fi
cp /tmp/B2.bak "$GOV"
report B-2 "$RED" "$B2"

# ── T-A 改筛选时不丢游标 → T-8 必须红 ──
# ⚠️ **注入必须同时拆掉两道保险**：
#    首轮只拆 `deps` → 用例仍绿（页面的 `reset()` 独立生效）；
#    再只拆 `reset()` → 仍绿（deps 变化本身会重跑 effect）。
#    也就是说：**任意一道都足够**，两者的关系是「冗余双保险」。
#    因此真正的反证是「两道全拆」，否则测的是「哪一道在起作用」而非「机制是否承重」。
echo "── T-A 改筛选沿用旧游标（同时拆掉 deps 与 reset）"
cp "$SEARCH" /tmp/TA.bak
python3 - <<'PY'
p='src/pages/business/RunSearchPage.tsx'
s=open(p,encoding='utf-8').read()
old_deps = """      // ⚠️ 筛选条件进 deps → 改筛选自动回到第一页
      deps: [state],"""
new_deps = "      deps: [], // 注入：筛选不进 deps"
assert old_deps in s, 'T-A deps 锚点未找到'
s = s.replace(old_deps, new_deps, 1)

old_reset = """                setState(e.target.value)
                // ⚠️ 改筛选必须丢游标：拿旧游标查新条件会得到空页/错页，
                //    而空页看起来像「这个租户没有数据」——
                //    这类「看起来像结论的错误」最难排查。
                reset()"""
new_reset = "                setState(e.target.value) // 注入：不 reset"
assert old_reset in s, 'T-A reset 锚点未找到'
s = s.replace(old_reset, new_reset, 1)
open(p,'w',encoding='utf-8').write(s)
PY
TA=$INVALID
if run_case "改筛选条件" tests/runSearch.page.test.tsx; then
  is_red && TA=$RED || TA=$GREEN
fi
cp /tmp/TA.bak "$SEARCH"
report T-A "$RED" "$TA"

# ── T-B 空列表说成「读取失败」→ T-3 必须红 ──
echo "── T-B 空态说成故障"
cp "$SEARCH" /tmp/TB.bak
python3 - <<'PY'
p='src/pages/business/RunSearchPage.tsx'
s=open(p,encoding='utf-8').read()
old = """          <Empty
            title="没有符合条件的记录"
            hint="放宽时间范围，或换个状态再试。"
          />"""
new = """          <Empty
            title="读取失败"
            hint="放宽时间范围，或换个状态再试。"
          />"""
assert old in s, 'T-B 注入锚点未找到'
open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
PY
TB=$INVALID
if run_case "诚实空态" tests/runSearch.page.test.tsx; then
  is_red && TB=$RED || TB=$GREEN
fi
cp /tmp/TB.bak "$SEARCH"
report T-B "$RED" "$TB"

# ── T-C 加回后端没有的「学科/耗时」两列 → T-10 必须红 ──
echo "── T-C 加回「学科/耗时」列"
cp "$SEARCH" /tmp/TC.bak
python3 - <<'PY'
p='src/pages/business/RunSearchPage.tsx'
s=open(p,encoding='utf-8').read()
old = "const COLUMNS = ['运行标识', '状态', '发起时间', '完成时间', '领取次数']"
new = "const COLUMNS = ['运行标识', '状态', '学科', '发起时间', '完成时间', '耗时', '领取次数']"
assert old in s, 'T-C 注入锚点未找到'
open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
PY
TC=$INVALID
if run_case "不得编造后端没有的字段" tests/runSearch.page.test.tsx; then
  is_red && TC=$RED || TC=$GREEN
fi
cp /tmp/TC.bak "$SEARCH"
report T-C "$RED" "$TC"

echo
echo "（全部反证执行完毕；工作区已还原，请用 git status 复核）"