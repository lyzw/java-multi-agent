-- =============================================================================
-- V1.0.2 活库等价 ALTER（二期：元数据确认）
-- =============================================================================
-- 纪律：01_rag_ddl.sql 是「终态快照」，本文件是它的**等价增量**，用于已存在的活库。
--       切勿对已存在的库重放 01_rag_ddl.sql（它是 CREATE TABLE，非幂等）。
--       本文件同样不得重放到「已含这些列」的快照重建库——用 IF NOT EXISTS 保证幂等。
--
-- 交互文档 §4.3「产出确认」：文件上传后由系统推断元数据（书名/出版社/学科/
-- 年级/册次/ISBN），**推断值必须由人确认后才可提交审核**（V1.0.2 §5 R1）。
--
-- ⚠️ 设计取舍：**为什么不建推断值表**
--   推断结果本身是「一次性、可重算」的：它由文件名/首屏/版式得出，
--   算错重算即可，没有审计价值（审计要的是「人确认了什么」，不是「机器猜了什么」）。
--   故这里只落两个**事实**：
--     · metadata_confirmed：人是否确认过（迁移前置守卫的依据）；
--     · metadata_confirmed_at / metadata_confirmed_by：谁在何时确认（留痕）。
--   推断值留在前端一次性展示，避免为「可重算的中间态」建表。
--
-- ⚠️ 上线顺序：先跑本文件 → 再部署应用（读路径缺列会直接 SQL 报错）。
-- =============================================================================

BEGIN;

-- ── 1. rag_source：元数据确认状态 ────────────────────────────────────────────

-- ⚠️ 默认 **false**：存量来源一律「未确认」。
--    这是 fail-closed 的选择——存量来源从未经过「推断值确认」这一步，
--    把它们当已确认等于凭空给人背书。运维可用下面的回填语句显式放行存量。
ALTER TABLE rag.rag_source
    ADD COLUMN IF NOT EXISTS metadata_confirmed boolean NOT NULL DEFAULT false;

ALTER TABLE rag.rag_source
    ADD COLUMN IF NOT EXISTS metadata_confirmed_at timestamp with time zone;

ALTER TABLE rag.rag_source
    ADD COLUMN IF NOT EXISTS metadata_confirmed_by uuid;

-- ── 2. 一致性约束：确认了就必须留痕（谁、何时）──────────────────────────────
--    ⚠️ 与 rag_source_verified_requires_evidence_chk 同一纪律：
--       「已确认」这个状态不能没有来源，否则审计时无法回答「谁确认的」。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'rag_source_metadata_confirm_chk'
    ) THEN
        ALTER TABLE rag.rag_source
            ADD CONSTRAINT rag_source_metadata_confirm_chk
            CHECK (
                metadata_confirmed = false
                OR (metadata_confirmed_at IS NOT NULL AND metadata_confirmed_by IS NOT NULL)
            );
    END IF;
END
$$;

-- ── 3. 读路径索引：待确认来源的筛选 ─────────────────────────────────────────
--    「哪些还没确认」是录入工作台的高频查询。
CREATE INDEX IF NOT EXISTS rag_source_tenant_unconfirmed_idx
    ON rag.rag_source USING btree (tenant_id, created_at DESC)
    WHERE metadata_confirmed = false;

-- ── 4. 存量回填（**默认不执行**，由运维按需显式放行）────────────────────────
--    ⚠️ 注释掉是刻意的：回填等于「替历史数据背书」，必须是人做的决定。
--    仅当确认「这批存量来源的元数据已由人工可靠提供」后才取消注释执行：
--
--    UPDATE rag.rag_source
--       SET metadata_confirmed = true,
--           metadata_confirmed_at = now(),
--           metadata_confirmed_by = '<运维主体 UUID>'
--     WHERE scope = 'TENANT'
--       AND metadata_confirmed = false
--       AND created_at < '<上线时间点>';

COMMIT;

-- =============================================================================
-- 回滚（仅在需要撤销本增量时执行；数据列保留更安全，故只删约束与索引）
-- =============================================================================
-- BEGIN;
-- ALTER TABLE rag.rag_source DROP CONSTRAINT IF EXISTS rag_source_metadata_confirm_chk;
-- DROP INDEX IF EXISTS rag.rag_source_tenant_unconfirmed_idx;
-- -- ⚠️ 列删除会丢失确认留痕，含数据损失的不可逆操作，需单独审批：
-- -- ALTER TABLE rag.rag_source DROP COLUMN IF EXISTS metadata_confirmed_by;
-- -- ALTER TABLE rag.rag_source DROP COLUMN IF EXISTS metadata_confirmed_at;
-- -- ALTER TABLE rag.rag_source DROP COLUMN IF EXISTS metadata_confirmed;
-- COMMIT;
