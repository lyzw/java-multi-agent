-- =============================================================================
-- 代际构建游标 · 活库等价 ALTER
-- =============================================================================
-- 纪律：01_rag_ddl.sql 是「终态快照」，本文件是它的**等价增量**，用于已存在的活库。
--
-- 【解决什么】RAG-D13 §3.5.2 索引代际构建的**异步化**（RAG-D21 §六 P1）：
--   改造前 `POST /index-generations/{g}/build` 在 HTTP 线程上一次性跑完全部片段，
--   数万片段会阻塞数十分钟，且进程重启后进度丢失、无法续跑。
--
--   异步方案是「worker 轮询 + 分批推进」：每轮处理一批就释放，
--   下一轮从**上次的位置**继续。这需要把游标持久化。
--
-- 【为什么用键集游标而不是 OFFSET / indexed_chunks】
--   构建期间片段可能被撤回或新增：
--     · OFFSET N —— 前面删了行，后面会整体错位，导致**跳过**部分片段；
--     · 用 indexed_chunks 当偏移 —— 同上，且它语义是"已写入行数"不是"扫到哪"。
--   键集游标（记住最后一个 chunk_id）不受增删影响。
--
-- ⚠️ 游标是 `text` 而非 `uuid`：全 0 UUID 作哨兵，且允许 NULL 表示"还没开始"。
--    用 text 是为了避免 UUID 类型转换的边界问题（与既有 `chunk_id::text` 一致）。
-- =============================================================================

BEGIN;

-- ── 1. 构建游标 ─────────────────────────────────────────────────────────────
ALTER TABLE rag.rag_index_generation
    ADD COLUMN IF NOT EXISTS build_cursor text;

COMMENT ON COLUMN rag.rag_index_generation.build_cursor IS
    '键集分页游标：上一个已处理的 chunk_id（text）。NULL = 尚未开始。'
    '⚠️ 不能用索引加偏移：构建期间片段增删会让 OFFSET 错位、跳过片段。';

-- ── 2. 待构建代际的查询索引 ─────────────────────────────────────────────────
--
-- worker 每轮都会问「有没有待构建的代际」，这个查询必须是索引命中而非全表扫。
CREATE INDEX IF NOT EXISTS rag_index_generation_pending_idx
    ON rag.rag_index_generation USING btree (tenant_id, generation)
    WHERE status = 'BUILDING';

COMMIT;

-- =============================================================================
-- 回滚
-- =============================================================================
-- BEGIN;
-- DROP INDEX IF EXISTS rag.rag_index_generation_pending_idx;
-- ALTER TABLE rag.rag_index_generation DROP COLUMN IF EXISTS build_cursor;
-- COMMIT;
