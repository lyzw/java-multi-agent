#!/usr/bin/env bash
# 第二十批 C20-1 反证脚本：把「委派工具 Schema 装配」逐处摘掉，
# 确认对应用例变红。
#
# ⚠️ 本脚本要证伪的是一件事：
#   **模型必须看得见委派工具** —— 否则运行期模型**物理上不可能**发起委派，
#   多级 Agent 的守卫、预算、账本、契约全都齐备，却**没有触发点**。
#
# ⚠️ 每处反证只动一个地方，然后跑相关测试：
#   被摘掉的那条能力，对应用例必须红；而**不该受影响**的用例必须保持绿
#   （否则说明反证改动的范围比预想的大 —— 结论不纯）。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

PROVIDER=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/WorkerChildAgentModelConfigProvider.java
ASSEMBLER=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/DelegationToolSchemaAssembler.java
SCHEMA=agent-core/src/main/java/cn/studykid/multiagent/core/orchestration/DelegationToolSchema.java
EXECUTOR=agent-application/src/main/java/cn/studykid/multiagent/application/orchestration/DelegationExecutor.java
BOOTSTRAP=platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/WorkerExecutionConfig.java
BAK=/tmp/c20_1_counterproof
mkdir -p "$BAK"
cp "$PROVIDER" "$BAK/provider.bak"
cp "$ASSEMBLER" "$BAK/assembler.bak"
cp "$SCHEMA" "$BAK/schema.bak"
cp "$EXECUTOR" "$BAK/executor.bak"
cp "$BOOTSTRAP" "$BAK/bootstrap.bak"
restore() {
  cp "$BAK/provider.bak" "$PROVIDER"
  cp "$BAK/assembler.bak" "$ASSEMBLER"
  cp "$BAK/schema.bak" "$SCHEMA"
  cp "$BAK/executor.bak" "$EXECUTOR"
  cp "$BAK/bootstrap.bak" "$BOOTSTRAP"
}

run_case() {
  local label="$1" module="$2" tests="$3"
  echo "================ ${label} ================"
  mvn -o -B -pl "$module" -am -Dtest="$tests" \
      -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
    | grep -E "^\[ERROR\]   [A-Za-z]+Test|Tests run: [0-9]+, Failures" \
    | sed 's/^/  /' | tail -14
  echo
}

restore

# ── 反证 A：整个委派工具装配摘掉（= 修复前的状态）────────────────
#    预期：装配类用例红（模型看不见委派工具），其余保持绿。
python3 - "$PROVIDER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        schemas.putAll(delegationToolSchema(tenantId, agentId, agentVersion));
        return schemas;"""
new = """        return schemas;"""
assert old in s, "A: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 A：委派工具装配整体摘掉（= 修复前：模型看不见委派工具）" \
    agent-worker 'WorkerChildAgentModelConfigProviderTest'
restore

# ── 反证 B：LEAF 也给委派工具（无委派能力却发枪）──────────────────
#    预期：LEAF 那条用例红（「不得给无委派能力的 Agent 发委派工具」）。
python3 - "$SCHEMA" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (orchestration == null || orchestration.isLeaf() || !orchestration.hasChildren()) {"""
new = """        if (false) {"""
assert old in s, "B: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 B：LEAF 也装配委派工具（无委派能力却发枪）" \
    agent-worker 'WorkerChildAgentModelConfigProviderTest'
restore

# ── 反证 C：目标不枚举（Schema 不限定目标范围）────────────────────
#    预期：目标枚举那条用例红。
python3 - "$SCHEMA" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        target.put("enum", List.copyOf(allowedTargets));"""
new = """        target.put("enum", List.of("anything"));"""
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 C：目标不枚举（Schema 不限定目标范围）" \
    agent-worker 'WorkerChildAgentModelConfigProviderTest'
restore

# ── 反证 D：additionalProperties 不设 false（与闸门「多传即拒」不一致）──
#    预期：参数口径那条用例红（协议两套语义）。
python3 - "$SCHEMA" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        schema.put("additionalProperties", false);"""
new = """        schema.put("additionalProperties", true);"""
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 D：additionalProperties 不设 false（与闸门口径不一致）" \
    agent-core 'DelegationToolSchemaTest'
restore

# ── 反证 E：工具名两处各写一遍（漂移的口子）────────────────────────
#    预期：application 层「工具名必须相同」那条用例红。
python3 - "$EXECUTOR" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """    public static final String DELEGATION_TOOL_NAME = DelegationToolSchema.TOOL_NAME;"""
new = """    public static final String DELEGATION_TOOL_NAME = "delegate_to_agent_v2";"""
assert old in s, "E: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 E：工具名两处各写一遍（模型可见的与闸门认得的漂移）" \
    agent-application 'DelegationToolSchemaTest'
restore

# ── 反证 F：Schema 暴露目标版本（模型可要求漂移到别的 release）──────
#    预期：版本不得出现那条用例红。
python3 - "$SCHEMA" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        target.put("enum", List.copyOf(allowedTargets));"""
new = """        target.put("enum", List.copyOf(allowedTargets));
        target.put("releaseVersion", 1);"""
assert old in s, "F: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 F：Schema 暴露目标版本（模型可要求漂移 release）" \
    agent-core 'DelegationToolSchemaTest'
restore

# ── 反证 G：根路径不装配委派工具（根→子这一跳没有触发点）──────────
#    预期：根路径「看得见委派工具」那条用例红。
python3 - "$BOOTSTRAP" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """            java.util.Map<String, Object> delegation =
                    delegationToolSchema(tenantId, runId);"""
new = """            java.util.Map<String, Object> delegation = java.util.Map.of();"""
assert old in s, "G: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 G：根路径不装配委派工具（根→子没有触发点）" \
    platform-bootstrap 'RootDelegationToolSchemaWiringTest'
restore
# ── 反证 H：装配器读不到 Agent 定义也照样给委派工具（fail-closed 被拆）──
#    预期：装配器「Agent 定义不存在 → 不装配」那条用例红。
#    ⚠️ 改法要点：**必须真的给出委派工具**，
#    构造一个「有子引用」的编排来绕开 leaf 判定 ——
#    否则改写后仍然「不给委派工具」，用例照样绿（反证无效）。
python3 - "$ASSEMBLER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (def.isEmpty()) {
            return Map.of();
        }"""
new = """        if (def.isEmpty()) {
            // ⚠️ 反证：读不到定义也照样给一把委派枪（fail-closed 被拆）
            return Map.of(DelegationToolSchema.TOOL_NAME, Map.of(
                    "name", DelegationToolSchema.TOOL_NAME,
                    "description", "反证用",
                    "inputSchema", Map.of("type", "object")));
        }"""
assert old in s, "H: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 H：装配器读不到定义也照样给委派工具（fail-closed 被拆）" \
    agent-worker 'DelegationToolSchemaAssemblerTest'
restore

echo "================ 恢复后复跑 ================"
run_case "恢复修复后（应全绿）" agent-worker 'WorkerChildAgentModelConfigProviderTest'
run_case "恢复修复后（应全绿）" agent-worker 'DelegationToolSchemaAssemblerTest'
run_case "恢复修复后（应全绿）" agent-core 'DelegationToolSchemaTest'
run_case "恢复修复后（应全绿）" agent-application 'DelegationToolSchemaTest'
run_case "恢复修复后（应全绿）" platform-bootstrap 'RootDelegationToolSchemaWiringTest'
