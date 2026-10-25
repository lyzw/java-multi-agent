#!/usr/bin/env bash
# 第二十四批 C24-1 反证脚本：把「编排绑定承载被引用行的内容指纹」逐处摘掉，
# 确认对应用例变红。
#
# ⚠️ 本脚本要证伪的是一件事：
#   **同一版本行被 DB 侧就地改写时，闭包指纹必须变** ——
#   否则「版本行不可变」就只是一句约定，没有任何机制在挡着它。
#
# ⚠️ 每处反证只动一个地方，然后跑相关测试：
#   被摘掉的那条能力，对应用例必须红；而**不该受影响**的用例必须保持绿
#   （否则说明反证改动的范围比预想的大 —— 结论不纯）。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

FP=agent-application/src/main/java/cn/studykid/multiagent/application/cap/ClosureFingerprint.java
RESOLVER=agent-application/src/main/java/cn/studykid/multiagent/application/cap/PermissionResolver.java
CACHE=agent-application/src/main/java/cn/studykid/multiagent/application/cap/CachingClosureResolver.java
BAK=/tmp/c24_1_counterproof
mkdir -p "$BAK"
cp "$FP" "$BAK/fp.bak"
cp "$RESOLVER" "$BAK/resolver.bak"
cp "$CACHE" "$BAK/cache.bak"
restore() {
  cp "$BAK/fp.bak" "$FP"
  cp "$BAK/resolver.bak" "$RESOLVER"
  cp "$BAK/cache.bak" "$CACHE"
}

run_case() {
  local label="$1" module="$2" tests="$3"
  echo "================ ${label} ================"
  mvn -o -B -pl "$module" -am -Dtest="$tests" \
      -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
    | grep -E "^\[ERROR\]   [A-Za-z]+(Test|IT)|Tests run: [0-9]+, Failures" \
    | sed 's/^/  /' | tail -14
  echo
}

restore

# ── 反证 A：闭包指纹不承载被引用行的内容指纹（= 修复前的状态）────────
#    预期：核心用例红（同一版本行被就地改写却指纹不变）。
python3 - "$FP" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (canonical.orchestration().hasReferencedRows()) {
            ObjectNode referenced = orchestration.putObject("referencedContent");
            referenced.put("version", REFERENCED_CONTENT_BINDING_VERSION);
            // ⚠️ 段内必须写成显式 null（putNullable），不能省略字段 ——
            //    省略会让「引用了但指纹没解析出来」与「压根没引用」算出同一个哈希。
            putNullable(referenced, "routingPolicyFingerprint",
                    canonical.orchestration().routingPolicyFingerprint());
            putNullable(referenced, "resultContractFingerprint",
                    canonical.orchestration().resultContractFingerprint());
        }"""
new = """        // 反证 A：不承载被引用行的内容指纹（= 修复前的状态）"""
assert old in s, "A: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 A：闭包指纹不承载被引用行的内容指纹（= 修复前：就地改写不可察）" \
    agent-application 'ReferencedContentFingerprintTest'
restore

# ── 反证 B：缓存不检查被引用行的新鲜度（防线被缓存架空）──────────────
#    预期：缓存那条用例红（缓存继续供出旧闭包 → 指纹不变）。
python3 - "$CACHE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        // ── 被引用行（输出契约 / 路由策略）的内容指纹（第二十四批 · A4）──
        if (!isReferencedContentFresh(tenantId, entry)) {
            return false;
        }
        return true;"""
new = """        // 反证 B：缓存不检查被引用行的新鲜度
        return true;"""
assert old in s, "B: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 B：缓存不查被引用行的新鲜度（第二道防线被缓存架空）" \
    agent-application 'ReferencedContentFingerprintTest'
restore

# ── 反证 C：被引用行解析不出时不再 fail-closed ───────────────────────
#    预期：解析不出那条用例红（悄悄放弃防线）。
python3 - "$RESOLVER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (hasPolicy && policyFingerprint == null) {
            throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                    "被引用的路由策略解析不出（无法承载其内容指纹）: ref=" + policyRef);
        }
        if (hasContract && contractFingerprint == null) {
            throw new ClosureResolutionException(ReasonCode.RESOURCE_UNKNOWN,
                    "被引用的输出契约解析不出（无法承载其内容指纹）: ref=" + contractRef);
        }"""
new = """        // 反证 C：解析不出也不再拒绝（悄悄放弃这道防线）"""
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 C：被引用行解析不出却不拒绝（防线看起来在、其实不在）" \
    agent-application 'ReferencedContentFingerprintTest'
restore

# ── 反证 D：解析能力未装配时不再 fail-closed ─────────────────────────
#    预期：未装配那条用例红。
python3 - "$RESOLVER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (referencedContent == null) {
            // ⚠️ 解析能力未装配 → fail-closed（与发布期「校验器未装配即拒绝」同口径）
            throw new ClosureResolutionException(ReasonCode.ORCHESTRATION_INVALID,
                    "被引用行内容指纹解析能力未装配，拒绝构建闭包: agent="
                            + orchestration.mode() + ", policyRef=" + policyRef
                            + ", contractRef=" + contractRef);
        }"""
new = """        // 反证 D：解析能力未装配也不再拒绝"""
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 D：解析能力未装配却不拒绝（静默放弃防线）" \
    agent-application 'ReferencedContentFingerprintTest'
restore

# ── 反证 E：缓存的解析器未装配时仍供出命中（缓存层 fail-open）─────────
#    预期：缓存那条用例红。
python3 - "$CACHE" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """        if (referencedContent == null) {
            log.warn("被引用行内容指纹解析能力未装配，含被引用行的闭包缓存一律作废: "
                    + "tenant={}, agent={}", tenantId, entry.agentId());
            return false;
        }"""
new = """        // 反证 E：没有解析器也认为内容没变（缓存层 fail-open）"""
assert old in s, "E: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 E：缓存没解析器却认为内容没变（缓存层 fail-open）" \
    agent-application 'ReferencedContentFingerprintTest'
restore

# ── 反证 F：被引用行段无条件写入（连没有引用的闭包也写）──────────────
#    预期：「没有引用行时指纹不变」那条用例红
#    （无谓地让不引用任何契约/策略的在途 Run 集体失效 —— 那不是 fail-closed）。
python3 - "$FP" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "        if (canonical.orchestration().hasReferencedRows()) {"
new = "        if (true) {"
assert old in s, "F: 未命中"
p.write_text(s.replace(old, new))
PY
run_case "反证 F：被引用行段无条件写入（不引用任何东西的 Run 也被无故判失效）" \
    agent-application 'ReferencedContentFingerprintTest'
restore

echo "================ 恢复后复跑全绿 ================"
restore
mvn -o -B -pl agent-application -am \
    -Dtest='ReferencedContentFingerprintTest,ClosureFingerprintTest,PermissionResolverMultiLevelTest,CachingClosureResolverTest' \
    -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
  | grep -E "Tests run: [0-9]+, Failures" | sed 's/^/  /' | tail -8
