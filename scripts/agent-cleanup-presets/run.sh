#!/usr/bin/env bash
# =====================================================================
# Agent 清理 / 预制 —— 唯一执行入口（默认只读预览，写操作必须显式 --apply）
# ---------------------------------------------------------------------
# 用法：
#   ./run.sh --target local --preview      # 本地 5433 agent_dev 只读盘点
#   ./run.sh --target rds   --preview      # RDS agent_dev 只读盘点
#   ./run.sh --target local --backup-only  # 仅导出备份 CSV（不写）
#   ./run.sh --target rds   --backup-only
#   ./run.sh --target local --apply        # 本地：彻底清理 + 预制 + 发布头重建
#   ./run.sh --target rds   --apply        # RDS：彻底清理（需 AI_AGENT_PASSWORD）
#
# 目标库固定为 agent_dev（两个库同名）。凭据只从 platform.env / 环境变量读取，
# 经环境变量传给容器内 psql，不落新文件、不回显。本机无 psql，借 agent-pg17
# 容器内的客户端；RDS 走 TCP，本地走容器内 unix socket。
# =====================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../.." && pwd)"
ENV_FILE="${PLATFORM_ENV_FILE:-$REPO_ROOT/platform.env}"
SCRIPT_DIR="$REPO_ROOT/scripts/agent-cleanup-presets"
BACKUP_DIR="$REPO_ROOT/backup/agent-cleanup-$(date +%Y%m%d-%H%M%S)"
PG_CONTAINER="${PG_CONTAINER:-agent-pg17}"

die() { echo "ERROR: $*" >&2; exit 1; }

# --- 解析参数 ---
TARGET=""
ACTION="--preview"
while [ $# -gt 0 ]; do
  case "$1" in
    --target) TARGET="${2:-}"; shift 2 ;;
    --preview|--backup-only|--apply) ACTION="$1"; shift ;;
    *) die "未知参数：$1（用法见脚本头部）" ;;
  esac
done
[ -n "$TARGET" ] || die "必须指定 --target local 或 --target rds"
[ "$TARGET" = "local" ] || [ "$TARGET" = "rds" ] || die "--target 仅支持 local / rds"

[ -f "$ENV_FILE" ] || die "找不到 $ENV_FILE"
docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER" \
  || die "容器 $PG_CONTAINER 未运行（本机 psql 客户端由它提供）"

# --- 读取 platform.env 的运维通道配置（RDS 只读/备份用 platform_ops）---
read_env() {
  local key="$1" line value
  line="$(grep -E "^${key}=" "$ENV_FILE" | tail -1)" || return 1
  value="${line#*=}"; value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
  printf '%s' "$value"
}
OPS_URL="$(read_env AGENT_PLATFORM_OPS_DATASOURCE_URL || true)"
OPS_USER="$(read_env AGENT_PLATFORM_OPS_DATASOURCE_USERNAME || true)"
OPS_PW="$(read_env AGENT_PLATFORM_OPS_DATASOURCE_PASSWORD || true)"
[ -n "$OPS_URL" ] && [ -n "$OPS_USER" ] && [ -n "$OPS_PW" ] \
  || die "platform.env 缺少平台运维通道配置(platform_ops)"

JDBC="${OPS_URL#jdbc:postgresql://}"
PGHOST_ENV="${JDBC%%:*}"
REST="${JDBC#*:}"; PGPORT_ENV="${REST%%/*}"
PGDATABASE_ENV="${REST#*/}"; PGDATABASE_ENV="${PGDATABASE_ENV%%\?*}"
[ "$PGDATABASE_ENV" = "agent_dev" ] \
  || die "运维通道指向 $PGDATABASE_ENV，不是 agent_dev —— 拒绝执行（防连错库）"

# --- 按 target 构造 PSQL 调用数组 ---
if [ "$TARGET" = "local" ]; then
  # 本地：agent 是超级用户，套接字直连，彻底删除可行
  PSQL=(docker exec -i "$PG_CONTAINER" psql -U agent -d agent_dev -X -v ON_ERROR_STOP=1 -v mode=local)
  echo "目标库[local]: agent@agent-pg17:/agent_dev  (超级用户, 可禁用不可变触发器)"
else
  # RDS：只读/备份用 platform_ops；apply 必须用表 owner ai_agent（需 AI_AGENT_PASSWORD）
  if [ "$ACTION" = "--apply" ]; then
    [ -n "${AI_AGENT_PASSWORD:-}" ] \
      || die "RDS 彻底删除需要表 owner ai_agent 的密码：请先 export AI_AGENT_PASSWORD=**** 再执行（当前环境未设置）"
    RDS_USER="ai_agent"; RDS_PW="$AI_AGENT_PASSWORD"
    echo "目标库[rds]: ai_agent@${PGHOST_ENV}:${PGPORT_ENV}/agent_dev  (表 owner, 可禁用不可变触发器)"
  else
    RDS_USER="$OPS_USER"; RDS_PW="$OPS_PW"
    echo "目标库[rds]: $OPS_USER@${PGHOST_ENV}:${PGPORT_ENV}/agent_dev  (只读/备份)"
  fi
  PSQL=(docker exec -i -e "PGPASSWORD=$RDS_PW" "$PG_CONTAINER" psql \
        -h "$PGHOST_ENV" -p "$PGPORT_ENV" -U "$RDS_USER" -d "$PGDATABASE_ENV" \
        -X -v ON_ERROR_STOP=1 -v mode=rds)
fi

do_backup() {
  mkdir -p "$BACKUP_DIR"
  echo "导出备份到 $BACKUP_DIR ..."
  "${PSQL[@]}" -c '\copy public.agent TO STDOUT WITH CSV HEADER'                 > "$BACKUP_DIR/agent.csv"
  "${PSQL[@]}" -c '\copy public.agent_version TO STDOUT WITH CSV HEADER'         > "$BACKUP_DIR/agent_version.csv"
  "${PSQL[@]}" -c '\copy public.execution_snapshot TO STDOUT WITH CSV HEADER'    > "$BACKUP_DIR/execution_snapshot.csv"
  "${PSQL[@]}" -c '\copy public.cap_agent_def TO STDOUT WITH CSV HEADER'         > "$BACKUP_DIR/cap_agent_def.csv"
  "${PSQL[@]}" -c '\copy public.cap_agent_child_ref TO STDOUT WITH CSV HEADER'   > "$BACKUP_DIR/cap_agent_child_ref.csv"
  "${PSQL[@]}" -c '\copy public.run TO STDOUT WITH CSV HEADER'                   > "$BACKUP_DIR/run.csv"
  "${PSQL[@]}" -c '\copy public.cap_config_pointer TO STDOUT WITH CSV HEADER'    > "$BACKUP_DIR/cap_config_pointer.csv"
  "${PSQL[@]}" -c '\copy public.cap_config_release TO STDOUT WITH CSV HEADER'    > "$BACKUP_DIR/cap_config_release.csv"
  wc -l "$BACKUP_DIR"/*.csv
}

case "$ACTION" in
  --preview)
    "${PSQL[@]}" -f /dev/stdin < "$SCRIPT_DIR/01_preview.sql"
    ;;
  --backup-only)
    do_backup
    ;;
  --apply)
    do_backup
    echo "===== 开始执行 02_apply.sql（彻底清理 + 预制 + 发布头重建）====="
    "${PSQL[@]}" -f /dev/stdin < "$SCRIPT_DIR/02_apply.sql"
    echo "===== 完成；备份目录：$BACKUP_DIR ====="
    ;;
esac
