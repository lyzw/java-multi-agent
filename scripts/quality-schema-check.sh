#!/usr/bin/env bash
# ============================================================================
# quality-schema-check.sh —— 质量体系（P0–P4）活库结构自检
#
# 【为什么需要它】
#   2026-11-09 评估发现：`agent_dev`（RDS）里 `attribution_correct` 两列**根本不存在**，
#   而 `patch_platform_eval_attribution.sql` 从未在活库执行过。
#   症状是最坏的形态 —— L2 判分一旦接线，`overview()` 聚合会报 column does not exist，
#   而采集器对评估异常是 fail-soft（只 WARN）⇒ **看板有数、归因正确率恒空、无报错**。
#
#   「跑过 patch」不等于「库里有列」。本脚本只做一件事：
#   **拿 01 终态快照要求的结构，逐项问活库要答案，缺一项就红。**
#
# 【用法】
#   # 本地 IT 库（免密 trust）
#   ./scripts/quality-schema-check.sh --host 127.0.0.1 --port 5433 --db agent_it --user agent
#
#   # RDS（口令走环境变量，不落盘、不上命令行 —— 命令行会进 shell history / ps）
#   PGPASSWORD='...' ./scripts/quality-schema-check.sh \
#       --host rm-xxx.rds.aliyuncs.com --db agent_dev --user app_agent
#
# 【退出码】
#   0 = 结构齐全；1 = 缺项（逐条打印缺什么）；2 = 参数/连接问题。
# ============================================================================
set -uo pipefail

HOST="" PORT="5432" DB="" USER_NAME=""

usage() {
    cat <<'EOF'
用法: quality-schema-check.sh --host HOST --db DB [--port 5432] [--user app_agent]

  --host   数据库主机（必填）
  --db     数据库名（必填）
  --port   端口，默认 5432
  --user   连接角色，默认 app_agent（受 RLS 约束的应用角色；结构检查只看 information_schema）
  口令     走 PGPASSWORD 环境变量（不要放命令行）

说明：本脚本只读 information_schema / pg_catalog，不做任何写入。
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --host) HOST="${2:-}"; shift 2 ;;
        --port) PORT="${2:-}"; shift 2 ;;
        --db)   DB="${2:-}"; shift 2 ;;
        --user) USER_NAME="${2:-}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "❌ 未知参数: $1" >&2; usage; exit 2 ;;
    esac
done

[ -z "$HOST" ] || [ -z "$DB" ] && { echo "❌ --host 与 --db 必填" >&2; usage; exit 2; }
USER_NAME="${USER_NAME:-app_agent}"

PSQL="$(command -v psql || true)"
if [ -z "$PSQL" ]; then
    for c in /opt/homebrew/opt/libpq/bin /usr/local/opt/libpq/bin; do
        [ -x "$c/psql" ] && PSQL="$c/psql" && break
    done
fi
if [ -z "$PSQL" ]; then
    echo "❌ 找不到 psql（brew install libpq，或用容器内 psql）" >&2
    exit 2
fi

query() {
    "$PSQL" -h "$HOST" -p "$PORT" -d "$DB" -U "$USER_NAME" -tAc "$1" 2>&1
}

if ! probe="$(query 'select 1')" || [ "$probe" != "1" ]; then
    echo "❌ 连不上 $HOST:$PORT/$DB（user=$USER_NAME）" >&2
    echo "   $probe" >&2
    exit 2
fi

fail=0
ok()   { printf '  ✅ %s\n' "$1"; }
bad()  { printf '  ❌ %s\n' "$1"; fail=1; }

echo "质量体系结构自检 —— $HOST:$PORT/$DB"
echo

# ── 1. 表 ────────────────────────────────────────────────────────────
echo "[表]"
for spec in "public:run_message" "platform:review_task" "platform:agent_trace_metric" "platform:agent_eval_sample" "platform:agent_task_outcome" "public:execution_snapshot"; do
    sch="${spec%%:*}"; tbl="${spec##*:}"
    n="$(query "select count(*) from information_schema.tables where table_schema='$sch' and table_name='$tbl'")"
    if [ "$n" = "1" ]; then ok "$sch.$tbl"; else bad "$sch.$tbl 不存在（P0/P1/P2 结构未应用）"; fi
done

# ── 2. 列（L2 归因正确率的两列 —— 2026-11-09 实测在 RDS 缺失）────────
echo
echo "[列]"
for spec in "platform|agent_eval_sample|attribution_correct" \
            "platform|agent_trace_metric|attribution_correct" \
            "platform|agent_trace_metric|scenario" \
            "platform|agent_eval_sample|verdict" \
            "public|run_message|idem_key" \
            "public|run_message|role"; do
    IFS='|' read -r sch tbl col <<< "$spec"
    n="$(query "select count(*) from information_schema.columns where table_schema='$sch' and table_name='$tbl' and column_name='$col'")"
    if [ "$n" = "1" ]; then ok "$sch.$tbl.$col"; else bad "$sch.$tbl.$col 不存在（跑 deploy/sql/platform/patch_platform_eval_attribution.sql）"; fi
done

# ── 3. RLS：run_message 必须 FORCE（否则租户隔离强度与代码假设不符）──
echo
echo "[RLS]"
n="$(query "select count(*) from pg_class c join pg_namespace ns on ns.oid=c.relnamespace where ns.nspname='public' and c.relname='run_message' and c.relrowsecurity and c.relforcerowsecurity")"
if [ "$n" = "1" ]; then ok "public.run_message FORCE RLS"; else bad "public.run_message 未强制 RLS（隔离强度与代码假设不符）"; fi

# ── 4. 授权：app_agent 对 eval_sample 需 INSERT；对 task_outcome 需 UPDATE
#        （ON CONFLICT DO UPDATE 要 UPDATE 而不是 INSERT，P3 实测踩过）
echo
echo "[授权]"
has_priv() {  # $1=schema $2=table $3=priv
    query "select has_table_privilege('$USER_NAME', '$1.\"$2\"', '$3')"
}
if [ "$(has_priv platform agent_eval_sample INSERT)" = "t" ]; then
    ok "$USER_NAME → platform.agent_eval_sample INSERT"
else
    bad "$USER_NAME 缺 platform.agent_eval_sample INSERT（重放 03_platform_roles.sql）"
fi
if [ "$(has_priv platform agent_task_outcome UPDATE)" = "t" ]; then
    ok "$USER_NAME → platform.agent_task_outcome UPDATE"
else
    bad "$USER_NAME 缺 platform.agent_task_outcome UPDATE ⇒ 回执恒 0 行且不报错"
fi
if [ "$(has_priv platform review_task INSERT)" = "t" ]; then
    ok "$USER_NAME → platform.review_task INSERT"
else
    bad "$USER_NAME 缺 platform.review_task INSERT（审查队列写不进去）"
fi

echo
if [ "$fail" -eq 0 ]; then
    echo "✅ 质量体系结构齐全"
    exit 0
fi
echo "❌ 存在缺项 —— 先跑 patch + 重放 03 → 04（顺序不可反）"
exit 1
