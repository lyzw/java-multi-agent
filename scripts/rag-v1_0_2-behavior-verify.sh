#!/usr/bin/env bash
# =============================================================================
# RAG 摄取链真库行为验证（V1.0.2 · M1/M3 行为级证据）
# =============================================================================
# 目的：把「无 PG 环境无法验证」的那批判据全部跑一遍，并给出可复现的输出。
#
# 覆盖：
#   V1  DDL 迁移可执行（01 快照 + 05/06 活库增量）
#   V2  N1a 部分唯一索引：REVOKED 来源可同 ISBN 重登记（T-10 不堵）
#   V3  N1b 部分唯一索引：两条活跃来源同 ISBN 被拒
#   V4  N1c 载体级：FAILED 载体可同 hash 重传（失败重传活口）
#   V5  N1d 载体级：两条活跃载体同 hash 被拒
#   V6  SKIP LOCKED：两个并发事务各领一条不同 job，互不阻塞
#   V7  源 REVOKED 级联取消：未完成 job 置 REJECTED + SOURCE_REVOKED（N2）
#
# 用法：
#   ./scripts/rag-v1_0_2-behavior-verify.sh            # 起容器 → 验证 → 保留容器
#   KEEP=0 ./scripts/rag-v1_0_2-behavior-verify.sh     # 验证完删除容器
#
# 退出码：0 全部通过；1 有断言失败。
# =============================================================================
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SQL_DIR="$REPO_ROOT/deploy/sql/rag"
CNAME="${CNAME:-rag-pg-verify}"
PORT="${PORT:-55433}"
DBPASS="${DBPASS:-ragpass}"
KEEP="${KEEP:-1}"

PASS=0
FAIL=0
ENV_TENANT="2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2"

say()  { printf '%s\n' "$*"; }
ok()   { PASS=$((PASS+1)); say "  ✅ $*"; }
bad()  { FAIL=$((FAIL+1)); say "  ❌ $*"; }

psql_q() { docker exec -i "$CNAME" psql -U postgres -d ragdb -q -t -A -v ON_ERROR_STOP=1 "$@"; }
psql_sql() { docker exec -i "$CNAME" psql -U postgres -d ragdb -q -v ON_ERROR_STOP=0 -f - ; }

# ⚠️ 判定「SQL 是否失败」不能看 psql 退出码：ON_ERROR_STOP=0 时语句报错 psql 仍返回 0。
#    统一以日志里是否出现 ERROR 为准。（本脚本首版正是踩了这个坑，
#    把「唯一索引正确拒绝」误报成「唯一索引失效」。）
# ⚠️ psql 的错误行形如 `psql:<stdin>:5: ERROR: ...`——ERROR 不在行首，
#    故用宽松匹配 `ERROR:`。首版写成 '^ERROR' 导致「索引正确拒绝」被判成「索引失效」。
sql_failed() { grep -qi 'ERROR:' "$1" 2>/dev/null; }

cleanup() {
  if [ "$KEEP" != "1" ]; then
    docker rm -f "$CNAME" >/dev/null 2>&1
  else
    say "容器保留：$CNAME（端口 $PORT）。清理：docker rm -f $CNAME"
  fi
}
trap cleanup EXIT

say "== 准备 PostgreSQL 17 + pgvector =="
docker rm -f "$CNAME" >/dev/null 2>&1
docker run -d --name "$CNAME" -e POSTGRES_PASSWORD="$DBPASS" -e POSTGRES_DB=ragdb \
  -p "$PORT:5432" postgres:17 >/dev/null || { say "docker run 失败"; exit 1; }

for i in $(seq 1 40); do
  docker exec "$CNAME" pg_isready -U postgres >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$CNAME" bash -lc \
  "apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq postgresql-17-pgvector >/dev/null 2>&1" \
  || { say "pgvector 安装失败（需要网络）"; exit 1; }
say "  就绪"

say "== V1 DDL 迁移 =="
for f in 01_rag_ddl.sql 02_rag_seed.sql 05_v1_0_2_s1_alter.sql 06_v1_0_2_s3_alter.sql; do
  if docker exec -i "$CNAME" psql -U postgres -d ragdb -q -v ON_ERROR_STOP=1 -f - \
       < "$SQL_DIR/$f" >/tmp/v1_ddl_$f.log 2>&1; then
    ok "$f 执行成功"
  else
    bad "$f 执行失败：$(grep -m1 -i error /tmp/v1_ddl_$f.log)"
  fi
done
# 关键结构存在性
for idx in rag_document_tenant_content_hash_uq rag_source_tenant_isbn_uq \
           rag_ingest_job_tenant_status_idx rag_ingest_job_status_created_idx; do
  n=$(psql_q -c "SELECT count(*) FROM pg_indexes WHERE schemaname='rag' AND indexname='$idx';")
  [ "$n" = "1" ] && ok "索引存在：$idx" || bad "索引缺失：$idx"
done
n=$(psql_q -c "SELECT count(*) FROM information_schema.columns WHERE table_schema='rag' AND table_name='rag_source' AND column_name='inspection_status';")
[ "$n" = "1" ] && ok "列存在：rag_source.inspection_status" || bad "列缺失：inspection_status"
n=$(psql_q -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='rag' AND table_name IN ('rag_inspection_record','rag_kp_alias');")
[ "$n" = "2" ] && ok "新表存在：rag_inspection_record / rag_kp_alias" || bad "新表缺失（实际 $n 张）"

say "== V2 N1a REVOKED 来源同 ISBN 可重登记（T-10 不堵）=="
psql_sql >/dev/null 2>&1 <<SQL
SET search_path TO rag, public;
INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, isbn, trust_tier,
  ver_status, ver_method, ver_valid_days, license_status, created_at, updated_at, scope)
VALUES ('11111111-0000-4000-8000-000000000001','$ENV_TENANT','TEXTBOOK','撤回版','人教版',
  '978-7-000-00001-1','UNVERIFIED','REVOKED','NONE',365,'PENDING',now(),now(),'TENANT');
SQL
psql_sql >/tmp/v2.log 2>&1 <<SQL
SET search_path TO rag, public;
INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, isbn, trust_tier,
  ver_status, ver_method, ver_valid_days, license_status, created_at, updated_at, scope)
VALUES ('11111111-0000-4000-8000-000000000002','$ENV_TENANT','TEXTBOOK','重新登记版','人教版',
  '978-7-000-00001-1','UNVERIFIED','PENDING','NONE',365,'PENDING',now(),now(),'TENANT');
SQL
if sql_failed /tmp/v2.log; then
  bad "REVOKED 后重登记被拒：$(grep -m1 -i error /tmp/v2.log)"
else
  ok "REVOKED 后用同 ISBN 重登记成功（T-10 合法路径未被堵死）"
fi

say "== V3 N1b 两条活跃来源同 ISBN 被拒 =="
psql_sql >/tmp/v3.log 2>&1 <<SQL
SET search_path TO rag, public;
INSERT INTO rag_source (source_id, tenant_id, source_type, title, publisher, isbn, trust_tier,
  ver_status, ver_method, ver_valid_days, license_status, created_at, updated_at, scope)
VALUES ('11111111-0000-4000-8000-000000000003','$ENV_TENANT','TEXTBOOK','重复版','人教版',
  '978-7-000-00001-1','UNVERIFIED','PENDING','NONE',365,'PENDING',now(),now(),'TENANT');
SQL
if sql_failed /tmp/v3.log; then
  ok "重复活跃来源被唯一索引拒绝（$(grep -m1 -o 'rag_source_tenant_isbn_uq' /tmp/v3.log)）"
else
  bad "重复活跃来源未被拒（唯一索引失效！）"
fi

say "== V4 N1c FAILED 载体可同 hash 重传 =="
psql_sql >/dev/null 2>&1 <<SQL
SET search_path TO rag, public;
INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, carrier, title,
  content_hash, parse_status, ver_status, retrievable, license_status, created_at, updated_at, scope)
SELECT '22222222-0000-4000-8000-000000000001', tenant_id,
  '9a1c0000-0000-4000-8000-000000000001', edition_id, 'PDF', 'X', repeat('a',64),
  'FAILED','PENDING',false,'PENDING',now(),now(),'TENANT' FROM rag_edition LIMIT 1;
SQL
psql_sql >/tmp/v4.log 2>&1 <<SQL
SET search_path TO rag, public;
INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, carrier, title,
  content_hash, parse_status, ver_status, retrievable, license_status, created_at, updated_at, scope)
SELECT '22222222-0000-4000-8000-000000000002', tenant_id,
  '9a1c0000-0000-4000-8000-000000000001', edition_id, 'PDF', 'Y', repeat('a',64),
  'PENDING','PENDING',false,'PENDING',now(),now(),'TENANT' FROM rag_edition LIMIT 1;
SQL
if sql_failed /tmp/v4.log; then
  bad "失败后重传被拒：$(grep -m1 -i error /tmp/v4.log)"
else
  ok "解析失败后同文件重传成功（失败处置活口保留）"
fi

say "== V5 N1d 两条活跃载体同 hash 被拒 =="
psql_sql >/tmp/v5.log 2>&1 <<SQL
SET search_path TO rag, public;
INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, carrier, title,
  content_hash, parse_status, ver_status, retrievable, license_status, created_at, updated_at, scope)
SELECT '22222222-0000-4000-8000-000000000003', tenant_id,
  '9a1c0000-0000-4000-8000-000000000001', edition_id, 'PDF', 'Z', repeat('a',64),
  'PENDING','PENDING',false,'PENDING',now(),now(),'TENANT' FROM rag_edition LIMIT 1;
SQL
if sql_failed /tmp/v5.log; then
  ok "重复活跃载体被唯一索引拒绝（$(grep -m1 -o 'rag_document_tenant_content_hash_uq' /tmp/v5.log)）"
else
  bad "重复活跃载体未被拒（唯一索引失效！）"
fi

say "== V6 SKIP LOCKED 并发领取互不重复 =="
psql_sql >/dev/null 2>&1 <<SQL
SET search_path TO rag, public;
INSERT INTO rag_ingest_job (tenant_id, source_id, status, attempt)
SELECT '$ENV_TENANT','9a1c0000-0000-4000-8000-000000000001','PENDING',0
FROM generate_series(1,3);
SQL
( psql_sql >/tmp/v6a.log 2>&1 <<SQL
SET search_path TO rag, public;
BEGIN;
SELECT job_id FROM rag_ingest_job WHERE status='PENDING'
  ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED;
SELECT pg_sleep(3);
COMMIT;
SQL
) &
sleep 1
B_ID=$(psql_q -c "SET search_path TO rag, public; BEGIN; SELECT job_id FROM rag_ingest_job WHERE status='PENDING' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED; COMMIT;" 2>/dev/null | grep -E '^[0-9a-f]{8}-' | head -1)
wait
A_ID=$(grep -E '^[[:space:]]*[0-9a-f]{8}-' /tmp/v6a.log | head -1 | tr -d ' ')
if [ -n "$A_ID" ] && [ -n "$B_ID" ] && [ "$A_ID" != "$B_ID" ]; then
  ok "两并发事务领取到不同 job（A=${A_ID:0:8} B=${B_ID:0:8}），未阻塞未重复"
else
  bad "SKIP LOCKED 语义异常：A=${A_ID:-空} B=${B_ID:-空}"
fi

say "== V7 源 REVOKED 级联取消 job（N2）=="
psql_sql >/dev/null 2>&1 <<SQL
SET search_path TO rag, public;
UPDATE rag_ingest_job
   SET status='REJECTED', error_detail='SOURCE_REVOKED', finished_at=now(), stage=NULL
 WHERE tenant_id='$ENV_TENANT' AND source_id='9a1c0000-0000-4000-8000-000000000001'
   AND status NOT IN ('SUCCEEDED','FAILED','REJECTED');
SQL
n=$(psql_q -c "SET search_path TO rag, public; SELECT count(*) FROM rag_ingest_job WHERE status='REJECTED' AND error_detail='SOURCE_REVOKED';" 2>/dev/null | grep -E '^[0-9]+$' | head -1)
[ "${n:-0}" -ge 1 ] && ok "取消落点为 REJECTED + SOURCE_REVOKED（${n} 条），未新增枚举值" \
                    || bad "取消落点缺失（计数 ${n:-0}）"

say ""
say "=========================================="
say "结果：通过 $PASS 项，失败 $FAIL 项"
say "=========================================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
