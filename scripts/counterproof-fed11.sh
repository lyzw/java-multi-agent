#!/usr/bin/env bash
# FE-D11 反证执行器（T-1 ~ T-4）
#
# ⚠️ 沿用本项目已踩四次的教训（F-14 / CP-1 CP-2 / A-2 长期假绿 / D15-B3 假绿）：
#   ① 不用 `-q`（会抑制汇总行 → grep 永不匹配 → 默认 GREEN）
#   ② 断言本轮**确实跑了用例**，否则判「无效」而非「绿」
#   ③ **注入必须自检**：sed 未匹配时注入根本没发生，
#      而「未转红」会被误读成 GREEN。
#      本脚本每条反证都有 grep 自检 + 还原后的二次确认。
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../frontend" || exit 1

TEN=src/features/config/tightening.ts
ROW=src/features/config/ConfigOverrideRow.tsx
PAGE=src/pages/config/ConfigPages.tsx
ERR=src/api/errors.ts

run_case() { # $1=测试名过滤（空=全部）
  if [ -n "${1:-}" ]; then
    npx vitest run "$2" -t "$1" --reporter=dot >/tmp/fe-cp.log 2>&1
  else
    npx vitest run "$2" --reporter=dot >/tmp/fe-cp.log 2>&1
  fi
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
T=configWrite.page.test.tsx

# ── T-1 去掉单向棘轮 → F6/F7 必须红 ──
echo "── T-1 审批矩阵去掉棘轮（列出全部等级）"
cp "$TEN" /tmp/T1.bak
sed -i '' 's|  return all.filter((c) => strictnessOf(c) >= strictnessOf(current))|  return all // T-1 注入|' "$TEN"
# ⚠️ 自检：注入必须真的发生
grep -q 'T-1 注入' "$TEN" \
  || { echo "     ⛔ 注入未生效（sed 未匹配）"; cp /tmp/T1.bak "$TEN"; report T-1 "$RED" "$INVALID"; }
T1=$INVALID
if run_case "F6" "$T"; then
  is_red && T1=$RED || T1=$GREEN
fi
cp /tmp/T1.bak "$TEN"
report T-1 "$RED" "$T1"

# ── T-2 去掉非正数拒绝 → F12 必须红 ──
echo "── T-2 数值上限去掉非正数拒绝"
cp "$TEN" /tmp/T2.bak
sed -i '' 's|  if (n <= 0) return false|  // T-2 注入：不再拒绝非正数|' "$TEN"
grep -q 'T-2 注入' "$TEN" \
  || { echo "     ⛔ 注入未生效（sed 未匹配）"; cp /tmp/T2.bak "$TEN"; report T-2 "$RED" "$INVALID"; }
T2=$INVALID
if run_case "F12b" "$T"; then
  is_red && T2=$RED || T2=$GREEN
fi
cp /tmp/T2.bak "$TEN"
report T-2 "$RED" "$T2"

# ── T-3 理由门槛放宽到 1 字 → F9/F10 必须红 ──
echo "── T-3 变更理由门槛降到 1 字"
cp "$TEN" /tmp/T3.bak
sed -i '' 's|export const MIN_REASON_LENGTH = 10|export const MIN_REASON_LENGTH = 1 // T-3 注入|' "$TEN"
grep -q 'T-3 注入' "$TEN" \
  || { echo "     ⛔ 注入未生效（sed 未匹配）"; cp /tmp/T3.bak "$TEN"; report T-3 "$RED" "$INVALID"; }
T3=$INVALID
if run_case "" "$T"; then
  is_red && T3=$RED || T3=$GREEN
fi
cp /tmp/T3.bak "$TEN"
report T-3 "$RED" "$T3"

# ── T-4 409 文案退回「刷新后重试」 → F17 必须红 ──
echo "── T-4 409 文案退回通用冲突提示"
cp "$ERR" /tmp/T4.bak
# ⚠️ **注入点在「强制覆盖」那处，而非 defaultMessage**：
#    首轮注入 defaultMessage 后 F17 仍绿——因为请求带非空 message 时
#    根本不会走到该分支（实测发现：那段映射是**死代码**）。
#    故改为删掉「强制覆盖」逻辑，使文案重新被服务端 message 支配。
python3 - <<'PY'
p='src/api/errors.ts'
s=open(p,encoding='utf-8').read()
old="""        : isConfigRelaxation
          ? CONFIG_RELAXATION_MESSAGE
          : codeMessage.message.length > 0"""
new="""        : codeMessage.message.length > 0 // T-4 注入：去掉强制覆盖"""
assert old in s, 'T-4 锚点未找到'
s = s.replace(old, new, 1)
open(p,'w',encoding='utf-8').write(s)
PY
grep -q 'T-4 注入' "$ERR" \
  || { echo "     ⛔ 注入未生效（锚点未匹配）"; cp /tmp/T4.bak "$ERR"; report T-4 "$RED" "$INVALID"; }
T4=$INVALID
if run_case "F17" "$T"; then
  is_red && T4=$RED || T4=$GREEN
fi
cp /tmp/T4.bak "$ERR"
report T-4 "$RED" "$T4"

# ── T-5 去掉「上一个 Run」生效说明 → F14 必须红 ──
echo "── T-5 删掉生效方式说明（改成误导性的「立即生效」）"
cp "$PAGE" /tmp/T5.bak
python3 - <<'PY'
p='src/pages/config/ConfigPages.tsx'
s=open(p,encoding='utf-8').read()
old="""          保存后<b>下一个新建的 Run</b> 会使用新值；已经开始执行的 Run 不受影响
          （配置在 Run 创建时即被快照固化）。"""
new="""          保存后<b>立即生效</b>。 // T-5 注入"""
assert old in s, 'T-5 锚点未找到'
open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
PY
grep -q 'T-5 注入' "$PAGE" \
  || { echo "     ⛔ 注入未生效（锚点未匹配）"; cp /tmp/T5.bak "$PAGE"; report T-5 "$RED" "$INVALID"; }
T5=$INVALID
if run_case "F14" "$T"; then
  is_red && T5=$RED || T5=$GREEN
fi
cp /tmp/T5.bak "$PAGE"
report T-5 "$RED" "$T5"

echo
echo "（全部反证执行完毕；工作区已还原，请用 git status 复核）"