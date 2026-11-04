-- =============================================================================
-- 版面事实持久化 · 活库等价 ALTER
-- =============================================================================
-- 纪律：01_rag_ddl.sql 是「终态快照」，本文件是它的**等价增量**，用于已存在的活库。
--       切勿对已存在的库重放 01_rag_ddl.sql（它是 CREATE TABLE，非幂等）。
--
-- 【解决什么】RAG-D13 §3.2.4「片段校对」的判定依据展示：
--   角色判定是**基于版面事实的确定性规则**（答案区标识 + 题号 + 块首前缀），
--   而改造前这些事实**只存在于切分时的内存对象里**，落库时被丢弃，
--   库中只留下最终角色（role）。
--
--   后果：校对员看到「这条被判成讲解」时，**无从判断系统为什么这么判**——
--   只能凭感觉改，改完也无从复核。而误判的最坏后果是试题/答案泄露（泄题）。
--
-- 【为什么存快照而不是每次重算】
--   解析器版本升级后，同一片段的重算结果可能不同。存快照才能解释
--   「历史上为什么那样判」；重算会抹掉这段历史，让事后审计失去依据。
--
-- ⚠️ 三列全部可空 / 有默认值：存量片段没有这些信息，
--   界面显示"该片段早于版面事实采集，无判定依据"——
--   **不伪造一个「不在答案区」**（那会被读成"系统确认过"，与"没采集"是两件事）。
--
-- ⚠️ 上线顺序：先跑本文件 → 再部署应用（应用写入会带这三列）。
-- =============================================================================

BEGIN;

-- ── 1. rag_chunk：版面事实三列 ─────────────────────────────────────────────
--
-- ⚠️ 列名与 `LayoutFacts` / `LayoutBlock` 的字段一一对应，不要另起别名：
--    in_answer_zone  ← LayoutBlock.inAnswerZone
--    block_type      ← LayoutBlock.type（BlockType 枚举名）
--    matched_prefix  ← 块首命中的解答前缀原文（如「解:」）
ALTER TABLE rag.rag_chunk
    ADD COLUMN IF NOT EXISTS in_answer_zone boolean NOT NULL DEFAULT false;

ALTER TABLE rag.rag_chunk
    ADD COLUMN IF NOT EXISTS block_type text;

ALTER TABLE rag.rag_chunk
    ADD COLUMN IF NOT EXISTS matched_prefix text;

COMMENT ON COLUMN rag.rag_chunk.in_answer_zone IS
    '是否位于答案区（版面级判定快照）。⚠️ false 不等于"确认不在答案区"——需结合 block_type IS NULL 判断是否采集过。';
COMMENT ON COLUMN rag.rag_chunk.block_type IS
    '版面块类型（BlockType 枚举名）。NULL = 该片段早于版面事实采集。';
COMMENT ON COLUMN rag.rag_chunk.matched_prefix IS
    '块首命中的解答前缀原文（如「解:」）。NULL = 未命中或未采集。';

-- ── 2. 校对页的查询索引 ─────────────────────────────────────────────────────
--
-- 校对页按「疑似误判」筛：答案区 + 被判为非答案角色（最可能泄题的方向）。
-- 这个部分索引让该查询不必全表扫。
CREATE INDEX IF NOT EXISTS rag_chunk_answer_zone_idx
    ON rag.rag_chunk USING btree (tenant_id, document_id, in_answer_zone)
    WHERE in_answer_zone = true;

COMMIT;

-- =============================================================================
-- 回滚（⚠️ 会丢失版面事实，需单独审批）
-- =============================================================================
-- BEGIN;
-- DROP INDEX IF EXISTS rag.rag_chunk_answer_zone_idx;
-- ALTER TABLE rag.rag_chunk DROP COLUMN IF EXISTS matched_prefix;
-- ALTER TABLE rag.rag_chunk DROP COLUMN IF EXISTS block_type;
-- ALTER TABLE rag.rag_chunk DROP COLUMN IF EXISTS in_answer_zone;
-- COMMIT;
