#!/usr/bin/env bash
# =====================================================================
# 平台审计档位列（U-8）：存量库升级路径 ≡ 全新库快照 的等价性反证
# =====================================================================
#
# 【为什么需要它】deploy/sql/platform/01_platform_ddl.sql 是 pg_dump 风格的
#   **终态快照**，只保证「空库能建成这个形状」；
#   deploy/sql/platform/patch_audit_ticket_scope.sql 是给**已有数据的库**打的列级补丁。
#   两条路径若有任何一处不一致（约束名、列类型、CHECK 值域、列注释），
#   就会出现「升级上来的库和新建的库行为不同」——
#   而差异只在存量库上暴露，全新库的测试**永远看不见**（U-7 退役时立下的纪律，
#   本脚本与 patch 头注释是那条纪律的第一次兑现）。
#
# 【怎么证】不做人工比对，直接把两个库各建一遍再 pg_dump 差分：
#   旧路径 = 快照建库 → DROP COLUMN ticket_scope（模拟 U-8 之前的存量形状）
#           → 播种 5 条**旧形状**能存的审计行 → 执行 patch
#   新路径 = 快照建库（列内联在 CREATE TABLE 里）
#   两库各 pg_dump --schema-only -n platform → diff 必须**零行**。
#   再加一组回填分布断言（patch 的第二段是否只填了「能证明的」）。
#
# 【回填分布断言在盯什么】patch 里 CHANNEL_DISABLED 刻意留 NULL
#   （闸门 2 排在档位校验之前，历史行里的档位确实不可知）。
#   若哪天有人把它「补齐成 PLATFORM」，本脚本会看到该行档位不再是 NULL 并失败 ——
#   这就是「回填有没有过度推断」的可执行定义，不靠读注释自证。
#
# 【只碰本机容器】只在 $PG_CONTAINER（默认 agent-pg17）里建/删两个
#   u8_retrofit_* 一次性库；⚠️ **绝不连共享远程 RDS**。
#   共享库上打 patch 的通道见 patch 头【执行】段（须 platform schema owner、先 dry-run）。
#
# 【退出码】0 等价且回填分布正确 / 1 结构漂移、幂等破裂或回填不符 / 2 环境不满足。
#
# 【用法】
#   scripts/platform-audit-ticket-scope-retrofit-check.sh
#   PG_CONTAINER=my-pg PG_SUPERUSER=postgres scripts/platform-audit-ticket-scope-retrofit-check.sh
#   KEEP_DBS=1 scripts/platform-audit-ticket-scope-retrofit-check.sh   # 留库排查
# =====================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG_CONTAINER="${PG_CONTAINER:-agent-pg17}"
PG_SUPERUSER="${PG_SUPERUSER:-agent}"
SNAPSHOT="$REPO/deploy/sql/platform/01_platform_ddl.sql"
PATCH="$REPO/deploy/sql/platform/patch_audit_ticket_scope.sql"
DB_PATCHED="u8_retrofit_patched"   # 存量库 + patch
DB_FRESH="u8_retrofit_fresh"       # 空库 + 快照

runsql() {  # runsql <db> [psql 参数…]：带 ON_ERROR_STOP，脚本/语句级执行
  docker exec -i "$PG_CONTAINER" psql -U "$PG_SUPERUSER" -d "$1" -v ON_ERROR_STOP=1 "${@:2}"
}
scalar() {  # scalar <db> <sql>：取单值
  docker exec -i "$PG_CONTAINER" psql -U "$PG_SUPERUSER" -d "$1" -tAc "$2"
}
dump() { docker exec "$PG_CONTAINER" pg_dump -U "$PG_SUPERUSER" --schema-only -n platform "$1"; }
dump_norm() {  # ⚠️ 过滤 \restrict/\unrestrict：pg_dump 17 每次导出**随机生成一个防护令牌**，
               #    不过滤会让差分永远命中这两行、把真漂移埋掉（实测撞到）。
               # 内层 || true：某些导出没有防护行时 grep 返回 1，别让 pipefail 误判成脚本失败。
  dump "$1" | { grep -v '^\\\(un\)\?restrict ' || true; }
}

drop_them() {
  local d
  for d in "$DB_PATCHED" "$DB_FRESH"; do
    docker exec "$PG_CONTAINER" psql -U "$PG_SUPERUSER" -d postgres \
        -c "DROP DATABASE IF EXISTS $d" >/dev/null 2>&1 || true
  done
}
cleanup() {
  if [ "${KEEP_DBS:-0}" = 1 ]; then
    echo "  · KEEP_DBS=1：保留 $DB_PATCHED / $DB_FRESH"
    return
  fi
  drop_them
}
trap cleanup EXIT

docker exec "$PG_CONTAINER" psql -U "$PG_SUPERUSER" -d postgres -tAc 'SELECT 1' >/dev/null 2>&1 \
  || { echo "❌ 连不上容器 $PG_CONTAINER（PG_CONTAINER/PG_SUPERUSER 可覆盖）"; exit 2; }
[ -f "$SNAPSHOT" ] || { echo "❌ 缺快照 $SNAPSHOT"; exit 2; }
[ -f "$PATCH" ] || { echo "❌ 缺补丁 $PATCH"; exit 2; }

echo "=== U-8 审计档位列：存量库升级 ≡ 全新库快照（结构差分 + 回填分布）==="
echo "  容器: $PG_CONTAINER  超户: $PG_SUPERUSER"
echo "  存量路径库: $DB_PATCHED   基准路径库: $DB_FRESH"
echo

# 一次性库：上一轮残留先删掉（本脚本自建自删；删别人建的库不在职责内）
drop_them
for d in "$DB_PATCHED" "$DB_FRESH"; do
  docker exec "$PG_CONTAINER" psql -U "$PG_SUPERUSER" -d postgres -c "CREATE DATABASE $d" >/dev/null
done

# ── 1. 两个库都先用**同一份快照**建起来 ──────────────────────────
# ⚠️ 过滤 \restrict/\unrestrict：pg_dump 17 的注入防护行，低版本 psql 不认
#    （同 scripts/dev-it-db.sh:61 的处理；仓库内文件是可信来源，不需要那层防护）。
grep -v '^\\\(un\)\?restrict ' "$SNAPSHOT" | runsql "$DB_FRESH" -q -f - >/dev/null
grep -v '^\\\(un\)\?restrict ' "$SNAPSHOT" | runsql "$DB_PATCHED" -q -f - >/dev/null

# ── 2. 把「存量库」退回 U-8 之前的形状 ──────────────────────────
# 快照是终态，无法直接表达「还没有这一列」；DROP COLUMN 就是那一列不存在时的形状
# （旧库里该列没有任何索引/策略/视图依赖，故 DROP 不会连带删掉别的东西）。
runsql "$DB_PATCHED" -q -c "ALTER TABLE platform.platform_audit_event DROP COLUMN ticket_scope" >/dev/null

# ── 3. 播种旧形状**能存**的审计行（不带档位列）──────────────────
# 五行覆盖 patch 的每一条回填分支 + 一条「不该被碰」的对照组。
runsql "$DB_PATCHED" -q >/dev/null <<'SQL'
INSERT INTO platform.platform_audit_event
    (id, actor_id, actor_type, action, target_type, decision, reason_code, occurred_at)
VALUES
    -- ① ALLOW：四道闸门全过 → 当时只可能签成 PLATFORM（闸门 3 对 ALL 一律先抛 409）
    (gen_random_uuid(), gen_random_uuid(), 'PLATFORM_USER',
     'platform.rag.governance_ticket', 'rag_governance_ticket', 'ALLOW',
     '0f0f0f0f-0f0f-0f0f-0f0f-0f0f0f0f0f0f', now() - interval '7 day'),
    -- ② DENY SCOPE_NOT_MINTABLE：只有申请 ALL 才走到这里
    (gen_random_uuid(), gen_random_uuid(), 'PLATFORM_USER',
     'platform.rag.governance_ticket', 'rag_governance_ticket', 'DENY',
     'SCOPE_NOT_MINTABLE', now() - interval '6 day'),
    -- ③ DENY RATE_LIMITED：闸门 4，排在档位闸门之后 → 当时必然是 PLATFORM
    (gen_random_uuid(), gen_random_uuid(), 'PLATFORM_USER',
     'platform.rag.governance_ticket', 'rag_governance_ticket', 'DENY',
     'RATE_LIMITED', now() - interval '5 day'),
    -- ④ DENY CHANNEL_DISABLED：闸门 2，排在档位校验**之前** → 档位不可知，必须留 NULL
    (gen_random_uuid(), gen_random_uuid(), 'PLATFORM_USER',
     'platform.rag.governance_ticket', 'rag_governance_ticket', 'DENY',
     'CHANNEL_DISABLED', now() - interval '4 day'),
    -- ⑤ 对照组：完全不同的 action，回填的两条 UPDATE 都不该命中它
    (gen_random_uuid(), gen_random_uuid(), 'PLATFORM_USER',
     'TENANT_SUSPEND', 'tenant', 'ALLOW', NULL, now() - interval '3 day');
SQL

echo "▶ 1/4 打补丁（存量库）"
# -t -A：补丁尾部的三段自证 SELECT 以「每行一个值」原样落进日志，
# 否则 psql 的表格框线会把它们裹在 | ... | 里，下面的 grep 一条都取不到（实测撞到）。
if ! runsql "$DB_PATCHED" -q -tA -f - < "$PATCH" > /tmp/u8-patch-run.log 2>&1; then
  echo "❌ 补丁执行失败，现场："; sed -n '1,40p' /tmp/u8-patch-run.log; exit 1
fi
echo "  · 补丁自证输出（不是装饰，是它给出的答案）："
grep -E '^(列 |约束 |档位分布 |票据行剩余)' /tmp/u8-patch-run.log | sed 's/^/      /' || true

# 幂等复核：整份文件重跑一遍，既不该报错，也不该把已回填的值改掉
before_null=$(scalar "$DB_PATCHED" "SELECT count(*) FROM platform.platform_audit_event WHERE ticket_scope IS NULL")
if ! runsql "$DB_PATCHED" -q -f - < "$PATCH" > /tmp/u8-patch-rerun.log 2>&1; then
  echo "❌ 补丁第二遍执行失败（幂等性破了），现场："; sed -n '1,40p' /tmp/u8-patch-rerun.log; exit 1
fi
after_null=$(scalar "$DB_PATCHED" "SELECT count(*) FROM platform.platform_audit_event WHERE ticket_scope IS NULL")
if [ "$before_null" != "$after_null" ]; then
  echo "❌ 重跑改变了 NULL 档位行数：$before_null → $after_null"; exit 1
fi
echo "  · 幂等：第二遍通过，NULL 档位行数不变（$after_null）"

# ── 4. 结构差分 ────────────────────────────────────────────────
echo
echo "▶ 2/4 pg_dump 差分（存量+patch  vs  空库+快照）"
dump_norm "$DB_PATCHED" > /tmp/u8-patched.dump
dump_norm "$DB_FRESH" > /tmp/u8-fresh.dump
if diff -u /tmp/u8-fresh.dump /tmp/u8-patched.dump > /tmp/u8-drift.diff; then
  echo "  · 零漂移：两侧各 $(wc -l < /tmp/u8-fresh.dump | tr -d ' ') 行 schema 完全一致"
else
  echo "❌ 两条路径建出的结构不一致，差异："; sed -n '1,60p' /tmp/u8-drift.diff; exit 1
fi

# ── 5. 回填分布 ───────────────────────────────────────────────
echo
echo "▶ 3/4 回填分布（只填能证明的；CHANNEL_DISABLED 留 NULL；对照组不动）"
fail=0
# 每行是「SQL 谓词|期望档位」：谓词按**行的来历**选（ALLOW 靠 action+decision，
# 拒绝行靠 reason_code，对照组靠 action），不要把 action 当 reason_code 比 ——
# 对照组的 reason_code 本来就是 NULL（实测第一版就是这么写错的，报「无此行」）。
for spec in \
    "action = 'platform.rag.governance_ticket' AND decision = 'ALLOW'|PLATFORM" \
    "reason_code = 'SCOPE_NOT_MINTABLE'|ALL" \
    "reason_code = 'RATE_LIMITED'|PLATFORM" \
    "reason_code = 'CHANNEL_DISABLED'|<NULL>" \
    "action = 'TENANT_SUSPEND'|<NULL>"; do
  pred="${spec%%|*}"; want="${spec##*|}"
  got=$(scalar "$DB_PATCHED" \
    "SELECT coalesce(ticket_scope, '<NULL>') FROM platform.platform_audit_event
      WHERE $pred LIMIT 1")
  if [ "$got" = "$want" ]; then
    echo "  ✓ [$pred] → ticket_scope=$want"
  else
    echo "  ✗ [$pred] → 期望 $want，实得 '${got:-<无此行>}'"
    fail=1
  fi
done
[ "$fail" = 0 ] || { echo "❌ 回填分布与 patch 声明的语义不符"; exit 1; }

# ── 6. 约束真的挡住非规范值（两条路径口径必须一致）──────────────
echo
echo "▶ 4/4 CHECK 在两条路径上都生效（值域精确匹配，NULL 放行）"
for db in "$DB_PATCHED" "$DB_FRESH"; do
  if runsql "$db" -q -c "INSERT INTO platform.platform_audit_event
        (id, actor_id, action, decision, occurred_at, ticket_scope)
        VALUES (gen_random_uuid(), gen_random_uuid(), 'x', 'DENY', now(), 'platform')" >/dev/null 2>&1; then
    echo "  ✗ $db：小写 'platform' 居然写进去了（CHECK 缺失或值域写宽了）"; exit 1
  fi
  runsql "$db" -q -c "INSERT INTO platform.platform_audit_event
        (id, actor_id, action, decision, occurred_at, ticket_scope)
        VALUES (gen_random_uuid(), gen_random_uuid(), 'x', 'DENY', now(), 'TENANT')" >/dev/null \
    || { echo "  ✗ $db：合法档位 TENANT 被拒"; exit 1; }
  runsql "$db" -q -c "INSERT INTO platform.platform_audit_event
        (id, actor_id, action, decision, occurred_at)
        VALUES (gen_random_uuid(), gen_random_uuid(), 'x', 'DENY', now())" >/dev/null \
    || { echo "  ✗ $db：NULL 档位（非票据行的常态）被拒"; exit 1; }
  echo "  ✓ $db：非规范值被拒 / TENANT 与 NULL 放行"
done

echo
echo "✅ U-8 等价性反证通过：存量库经 patch 升上来的形状与空库快照逐字一致，回填只填可证明的行。"
