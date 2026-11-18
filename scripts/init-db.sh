#!/usr/bin/env bash
# ============================================================================
# init-db.sh —— 数据库初始化总入口（platform / rag）
# ============================================================================
# 把 deploy/sql 下收敛后的权威快照按依赖序灌进一个目标库：
#
#   platform 目标（agent_dev / agent_prod / 隔离 IT 库）：
#     ① 建库（缺失时，owner=执行 DDL 的角色）
#     ② 01_platform_ddl.sql    完整结构终态快照（当前 74 表 / 45 RLS 表 / 53 策略）
#     ③ 02_platform_seed.sql   种子（t-a + t-b 夹具 + 发布头/指针附录）
#     ④ 03_platform_roles.sql  角色 + 授权（public 域 + platform 域）
#     ⑤ 04_retention_role.sql  保留期专用角色（凭据隔离，G3）
#   rag 目标（study_rag）：
#     ① 建库（缺失时）
#     ② 01_rag_ddl.sql         完整结构（11 表 / halfvec(4096)，含扩展头）
#     ③ 02_rag_seed.sql        种子（全局字典 + 演示语料）
#     ④ 03_rag_roles.sql       rag_app 角色 + 授权
#     ⑤ 04_rag_ops_role.sql    rag_ops 全局语料治理通道（BYPASSRLS）
#
# 【身份矩阵】
#   admin   —— 建库 / 建角色（需 CREATEDB / CREATEROLE；RDS 上可用 ai_agent）
#   owner   —— 跑 DDL 与种子（须是目标库 owner，否则 CREATE SCHEMA 被拒）
#   二者可以是同一个账号（如 RDS 上的 ai_agent）。
#
# 【用法】
#   scripts/init-db.sh platform \
#     --host rm-xxx.rwlb.rds.aliyuncs.com --port 5432 \
#     --admin-user ai_agent --admin-password '...' \
#     --db agent_dev \
#     --app-password '...' --ops-password '...' --retention-password '...'
#
#   scripts/init-db.sh rag --host ... --admin-user ai_agent --admin-password '...' \
#     --db study_rag --rag-password '...' --rag-ops-password '...'
#
#   ⚠️ 口令也可用环境变量（INIT_ADMIN_PASSWORD / INIT_APP_PASSWORD /
#      INIT_OPS_PASSWORD / INIT_RETENTION_PASSWORD / INIT_RAG_PASSWORD /
#      INIT_RAG_OPS_PASSWORD）传入，避免出现在 shell 历史里。
#
# 【幂等性】
#   建库/建角色判存在；DDL 快照**不幂等**（终态快照只在空库/空 schema 上执行一次），
#   重复执行请先 DROP DATABASE / DROP SCHEMA。种子与角色脚本可重复执行。
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
SQL_DIR="$SCRIPT_DIR/../deploy/sql"

# ── psql 定位（本机装了 libpq 而 psql 不在 PATH 的形态很常见）────────
PSQL_BIN="$(command -v psql || true)"
[ -z "$PSQL_BIN" ] && for c in /opt/homebrew/opt/libpq/bin /usr/local/opt/libpq/bin; do
  [ -x "$c/psql" ] && PSQL_BIN="$c/psql" && break
done
[ -z "$PSQL_BIN" ] && { echo "❌ 找不到 psql（brew install libpq）"; exit 1; }

TARGET="${1:-}"; shift || true
case "$TARGET" in
  platform|rag) ;;
  *) echo "用法: scripts/init-db.sh platform|rag [选项]"; exit 1 ;;
esac

HOST="127.0.0.1"; PORT=5432; DB=""
ADMIN_USER="${INIT_ADMIN_USER:-postgres}";  ADMIN_PASSWORD="${INIT_ADMIN_PASSWORD:-}"
OWNER_USER="${INIT_OWNER_USER:-}";          OWNER_PASSWORD="${INIT_OWNER_PASSWORD:-}"
APP_PASSWORD="${INIT_APP_PASSWORD:-}";      OPS_PASSWORD="${INIT_OPS_PASSWORD:-}"
RETENTION_PASSWORD="${INIT_RETENTION_PASSWORD:-}"
RAG_PASSWORD="${INIT_RAG_PASSWORD:-}";      RAG_OPS_PASSWORD="${INIT_RAG_OPS_PASSWORD:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST="$2"; shift 2;;
    --port) PORT="$2"; shift 2;;
    --db) DB="$2"; shift 2;;
    --admin-user) ADMIN_USER="$2"; shift 2;;
    --admin-password) ADMIN_PASSWORD="$2"; shift 2;;
    --owner-user) OWNER_USER="$2"; shift 2;;
    --owner-password) OWNER_PASSWORD="$2"; shift 2;;
    --app-password) APP_PASSWORD="$2"; shift 2;;
    --ops-password) OPS_PASSWORD="$2"; shift 2;;
    --retention-password) RETENTION_PASSWORD="$2"; shift 2;;
    --rag-password) RAG_PASSWORD="$2"; shift 2;;
    --rag-ops-password) RAG_OPS_PASSWORD="$2"; shift 2;;
    *) echo "未知参数: $1"; exit 1;;
  esac
done

[ -z "$DB" ] && DB=$([ "$TARGET" = platform ] && echo agent_dev || echo study_rag)
[ -z "$OWNER_USER" ] && OWNER_USER="$ADMIN_USER"
[ -z "$OWNER_PASSWORD" ] && OWNER_PASSWORD="$ADMIN_PASSWORD"

psql_admin()  { PGPASSWORD="$ADMIN_PASSWORD" "$PSQL_BIN" -h "$HOST" -p "$PORT" -U "$ADMIN_USER" "$@"; }
psql_owner()  { PGPASSWORD="$OWNER_PASSWORD" "$PSQL_BIN" -h "$HOST" -p "$PORT" -U "$OWNER_USER" "$@"; }

echo "════════════════════════════════════════════════════"
echo " 初始化目标: $TARGET → $HOST:$PORT/$DB (owner=$OWNER_USER)"
echo "════════════════════════════════════════════════════"

# ── ① 建库（缺失时；role 是集群级对象，库删了角色还在）────────────
if [ "$(psql_admin -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$DB'")" != "1" ]; then
  echo "· CREATE DATABASE $DB (OWNER $OWNER_USER)"
  psql_admin -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$DB\" OWNER \"$OWNER_USER\""
else
  echo "· 库 $DB 已存在，跳过建库"
fi

if [ "$TARGET" = platform ]; then
  # ── ② 结构快照（owner）─────────────────────────────────────────
  echo "· 应用结构快照 01_platform_ddl.sql"
  # ⚠️ 快照含 pg_dump 的 \restrict 元命令，容器内旧 psql 不认 → 过滤后走 stdin
  grep -v '^\\\(un\)\?restrict ' "$SQL_DIR/platform/01_platform_ddl.sql" | \
    psql_owner -d "$DB" -v ON_ERROR_STOP=1 -q -f -

  # ── ③ 种子（owner；末尾含发布头/指针附录与 t-b 夹具）────────────
  echo "· 灌种子 02_platform_seed.sql"
  grep -v '^\\\(un\)\?restrict ' "$SQL_DIR/platform/02_platform_seed.sql" | \
    psql_owner -d "$DB" -v ON_ERROR_STOP=1 -q -f -

  # ── ④ 角色 + 授权（含 platform 域；CREATEROLE）──────────────────
  echo "· 配角色与授权 03_platform_roles.sql"
  psql_admin -d "$DB" -v ON_ERROR_STOP=1 -q \
    -v app_password="$APP_PASSWORD" -v ops_password="$OPS_PASSWORD" \
    -f "$SQL_DIR/platform/03_platform_roles.sql" >/dev/null

  # ── ⑤ 保留期专用角色（凭据隔离；不传口令则不改既有凭据）────────
  echo "· 配保留期角色 04_retention_role.sql"
  psql_admin -d "$DB" -v ON_ERROR_STOP=1 -q \
    -v retention_password="$RETENTION_PASSWORD" \
    -f "$SQL_DIR/platform/04_retention_role.sql" >/dev/null

  # ── ⑥ 自检 ─────────────────────────────────────────────────────
  # ⚠️ 期望值是**常量**，必须与 01_platform_ddl.sql 的实际产物对齐：
  #    2026-11-03 实测「全新库 66 表 / RDS agent_dev 66 表 / 本地旧 agent_it 65 表」
  #    —— 旧值 65 会让**正确建出来的库**判自检失败（脚本缺陷，已修）。
  #    多出来的那张是 platform.authz_decision_log（决策流水，2026-10-31 引入）。
  #    2026-11-06 P1 跨 Run 记忆再 +1 表 public.student_memory（同时 +1 RLS 表 / +1 策略），
  #    故三项期望值同步为 67 / 43 / 51；与 it-env.sh --probe 用的是同一份事实。
  #    2026-11-07 统一模型目录表 model_catalog（全局、无 RLS）：67 → 68。
  #    2026-11-07 供应商密钥表 model_provider_credential（全局、无 RLS，加密存储）：68 → 69。
  #    2026-11-08 P1 G1 对话回放底座 public.run_message（租户域、FORCE RLS）：
  #    69 → 70 表，同时 +1 RLS 表 / +1 策略 → 44 / 52。
#    2026-11-08 P2 人工审查队列 platform.review_task（平台域、无 RLS）：70 → 71。
#    ⚠️ review_task 是平台域表，三项期望值只有表数变化（44/52 不变）。
  #    ⚠️ run_message 走租户域，故三项期望值**同时**变化；
  #    只改表数会让「建库成功但自检红」被当成环境问题排查。
  #    改快照新增/删除表时**必须同步这里**，否则「建库成功但自检红」会被当成环境问题排查。
#    2026-11-12 execution_snapshot 新增 context_policy 列（CH-9 / M3）：加列不加表，
#    三项期望值 71 / 44 / 52 不变；列级存在性由 SchemaDriftIT.snapshotColumnsExistInLiveDatabase 验证。
#    2026-11-18 对话审计插件 P0 配置表 public.chat_audit_config（平台域、无 RLS）：71 → 72。
#    2026-11-18 对话审计插件 P2 审计主表 public.chat_audit_record（租户域、FORCE RLS）
#    + public.chat_audit_unattributed（无 RLS）：表 72 → 74，RLS 表 44 → 45，策略 52 → 53。
  EXPECT_TABLES=74
  # ── RLS 退役期变量（RLS依赖清理改造计划 M4 B1–B4 批）──────────────
  #    每退役一批（DROP POLICY / DISABLE RLS）上调 RLS_RETIRED_*；
  #    期望值 = 基线 − 已退役数。未退役时为 0，行为与原常量完全一致。
  #    改这里必须同步 it-env.sh --probe 的 rls_retired_*（同一份事实）。
  RLS_RETIRED_TABLES=0
  RLS_RETIRED_POLICIES=0
  EXPECT_RLS_TABLES=$((45 - RLS_RETIRED_TABLES))
  EXPECT_POLICIES=$((53 - RLS_RETIRED_POLICIES))
  echo "· 自检"
  TABLES=$(psql_owner -d "$DB" -tAc "SELECT count(*) FROM pg_tables WHERE schemaname IN ('public','platform')")
  RLSTB=$(psql_owner -d "$DB" -tAc "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relrowsecurity")
  POLICIES=$(psql_owner -d "$DB" -tAc "SELECT count(*) FROM pg_policies WHERE schemaname='public'")
  ENUMWA=$(psql_owner -d "$DB" -tAc "SELECT count(*) FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid WHERE t.typname='run_state' AND e.enumlabel='WAITING_APPROVAL'")
  APPGRANTS=$(psql_owner -d "$DB" -tAc "SELECT count(*) FROM information_schema.role_table_grants WHERE grantee='app_agent' AND table_schema='public'")
  APP_TENANT=$(psql_owner -d "$DB" -tAc "SELECT has_table_privilege('app_agent','platform.tenant','SELECT')")
  RETENTION=$(psql_owner -d "$DB" -tAc "SELECT count(*) FROM pg_roles WHERE rolname='agent_retention'")
  echo "  表: ${TABLES}（应 ${EXPECT_TABLES}）| RLS 表: ${RLSTB}（应 ${EXPECT_RLS_TABLES}）| 策略: ${POLICIES}（应 ${EXPECT_POLICIES}）"
  echo "  run_state.WAITING_APPROVAL: ${ENUMWA}（应 1）| app_agent public 授权: ${APPGRANTS}"
  echo "  app_agent 可读 platform.tenant: ${APP_TENANT}（应 t）| agent_retention 角色: ${RETENTION}（应 1）"
  if [ "$TABLES" -ne "$EXPECT_TABLES" ] || [ "$RLSTB" -ne "$EXPECT_RLS_TABLES" ] || [ "$POLICIES" -ne "$EXPECT_POLICIES" ] || [ "$ENUMWA" -ne 1 ] \
     || [ "$APP_TENANT" != "t" ] || [ "$RETENTION" -ne 1 ]; then
    echo "❌ 自检未过，请核对上方各项"; exit 1
  fi
else
  # ── ② 结构快照（owner；扩展已存在时 IF NOT EXISTS 为 no-op）────
  echo "· 应用结构快照 01_rag_ddl.sql"
  grep -v '^\\\(un\)\?restrict ' "$SQL_DIR/rag/01_rag_ddl.sql" | \
    psql_owner -d "$DB" -v ON_ERROR_STOP=1 -q -f -

  # ── ③ 种子 ─────────────────────────────────────────────────────
  echo "· 灌种子 02_rag_seed.sql"
  grep -v '^\\\(un\)\?restrict ' "$SQL_DIR/rag/02_rag_seed.sql" | \
    psql_owner -d "$DB" -v ON_ERROR_STOP=1 -q -f -

  # ── ④ 应用角色 ────────────────────────────────────────────────
  echo "· 配应用角色 03_rag_roles.sql"
  psql_admin -d "$DB" -v ON_ERROR_STOP=1 -q \
    -v rag_password="$RAG_PASSWORD" \
    -f "$SQL_DIR/rag/03_rag_roles.sql" >/dev/null

  # ── ⑤ 全局语料治理通道（BYPASSRLS，仅挂 guarded 会话窗）────────
  echo "· 配治理通道 04_rag_ops_role.sql"
  psql_admin -d "$DB" -v ON_ERROR_STOP=1 -q \
    -v rag_ops_password="$RAG_OPS_PASSWORD" \
    -f "$SQL_DIR/rag/04_rag_ops_role.sql" >/dev/null

  # ── ⑥ 自检 ────────────────────────────────────────────────────
  echo "· 自检"
  TABLES=$(psql_owner -d "$DB" -tAc "SELECT count(*) FROM pg_tables WHERE schemaname='rag'")
  POLICIES=$(psql_owner -d "$DB" -tAc "SELECT count(*) FROM pg_policies WHERE schemaname='rag'")
  EMBTYPE=$(psql_owner -d "$DB" -tAc "SELECT format_type(a.atttypid,a.atttypmod) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid WHERE c.relname='rag_chunk_embedding' AND a.attname='embedding'")
  RAGOPS=$(psql_owner -d "$DB" -tAc "SELECT count(*) FROM pg_roles WHERE rolname='rag_ops'")
  # ⚠️ 期望值必须与 01_rag_ddl.sql 实际产物对齐（2026-11-12 实测 13 表 / 37 策略 / 13 FORCE）：
  #    旧值 11/35 已漂移（01 终态并入增量 05/06/08/09 后未同步本处），
  #    会让**正确建出来的库**判自检失败。
  #    RLS 退役期（改造计划 M4 R1–R3 批）每退役一批上调 RLS_RETIRED_*。
  RLS_RETIRED_TABLES=0
  RLS_RETIRED_POLICIES=0
  EXPECT_RAG_TABLES=$((13 - RLS_RETIRED_TABLES))
  EXPECT_RAG_POLICIES=$((37 - RLS_RETIRED_POLICIES))
  echo "  rag 表: ${TABLES}（应 ${EXPECT_RAG_TABLES}）| 策略: ${POLICIES}（应 ${EXPECT_RAG_POLICIES}）| embedding: ${EMBTYPE}（应 halfvec(4096)）| rag_ops: ${RAGOPS}（应 1）"
  if [ "$TABLES" -ne "$EXPECT_RAG_TABLES" ] || [ "$POLICIES" -ne "$EXPECT_RAG_POLICIES" ] || [ "$EMBTYPE" != "halfvec(4096)" ] || [ "$RAGOPS" -ne 1 ]; then
    echo "❌ 自检未过，请核对上方各项"; exit 1
  fi
fi

echo "✅ $TARGET 初始化完成: $HOST:$PORT/$DB"
[ "$TARGET" = platform ] && echo "   ⚠️ 用 app_agent 另开连接验 RLS（owner/BYPASSRLS 验隔离＝假结论）" || true
