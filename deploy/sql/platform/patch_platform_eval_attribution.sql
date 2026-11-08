-- =====================================================================
-- 活库补 attribution_correct 列（P3 · §13 第 3 项 · L2 归因正确率）
--   目标：既有库（结构已按 01 终态快照建好，但缺这两列）增量补齐。
--   ⚠️ **结构事实源仍是 01_platform_ddl.sql**（终态快照），本文件只是
--      「既有库补列」的一次性 ALTER，不是迁移链。
--   ⚠️ 授权不在本文件里：列落在既有表上，沿用 03_platform_roles.sql 已授予
--      app_agent 的 SELECT/INSERT/UPDATE（agent_eval_sample / agent_trace_metric）。
--      建完列后按「授权唯一源」依次重放 03 → 04（顺序不可反）。
--   ⚠️ 执行者须能 ALTER 这两张表（库 owner）。
-- =====================================================================
\set ON_ERROR_STOP on

BEGIN;

DO $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema='platform' AND table_name='agent_eval_sample' AND column_name='attribution_correct') THEN
    ALTER TABLE platform.agent_eval_sample ADD COLUMN attribution_correct boolean;
  END IF;
  IF NOT EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema='platform' AND table_name='agent_trace_metric' AND column_name='attribution_correct') THEN
    ALTER TABLE platform.agent_trace_metric ADD COLUMN attribution_correct boolean;
  END IF;
END
$$;

COMMIT;

-- ── 复查（与 01 快照口径一致）──────────────────────────────────────
SELECT concat('eval_sample.attribution_correct=',
              (SELECT count(*) FROM information_schema.columns
                 WHERE table_schema='platform' AND table_name='agent_eval_sample' AND column_name='attribution_correct'),
              ' | trace_metric.attribution_correct=',
              (SELECT count(*) FROM information_schema.columns
                 WHERE table_schema='platform' AND table_name='agent_trace_metric' AND column_name='attribution_correct'))
       AS attribution_shape;
