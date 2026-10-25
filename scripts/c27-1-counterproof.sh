#!/usr/bin/env bash
# 第二十七批 C27-1 反证脚本：把多级 Agent 三张表的迁移脚本逐处改坏，
# 确认对应用例变红。
#
# ⚠️ 本脚本要证伪的是一件事：
#   **迁移脚本的结构退化必须能被自动化发现**。
#   迁移脚本只在**部署新环境**时执行 —— 脚本被改坏时，已部署环境察觉不到
#   （库里已经是旧结构），现有行为断言也照样全绿（它们跑在早就建好的库上）。
#   于是「脚本退化」会一路潜行到**新环境上线那一刻**才爆。
#   本脚本要证明：现在这类退化**在构建期就会红**。
#
# ⚠️ 每处反证只动一个地方，然后跑相关测试：
#   被改坏的那条不变量，对应用例必须红；而**不该受影响**的用例必须保持绿
#   （否则说明反证改动的范围比预想的大 —— 结论不纯）。
#
# ⚠️ **判定标准**：每处反证都必须让用例**真的变红，且恰好红在预期的那几条**。
#   若改动后结论不变（仍然全绿），说明这条反证**无效** —— 它没咬到任何东西，
#   此时脚本会明确报「⛔ 反证无效」并以非零码退出。
#   编译失败同样判为无效 —— 「编译不过」不是「用例红」。
#   红了但**超出预期条数**同样判为无效 —— 那说明断言之间的耦合比预期紧。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

MIG=agent-infrastructure/src/main/resources/db/migration
V19=$MIG/V19__multi_agent_orchestration.sql
V23=$MIG/V23__result_contract_text_id.sql
V25=$MIG/V25__agent_invocation_requested_target.sql
V26=$MIG/V26__contract_policy_lifecycle_state.sql

BAK=/tmp/c27_1_counterproof
mkdir -p "$BAK"
cp "$V19" "$BAK/V19.bak"
cp "$V23" "$BAK/V23.bak"
cp "$V25" "$BAK/V25.bak"
cp "$V26" "$BAK/V26.bak"

restore() {
  cp "$BAK/V19.bak" "$V19"
  cp "$BAK/V23.bak" "$V23"
  cp "$BAK/V25.bak" "$V25"
  cp "$BAK/V26.bak" "$V26"
}

FAILED=0

# ⚠️ 只数**单条用例**的失败行：
#   surefire 还会额外打一行「Tests run: N, Failures: M ... <<< FAILURE!」的类汇总，
#   那行带 `<<< FAILURE!` 但不是一条用例 —— 把它算进去会让计数翻倍。
# ⚠️ 类名是**全限定名**（带点），正则里必须允许点，否则一条都数不到。
count_red() {
  grep -cE "^\[ERROR\] +[A-Za-z0-9_.]+ -- Time elapsed" "$1"
}

report() {
  local label="$1" expect_red="$2" log="$3"
  echo "================ ${label} ================"
  if grep -q "COMPILATION ERROR\|Failed to execute goal .*compile" "$log"; then
    echo "  ⛔ 反证无效：编译失败（编译不过不是用例红，它证明不了任何结论）"
    FAILED=1
    return
  fi
  grep -E "^\[ERROR\] +[A-Za-z0-9_.]+ -- Time elapsed" "$log" \
    | sed 's/^/  /' | head -12
  local red
  red=$(count_red "$log")
  if [ "$red" -eq "$expect_red" ]; then
    echo "  ✅ 结论已变化：恰好 ${red} 条红（预期 ${expect_red} 条，反证精准）"
  elif [ "$red" -gt "$expect_red" ]; then
    echo "  ⛔ 反证越界：红了 ${red} 条，预期 ${expect_red} 条 —— 误伤了不该受影响的用例"
    FAILED=1
  else
    echo "  ⛔ 反证无效：红了 ${red} 条，预期 ${expect_red} 条（结论未按预期变化）"
    FAILED=1
  fi
  echo
}

run_unit() {
  local label="$1" expect_red="$2"
  local log=/tmp/c27_1_case.log
  mvn -o -B -pl agent-infrastructure -am -Dtest='MultiAgentSchemaStructureTest' \
      -Dsurefire.failIfNoSpecifiedTests=false test > "$log" 2>&1
  report "$label" "$expect_red" "$log"
}

run_it() {
  local label="$1" expect_red="$2"
  local log=/tmp/c27_1_it.log
  export IT_PG_URL=jdbc:postgresql://127.0.0.1:5433/agent_dev
  export IT_PG_USER=app_agent
  export IT_PG_PASSWORD=app_agent_pw
  export IT_PG_ADMIN_USER=agent
  export IT_PG_ADMIN_PASSWORD=agent_dev_pw
  mvn -o -B -pl agent-infrastructure -am verify \
      -Dit.test='MultiAgentSchemaIT' -Dtest='NoSuchUnitTest' \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Dfailsafe.failIfNoSpecifiedTests=false > "$log" 2>&1
  report "$label" "$expect_red" "$log"
}

# ── 反证 A：状态集合拼错（封闭集合被打破）─────────────────────────────
#    预期：状态集合那条用例红（1 条）；其余不受影响。
python3 - "$V19" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'"
new = "'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED'"
assert old in s, "A: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_unit "反证 A：状态集合拼错（封闭集合被打破）" 1
restore

# ── 反证 B：世代令牌约束被删（MA-T12 的地基消失）──────────────────────
#    预期：世代令牌那条用例红（1 条）。
python3 - "$V19" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "    CONSTRAINT agent_invocation_fencing_ck CHECK (fencing_token > 0),\n"
assert old in s, "B: 未命中"
p.write_text(s.replace(old, "", 1))
PY
run_unit "反证 B：世代令牌约束被删（MA-T12 的地基消失）" 1
restore

# ── 反证 C：契约 id 改回 UUID（V23 修过的缺陷静默回归）────────────────
#    预期：契约列那条用例红（1 条）。
#    ⚠️ 这条反证也证明了**两层的分工**：真实库里的类型没变，
#       所以 IT 层那条断言仍然绿 —— 库层要等迁移真的执行才会变。
python3 - "$V23" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "ALTER COLUMN contract_id TYPE TEXT USING contract_id::text;"
new = "ALTER COLUMN contract_id TYPE UUID USING contract_id::uuid;"
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_unit "反证 C：契约 id 改回 UUID（V23 修过的缺陷静默回归）" 1
restore

# ── 反证 D：已发布契约行的不可变触发器被删（V26 补的那条被摘掉）──────
#    预期：不可变触发器那条用例红（1 条）。
python3 - "$V26" <<'PY'
import sys, pathlib, re
p = pathlib.Path(sys.argv[1])
s = p.read_text()
# ⚠️ 用正则删整条语句：DO 块里的语句被拆成多段字符串、缩进不固定，
#    逐字锚定很容易对不上而变成「什么都没改」。
pattern = re.compile(
    r"[ \t]*EXECUTE format\('CREATE TRIGGER trg_%I_immutable BEFORE UPDATE ON %I '\s*\n"
    r"[ \t]*'FOR EACH ROW EXECUTE FUNCTION forbid_published_def_mutation\(\)', t, t\);\n")
s2, n = pattern.subn("", s)
assert n == 1, "D: 未命中"
p.write_text(s2)
PY
run_unit "反证 D：已发布契约行的不可变触发器被删" 1
restore

# ── 反证 E：FORCE RLS 被摘掉（表 owner 重新能跨租户读）─────────────────
#    预期：RLS 强制那条用例红（1 条）；策略那条仍绿（策略确实还在）。
python3 - "$V19" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "            EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);\n"
assert old in s, "E: 未命中"
p.write_text(s.replace(old, "", 1))
PY
run_unit "反证 E：FORCE RLS 被摘掉（表 owner 重新能跨租户读）" 1
restore

# ── 反证 F：主键去掉 tenant_id（跨租户寻址失去隔离维度）────────────────
#    预期：主键两条用例红（tenant 维度那条 + 精确形状那条）。
python3 - "$V19" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "    CONSTRAINT agent_invocation_pk PRIMARY KEY (tenant_id, invocation_id),"
new = "    CONSTRAINT agent_invocation_pk PRIMARY KEY (invocation_id),"
assert old in s, "F: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_unit "反证 F：主键去掉 tenant_id（跨租户寻址失去隔离维度）" 2
restore

# ── 反证 G：降级索引的谓词被去掉（存量行被算成「发生过降级」）──────────
#    预期：索引谓词那条用例红（1 条）。
python3 - "$V25" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """    WHERE requested_target_agent_id IS NOT NULL
      AND requested_target_agent_id IS DISTINCT FROM target_agent_id;"""
new = """    WHERE requested_target_agent_id IS DISTINCT FROM target_agent_id;"""
assert old in s, "G: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_unit "反证 G：降级索引的谓词被去掉（存量行被算成「发生过降级」）" 1
restore

# ── 反证 H：IT 层 —— 真实库里把不可变触发器摘掉 ────────────────────────
#    预期：IT 的触发器那条用例红（1 条）。
#    ⚠️ 这一处动的是**真实库**（不是脚本），用来证明 IT 层真的咬得住：
#    脚本层只能证明「脚本说的是什么」，库层才能证明「库里长出来的是什么」。
#    ⚠️ 必须**当场恢复**：不可变触发器被摘掉会连累其他 IT
#    （第二十五批的 ContractPolicyLifecycleIT 就依赖它），
#    故用 trap 保证无论脚本怎么退出都会复原。
restore_trigger() {
  docker exec -i agent-pg17 psql -U agent -d agent_dev -v ON_ERROR_STOP=1 -q \
    < "$V26" >/dev/null 2>&1 || true
}
trap 'restore; restore_trigger' EXIT

docker exec agent-pg17 psql -U agent -d agent_dev -q -c \
  "DROP TRIGGER IF EXISTS trg_cap_result_contract_immutable ON cap_result_contract;" \
  >/dev/null 2>&1
run_it "反证 H：真实库里把不可变触发器摘掉（IT 层）" 1
restore_trigger

echo "================ 恢复后复跑全绿 ================"
restore
mvn -o -B -pl agent-infrastructure -am \
    -Dtest='MultiAgentSchemaStructureTest' \
    -Dsurefire.failIfNoSpecifiedTests=false test 2>&1 \
  | grep -E "Tests run: [0-9]+, Failures" | sed 's/^/  /' | tail -4
run_it "恢复后复跑 IT 全绿" 0

if [ "$FAILED" -ne 0 ]; then
  echo "⛔ 存在无效反证（结论未变 / 编译失败 / 范围越界）"
  exit 1
fi
echo "✅ 全部反证有效"
