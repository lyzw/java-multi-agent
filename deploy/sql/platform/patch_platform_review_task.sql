-- =====================================================================
-- 活库补 platform.review_task（P2 · G3 人工审查队列）
--   目标：既有库（结构已按 01 终态快照建好，但缺本表）增量补齐。
--   ⚠️ **结构事实源仍是 01_platform_ddl.sql**（终态快照），本文件只是
--      「既有库补表」的一次性 ALTER，不是迁移链。
--   ⚠️ 授权不在本文件里：建完表后按「授权唯一源」依次重放
--      03_platform_roles.sql → 04_retention_role.sql（顺序不可反）。
--   ⚠️ 执行者须能 CREATE ON SCHEMA platform（库 owner）。
-- =====================================================================
\set ON_ERROR_STOP on

BEGIN;

CREATE TABLE IF NOT EXISTS platform.review_task (
    task_id uuid DEFAULT gen_random_uuid() NOT NULL,
    trace_id text NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    source text NOT NULL,
    priority text DEFAULT 'NORMAL'::text NOT NULL,
    status text DEFAULT 'PENDING'::text NOT NULL,
    assigned_to text,
    root_cause text,
    note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone,
    CONSTRAINT review_task_source_valid CHECK (source = ANY (ARRAY['ALERT'::text, 'ANOMALY'::text, 'SAMPLE_RANDOM'::text, 'L1_GAP'::text, 'MANUAL'::text])),
    CONSTRAINT review_task_priority_valid CHECK (priority = ANY (ARRAY['HIGH'::text, 'NORMAL'::text, 'LOW'::text])),
    CONSTRAINT review_task_status_valid CHECK (status = ANY (ARRAY['PENDING'::text, 'IN_REVIEW'::text, 'RESOLVED'::text, 'DISMISSED'::text])),
    CONSTRAINT review_task_root_cause_valid CHECK (root_cause = ANY (ARRAY['MODEL'::text, 'PROMPT'::text, 'TOOL'::text, 'DATA'::text, 'CONFIG'::text, 'UNKNOWN'::text]))
);

DO $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM pg_constraint
      WHERE conname = 'review_task_pkey' AND conrelid = 'platform.review_task'::regclass) THEN
    ALTER TABLE platform.review_task ADD CONSTRAINT review_task_pkey PRIMARY KEY (task_id);
  END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_review_task_status ON platform.review_task USING btree (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_task_tenant ON platform.review_task USING btree (tenant_id, status);

COMMIT;

-- ── 复查（与 01 快照口径一致：71 表 / 44 RLS 表 / 52 策略）──────────
SELECT concat('tables=', (SELECT count(*) FROM pg_tables WHERE schemaname IN ('public','platform')),
              ' | rls_tables=', (SELECT count(*) FROM pg_tables t JOIN pg_class c ON c.relname=t.tablename AND c.relnamespace=(SELECT oid FROM pg_namespace WHERE nspname=t.schemaname) WHERE t.schemaname IN ('public','platform') AND c.relrowsecurity AND c.relforcerowsecurity),
              ' | policies=', (SELECT count(*) FROM pg_policies WHERE schemaname IN ('public','platform'))) AS structure;

SELECT concat('review_task cols=', (SELECT count(*) FROM information_schema.columns WHERE table_schema='platform' AND table_name='review_task'),
              ' | indexes=', (SELECT count(*) FROM pg_indexes WHERE schemaname='platform' AND tablename='review_task'),
              ' | checks=', (SELECT count(*) FROM pg_constraint WHERE conrelid='platform.review_task'::regclass AND contype='c')) AS review_task_shape;
