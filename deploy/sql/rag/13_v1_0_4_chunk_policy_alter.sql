-- =============================================================================
-- 物料前期处理留痕 · 活库等价 ALTER（RAG-D26 阶段 2.5）
-- =============================================================================
-- 纪律：01_rag_ddl.sql 是「终态快照」，本文件是它的**等价增量**，用于已存在的活库。
--       切勿对已存在的库重放 01_rag_ddl.sql（它是 CREATE TABLE，非幂等）。
--
-- 【解决什么】RAG-D26 §2.5 的留痕缺口：
--   rag_document.parser_version 只有一个版本号，无法表达
--   「解析器 + 切分策略 + 切分参数」三元组。切分策略可切换后
--   （rag.premise.chunk-policy = heading | hybrid），
--   「这批语料按哪套规则切的」必须可查——否则：
--   ① 评测无法把「检索表现变化」归因到切分策略（会把切分差异误归因为模型）；
--   ② 按策略筛选「需重灌的册」这类运维查询无从下手；
--   ③ HybridChunker 与标题层级分块的 token 边界不同，混切不可比。
--
-- ⚠️ 两列全部可空：存量片段/任务早于本改造，NULL = 「当时只有内置切分」
--   ——不伪造一个策略值（那会被读成"系统确认过"，与"没采集"是两件事，
--   同 10_v1_0_3 版面事实的口径）。
--
-- ⚠️ 上线顺序：先跑本文件 → 再部署应用（应用写入会带这两列）。
--
-- ⚠️ 历史数据语义（阶段 4 收口时可选回填，非必须）：
--   所有存量语料都是旧内置切分（= HEADING_HIERARCHY 的等价行为）产出，
--   如需显式标记可执行：
--     UPDATE rag.rag_document SET chunk_policy = 'HEADING_HIERARCHY'
--      WHERE chunk_policy IS NULL;
--   不默认回填的理由：NULL 表达「早于采集」是准确的历史事实。
-- =============================================================================

BEGIN;

-- ── 1. rag_document：切分策略 ───────────────────────────────────────────────
--
-- ⚠️ 与 ChunkPolicy 枚举名严格一致（HEADING_HIERARCHY / HYBRID_CHUNKER），
--    不要另起别名（如 hybrid / docling-hybrid）——运维查询按枚举名过滤。
ALTER TABLE rag.rag_document
    ADD COLUMN IF NOT EXISTS chunk_policy text;

COMMENT ON COLUMN rag.rag_document.chunk_policy IS
    '切分策略（ChunkPolicy 枚举名）。NULL = 早于 RAG-D26 改造（旧内置切分）。';

-- ── 2. rag_ingest_job：切分策略 + 参数快照 ─────────────────────────────────
--
-- ⚠️ config 快照取 ChunkPolicyConfig.stableString() 的确定性串
--    （maxTokens=...,overlapRatio=...,mergePeers=...,repeatTableHeader=...），
--    字段顺序固定、可按等值比较——不用 jsonb 是为了让「参数完全相同」
--    成为一条 SQL 等值条件（jsonb 的键序规范化反而引入歧义）。
ALTER TABLE rag.rag_ingest_job
    ADD COLUMN IF NOT EXISTS chunk_policy text;

ALTER TABLE rag.rag_ingest_job
    ADD COLUMN IF NOT EXISTS chunk_policy_config text;

COMMENT ON COLUMN rag.rag_ingest_job.chunk_policy IS
    '本次 job 使用的切分策略（ChunkPolicy 枚举名）。NULL = 早于 RAG-D26 改造。';
COMMENT ON COLUMN rag.rag_ingest_job.chunk_policy_config IS
    '切分参数快照（ChunkPolicyConfig.stableString()）。NULL = 未采集或策略无参数。';

-- ── 3. 按策略筛「需重灌的册」的查询索引 ────────────────────────────────────
--
-- 运维场景：某策略发现缺陷 → 找出所有用该策略切过的册 → 逐册重灌。
CREATE INDEX IF NOT EXISTS rag_document_chunk_policy_idx
    ON rag.rag_document USING btree (chunk_policy)
    WHERE chunk_policy IS NOT NULL;

COMMIT;
