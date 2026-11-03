#!/usr/bin/env bash
# =============================================================================
# RAG 摄取链并发与规模压测（V1.0.2 S4-2）
# =============================================================================
# 覆盖计划文档 S4-2 的两条验收判据：
#   P1  多 worker 并行领 job 无重复 —— SKIP LOCKED 反证
#      P1a 8 worker × 200 job：总数守恒、无遗留、无重复
#      P1b 16 并发 × 单条领取：严格竞态下无重复领取
#   P2  10 册批量吞吐 —— 500 片段落库耗时基准
# 附加：
#   P3  重灌 UPSERT 清 text_jieba（不变式 3 的真库证据）
#
# 用法：./scripts/rag-v1_0_2-concurrency-verify.sh
# 退出码：0 全通过；1 有断言失败。
# =============================================================================
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SQL_DIR="$REPO_ROOT/deploy/sql/rag"
CNAME="${CNAME:-rag-pg-perf}"
PORT="${PORT:-55434}"
TENANT="2187ffed-3e2e-4fd0-b4a7-54dcc0a483e2"
SRC="9a1c0000-0000-4000-8000-000000000001"
KEEP="${KEEP:-1}"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '  ✅ %s\n' "$*"; }
bad() { FAIL=$((FAIL+1)); printf '  ❌ %s\n' "$*"; }
say() { printf '%s\n' "$*"; }

q()   { docker exec -i "$CNAME" psql -U postgres -d ragdb -q -t -A -v ON_ERROR_STOP=1 -f -; }
qa()  { docker exec -i "$CNAME" psql -U postgres -d ragdb -q -t -A -v ON_ERROR_STOP=1 -c "SET search_path TO rag,public; $1"; }

cleanup() { [ "$KEEP" != "1" ] && docker rm -f "$CNAME" >/dev/null 2>&1 || say "容器保留：$CNAME"; }
trap cleanup EXIT

say "== 准备环境 =="
docker rm -f "$CNAME" >/dev/null 2>&1
docker run -d --name "$CNAME" -e POSTGRES_PASSWORD=ragpass -e POSTGRES_DB=ragdb \
  -p "$PORT:5432" postgres:17 >/dev/null || { say "docker run 失败"; exit 1; }
for i in $(seq 1 40); do docker exec "$CNAME" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
docker exec "$CNAME" bash -lc \
  "apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq postgresql-17-pgvector >/dev/null 2>&1" \
  || { say "pgvector 安装失败（需网络）"; exit 1; }
for f in 01_rag_ddl.sql 02_rag_seed.sql 05_v1_0_2_s1_alter.sql 06_v1_0_2_s3_alter.sql; do
  docker exec -i "$CNAME" psql -U postgres -d ragdb -q -v ON_ERROR_STOP=1 -f - < "$SQL_DIR/$f" >/dev/null 2>&1 \
    || { say "迁移失败：$f"; exit 1; }
done
say "  就绪"

# ── P1a：8 worker × 200 job ───────────────────────────────────────────────
say "== P1a 8 worker 并发领取 200 job =="
cat <<SQL | q >/dev/null 2>&1
SET search_path TO rag, public;
INSERT INTO rag_ingest_job (tenant_id, source_id, status, attempt)
SELECT '$TENANT', '$SRC', 'PENDING', 0 FROM generate_series(1,200);
SQL

cat > /tmp/pp_claim.sql <<'SQL'
SET search_path TO rag, public;
DO $$
DECLARE claimed uuid; n int := 0;
BEGIN
  LOOP
    SELECT job_id INTO claimed FROM rag_ingest_job
     WHERE status='PENDING' ORDER BY created_at
     LIMIT 1 FOR UPDATE SKIP LOCKED;
    EXIT WHEN claimed IS NULL;
    UPDATE rag_ingest_job SET status='PARSING', started_at=now() WHERE job_id = claimed;
    n := n + 1;
    PERFORM pg_sleep(0.002);
    UPDATE rag_ingest_job SET status='SUCCEEDED', finished_at=now() WHERE job_id = claimed;
  END LOOP;
  RAISE NOTICE 'claimed % jobs', n;
END $$;
SQL

START=$(date +%s%N)
for i in $(seq 1 8); do
  docker exec -i "$CNAME" psql -U postgres -d ragdb -q -f - < /tmp/pp_claim.sql > "/tmp/pp_$i.log" 2>&1 &
done
wait
END=$(date +%s%N)

TOTAL=$(qa "SELECT count(*) FROM rag_ingest_job;")
SUCC=$(qa "SELECT count(*) FROM rag_ingest_job WHERE status='SUCCEEDED';")
PEND=$(qa "SELECT count(*) FROM rag_ingest_job WHERE status='PENDING';")
CLAIMED=$(grep -h -o 'claimed [0-9]*' /tmp/pp_*.log | awk '{s+=$2} END{print s+0}')

say "  耗时 $(( (END-START)/1000000 )) ms；各 worker 领取：$(grep -h -o 'claimed [0-9]*' /tmp/pp_*.log | awk '{printf "%s ", $2}')"
[ "$TOTAL" = "200" ] && ok "任务总数守恒（200）" || bad "任务总数异常：$TOTAL"
[ "$SUCC" = "200" ]  && ok "全部处理完成（200 SUCCEEDED）" || bad "完成数异常：$SUCC"
[ "$PEND" = "0" ]    && ok "无遗留 PENDING" || bad "遗留 PENDING：$PEND"
[ "$CLAIMED" = "200" ] && ok "领取次数合计 = 任务数（无重复领取）" || bad "领取次数 $CLAIMED ≠ 200（存在重复或漏领）"
rm -f /tmp/pp_*.log

# ── P1b：16 并发严格竞态 ──────────────────────────────────────────────────
say "== P1b 16 并发 × 单条领取（严格竞态）=="
qa "DELETE FROM rag_ingest_job;" >/dev/null 2>&1
cat <<SQL | q >/dev/null 2>&1
SET search_path TO rag, public;
INSERT INTO rag_ingest_job (tenant_id, source_id, status, attempt)
SELECT '$TENANT', '$SRC', 'PENDING', 0 FROM generate_series(1,16);
SQL
cat > /tmp/pp_one.sql <<'SQL'
SET search_path TO rag, public;
BEGIN;
SELECT job_id::text FROM rag_ingest_job
 WHERE status='PENDING' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED;
SELECT pg_sleep(0.3);
COMMIT;
SQL
for i in $(seq 1 16); do
  (docker exec -i "$CNAME" psql -U postgres -d ragdb -t -A -f - < /tmp/pp_one.sql 2>/dev/null \
     | grep -E '^[0-9a-f]{8}-' > "/tmp/pp_one_$i.txt") &
done
wait
cat /tmp/pp_one_*.txt | sort > /tmp/pp_all.txt
N_CLAIM=$(wc -l < /tmp/pp_all.txt | tr -d ' ')
N_UNIQ=$(sort -u /tmp/pp_all.txt | wc -l | tr -d ' ')
N_DUP=$(( N_CLAIM - N_UNIQ ))
[ "$N_CLAIM" -ge 1 ] && ok "并发领取生效（${N_CLAIM} 次）" || bad "并发领取未取到任何 job"
[ "$N_DUP" = "0" ]   && ok "零重复领取（${N_UNIQ} 条互异）" || bad "存在 $N_DUP 条重复领取（SKIP LOCKED 失效！）"
rm -f /tmp/pp_one_*.txt /tmp/pp_all.txt

# ── P2：10 册 500 片段吞吐 ────────────────────────────────────────────────
say "== P2 10 册 × 50 片段落库吞吐 =="
qa "DELETE FROM rag_chunk; DELETE FROM rag_document;" >/dev/null 2>&1
cat > /tmp/pp_tp.sql <<SQL
SET search_path TO rag, public;
DO \$\$
DECLARE doc_id uuid; ed_id uuid; t0 timestamptz := clock_timestamp(); i int; j int;
BEGIN
  SELECT edition_id INTO ed_id FROM rag_edition LIMIT 1;
  FOR i IN 1..10 LOOP
    doc_id := gen_random_uuid();
    INSERT INTO rag_document (document_id, tenant_id, source_id, edition_id, carrier, title,
      content_hash, parse_status, ver_status, retrievable, license_status, created_at, updated_at, scope)
    VALUES (doc_id, '$TENANT', '$SRC', ed_id, 'PDF', 'book'||i,
      md5(i::text)||repeat('0',32),'CHUNKED','PENDING',false,'PENDING',now(),now(),'TENANT');
    FOR j IN 1..50 LOOP
      INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role, text, text_jieba,
        page_number, section_path, char_start, char_end, kp_ids, content_hash, index_generation)
      VALUES (gen_random_uuid(), '$TENANT', doc_id, ed_id, 'EXPLANATION',
        '第'||i||'册第'||j||'段', '', j, 'Unit 1', 0, 20, '{}', md5(i::text||j::text), 1);
    END LOOP;
  END LOOP;
  RAISE NOTICE 'inserted 500 chunks in % ms', round(extract(epoch from clock_timestamp()-t0)*1000);
END \$\$;
SQL
TPS_OUT=$(docker exec -i "$CNAME" psql -U postgres -d ragdb -q -f - < /tmp/pp_tp.sql 2>&1 | grep -o 'inserted 500 chunks in [0-9]* ms')
DOCS=$(qa "SELECT count(*) FROM rag_document;")
CHUNKS=$(qa "SELECT count(*) FROM rag_chunk;")
say "  $TPS_OUT"
[ "$DOCS" = "10" ]   && ok "10 册载体落库" || bad "载体数异常：$DOCS"
[ "$CHUNKS" = "500" ] && ok "500 片段落库" || bad "片段数异常：$CHUNKS"

# ── P3：重灌清 text_jieba ────────────────────────────────────────────────
say "== P3 重灌 UPSERT 清 text_jieba（不变式 3）=="
qa "UPDATE rag_chunk SET text_jieba='已 分词' WHERE chunk_id=(SELECT chunk_id FROM rag_chunk ORDER BY chunk_id LIMIT 1);" >/dev/null 2>&1
cat > /tmp/pp_re.sql <<'SQL'
SET search_path TO rag, public;
INSERT INTO rag_chunk (chunk_id, tenant_id, document_id, edition_id, role, text, text_jieba,
  page_number, section_path, char_start, char_end, kp_ids, content_hash, index_generation)
SELECT chunk_id, tenant_id, document_id, edition_id, 'EXPLANATION', '重灌新正文', '',
  page_number, section_path, char_start, char_end, kp_ids, 'newhash', 1
FROM rag_chunk ORDER BY chunk_id LIMIT 1
ON CONFLICT (chunk_id) DO UPDATE SET
  text=EXCLUDED.text, text_jieba='', content_hash=EXCLUDED.content_hash;
SQL
docker exec -i "$CNAME" psql -U postgres -d ragdb -q -f - < /tmp/pp_re.sql >/dev/null 2>&1
JIEBA=$(qa "SELECT text_jieba FROM rag_chunk ORDER BY chunk_id LIMIT 1;")
TXT=$(qa "SELECT text FROM rag_chunk ORDER BY chunk_id LIMIT 1;")
[ -z "$JIEBA" ] && ok "重灌后 text_jieba 已清空（会触发重新嵌入）" || bad "text_jieba 未清空：[$JIEBA]（静默陈旧风险）"
[ "$TXT" = "重灌新正文" ] && ok "正文已更新" || bad "正文未更新：$TXT"

say ""
say "=========================================="
say "并发与规模压测：通过 $PASS 项，失败 $FAIL 项"
say "=========================================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
