#!/usr/bin/env bash
# P4-D13 反证执行器（A-1 ~ A-7）
#
# 每个反证：注入一个错误 → 跑指定测试 → 期望**如期转红** → 还原。
# 只报告「是否如期转红」，不做判断——判断由人看输出。
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../platform" || exit 1
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin:$PATH"
set -a; source ../config.env; set +a
export IT_PG_URL=jdbc:postgresql://127.0.0.1:5433/agent_it
export IT_PG_USER=app_agent
export IT_PG_PASSWORD=
export RABBITMQ_VHOST=agent-test

INF=agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence
API=agent-api/src/main/java/cn/studykid/multiagent/api/governance
PORTS=agent-ports/src/main/java/cn/studykid/multiagent/ports/persistence

run_it() { # $1=模块 $2=测试类
  # ⚠️⚠️ **不可用 `-q`**：它会连带抑制 `[INFO] Tests run: …` 汇总行，
  #    导致下面的 grep 永远匹配不到 → **每个反证都被误报为「如期保持绿」**。
  #    这正是 P4-D04 F-14 的同形态：「测试没跑起来」被当成「测试通过」。
  #
  # ⚠️⚠️ **必须先清 failsafe 报告**：failsafe:verify 读的是 target 下的
  #    failsafe-summary.xml，而它**不会**因为本轮「无用例执行」而重写——
  #    上一轮（故意跑红）的残留会被本轮 verify 当成本轮结果。
  #    后果：`-Dit.test=X` 在某个模块匹配不到用例时，
  #    verify 仍报「There are test failures」，整个 reactor 中断。
  #    同样是「陈旧产物冒充本轮结论」，与 F-14 同源。
  rm -rf */target/failsafe-reports */target/failsafe-summary.xml

  mvn -o -pl "$1" -am test-compile failsafe:integration-test failsafe:verify \
      -Dit.test="$2" -Dfailsafe.failIfNoSpecifiedTests=false >/tmp/cp.log 2>&1
  local ran
  ran=$(grep -oE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+" /tmp/cp.log \
        | tail -1 | grep -oE "^Tests run: [0-9]+" | grep -oE "[0-9]+")
  if [ -z "${ran:-}" ] || [ "$ran" = "0" ]; then
    echo "     ⛔ 本轮**没有用例被执行**（编译失败或类名不匹配）——判定无效"
    sed -n '/COMPILATION ERROR/,+6p' /tmp/cp.log | head -8
    return 2
  fi
  echo "     （本轮实跑 $ran 项）"
  return 0
}

# 红/绿判定：必须**同时**看到「有失败」与「确有执行」。
is_red() {
  grep -qE "Tests run: [0-9]+, Failures: [1-9]" /tmp/cp.log \
    || grep -qE "Tests run: [0-9]+, Failures: 0, Errors: [1-9]" /tmp/cp.log
}

report() { # $1=编号 $2=期望 $3=实际
  if [ "$3" = "INVALID" ]; then
    printf '%-6s ⛔ 判定无效（测试未执行）\n' "$1"
    return
  fi
  if [ "$2" = "$3" ]; then
    printf '%-6s ✅ 如期 %s\n' "$1" "$3"
  else
    printf '%-6s ❓ 未如期（期望 %s，实得 %s）\n' "$1" "$2" "$3"
  fi
}

RED='RED'; GREEN='GREEN'; INVALID='INVALID'

# ── A-1 去掉 SQL 显式 tenant_id + 删 RLS 策略 → 跨租户用例必须红 ──
echo "── A-1 去掉显式 tenant_id + 删除 RLS 策略"
cp "$INF/JdbcRunListQueryRepository.java" /tmp/A1.bak
sed -i '' 's|WHERE tenant_id = ?::uuid|WHERE true /* A1 注入 */|' \
    "$INF/JdbcRunListQueryRepository.java"
docker exec agent-pg17 psql -U agent -d agent_it -qc \
    "DROP POLICY IF EXISTS run_tenant_isolation ON run;" >/dev/null 2>&1
A1=$INVALID
if run_it agent-infrastructure GovernanceQueryIT; then
  is_red && A1=$RED || A1=$GREEN
fi
cp /tmp/A1.bak "$INF/JdbcRunListQueryRepository.java"
docker exec agent-pg17 psql -U agent -d agent_it -qc \
    "ALTER TABLE run ENABLE ROW LEVEL SECURITY;
     CREATE POLICY run_tenant_isolation ON run
       USING (tenant_id = current_tenant_id())
       WITH CHECK (tenant_id = current_tenant_id());" >/dev/null 2>&1
report A-1 "$RED" "$A1"

# ── A-2 排序键去掉 run_id → 全序性断言必须红 ──
echo "── A-2 排序键去掉 run_id"
cp "$INF/JdbcRunListQueryRepository.java" /tmp/A2.bak
sed -i '' 's|ORDER BY created_at DESC, run_id DESC LIMIT ?|ORDER BY created_at DESC LIMIT ?|' \
    "$INF/JdbcRunListQueryRepository.java"
A2=$INVALID
if run_it agent-infrastructure GovernanceQueryIT; then
  is_red && A2=$RED || A2=$GREEN
fi
cp /tmp/A2.bak "$INF/JdbcRunListQueryRepository.java"
report A-2 "$RED" "$A2"

# ── A-3 limit 上限放开 → 端点 400 断言必须红 ──
echo "── A-3 limit 上限放开到 10000"
cp "$API/GovernanceSupport.java" /tmp/A3.bak
sed -i '' 's|MAX_LIMIT = 100;|MAX_LIMIT = 10000;|' "$API/GovernanceSupport.java"
A3=$INVALID
if run_it platform-bootstrap GovernanceEndpointIT; then
  is_red && A3=$RED || A3=$GREEN
fi
cp /tmp/A3.bak "$API/GovernanceSupport.java"
report A-3 "$RED" "$A3"

# ── A-4 过期审批自动批准 → 409 断言必须红 ──
echo "── A-4 过期审批自动批准"
cp "$INF/JdbcApprovalQueryRepository.java" /tmp/A4.bak
sed -i '' 's|if (current.expired()) {|if (false) { /* A4 注入 */|' \
    "$INF/JdbcApprovalQueryRepository.java"
A4=$INVALID
if run_it platform-bootstrap GovernanceEndpointIT; then
  is_red && A4=$RED || A4=$GREEN
fi
cp /tmp/A4.bak "$INF/JdbcApprovalQueryRepository.java"
report A-4 "$RED" "$A4"

# ── A-5 处置枚举加入 RETRY → G9-2 必须红 ──
echo "── A-5 处置枚举加入 RETRY"
cp "$PORTS/UnknownOperationPort.java" /tmp/A5.bak
sed -i '' 's|        MANUAL_DISPOSED$|        MANUAL_DISPOSED,\n        RETRY|' \
    "$PORTS/UnknownOperationPort.java"
# ⚠️ 自检：注入必须真的发生，否则这次反证等于没跑
grep -q '^        RETRY$' "$PORTS/UnknownOperationPort.java" \
  || { echo "     ⛔ 注入未生效（sed 未匹配）"; cp /tmp/A5.bak "$PORTS/UnknownOperationPort.java"; report A-5 "$RED" "$INVALID"; }
A5=$INVALID
if run_it platform-bootstrap GovernanceEndpointIT; then
  is_red && A5=$RED || A5=$GREEN
fi
cp /tmp/A5.bak "$PORTS/UnknownOperationPort.java"
report A-5 "$RED" "$A5"

# ── A-6 不存在/跨租户改抛 AccessDenied（403 路径）→ 跨租户断言必须红 ──
echo "── A-6 跨租户抛 AccessDenied 而非 NotFound"
cp "$INF/JdbcApprovalQueryRepository.java" /tmp/A6.bak
sed -i '' 's|throw new ApprovalQueryPort.ApprovalNotFoundException(approvalId);|throw new cn.studykid.multiagent.ports.security.AccessDeniedException("actor", "approval:consume", approvalId); // A6 注入|' \
    "$INF/JdbcApprovalQueryRepository.java"
A6=$INVALID
if run_it platform-bootstrap GovernanceEndpointIT; then
  is_red && A6=$RED || A6=$GREEN
fi
cp /tmp/A6.bak "$INF/JdbcApprovalQueryRepository.java"
report A-6 "$RED" "$A6"

# ── A-7 治理端点复用 EVENT_QUERY 桶 → 分桶隔离断言必须红 ──
echo "── A-7 治理端点复用 EVENT_QUERY 桶"
for c in RunListController ApprovalController UnknownOperationController AuditQueryController; do
  cp "$API/$c.java" "/tmp/A7-$c.bak"
  sed -i '' 's|Buckets.GOVERNANCE_QUERY|Buckets.EVENT_QUERY|g' "$API/$c.java"
done
# ⚠️ 自检：四个控制器必须**全部**换掉，否则反证覆盖不全
swapped=$(grep -l 'Buckets.EVENT_QUERY' "$API"/*.java | wc -l | tr -d ' ')
[ "$swapped" -ge 4 ] || echo "     ⛔ 只有 $swapped/4 个控制器被替换"
A7=$INVALID
if run_it platform-bootstrap GovernanceEndpointIT; then
  is_red && A7=$RED || A7=$GREEN
fi
for c in RunListController ApprovalController UnknownOperationController AuditQueryController; do
  cp "/tmp/A7-$c.bak" "$API/$c.java"
done
report A-7 "$RED" "$A7"

echo
echo "（全部反证执行完毕；工作区已还原，请用 git status 复核）"