#!/usr/bin/env bash
#
# =====================================================================
# rag-dict-scope-counterproof.sh —— 字典「全局 / 租户」双作用域的 RLS 反证
#
# 【要证明什么】
#   rag_dictionary 上「平台全局 + 租户私有」这套语义里有几处**不能靠推断**：
#     ① DELETE/UPDATE 的可见性会不会被 _read 策略（放行全局桶）连带放开，
#        导致租户能删/能改平台全局行？
#     ② 应用角色（NOBYPASSRLS）往全局桶插行，到底是被 RLS 拒，
#        还是被 CHECK 拒，还是——最坏的——悄悄成功？
#     ③ 全局行的 upsert 到底能不能探测到冲突
#        （探测不到 = 管理台每保存一次多一行重复全局字典项）。
#   全部以**非特权应用角色**实测。超级用户跑这些探针会得到全绿的假结论。
#
# 【表示法：V8 起的哨兵租户，不是 V7 的 NULL】
#   V7 曾用「可空 tenant_id + NULLS NOT DISTINCT 唯一索引」表示全局行，
#   V8 把本表收敛到与语料表同一表示：
#       全局行 = tenant_id = rag_global_tenant_id()（哨兵 UUID）且 scope='PLATFORM'
#   ⚠️ 本脚本里**不允许再出现** `tenant_id IS NULL` 形式的断言：
#      V8 后 tenant_id 是 NOT NULL，那样的谓词恒为 0 行，
#      会把「没测到」读成「挡住了」（假绿）。
#
# 【为什么单独一个脚本，而不是只靠 JdbcDictionaryRepositoryIT】
#   IT 的断言只覆盖「应用代码怎么用」；本脚本覆盖「数据库允不允许」，
#   包括应用根本不该发出的语句（直连 psql 注入）。越权路径只有在这里才测得到。
#
# 用法：
#   ./scripts/rag-dict-scope-counterproof.sh            # 建临时库 + 全量反证
#   PROBE_DB=xxx ./scripts/rag-dict-scope-counterproof.sh
#
# ⚠️ 本脚本**只动** PROBE_DB（默认 rag_scope_probe，本脚本自建自删）。
#    绝不碰 agent_dev / agent_it / rag_it / study_rag。
# =====================================================================

set -uo pipefail

CONTAINER="${PG_CONTAINER:-agent-pg17}"
SUPER_USER="${PG_SUPERUSER:-agent}"
PROBE_DB="${PROBE_DB:-rag_scope_probe}"
APP_ROLE="${PROBE_APP_ROLE:-rag_probe_app}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATION_DIR="$SCRIPT_DIR/../rag/rag-infrastructure/src/main/resources/db/migration"

T_TENANT="11111111-1111-1111-1111-111111111111"
U_TENANT="22222222-2222-2222-2222-222222222222"
# 与 Java 侧 RagScope.GLOBAL_TENANT_ID 同值；P0-5 用系统表核对二者一致。
SENTINEL="00000000-0000-0000-0000-000000000000"

PASS=0
FAIL=0

say()  { printf '\033[0;36m[PROBE]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[PROBE] ⚠\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[PROBE] ✗ %s\033[0m\n' "$*" >&2; exit 1; }

super_psql() { docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d "$PROBE_DB" -v ON_ERROR_STOP=1 -q "$@"; }

# 以**非特权应用角色**连库（探针的真正执行者）
# ⚠️ stderr 并入 stdout：expect_rejected 要读得到 RLS 的报错文本，
#    否则「被拒」永远测不出来 —— 报错只出现在终端上，断言却拿到空串（假绿）。
# ⚠️ -q 抑制 SET 之类的命令标签回显，否则每个断言值前都挂着 "SET"，
#    比对永远不等（实测踩过：13 项假失败，真结论被淹没）。
app_psql()   { docker exec -i "$CONTAINER" psql -U "$APP_ROLE" -d "$PROBE_DB" -q -v ON_ERROR_STOP=0 -tA "$@" 2>&1; }

# 应用角色 + 设置租户上下文后再执行
# ⚠️ set_config 的结果用 \g /dev/null 丢掉：它是一行 UUID，
#    留着会污染紧随其后的标量断言值。
# ⚠️ $1 传**裸**租户 ID（空串 = 不建租户上下文）；引号由本函数自己加。
app_psql_as() { # $1=tenantId（空串=不建租户上下文） $2=SQL
    local tid="$1" sql="$2"
    printf 'SET search_path TO rag, public;\nSELECT set_config(%s, %s, false) \\g /dev/null\n%s\n' \
           "'app.tenant_id'" "'$tid'" "$sql" | app_psql -f -
}

# 超级用户取标量（用于「事后核对真实状态」，不受 RLS 影响）
super_val() { docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d "$PROBE_DB" -q -tA -c "$1" 2>&1; }

# ── 断言器 ────────────────────────────────────────────────
# expect_eq  <标题> <期望> <实际>
expect_eq() {
    if [ "$2" = "$3" ]; then
        printf '  ✅ %s（= %s）\n' "$1" "$3"; PASS=$((PASS + 1))
    else
        printf '  ❌ %s：期望 %s，实际 %s\n' "$1" "$2" "$3"; FAIL=$((FAIL + 1))
    fi
}

# expect_rejected <标题> <SQL 输出> <期望拒绝原因正则>
# ⚠️ 原因必须对上：P8 期望被 **RLS** 拒（写策略不含全局桶分支），
#    P9 期望被 **CHECK** 拒（scope 与 tenant_id 不同向）。
#    只判「有没有报错」会把「环境坏了 / 权限缺失」也误判成安全生效。
expect_rejected() {
    if printf '%s' "$2" | grep -qiE "$3"; then
        printf '  ✅ %s（被拒：%s）\n' "$1" \
            "$(printf '%s' "$2" | grep -m1 -iE 'ERROR' | head -c 160)"
        PASS=$((PASS + 1))
    else
        printf '  ❌ %s：未按期望被拒（期望原因 /%s/）。实际输出：%s\n' \
            "$1" "$3" "$(printf '%s' "$2" | tr '\n' ' ' | head -c 200)"
        FAIL=$((FAIL + 1))
    fi
}

RLS_REJECT='violates row-level security policy|row-level security'
CHECK_REJECT='violates check constraint'
NOTNULL_REJECT='violates not-null constraint|null value in column'
SENTINEL_REJECT='禁止把全局哨兵租户'

# ── 0. 准备临时库 ─────────────────────────────────────────
docker ps --format '{{.Names}}' | grep -qx "$CONTAINER" \
    || die "容器 $CONTAINER 未运行（./scripts/dev-postgres.sh start）"

say "重建临时库 $PROBE_DB"
docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d postgres -q -c \
    "DROP DATABASE IF EXISTS $PROBE_DB" >/dev/null 2>&1
docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d postgres -q -c \
    "CREATE DATABASE $PROBE_DB" >/dev/null 2>&1 || die "建库失败"

say "按序应用迁移 V1 → 最新（自动发现，不硬编码清单）"
for f in "$MIGRATION_DIR"/V[0-9]*__*.sql; do
    name="$(basename "$f")"
    # ⚠️ 迁移必须带 search_path：V*.sql 内部用未限定表名，且依赖 public 里的扩展。
    { printf 'SET search_path TO rag, public;\n'; cat "$f"; } \
        | docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d "$PROBE_DB" \
              -v ON_ERROR_STOP=1 -q >/dev/null 2>&1 \
        || { printf '\033[0;31m[PROBE] ✗ 迁移 %s 应用失败\033[0m\n' "$name"; exit 1; }
    say "  · $name 已应用"
done

say "创建非特权应用角色 $APP_ROLE（对齐 03_rag_roles.sql 的授权面）"
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

# =====================================================================
# P0 环境自证：角色必须真的无特权
# =====================================================================
say "P0 环境自证"
# ⚠️ 按 current_user 取属性，而不是按角色名查表：
#    连库身份若悄悄变成了超级用户，按名字查出来的仍是 f，整轮结论全假。
expect_eq "P0-1 连接身份非超级用户" "off" "$(app_psql -tAc "SELECT current_setting('is_superuser')")"
expect_eq "P0-2 连接身份无 BYPASSRLS" "f" "$(app_psql -tAc "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user")"
expect_eq "P0-3 连接身份就是该应用角色" "$APP_ROLE" "$(app_psql -tAc 'SELECT current_user')"
# ⚠️ 哨兵值必须由**函数**给出，不能在脚本里写死就完事：
#    DB 侧函数与 Java 侧常量若漂移，全局行在应用眼里就成了某个普通租户的行。
expect_eq "P0-4 DB 侧 rag_global_tenant_id() 与脚本常量一致" "$SENTINEL" \
    "$(super_val "SET search_path TO rag, public; SELECT rag_global_tenant_id()")"
expect_eq "P0-5 V8 已执行（语料表也有 scope 列）" "8" \
    "$(super_val "SELECT count(*) FROM information_schema.columns
                      WHERE table_schema='rag' AND column_name='scope'")"

# =====================================================================
# 夹具：平台全局行由超级用户发布（模拟迁移/运维通道，非应用通道）
# ⚠️ 做成**可重入函数**：P6 的租户 DELETE 会合法地删掉 T 自己的覆盖行，
#    这是被测行为本身（不是 bug），但会让 P7 拿不到前置数据 ——
#    实测就栽在这里（P7 返回空值被误读成「改不动」）。
#    故每个会改状态的探针组前重新播种，保证断言只反映策略、不反映顺序。
# =====================================================================
seed_fixture() {
    super_psql -q >/dev/null <<EOF
SET search_path TO rag, public;
DELETE FROM rag_dictionary;
INSERT INTO rag_dictionary (tenant_id, dict_type, code, label, sort_order, scope)
VALUES (rag.rag_global_tenant_id(), 'SOURCE_TYPE', 'TEXTBOOK', '教材（平台）', 10, 'PLATFORM'),
       (rag.rag_global_tenant_id(), 'SOURCE_TYPE', 'PAPER',    '论文（平台）', 30, 'PLATFORM'),
       ('$T_TENANT', 'SOURCE_TYPE', 'TEXTBOOK', '教材（T 覆盖）', 10, 'TENANT'),
       ('$T_TENANT', 'SOURCE_TYPE', 'WORKBOOK', '教辅（T 私有）', 20, 'TENANT');
EOF
}

# 全局行真实条数（走超级用户，绕过 RLS）
global_count() {
    super_val "SET search_path TO rag, public;
               SELECT count(*) FROM rag_dictionary
               WHERE tenant_id = rag.rag_global_tenant_id()"
}

say "播种夹具"
seed_fixture

# =====================================================================
# P1 语料侧 fail-closed 的新底线（V8 重定义后的口径）
#   旧口径「无租户 = 0 行」在语料侧已不成立：全局语料本就设计为全租户可见。
#   新口径：无租户上下文时，**除 scope='PLATFORM' 外一行都看不见**。
#   这里语料表尚未播种，故同时给出「确实有全局行也照样看不见私有行」的读法。
# =====================================================================
say "P1 语料 fail-closed（新口径：只见全局）"
expect_eq "P1-1 无租户读 rag_chunk 的非全局行数 = 0" "0" \
    "$(app_psql_as '' "SELECT count(*) FROM rag_chunk WHERE scope <> 'PLATFORM'")"
expect_eq "P1-2 无租户读 rag_source 的非全局行数 = 0" "0" \
    "$(app_psql_as '' "SELECT count(*) FROM rag_source WHERE scope <> 'PLATFORM'")"

# =====================================================================
# P2 全局行可见性（有意例外，可见集合只有平台发布的那几行）
# =====================================================================
say "P2 全局字典行可见性"
expect_eq "P2-1 无租户上下文可见 PLATFORM 行数" "2" \
    "$(app_psql_as '' "SELECT count(*) FROM rag_dictionary WHERE scope='PLATFORM'")"
expect_eq "P2-2 无租户上下文**看不见**任何租户私有行" "0" \
    "$(app_psql_as '' "SELECT count(*) FROM rag_dictionary WHERE scope='TENANT'")"

# =====================================================================
# P3/P4 租户视角：全局 + 自己的私有，别人的私有不可见
# =====================================================================
say "P3/P4 租户视角"
expect_eq "P3-1 租户 T 可见总数（全局 2 + 私有 2）" "4" \
    "$(app_psql_as "$T_TENANT" 'SELECT count(*) FROM rag_dictionary')"
expect_eq "P3-2 租户 T 可见全局行" "2" \
    "$(app_psql_as "$T_TENANT" "SELECT count(*) FROM rag_dictionary WHERE scope='PLATFORM'")"
expect_eq "P4-1 租户 U 可见总数（仅全局 2）" "2" \
    "$(app_psql_as "$U_TENANT" 'SELECT count(*) FROM rag_dictionary')"
expect_eq "P4-2 租户 U 看不见 T 的私有 WORKBOOK" "0" \
    "$(app_psql_as "$U_TENANT" "SELECT count(*) FROM rag_dictionary WHERE code='WORKBOOK'")"

# 覆盖语义：同 code 时全局与租户行并存，由应用层裁决
expect_eq "P5-1 同 code 允许全局+租户并存（TEXTBOOK 两行）" "2" \
    "$(app_psql_as "$T_TENANT" "SELECT count(*) FROM rag_dictionary WHERE code='TEXTBOOK'")"
expect_eq "P5-2 租户 U 下同 code 只见全局那行" "1" \
    "$(app_psql_as "$U_TENANT" "SELECT count(*) FROM rag_dictionary WHERE code='TEXTBOOK'")"

# =====================================================================
# P6 ⚠️核心：租户能否删除平台全局行
# =====================================================================
# _read 策略放行全局桶，DELETE 的「行的可见性」是否被连带放开
# 是 PG 策略组合里最容易推断错的一处。这里不给结论，只给事实。
say "P6 租户删全局行（读写分策略的关键交互）"
app_psql_as "$T_TENANT" \
    "DELETE FROM rag_dictionary WHERE code='TEXTBOOK' AND tenant_id = rag_global_tenant_id()" >/dev/null 2>&1
expect_eq "P6-1 全局 TEXTBOOK 行仍存活（条数）" "1" \
    "$(super_val "SET search_path TO rag, public;
                  SELECT count(*) FROM rag_dictionary
                  WHERE code='TEXTBOOK' AND tenant_id = rag.rag_global_tenant_id()")"
expect_eq "P6-2 全局行总数未被租户 DELETE 影响" "2" "$(global_count)"

# 应用真实写法（deleteEntry 带显式租户谓词）也必须删不到全局行
expect_eq "P6-3 显式租户谓词的 DELETE 同样删不到全局行" "2" \
    "$(app_psql_as "$T_TENANT" "DELETE FROM rag_dictionary
            WHERE tenant_id = rag_current_tenant() AND dict_type='SOURCE_TYPE' AND code='TEXTBOOK';
            SELECT count(*) FROM rag_dictionary WHERE tenant_id = rag_global_tenant_id()" | tail -1)"

# =====================================================================
# P7 租户能否改写平台全局行
# =====================================================================
say "P7 租户改全局行"
seed_fixture   # P6 的 DELETE 已合法删掉 T 的覆盖行，这里恢复前置数据
expect_eq "P7-1 直连 UPDATE 全局行后标签未变" "教材（平台）" \
    "$(app_psql_as "$T_TENANT" "UPDATE rag_dictionary SET label='被租户篡改'
              WHERE code='TEXTBOOK' AND tenant_id = rag_global_tenant_id()" >/dev/null 2>&1;
          super_val "SET search_path TO rag, public; SELECT label FROM rag_dictionary
                     WHERE code='TEXTBOOK' AND tenant_id = rag.rag_global_tenant_id()")"
expect_eq "P7-2 租户改自己的覆盖行成功" "教材（T 覆盖）→ 改后" \
    "$(app_psql_as "$T_TENANT" "UPDATE rag_dictionary SET label='教材（T 覆盖）→ 改后'
              WHERE code='TEXTBOOK' AND scope='TENANT'" >/dev/null 2>&1;
          app_psql_as "$T_TENANT" "SELECT label FROM rag_dictionary
              WHERE code='TEXTBOOK' AND scope='TENANT'")"
expect_eq "P7-3 租户 U 改不到 T 的行" "教材（T 覆盖）→ 改后" \
    "$(app_psql_as "$U_TENANT" "UPDATE rag_dictionary SET label='U 篡改' WHERE code='TEXTBOOK'" >/dev/null 2>&1;
          app_psql_as "$T_TENANT" "SELECT label FROM rag_dictionary
              WHERE code='TEXTBOOK' AND scope='TENANT'")"

# =====================================================================
# P8 应用角色能否发布全局行（越权写）
# =====================================================================
say "P8 应用角色写全局行（必须被拒）"
P8_EXPLICIT="$(app_psql_as "$T_TENANT" "INSERT INTO rag_dictionary
    (tenant_id, dict_type, code, label, scope)
    VALUES (rag_global_tenant_id(), 'SOURCE_TYPE', 'SMUGGLED', '偷渡的全局项', 'PLATFORM')")"
expect_rejected "P8-1 显式哨兵 tenant_id 插入" "$P8_EXPLICIT" "$RLS_REJECT"
expect_eq "P8-2 偷渡行确实不存在" "0" \
    "$(super_val "SET search_path TO rag, public; SELECT count(*) FROM rag_dictionary WHERE code='SMUGGLED'")"

# 未设租户上下文时写全局桶（另一条潜在通道：GUC 为空 → rag_current_tenant() 为 NULL，
# 于是 `tenant_id = rag_current_tenant()` 变成 `= NULL` → NULL，不是 true）
P8_NOCTX="$(app_psql_as '' "INSERT INTO rag_dictionary
    (tenant_id, dict_type, code, label, scope)
    VALUES (rag_global_tenant_id(), 'SOURCE_TYPE', 'SMUGGLED2', '无上下文偷渡', 'PLATFORM')")"
expect_rejected "P8-3 无租户上下文写全局桶也被拒" "$P8_NOCTX" "$RLS_REJECT"

# 正常租户写必须成功（否则上一条的「被拒」是环境坏了造成的假绿）
expect_eq "P8-4 同角色写本租户行正常成功（反假绿）" "1" \
    "$(app_psql_as "$U_TENANT" "INSERT INTO rag_dictionary
            (tenant_id, dict_type, code, label, scope)
            VALUES (rag_current_tenant(), 'SOURCE_TYPE', 'OK_TENANT', '正常租户项', 'TENANT');
            SELECT count(*) FROM rag_dictionary WHERE code='OK_TENANT'" | tail -1)"

# =====================================================================
# P9 不变式与列约束（V8 收敛后的两条新底线）
# =====================================================================
say "P9 不变式：scope 与 tenant_id 必须同向 + tenant_id 不再可空"
# 用超级用户绕过 RLS，单独测 CHECK —— 否则会分不清是被 RLS 拒还是被 CHECK 拒
P9A="$(super_val "SET search_path TO rag, public;
    INSERT INTO rag_dictionary (tenant_id, dict_type, code, label, scope)
    VALUES ('$T_TENANT', 'SOURCE_TYPE', 'BAD_A', '半全局', 'PLATFORM')" 2>&1)"
expect_rejected "P9-1 带租户却标 PLATFORM 被拒" "$P9A" "$CHECK_REJECT"
P9B="$(super_val "SET search_path TO rag, public;
    INSERT INTO rag_dictionary (tenant_id, dict_type, code, label, scope)
    VALUES (rag.rag_global_tenant_id(), 'SOURCE_TYPE', 'BAD_B', '冒充租户', 'TENANT')" 2>&1)"
expect_rejected "P9-2 全局桶却标 TENANT 被拒" "$P9B" "$CHECK_REJECT"
P9C="$(super_val "SET search_path TO rag, public;
    INSERT INTO rag_dictionary (tenant_id, dict_type, code, label, scope)
    VALUES (NULL, 'SOURCE_TYPE', 'BAD_C', 'V7 遗留表示', 'PLATFORM')" 2>&1)"
expect_rejected "P9-3 NULL 租户已被 NOT NULL 挡住（V7 表示法不再可用）" "$P9C" "$NOTNULL_REJECT"

# =====================================================================
# P10 全局行 upsert 幂等（哨兵参与唯一性比较，无需 NULLS NOT DISTINCT）
# =====================================================================
say "P10 全局行 upsert 幂等"
super_psql -q >/dev/null <<'EOF'
SET search_path TO rag, public;
INSERT INTO rag_dictionary (tenant_id, dict_type, code, label, sort_order, scope)
VALUES (rag.rag_global_tenant_id(), 'SOURCE_TYPE', 'PREPRINT', '预印本', 40, 'PLATFORM')
ON CONFLICT (tenant_id, dict_type, code)
DO UPDATE SET label = EXCLUDED.label, sort_order = EXCLUDED.sort_order;
INSERT INTO rag_dictionary (tenant_id, dict_type, code, label, sort_order, scope)
VALUES (rag.rag_global_tenant_id(), 'SOURCE_TYPE', 'PREPRINT', '预印本/白皮书', 45, 'PLATFORM')
ON CONFLICT (tenant_id, dict_type, code)
DO UPDATE SET label = EXCLUDED.label, sort_order = EXCLUDED.sort_order;
EOF
expect_eq "P10-1 两次 upsert 后仍只有 1 行（冲突被探到）" "1" \
    "$(super_val "SET search_path TO rag, public; SELECT count(*) FROM rag_dictionary WHERE code='PREPRINT'")"
expect_eq "P10-2 第二次 upsert 确实更新了标签" "预印本/白皮书" \
    "$(super_val "SET search_path TO rag, public; SELECT label FROM rag_dictionary WHERE code='PREPRINT'")"
expect_eq "P10-3 全局行总数（2 夹具 + PREPRINT 1 = 3，无重复膨胀）" "3" "$(global_count)"
# V7 时代靠 NULLS NOT DISTINCT 才有的幂等，现在由哨兵天然具备
expect_eq "P10-4 表上不再有 NULLS NOT DISTINCT 索引（表示法已收敛）" "0" \
    "$(super_val "SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                  WHERE c.relname LIKE 'rag_dictionary%' AND i.indnullsnotdistinct")"

# =====================================================================
# P11 索引与策略的系统表自证
# =====================================================================
say "P11 系统表自证"
# ⚠️ information_schema 的 is_nullable 用 YES/NO 而非 t/f（拿 t 比对会恒假）
expect_eq "P11-1 字典表 tenant_id 已收回 NOT NULL（V8）" "NO" \
    "$(super_val "SELECT is_nullable FROM information_schema.columns
        WHERE table_schema='rag' AND table_name='rag_dictionary' AND column_name='tenant_id'")"
expect_eq "P11-2 四条读写分策略齐备" "4" \
    "$(super_val "SELECT count(*) FROM pg_policies
        WHERE schemaname='rag' AND tablename='rag_dictionary'")"
expect_eq "P11-3 写/删策略不放行全局桶" "0" \
    "$(super_val "SELECT count(*) FROM pg_policies WHERE schemaname='rag'
        AND tablename='rag_dictionary' AND cmd IN ('INSERT','DELETE')
        AND (qual LIKE '%rag_global_tenant_id%' OR with_check LIKE '%rag_global_tenant_id%')")"
expect_eq "P11-4 语料表同样拆成四条分策略（V8 波及，不再是单条严格相等）" "4" \
    "$(super_val "SELECT count(*) FROM pg_policies
        WHERE schemaname='rag' AND tablename='rag_chunk'")"
expect_eq "P11-5 rag_chunk 仍 FORCE RLS" "t" \
    "$(super_val "SELECT relforcerowsecurity FROM pg_class WHERE relname='rag_chunk'")"

# =====================================================================
# P12 会话身份不得冒充全局桶（V8 给哨兵表示新增的唯一攻击面）
# =====================================================================
say "P12 伪造 app.tenant_id = 哨兵"
P12="$(app_psql_as "$SENTINEL" 'SELECT count(*) FROM rag_dictionary')"
expect_rejected "P12-1 rag_current_tenant() 拒绝哨兵作为会话身份" "$P12" "$SENTINEL_REJECT"
expect_eq "P12-2 被拒后全局行仍只由平台侧持有" "3" "$(global_count)"

# =====================================================================
# 汇总
# =====================================================================
echo
say "反证汇总：通过 $PASS 项，失败 $FAIL 项"
if [ "$FAIL" -gt 0 ]; then
    printf '\033[0;31m✗ 存在失败项 —— 字典的全局/租户隔离语义不可信，勿上线\033[0m\n'
    exit 1
fi
printf '\033[0;32m✓ 全部通过：全局只读共享 + 租户严格隔离，且应用角色无法发布/篡改全局行\033[0m\n'
printf '  临时库 %s 保留供复查，可执行：\n' "$PROBE_DB"
printf '    docker exec -it %s psql -U %s -d %s\n' "$CONTAINER" "$APP_ROLE" "$PROBE_DB"
