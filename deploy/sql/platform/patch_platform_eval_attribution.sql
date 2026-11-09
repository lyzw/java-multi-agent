-- =====================================================================
-- 活库补结构（P3 · §13 第 3 项 · L2 归因正确率 + 审查队列）
--   目标：既有库（结构已按 01 终态快照建好，但缺这些列/表）增量补齐。
--
--   ⚠️ **结构事实源仍是 01_platform_ddl.sql**（终态快照），本文件不是迁移链，
--      只是「既有库补齐」的一次性脚本。改完必须按「授权唯一源」重放 03 → 04。
--   ⚠️ **幂等**：全程 IF NOT EXISTS 语义，可重复执行；不 DROP、不重建。
--   ⚠️ **执行者须是库 owner**（能 ALTER 这几张表）。
--   ⚠️ **顺序不可反**：本脚本 → 03_platform_roles.sql → 04_retention_role.sql
--      （03 的全表 GRANT 会覆盖 04 的两条 REVOKE DELETE，导致 app_agent 持有审计 DELETE）。
--
--   覆盖范围（与 01 快照逐列对齐）：
--     1. platform.agent_eval_sample.attribution_correct   （人工标注的归因正确率）
--     2. platform.agent_trace_metric.attribution_correct  （宽表聚合口径）
--     3. platform.review_task 整表 + 索引 + 4 个 CHECK     （P2 审查队列；老库可能没有）
-- =====================================================================
\set ON_ERROR_STOP on

BEGIN;

-- ── 1. 归因正确率两列 ────────────────────────────────────────────────
DO $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema='platform' AND table_name='agent_eval_sample'
        AND column_name='attribution_correct') THEN
    ALTER TABLE platform.agent_eval_sample ADD COLUMN attribution_correct boolean;
  END IF;

  IF NOT EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema='platform' AND table_name='agent_trace_metric'
        AND column_name='attribution_correct') THEN
    ALTER TABLE platform.agent_trace_metric ADD COLUMN attribution_correct boolean;
  END IF;
END
$$;

-- ── 2. 审查队列表（P2 · platform.review_task）────────────────────────
--   ⚠️ 与 01 快照的定义逐字一致：四个 CHECK 是「状态机不许乱跳」的库侧兜底。
--      少一个就会让非法状态从应用层漏进来（应用层校验可以被绕过，约束不行）。
CREATE TABLE IF NOT EXISTS platform.review_task (
    task_id      uuid DEFAULT gen_random_uuid() NOT NULL,
    trace_id     text NOT NULL,
    tenant_id    uuid NOT NULL,
    run_id       uuid NOT NULL,
    source       text NOT NULL,
    priority     text NOT NULL,
    status       text NOT NULL DEFAULT 'PENDING',
    assigned_to  text,
    root_cause   text,
    note         text,
    created_at   timestamp with time zone DEFAULT now() NOT NULL,
    updated_at   timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at  timestamp with time zone,
    CONSTRAINT review_task_pkey PRIMARY KEY (task_id),
    CONSTRAINT review_task_source_valid CHECK ((source = ANY (ARRAY['ALERT'::text, 'ANOMALY'::text, 'SAMPLE_RANDOM'::text, 'L1_GAP'::text, 'MANUAL'::text]))),
    CONSTRAINT review_task_priority_valid CHECK ((priority = ANY (ARRAY['HIGH'::text, 'NORMAL'::text, 'LOW'::text]))),
    CONSTRAINT review_task_status_valid CHECK ((status = ANY (ARRAY['PENDING'::text, 'IN_REVIEW'::text, 'RESOLVED'::text, 'DISMISSED'::text]))),
    CONSTRAINT review_task_root_cause_valid CHECK ((root_cause = ANY (ARRAY['MODEL'::text, 'PROMPT'::text, 'TOOL'::text, 'DATA'::text, 'CONFIG'::text, 'UNKNOWN'::text])))
);

CREATE INDEX IF NOT EXISTS idx_review_task_status ON platform.review_task USING btree (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_task_tenant ON platform.review_task USING btree (tenant_id, status);

COMMIT;

-- ── 3. 复查（人读）──────────────────────────────────────────────────
SELECT concat(
    'eval_sample.attribution_correct=', (
        SELECT count(*) FROM information_schema.columns
         WHERE table_schema='platform' AND table_name='agent_eval_sample'
           AND column_name='attribution_correct'),
    ' | trace_metric.attribution_correct=', (
        SELECT count(*) FROM information_schema.columns
         WHERE table_schema='platform' AND table_name='agent_trace_metric'
           AND column_name='attribution_correct'),
    ' | review_task=', (
        SELECT count(*) FROM information_schema.tables
         WHERE table_schema='platform' AND table_name='review_task'),
    ' | review_task_idx=', (
        SELECT count(*) FROM pg_indexes
         WHERE schemaname='platform' AND tablename='review_task')
) AS quality_shape;

-- ── 4. 断言（机读）──────────────────────────────────────────────────
--   ⚠️ 失败即抛异常：早先版本只打印不校验，于是
--      「跑了但没生效」与「跑了且生效」在输出上毫无差别 —— 正是本仓反复治理的失效形态。
DO $$
DECLARE
    cols int;
    tbl  int;
    idx  int;
BEGIN
    SELECT count(*) INTO cols FROM information_schema.columns
     WHERE table_schema='platform' AND column_name='attribution_correct'
       AND table_name IN ('agent_eval_sample','agent_trace_metric');
    SELECT count(*) INTO tbl FROM information_schema.tables
     WHERE table_schema='platform' AND table_name='review_task';
    SELECT count(*) INTO idx FROM pg_indexes
     WHERE schemaname='platform' AND tablename='review_task';

    IF cols <> 2 THEN
        RAISE EXCEPTION '补列未生效：attribution_correct 只找到 % 列（应为 2）', cols;
    END IF;
    IF tbl <> 1 THEN
        RAISE EXCEPTION 'review_task 未建出来';
    END IF;
    IF idx < 3 THEN
        RAISE EXCEPTION 'review_task 索引不足：% 个（主键 + 2 个查询索引 = 3）', idx;
    END IF;
    RAISE NOTICE '✅ 质量体系结构补齐通过（列 2 / 表 1 / 索引 %）', idx;
END
$$;
