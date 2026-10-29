#!/usr/bin/env bash
#
# =====================================================================
# rag-corpus-scope-counterproof.sh —— 语料侧「全局 / 租户」双作用域的反证
#
# 【要证明什么】
#   V8 把「平台全局语料」落成语料链上的**哨兵租户 ID**
#   （tenant_id = rag_global_tenant_id()，列继续 NOT NULL），
#   并把 8 张表的 RLS 从「单策略严格相等」改成「读写分策略」。
#   这里面有四类结论**不能靠推断**，只能以非特权角色实测：
#
#     A 类 · 隔离是否真的守住（安全面）
#       ① 读策略放开到 `IN (本租户, 哨兵)` 之后，租户到底能不能
#          INSERT/UPDATE/DELETE 全局行？（读写策略的可见性组合是 PG 里
#          最容易推断错的一处，V7 的反证已证明必须实测）
#       ② 会话把 app.tenant_id 直接设成哨兵值，能不能伪装成平台身份？
#          —— 这是哨兵表示法**唯一新增的攻击面**，必须封死。
#       ③ CHECK 不变式能否挡住「scope 与 tenant_id 不同向」的半全局行
#          （否则某租户的私有语料会被读策略当全局行放行 = 泄露）。
#
#     B 类 · 功能是否真的生效（可用性面，最容易假绿）
#       ④ 全局语料**整条链**（source→edition→document→chunk）在
#          等值 JOIN 下是否还能连起来？——「只改策略不改 JOIN」的
#          NULL 表示法就是死在这里（实测 0 行，见 V8 文件头 ②）。
#       ⑤ 租户答案能否引用一条全局片段（citations 的外键已降为单列）。
#       ⑥ 全局知识点的先决关系边能否存在（该表主键含 tenant_id，
#          NULL 表示法连插都插不进去，见 V8 文件头 ③）。
#
#     C 类 · fail-closed 语义重述是否成立
#       ⑦ 未建租户上下文时可见集合 = **仅** scope='PLATFORM' 的行，
#          且租户私有行一行不漏出来（启动自检与 worker 预检按此重述）。
#
#     D 类 · 装配完整性（防静默退化）
#       ⑧ 8 张表各恰好 4 条分策略、写/删策略不含哨兵、FORCE RLS 在、
#          tenant_id 仍 NOT NULL、citations 外键已单列化。
#
#     E 类 · 运维通道（RAG-D11 第二刀·P13）
#       ⑨ 独立角色 rag_ops **真的能写哨兵桶**（否则控制台的 G 档写是空头承诺），
#          且它写出来的全局行租户在应用上下文里读得到（通道与读策略接得上）。
#       ⑩ 它的授权面仍比 rag_app 窄：一张表都不给 DELETE、未授权的表不能 UPDATE、
#          没有 schema 建对象权、不是任何角色的成员。
#       ⑪ 开了这道门**没有**把原来那道关上：rag_app 写全局行仍被 RLS 拒；
#          且 BYPASSRLS 绕不掉库内 CHECK（半全局行插不进去）。
#       ⑫ BYPASSRLS 的代价如实测出：ops 角色无租户上下文时跨租户读得到私有行
#          —— 这是 §1.3 记录的既有取舍，不是本次发现的新漏洞，钉成断言免得
#          日后有人以为 ops 角色「只碰得到全局桶」。
#
# 【E 类不覆盖的部分（别误以为这里全测了）】
#   治理票据的四种拒绝（无票据 / 档位不符 / 租户域令牌 / 带租户头）**是应用层闸门**，
#   数据库里根本没有「票据」这个概念，本脚本测不到。它们钉在
#   RagManagementControllerTest（403/400 与「一次连接都不发起」）和
#   PlatformRagGovernanceTicketServiceTest（签票四道闸门 + 审计原因码）里。
#
# 【为什么单独一个脚本，而不是只靠 IT】
#   IT 的断言只覆盖「应用代码怎么用」；本脚本覆盖「数据库允不允许」，
#   包括应用根本不该发出的语句（直连 psql 注入）。越权路径只有这里测得到。
#
# 用法：
#   ./scripts/rag-corpus-scope-counterproof.sh
#   PROBE_DB=xxx ./scripts/rag-corpus-scope-counterproof.sh
#   PG_CONTAINER=xxx PROBE_OPS_ROLE=yyy ./scripts/rag-corpus-scope-counterproof.sh
#
# ⚠️ 需要 Docker 里有跑着的 PG17 容器（默认 agent-pg17，见 scripts/dev-postgres.sh）：
#    本脚本全程用 docker exec 连库，**不接受外部 PG 连接串**——
#    临时库必须能自建自删，而共享实例上没有这个前提。
#
# ⚠️ 本脚本**只动** PROBE_DB（默认 rag_corpus_scope_probe，自建自删）。
#    绝不碰 agent_dev / agent_it / rag_it / study_rag。
# =====================================================================

set -uo pipefail

CONTAINER="${PG_CONTAINER:-agent-pg17}"
SUPER_USER="${PG_SUPERUSER:-agent}"
PROBE_DB="${PROBE_DB:-rag_corpus_scope_probe}"
APP_ROLE="${PROBE_APP_ROLE:-rag_probe_app}"
# 运维通道角色（P13）：属性对齐 deploy/sql/rag/05_rag_ops_role.sql。
# ⚠️ **不设口令**：本容器走 trust 登录，探针只需要「以该身份连库」；
#    写死一个口令进脚本文件只会让人以为它是凭据（真实部署的口令由密钥管理注入）。
OPS_ROLE="${PROBE_OPS_ROLE:-rag_probe_ops}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 权威库结构（826d77b 之后：Flyway V1→V8 已删，deploy/sql/rag/01_rag_ddl.sql 是唯一源）。
# ⚠️ 本脚本**只灌结构、不灌 02_rag_seed.sql**：种子是真实教材语料，
#    会把 P0-4/P1/P3 那批「全局 1 + 私有 1」的精确计数打成随机数。
#    夹具本来就从零播种，结构才是这里要复用的东西。
DDL_FILE="$SCRIPT_DIR/../deploy/sql/rag/01_rag_ddl.sql"

GLOBAL_TENANT="00000000-0000-0000-0000-000000000000"
T_TENANT="11111111-1111-1111-1111-111111111111"
U_TENANT="22222222-2222-2222-2222-222222222222"
HASH64="$(printf '0%.0s' $(seq 1 64))"

PASS=0
FAIL=0

say()  { printf '\033[0;36m[PROBE]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[PROBE] ✗ %s\033[0m\n' "$*" >&2; exit 1; }

super_psql() { docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d "$PROBE_DB" -v ON_ERROR_STOP=1 -q "$@"; }
super_val()  { docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d "$PROBE_DB" -q -tA -c "SET search_path TO rag, public; $1" 2>&1; }

# 以**非特权应用角色**连库（探针的真正执行者）。
# ⚠️ stderr 并入 stdout：否则「被拒」永远测不出来（报错只在终端，断言拿到空串 = 假绿）。
# ⚠️ -q 抑制 SET 的命令标签回显，否则每个标量断言前挂着 "SET" 导致恒不相等。
# ⚠️ -f - 不能省：不写 -f 时从管道读脚本的行为在不同 psql 版本下不一致，
#    实测会让 search_path 的 SET 不生效，于是所有断言变成 "relation does not exist"
#    ——看着像「权限生效了」，其实是连表都找不到（本轮就踩过一次）。
app_psql() { docker exec -i "$CONTAINER" psql -U "$APP_ROLE" -d "$PROBE_DB" -q -tA \
                -v ON_ERROR_STOP=0 -f - 2>&1; }

# 应用角色 + 设定租户上下文后再执行（$1 传空串 = 不建租户上下文）
# ⚠️ 引号由本函数自己加：$1 传**裸**租户 ID（空串 = 不建上下文 → 设成 ''）。
#    若让调用方各写一次引号，空串那一路就会漏引号变成语法错（实测踩过）。
app_psql_as() {
    local tid="$1" sql="$2"
    printf 'SET search_path TO rag, public;\nSELECT set_config(%s, %s, false) \\g /dev/null\n%s\n' \
           "'app.tenant_id'" "'$tid'" "$sql" | app_psql
}

# 运维通道角色连库（P13 的执行者）。
# ⚠️ 不带租户上下文：这条通道的身份就是「平台」，应用侧也不给它设 app.tenant_id
#    （角色级默认租户会把上一个持有连接者的作用域伪装成当前请求的作用域）。
ops_psql() { docker exec -i "$CONTAINER" psql -U "$OPS_ROLE" -d "$PROBE_DB" -q -tA \
                -v ON_ERROR_STOP=0 -f - 2>&1; }

expect_eq() {
    if [ "$2" = "$3" ]; then
        printf '  ✅ %s（= %s）\n' "$1" "$3"; PASS=$((PASS + 1))
    else
        printf '  ❌ %s：期望 %s，实际 %s\n' "$1" "$2" "$3"; FAIL=$((FAIL + 1))
    fi
}

expect_rejected() {
    if printf '%s' "$2" | grep -qiE "$3"; then
        printf '  ✅ %s（被拒：%s）\n' "$1" \
            "$(printf '%s' "$2" | grep -m1 -iE 'ERROR' | head -c 170)"
        PASS=$((PASS + 1))
    else
        printf '  ❌ %s：未按期望被拒（期望原因 /%s/）。实际输出：%s\n' \
            "$1" "$3" "$(printf '%s' "$2" | tr '\n' ' ' | head -c 220)"
        FAIL=$((FAIL + 1))
    fi
}

RLS_REJECT='violates row-level security policy|row-level security'
CHECK_REJECT='violates check constraint'
FK_REJECT='violates foreign key constraint|foreign key constraint'
SENTINEL_REJECT='禁止把全局哨兵租户'
# 运维通道被拒的原因**必须是权限**，不是 RLS：它带 BYPASSRLS，RLS 策略对它不生效。
# ⚠️ 断言里区分这两种拒因有实际用处：若这里出现的是 RLS 报错，说明夹具没把
#    BYPASSRLS 配上（那 P13 整组测的就不是运维通道，而是又一个 rag_app），
#    越权结论会一路假绿到线上。
PERM_REJECT='permission denied'

# ── 0. 准备临时库 ─────────────────────────────────────────
docker ps --format '{{.Names}}' | grep -qx "$CONTAINER" \
    || die "容器 $CONTAINER 未运行（./scripts/dev-postgres.sh start）"

say "重建临时库 $PROBE_DB"
docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d postgres -q -c \
    "DROP DATABASE IF EXISTS $PROBE_DB" >/dev/null 2>&1
docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d postgres -q -c \
    "CREATE DATABASE $PROBE_DB" >/dev/null 2>&1 || die "建库失败"

say "应用权威库结构 $(basename "$DDL_FILE")"
[ -f "$DDL_FILE" ] || die "找不到库结构权威源：$DDL_FILE"
{ printf 'SET search_path TO rag, public;\n'; cat "$DDL_FILE"; } \
    | docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d "$PROBE_DB" \
          -v ON_ERROR_STOP=1 -q >/dev/null 2>&1 \
    || die "库结构应用失败（临时库可能残留旧对象，可手动 DROP DATABASE $PROBE_DB 后重跑）"
say "  · 结构已应用（含 RLS 策略、CHECK、复合外键）"

say "创建非特权应用角色 $APP_ROLE（对齐 deploy/sql/rag/03_rag_roles.sql 的授权面）"
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

# 运维通道角色（P13 的主角）—— **直接执行真实发布脚本** 05_rag_ops_role.sql。
# ⚠️ 不照抄授权清单：照抄的版本会跟 05 漂移，于是 P13 测的是「我以为的形状」，
#    而生产上的 rag_ops 是另一个形状。让反证跑权威源本身，漂移就不可能存在。
#    （05 是幂等的：先 REVOKE ALL 再 GRANT，且角色名由 -v 传入，不会撞上真库角色。）
OPS_DDL="$SCRIPT_DIR/../deploy/sql/rag/05_rag_ops_role.sql"
[ -f "$OPS_DDL" ] || die "找不到运维通道发布脚本：$OPS_DDL"
say "以发布脚本建运维通道：deploy/sql/rag/$(basename "$OPS_DDL") -v rag_ops_role=$OPS_ROLE"
# ⚠️ 口令变量刻意不传：05 的 \if :{?rag_ops_password} 走「不设口令」分支
#    （本容器 trust 登录，探针只需要身份；把口令写进脚本只会让人以为它是凭据）。
if ! OPS_SETUP="$(docker exec -i "$CONTAINER" psql -U "$SUPER_USER" -d "$PROBE_DB" \
        -v rag_ops_role="$OPS_ROLE" -f - 2>&1 <<< "$(cat "$OPS_DDL")")"; then
    printf '%s\n' "$OPS_SETUP"
    die "运维通道发布脚本执行失败（见上方 psql 输出）"
fi
say "  · 05 的三段校验输出（授权面证据，人读用）："
printf '%s\n' "$OPS_SETUP" | sed 's/^/      /' | tail -32

# =====================================================================
# 夹具：全局语料链 + 两个租户各自的链
# ⚠️ 由超级用户写入 = 模拟「运维/迁移发布全局内容」这条通道；
#    应用角色写全局行是本脚本要否定的事（P4），不是夹具的一部分。
# ⚠️ 做成可重入函数：会改状态的探针组前重新播种，
#    保证断言只反映策略、不反映执行顺序（V7 那轮栽过一次）。
# =====================================================================
seed_fixture() {
    super_psql -q >/dev/null <<EOF
SET search_path TO rag, public;
DELETE FROM rag_answer_citation;
DELETE FROM rag_chunk_embedding;
DELETE FROM rag_chunk;
DELETE FROM rag_document;
DELETE FROM rag_edition;
DELETE FROM rag_source;
DELETE FROM rag_knowledge_prerequisite;
DELETE FROM rag_knowledge_point;

-- ① 平台全局链（tenant_id = 哨兵）
INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, trust_tier, isbn,
                        scope, ver_status, ver_method, ver_evidence, verified_at, license_status)
VALUES ('aaaaaaaa-0000-0000-0000-000000000001', '$GLOBAL_TENANT', 'TEXTBOOK', '全局教材',
        '人教', 'OFFICIAL_PUBLISHER', 'ISBN-GLOBAL-1', 'PLATFORM',
        'VERIFIED', 'ISBN', '平台核验', now(), 'LICENSED');
INSERT INTO rag_edition (edition_id, tenant_id, source_id, subject, grade, term, publisher, revision_code, scope)
VALUES ('aaaaaaaa-0000-0000-0000-0000000000e1', '$GLOBAL_TENANT',
        'aaaaaaaa-0000-0000-0000-000000000001', '数学', '一年级', '上册', '人教版', 'R1', 'PLATFORM');
INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, title, scope,
                          ver_status, retrievable, license_status)
VALUES ('aaaaaaaa-0000-0000-0000-0000000000d1', '$GLOBAL_TENANT',
        'aaaaaaaa-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-0000000000e1',
        '全局载体', 'PLATFORM', 'VERIFIED', true, 'LICENSED');
INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role, text, text_jieba,
                       page_number, section_path, char_start, char_end, content_hash, scope)
VALUES ('aaaaaaaa-0000-0000-0000-0000000000c1', '$GLOBAL_TENANT',
        'aaaaaaaa-0000-0000-0000-0000000000d1', 'aaaaaaaa-0000-0000-0000-0000000000e1',
        'EXPLANATION', '全局片段：二次函数的顶点', '全局 片段 二次 函数 顶点',
        1, '/1', 0, 10, '$HASH64', 'PLATFORM');
INSERT INTO rag_knowledge_point (kp_id, tenant_id, subject, grade, canonical_name, scope)
VALUES ('aaaaaaaa-0000-0000-0000-0000000000f1', '$GLOBAL_TENANT', '数学', '一年级', '二次函数', 'PLATFORM'),
       ('aaaaaaaa-0000-0000-0000-0000000000f2', '$GLOBAL_TENANT', '数学', '一年级', '一次函数', 'PLATFORM');
INSERT INTO rag_knowledge_prerequisite (tenant_id, kp_id, prereq_kp_id, scope)
VALUES ('$GLOBAL_TENANT', 'aaaaaaaa-0000-0000-0000-0000000000f1',
        'aaaaaaaa-0000-0000-0000-0000000000f2', 'PLATFORM');

-- ② 租户 T / U 各自的链
INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, trust_tier, isbn,
                        ver_status, ver_method, ver_evidence, verified_at, license_status)
VALUES ('bbbbbbbb-0000-0000-0000-000000000001', '$T_TENANT', 'TEXTBOOK', 'T 教材',
        '人教', 'OFFICIAL_PUBLISHER', 'ISBN-T', 'VERIFIED', 'ISBN', '租户核验', now(), 'LICENSED'),
       ('cccccccc-0000-0000-0000-000000000001', '$U_TENANT', 'TEXTBOOK', 'U 教材',
        '人教', 'OFFICIAL_PUBLISHER', 'ISBN-U', 'VERIFIED', 'ISBN', '租户核验', now(), 'LICENSED');
INSERT INTO rag_edition (edition_id, tenant_id, source_id, subject, grade, term, publisher, revision_code)
VALUES ('bbbbbbbb-0000-0000-0000-0000000000e1', '$T_TENANT', 'bbbbbbbb-0000-0000-0000-000000000001',
        '数学', '一年级', '上册', '人教版', 'R1'),
       ('cccccccc-0000-0000-0000-0000000000e1', '$U_TENANT', 'cccccccc-0000-0000-0000-000000000001',
        '数学', '一年级', '上册', '人教版', 'R1');
INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, title, ver_status, retrievable, license_status)
VALUES ('bbbbbbbb-0000-0000-0000-0000000000d1', '$T_TENANT', 'bbbbbbbb-0000-0000-0000-000000000001',
        'bbbbbbbb-0000-0000-0000-0000000000e1', 'T 载体', 'VERIFIED', true, 'LICENSED'),
       ('cccccccc-0000-0000-0000-0000000000d1', '$U_TENANT', 'cccccccc-0000-0000-0000-000000000001',
        'cccccccc-0000-0000-0000-0000000000e1', 'U 载体', 'VERIFIED', true, 'LICENSED');
INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role, text, text_jieba,
                       page_number, section_path, char_start, char_end, content_hash)
VALUES ('bbbbbbbb-0000-0000-0000-0000000000c1', '$T_TENANT', 'bbbbbbbb-0000-0000-0000-0000000000d1',
        'bbbbbbbb-0000-0000-0000-0000000000e1', 'EXPLANATION', 'T 私有片段', 'T 私有 片段',
        1, '/1', 0, 5, '$HASH64'),
       ('cccccccc-0000-0000-0000-0000000000c1', '$U_TENANT', 'cccccccc-0000-0000-0000-0000000000d1',
        'cccccccc-0000-0000-0000-0000000000e1', 'EXPLANATION', 'U 私有片段', 'U 私有 片段',
        1, '/1', 0, 5, '$HASH64');
EOF
}

say "播种夹具"
seed_fixture || die "夹具播种失败（检查 03_rag_roles.sql 的授权面是否与此脚本一致）"

# =====================================================================
# P0 环境自证：角色必须真的无特权
# =====================================================================
# ⚠️ 按 current_user 取属性而不是按角色名查表：
#    连库身份若悄悄变成超级用户，按名字查出来的仍是 f，整轮结论全假。
say "P0 环境自证（非特权角色 —— 否则以下所有结论都是假的）"
expect_eq "P0-1 连接身份非超级用户" "off" "$(app_psql <<< "SELECT current_setting('is_superuser')")"
expect_eq "P0-2 连接身份无 BYPASSRLS" "f" \
    "$(app_psql <<< "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user")"
expect_eq "P0-3 连接身份就是应用角色" "$APP_ROLE" "$(app_psql <<< "SELECT current_user")"
# search_path 真的生效（踩过：不生效时全部断言变成 relation does not exist 的假绿）
expect_eq "P0-4 能看到 rag_chunk（search_path 生效，反假绿；全局 1 + 私有 1）" "2" \
    "$(app_psql_as "$T_TENANT" 'SELECT count(*) FROM rag_chunk')"

# =====================================================================
# P1 全局语料整链可见 + 等值 JOIN 命中（功能是否真的生效）
# =====================================================================
say "P1 全局语料整链可见（哨兵表示法的核心收益：JOIN 不用改）"
# 检索侧真实 SQL 形态：四表等值 JOIN + tenant_id IN (本租户, 全局)
RETR_STYLE="SELECT count(*)
FROM rag_chunk c
JOIN rag_edition ed ON c.edition_id = ed.edition_id AND c.tenant_id = ed.tenant_id
JOIN rag_document doc ON c.document_id = doc.document_id AND c.tenant_id = doc.tenant_id
JOIN rag_source src ON doc.source_id = src.source_id AND c.tenant_id = src.tenant_id
WHERE c.tenant_id IN (rag_current_tenant(), rag_global_tenant_id())
  AND src.ver_status = 'VERIFIED' AND doc.ver_status = 'VERIFIED' AND doc.retrievable"
expect_eq "P1-1 租户 T 检索式 JOIN 命中 2 行（全局 1 + 私有 1）" "2" \
    "$(app_psql_as "$T_TENANT" "$RETR_STYLE")"
expect_eq "P1-2 租户 U 检索式 JOIN 同样命中 2 行" "2" \
    "$(app_psql_as "$U_TENANT" "$RETR_STYLE")"
expect_eq "P1-3 其中全局片段确实在结果里" "1" \
    "$(app_psql_as "$T_TENANT" "SELECT count(*)
       FROM rag_chunk c
       JOIN rag_edition ed ON c.edition_id = ed.edition_id AND c.tenant_id = ed.tenant_id
      WHERE c.tenant_id IN (rag_current_tenant(), rag_global_tenant_id())
        AND c.chunk_id = 'aaaaaaaa-0000-0000-0000-0000000000c1'")"
# 对照：只放开策略、不改应用谓词（老写法 = rag_current_tenant() 严格相等）
expect_eq "P1-4 ⚠️老写法（= rag_current_tenant()）仍看不到全局行" "1" \
    "$(app_psql_as "$T_TENANT" 'SELECT count(*) FROM rag_chunk c WHERE c.tenant_id = rag_current_tenant()')"
# 对照：NULL 表示法下等值 JOIN 恒 0 行（V8 文件头 ②），这里用哨兵值不成立，
#        但把 JOIN 换成 IS NOT DISTINCT FROM 也应得到同样 2 行 —— 说明两种写法
#        在哨兵下等价，不需要逐处改写 JOIN。
expect_eq "P1-5 JOIN 用 IS NOT DISTINCT FROM 结果不变（哨兵非 NULL）" "2" \
    "$(app_psql_as "$T_TENANT" "SELECT count(*) FROM rag_chunk c
        JOIN rag_edition ed ON c.edition_id = ed.edition_id
            AND c.tenant_id IS NOT DISTINCT FROM ed.tenant_id
        JOIN rag_document doc ON c.document_id = doc.document_id
            AND c.tenant_id IS NOT DISTINCT FROM doc.tenant_id
        JOIN rag_source src ON doc.source_id = src.source_id
            AND c.tenant_id IS NOT DISTINCT FROM src.tenant_id
        WHERE c.tenant_id IN (rag_current_tenant(), rag_global_tenant_id())")"

# =====================================================================
# P2 隔离：别人的私有行不可见
# =====================================================================
say "P2 租户私有行互不可见"
expect_eq "P2-1 T 看不见 U 的片段" "0" \
    "$(app_psql_as "$T_TENANT" "SELECT count(*) FROM rag_chunk
        WHERE chunk_id='cccccccc-0000-0000-0000-0000000000c1'")"
expect_eq "P2-2 U 看不见 T 的片段" "0" \
    "$(app_psql_as "$U_TENANT" "SELECT count(*) FROM rag_chunk
        WHERE chunk_id='bbbbbbbb-0000-0000-0000-0000000000c1'")"
expect_eq "P2-3 T 看不见 U 的来源" "0" \
    "$(app_psql_as "$T_TENANT" "SELECT count(*) FROM rag_source
        WHERE source_id='cccccccc-0000-0000-0000-000000000001'")"

# =====================================================================
# P3 fail-closed 重述：无租户上下文只见全局
# =====================================================================
say "P3 fail-closed 重述（无租户上下文 = 只见 scope='PLATFORM'）"
expect_eq "P3-1 无上下文可见 rag_chunk 数（= 全局片段数）" "1" \
    "$(app_psql_as '' 'SELECT count(*) FROM rag_chunk')"
expect_eq "P3-2 无上下文可见的租户私有行数（必须 0）" "0" \
    "$(app_psql_as '' "SELECT count(*) FROM rag_chunk WHERE scope <> 'PLATFORM'")"
expect_eq "P3-3 无上下文可见 rag_source 数" "1" \
    "$(app_psql_as '' 'SELECT count(*) FROM rag_source')"
expect_eq "P3-4 无上下文可见知识点数（2 个全局）" "2" \
    "$(app_psql_as '' 'SELECT count(*) FROM rag_knowledge_point')"
# ⚠️ 这是重述后的底线表达式，启动自检/worker 预检必须与它逐字同构：
#    「无租户可见集合 ⊆ scope='PLATFORM'」而不是「可见 0 行」。
expect_eq "P3-5 无上下文写 rag_chunk 被拒（写策略不含哨兵分支）" "0" \
    "$(app_psql_as '' "INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role,
             text, page_number, section_path, char_start, char_end, content_hash)
        VALUES ('dddddddd-0000-0000-0000-0000000000c1', rag_current_tenant(),
                'aaaaaaaa-0000-0000-0000-0000000000d1', 'aaaaaaaa-0000-0000-0000-0000000000e1',
                'EXPLANATION', 'x', 1, '/1', 0, 1, '$HASH64');
        SELECT count(*) FROM rag_chunk WHERE chunk_id='dddddddd-0000-0000-0000-0000000000c1'" | tail -1)"

# =====================================================================
# P4 越权写：租户能否往全局桶里塞内容
# =====================================================================
say "P4 租户写全局行（必须被 RLS 拒）"
P4A="$(app_psql_as "$T_TENANT" "INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id,
        role, text, page_number, section_path, char_start, char_end, content_hash, scope)
        VALUES ('dddddddd-0000-0000-0000-0000000000c2', '$GLOBAL_TENANT',
                'aaaaaaaa-0000-0000-0000-0000000000d1', 'aaaaaaaa-0000-0000-0000-0000000000e1',
                'EXPLANATION', '偷渡的全局片段', 1, '/1', 0, 3, '$HASH64', 'PLATFORM')")"
expect_rejected "P4-1 显式写哨兵租户被 RLS 拒" "$P4A" "$RLS_REJECT"
expect_eq "P4-2 偷渡行确实不存在（超级用户核对）" "0" \
    "$(super_val "SELECT count(*) FROM rag_chunk WHERE chunk_id='dddddddd-0000-0000-0000-0000000000c2'")"

# 反假绿：同一角色写**自己**的行必须成功，否则 P4-1 的「被拒」可能只是环境坏了
expect_eq "P4-3 同角色写本租户行正常成功（反假绿）" "1" \
    "$(app_psql_as "$U_TENANT" "INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id,
             role, text, page_number, section_path, char_start, char_end, content_hash)
             VALUES ('dddddddd-0000-0000-0000-0000000000c3', rag_current_tenant(),
                     'cccccccc-0000-0000-0000-0000000000d1', 'cccccccc-0000-0000-0000-0000000000e1',
                     'EXPLANATION', '正常租户片段', 1, '/1', 0, 3, '$HASH64');
             SELECT count(*) FROM rag_chunk WHERE chunk_id='dddddddd-0000-0000-0000-0000000000c3'" | tail -1)"

# 把本租户行「自封」为全局：scope 想升 PLATFORM，tenant_id 不变
P4D="$(app_psql_as "$T_TENANT" "UPDATE rag_chunk SET scope='PLATFORM'
        WHERE chunk_id='bbbbbbbb-0000-0000-0000-0000000000c1'")"
expect_rejected "P4-4 自封全局（改 scope 不改租户）被拒" "$P4D" "$CHECK_REJECT|$RLS_REJECT"

# =====================================================================
# P5 越权改/删全局行
# =====================================================================
say "P5 租户改/删全局行（读写分策略的关键交互，只给事实不给推断）"
app_psql_as "$T_TENANT" "UPDATE rag_chunk SET text='租户篡改全局'
    WHERE chunk_id='aaaaaaaa-0000-0000-0000-0000000000c1'" >/dev/null 2>&1
expect_eq "P5-1 全局片段文案未被租户 UPDATE 改动" "全局片段：二次函数的顶点" \
    "$(super_val "SELECT text FROM rag_chunk WHERE chunk_id='aaaaaaaa-0000-0000-0000-0000000000c1'")"

app_psql_as "$T_TENANT" "DELETE FROM rag_chunk
    WHERE chunk_id='aaaaaaaa-0000-0000-0000-0000000000c1'" >/dev/null 2>&1
expect_eq "P5-2 全局片段仍存活（租户 DELETE 删不到）" "1" \
    "$(super_val "SELECT count(*) FROM rag_chunk WHERE chunk_id='aaaaaaaa-0000-0000-0000-0000000000c1'")"

# 显式带租户谓词的删（应用真实写法）同样删不到
seed_fixture
expect_eq "P5-3 显式租户谓词的 DELETE 也删不到全局行" "1" \
    "$(app_psql_as "$T_TENANT" "DELETE FROM rag_chunk
             WHERE tenant_id = rag_current_tenant()
               AND chunk_id='aaaaaaaa-0000-0000-0000-0000000000c1';
             SELECT count(*) FROM rag_chunk
             WHERE chunk_id='aaaaaaaa-0000-0000-0000-0000000000c1'" | tail -1)"

# =====================================================================
# P6 哨兵身份伪装（本方案唯一新增攻击面）
# =====================================================================
say "P6 会话把 app.tenant_id 设成哨兵（必须 fail-loud）"
P6A="$(app_psql_as "$GLOBAL_TENANT" 'SELECT count(*) FROM rag_chunk')"
expect_rejected "P6-1 以哨兵为身份取租户上下文被拒" "$P6A" "$SENTINEL_REJECT"
# 读策略也走同一个函数，所以「伪装成平台读全库」这条路同样被封
P6B="$(app_psql_as "$GLOBAL_TENANT" 'SELECT count(*) FROM rag_source')"
expect_rejected "P6-2 以哨兵身份读 rag_source 同样被拒" "$P6B" "$SENTINEL_REJECT"
# ⚠️ 对照组：不加固的 rag_current_tenant()（V1 版只做 cast）会让 P6-1 **成功返回 1**，
#    即任何租户都能以平台身份改写全局语料。这里把「加固是承重的」钉成断言：
    # 用超级用户直接写哨兵行是允许的（那是运维通道），但应用角色即使绕过函数
    # 也过不了写策略 —— P4-1 已证。

# =====================================================================
# P7 CHECK 不变式（scope 与租户必须同向）
# =====================================================================
say "P7 不变式：scope 与 tenant_id 必须同向"
# 用超级用户绕开 RLS，单独测 CHECK，否则分不清是被 RLS 拒还是被 CHECK 拒
P7A="$(super_val "INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role,
    text, page_number, section_path, char_start, char_end, content_hash, scope)
    VALUES ('eeeeeeee-0000-0000-0000-0000000000c1', '$T_TENANT',
            'bbbbbbbb-0000-0000-0000-0000000000d1', 'bbbbbbbb-0000-0000-0000-0000000000e1',
            'EXPLANATION', '半全局', 1, '/1', 0, 2, '$HASH64', 'PLATFORM')")"
expect_rejected "P7-1 带租户却标 PLATFORM 被 CHECK 拒" "$P7A" "$CHECK_REJECT"
P7B="$(super_val "INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role,
    text, page_number, section_path, char_start, char_end, content_hash, scope)
    VALUES ('eeeeeeee-0000-0000-0000-0000000000c2', '$GLOBAL_TENANT',
            'aaaaaaaa-0000-0000-0000-0000000000d1', 'aaaaaaaa-0000-0000-0000-0000000000e1',
            'EXPLANATION', '半租户', 1, '/1', 0, 2, '$HASH64', 'TENANT')")"
expect_rejected "P7-2 哨兵租户却标 TENANT 被 CHECK 拒" "$P7B" "$CHECK_REJECT"

# =====================================================================
# P8 引用完整性：复合外键对全局行仍然管用
# =====================================================================
# ⚠️ 这条是「为什么不用 NULL」的第一手证据：
#    NULL 表示法下 FK 任一列为 NULL 即不校验（MATCH SIMPLE），
#    实测能把孤儿片段插进去（见 V8 文件头 ①）；哨兵表示法照常拦截。
say "P8 复合外键对全局行仍然校验"
P8A="$(super_val "INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role,
    text, page_number, section_path, char_start, char_end, content_hash, scope)
    VALUES ('ffffffff-0000-0000-0000-0000000000c1', '$GLOBAL_TENANT',
            'ffffffff-9999-9999-9999-999999999999', 'aaaaaaaa-0000-0000-0000-0000000000e1',
            'EXPLANATION', '孤儿全局片段', 1, '/1', 0, 2, '$HASH64', 'PLATFORM')")"
expect_rejected "P8-1 指向不存在载体的全局片段被 FK 拒" "$P8A" "$FK_REJECT"
expect_eq "P8-2 孤儿片段确实不存在" "0" \
    "$(super_val "SELECT count(*) FROM rag_chunk WHERE chunk_id='ffffffff-0000-0000-0000-0000000000c1'")"

# =====================================================================
# P9 租户答案可以引用全局片段（citations 外键已降为单列）
# =====================================================================
say "P9 引用全局片段"
P9A="$(app_psql_as "$T_TENANT" "INSERT INTO rag_answer_citation
        (citation_id, tenant_id, chunk_id, supported_claim, verbatim_quote, verdict,
         entailment_score, edition_snapshot)
        VALUES (gen_random_uuid(), rag_current_tenant(),
                'aaaaaaaa-0000-0000-0000-0000000000c1', '二次函数顶点公式',
                '顶点式 y=a(x-h)^2+k', 'GROUNDED', 0.97, '{}'::jsonb);
        SELECT count(*) FROM rag_answer_citation
         WHERE chunk_id='aaaaaaaa-0000-0000-0000-0000000000c1'" | tail -1)"
expect_eq "P9-1 租户答案引用全局片段写成功（1 行）" "1" "$P9A"
expect_eq "P9-2 该 citation 对其他租户不可见" "0" \
    "$(app_psql_as "$U_TENANT" "SELECT count(*) FROM rag_answer_citation
        WHERE chunk_id='aaaaaaaa-0000-0000-0000-0000000000c1'")"
# 反假绿：引用一条**不存在**的片段必须被 FK 拒（单列化不等于放松约束）
P9C="$(app_psql_as "$T_TENANT" "INSERT INTO rag_answer_citation
        (citation_id, tenant_id, chunk_id, supported_claim, verbatim_quote, verdict,
         entailment_score, edition_snapshot)
        VALUES (gen_random_uuid(), rag_current_tenant(),
                'deadbeef-0000-0000-0000-000000000000', 'x', 'y',
                'GROUNDED', 0.5, '{}'::jsonb)")"
expect_rejected "P9-3 引用不存在的片段仍被 FK 拒" "$P9C" "$FK_REJECT"

# =====================================================================
# P10 全局先决关系边（NULL 表示法下根本插不进去）
# =====================================================================
say "P10 全局知识点先决关系（该表主键含 tenant_id）"
expect_eq "P10-1 租户 T 能读到全局先决边" "1" \
    "$(app_psql_as "$T_TENANT" 'SELECT count(*) FROM rag_knowledge_prerequisite')"
expect_eq "P10-2 无上下文也能读到（全局共享）" "1" \
    "$(app_psql_as '' 'SELECT count(*) FROM rag_knowledge_prerequisite')"
# ⚠️ 断言别看「全局边总数」：夹具本来就有 1 条全局边，
#    偷渡成功与否从那个数字上根本看不出来（本轮第一版就是这么写错的，
#    期望 0、实际 1，看着像越权成功，其实是数错了对象）。
P10C="$(app_psql_as "$T_TENANT" "INSERT INTO rag_knowledge_prerequisite
        (tenant_id, kp_id, prereq_kp_id, scope)
        VALUES ('$GLOBAL_TENANT', 'aaaaaaaa-0000-0000-0000-0000000000f1',
                'aaaaaaaa-0000-0000-0000-0000000000f2', 'PLATFORM')")"
expect_rejected "P10-3 租户写不出全局先决边" "$P10C" "$RLS_REJECT"
expect_eq "P10-4 全局先决边仍只有夹具那 1 条" "1" \
    "$(super_val "SELECT count(*) FROM rag_knowledge_prerequisite WHERE scope='PLATFORM'")"

# =====================================================================
# P11 字典表已收敛到同一表示法（V7 的 NULL 已被 V8 回填）
# =====================================================================
say "P11 字典表示法统一"
expect_eq "P11-1 字典表不再有 NULL 租户行" "0" \
    "$(super_val "SELECT count(*) FROM rag_dictionary WHERE tenant_id IS NULL")"
super_psql -q >/dev/null <<EOF
SET search_path TO rag, public;
DELETE FROM rag_dictionary;
INSERT INTO rag_dictionary (tenant_id, dict_type, code, label, sort_order, scope)
VALUES ('$GLOBAL_TENANT', 'SOURCE_TYPE', 'TEXTBOOK', '教材（平台）', 10, 'PLATFORM'),
       ('$T_TENANT',      'SOURCE_TYPE', 'TEXTBOOK', '教材（T 覆盖）', 10, 'TENANT');
EOF
expect_eq "P11-2 全局字典项对租户 U 可见" "1" \
    "$(app_psql_as "$U_TENANT" "SELECT count(*) FROM rag_dictionary WHERE code='TEXTBOOK'")"
expect_eq "P11-3 T 的覆盖项对 U 不可见（只见全局那行）" "教材（平台）" \
    "$(app_psql_as "$U_TENANT" "SELECT label FROM rag_dictionary WHERE code='TEXTBOOK'")"
expect_eq "P11-4 哨兵行仍参与普通唯一键（upsert 探得到冲突）" "1" \
    "$(super_val "INSERT INTO rag_dictionary (tenant_id, dict_type, code, label, sort_order, scope)
        VALUES ('$GLOBAL_TENANT','SOURCE_TYPE','TEXTBOOK','教材（平台/改名）',10,'PLATFORM')
        ON CONFLICT (tenant_id, dict_type, code) DO UPDATE SET label=EXCLUDED.label;
        SELECT count(*) FROM rag_dictionary
        WHERE tenant_id='$GLOBAL_TENANT' AND code='TEXTBOOK'")"

# =====================================================================
# P12 系统表自证（装配完整性，防静默退化）
# =====================================================================
say "P12 系统表自证"
DUAL_TABLES="rag_source rag_edition rag_document rag_chunk rag_chunk_embedding \
rag_knowledge_point rag_knowledge_prerequisite rag_dictionary"
for t in $DUAL_TABLES; do
    expect_eq "P12-[$t] 恰好 4 条读写分策略" "4" \
        "$(super_val "SELECT count(*) FROM pg_policies WHERE tablename='$t'")"
    # ⚠️ 只扫 INSERT/DELETE：_read 含哨兵是应该的，一起扫会得到恒假的自检。
    expect_eq "P12-[$t] 写/删策略不含全局哨兵" "0" \
        "$(super_val "SELECT count(*) FROM pg_policies WHERE tablename='$t'
            AND cmd IN ('INSERT','DELETE')
            AND (qual || coalesce(with_check,'')) LIKE '%rag_global_tenant_id%'")"
    expect_eq "P12-[$t] 仍 FORCE ROW LEVEL SECURITY" "t" \
        "$(super_val "SELECT relforcerowsecurity FROM pg_class WHERE relname='$t'")"
    expect_eq "P12-[$t] tenant_id 仍 NOT NULL（哨兵表示法的前提）" "NO" \
        "$(super_val "SELECT is_nullable FROM information_schema.columns
            WHERE table_schema='rag' AND table_name='$t' AND column_name='tenant_id'")"
done
expect_eq "P12 citation 外键已降为单列" "1" \
    "$(super_val "SELECT array_length(conkey,1) FROM pg_constraint
        WHERE conrelid='rag.rag_answer_citation'::regclass AND conname='rag_citation_chunk_fk'")"
expect_eq "P12 语料表 scope 判别列齐备" "8" \
    "$(super_val "SELECT count(*) FROM information_schema.columns
        WHERE table_schema='rag' AND column_name='scope'")"
expect_eq "P12 未建租户上下文时 rag_current_tenant() 为 NULL（不是报错）" "<null>" \
    "$(app_psql_as '' "SELECT coalesce(rag_current_tenant()::text, '<null>')")"

# =====================================================================
# P13 运维通道（rag_ops）：全局桶的唯一写入口 —— E 类
# ⚠️ 第二刀新加。P4/P5/P10 钉的是「应用角色写不出全局行」，
#    第二刀不是去放宽它（放宽它 = 给每个租户都发一条够得着全局桶的写路径），
#    而是**另开一个带 BYPASSRLS 的独立角色**。于是必须同时钉住三件事：
#      ① 新通道真的能写哨兵桶，且写出来的行租户在应用上下文里读得到；
#      ② 新通道的授权面比 rag_app 还窄（无 DELETE、无未授权表的 UPDATE、
#         无 schema 建对象权、无成员关系）；
#      ③ 原闸门一处没动（rag_app 写全局仍被 RLS 拒、CHECK 仍绕不掉）。
#    BYPASSRLS 的**代价**也在这一组如实测出来（P13-18，范围量在 P13-19~22）：
#    它跨租户读得到私有行。这是方案 §1.3 记录的既有取舍，不是本次新发现的漏洞，
#    把它钉成断言是为了让「ops 角色只碰得到全局桶」这种误解无法成立。
# =====================================================================
say "P13 运维通道 rag_ops"
seed_fixture

# ── 13.1 通道身份自证（属性错则整组结论假） ──
expect_eq "P13-1 ops 连接身份非超级用户" "off" "$(ops_psql <<< "SELECT current_setting('is_superuser')")"
expect_eq "P13-2 ops 连接身份确实带 BYPASSRLS" "t" \
    "$(ops_psql <<< "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user")"
expect_eq "P13-3 ops 连接身份就是运维角色" "$OPS_ROLE" "$(ops_psql <<< "SELECT current_user")"
expect_eq "P13-4 ops 不是任何角色的成员（能力不能靠继承拿到）" "0" \
    "$(super_val "SELECT count(*) FROM pg_auth_members am
        JOIN pg_roles m ON m.oid = am.roleid
        WHERE am.member = (SELECT oid FROM pg_roles WHERE rolname = '$OPS_ROLE')")"
expect_eq "P13-5 ops 带角色级 search_path（未限定名解析得到，反假绿）" "1" \
    "$(ops_psql <<< "SELECT count(*) FROM rag_chunk WHERE chunk_id='aaaaaaaa-0000-0000-0000-0000000000c1'")"

# ── 13.6 通道真的能写全局桶（否则控制台的 G 档写是空头承诺） ──
# ⚠️ 写面按**代码里真实的全局写路径**对账，不是按「运维大概要写哪些表」想象：
#    JdbcCorpusManagementRepository 的全局 WriteChannel 写 rag_source / rag_document /
#    rag_dictionary，JdbcCorpusRepository 的全局索引路径只 UPDATE rag_chunk
#    + INSERT rag_chunk_embedding。片段的**产生**不在治理通道里，所以
#    ops 对 rag_chunk 只有 UPDATE 没有 INSERT —— P13-7 专门钉这条差异：
#    它既证明授权面贴着代码，也证明「全局片段由治理通道凭空插入」不成立。
OPS_SRC="$(ops_psql <<< "INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher,
    trust_tier, isbn, scope, ver_status, license_status)
    VALUES ('90909090-0000-0000-0000-000000000001', '$GLOBAL_TENANT', 'TEXTBOOK',
            '运维登记的全局教材', '人教', 'OFFICIAL_PUBLISHER', 'ISBN-OPS-1',
            'PLATFORM', 'PENDING', 'LICENSED')")"
expect_eq "P13-6 ops 写全局 rag_source 成功（1 行）" "1" \
    "$(super_val "SELECT count(*) FROM rag_source WHERE source_id='90909090-0000-0000-0000-000000000001'")"
[ -z "$OPS_SRC" ] || printf '  ⚠️ ops 写来源的回显：%s\n' "$(printf '%s' "$OPS_SRC" | tr '\n' ' ' | head -c 200)"

P13I="$(ops_psql <<< "INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role,
    text, page_number, section_path, char_start, char_end, content_hash, scope)
    VALUES ('90909090-0000-0000-0000-0000000000c1', '$GLOBAL_TENANT',
            'aaaaaaaa-0000-0000-0000-0000000000d1', 'aaaaaaaa-0000-0000-0000-0000000000e1',
            'EXPLANATION', '运维凭空造一个全局片段', 1, '/1', 0, 3, '$HASH64', 'PLATFORM')")"
expect_rejected "P13-7 ops 对 rag_chunk 只有 UPDATE，插不出新片段（写面贴代码，不是按想象给权）" "$P13I" "$PERM_REJECT"

# ops 改得动已有的全局片段（这是治理通道对 rag_chunk 的真实能力）
P13U="$(ops_psql <<< "UPDATE rag_chunk SET text='运维改写的全局片段'
        WHERE chunk_id='aaaaaaaa-0000-0000-0000-0000000000c1'")"
expect_eq "P13-8 ops 改得动全局片段（UPDATE 在授权面内）" "运维改写的全局片段" \
    "$(super_val "SELECT text FROM rag_chunk WHERE chunk_id='aaaaaaaa-0000-0000-0000-0000000000c1'")"

# 闭环：ops 的改写必须能被租户在**应用上下文**里读到，
# 否则「平台在控制台维护全局语料」只是写进去了而已，检索面拿不到。
expect_eq "P13-9 ✅ 闭环：租户 T 应用上下文读到 ops 改写后的文本" "运维改写的全局片段" \
    "$(app_psql_as "$T_TENANT" "SELECT text FROM rag_chunk
        WHERE chunk_id='aaaaaaaa-0000-0000-0000-0000000000c1'")"

# ── 13.10 原闸门一处没动 ──
P13B="$(app_psql_as "$T_TENANT" "INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id,
        role, text, page_number, section_path, char_start, char_end, content_hash, scope)
        VALUES ('90909090-0000-0000-0000-0000000000c2', '$GLOBAL_TENANT',
                'aaaaaaaa-0000-0000-0000-0000000000d1', 'aaaaaaaa-0000-0000-0000-0000000000e1',
                'EXPLANATION', '第二刀后仍能偷渡吗', 1, '/1', 0, 3, '$HASH64', 'PLATFORM')")"
expect_rejected "P13-10 ⛔ 第二刀没有放宽 rag_app：应用角色写哨兵桶仍被 RLS 拒" "$P13B" "$RLS_REJECT"
expect_eq "P13-11 偷渡行确实不存在" "0" \
    "$(super_val "SELECT count(*) FROM rag_chunk WHERE chunk_id='90909090-0000-0000-0000-0000000000c2'")"

# BYPASSRLS 绕不掉 CHECK：半全局行仍然插不进去（用 ops **有** INSERT 权的表测，
# 否则先撞权限错、根本走不到 CHECK —— 上一版就错在这里，实测看到的是
# 「permission denied」而不是「violates check constraint」）
P13C="$(ops_psql <<< "INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher,
    trust_tier, isbn, scope, ver_status, license_status)
    VALUES ('90909090-0000-0000-0000-000000000002', '$T_TENANT', 'TEXTBOOK',
            '运维把租户来源升全局', '人教', 'OFFICIAL_PUBLISHER', 'ISBN-OPS-2',
            'PLATFORM', 'PENDING', 'LICENSED')")"
expect_rejected "P13-12 BYPASSRLS 绕不掉库内 CHECK（scope 与租户必须同向）" "$P13C" "$CHECK_REJECT"

# ── 13.13 ops 的授权面比 rag_app 还窄 ──
P13D="$(ops_psql <<< "DELETE FROM rag_chunk WHERE chunk_id='aaaaaaaa-0000-0000-0000-0000000000c1'")"
expect_rejected "P13-13 ops 无 DELETE（全局桶没有删除语义）" "$P13D" "$PERM_REJECT"
P13E="$(ops_psql <<< "DELETE FROM rag_access_audit WHERE 1=0")"
expect_rejected "P13-14 ops 删不动审计行（append-only）" "$P13E" "$PERM_REJECT"
P13F="$(ops_psql <<< "UPDATE rag_knowledge_point SET canonical_name='运维改写'
        WHERE kp_id='aaaaaaaa-0000-0000-0000-0000000000f1'")"
expect_rejected "P13-15 ops 对未授权表无 UPDATE（知识点不在治理写面内）" "$P13F" "$PERM_REJECT"
P13G="$(ops_psql <<< "CREATE TABLE rag.probe_should_fail (id int)")"
expect_rejected "P13-16 ops 无 schema 建对象权" "$P13G" "$PERM_REJECT"
# 反假绿：同一连接对**已授权**的表写成功，说明上面四条拒因真是权限而不是连接坏了
expect_eq "P13-17 同连接写已授权表仍成功（反假绿：拒因是权限，不是环境）" "1" \
    "$(ops_psql <<< "UPDATE rag_source SET title='运维改过的全局教材标题'
            WHERE source_id='90909090-0000-0000-0000-000000000001';
            SELECT count(*) FROM rag_source
            WHERE source_id='90909090-0000-0000-0000-000000000001'
              AND title='运维改过的全局教材标题'" | tail -1)"

# ── 13.18 BYPASSRLS 的代价（如实记录，不是新发现的漏洞） ──
expect_eq "P13-18 ⚠️ 代价：ops 无租户上下文也读得到租户私有行（RLS 对它不生效）" "1" \
    "$(ops_psql <<< "SELECT count(*) FROM rag_chunk
        WHERE chunk_id='cccccccc-0000-0000-0000-0000000000c1'")"
# P13-19~21 把上面那条的**范围**量出来：读=全库 11 张、写=6 张、删=0 张。
# ⚠️ 用 has_table_privilege 而不是 information_schema.role_table_grants：
#    后者受「当前用户可见性」过滤 —— 实测（本临时库）：超级用户查得到别人的授权
#    （05 的 §3.2 就是靠它打印证据的），但非超级用户查别人的 grantee 得 **0 行**，
#    看着像「授权面比设计更窄」，其实是视图根本不给你看。
#    has_table_privilege 按**角色名**判定，与谁来问无关，两种身份下同一口径。
expect_eq "P13-19 ops 读面 = 11 张表全库（跨租户读得到，限长只能在应用侧）" "11" \
    "$(super_val "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname='rag' AND c.relkind='r'
          AND has_table_privilege('$OPS_ROLE', c.oid, 'SELECT')")"
expect_eq "P13-20 ops 写面恰好 6 张表（全局治理真正要写的，不多一张）" "6" \
    "$(super_val "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname='rag' AND c.relkind='r'
          AND (has_table_privilege('$OPS_ROLE', c.oid, 'INSERT')
            OR has_table_privilege('$OPS_ROLE', c.oid, 'UPDATE'))")"
expect_eq "P13-21 ops 删除面 = 0 张表（一条都不给）" "0" \
    "$(super_val "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname='rag' AND c.relkind='r'
          AND has_table_privilege('$OPS_ROLE', c.oid, 'DELETE')")"
# 对照：应用角色的删除面是**满的**（租户语料要能撤回/清理）。
# ⚠️ 这条不是冗余：它证明「P13-21 的 0」来自授权差异，而不是整个库都没 DELETE 权。
expect_eq "P13-22 对照：rag_app 仍有删除权（差异来自授权面，不是全局没收权）" "11" \
    "$(super_val "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname='rag' AND c.relkind='r'
          AND has_table_privilege('$APP_ROLE', c.oid, 'DELETE')")"

# ── 13.23 角色级默认值（05 的三条护栏 + 一条刻意不设） ──
expect_eq "P13-23 ops 的角色级默认里**没有** app.tenant_id（连接池不能继承上一个持有者的作用域）" "0" \
    "$(super_val "SELECT count(*) FROM pg_roles WHERE rolname='$OPS_ROLE'
        AND array_to_string(rolconfig, ',') LIKE '%app.tenant_id%'")"
expect_eq "P13-24 ops 带 statement_timeout（长事务卡全局桶由库层打断）" "1" \
    "$(super_val "SELECT count(*) FROM pg_roles WHERE rolname='$OPS_ROLE'
        AND array_to_string(rolconfig, ',') LIKE '%statement_timeout%'")"
expect_eq "P13-25 ops 带 idle_in_transaction_session_timeout" "1" \
    "$(super_val "SELECT count(*) FROM pg_roles WHERE rolname='$OPS_ROLE'
        AND array_to_string(rolconfig, ',') LIKE '%idle_in_transaction%'")"
# 同一道门的另一半：主数据源角色绝不能有 BYPASSRLS（否则 ops 通道形同虚设）
expect_eq "P13-26 应用角色仍无 BYPASSRLS（两条通道的身份不可互换）" "f" \
    "$(super_val "SELECT rolbypassrls FROM pg_roles WHERE rolname='$APP_ROLE'")"

# =====================================================================
# 汇总
# =====================================================================
echo
say "反证汇总：通过 $PASS 项，失败 $FAIL 项"
if [ "$FAIL" -gt 0 ]; then
    printf '\033[0;31m✗ 存在失败项 —— V8 的全局/租户隔离语义不可信，勿上线\033[0m\n'
    exit 1
fi
printf '\033[0;32m✓ 全部通过：全局语料整链可被任一租户召回，私有语料严格隔离，\033[0m\n'
printf '\033[0;32m  应用角色既写不出也改不动全局行，且无法把会话身份伪装成哨兵；\033[0m\n'
printf '\033[0;32m  运维通道写得进哨兵桶、删不动任何行、读得到全部（代价已量出），\033[0m\n'
printf '\033[0;32m  且它的存在没有让 rag_app 的闸门松一分。\033[0m\n'
printf '  临时库 %s 保留供复查，可执行：\n' "$PROBE_DB"
printf '    docker exec -it %s psql -U %s -d %s\n' "$CONTAINER" "$APP_ROLE" "$PROBE_DB"
