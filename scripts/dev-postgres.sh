#!/usr/bin/env bash
#
# =====================================================================
# PostgreSQL 本地开发环境（DEC-13）
#
# 目标版本：PostgreSQL 17（支持至 2029-11-08，项目 2027-02 结束后仍余 2.75 年）
#
# ⚠️ 为什么脚本默认用 trust 模式而非密码：
#   本容器仅监听 127.0.0.1，属纯本地开发环境；设密码反而使脚本与
#   集成测试都必须硬编码凭据，凭据一旦进入脚本或命令历史即成泄露面。
#   **生产环境必须改用密码 + SCRAM 认证，且凭据由密钥管理注入。**
#
# 用法：
#   ./scripts/dev-postgres.sh start     启动（首次会执行迁移）
#   ./scripts/dev-postgres.sh stop      停止，保留数据
#   ./scripts/dev-postgres.sh destroy   停止并删除容器
#   ./scripts/dev-postgres.sh status    查看状态与版本
#   ./scripts/dev-postgres.sh migrate   重新执行迁移（幂等）
#   ./scripts/dev-postgres.sh verify    以非特权角色验证 15 项约束
#   ./scripts/dev-postgres.sh logs      跟踪日志
# =====================================================================

set -euo pipefail

CONTAINER_NAME="${PG_CONTAINER:-agent-pg17}"
# DEC-13：PostgreSQL 17。若本地无该镜像，用 PG_IMAGE 覆盖。
IMAGE="${PG_IMAGE:-postgres:17}"
NETWORK="${PG_NETWORK:-agent-local}"
DB_NAME="${PG_DB:-agent_dev}"
DB_USER="${PG_USER:-agent}"
PORT="${PG_PORT:-5433}"
# 结构唯一权威源（2026-10-27 收口：Flyway 迁移链 V1~V34 已删除，改为终态快照）
SQL_REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../deploy/sql/platform" && pwd)"
SNAPSHOT="$SQL_REPO_DIR/01_platform_ddl.sql"
# 校验/种子脚本仍在原位置（它们不是迁移，是验证夹具）
VERIFY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../platform/agent-infrastructure/src/main/resources/db/migration/verify" && pwd)"

# 非特权验证角色（验证 RLS 必须用它——超级用户会绕过策略）
APP_ROLE="${PG_APP_ROLE:-app_agent}"

say()  { printf '\033[0;36m[PG]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[PG] ⚠\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[PG] ✗ %s\033[0m\n' "$*" >&2; exit 1; }

usage() {
  cat <<EOF

用法: $0 {start|stop|destroy|status|migrate|verify|logs}

  start     启动 PG 17 并执行迁移 + 种子数据
  stop      停止容器，保留数据
  destroy   删除容器
  status    查看状态与版本
  migrate   重新执行 DDL 与种子（幂等）
  verify    以非特权角色 ${APP_ROLE} 验证全部安全约束
  logs      跟踪日志

连接信息：
  jdbc:postgresql://127.0.0.1:${PORT}/${DB_NAME}
  用户 ${DB_USER}（trust 模式，无需密码）

环境变量：
  PG_CONTAINER  容器名     默认 agent-pg17
  PG_IMAGE      镜像       默认 postgres:17
  PG_PORT       端口       默认 5433
  PG_DB         数据库     默认 agent_dev
  PG_USER       用户       默认 agent
  PG_APP_ROLE   验证角色   默认 app_agent
EOF
}

container_exists()  { docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER_NAME"; }
container_running() { [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null || echo false)" = "true" ]; }

psql_super() { docker exec "$CONTAINER_NAME" psql -U "$DB_USER" -d "$DB_NAME" "$@"; }

resolve_image() {
  if docker image inspect "$IMAGE" >/dev/null 2>&1; then
    return 0
  fi
  say "本地无 ${IMAGE} ，尝试经加速器拉取…"
  local mirror="${PG_MIRROR:-docker.1ms.run}"
  if docker pull "${mirror}/library/${IMAGE}" >/dev/null 2>&1; then
    docker tag "${mirror}/library/${IMAGE}" "$IMAGE"
    say "已拉取并 retag"
    return 0
  fi
  if docker pull "$IMAGE" >/dev/null 2>&1; then
    return 0
  fi
  die "无法获取镜像 ${IMAGE}（可设 PG_MIRROR=<加速器> 重试）"
}

cmd_start() {
  if container_running; then
    say "已在运行：$CONTAINER_NAME"
    return 0
  fi
  if container_exists; then
    say "容器存在但未运行，启动中"
    docker start "$CONTAINER_NAME" >/dev/null
  else
    resolve_image
    say "启动容器 ${CONTAINER_NAME}（端口 ${PORT}）"
    # trust 模式：仅本地开发，避免凭据硬编码
    docker run -d \
      --name "$CONTAINER_NAME" \
      --network "$NETWORK" \
      -p "127.0.0.1:${PORT}:5432" \
      -e POSTGRES_USER="$DB_USER" \
      -e POSTGRES_DB="$DB_NAME" \
      -e POSTGRES_HOST_AUTH_METHOD=trust \
      "$IMAGE" >/dev/null
  fi

  say "等待就绪…"
  local i
  for i in $(seq 1 60); do
    if docker exec "$CONTAINER_NAME" pg_isready -U "$DB_USER" >/dev/null 2>&1; then
      say "就绪（耗时约 ${i}s）"
      cmd_migrate
      cmd_grant
      return 0
    fi
    sleep 1
  done
  die "启动超时，请执行 '$0 logs'"
}

cmd_migrate() {
  container_running || die "容器未运行"
  # ⚠️ 结构源已收编为**一份终态快照**（deploy/sql/platform/01_platform_ddl.sql），
  #    原 V1~V34 迁移链删除后不再有「按版本顺序应用」这件事，
  #    也就没有「脚本清单与现实脱节」的老问题 —— 快照本身就是现实。
  #
  # ⚠️⚠️ 快照**不幂等**：它按空库导出，重复执行会在 CREATE TABLE 上报错。
  #    原实现允许每次 start 都跑一遍，靠的是「迁移脚本自身幂等」这个前提；
  #    前提没了，所以这里改成先判断结构是否已建（以 run 表为准）。
  #    要重建请执行 '$0 destroy' 后重跑 start。
  if psql_super -tAc "SELECT 1 FROM pg_class WHERE relname='run' LIMIT 1" | grep -q 1; then
    say "结构已存在于 $DB_NAME，跳过快照"
  else
    say "应用结构快照：$(basename "$SNAPSHOT")"
    local tmp
    # ⚠️ 剥掉 pg_dump 17 的 \restrict / \unrestrict 头尾行：
    #    低版本 psql 不认这个反斜杠命令，配 ON_ERROR_STOP=1 会直接判死。
    #    那是给「不可信对象名」用的注入防护，仓库内的文件是可信来源。
    tmp="$(mktemp)"
    grep -v '^\\\(un\)\?restrict ' "$SNAPSHOT" > "$tmp"
    docker cp "$tmp" "$CONTAINER_NAME":/tmp/snapshot.sql >/dev/null
    rm -f "$tmp"
    local errs
    errs=$(psql_super -v ON_ERROR_STOP=1 -q -f /tmp/snapshot.sql 2>&1 | grep -c "^ERROR" || true)
    [ "$errs" -eq 0 ] || die "结构快照执行失败（$errs 个错误），请查看日志"
    say "结构快照应用完成"
  fi

  say "执行种子数据（幂等）"
  docker cp "$VERIFY_DIR/V1_seed.sql" "$CONTAINER_NAME":/tmp/seed.sql >/dev/null
  local errs
  errs=$(psql_super -f /tmp/seed.sql 2>&1 | grep -c "^ERROR" || true)
  [ "$errs" -eq 0 ] || warn "种子数据有 $errs 个错误（可重复执行，通常可忽略）"

  say "数据概览：$(psql_super -tAc "SELECT (SELECT count(*) FROM tenant)||' 租户, '||(SELECT count(*) FROM agent)||' agent, '||(SELECT count(*) FROM run)||' run, '||(SELECT count(*) FROM tool_operation)||' tool_operation, '||(SELECT count(*) FROM budget_account)||' budget_account'")"
}

# 授权给非特权角色。
# ⚠️ 注意：必须用 -c 逐条执行。若整段放在一个事务里，任意一条失败会
#    连带回滚全部 GRANT，表现为「授权数=0」却无明显报错。
cmd_grant() {
  say "准备非特权验证角色 $APP_ROLE"
  psql_super -c "DO \$\$ BEGIN
      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='${APP_ROLE}') THEN
        CREATE ROLE ${APP_ROLE} NOSUPERUSER NOBYPASSRLS LOGIN;
      END IF;
    END \$\$;" >/dev/null
  psql_super -c "GRANT USAGE ON SCHEMA public TO ${APP_ROLE};" >/dev/null
  psql_super -c "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${APP_ROLE};" >/dev/null
  psql_super -c "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${APP_ROLE};" >/dev/null
  # ⚠️ 默认权限必须设（W-PERM-P0 实测）：
  #    上面三条只覆盖**已存在**的表。新增迁移（如 V15 的 cap_* 表）建出的表
  #    **不会**自动带上授权 → 应用角色一访问就报 permission denied，
  #    而报错点在业务代码，离「表没授权」的真实成因很远
  #    （与 P4-D10「迁移清单与现实脱节」同源：清单与现实脱节且系统表面正常）。
  #    这里让**后续**建表自动继承授权，新增迁移无需再手工补。
  psql_super -c "ALTER DEFAULT PRIVILEGES IN SCHEMA PUBLIC GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${APP_ROLE};" >/dev/null
  psql_super -c "ALTER DEFAULT PRIVILEGES IN SCHEMA PUBLIC GRANT USAGE, SELECT ON SEQUENCES TO ${APP_ROLE};" >/dev/null
  say "授权完成（NOSUPERUSER NOBYPASSRLS —— 验证 RLS 必需）"
  say "已设置默认权限：后续迁移新建的表/序列自动授权给 ${APP_ROLE}"
}

cmd_verify() {
  container_running || die "容器未运行，请先执行 '$0 start'"
  docker cp "$VERIFY_DIR/V1_constraint_check.sql" "$CONTAINER_NAME":/tmp/check.sql >/dev/null
  say "以 ${APP_ROLE} 身份验证安全约束"
  echo ""
  docker exec "$CONTAINER_NAME" psql -U "$APP_ROLE" -d "$DB_NAME" -f /tmp/check.sql 2>&1 \
    | grep -E "^---|^psql.*ERROR:|^ [0-9]+$" | head -60
  echo ""
  warn "判读规则：标注「期望拒绝」的检查若<b>无 ERROR 行</b>，即表示该约束已失效（Sev-0）。"
}

cmd_status() {
  if container_running; then
    say "运行中：$CONTAINER_NAME"
    psql_super -tAc "SELECT '版本: '||version()" | head -1
    say "连接: jdbc:postgresql://127.0.0.1:${PORT}/${DB_NAME}（用户 ${DB_USER}）"
  else
    say "未运行：$CONTAINER_NAME"
  fi
}

case "${1:-start}" in
  start)          cmd_start ;;
  stop)           container_exists && docker stop "$CONTAINER_NAME" >/dev/null || say "容器不存在" ;;
  destroy)        container_exists && { warn "删除容器"; docker rm -f "$CONTAINER_NAME" >/dev/null; } || say "容器不存在" ;;
  status)         cmd_status ;;
  migrate)        cmd_migrate; cmd_grant ;;
  verify)         cmd_verify ;;
  logs)           docker logs -f "$CONTAINER_NAME" ;;
  -h|--help|help) usage ;;
  *)              usage; exit 1 ;;
esac

# =====================================================================
# 生产环境注意事项（与本脚本默认值不同，勿直接照搬）
#
#   1. 认证：必须改用密码 + SCRAM-SHA-256，凭据由密钥管理注入，
#      绝不可写入 compose 文件、脚本或环境变量默认值。
#   2. 网络：去掉 -p 127.0.0.1 绑定，按实际网络暴露并配防火墙。
#   3. 版本：必须 PostgreSQL 17.x。
#        ⚠️ PG 14 已于 2026-11-12 EOL；PG 15 于 2027-11 EOL。
#   4. 备份：按 dr.rpo=24h / dr.rto=4h 配置（P4 演练验证）。
#   5. 迁移：由 Flyway 管理版本，不可手工执行 SQL 脚本。
# =====================================================================