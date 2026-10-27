#!/usr/bin/env bash
#
# =====================================================================
# rag-v7-to-v8-retrofit-check.sh —— V7→V8 **升级路径**反证
#
# 【要证明什么】
#   全新库（表里没有全局行）跑 V1→V8 永远绿：回填 UPDATE 影响 0 行，
#   V7 的 CHECK 根本不会被触发。真实生产库是**后者**：
#   V7 已应用 + deploy/sql/rag/04 已发布过全局字典 →
#   rag_dictionary 里躺着若干 `tenant_id IS NULL` 的 PLATFORM 行。
#   V8 要把它们改写成哨兵，顺序错了就会被当时还生效的
#   rag_dictionary_scope_ck（PLATFORM ⇒ tenant_id IS NULL）当场打回。
#
#   ⚠️ 这个坑**实测存在过**（本脚本就是为钉它而写）：
#     旧顺序 → ERROR: new row ... violates check constraint "rag_dictionary_scope_ck"
#     现顺序（先摘 CHECK 再回填）→ 通过
#   所以这里跑的是**真实 upgrade**，不是 fresh 应用的重复验证。
#
# 用法：
#   ./scripts/rag-v7-to-v8-retrofit-check.sh
#   PROBE_DB=xxx ./scripts/rag-v7-to-v8-retrofit-check.sh
#
# ⚠️ 只动 PROBE_DB（默认 rag_retrofit_probe，自建自删）；绝不碰业务库。
# =====================================================================

set -uo pipefail

CONTAINER="${PG_CONTAINER:-agent-pg17}"
SUPER_USER="${PG_SUPERUSER:-agent}"
PROBE_DB="${PROBE_DB:-rag_retrofit_probe}"
APP_ROLE="${PROBE_APP_ROLE:-rag_probe_app}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATION_DIR="$SCRIPT_DIR/../rag/rag-infrastructure/src/main/resources/db/migration"

T_TENANT="11111111-1111-1111-1111-111111111111"
SENTINEL="00000000-0000-0000-0000-000000000000"

PASS=0
FAIL=0

say() { printf '\033[0;36m[PROBE]\033[0m %s\n' "$*"; }
die() { printf '\033[0;31m[PROBE] ✗ %s\033[0m\n' "$*" >&2; exit 1; }

super() { docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d "$PROBE_DB" -q -tA -c "SET search_path TO rag, public; $1" 2>&1; }

expect_eq() {
    if [ "$2" = "$3" ]; then
        printf '  ✅ %s（= %s）\n' "$1" "$3"; PASS=$((PASS + 1))
    else
        printf '  ❌ %s：期望 %s，实际 %s\n' "$1" "$2" "$3"; FAIL=$((FAIL + 1))
    fi
}

docker ps --format '{{.Names}}' | grep -qx "$CONTAINER" \
    || die "容器 $CONTAINER 未运行（./scripts/dev-postgres.sh start）"

say "重建临时库 $PROBE_DB"
docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d postgres -q \
    -c "DROP DATABASE IF EXISTS $PROBE_DB" -c "CREATE DATABASE $PROBE_DB" >/dev/null 2>&1 \
    || die "建库失败"

# ── 1. 只应用到 V7：模拟「V7 时期的生产库」 ────────────────────
say "应用 V1 → V7（跳过 V8）"
for f in "$MIGRATION_DIR"/V[0-9]*__*.sql; do
    case "$(basename "$f")" in V8*) continue ;; esac
    { printf 'SET search_path TO rag, public;\n'; cat "$f"; } \
        | docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d "$PROBE_DB" \
              -v ON_ERROR_STOP=1 -q >/dev/null 2>&1 \
        || die "迁移 $(basename "$f") 应用失败"
done

# ── 2. 用 V7 的表示法发布全局字典（04 脚本当时的形态：tenant_id IS NULL）──
say "按 V7 表示播种全局行（NULL 租户）+ 一条租户覆盖行"
super "INSERT INTO rag_dictionary (tenant_id, dict_type, code, label, sort_order, scope)
        VALUES (NULL, 'SOURCE_TYPE', 'TEXTBOOK', '教材（平台）', 10, 'PLATFORM'),
               (NULL, 'SOURCE_TYPE', 'PAPER',    '论文（平台）', 30, 'PLATFORM'),
               ('$T_TENANT', 'SOURCE_TYPE', 'TEXTBOOK', '教材（T 覆盖）', 10, 'TENANT')" \
    | grep -qiE 'ERROR' && die "V7 全局行播种失败"

expect_eq "前置自证：确有 2 行 NULL 租户的全局项" "2" \
    "$(super "SELECT count(*) FROM rag_dictionary WHERE tenant_id IS NULL")"

# ── 3. 应用 V8：这一步就是被测对象 ────────────────────────────
say "应用 V8（升级路径）"
{ printf 'SET search_path TO rag, public;\n'; cat "$MIGRATION_DIR"/V8__rag_corpus_global_scope.sql; } \
    | docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d "$PROBE_DB" \
          -v ON_ERROR_STOP=1 -q >/tmp/rag_retrofit_v8.err 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
    printf '  ❌ V8 在「已有 V7 全局行」的库上应用失败：\n'
    sed 's/^/      /' /tmp/rag_retrofit_v8.err | tail -8
    FAIL=$((FAIL + 1))
else
    printf '  ✅ V8 在「已有 V7 全局行」的库上应用成功\n'; PASS=$((PASS + 1))
fi

# ── 4. 回填结果断言 ──────────────────────────────────────────
say "回填与约束"
expect_eq "R1 全局行改写到哨兵桶" "2" \
    "$(super "SELECT count(*) FROM rag_dictionary WHERE tenant_id = rag_global_tenant_id()")"
expect_eq "R2 表里不再有任何 NULL 租户行" "0" "$(super "SELECT count(*) FROM rag_dictionary WHERE tenant_id IS NULL")"
expect_eq "R3 租户覆盖行未被误改写" "1" \
    "$(super "SELECT count(*) FROM rag_dictionary WHERE tenant_id = '$T_TENANT'::uuid")"
expect_eq "R4 全局行标签未丢（回填只动 tenant_id）" "教材（平台）" \
    "$(super "SELECT label FROM rag_dictionary
              WHERE tenant_id = rag_global_tenant_id() AND code='TEXTBOOK'")"
expect_eq "R5 tenant_id 已收回 NOT NULL" "NO" \
    "$(docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d "$PROBE_DB" -q -tA -c \
        "SELECT is_nullable FROM information_schema.columns
         WHERE table_schema='rag' AND table_name='rag_dictionary' AND column_name='tenant_id'")"
expect_eq "R6 同 code 的全局行与租户行仍可并存（唯一键含租户）" "2" \
    "$(super "SELECT count(*) FROM rag_dictionary WHERE code='TEXTBOOK'")"

# ── 5. 升级后隔离仍然成立（用非特权角色复测关键两条） ──────────
say "创建非特权应用角色 $APP_ROLE 并复测隔离"
docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d "$PROBE_DB" -q >/dev/null <<EOF
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$APP_ROLE') THEN
        CREATE ROLE $APP_ROLE LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE INHERIT;
    ELSE
        ALTER ROLE $APP_ROLE LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE INHERIT;
    END IF;
END
\$\$;
GRANT USAGE ON SCHEMA rag TO $APP_ROLE;
GRANT USAGE ON SCHEMA public TO $APP_ROLE;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA rag TO $APP_ROLE;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA rag TO $APP_ROLE;
EOF

app() { # $1=tenantId（空串=不建上下文） $2=SQL
    local tid="$1" sql="$2"
    printf 'SET search_path TO rag, public;\nSELECT set_config(%s, %s, false) \\g /dev/null\n%s\n' \
           "'app.tenant_id'" "'$tid'" "$sql" \
        | docker exec -i "$CONTAINER" psql -U "$APP_ROLE" -d "$PROBE_DB" -q -v ON_ERROR_STOP=0 -tA -f - 2>&1
}

expect_eq "A1 升级后租户 T 仍读到全局 2 + 私有 1" "3" "$(app "$T_TENANT" 'SELECT count(*) FROM rag_dictionary')"
expect_eq "A2 其他租户读不到 T 的覆盖行" "2" \
    "$(app '22222222-2222-2222-2222-222222222222' 'SELECT count(*) FROM rag_dictionary')"
# ⚠️ UPDATE 的「挡住」表现为**影响 0 行、不报错**，不是 RLS 异常：
#    _update 策略的 USING 是 `tenant_id = rag_current_tenant()`，全局行根本不进入
#    被改集合，于是没有行可供 WITH CHECK 校验。
#    拿「有没有 ERROR」当断言会把安全生效误判成失败（INSERT 才必然报错，
#    因为那时确有 new row）。故这里核对**真实状态**。
app "$T_TENANT" "UPDATE rag_dictionary SET label='篡改'
                 WHERE tenant_id = rag_global_tenant_id()" >/dev/null 2>&1
expect_eq "A3 升级后租户仍改不动全局行（标签以超级用户视角核对）" "教材（平台）" \
    "$(super "SELECT label FROM rag_dictionary
              WHERE tenant_id = rag_global_tenant_id() AND code='TEXTBOOK'")"

echo
say "反证汇总：通过 $PASS 项，失败 $FAIL 项"
if [ "$FAIL" -gt 0 ]; then
    printf '\033[0;31m✗ V7→V8 升级路径不可信（新库也许能跑，生产库会炸）\033[0m\n'
    exit 1
fi
printf '\033[0;32m✓ 升级路径通过：历史 NULL 全局行被正确改写到哨兵桶，隔离语义不变\033[0m\n'
