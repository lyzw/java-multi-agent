#!/usr/bin/env bash
# P4-D15 反证执行器（S-1 ~ S-4）
#
# 每个反证：注入一个错误 → 跑指定测试 → 期望**如期转红** → 还原。
#
# ⚠️ 沿用本项目已踩三次的教训（P4-D04 F-14 / P4-D13 CP-1 CP-2 / A-2 长期假绿）：
#   ① 不用 `-q`（会抑制 [INFO] Tests run: 汇总行 → grep 永不匹配 → 默认 GREEN）
#   ② 断言本轮**确实跑了用例**，否则判「无效」而非「绿」
#   ③ **注入必须自检**：sed 未匹配时注入根本没发生，
#      而「未转红」会被误读成 GREEN——A-2 就是这样假绿了很久。
#      本脚本每个反证都有 grep 自检，覆盖 4/4。
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../platform" || exit 1
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin:$PATH"
set -a; source ../config.env; set +a
export IT_PG_URL=jdbc:postgresql://127.0.0.1:5433/agent_it
export IT_PG_USER=app_agent
export IT_PG_PASSWORD=
export RABBITMQ_VHOST=agent-test

APP=agent-application/src/main/java/cn/studykid/multiagent/application/config
BOOT=platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config

# 单元层反证（不依赖数据库，容器停了也能跑）
run_unit() { # $1=测试类
  run_unit2 agent-application "$1"
}

run_unit2() { # $1=模块 $2=测试类
  rm -rf */target/surefire-reports */target/failsafe-summary.xml
  mvn -o -pl "$1" -am test -Dtest="$2" -Dsurefire.failIfNoSpecifiedTests=false \
      >/tmp/cp-d15.log 2>&1
  local ran
  ran=$(grep -oE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+" /tmp/cp-d15.log \
        | tail -1 | grep -oE "^Tests run: [0-9]+" | grep -oE "[0-9]+")
  if [ -z "${ran:-}" ] || [ "$ran" = "0" ]; then
    echo "     ⛔ 本轮**没有用例被执行**（编译失败或类名不匹配）——判定无效"
    sed -n '/COMPILATION ERROR/,+6p' /tmp/cp-d15.log | head -8
    return 2
  fi
  echo "     （本轮实跑 $ran 项）"
  return 0
}

# 集成层反证（依赖真实 PG）
run_it() { # $1=模块 $2=测试类
  rm -rf */target/failsafe-reports */target/failsafe-summary.xml
  mvn -o -pl "$1" -am test-compile failsafe:integration-test failsafe:verify \
      -Dit.test="$2" -Dfailsafe.failIfNoSpecifiedTests=false >/tmp/cp-d15.log 2>&1
  local ran
  ran=$(grep -oE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+" /tmp/cp-d15.log \
        | tail -1 | grep -oE "^Tests run: [0-9]+" | grep -oE "[0-9]+")
  if [ -z "${ran:-}" ] || [ "$ran" = "0" ]; then
    echo "     ⛔ 本轮**没有用例被执行**——判定无效"
    return 2
  fi
  echo "     （本轮实跑 $ran 项）"
  return 0
}

is_red() {
  grep -qE "Tests run: [0-9]+, Failures: [1-9]" /tmp/cp-d15.log \
    || grep -qE "Tests run: [0-9]+, Failures: 0, Errors: [1-9]" /tmp/cp-d15.log
}

report() { # $1=编号 $2=期望 $3=实际
  if [ "$3" = "INVALID" ]; then
    printf '%-5s ⛔ 判定无效（测试未执行）\n' "$1"; return
  fi
  if [ "$2" = "$3" ]; then printf '%-5s ✅ 如期 %s\n' "$1" "$3"
  else printf '%-5s ❓ 未如期（期望 %s，实得 %s）\n' "$1" "$2" "$3"; fi
}

RED='RED'; GREEN='GREEN'; INVALID='INVALID'

# ── S-1 去掉收紧判定 → 收紧策略用例必须红 ──
echo "── S-1 去掉「只能收紧」判定"
cp "$APP/ConfigTighteningPolicy.java" /tmp/S1.bak
python3 - <<'PY'
p='agent-application/src/main/java/cn/studykid/multiagent/application/config/ConfigTighteningPolicy.java'
s=open(p,encoding='utf-8').read()
old = "            case TIGHTEN -> checkUpperBoundTightening(key, newValue, currentValue, valueType);"
new = "            case TIGHTEN -> valueType.parse(newValue); // S1 注入：去掉收紧判定"
assert old in s, 'S-1 锚点未找到'
s = s.replace(old, new, 1)
# 去掉 RATCHET 判定
old2 = "            case TIGHTEN_RATCHET -> checkRatchetTightening(key, newValue, currentValue);"
new2 = "            case TIGHTEN_RATCHET -> valueType.parse(newValue); // S1 注入"
assert old2 in s, 'S-1b 锚点未找到'
s = s.replace(old2, new2, 1)
open(p,'w',encoding='utf-8').write(s)
PY
# ⚠️ 自检：注入必须真的发生（并且能编译）
grep -q 'S1 注入' "$APP/ConfigTighteningPolicy.java" \
  || { echo "     ⛔ 注入未生效（锚点未匹配）"; cp /tmp/S1.bak "$APP/ConfigTighteningPolicy.java"; report S-1 "$RED" "$INVALID"; }
S1=$INVALID
if run_unit ConfigTighteningPolicyTest; then
  is_red && S1=$RED || S1=$GREEN
fi
cp /tmp/S1.bak "$APP/ConfigTighteningPolicy.java"
report S-1 "$RED" "$S1"

# ── S-2 去掉理由长度校验 → 理由用例必须红 ──
echo "── S-2 去掉 CHG 理由长度校验"
cp "$APP/ConfigTighteningPolicy.java" /tmp/S2.bak
sed -i '' 's|if (reason == null \|\| reason.trim().length() < MIN_REASON_LENGTH) {|if (false) { // S2 注入|' \
    "$APP/ConfigTighteningPolicy.java"
grep -q 'S2 注入' "$APP/ConfigTighteningPolicy.java" \
  || { echo "     ⛔ 注入未生效（锚点未匹配）"; cp /tmp/S2.bak "$APP/ConfigTighteningPolicy.java"; report S-2 "$RED" "$INVALID"; }
S2=$INVALID
if run_unit ConfigTighteningPolicyTest; then
  is_red && S2=$RED || S2=$GREEN
fi
cp /tmp/S2.bak "$APP/ConfigTighteningPolicy.java"
report S-2 "$RED" "$S2"

# ── S-3 去掉白名单 → 敏感键可写用例必须红 ──
echo "── S-3 白名单改为放行一切（黑名单语义）"
cp "$APP/ConfigTighteningPolicy.java" /tmp/S3.bak
sed -i '' 's|        if (direction == null) {|        if (false) { // S3 注入：不再拦截非白名单键|' \
    "$APP/ConfigTighteningPolicy.java"
grep -q 'S3 注入' "$APP/ConfigTighteningPolicy.java" \
  || { echo "     ⛔ 注入未生效（锚点未匹配）"; cp /tmp/S3.bak "$APP/ConfigTighteningPolicy.java"; report S-3 "$RED" "$INVALID"; }
S3=$INVALID
if run_unit ConfigTighteningPolicyTest; then
  is_red && S3=$RED || S3=$GREEN
fi
cp /tmp/S3.bak "$APP/ConfigTighteningPolicy.java"
report S-3 "$RED" "$S3"

# ── S-4 快照忽略 DB 覆盖 → 覆盖不生效用例必须红 ──
# ⚠️ 首轮本反证指向的是 ConfigOverrideEndpointIT，**实测未转红**——
#    因为该 IT 只验证「读接口显示新值」，不验证「快照真的用了覆盖」。
#    这本身就是「假绿」的又一次实例，且发生在本切片自己的新测试上。
#    改为指向真正的行为测试 ConfigOverrideSnapshotWiringTest（D15-9）。
echo "── S-4 快照时忽略 DB 覆盖（端到端不生效）"
cp "$BOOT/WorkerExecutionConfig.java" /tmp/S4.bak
sed -i '' 's|            if (effectiveResolver != null \&\& tenantId != null) {|            if (false) { // S4 注入：假装没有覆盖|' \
    "$BOOT/WorkerExecutionConfig.java"
grep -q 'S4 注入' "$BOOT/WorkerExecutionConfig.java" \
  || { echo "     ⛔ 注入未生效（锚点未匹配）"; cp /tmp/S4.bak "$BOOT/WorkerExecutionConfig.java"; report S-4 "$RED" "$INVALID"; }
S4=$INVALID
if run_unit2 platform-bootstrap ConfigOverrideSnapshotWiringTest; then
  is_red && S4=$RED || S4=$GREEN
fi
cp /tmp/S4.bak "$BOOT/WorkerExecutionConfig.java"
report S-4 "$RED" "$S4"

echo
echo "（全部反证执行完毕；工作区已还原，请用 git status 复核）"