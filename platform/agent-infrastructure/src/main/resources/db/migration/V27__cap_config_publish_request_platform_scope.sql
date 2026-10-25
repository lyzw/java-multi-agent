-- =====================================================================
-- V27：配置变更申请单支持 PLATFORM 作用域（第三十四批 · C34-2）
--
-- 对标设计文档：
--   docs/多级 Agent优化/06_配置数据模型与生命周期设计.md
--   docs/多级 Agent优化/09_需求追踪矩阵.md
--
-- 技术基线：PostgreSQL 17（DEC-13）
--
-- 背景（第三十四批 · C34-2 · 管理台改造）：
--   Tool / Skill / MCP Server 是 **platform 级对象**（无 tenant_id，不启用 RLS），
--   它们的发布必须写 PLATFORM 作用域的发布头（cap_config_release）与
--   当前指针（cap_config_pointer）—— 而这两张表的**写策略只放行本租户的 TENANT 行**
--   （V15 / V17），PLATFORM 行**只能**由平台运维角色（BYPASSRLS）写入。
--
--   管理台要能维护这三类对象，写入口就必须落在 PLATFORM 作用域上；
--   而「双人复核」要求申请与审批是**两次独立的、带身份的调用**
--   （V18 迁移注释：两个 id 放进一次请求由调用方填写，双人复核就退化成自证）。
--   于是平台级变更**也必须走申请单** —— 而 V18 建表时
--   `cap_config_publish_request.tenant_id` 是 `UUID NOT NULL`，
--   PLATFORM 作用域的申请单**存不进去**。
--
-- ⚠️ **本迁移只做两件事，且刻意不改 RLS 策略**：
--   ① `tenant_id` 改为**可空**（PLATFORM 行用 NULL）；
--   ② 新增 `scope` 列，并加 CHECK 保证「scope 与 tenant_id 的一致性」。
--
-- ⚠️ **为什么 RLS 策略一个字都不改**：
--   本表的读写策略是严格相等（`tenant_id = current_tenant_id()`），
--   平台行（`tenant_id IS NULL`）因此对**应用角色连接天然不可见** ——
--   租户既读不到、也写不进平台的申请单。
--   平台的申请单只能经**平台运维角色（BYPASSRLS）连接**读写，
--   这正是「平台级变更不经租户身份暴露」这条纪律在存储层的落点。
--   若为了「让平台申请单能被应用连接读到」而放行 `tenant_id IS NULL`，
--   就等于让**任一租户**读到（甚至写入）全平台的变更方案 ——
--   那正是 V18 刻意避免的「多一个 IS NULL 的例外，就少一个能出事的分支」。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 结构变更（可重复执行）
--
-- ⚠️ **幂等**：`ADD COLUMN IF NOT EXISTS` 是 PG 原生语法；
--    但 `ADD CONSTRAINT` **没有** `IF NOT EXISTS`（PG 17 实测），
--    故用 `pg_constraint` 存在性判断来跳过（同 V15 / V17 的做法）。
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'cap_config_publish_request'
                     AND column_name = 'scope') THEN
        ALTER TABLE cap_config_publish_request
            ADD COLUMN scope TEXT NOT NULL DEFAULT 'TENANT';
    END IF;

    -- ⚠️ PLATFORM 作用域的申请单没有租户 → tenant_id 必须可空。
    --    重复执行安全（DROP NOT NULL 是幂等的）。
    ALTER TABLE cap_config_publish_request ALTER COLUMN tenant_id DROP NOT NULL;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'cap_config_publish_request_scope_ck') THEN
        ALTER TABLE cap_config_publish_request
            ADD CONSTRAINT cap_config_publish_request_scope_ck CHECK (
                (scope = 'TENANT' AND tenant_id IS NOT NULL)
                OR (scope = 'PLATFORM' AND tenant_id IS NULL));
    END IF;
END;
$$;

-- ---------------------------------------------------------------------
-- 2. 口径注释
--
-- ⚠️ **scope 的取值只有两个**，与 `cap_config_release.scope` 同名同义：
--    TENANT   —— 租户级对象（Agent / 输出契约 / 路由策略）；tenant_id **必填**；
--    PLATFORM —— 平台级对象（Tool / Skill / MCP Server）；tenant_id **必为 NULL**。
--
-- ⚠️ **存量行无需回填**：`scope` 的 DEFAULT 是 'TENANT'，
--    而 V18 建表时所有行都带 tenant_id → 回填值天然正确。
--    （若将来新增列时 DEFAULT 不是 'TENANT'，就必须显式回填 —— 届时需改本迁移。）
-- ---------------------------------------------------------------------
COMMENT ON COLUMN cap_config_publish_request.scope IS
    '变更作用域：TENANT（租户级对象，tenant_id 必填）/ PLATFORM（平台级对象，tenant_id 必为 NULL）。'
    'PLATFORM 行对应用角色连接不可见（RLS 严格相等），只能经平台运维角色（BYPASSRLS）读写。';

COMMENT ON TABLE cap_config_publish_request IS
    '配置变更申请单（P6R4 管理端点 · 第三十四批起亦承载平台级变更）：'
    '承载**待审批**的发布 / 回滚 / 生命周期迁移方案。'
    '双人复核的载体 —— 申请与审批是两次独立的、带身份的调用。'
    '第三十四批起支持 PLATFORM 作用域（平台级 Tool / Skill / MCP Server 的变更）。';
