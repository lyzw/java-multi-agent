-- =============================================================================
-- V1.0.2 活库等价 ALTER（S1 阶段）
-- =============================================================================
-- 纪律：01_rag_ddl.sql 是「终态快照」，本文件是它的**等价增量**，用于已存在的活库。
--       切勿对已存在的库重放 01_rag_ddl.sql（它是 CREATE TABLE，非幂等）。
--
-- 覆盖计划文档 S1-1 / S1-4，对应方案 §8.3 与设计 §2.2.1/§2.2.2。
--
-- ⚠️ 上线顺序（重要）：
--   1) 先跑本文件建列与索引；
--   2) 若「存量活跃重复」导致部分唯一索引创建失败，按 设计 §8.3「存量重复清理纪律」
--      由 DBA 运维通道逐条留档清理后重跑索引创建；
--   3) 最后部署应用（S1-2/S1-3/S1-4 代码）。
-- =============================================================================

BEGIN;

-- ── 1. rag_ingest_job：加列（S1-1）───────────────────────────────────────────
ALTER TABLE rag.rag_ingest_job ADD COLUMN IF NOT EXISTS stage     text;
ALTER TABLE rag.rag_ingest_job ADD COLUMN IF NOT EXISTS attempt   integer NOT NULL DEFAULT 0;
ALTER TABLE rag.rag_ingest_job ADD COLUMN IF NOT EXISTS created_by uuid;

-- ── 2. rag_ingest_job：二级索引（S1-1，该表原为零索引）────────────────────────
CREATE INDEX IF NOT EXISTS rag_ingest_job_tenant_status_idx
    ON rag.rag_ingest_job USING btree (tenant_id, status);

CREATE INDEX IF NOT EXISTS rag_ingest_job_status_created_idx
    ON rag.rag_ingest_job USING btree (status, created_at);

-- ── 3. 去重兜底：部分唯一索引（S1-4 / N1）────────────────────────────────────
-- ⚠️ WHERE 条件必须与 IngestDedupPrecheck 的应用层预检逐字同口径。

-- 载体级：排除 REVOKED 载体与解析失败载体（保留撤回重传、失败重传两条活口）
CREATE UNIQUE INDEX IF NOT EXISTS rag_document_tenant_content_hash_uq
    ON rag.rag_document USING btree (tenant_id, content_hash)
    WHERE (ver_status <> 'REVOKED'::rag.rag_verification_status
           AND parse_status <> 'FAILED'::text);

-- 来源级：排除 REVOKED 来源（保留 T-10「撤回后新建 source_id 重登记」路径）
CREATE UNIQUE INDEX IF NOT EXISTS rag_source_tenant_isbn_uq
    ON rag.rag_source USING btree (tenant_id, isbn)
    WHERE (isbn IS NOT NULL AND ver_status <> 'REVOKED'::rag.rag_verification_status);

CREATE UNIQUE INDEX IF NOT EXISTS rag_source_tenant_doi_uq
    ON rag.rag_source USING btree (tenant_id, doi)
    WHERE (doi IS NOT NULL AND ver_status <> 'REVOKED'::rag.rag_verification_status);

-- ── 4. 存量重复体检（只读，不自动清理）───────────────────────────────────────
-- 上线前请人工核对下列查询结果；为空的查询对应的唯一索引可直接创建。
-- 清理仅限 DBA 运维通道、逐条留档（对象 ID + 处置人 + 依据），管理面 API 无删除端点。

-- 4.1 活跃载体的同 content_hash 重复
SELECT tenant_id, content_hash, count(*) AS dup_cnt,
       array_agg(document_id::text ORDER BY created_at) AS document_ids
FROM rag.rag_document
WHERE content_hash IS NOT NULL
  AND ver_status <> 'REVOKED'::rag.rag_verification_status
  AND parse_status <> 'FAILED'::text
GROUP BY tenant_id, content_hash
HAVING count(*) > 1;

-- 4.2 活跃来源的同 ISBN 重复
SELECT tenant_id, isbn, count(*) AS dup_cnt,
       array_agg(source_id::text ORDER BY created_at) AS source_ids
FROM rag.rag_source
WHERE isbn IS NOT NULL
  AND ver_status <> 'REVOKED'::rag.rag_verification_status
GROUP BY tenant_id, isbn
HAVING count(*) > 1;

-- 4.3 活跃来源的同 DOI 重复
SELECT tenant_id, doi, count(*) AS dup_cnt,
       array_agg(source_id::text ORDER BY created_at) AS source_ids
FROM rag.rag_source
WHERE doi IS NOT NULL
  AND ver_status <> 'REVOKED'::rag.rag_verification_status
GROUP BY tenant_id, doi
HAVING count(*) > 1;

COMMIT;
