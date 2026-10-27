#!/usr/bin/env bash
# apply-migrations.sh —— 按版本号数值顺序把迁移脚本灌进指定库
# 用法: apply-migrations.sh <库名> <迁移目录>
# ⚠️ 用 bash 而不是 shell 循环变量分词（zsh 默认不拆分 IFS）；
#    且按版本号数值排序 —— shell 通配会把 V10 排在 V2 前面。
set -uo pipefail

DB="${1:?需要库名}"
MIG="${2:?需要迁移目录}"
CONTAINER="${PG_CONTAINER:-agent-pg17}"
SUPERUSER="${PG_SUPERUSER:-agent}"

# ⚠️ 不用 mapfile（macOS 自带 bash 3.2 无此命令），直接对排序结果迭代。
LIST=$(ls "$MIG"/V[0-9]*__*.sql 2>/dev/null \
  | sed 's|.*/V||; s|__.*||' | sort -n | while read -r v; do ls "$MIG"/V"${v}"__*.sql; done)

if [ -z "$LIST" ]; then
  echo "✗ 未找到迁移文件: $MIG"; exit 1
fi

COUNT=$(printf '%s\n' "$LIST" | wc -l | tr -d ' ')
echo "▶ $DB ← ${COUNT} 个迁移（数值序）"
FAILED=0
for f in $LIST; do
  name="$(basename "$f")"
  if docker exec -i "$CONTAINER" psql -U "$SUPERUSER" -d "$DB" \
       -v ON_ERROR_STOP=1 -q -f - < "$f" > /tmp/apply-mig.log 2>&1; then
    printf '  ✓ %s\n' "$name"
  else
    printf '  ✗ %s\n' "$name"
    tail -6 /tmp/apply-mig.log | sed 's/^/      /'
    FAILED=$((FAILED + 1))
    break
  fi
done

echo "▶ 失败数=$FAILED"
[ "$FAILED" -eq 0 ]
