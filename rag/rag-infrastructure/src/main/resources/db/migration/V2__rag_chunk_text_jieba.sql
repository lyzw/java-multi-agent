-- =====================================================================
-- V2 迁移：rag_chunk 词法通道预分词列（RAG-D02 §3.1.2）
--
-- 方案：应用层用 ChineseTokenizer 切分 chunk.text，把结果存入 text_jieba 列，
--       DB 侧建 GIN(to_tsvector('simple', text_jieba)) 索引。
--       词法通道与稠密通道（pgvector）在 JdbcRetrievalRepository 内用 RRF 融合。
--
-- ⚠️ 为什么不在 DB 里切分中文：PostgreSQL 默认分词器（含 simple 词典）
--       不切分中文。若直接对 text 建 tsvector，中文会被当成一个整体 lexeme，
--       词法召回退化为「整段包含」的粗匹配。应用层预分词使词法通道可用。
--
-- ⚠️ 历史数据回填：本迁移只保证列与索引存在；已存在的 chunk 的 text_jieba
--       由 rag-worker 入库流水线在下次重索引时补齐（见 RAG-D09 R2-1）。
-- =====================================================================

SET search_path TO rag, public;

-- 预分词列：应用层已切分为空格分隔的词元序列（unigram + bigram）
ALTER TABLE rag_chunk ADD COLUMN IF NOT EXISTS text_jieba TEXT NOT NULL DEFAULT '';

-- 词法通道 GIN 索引（simple 词典：不切分中文，每个词元作为独立 lexeme）
CREATE INDEX IF NOT EXISTS rag_chunk_jieba_idx
    ON rag_chunk USING gin (to_tsvector('simple', text_jieba));

-- ⚠️ 部分索引 rag_chunk_retrievable_idx（V1 已建）只索引可检索角色，
--    是存储层的泄题防线；本迁移不改动它，词法索引与之互补。
