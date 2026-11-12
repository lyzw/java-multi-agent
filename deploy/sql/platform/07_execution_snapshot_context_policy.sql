-- =============================================================================
-- 07_execution_snapshot_context_policy.sql
-- 活库结构升级：execution_snapshot 新增 context_policy 列（ADR-013 / CH-9 / M3）
--
-- 适用对象：**存量活库**（本次升级前建的库，含远程 RDS agent_dev、本地 agent_dev / agent_it）。
--   · 新建库不要用本文件 —— 01_platform_ddl.sql 是终态快照，已含该列与列注释；
--     init-db.sh 只按顺序加载 01 / 02 / 03 / 04，不会自动执行本文件。
--   · 本文件**幂等**（ADD COLUMN IF NOT EXISTS），可安全重复执行。
--
-- ⚠️ 执行身份：库 owner / 管理员（DDL 权限）。**不要用 app_agent 跑**：
--    业务 SQL 一律走 app_agent，但 DDL 属于带外运维，二者不可混用。
-- ⚠️ 若本升级之后重跑过 03_platform_roles.sql（全表 GRANT 会覆盖
--    04_retention_role.sql 的 REVOKE DELETE），**必须紧跟重跑 04**。
--    execution_snapshot 在 public 域，表级 GRANT + DEFAULT PRIVILEGES 已覆盖新增列，
--    **加列无需改 03 授权**（不存在列级 GRANT）。
-- ⚠️ 本文件不新增表、不新增 RLS 策略，故 init-db.sh 的
--    EXPECT_TABLES=71 / EXPECT_RLS_TABLES=44 / EXPECT_POLICIES=52
--    与 it-env.sh --probe 的 71 / 44 / 52 口径**全部不变**；
--    列级存在性由 SchemaDriftIT.snapshotColumnsExistInLiveDatabase 真库校验。
--
-- ⚠️ 只影响新 Run：存量快照行由 NOT NULL DEFAULT '{}' 自动补齐。
--    '{}' 表示「未声明压缩策略 / 回落默认」，在途 Run 继续沿用原快照，
--    不随配置发布漂移（[D4] / CH-9）。本列专管上下文压缩策略，
--    不混入 run_config（DEC-9 容量配置）的四个既有键。
-- =============================================================================

BEGIN;

-- ── 1. 结构：与 01_platform_ddl.sql 终态逐字对齐 ────────────────────────────
ALTER TABLE public.execution_snapshot
    ADD COLUMN IF NOT EXISTS context_policy jsonb NOT NULL DEFAULT '{}'::jsonb;

-- 列注释：诊断 / 运维读库时能直接看到口径，避免「看列名猜语义」。
COMMENT ON COLUMN public.execution_snapshot.context_policy IS
    'ADR-013 / CH-9：Agent 级 generation_params.context 的发布期规范快照（jsonb 对象）。'
    '{} = 未声明 / 回落默认；只影响新 Run，在途 Run 沿用原快照。'
    '与 run_config（DEC-9 容量配置）分工：本列专管上下文压缩策略，不混入容量字段。';

-- ── 2. 结构断言：列真的落上了，且没有悄悄变成别的类型 / 可空 ─────────────────
DO $$
DECLARE
    v_bad text;
BEGIN
    SELECT string_agg(c.column_name || '(' || c.data_type || ',' || c.is_nullable || ')', ', ')
      INTO v_bad
      FROM information_schema.columns c
     WHERE c.table_schema = 'public'
       AND c.table_name = 'execution_snapshot'
       AND c.column_name = 'context_policy'
       AND NOT (c.data_type = 'jsonb' AND c.is_nullable = 'NO'
                AND c.column_default IS NOT NULL);
    IF v_bad IS NULL THEN
        -- 列完全不存在时上面聚合也是 NULL，必须再显式判一次存在性。
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'execution_snapshot'
                          AND column_name = 'context_policy') THEN
            RAISE EXCEPTION '升级校验失败：execution_snapshot.context_policy 列不存在';
        END IF;
    ELSE
        RAISE EXCEPTION '升级校验失败：execution_snapshot.context_policy 口径不符 → %', v_bad;
    END IF;
END $$;

-- ── 3. 数据诊断（只读）：存量行取默认值是预期，不是升级失败 ──────────────────
DO $$
DECLARE
    v_total        bigint;
    v_unspecified  bigint;
BEGIN
    SELECT count(*), count(*) FILTER (WHERE context_policy = '{}'::jsonb)
      INTO v_total, v_unspecified
      FROM public.execution_snapshot;
    RAISE NOTICE 'execution_snapshot 升级诊断：共 % 行，其中 % 行 context_policy={}（存量行/未声明策略，预期）',
        v_total, v_unspecified;
END $$;

COMMIT;

-- ── 验收清单（执行后人工核对） ─────────────────────────────────────────────
-- 1) SELECT column_name, data_type, is_nullable, column_default
--      FROM information_schema.columns
--     WHERE table_schema='public' AND table_name='execution_snapshot'
--       AND column_name='context_policy';
--    → 1 行；jsonb / NO / '{}'::jsonb。
-- 2) SELECT count(*) FROM public.execution_snapshot WHERE context_policy IS NULL;  -- 必须为 0
-- 3) 若随后重跑了 03，必须紧跟重跑 04_retention_role.sql（见文件头）。
-- 4) 结构快照三数（71 / 44 / 52）**不变**；
--    `SchemaDriftIT.snapshotColumnsExistInLiveDatabase` 需在两套活库真跑。
-- =============================================================================
