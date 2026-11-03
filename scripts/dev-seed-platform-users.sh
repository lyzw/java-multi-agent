#!/usr/bin/env bash
# =====================================================================
# admin-console 平台账号安装（带外，直连库）
# =====================================================================
#
# 【装什么】6 个平台身份账号，1:1 绑一个平台角色：
#   plat-superadmin  PLATFORM_SUPER_ADMIN      plat-security  PLATFORM_SECURITY_ADMIN
#   plat-lifecycle   TENANT_LIFECYCLE_ADMIN    plat-auditor   PLATFORM_AUDITOR
#   plat-resource    PLATFORM_RESOURCE_ADMIN   plat-support   PLATFORM_SUPPORT
#
# 【口令从哪来】环境变量 SEED_ACCOUNT_PASSWORD —— 绝不写进仓库、绝不进命令行
#   （命令行参数会在 ps 里可见；旧 dev-seed.sh 把口令字符串拼进 Java 源码，
#     `!`/`$` 一类字符会让写入静默失败，这里改成 env + PreparedStatement）。
#
# 【哈希由谁算】服务端同一个 PrincipalRepository$PasswordHasher（PBKDF2-HMAC-SHA256
#   600k 迭代）。装完立刻读回库里的哈希 verify 一次，登录 401 不会留到运行时才发现。
#
# 【为什么走 JDBC 而不是 psql/docker】本地 Docker 守护不可用、机器上没装 psql。
#   platform schema 归库 owner（ai_agent）所有；app_agent 对它刻意无写权限。
#   ⚠️ 2026-11-01：连接身份从「主数据源」改为 platform_ops —— 主数据源自
#   2026-10-30 起已切 app_agent（NOBYPASSRLS 且对 platform_user 无授权），
#   旧写法播种必报 permission denied；platform_ops 对 platform 域有整域 DML
#   （03_platform_roles.sql §2b），凭据来自 platform.env 的运维通道段。
#
# 【用法】
#   scripts/dev-seed-platform-users.sh                 # dry-run（结尾回滚，什么都不写）
#   scripts/dev-seed-platform-users.sh --commit        # 真正写入
#   SEED_ACCOUNT_PASSWORD='xxx' scripts/dev-seed-platform-users.sh --commit
#   附加：--reset（改写已存在账号的口令） --grantor <用户名>（granted_by，默认 e2eadmin）
#
# 【幂等】username 唯一键 + (user,role) 主键都是 DO NOTHING，可反复执行。
# =====================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Users/zhouwei/software/Java/jdk-21.0.12.jdk/Contents/Home}"
JAVA_BIN="$JAVA_HOME/bin/java"
[ -x "$JAVA_BIN" ] || { echo "❌ 找不到 JDK：$JAVA_BIN（设 JAVA_HOME 后重试）"; exit 1; }

PGJAR="$(ls -1 "$HOME"/.m2/repository/org/postgresql/postgresql/*/postgresql-*.jar 2>/dev/null \
  | grep -v sources | tail -1 || true)"
[ -n "$PGJAR" ] || { echo "❌ 找不到 pgjdbc jar（~/.m2/repository/org/postgresql/postgresql/）"; exit 1; }

CLASSES="$REPO/platform/agent-infrastructure/target/classes"
if [ ! -d "$CLASSES" ]; then
  echo "▶ 编译 agent-infrastructure（PasswordHasher 所在模块）…"
  (cd "$REPO" && mvn -q -pl platform/agent-infrastructure -am -DskipTests compile)
fi

# DB 凭据：优先已导出的环境变量，否则从 platform.env 取 platform_ops 运维通道
if [ -z "${SEED_DB_URL:-}" ] && [ -f "$REPO/platform.env" ]; then
  # shellcheck disable=SC1091
  set -a; source "$REPO/platform.env"; set +a
  export SEED_DB_URL="${AGENT_PLATFORM_OPS_DATASOURCE_URL:-${SPRING_DATASOURCE_URL:-}}"
  export SEED_DB_USER="${AGENT_PLATFORM_OPS_DATASOURCE_USERNAME:-${SPRING_DATASOURCE_USERNAME:-}}"
  export SEED_DB_PASSWORD="${AGENT_PLATFORM_OPS_DATASOURCE_PASSWORD:-${SPRING_DATASOURCE_PASSWORD:-}}"
fi
[ -n "${SEED_DB_URL:-}" ] || { echo "❌ SEED_DB_URL 缺失（或仓库内 platform.env 不存在）"; exit 1; }

if [ -z "${SEED_ACCOUNT_PASSWORD:-}" ]; then
  read -rs -p "平台账号口令（不回显）: " SEED_ACCOUNT_PASSWORD; echo
  export SEED_ACCOUNT_PASSWORD
fi

echo "=== platform schema 归属检查 ==="
echo "  连接身份: ${SEED_DB_USER}（platform_ops 对 platform.* 有整域 DML）"
echo

exec "$JAVA_BIN" -cp "$PGJAR:$CLASSES" "$REPO/scripts/tools/SeedPlatformUsers.java" "$@"
