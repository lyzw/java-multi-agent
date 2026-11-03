#!/usr/bin/env bash
#
# dev-it-db.sh —— 为集成测试建立**隔离数据库**
#
# ⚠️ **为什么需要它**（P4-D09 实测）：
# 本机开发库 `agent_dev` 被**正在运行的 PlatformApplication 实例**持续写入
# （用户在 IntelliJ 中调试运行），产生上千条僵尸 `run` 行（state='RUNNING'）与
# 未发布 outbox。这些行占满 `claimTasks` 的 `LIMIT 1000` 任务池 →
# **后来者报「任务未被领取」**（F-9 测试数据污染的经典形态）。
# 而 IT 与运行实例共用一个库时，**双方互相污染**：
# - IT 污染运行实例（影响用户调试）
# - 运行实例污染 IT（导致「莫名失败」，且失败现场指向错误方向）
#
# 正解：IT 用**自己的库**，与运行实例彻底隔离。
#
# 用法：
#   ./scripts/dev-it-db.sh            # 建库 + 应用结构快照 + 播种
#   IT_DB=agent_it_test ./scripts/dev-it-db.sh
#
# ⚠️ 幂等：重复执行不会重复插入，也不会清空既有数据。

set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-agent-pg17}"
PG_PORT="${PG_PORT:-5433}"
PG_DB="${IT_DB:-agent_it}"
PG_SUPERUSER="${PG_SUPERUSER:-agent}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# 结构唯一权威源（2026-10-27 收口：Flyway 迁移链已删除，改为终态快照）
SNAPSHOT="$SCRIPT_DIR/../deploy/sql/platform/01_platform_ddl.sql"

echo "▶ 隔离 IT 库: ${PG_DB}（容器 ${PG_CONTAINER}:${PG_PORT}）"

# ── 1. 建库（若不存在）────────────────────────────────────────────
if ! docker exec "$PG_CONTAINER" psql -U "$PG_SUPERUSER" -d postgres -tAc \
        "SELECT 1 FROM pg_database WHERE datname='$PG_DB'" | grep -q 1; then
  echo "  · 创建数据库 $PG_DB"
  docker exec "$PG_CONTAINER" psql -U "$PG_SUPERUSER" -d postgres \
      -c "CREATE DATABASE $PG_DB" >/dev/null
else
  echo "  · 数据库 $PG_DB 已存在"
fi

psql_it() {
  docker exec -i "$PG_CONTAINER" psql -U "$PG_SUPERUSER" -d "$PG_DB" \
      -v ON_ERROR_STOP=1 "$@"
}

# ── 2. 应用结构快照（一次性，已建库则跳过）───────────────────────
# ⚠️ 快照不是幂等的迁移：它按「空库」导出，重复执行会在 CREATE TABLE 上报错。
#    因此这里以「run 表是否已存在」判定结构是否已建 —— 与 IT 只关心
#    「库里有没有这套结构」的语义一致。
# ⚠️ 快照头尾的 \restrict / \unrestrict 是 pg_dump 17 的注入防护行，
#    低版本 psql 不认（配合 ON_ERROR_STOP=1 会直接判死），这里过滤掉：
#    仓库内的文件是可信来源，不需要那层防护。
if psql_it -tAc "SELECT 1 FROM pg_class WHERE relname='run' LIMIT 1" | grep -q 1; then
  echo "  · 结构已存在，跳过快照"
else
  echo "  · 应用结构快照 $(basename "$SNAPSHOT")"
  grep -v '^\\\(un\)\?restrict ' "$SNAPSHOT" | psql_it -q -v ON_ERROR_STOP=1 -f -
fi

# ── 2.2 结构版本核对（⚠️ 「存在」≠「是当前快照」）────────────────
# 上面的判定只看 run 表在不在，于是**快照后续新增的表永远补不上**：
# 10-31 引入 platform.authz_decision_log 时就是这样漏的 —— 本地库停在 65 表、
# 快照已是 66 表，脚本一路绿灯，直到 SchemaDriftIT / 某个平台仓储 IT 才炸，
# 而失败信息指向的是「表不存在」，不是「库是旧的」。
# 快照是终态导出、只能灌空库，所以这里做**核对**而不是补差量。
EXPECTED_TABLES=$(grep -c '^CREATE TABLE' "$SNAPSHOT")
ACTUAL_TABLES=$(psql_it -tAc \
  "SELECT count(*) FROM pg_tables WHERE schemaname IN ('public','platform')")
if [ "$ACTUAL_TABLES" -lt "$EXPECTED_TABLES" ]; then
  cat >&2 <<EOF
❌ 结构陈旧：库里 ${ACTUAL_TABLES} 张表，快照 ${EXPECTED_TABLES} 张（$(basename "$SNAPSHOT")）
   本脚本不补差量（快照只适用于空库 → CREATE TABLE 会直接冲突）。
   重建（本库只放测试数据，重建等价于清缓存）：
     docker exec ${PG_CONTAINER} psql -U ${PG_SUPERUSER} -d postgres -c 'DROP DATABASE ${PG_DB} WITH (FORCE)'
     IT_DB=${PG_DB} $0
EOF
  exit 1
fi
echo "  · 结构版本核对：${ACTUAL_TABLES} 张表（快照 ${EXPECTED_TABLES}）"

# ── 2.4 应用种子（幂等：以 platform.tenant 的 t-a 为标记）────────
# ⚠️ 2026-11-01 收敛后，02_platform_seed.sql 附录 A 的「发布头 + 指针」与
#    3 条 cap_agent_def 是 RunCreationEndpointIT 等 IT 的**硬依赖**
#    （快照写入链第一步就是 resolver 解析 cap_agent_def；缺了=CONFIG_UNAVAILABLE）。
#    收敛前这段数据来自已删除的 V1_seed.sql / 13 号回填补丁，收敛后权威源就是 02。
SEED="$SCRIPT_DIR/../deploy/sql/platform/02_platform_seed.sql"
if psql_it -tAc "SELECT 1 FROM platform.tenant WHERE slug='t-a' LIMIT 1" | grep -q 1; then
  echo "  · 种子已存在，跳过（幂等标记：platform.tenant 有 t-a）"
else
  echo "  · 应用种子 $(basename "$SEED")"
  grep -v '^\\\(un\)\?restrict ' "$SEED" | psql_it -q -v ON_ERROR_STOP=1 -f -
fi

# ── 2.6 清理测试残留 ─────────────────────────────────────────────
# ⚠️⚠️ 为什么必须清（P4-D10 实测）：
#   多个 agent-infrastructure 集成测试**没有 @AfterEach 清理**
#   （如 RunClaimFencingIT 只创建不删除），每跑一轮全量构建就残留 ~50 行 run。
#   累积到 1000 行左右时，claimTasks 的 `ORDER BY created_at LIMIT 1000`
#   窗口被历史行占满 → **后来者报「任务未被领取」**——
#   这正是 F-9「测试数据污染」的形态，且**失败现场指向错误方向**
#   （看起来像 claim 逻辑坏了）。
#
#   另一条更隐蔽的污染路径：rate_limit_bucket 的计数是 DB 状态，
#   跨轮次累积会吃光「30 次 / 60 秒」的配额 → 后续测试随机收到 **429**，
#   失败现场指向「限流坏了」而真因是「上轮没清」。
#
#   本库是**专用隔离库**，只保留种子数据（下方 §3 会重新播种），
#   故这里可以安全清空**测试产物表**。
#   ⚠️ 只清测试产物，**不动** tenant / agent / agent_version /
#      execution_snapshot / budget_account / principal（§3 会幂等重建）。
echo "  · 清理测试残留"
psql_it -q <<'SQL' >/dev/null
DO $$
DECLARE t TEXT;
BEGIN
  -- ⚠️ 顺序：先删子表再删父表，避免外键报错
  FOREACH t IN ARRAY ARRAY[
    -- ⚠️ cap_approval 有 FK 指向 tool_operation / run，
    --    故**必须先删它**，否则下面的 DELETE FROM tool_operation 会被外键拒绝。
    --    且它有 UNIQUE(tenant_id, invocation_id, tool_id)：
    --    跨轮次残留会让下一轮的 IT 直接撞唯一约束失败。
    'cap_approval','cap_compensation_task','cap_result_event','cap_execution_intent',
    'tool_operation_attempt','tool_operation','run_event','run_step',
    'usage_record','budget_reservation','approval',
    -- ⚠️ **audit_record 刻意不清**：它有「追加不可改」触发器（R-3 不变式），
    --    DELETE 会被 forbid_audit_mutation() 拒绝。
    --    绕过该不变式来「方便清库」＝用便利换掉一条安全不变量，
    --    本项目不这么做。审计残留在本库中**惰性无害**（不参与 claimTasks
    --    的任务窗口，也不占限流配额），审计表本就该由保留期作业回收。
    'outbox_event','idempotency_record','rate_limit_bucket',
    'weakness_record','student_profile','consent_withdrawal',
    -- ⚠️ P4-D18 补：V13 新增的 PG 队列两表（实测遗漏导致 IT 互相污染）
    --
    --   漏清的后果不是「测试变慢」而是**后续 IT 直接无法启动**：
    --   WorkerLoopWiringTest 强制 agent.messaging.mode=rabbitmq，
    --   而 MessagingSwitchGuard 在启动期检查「该租户是否有 database 模式的残留消息」——
    --   有就 fail-closed 拒绝启动。报错文案把矛头指向「通道切换残留」，
    --   与「上轮测试没清库」的真实成因**完全不同**，排查方向被带偏（实测踩到）。
    'agent_message_queue','agent_message_dead_letter',
    'erasure_certificate','run'
  ] LOOP
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='public' AND tablename=t) THEN
      EXECUTE format('DELETE FROM %I', t);
    END IF;
  END LOOP;
END;
$$;
SQL

# ── 3. 播种 IT 所需数据 ──────────────────────────────────────────
echo "  · 播种租户 / 账号 / agent / 快照 / 预算"

psql_it -q <<'SQL' >/dev/null
-- 租户
INSERT INTO tenant (tenant_id, slug, name, status, created_at)
SELECT gen_random_uuid(), 't-a', 'IT 租户 A', 'ACTIVE', now()
WHERE NOT EXISTS (SELECT 1 FROM tenant WHERE slug='t-a');

INSERT INTO tenant (tenant_id, slug, name, status, created_at)
SELECT gen_random_uuid(), 't-b', 'IT 租户 B', 'ACTIVE', now()
WHERE NOT EXISTS (SELECT 1 FROM tenant WHERE slug='t-b');

-- agent + version（t-a 与 t-b 都要：部分 IT 需要 t-b 的快照）
INSERT INTO agent (agent_id, tenant_id, name, description, created_at)
SELECT gen_random_uuid(), t.tenant_id, 'agent1', 'IT 用 agent', now()
FROM tenant t WHERE t.slug IN ('t-a','t-b')
  AND NOT EXISTS (SELECT 1 FROM agent a WHERE a.tenant_id=t.tenant_id AND a.name='agent1');

INSERT INTO agent_version (tenant_id, agent_id, version_number, definition, created_at)
SELECT a.tenant_id, a.agent_id, 1, '{"kind":"tutor"}'::jsonb, now()
FROM agent a JOIN tenant t ON t.tenant_id=a.tenant_id
WHERE t.slug IN ('t-a','t-b') AND a.name='agent1'
  AND NOT EXISTS (SELECT 1 FROM agent_version v
                  WHERE v.tenant_id=a.tenant_id AND v.agent_id=a.agent_id
                    AND v.version_number=1);

-- 执行快照
INSERT INTO execution_snapshot
  (tenant_id, version_id, agent_config, prompt_config, tool_contracts, run_config, created_at)
SELECT v.tenant_id, v.version_id, '{}', '{}', '{}',
  '{"maxCostPerRun":"2.00","maxLlmCalls":12,"maxSteps":20,"approvalRequired":"OPTIONAL"}'::jsonb,
  now()
FROM agent_version v JOIN agent a ON a.agent_id=v.agent_id JOIN tenant t ON t.tenant_id=a.tenant_id
WHERE t.slug IN ('t-a','t-b') AND a.name='agent1' AND v.version_number=1
  AND NOT EXISTS (SELECT 1 FROM execution_snapshot s WHERE s.tenant_id=v.tenant_id);

-- 预算账户（工具调用的预算预留需要）
INSERT INTO budget_account (tenant_id, total_limit, reserved_amount, settled_amount, currency, created_at)
SELECT t.tenant_id, 1000.00, 0, 0, 'CNY', now()
FROM tenant t WHERE t.slug IN ('t-a','t-b')
  AND NOT EXISTS (SELECT 1 FROM budget_account b WHERE b.tenant_id=t.tenant_id);
SQL

# ── 4. 授权（直接执行权威脚本 03 + 04，不在这里重写一套 GRANT）──────
# ⚠️ **必须用非特权角色 `app_agent` 跑 IT**（记忆铁律：验 RLS 必须用非特权，
#    超级用户能绕过 RLS，会得到「隔离失效」的反向结论）。新建库时它没有任何表权限。
#
# ⚠️⚠️ 这里原来是对 public/platform **每张表 GRANT SELECT,INSERT,UPDATE,DELETE**
#    的循环。看着省事，实际把最小权限模型整个抹平了，实测留下三个假绿：
#      · app_agent 拿到 `audit_record` 的 DELETE → G3 凭据隔离失效
#        （`RetentionErasureIT.requireAppCannotDeleteAudit` 直接判红）；
#      · app_agent 拿到 `platform.authz_decision_log` 的 SELECT → 可枚举全平台
#        授权决策（`AuthzDecisionLogLiveIT.appRoleCannotSelectDecisionLog` 判红）；
#      · 因为「有 SELECT」，`INSERT … RETURNING` 也被放行
#        （`insertWithReturningIsDenied` 判红）—— RETURNING 需要读权限这件事，
#        正是本项目的第 4 条 DDL 铁律。
#    授权模型只有一个权威源：`deploy/sql/platform/03`（public + platform 域）
#    与 `04`（保留期角色 + 撤销 DELETE）。库在本地容器也必须走它，
#    否则「本地绿 / RDS 红」这种最耗时的分歧就会回来。
echo "  · 授权（权威脚本 03_platform_roles.sql + 04_retention_role.sql）"
for f in 03_platform_roles.sql 04_retention_role.sql; do
  docker exec -i "$PG_CONTAINER" psql -U "$PG_SUPERUSER" -d "$PG_DB" \
      -v ON_ERROR_STOP=1 -q -f - \
      < "$SCRIPT_DIR/../deploy/sql/platform/$f" >/dev/null
done

# 策略/RLS 随结构快照一并创建（01_platform_ddl.sql 内含 CREATE POLICY），
# 此处只做兜底核对：策略缺失会导致「应用侧 tenant_id 条件」成为唯一防线。
enabled=$(psql_it -tAc "SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace AND relrowsecurity")
policies=$(psql_it -tAc "SELECT count(*) FROM pg_policies WHERE schemaname='public'")
echo "    RLS 启用的表: ${enabled} | 策略数: ${policies}"

# ── 4.2 权限模型自检（fail-closed，与 IT 的断言同口径）─────────────
# ⚠️ 为什么在这里再查一遍：授权脚本是「逐条 GRANT、失败不中断」的写法（刻意如此，
#    避免一条失败回滚全部），因此**它自己不会报错**。若某条 REVOKE 因表名/角色名
#    变更而失配，库照样建成功，直到某个 IT 判红——而失败信息是「应用角色竟有 DELETE」，
#    指向的是权限模型，不是「你刚建的那个库」。
chk() { psql_it -tAc "$1"; }
app_del_audit=$(chk "SELECT has_table_privilege('app_agent','public.audit_record','DELETE')")
app_sel_authz=$(chk "SELECT has_table_privilege('app_agent','platform.authz_decision_log','SELECT')")
app_ins_authz=$(chk "SELECT has_table_privilege('app_agent','platform.authz_decision_log','INSERT')")
app_sel_tenant=$(chk "SELECT has_table_privilege('app_agent','platform.tenant','SELECT')")
ret_del_audit=$(chk "SELECT has_table_privilege('agent_retention','public.audit_record','DELETE')")

echo "    权限自检: app_agent(audit_record.DELETE=${app_del_audit} 应 f | "
echo "              authz_decision_log SELECT=${app_sel_authz} 应 f / INSERT=${app_ins_authz} 应 t | "
echo "              platform.tenant SELECT=${app_sel_tenant} 应 t) | agent_retention(audit_record.DELETE=${ret_del_audit} 应 t)"
if [ "$app_del_audit" != "f" ] || [ "$app_sel_authz" != "f" ] \
   || [ "$app_ins_authz" != "t" ] || [ "$app_sel_tenant" != "t" ] || [ "$ret_del_audit" != "t" ]; then
  cat >&2 <<'EOF'
❌ 权限模型自检未过 —— 这个库不能用来跑 IT（会得到「本地绿」的假相）：
   · app_agent 必须能写审计但不能删（G3 凭据隔离）
   · app_agent 不得读 authz_decision_log（全平台授权决策，读能力只给 platform_ops）
   · app_agent 必须能读 platform.tenant（租户登记表）
   · agent_retention 必须能删 audit_record（保留期唯一的删除通道）
   核对 deploy/sql/platform/03_platform_roles.sql 与 04_retention_role.sql 是否执行成功。
EOF
  exit 1
fi
if [ "$policies" -lt 10 ]; then
  echo "    ⚠️ 策略数偏少，请核对结构快照是否完整应用"
fi

echo "✅ 隔离 IT 库就绪: $PG_DB"
echo "   下一步（连接环境只有一个入口，别手工 export 这七八个变量）："
echo "     source scripts/it-env.sh --probe        # 自动指向本库 + RABBITMQ + 保留期凭据"
echo "     cd platform && mvn -o clean verify -Dtest='Test*,*Test,*Tests,*TestCase,!MessagingCanaryWiringTest' \\"
echo "         -Dit.test='*IT,!RealBrokerEndToEndIT' -Dfailsafe.failIfNoSpecifiedTests=false"
echo "   （本脚本只建库；Agent 侧的隔离判据由 ItTargetDatabaseIsolationTest 在 surefire 阶段强制）"
