#!/usr/bin/env bash
# 第二十一批 C21-1 反证脚本：把「根 Agent 按 Run 快照读真实定义」逐处摘掉，
# 确认对应用例变红。
#
# ⚠️ 本脚本要证伪的是一件事：
#   **根 Agent 的模型配置必须来自它自己的定义（按 Run 快照里的固定 release）** ——
#   否则根 Agent 实际上**没有任何普通工具可用**，
#   「多级 Agent 可用」这句话在根这一层是假的（根只能委派、不能自己干活）。
#
# ⚠️ 每处反证只动一个地方，然后跑相关测试：
#   被摘掉的那条能力，对应用例必须红；而**不该受影响**的用例必须保持绿
#   （否则说明反证改动的范围比预想的大 —— 结论不纯）。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

ASSEMBLER=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/AgentModelConfigAssembler.java
ROOT=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RootAgentModelConfigProvider.java
CHILD=agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/WorkerChildAgentModelConfigProvider.java
BOOTSTRAP=platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/WorkerExecutionConfig.java
BAK=/tmp/c21_1_counterproof
mkdir -p "$BAK"
cp "$ASSEMBLER" "$BAK/assembler.bak"
cp "$ROOT" "$BAK/root.bak"
cp "$CHILD" "$BAK/child.bak"
cp "$BOOTSTRAP" "$BAK/bootstrap.bak"
restore() {
  cp "$BAK/assembler.bak" "$ASSEMBLER"
  cp "$BAK/root.bak" "$ROOT"
  cp "$BAK/child.bak" "$CHILD"
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

# ── 反证 A：根路径整体回落占位模型（= 修复前的状态）────────────────
#    预期：根配置用例红（模型标识不是真实的 / 读不到时不拒绝）。
python3 - "$ROOT" <<'PY'
import sys, pathlib
root = pathlib.Path(sys.argv[1])

s = root.read_text()
old = """        Optional<AgentModelConfigAssembler.AgentModelSettings> settings =
                assembler.assemble(tenantId, s.agentId(), s.agentVersion());"""
new = """        Optional<AgentModelConfigAssembler.AgentModelSettings> settings =
                Optional.of(new AgentModelConfigAssembler.AgentModelSettings(
                        "fake-model", "你是初中数学辅导助手。", 0.7d, 2048,
                        java.time.Duration.ofSeconds(30), java.util.Map.of()));"""
assert old in s, "A: 未命中 root"
root.write_text(s.replace(old, new))
PY
run_case "反证 A：根路径回落 fake-model 占位（= 修复前：根只能委派、不能自己干活）" \
    agent-worker 'RootAgentModelConfigProviderTest'
run_case "反证 A（接线层）：根路径回落占位" \
    platform-bootstrap 'RootDelegationToolSchemaWiringTest'
restore

# ── 反证 B：根路径按「当前已发布版本」而非快照里的固定 release 取定义 ──
#    预期：钉死「按固定 release」的用例红（在途 Run 会漂移到新版本）。
python3 - "$ROOT" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                assembler.assemble(tenantId, s.agentId(), s.agentVersion());"""
new = """                assembler.assemble(tenantId, s.agentId(), s.agentVersion() + 1);"""
assert old in s, "B: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 B：根路径漂移到别的 release（违反「子/根固定 release」）" \
    agent-worker 'RootAgentModelConfigProviderTest'
restore

# ── 反证 C：读不到定义时回落占位（不拒绝执行）──────────────────────
#    预期：「读不到即拒绝」那条用例红。
python3 - "$ROOT" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """            log.warn("根 Agent 模型配置不可用: agent={}, version={}",
                    s.agentId(), s.agentVersion());
            return Optional.empty();"""
new = """            log.warn("根 Agent 模型配置不可用: agent={}, version={}",
                    s.agentId(), s.agentVersion());
            return Optional.of(new RootAgentModelConfig(
                    s.agentId(), s.agentVersion(), "fake-model", "占位", 0.7d, 2048,
                    java.time.Duration.ofSeconds(30), java.util.Map.of()));"""
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 C：读不到定义却回落占位（fail-closed 被拆）" \
    agent-worker 'RootAgentModelConfigProviderTest'
restore

# ── 反证 D：根路径不装配委派工具（根→子没有触发点）─────────────────
#    预期：「根看得见委派工具」那条用例红。
python3 - "$ROOT" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        schemas.putAll(delegationToolSchema(tenantId, s.agentId(), s.agentVersion()));"""
new = """        // 反证 D：委派工具不装配"""
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 D：根路径不给委派工具（根→子这一跳没有触发点）" \
    agent-worker 'RootAgentModelConfigProviderTest'
restore

# ── 反证 E：LEAF 也给委派工具（无委派能力却发枪）────────────────────
#    预期：「LEAF 不给委派工具」那条用例红。
python3 - "$ROOT" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        return delegationSchemas.assemble(tenantId, agentId, agentVersion);"""
new = """        return cn.studykid.multiagent.core.orchestration.DelegationToolSchema
                .forOrchestration(cn.studykid.multiagent.application.orchestration
                        .AgentOrchestrationParser.parse(
                                "{\\"mode\\":\\"SEQUENTIAL\\",\\"childAgentRefs\\":[{\\"id\\":\\"c\\",\\"version\\":1}]}"))
                .map(cn.studykid.multiagent.core.orchestration.DelegationToolSchema::render)
                .orElseGet(java.util.Map::of);"""
assert old in s, "E: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 E：LEAF 也给委派工具（不该给的不给）" \
    agent-worker 'RootAgentModelConfigProviderTest'
restore

# ── 反证 F：子路径不再共用装配器（各写一份 → 两套语义）─────────────
#    预期：子路径的用例仍绿（子路径行为不变），
#    但这条反证的意义是**证明共用是可选的**——
#    若子路径改回自己解析，根/子就会再次漂移（结构上无法被用例捕获，
#    故这里只做**行为不变性**的验证，并记录结论）。
python3 - "$CHILD" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        Optional<AgentModelConfigAssembler.AgentModelSettings> settings =
                assembler().assemble(tenantId, agentId, agentVersion);"""
new = """        Optional<AgentModelConfigAssembler.AgentModelSettings> settings =
                Optional.of(new AgentModelConfigAssembler.AgentModelSettings(
                        "sub-model", "子提示词", 0.2d, 1024,
                        java.time.Duration.ofSeconds(30), java.util.Map.of()));"""
assert old in s, "F: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 F：子路径自写一份解析（验证子路径用例仍绿 = 行为可独立回退）" \
    agent-worker 'WorkerChildAgentModelConfigProviderTest'
restore

# ── 反证 G：缺失模型标识时也放行（不猜模型的反面）──────────────────
#    预期：「缺模型标识 → 拒绝」那条用例红。
python3 - "$ASSEMBLER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        String modelId = text(root.get("modelRef"), root.get("modelId"));
        if (modelId == null) {"""
new = """        String modelId = text(root.get("modelRef"), root.get("modelId"));
        if (modelId == null) {
            modelId = "fake-model";
        }
        if (false) {"""
assert old in s, "G: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 G：缺模型标识也放行（随便挑一个模型）" \
    agent-worker 'AgentModelConfigAssemblerTest'
restore

# ── 反证 H：工具表不来自自己的闭包（工具授权面被放大）──────────────
#    预期：「工具表来自自己闭包」那条用例红。
python3 - "$ASSEMBLER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        for (EffectiveClosure.ToolBinding binding : closure.tools()) {"""
new = """        // ⚠️ 反证 H：**无视闭包**，硬塞一个工具 ——
        //    「工具表必须来自该 Agent 自己的闭包」这条约束被破坏。
        schemas.put("smuggled-tool", Map.of("name", "smuggled-tool"));
        for (EffectiveClosure.ToolBinding binding : closure.tools()) {"""
assert old in s, "H: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 H：工具表不来自自己的闭包（工具授权面被放大）" \
    agent-worker 'AgentModelConfigAssemblerTest'
restore

echo "================ 恢复后复跑全绿 ================"
restore
mvn -o -B -pl agent-worker,platform-bootstrap -am \
    -Dtest='AgentModelConfigAssemblerTest,RootAgentModelConfigProviderTest,WorkerChildAgentModelConfigProviderTest,RootDelegationToolSchemaWiringTest' \
    -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
  | grep -E "Tests run: [0-9]+, Failures" | sed 's/^/  /' | tail -8
