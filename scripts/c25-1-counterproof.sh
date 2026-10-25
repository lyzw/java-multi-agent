#!/usr/bin/env bash
# 第二十五批 C25-1 反证脚本：把「输出契约 / 路由策略的独立生命周期迁移」逐处摘掉，
# 确认对应用例变红。
#
# ⚠️ 本脚本要证伪的是一件事：
#   **「停用」必须是一条真防线，而不是治理层的一个说法** ——
#   若被引用的契约 / 策略无法被停用、或停用了照样能被新发布引用，
#   那么「已停用」三个字对系统没有任何约束力。
#
# ⚠️ 每处反证只动一个地方，然后跑相关测试：
#   被摘掉的那条能力，对应用例必须红；而**不该受影响**的用例必须保持绿
#   （否则说明反证改动的范围比预想的大 —— 结论不纯）。
#
# ⚠️ **判定标准**：每处反证都必须让用例**真的变红**。
#   若改动后结论不变（仍然全绿），说明这条反证**无效** —— 它没咬到任何东西，
#   此时脚本会明确报「⛔ 反证无效」并以非零码退出。
#   反过来，若编译就失败（而不是用例红），同样判为无效 ——
#   「编译不过」不是「用例红」，它证明不了任何结论。
#
# ⚠️ DB 层反证需要本地 PG17（迁移 V26 已应用）；
#   应用层反证不需要 DB。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
# ⚠️ DB 层 IT 需要真实 PG17（非特权角色，才能验到 RLS 真实效果）
export IT_PG_URL="${IT_PG_URL:-jdbc:postgresql://127.0.0.1:5433/agent_dev}"
export IT_PG_USER="${IT_PG_USER:-app_agent}"
export IT_PG_PASSWORD="${IT_PG_PASSWORD:-app_agent_pw}"
export IT_PG_ADMIN_USER="${IT_PG_ADMIN_USER:-agent}"
export IT_PG_ADMIN_PASSWORD="${IT_PG_ADMIN_PASSWORD:-agent_dev_pw}"
PG_CONTAINER="${IT_PG_CONTAINER:-agent-pg17}"

VALIDATOR=agent-application/src/main/java/cn/studykid/multiagent/application/cap/AgentPublishClosureValidator.java
WORKFLOW=agent-application/src/main/java/cn/studykid/multiagent/application/cap/ConfigPublishWorkflowService.java
LIFECYCLE=agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcConfigLifecycleRepository.java
REPO=agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcCapResultContractRepository.java
BAK=/tmp/c25_1_counterproof
mkdir -p "$BAK"
cp "$VALIDATOR" "$BAK/validator.bak"
cp "$WORKFLOW" "$BAK/workflow.bak"
cp "$LIFECYCLE" "$BAK/lifecycle.bak"
cp "$REPO" "$BAK/repo.bak"
restore() {
  cp "$BAK/validator.bak" "$VALIDATOR"
  cp "$BAK/workflow.bak" "$WORKFLOW"
  cp "$BAK/lifecycle.bak" "$LIFECYCLE"
  cp "$BAK/repo.bak" "$REPO"
}

# ⚠️ 期望结论：red = 用例必须变红；green = 用例必须保持全绿。
#    两者都不满足 → 该反证无效 → 脚本以非零码退出。
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
  local label="$1" module="$2" tests="$3" expect="$4"
  local log=/tmp/c25_1_case.log
  mvn -o -B -pl "$module" -am -Dtest="$tests" \
      -Dsurefire.failIfNoSpecifiedTests=false test > "$log" 2>&1
  report "$label" "$expect" "$log"
}

run_it() {
  local label="$1" module="$2" tests="$3" expect="$4"
  local log=/tmp/c25_1_case.log
  mvn -o -B -pl "$module" -am verify -Dit.test="$tests" -Dtest='NoSuchUnitTest' \
      -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false \
      > "$log" 2>&1
  report "$label" "$expect" "$log"
}

restore

# ── 反证 A：发布期不再拒绝引用「已停用的输出契约」─────────────────────
#    预期：DEPRECATED 那条用例红；未停用的那条仍绿（成对断言没被一起拆掉）。
python3 - "$VALIDATOR" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (contract.isDeprecated()) {"""
new = """        if (false && contract.isDeprecated()) {"""
assert old in s, "A: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 A：发布期不再拒绝引用已停用的输出契约（停用不是防线）" \
    agent-application 'ContractPolicyLifecycleTest' red
restore

# ── 反证 B：发布期不再拒绝引用「已停用的路由策略」──────────────────────
#    预期：DEPRECATED 那条用例红；未停用的那条仍绿。
python3 - "$VALIDATOR" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (policy.isDeprecated()) {"""
new = """        if (false && policy.isDeprecated()) {"""
assert old in s, "B: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 B：发布期不再拒绝引用已停用的路由策略（停用不是防线）" \
    agent-application 'ContractPolicyLifecycleTest' red
restore

# ── 反证 C：生命周期迁移端点退回「只接受 agent 类型」──────────────────
#    预期：契约 / 策略的独立迁移用例红；
#    「平台级对象仍被拒」那条仍绿（证明改动范围准确）。
python3 - "$WORKFLOW" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """            Set.of(TENANT_OBJECT_TYPE, "result_contract", "routing_policy");"""
new = """            Set.of(TENANT_OBJECT_TYPE);"""
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 C：生命周期迁移端点退回只接受 agent（契约/策略又停不掉了）" \
    agent-application 'ContractPolicyLifecycleTest' red
restore

# ── 反证 D：持久层不认识 result_contract 这个对象类型 ──────────────────
#    预期：IT 里涉及契约迁移的用例红（错误，而不是「迁移成功」）。
python3 - "$LIFECYCLE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = '                        case TYPE_RESULT_CONTRACT -> "UPDATE cap_result_contract"\n'
new = ('                        case TYPE_RESULT_CONTRACT -> throw new IllegalArgumentException(\n'
       '                                "反证 D：持久层不认识 result_contract");\n'
       '                        case "__unused__" -> "UPDATE cap_result_contract"\n')
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new))
PY
run_it "反证 D：持久层不认识契约类型（独立停用在数据层无法执行）" \
    agent-infrastructure 'ContractPolicyLifecycleIT' red
restore

# ── 反证 E：DB 层不挂不可变触发器（已发布契约可被改写）────────────────
#    预期：IT「已发布契约改内容被拒」那条红。
if docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
  docker exec "$PG_CONTAINER" psql -U agent -d agent_dev -q -c \
    "DROP TRIGGER IF EXISTS trg_cap_result_contract_immutable ON cap_result_contract"
  run_it "反证 E：DB 层不挂不可变触发器（§6.1 对契约从未生效）" \
      agent-infrastructure 'ContractPolicyLifecycleIT' red
  docker exec "$PG_CONTAINER" psql -U agent -d agent_dev -q -c \
    "CREATE TRIGGER trg_cap_result_contract_immutable BEFORE UPDATE ON cap_result_contract \
     FOR EACH ROW EXECUTE FUNCTION forbid_published_def_mutation()"
else
  echo "⚠️ 未找到 PG 容器 $PG_CONTAINER，跳过反证 E"
fi
restore

# ── 反证 F：DB 层没有 lifecycle_state 列（结构断言非空转）──────────────
#    预期：结构断言里「列存在」那条红；应用层用例仍绿（它不依赖 DB）。
if docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
  docker exec "$PG_CONTAINER" psql -U agent -d agent_dev -q -c \
    "ALTER TABLE cap_result_contract RENAME COLUMN lifecycle_state TO lifecycle_state_probe"
  run_it "反证 F：DB 层没有 lifecycle_state 列（停用无处落地）" \
      agent-infrastructure 'ContractPolicyLifecycleIT$MigrationStructure' red
  run_unit "反证 F（对照）：应用层用例不依赖 DB，应保持绿" \
      agent-application 'ContractPolicyLifecycleTest' green
  docker exec "$PG_CONTAINER" psql -U agent -d agent_dev -q -c \
    "ALTER TABLE cap_result_contract RENAME COLUMN lifecycle_state_probe TO lifecycle_state"
else
  echo "⚠️ 未找到 PG 容器 $PG_CONTAINER，跳过反证 F"
fi
restore

# ── 反证 G：仓储读不到 lifecycle_state（防线的数据来源被架空）──────────
#    预期：IT「仓储读得到 lifecycle_state」那条红。
python3 - "$REPO" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                publishedAt == null ? null : publishedAt.toInstant(),
                lifecycleState(rs));"""
new = """                publishedAt == null ? null : publishedAt.toInstant(),
                null);"""
assert old in s, "G: 未命中"
p.write_text(s.replace(old, new))
PY
run_it "反证 G：仓储读不到 lifecycle_state（已停用这个事实读不出来）" \
    agent-infrastructure 'ContractPolicyLifecycleIT' red
restore

echo "================ 恢复后复跑全绿 ================"
restore
mvn -o -B -pl agent-application -am \
    -Dtest='ContractPolicyLifecycleTest,AgentPublishClosureValidatorTest' \
    -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
  | grep -E "Tests run: [0-9]+, Failures" | sed 's/^/  /' | tail -8
mvn -o -B -pl agent-infrastructure -am verify -Dit.test='ContractPolicyLifecycleIT' \
    -Dtest='NoSuchUnitTest' -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false 2>&1 \
  | grep -E "Tests run: [0-9]+, Failures" | sed 's/^/  /' | tail -8

if [ "$FAILED" -ne 0 ]; then
  echo "⛔ 存在无效反证（结论未变 / 编译失败 / 范围越界）"
  exit 1
fi
echo "✅ 全部反证有效"
