#!/usr/bin/env bash
# =====================================================================
# 能力目录「发布头 + 当前指针」一致性巡检（只读，带外直连库）
# =====================================================================
#
# 【查什么】定义表标了 PUBLISHED 之后，运行期能不能真把版本解析出来：
#   指针 → 发布头 → object_versions → id@精确版本 是唯一口径，
#   lifecycle_state 运行期**没人读**。种子只灌定义表就会留下
#   「目录 14 个已发布对象、发布头只有 2 个、TENANT 指针 0 条」的库。
#   2026-10-29 实测后果：除 e2e-minimax 外每个 Agent 闭包算不出 →
#   Run 无能力快照 → CONFIG_UNAVAILABLE（症状在配置读取，根因在发布链）。
#   「空库复放 0 错误」拦不住它 —— 那道校验比的是**一致性**，不是**可执行性**。
#
# 【连接身份】必须能读 public 全部 cap_* 表（platform.env 的 owner 账号）：
#   RLS 会把可见行收窄，巡检结论随之失真。
#
# 【只读】工具侧 conn.setReadOnly(true) 且结尾 ROLLBACK，不写任何一行。
#
# 【退出码】0 一致 / 1 有漂移 / 2 连不上或权限不足 —— 可直接挂 CI 或运维 cron。
#
# 【修复通道】deploy/sql/platform/patch_cap_release_head.sql（幂等，须库 owner 执行）
#
# 【用法】
#   scripts/check-cap-release-head.sh                                # 用仓库内 platform.env
#   CHECK_DB_URL=... CHECK_DB_USER=... CHECK_DB_PASSWORD=... \
#     scripts/check-cap-release-head.sh                              # 显式给连接串
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

echo "=== 能力目录发布头一致性巡检（5 项，只读）==="
echo "  连接身份: ${CHECK_DB_USER}"
echo
exec "$JAVA_BIN" -cp "$PGJAR" "$REPO/scripts/tools/CheckCapReleaseHead.java"
