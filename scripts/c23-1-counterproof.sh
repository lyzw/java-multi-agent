#!/usr/bin/env bash
# 第二十三批 C23-1 反证脚本：把「委派记录补模型原始候选」逐处摘掉，
# 确认对应用例变红。
#
# ⚠️ 本脚本要证伪的是一件事：
#   **路由降级这件事必须能从数据上看出来** ——
#   只记「实际生效的目标」的话，「这次是不是降级了、模型原本想选谁」
#   就只剩日志 —— 日志会轮转、会丢，事后无法复核。
#
# ⚠️ 每处反证只动一个地方，然后跑相关测试：
#   被摘掉的那条能力，对应用例必须红；而**不该受影响**的用例必须保持绿
#   （否则说明反证改动的范围比预想的大 —— 结论不纯）。
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
# ⚠️ 反证 G 需要直接改 DB 里的索引谓词（迁移脚本本身已应用过一次）
PG_CONTAINER="${IT_PG_CONTAINER:-agent-pg17}"

EXEC=agent-application/src/main/java/cn/studykid/multiagent/application/orchestration/DelegationExecutor.java
RECORD=agent-core/src/main/java/cn/studykid/multiagent/core/orchestration/AgentInvocation.java
JDBC=agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/persistence/cap/JdbcAgentInvocationRepository.java
MIG=agent-infrastructure/src/main/resources/db/migration/V25__agent_invocation_requested_target.sql
BAK=/tmp/c23_1_counterproof
mkdir -p "$BAK"
cp "$EXEC" "$BAK/exec.bak"
cp "$RECORD" "$BAK/record.bak"
cp "$JDBC" "$BAK/jdbc.bak"
cp "$MIG" "$BAK/mig.bak"
restore() {
  cp "$BAK/exec.bak" "$EXEC"
  cp "$BAK/record.bak" "$RECORD"
  cp "$BAK/jdbc.bak" "$JDBC"
  cp "$BAK/mig.bak" "$MIG"
}

run_unit() {
  local label="$1" module="$2" tests="$3"
  echo "================ ${label} ================"
  mvn -o -B -pl "$module" -am -Dtest="$tests" \
      -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
    | grep -E "^\[ERROR\]   [A-Za-z]+(Test|IT)|Tests run: [0-9]+, Failures" \
    | sed 's/^/  /' | tail -14
  echo
}

run_it() {
  local label="$1" module="$2" tests="$3"
  echo "================ ${label} ================"
  mvn -o -B -pl "$module" -am verify -Dit.test="$tests" -Dtest='NoSuchUnitTest' \
      -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false 2>&1 \
    | grep -E "^\[ERROR\]   [A-Za-z]+(Test|IT)|Tests run: [0-9]+, Failures" \
    | sed 's/^/  /' | tail -14
  echo
}

restore

# ── 反证 A：执行器不写「模型原始候选」（落库为 NULL）──────────────────
#    预期：降级那条用例红（原始候选查不到 → 降级在数据上消失）。
python3 - "$EXEC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                command.targetAgentId(),
                AgentInvocationStatus.RUNNING,"""
new = """                null,
                AgentInvocationStatus.RUNNING,"""
assert old in s, "A: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 A：执行器不写「模型原始候选」（降级在数据上又消失了）" \
    agent-application 'RouterDelegationTest'
restore

# ── 反证 B：把「模型原始候选」冒充成「生效目标」────────────────────────
#    预期：降级那条用例红（两列相同 → 看起来从未降级）。
python3 - "$EXEC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                command.targetAgentId(),
                AgentInvocationStatus.RUNNING,"""
new = """                effectiveTargetId,
                AgentInvocationStatus.RUNNING,"""
assert old in s, "B: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 B：「模型原始候选」被冒充成「生效目标」（降级看不出）" \
    agent-application 'RouterDelegationTest'
restore

# ── 反证 C：routedFallback() 的判定反了（相同才算降级）────────────────
#    预期：降级那条用例红。
python3 - "$RECORD" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        return requestedTargetAgentId != null
                && !requestedTargetAgentId.equals(targetAgentId);"""
new = """        return requestedTargetAgentId != null
                && requestedTargetAgentId.equals(targetAgentId);"""
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 C：降级判定反了（相同才算降级）" \
    agent-application 'RouterDelegationTest'
restore

# ── 反证 D：兼容构造器把「未记录」填成「生效目标」（冒充）──────────────
#    预期：存量行那条用例红（NULL 语义被破坏）。
python3 - "$RECORD" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        this(invocationId, tenantId, runId, parentInvocationId, parentAgentId,
                parentAgentVersion, targetAgentId, targetAgentVersion, null,"""
new = """        this(invocationId, tenantId, runId, parentInvocationId, parentAgentId,
                parentAgentVersion, targetAgentId, targetAgentVersion, targetAgentId,"""
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 D：「未记录」被冒充成「生效目标」（存量行的 NULL 语义被破坏）" \
    agent-application 'RouterDelegationTest'
restore

# ── 反证 E：routedFallback() 对「未记录」返回 true ─────────────────────
#    预期：存量行那条用例红（把「无从判断」说成「降级过」）。
python3 - "$RECORD" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        return requestedTargetAgentId != null
                && !requestedTargetAgentId.equals(targetAgentId);"""
new = """        return requestedTargetAgentId == null
                || !requestedTargetAgentId.equals(targetAgentId);"""
assert old in s, "E: 未命中"
p.write_text(s.replace(old, new))
PY
run_unit "反证 E：「未记录」被当成「降级过」（凭空造出降级噪音）" \
    agent-application 'RouterDelegationTest'
restore

# ── 反证 F：JDBC 读列时映射错位（requested 读成 target）────────────────
#    预期：DB 层那条用例红。
python3 - "$JDBC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                rs.getString("requested_target_agent_id"),"""
new = """                rs.getString("target_agent_id"),"""
assert old in s, "F: 未命中"
p.write_text(s.replace(old, new))
PY
run_it "反证 F：JDBC 把「原始候选」读成「生效目标」（落库对了、读回来错了）" \
    agent-infrastructure 'MultiAgentPersistenceIT'
restore

# ── 反证 G：降级索引的谓词漏掉「排除存量行」──────────────────────────
#    预期：结构断言那条用例红（从未降级的历史行被算进降级集合）。
if docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
  docker exec "$PG_CONTAINER" psql -U agent -d agent_dev -q -c \
    "DROP INDEX IF EXISTS idx_agent_invocation_routed_fallback;
     CREATE INDEX idx_agent_invocation_routed_fallback
       ON agent_invocation (tenant_id, started_at DESC)
       WHERE requested_target_agent_id IS DISTINCT FROM target_agent_id;" >/dev/null 2>&1
  run_it "反证 G：降级索引的谓词没排除存量行（历史行被算成降级）" \
      agent-infrastructure 'MultiAgentPersistenceIT'
  # 恢复正确的索引（按迁移脚本原样重建）
  docker exec "$PG_CONTAINER" psql -U agent -d agent_dev -q -c \
    "DROP INDEX IF EXISTS idx_agent_invocation_routed_fallback;
     CREATE INDEX idx_agent_invocation_routed_fallback
       ON agent_invocation (tenant_id, started_at DESC)
       WHERE requested_target_agent_id IS NOT NULL
         AND requested_target_agent_id IS DISTINCT FROM target_agent_id;" >/dev/null 2>&1
else
  echo "================ 反证 G：跳过（未找到 PG 容器 $PG_CONTAINER） ================"
fi

echo "================ 恢复后复跑全绿 ================"
restore
mvn -o -B -pl agent-application -am \
    -Dtest='RouterDelegationTest' -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
  | grep -E "Tests run: [0-9]+, Failures" | sed 's/^/  /' | tail -6
mvn -o -B -pl agent-infrastructure -am verify -Dit.test='MultiAgentPersistenceIT' \
    -Dtest='NoSuchUnitTest' -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false 2>&1 \
  | grep -E "Tests run: [0-9]+, Failures" | sed 's/^/  /' | tail -6
