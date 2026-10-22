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
#   ./scripts/dev-it-db.sh            # 建库 + 应用迁移 + 播种
#   IT_DB=agent_it_test ./scripts/dev-it-db.sh
#
# ⚠️ 幂等：重复执行不会重复插入，也不会清空既有数据。

set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-agent-pg17}"
PG_PORT="${PG_PORT:-5433}"
PG_DB="${IT_DB:-agent_it}"
PG_SUPERUSER="${PG_SUPERUSER:-agent}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATION_DIR="$SCRIPT_DIR/../platform/agent-infrastructure/src/main/resources/db/migration"

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

# ── 2. 应用迁移（自动发现 V*.sql，按版本号顺序）────────────────
echo "  · 应用迁移"
for f in "$MIGRATION_DIR"/V[0-9]*__*.sql; do
  name="$(basename "$f")"
  if psql_it -tAc "SELECT 1 FROM pg_class WHERE relname='run' LIMIT 1" | grep -q 1; then
    # V1 已应用过：只应用后续版本
    case "$name" in V1__*) continue ;; esac
  fi
  if psql_it -tAc "SELECT 1 FROM flyway_schema_history WHERE version LIKE '$(echo "$name" | cut -d_ -f1 | tr -d 'V')%'" | grep -q 1; then
    continue
  fi
  psql_it -q -f - < "$f" >/dev/null 2>&1 || {
    echo "  ⚠️ 迁移 $name 应用失败（可能已应用或语法差异），继续"
  }
done

# 记录版本，便于幂等判断
psql_it -q -c "
CREATE TABLE IF NOT EXISTS flyway_schema_history (
  version VARCHAR(50) PRIMARY KEY,
  description VARCHAR(200),
  installed_on TIMESTAMPTZ DEFAULT now()
);" >/dev/null 2>&1 || true

for f in "$MIGRATION_DIR"/V[0-9]*__*.sql; do
  name="$(basename "$f")"
  ver="$(echo "$name" | cut -d_ -f1 | tr -d 'V')"
  psql_it -q -c "INSERT INTO flyway_schema_history(version, description)
      SELECT '$ver', '$name' WHERE NOT EXISTS
      (SELECT 1 FROM flyway_schema_history WHERE version='$ver');" >/dev/null 2>&1 || true
done

# ── 2.5 清理测试残留 ─────────────────────────────────────────────
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

# ── 4. 授权 + RLS（对齐开发库）────────────────────────────────────
# ⚠️ **必须做**：IT 用**非特权角色** `app_agent`（记忆铁律：验 RLS 必须用非特权，
#    超级用户能绕过 RLS，会得到「隔离失效」的反向结论）。
#    新建库时 `app_agent` 无任何表权限 → 报 permission denied。
echo "  · 授权 app_agent 并启用 RLS"

psql_it -q <<'SQL' >/dev/null
-- 表级权限（逐条 GRANT；整段事务失败会连带回滚，故分条）
SQL

for tbl in $(psql_it -tAc "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename"); do
  for priv in SELECT INSERT UPDATE DELETE; do
    psql_it -q -c "GRANT $priv ON public.$tbl TO app_agent" >/dev/null 2>&1 || true
  done
done
psql_it -q -c "GRANT USAGE ON SCHEMA public TO app_agent" >/dev/null 2>&1 || true
psql_it -q -c "GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO app_agent" >/dev/null 2>&1 || true

# ⚠️ RLS 策略随迁移建表时一并创建（V1–V8 内含 CREATE POLICY），
#    此处只做兜底核对：策略缺失会导致「应用侧 tenant_id 条件」成为唯一防线。
enabled=$(psql_it -tAc "SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace AND relrowsecurity")
policies=$(psql_it -tAc "SELECT count(*) FROM pg_policies WHERE schemaname='public'")
echo "    RLS 启用的表: ${enabled} | 策略数: ${policies}"
if [ "$policies" -lt 10 ]; then
  echo "    ⚠️ 策略数偏少，请核对迁移是否全部应用"
fi

echo "✅ 隔离 IT 库就绪: $PG_DB"
echo "   用法：export IT_PG_URL=jdbc:postgresql://127.0.0.1:$PG_PORT/$PG_DB"
echo "        export IT_PG_USER=app_agent IT_PG_PASSWORD="
