-- =============================================================================
-- V1.0.2 活库等价 ALTER（S3 阶段）
-- =============================================================================
-- 纪律：01_rag_ddl.sql 是「终态快照」，本文件是其**等价增量**，用于已存在的活库。
--       切勿对已存在的库重放 01_rag_ddl.sql（CREATE TABLE 非幂等）。
--
-- 覆盖计划文档 S3-1 / S3-3，对应方案 §5 R2/R7、§8.3 与设计 §2.2.3/§2.2.4。
-- =============================================================================

BEGIN;

-- ── 1. rag_source.inspection_status（S3-1：VERIFIED 前置守卫落点）────────────
-- ⚠️ ADD COLUMN 默认追加在表尾，与不带列名的 INSERT 保持值顺序对齐——
--    这正是 02_rag_seed.sql 能继续工作的前提（真库实测踩过中间插入导致的错位）。
ALTER TABLE rag.rag_source
    ADD COLUMN IF NOT EXISTS inspection_status text NOT NULL DEFAULT 'NOT_REQUIRED';

ALTER TABLE rag.rag_source
    DROP CONSTRAINT IF EXISTS rag_source_inspection_status_chk;
ALTER TABLE rag.rag_source
    ADD CONSTRAINT rag_source_inspection_status_chk
    CHECK (inspection_status = ANY (ARRAY['NOT_REQUIRED'::text, 'PASSED'::text, 'FAILED'::text]));

-- ── 2. rag_inspection_record（S3-1：抽检明细，只追加不修改）──────────────────
CREATE TABLE IF NOT EXISTS rag.rag_inspection_record (
    record_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    source_id uuid NOT NULL,
    chunk_id uuid NOT NULL,
    inspector_id uuid NOT NULL,
    expected_role text NOT NULL,
    actual_role text NOT NULL,
    error_type text,
    verdict text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    CONSTRAINT rag_inspection_record_pkey PRIMARY KEY (record_id),
    CONSTRAINT rag_inspection_verdict_chk CHECK (verdict = ANY (ARRAY['PASS'::text, 'FAIL'::text])),
    CONSTRAINT rag_inspection_error_type_chk CHECK ((error_type IS NULL OR error_type = ANY (ARRAY['ANSWER_LEAK'::text, 'EXERCISE_NO_LOST'::text, 'LOCATOR_WRONG'::text, 'CHUNK_BOUNDARY'::text, 'OTHER'::text]))),
    CONSTRAINT rag_inspection_verdict_error_ck CHECK ((((verdict = 'FAIL'::text) AND (error_type IS NOT NULL)) OR ((verdict = 'PASS'::text) AND (error_type IS NULL))))
);

ALTER TABLE ONLY rag.rag_inspection_record FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_inspection_record ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rag_inspection_tenant_isolation ON rag.rag_inspection_record;
CREATE POLICY rag_inspection_tenant_isolation ON rag.rag_inspection_record
    USING ((tenant_id = rag.rag_current_tenant()))
    WITH CHECK ((tenant_id = rag.rag_current_tenant()));

CREATE INDEX IF NOT EXISTS rag_inspection_record_source_idx
    ON rag.rag_inspection_record USING btree (tenant_id, source_id, created_at DESC);

-- ── 3. rag_kp_alias（S3-3：知识点别名，复用既有 KnowledgePoint.aliases 机制）──
CREATE TABLE IF NOT EXISTS rag.rag_kp_alias (
    alias_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    kp_id uuid NOT NULL,
    alias_text text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    CONSTRAINT rag_kp_alias_pkey PRIMARY KEY (alias_id),
    CONSTRAINT rag_kp_alias_unique UNIQUE (tenant_id, kp_id, alias_text)
);

ALTER TABLE ONLY rag.rag_kp_alias FORCE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_kp_alias ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS rag_kp_alias_tenant_isolation ON rag.rag_kp_alias;
CREATE POLICY rag_kp_alias_tenant_isolation ON rag.rag_kp_alias
    USING ((tenant_id = rag.rag_current_tenant()))
    WITH CHECK ((tenant_id = rag.rag_current_tenant()));

CREATE INDEX IF NOT EXISTS rag_kp_alias_kp_idx
    ON rag.rag_kp_alias USING btree (tenant_id, kp_id);

COMMIT;
