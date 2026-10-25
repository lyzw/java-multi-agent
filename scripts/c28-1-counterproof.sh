#!/usr/bin/env bash
# 第二十八批 C28-1 反证脚本：把 MQ 拓扑的各个「配置化」逐处摘掉，
# 确认对应用例变红。
#
# ⚠️ 本脚本要证伪的是一件事：
#   **MQ 拓扑必须由配置驱动，且生产拓扑必须完整（含绑定）、测试不得污染生产队列**。
#   三者此前全是「假的」或「缺的」：
#     · 配置项定义了却没人读（agent.mq.exchange 等），发送器硬编码；
#     · 生产代码**从未声明绑定** —— 没有绑定就没有路由，消息无处可去，
#       而集成测试自己补了绑定，于是「测试替生产兜底」，缺陷只在新环境暴露；
#     · 集成测试直接用生产队列名，每次跑都往生产队列写消息。
#
# ⚠️ 每处反证只动一个地方，然后跑相关测试：
#   被摘掉的那条不变量，对应用例必须红；而**不该受影响**的用例必须保持绿
#   （否则说明反证改动的范围比预想的大 —— 结论不纯）。
#
# ⚠️ **判定标准**：每处反证都必须让用例**真的变红，且恰好红在预期的那几条**。
#   结论不变（仍全绿）= 无效反证；红了但**超出预期条数**同样判无效
#   （那说明断言之间的耦合比预期紧）。
set -u
cd /Users/zhouwei/code-workspace/ai-tech/java-multi-agent/platform
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"

export IT_PG_URL=jdbc:postgresql://127.0.0.1:5433/agent_dev
export IT_PG_USER=app_agent
export IT_PG_PASSWORD=app_agent_pw
export IT_PG_ADMIN_USER=agent
export IT_PG_ADMIN_PASSWORD=agent_dev_pw
export RABBITMQ_USER=agent
export RABBITMQ_PASSWORD=agent_dev_pw
export RABBITMQ_VHOST=agent

SENDER=agent-infrastructure/src/main/java/cn/studykid/multiagent/infrastructure/messaging/RabbitMessageSender.java
CFG=platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/WorkerLoopConfig.java
E2E=agent-infrastructure/src/test/java/cn/studykid/multiagent/infrastructure/messaging/RealBrokerEndToEndIT.java

BAK=/tmp/c28_1_counterproof
mkdir -p "$BAK"
cp "$SENDER" "$BAK/sender.bak"
cp "$CFG" "$BAK/cfg.bak"
cp "$E2E" "$BAK/e2e.bak"

restore() {
  cp "$BAK/sender.bak" "$SENDER"
  cp "$BAK/cfg.bak" "$CFG"
  cp "$BAK/e2e.bak" "$E2E"
}

FAILED=0

# ⚠️ 只数**单条用例**的失败行（surefire 还会额外打一行类汇总，
#    那行带 `<<< FAILURE!` 但不是一条用例 —— 算进去会让计数翻倍）。
# ⚠️ 类名是**全限定名**（带点），正则里必须允许点。
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
  local log=/tmp/c28_1_case.log
  mvn -o -B -pl agent-infrastructure,platform-bootstrap -am \
      -Dtest='RabbitMessageSenderTest,MqTopologyWiringTest' \
      -Dsurefire.failIfNoSpecifiedTests=false test > "$log" 2>&1
  report "$label" "$expect_red" "$log"
}

run_it() {
  local label="$1" expect_red="$2"
  local log=/tmp/c28_1_it.log
  mvn -o -B -pl agent-infrastructure -am verify \
      -Dit.test='RealBrokerEndToEndIT' -Dtest='NoSuchUnitTest' \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Dfailsafe.failIfNoSpecifiedTests=false > "$log" 2>&1
  report "$label" "$expect_red" "$log"
}

restore

# ── 反证 A：发送器忽略构造参数（回到硬编码默认值）─────────────────────
#    预期：那条「发布到构造传入的拓扑」用例红（1 条）。
python3 - "$SENDER" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "        template.convertAndSend(exchange, routingKey, payload, m -> {"
new = ("        // 反证 A：忽略构造参数，回到硬编码默认值\n"
       "        template.convertAndSend(DEFAULT_EXCHANGE, DEFAULT_ROUTING_KEY, payload, m -> {")
assert old in s, "A: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_unit "反证 A：发送器忽略构造参数（回到硬编码默认值）" 1
restore

# ── 反证 B：绑定 Bean 被删（生产拓扑缺绑定 = 消息无处可去）────────────
#    预期：绑定存在性与「路由键一致」两条用例红（2 条）。
python3 - "$CFG" <<'PY'
import sys, pathlib, re
p = pathlib.Path(sys.argv[1])
s = p.read_text()
pattern = re.compile(
    r"[ \t]*@Bean\n[ \t]*public Binding runExecuteBinding\(.*?\n[ \t]*\}\n", re.DOTALL)
s2, n = pattern.subn("", s)
assert n == 1, "B: 未命中"
p.write_text(s2)
PY
run_unit "反证 B：绑定 Bean 被删（生产拓扑缺绑定 = 消息无处可去）" 2
restore

# ── 反证 C：发布交换机忽略配置（agent.mq.exchange 变回摆设）──────────
#    预期：2 条红 —— 「交换机必须用配置值」+「绑定必须指向发布交换机」。
#    ⚠️ 这**不是**误伤，而是**正确的依赖传播**：交换机是绑定的上游，
#    上游换名字，下游「绑的是不是那个交换机」当然也要红。
python3 - "$CFG" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "        return new TopicExchange(exchange, true, false);"
new = "        return new TopicExchange(\"agent.run.dispatch\", true, false);"
assert old in s, "C: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_unit "反证 C：发布交换机忽略配置（agent.mq.exchange 变回摆设）" 2
restore

# ── 反证 D：绑定的路由键与发送器不一致（失配即消息无处可去）────────────
#    预期：那条「路由键必须与发送器一致」用例红（1 条）。
python3 - "$CFG" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "                .with(routingKey);"
new = "                .with(\"mismatched.routing.key\");"
assert old in s, "D: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_unit "反证 D：绑定的路由键与发送器不一致（失配即消息无处可去）" 1
restore

# ── 反证 E：执行队列的死信参数忽略配置 ────────────────────────────────
#    预期：那条「执行队列必须用配置值」用例红（1 条）。
python3 - "$CFG" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = """                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(deadLetterRoutingKey)"""
new = """                .deadLetterExchange("agent.run.dlx")
                .deadLetterRoutingKey("agent.run.dead")"""
assert old in s, "E: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_unit "反证 E：执行队列的死信参数忽略配置" 1
restore

# ── 反证 F：发送器的发布目标指向生产拓扑（消息直达生产队列）────────────
#    预期：2 条红 —— 「发送器实际目标不得是生产拓扑」+「消息必须落在自己的队列」
#    （后者同时证明：消息真的跑到了生产队列，本片自己的队列收不到）。
python3 - "$E2E" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old = "        sender = new RabbitMessageSender(template, EXCHANGE, ROUTING_KEY);"
new = ("        // 反证 F：发布目标指向生产拓扑\n"
       "        sender = new RabbitMessageSender(template, PRODUCTION_EXCHANGE,"
       " PRODUCTION_ROUTING_KEY);")
assert old in s, "F: 未命中"
p.write_text(s.replace(old, new, 1))
PY
run_it "反证 F：发送器的发布目标指向生产拓扑（消息直达生产队列）" 2
restore

# ── 反证 G：集成测试把整套拓扑改回生产名（污染生产队列）────────────────
#    预期：2 条红 —— 「本片拓扑名必须 ≠ 生产名」+「发送器实际目标不得是生产拓扑」。
#    ⚠️ 这里把**交换机/死信/队列/路由键全部**改成生产值，
#       这样声明参数与既有生产队列完全一致 → broker 不会拒绝，
#    ��退化就以「安静地污染生产队列」的方式发生 —— 正是要防的那一幕。
python3 - "$E2E" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text()
pairs = [
    ('    private static final String EXCHANGE = "agent.run.dispatch.it";',
     '    private static final String EXCHANGE = PRODUCTION_EXCHANGE;'),
    ('    private static final String QUEUE = "agent.run.execute.it";',
     '    private static final String QUEUE = PRODUCTION_QUEUE;'),
    ('    private static final String ROUTING_KEY = "agent.run.execute.it";',
     '    private static final String ROUTING_KEY = PRODUCTION_ROUTING_KEY;'),
    ('    private static final String DLX = "agent.run.dlx.it";',
     '    private static final String DLX = "agent.run.dlx";'),
    ('    private static final String DEAD_QUEUE = "agent.run.dead.it";',
     '    private static final String DEAD_QUEUE = "agent.run.dead";'),
]
for old, new in pairs:
    assert old in s, "G: 未命中 " + old
    s = s.replace(old, new, 1)
p.write_text(s)
PY
run_it "反证 G：集成测试把整套拓扑改回生产名（污染生产队列）" 2
restore

echo "================ 恢复后复跑全绿 ================"
restore
run_unit "恢复后复跑单测全绿" 0
run_it "恢复后复跑 IT 全绿" 0

if [ "$FAILED" -ne 0 ]; then
  echo "⛔ 存在无效反证（结论未变 / 编译失败 / 范围越界）"
  exit 1
fi
echo "✅ 全部反证有效"
