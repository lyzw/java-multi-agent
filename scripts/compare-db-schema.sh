#!/usr/bin/env bash
# compare-db-schema.sh —— 比对两个库的结构与种子规模是否等价
# 用法: compare-db-schema.sh <库A> <库B> [schema 列表]
# 用途：验证「pg_dump 导出 → 空库复放」后与原库等价（删脚本 / 重建库的前置闸门）。
# ⚠️ constraints 排除 contype='n'：PG18 起 NOT NULL 会实体化成 pg_constraint 行，
#    PG17 只存 pg_attribute.attnotnull —— 跨版本比对会凭空多出上百条「差异」。
#    故 NOT NULL 单列为 not_null_cols，两侧口径一致。
set -euo pipefail

A="${1:?需要库 A}"
B="${2:?需要库 B}"
SCHEMAS="${3:-'public','platform'}"
CONTAINER="${PG_CONTAINER:-agent-pg17}"
SUPERUSER="${PG_SUPERUSER:-agent}"

cat > /tmp/cmp-probe.sql <<SQL
select 'tables'        k, count(*)::text v from information_schema.tables      where table_schema in ($SCHEMAS)
union all select 'columns',       count(*)::text from information_schema.columns    where table_schema in ($SCHEMAS)
union all select 'indexes',       count(*)::text from pg_indexes                    where schemaname   in ($SCHEMAS)
union all select 'rls_policies',  count(*)::text from pg_policies                   where schemaname   in ($SCHEMAS)
union all select 'rls_enabled',   count(*)::text from pg_class c join pg_namespace n on n.oid=c.relnamespace
                                      where n.nspname in ($SCHEMAS) and c.relrowsecurity
union all select 'functions',     count(*)::text from information_schema.routines   where routine_schema in ($SCHEMAS)
union all select 'triggers',      count(*)::text from information_schema.triggers   where trigger_schema in ($SCHEMAS)
union all select 'constraints',   count(*)::text from pg_constraint con join pg_namespace n on n.oid=con.connamespace
                                      where n.nspname in ($SCHEMAS) and con.contype <> 'n'
union all select 'not_null_cols', count(*)::text from pg_attribute a join pg_class c on c.oid=a.attrelid
                                      join pg_namespace n on n.oid=c.relnamespace
                                      where n.nspname in ($SCHEMAS) and a.attnotnull and a.attnum > 0
union all select 'enums',         count(*)::text from pg_type t join pg_namespace n on n.oid=t.typnamespace
                                      where n.nspname in ($SCHEMAS) and t.typtype='e'
union all select 'views',         count(*)::text from information_schema.views      where table_schema in ($SCHEMAS)
order by 1;
SQL

run() { docker exec -i "$CONTAINER" psql -U "$SUPERUSER" -d "$1" -tA -F'|' -f - < /tmp/cmp-probe.sql; }

run "$A" > /tmp/cmp_a.txt
run "$B" > /tmp/cmp_b.txt

printf '%-16s %-14s %-14s\n' 指标 "$A" "$B"
paste -d'|' /tmp/cmp_a.txt /tmp/cmp_b.txt | awk -F'|' '{ printf "  %-16s %-14s %-14s%s\n", $1, $2, $4, ($2==$4?"":"   ← 不一致") }'

if diff -q /tmp/cmp_a.txt /tmp/cmp_b.txt >/dev/null; then
  echo "✓ 结构等价：$A ≡ $B"
else
  echo "✗ 存在差异：$A ≠ $B"
  exit 1
fi
