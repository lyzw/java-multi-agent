-- =====================================================================
-- V4 迁移：解除 RAG 库的租户信息副本（RAG-D00 §4「租户与身份」边界）
--
-- 背景（2026-10-22 架构澄清）：
--   「RAG 数据库只存放 RAG 相关信息，不存放租户相关的信息；
--     租户相关的信息包括权限等来自 platform 项目接口。」
--
-- ⚠️ V1 建了 rag_tenant 表（slug / name / min_trust_tier），
--    它是 platform.tenant 的**重复副本**，
--    与 RAG-D00 §4 的边界决策相悖：
--      「租户与身份 | platform/ | RAG 只消费 TenantContext，不自建认证」
--      「租户身份、权限、审计的权威源始终是 platform」
--
--    实际危害（本次修复的直接起因）：
--      ① RAG 库里必须**手工维护**一份租户清单，否则 RLS 的
--         `tenant_id REFERENCES rag_tenant` 外键会让任何写入直接失败；
--      ② 平台新建租户后，RAG 侧**不会自动出现**该租户，
--         导致「登录后 RAG 管理面查不到任何数据」——
--         租户信息在两个系统间**漂移**，且漂移方向是「RAG 缺租户」；
--      ③ min_trust_tier（租户权威门槛）在 platform 侧**并不存在**，
--         于是 RAG 自己发明了它 → 门槛语义只存在于 RAG，
--         platform 的「租户管理员设置本租户门槛」这条规则**无处落地**。
--
-- 本迁移做三件事：
--   1. 移除 8 个子表对 rag_tenant 的外键约束，tenant_id 退化为普通列
--   2. 删除 rag_tenant 表（租户信息的副本随之消失）
--   3. 补上「租户权威门槛」的下发契约说明（见 rag-management 的端口注释）
--
-- ⚠️ 隔离纪律**不变**：
--    tenant_id 列仍然是 NOT NULL，RLS 策略仍然以
--    `tenant_id = rag_current_tenant()` 收口。
--    去掉外键**不等于**去掉隔离——外键只保证「引用的租户在清单里存在」，
--    真正的隔离靠 RLS（V1 §7 + RD-13：必须用非特权角色验证）。
-- =====================================================================

SET search_path TO rag, public;

-- ---------------------------------------------------------------------
-- 1. 解除外键约束
-- ---------------------------------------------------------------------
-- ⚠️ 必须**先删外键再删表**：直接 DROP TABLE rag_tenant CASCADE
--    会连带删掉 8 个子表的 tenant_id 列——那等于把 RAG 的全部数据删光。
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'rag_source', 'rag_edition', 'rag_knowledge_point',
        'rag_knowledge_prerequisite', 'rag_document', 'rag_chunk',
        'rag_ingest_job', 'rag_answer_citation'
    ] LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT IF EXISTS %I', t, t || '_tenant_id_fkey');
    END LOOP;
END$$;

-- ⚠️ 兜底：V1 里的外键约束名是**自动生成**的（未显式命名），
--    形如 rag_source_tenant_id_fkey / rag_edition_tenant_id_fkey …，
--    上面的循环按「表名_tenant_id_fkey」尝试删除。
--    但若某张表的外键名因历史原因不同，仍会残留——
--    故再用一次「按引用关系反查」把剩下的全部摘掉。
DO $$
DECLARE
    r record;
BEGIN
    -- ⚠️ 不能用 `confrelid = 'rag.rag_tenant'::regclass`：
    --    该写法在表**不存在**时会直接抛 "relation does not exist"，
    --    使整个迁移失败（2026-10-22 实测踩中：首版误写成 'rag.tenant'）。
    --    改用「表名字符串比对」，表不存在时自然查不到行，不报错。
    FOR r IN
        SELECT c.conrelid::regclass AS tbl, c.conname
        FROM pg_constraint c
        JOIN pg_class parent ON parent.oid = c.confrelid
        JOIN pg_namespace ns ON ns.oid = parent.relnamespace
        WHERE c.contype = 'f'
          AND ns.nspname = 'rag'
          AND parent.relname = 'rag_tenant'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.tbl, r.conname);
    END LOOP;
END$$;

-- ---------------------------------------------------------------------
-- 2. 删除租户副本表
-- ---------------------------------------------------------------------
-- ⚠️ 先确保没有任何子表还在引用它（上面的循环已处理），
--    再 DROP TABLE ... RESTRICT：若仍有引用则报错，
--    而不是 CASCADE 静默删掉子表列（那会丢数据）。
DROP TABLE IF EXISTS rag.rag_tenant RESTRICT;

-- ---------------------------------------------------------------------
-- 3. tenant_id 列的语义固化
-- ---------------------------------------------------------------------
-- ⚠️ 迁移后 tenant_id 是一个**无外键的普通 UUID 列**：
--    · 它是「platform 权威租户 ID」的**引用值**，不是租户记录的副本；
--    · RAG **不校验**该租户是否存在（校验是 platform 的职责，
--      跨系统外键无法建立，见 RAG-D07 §3 冲突仲裁）；
--    · 租户被 platform 删除时，RAG 侧的清理由**平台侧编排**负责
--      （「先撤回 → 再清数据」两阶段，RAG-D07 §5.5 要求先阻断后核实）。
--
--    ⛔ 不再提供 ON DELETE CASCADE：删租户**不是** RAG 的自主行为，
--       贸然级联会抹除历史引用（破坏可溯源性，RAG-D07 §5.5）。
COMMENT ON COLUMN rag.rag_source.tenant_id IS
    'platform 权威租户 ID 的引用值。RAG 不存租户信息、不校验租户存在性；隔离由 RLS 保证。';
COMMENT ON COLUMN rag.rag_chunk.tenant_id IS
    'platform 权威租户 ID 的引用值。RAG 不存租户信息、不校验租户存在性；隔离由 RLS 保证。';

-- ---------------------------------------------------------------------
-- 4. 租户权威门槛（min_trust_tier）的归属
-- ---------------------------------------------------------------------
-- ⚠️ 该门槛的**权威源是 platform**（RAG-D07 §3.3：租户管理员设置本租户门槛，
--    且不得降至 UNVERIFIED）。但 platform 的 tenant 表**当前尚无该字段**
--    （截至 2026-10-22 实测确认）。
--
--    故此刻的处置是：
--      · RAG **不再自行存储**门槛（随 rag_tenant 一并移除）；
--      · 门槛由 platform 在调用 RAG 时**随租户上下文下发**
--        （见 rag-management 的 TenantContextHeader 契约）；
--      · platform 侧字段尚未落地时，platform 应下发其**默认值**
--        OFFICIAL_TUTORIAL（RAG-D00 RD-Q7 决议的默认值）。
--
--    ⛔ 本迁移**不**在 RAG 侧补一张门槛表——那等于重建刚被移除的副本。
--       缺口登记为 RAG-R23（详见 RAG-D10 文档）。
