#!/usr/bin/env bash
# =====================================================================
# rag_ops 运维通道能力面巡检（只读，带外直连库）
# =====================================================================
#
# 【查什么】05_rag_ops_role.sql 声明的那套「最小授权」现在还成立吗：
#   写面恰好 6 张表、一张都不给 DELETE、无角色成员关系、
#   角色级默认里没有 app.tenant_id、超时护栏在、带外未限定表名解析得到
#   （search_path 必须是列表语法，不是整串引号），
#   **且**第二刀没有把原闸门挪开（rag 的写类策略里仍不出现全局哨兵、
#   8 张语料表的 scope↔哨兵 CHECK 仍在、主数据源角色仍无 BYPASSRLS）。
#   13 项检查，任一违规退出码 1 —— 把「能执行」变成一条可挂 CI 的退出码。
#
# 【连接身份】必须是**运维角色本身**（默认 rag_ops），不是库 owner：
#   断言全部走 has_table_privilege(current_user, ...) 与
#   pg_roles WHERE rolname = current_user，换身份就是在检查别人。
#
# 【只读】工具侧 conn.setReadOnly(true) 且结尾 ROLLBACK，不写一行。
#   ⚠️ 因此这里刻意**不测**「ops 真能写进哨兵桶」——那是写动作，
#      由 scripts/rag-corpus-scope-counterproof.sh 的 P13 组
#      和 rag_ops 真实数据源上的集成测试覆盖。
#
# 【退出码】0 一致 / 1 漂移 / 2 连不上或身份不对。
#
# 【修复通道】重新执行 deploy/sql/rag/05_rag_ops_role.sql（先 REVOKE 再 GRANT，
#   可重复达成最小授权）；⚠️ 须库 owner 执行。
#
# 【用法】
#   scripts/check-rag-ops-role.sh                    # 用仓库内 rag.env 的 ops 数据源
#   CHECK_DB_URL=... CHECK_DB_USER=... CHECK_DB_PASSWORD=... \
#     scripts/check-rag-ops-role.sh                  # 显式给连接串
#   RAG_APP_ROLE=rag_app_dev scripts/check-rag-ops-role.sh   # 主数据源角色名不是 rag_app 时
# =====================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Users/zhouwei/software/Java/jdk-21.0.12.jdk/Contents/Home}"
JAVA_BIN="$JAVA_HOME/bin/java"
[ -x "$JAVA_BIN" ] || { echo "❌ 找不到 JDK：$JAVA_BIN（设 JAVA_HOME 后重试）"; exit 2; }

PGJAR="$(ls -1 "$HOME"/.m2/repository/org/postgresql/postgresql/*/postgresql-*.jar 2>/dev/null \
  | grep -v sources | tail -1 || true)"
[ -n "$PGJAR" ] || { echo "❌ 找不到 pgjdbc jar（~/.m2/repository/org/postgresql/postgresql/）"; exit 2; }

# 连接凭据：优先已导出的环境变量，否则从仓库内 rag.env 取**运维数据源**那一组。
# ⚠️ 口令只经环境变量传给子进程，绝不进命令行（ps 可见），也不打印。
if [ -z "${CHECK_DB_URL:-}" ] && [ -f "$REPO/rag.env" ]; then
  # shellcheck disable=SC1091
  set -a; source "$REPO/rag.env"; set +a
  export CHECK_DB_URL="${RAG_OPS_DATASOURCE_URL:-}"
  export CHECK_DB_USER="${RAG_OPS_DATASOURCE_USERNAME:-}"
  export CHECK_DB_PASSWORD="${RAG_OPS_DATASOURCE_PASSWORD:-}"
fi
[ -n "${CHECK_DB_URL:-}" ] || { echo "❌ CHECK_DB_URL 缺失（或 rag.env 里没有 RAG_OPS_DATASOURCE_URL）"; exit 2; }
[ -n "${CHECK_DB_USER:-}" ] || { echo "❌ CHECK_DB_USER 缺失"; exit 2; }

echo "=== rag_ops 运维通道能力面巡检（13 项，只读）==="
echo "  连接身份: ${CHECK_DB_USER}    主数据源角色: ${RAG_APP_ROLE:-rag_app}"
echo "  ⚠️ 连接身份若不是运维角色本身，下面每一条断言测的都是别人。"
echo
exec "$JAVA_BIN" -cp "$PGJAR" "$REPO/scripts/tools/CheckRagOpsRole.java"
