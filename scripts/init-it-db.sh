#!/usr/bin/env bash
# ============================================================================
# init-it-db.sh —— 建/重建**隔离 IT 库**（默认 RDS 上的 agent_it）
# ============================================================================
# 为什么单独一个脚本，而不是直接用 init-db.sh：
#
#   init-db.sh 是「通用初始化器」，要人手工传 4 个口令（admin/app/ops/retention）。
#   而 IT 库的特点是**随时可以推倒重来**——重建能力就是它的价值（快照删不掉，
#   库删得掉）。所以这里把三个非特权口令从 platform.env 自动取来（它们必须与
#   应用侧**完全一致**，否则 03/04 号脚本会把集群级角色口令改成别的值，
#   连带把运行实例连不上库），只让用户提供一个特权口令。
#
# 用法
#   export AI_AGENT_PASSWORD='...'      # RDS 上唯一带 CREATEDB/CREATEROLE 的业务角色
#   ./scripts/init-it-db.sh             # 目标库默认 agent_it（幂等：已存在则只补结构）
#   ./scripts/init-it-db.sh --recreate  # 先 DROP DATABASE 再建（**清空所有 IT 残留**）
#   IT_DB=agent_it_2 ./scripts/init-it-db.sh
#
# ⚠️ 为什么这里允许 DROP DATABASE，而产品脚本不允许：
#    本脚本的目标库**只有测试数据**，删它等价于「清缓存」；
#    但它绝不会删应用库——守卫见下（目标库 == 应用库时直接拒绝）。
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${PLATFORM_ENV_FILE:-$REPO_ROOT/platform.env}"

RECREATE=0
for arg in "$@"; do
  case "$arg" in
    --recreate) RECREATE=1;;
    -h|--help) sed -n '2,30p' "$0"; exit 0;;
    *) echo "未知参数: $arg（只支持 --recreate）"; exit 1;;
  esac
done

die() { echo "❌ init-it-db: $*" >&2; exit 1; }

[ -f "$ENV_FILE" ] || die "找不到 $ENV_FILE（先 cp platform.env.example platform.env）"
[ -n "${AI_AGENT_PASSWORD:-}" ] || die "缺 AI_AGENT_PASSWORD（RDS 上唯一带 CREATEDB 的业务角色）"

# ── 从 platform.env 取连接信息与三个非特权口令 ────────────────────────
app_url=''; app_pw=''; ops_pw=''; retention_pw=''
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in ''|'#'*) continue;; esac
  case "$line" in *=*) ;; *) continue;; esac
  k="${line%%=*}"; v="${line#*=}"
  k="${k//[[:space:]]/}"
  case "$k" in
    SPRING_DATASOURCE_URL)                    app_url="$v";;
    SPRING_DATASOURCE_PASSWORD)               app_pw="$v";;
    AGENT_PLATFORM_OPS_DATASOURCE_PASSWORD)   ops_pw="$v";;
    AGENT_RETENTION_DATASOURCE_PASSWORD)      retention_pw="$v";;
  esac
done < "$ENV_FILE"

[ -n "$app_url" ] || die "$ENV_FILE 缺 SPRING_DATASOURCE_URL"
rest="${app_url#jdbc:postgresql://}"
host_port="${rest%%/*}"
app_db="${rest#*/}"; app_db="${app_db%%\?*}"
HOST="${host_port%%:*}"; PORT="${host_port##*:}"
[ "$PORT" = "$HOST" ] && PORT=5432

TARGET_DB="${IT_DB:-agent_it}"

# ── 守卫：绝不把应用库当 IT 库来 DROP / 重建 ──────────────────────────
if [ "$TARGET_DB" = "$app_db" ]; then
  die "目标库 '$TARGET_DB' 正是应用库（${ENV_FILE} 的 SPRING_DATASOURCE_URL）。
   本脚本会 DROP DATABASE，不允许指向应用库。请改 IT_DB。"
fi

echo "════════════════════════════════════════════════════"
echo " 隔离 IT 库: ${HOST}:${PORT}/${TARGET_DB}  (应用库 ${app_db} 不动)"
echo "════════════════════════════════════════════════════"

# ── 可选：推倒重来 ──────────────────────────────────────────────────
if [ "$RECREATE" = "1" ]; then
  PSQL_BIN="$(command -v psql || true)"
  [ -z "$PSQL_BIN" ] && for c in /opt/homebrew/opt/libpq/bin /usr/local/opt/libpq/bin; do
    [ -x "$c/psql" ] && PSQL_BIN="$c/psql" && break
  done
  [ -n "$PSQL_BIN" ] || die "找不到 psql（brew install libpq）—— --recreate 需要它"

  echo "· DROP DATABASE ${TARGET_DB}（IF EXISTS，FORCE 断开残留连接）"
  PGPASSWORD="$AI_AGENT_PASSWORD" "$PSQL_BIN" -h "$HOST" -p "$PORT" -U ai_agent -d postgres \
    -v ON_ERROR_STOP=1 -q -c "DROP DATABASE IF EXISTS \"${TARGET_DB}\" WITH (FORCE)"
fi

# ── 交给 init-db.sh（结构快照 + 种子 + 角色 + 自检）─────────────────
INIT_ARGS=(
  platform
  --host "$HOST" --port "$PORT" --db "$TARGET_DB"
  --admin-user ai_agent --admin-password "$AI_AGENT_PASSWORD"
  --app-password "$app_pw"
)
# ⚠️ 不能用 `[ -n ... ] && INIT_ARGS+=(...)`：set -e 下「条件不成立」会让整个脚本退出
if [ -n "$ops_pw" ]; then
  INIT_ARGS+=(--ops-password "$ops_pw")
fi
if [ -n "$retention_pw" ]; then
  INIT_ARGS+=(--retention-password "$retention_pw")
fi

"$SCRIPT_DIR/init-db.sh" "${INIT_ARGS[@]}"

echo
echo "下一步："
echo "  export AI_AGENT_PASSWORD='...' && source scripts/it-env.sh --probe"
echo "  cd platform && mvn -o clean verify -Dtest='Test*,*Test,*Tests,*TestCase,!MessagingCanaryWiringTest' \\"
echo "      -Dit.test='*IT,!RealBrokerEndToEndIT' -Dfailsafe.failIfNoSpecifiedTests=false"
