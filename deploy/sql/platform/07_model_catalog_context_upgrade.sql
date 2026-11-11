-- =============================================================================
-- 07_model_catalog_context_upgrade.sql
-- 活库结构升级：`model_catalog` 上下文压缩 5 列（ADR-013 / 方案 §3.2-A）
--
-- 适用对象：**存量活库**（本次升级前建的库，含远程 RDS `agent_dev` 与本地 `agent_dev` / `agent_it`）。
--   · 新建库不要用本文件 —— `01_platform_ddl.sql` 是终态快照，已含全部 5 列与列注释；
--     `init-db.sh` 只按顺序加载 01 / 02 / 03 / 04，不会自动执行本文件。
--   · 本文件**幂等**（ADD COLUMN IF NOT EXISTS），可安全重复执行。
--   · 升级后如需补种模型目录，用 `seed_model_catalog.sql`（已同步 5 列）。
--
-- ⚠️ 执行身份：库 owner / 管理员（DDL 权限）。**不要用 app_agent 跑**：
--    业务 SQL 一律走 app_agent，但 DDL 属于带外运维，二者不可混用。
-- ⚠️ 若本升级之后重跑过 `03_platform_roles.sql`（全表 GRANT 会覆盖
--    `04_retention_role.sql` 的 REVOKE DELETE），**必须紧跟重跑 04**。
--    `model_catalog` 在 public 域，由 03 的整域 GRANT + DEFAULT PRIVILEGES 覆盖，
--    **加列无需改 03 授权**（不存在列级 GRANT）。
-- ⚠️ 本文件不新增表、不新增 RLS 策略，故 `init-db.sh` 的
--    EXPECT_TABLES=71 / EXPECT_RLS_TABLES=44 / EXPECT_POLICIES=52
--    与 `it-env.sh --probe` 的 71 / 44 / 52 口径**全部不变**。
--
-- ⚠️ R10：默认值里 `supports_prompt_caching` / `supports_tokenize_api` 恒 false、
--    `tokenizer_id` 恒 NULL —— 这是「能力未探测」的保守回落，不是「已确认支持」。
--    IN-6 事实探测完成后才允许由管理后台显式置 true。
-- =============================================================================

BEGIN;

-- ── 1. 结构：与 01_platform_ddl.sql 终态逐字对齐 ────────────────────────────
ALTER TABLE public.model_catalog
    ADD COLUMN IF NOT EXISTS context_window_tokens   integer NOT NULL DEFAULT 32768,
    ADD COLUMN IF NOT EXISTS max_input_tokens        integer NOT NULL DEFAULT 28672,
    ADD COLUMN IF NOT EXISTS tokenizer_id            text,
    ADD COLUMN IF NOT EXISTS supports_prompt_caching boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS supports_tokenize_api   boolean NOT NULL DEFAULT false;

-- 列注释：诊断 / 运维读库时能直接看到口径，避免「看列名猜语义」。
COMMENT ON COLUMN public.model_catalog.context_window_tokens IS
    '模型总窗口（输入+输出），默认 32768，范围 4096–1000000。决定 max_input_tokens 上界；'
    '未登记/不确定时按 R10 回落全局默认并上调 reserve。';
COMMENT ON COLUMN public.model_catalog.max_input_tokens IS
    '允许的输入 token 硬顶，默认 = context_window_tokens − 4096。'
    '直接决定压缩触发阈值与最终硬顶；必须 ≤ 总窗口 − 4096（输出预留），配置期校验不满足即拒绝。';
COMMENT ON COLUMN public.model_catalog.tokenizer_id IS
    '精确计数器标识；NULL = 启发式估算。有精确 tokenizer 时 reserveRatio 才可下调到 0.03。';
COMMENT ON COLUMN public.model_catalog.supports_prompt_caching IS
    '供应商是否支持前缀缓存；为 true 才启用 [D6] 稳定前缀守卫与缓存命中率指标。默认 false。';
COMMENT ON COLUMN public.model_catalog.supports_tokenize_api IS
    '供应商是否提供 /tokenize 精确计数端点；为 true 才优先调用（带本地兜底）。默认 false。';

-- ── 2. 结构断言：列真的落上了，且没有悄悄变成别的类型 ─────────────────────
DO $$
DECLARE
    v_missing text;
BEGIN
    SELECT string_agg(t.column_name, ', ' ORDER BY t.column_name)
      INTO v_missing
      FROM (VALUES
                ('context_window_tokens',   'integer'),
                ('max_input_tokens',        'integer'),
                ('tokenizer_id',            'text'),
                ('supports_prompt_caching', 'boolean'),
                ('supports_tokenize_api',   'boolean')) AS t(column_name, data_type)
      LEFT JOIN information_schema.columns c
             ON c.table_schema = 'public'
            AND c.table_name   = 'model_catalog'
            AND c.column_name  = t.column_name
            AND c.data_type    = t.data_type
     WHERE c.column_name IS NULL;
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION '升级校验失败：model_catalog 以下列缺失或类型不符 → %', v_missing;
    END IF;
END $$;

-- ── 3. 数据诊断（只读）：旧行的能力仍为保守回落 ───────────────────────────
-- ⚠️ 这不是「升级失败」：R10 要求能力未知时按 false / NULL 回落。
--    若某模型确实支持精确计数 / 前缀缓存，须由 IN-6 探测并经管理后台显式写入。
DO $$
DECLARE
    v_total        bigint;
    v_unprobed     bigint;
BEGIN
    SELECT count(*),
           count(*) FILTER (WHERE NOT supports_prompt_caching
                              AND NOT supports_tokenize_api
                              AND tokenizer_id IS NULL)
      INTO v_total, v_unprobed
      FROM public.model_catalog;
    RAISE NOTICE 'model_catalog 升级诊断：共 % 行，其中 % 行仍为「能力未探测」保守回落（R10 预期）',
        v_total, v_unprobed;
END $$;

COMMIT;

-- ── 验收清单（执行后人工核对） ─────────────────────────────────────────────
-- 1) SELECT column_name, data_type, column_default, is_nullable
--      FROM information_schema.columns
--     WHERE table_schema='public' AND table_name='model_catalog'
--       AND column_name IN ('context_window_tokens','max_input_tokens','tokenizer_id',
--                           'supports_prompt_caching','supports_tokenize_api')
--     ORDER BY column_name;
--    → 5 行；integer / integer / text / boolean / boolean；前 2 列与 2 个 bool 均 NOT NULL。
-- 2) SELECT count(*) FROM public.model_catalog
--     WHERE max_input_tokens > context_window_tokens - 4096;   -- 必须为 0
-- 3) 若随后重跑了 03，必须紧跟重跑 04_retention_role.sql（见文件头）。
-- 4) 结构快照三数（71 / 44 / 52）**不变**；`SchemaDriftIT` 需在两套活库真跑。
-- =============================================================================
