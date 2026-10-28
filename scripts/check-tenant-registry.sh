#!/usr/bin/env bash
# =====================================================================
# 租户登记一致性巡检（只读，带外直连库）
# =====================================================================
#
# 【查什么】platform.tenant 与 public.tenant 这两处租户登记的恒等关系：
#   执行票证闸门读平台侧 lifecycle_state，worker 执行前读业务侧 status；
#   后者本应是前者的**同事务镜像**（provisionTenant / transitionLifecycle 两张表一起写），
#   但两表之间没有外键也没有触发器，漂移不会报错、只会让人查错方向。
#   2026-10-29 实测案例：种子用裸 INSERT 造租户 → 平台侧无登记 →
#   worker 照常执行、取票证 404，看起来像票证的错。
#
# 【连接身份】必须库 owner（platform.env 的 SPRING_DATASOURCE_USERNAME）：
#   public.tenant 开了 RLS，用 app_agent 连只看得到自己那一个租户，巡检结论会失真。
#
# 【只读】工具侧 conn.setReadOnly(true) 且结尾 ROLLBACK，不写任何一行。
#
# 【退出码】0 一致 / 1 有漂移 / 2 连不上或权限不足 —— 可以直接挂进 CI 或运维 cron。
#
# 【用法】
#   scripts/check-tenant-registry.sh                                  # 用仓库内 platform.env
#   CHECK_DB_URL=... CHECK_DB_USER=... CHECK_DB_PASSWORD=... \
#     scripts/check-tenant-registry.sh                                # 显式给连接串
# =====================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Users/zhouwei/software/Java/jdk-21.0.12.jdk/Contents/Home}"
JAVA_BIN="$JAVA_HOME/bin/java"
[ -x "$JAVA_BIN" ] || { echo "❌ 找不到 JDK：$JAVA_BIN（设 JAVA_HOME 后重试）"; exit 1; }

PGJAR="$(ls -1 "$HOME"/.m2/repository/org/postgresql/postgresql/*/postgresql-*.jar 2>/dev/null \
  | grep -v sources | tail -1 || true)"
[ -n "$PGJAR" ] || { echo "❌ 找不到 pgjdbc jar（~/.m2/repository/org/postgresql/postgresql/）"; exit 1; }

# 连接凭据：优先已导出的环境变量，否则从仓库内 platform.env 取 owner 账号。
# ⚠️ 口令只经环境变量传给子进程，绝不进命令行（ps 可见）。
if [ -z "${CHECK_DB_URL:-}" ] && [ -f "$REPO/platform.env" ]; then
  # shellcheck disable=SC1091
  set -a; source "$REPO/platform.env"; set +a
  export CHECK_DB_URL="${SPRING_DATASOURCE_URL:-}"
  export CHECK_DB_USER="${SPRING_DATASOURCE_USERNAME:-}"
  export CHECK_DB_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-}"
fi
[ -n "${CHECK_DB_URL:-}" ] || { echo "❌ CHECK_DB_URL 缺失（或仓库内 platform.env 不存在）"; exit 1; }

echo "=== 租户登记一致性巡检（4 项，只读）==="
echo "  owner 连接: ${CHECK_DB_USER}"
echo
exec "$JAVA_BIN" -cp "$PGJAR" "$REPO/scripts/tools/CheckTenantRegistry.java"
