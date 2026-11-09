-- =============================================================================
-- 07_agent_definition_upgrade.sql
-- 活库结构升级：Agent 定义权威源结构化列 + Tool 模型可见描述（2026-11-10）
--
-- 适用对象：**存量活库**（2026-11-09 之前建的库）。
--   · 新建库不要用本文件 —— `01_platform_ddl.sql` 是终态快照，已含全部列；
--     `init-db.sh` 只按顺序加载 01 / 02 / 03 / 04，不会自动执行本文件。
--   · 本文件**幂等**（IF NOT EXISTS），可在已手工补过列的库上重复执行。
--
-- 背景（06 §5.3 / 审查《Agent 定义体系审查_2026-11-09》）：
--   运行期提示词 / 模型 / 生成参数改读 `cap_agent_def` 结构化列，
--   `agent_version.definition` 降级为发布事务内**派生**的归档。
--   2026-11-09 那批只改了 01/02 快照与代码，仓库内没有可复现的活库 ALTER，
--   本地 agent_dev / agent_it 是手工补的列 —— 本文件补齐这一环。
--
-- ⚠️ 执行身份：库 owner / 管理员（DDL 权限）。**不要用 app_agent 跑**：
--    业务 SQL 一律走 app_agent，但 DDL 属于带外运维，二者不可混用。
-- ⚠️ 若本升级之后重跑过 `03_platform_roles.sql`（全表 GRANT 会覆盖
--    `04_retention_role.sql` 的 REVOKE DELETE），**必须紧跟重跑 04**。
--    本文件不新增表，故 `init-db.sh` 的 EXPECT_TABLES/RLS/POLICIES 与
--    `it-env.sh --probe` 的 71 表 / 44 RLS 表 / 52 策略口径**不变**。
-- =============================================================================

BEGIN;

-- ── 1. 结构：与 01_platform_ddl.sql 终态逐字对齐 ────────────────────────────
-- ⚠️ 三列都带 DEFAULT '{}' 且 NOT NULL：这不是「给了默认值就能跑」，
--    而是「旧行被兼容读成**未声明**」—— 运行期据此 fail-closed，
--    绝不把缺省当成「绑定了某个默认模型」。
ALTER TABLE public.cap_agent_def
    ADD COLUMN IF NOT EXISTS instructions      jsonb DEFAULT '{}'::jsonb NOT NULL,
    ADD COLUMN IF NOT EXISTS model_ref         text,
    ADD COLUMN IF NOT EXISTS generation_params jsonb DEFAULT '{}'::jsonb NOT NULL;

ALTER TABLE public.cap_tool_def
    ADD COLUMN IF NOT EXISTS description text;

-- ── 2. 结构断言：列真的落上了，且没有悄悄变成别的类型 ─────────────────────
DO $$
DECLARE
    v_missing text;
BEGIN
    SELECT string_agg(t.table_name || '.' || t.column_name, ', ' ORDER BY t.table_name)
      INTO v_missing
      FROM (VALUES
                ('cap_agent_def', 'instructions',      'jsonb'),
                ('cap_agent_def', 'model_ref',         'text'),
                ('cap_agent_def', 'generation_params', 'jsonb'),
                ('cap_tool_def',  'description',       'text')) AS t(table_name, column_name, data_type)
      LEFT JOIN information_schema.columns c
             ON c.table_schema = 'public'
            AND c.table_name   = t.table_name
            AND c.column_name  = t.column_name
            AND c.data_type    = t.data_type
     WHERE c.column_name IS NULL;
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION '升级校验失败：以下列缺失或类型不符 → %', v_missing;
    END IF;
END $$;

-- ── 3. 数据诊断（只读）：升级后哪些 Agent 仍然跑不起来 ─────────────────────
-- ⚠️ `model_ref IS NULL` 的行，在新代码下一律 fail-closed 拒绝执行 Run。
--    这不是「配置待优化」，是「这批 Agent 全部不可用」，必须显式处置。
DO $$
DECLARE
    v_total     bigint;
    v_published bigint;
    v_draft     bigint;
BEGIN
    SELECT count(*),
           count(*) FILTER (WHERE published_at IS NOT NULL),
           count(*) FILTER (WHERE published_at IS NULL)
      INTO v_total, v_published, v_draft
      FROM public.cap_agent_def
     WHERE model_ref IS NULL OR model_ref = '';
    RAISE NOTICE '未绑定模型的 cap_agent_def：共 % 行（已发布 % / 草稿 %）',
        v_total, v_published, v_draft;
END $$;

-- 明细供运维逐条处置（可直接单独执行）：
-- SELECT tenant_id, agent_id, version, name, lifecycle_state, published_at,
--        instructions, generation_params
--   FROM public.cap_agent_def
--  WHERE model_ref IS NULL OR model_ref = ''
--  ORDER BY tenant_id, agent_id, version;

-- ── 4. 回填口径（⚠️ 不自动执行，且绝大多数情况**不应该**执行）──────────────
--
-- 4.1 已发布行**禁止就地回填**：`trg_cap_agent_def_immutable` 只允许
--     PUBLISHED→DEPRECATED 的纯状态停用，任何内容 UPDATE 都会抛
--     restrict_violation。这是设计约束，不是脚本可以绕过的障碍：
--       版本行不可变 → 正确处置是「走治理三步发布，发 version+1 的新行」，
--       由 `ConfigPublishService` 重新计算 cap_obj_fp_v1 并派生归档。
--     ⚠️ 绝不因为旧行指纹是旧口径就重算指纹：那会让「内容没变」的证明失真。
--
-- 4.2 草稿行（published_at IS NULL）可以编辑，但**绝不自动填默认模型**：
--     「猜一个模型」会让配置缺失被伪装成配置正常，正是 fail-closed 要防的事。
--     确需补齐时由运维按已批准的模型目录显式填值，模板如下（默认注释）：
--
-- UPDATE public.cap_agent_def
--    SET instructions      = '{"inlineInstructions": "<经审批的提示词原文>"}'::jsonb,
--        model_ref         = '<模型目录中已批准的 modelRef>',
--        generation_params = '{"temperature": 0.3, "maxOutputTokens": 2048}'::jsonb
--  WHERE published_at IS NULL
--    AND tenant_id = '<tenant-uuid>' AND agent_id = '<agent-uuid>' AND version = 1;
--
-- 4.3 ⚠️ **绝不从 `agent_version.definition` 反填** `cap_agent_def`：
--     方向反了 —— 归档是权威源的派生物，把归档内容倒灌回权威源
--     等于让「历史快照」定义「当前事实」。
--
-- 4.4 工具描述同理：已发布 Tool 行不可改写；如需补描述，走 PLATFORM 作用域
--     的三步发布发新版本（治理通道 `PlatformToolSpec.description`）。

COMMIT;

-- ── 5. 升级后验收清单 ──────────────────────────────────────────────────────
-- ① `SELECT count(*) FROM cap_agent_def WHERE model_ref IS NULL;` 应有明确处置结论
--    （发新版本 / 停用），不能留着「不知道」。
-- ② 新建 Run 走一次三步发布 → 建 Run → worker 装配，断言提示词与模型来自新行
--    （`ConfigPublishManagementIT` 的跨表面闭环用例）。
-- ③ 若重跑过 03_platform_roles.sql，确认 04_retention_role.sql 已紧跟重跑
--    （否则 RetentionErasureIT 会因 DELETE 权限被 GRANT 覆盖而红）。
