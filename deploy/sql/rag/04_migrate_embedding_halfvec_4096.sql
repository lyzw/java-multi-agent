-- 迁移：rag_chunk_embedding.embedding  vector(1024) → halfvec(4096)
--
-- 背景：接入真实嵌入模型 qwen3-embedding:8b（4096 维）替换本地确定性占位（1024 维）。
-- 旧占位向量与新模型向量<b>不可比</b>，全部作废，故本脚本连带清空向量表。
--
-- ⚠️ 维度上限（pgvector 0.8.x）
--   存储：vector / halfvec 均可到 16000 维；
--   ANN 索引：HNSW/IVFFlat 对 vector 上限 2000 维、对 halfvec 上限 4000 维。
--   4096 > 4000 ⇒ 本迁移后<b>没有</b>稠密向量的 ANN 索引，
--   检索退化为全表精确扫描（当前语料量级下这比带召回损失的 HNSW 更快也更准）。
--   语料规模上到数万片段后，二选一：截断到 ≤4000 维（MRL 截断）或改用二进制量化索引。
--
-- 执行方式：由库 owner 在 agent-pg17 容器内对 study_rag 执行。
--   ⚠️ 先在事务里 dry-run（结尾 ROLLBACK），确认 ALTER/DELETE 都能过，再原样改成 COMMIT 跑第二遍。
--   ⚠️ 共享业务库：禁止 DROP DATABASE / DROP SCHEMA，本脚本只做列级 ALTER 与行级 DELETE。

SET search_path TO rag, public;
-- 共享库上 ALTER 要拿 ACCESS EXCLUSIVE 锁；拿不到就快速失败，
-- 不要排在长查询后面把整条检索链路一起卡住。
SET lock_timeout = '20s';
SET statement_timeout = '120s';

BEGIN;

-- 1) 删掉按旧类型建的 ANN 索引（4096 维无法再建同类索引）
DROP INDEX IF EXISTS rag.rag_chunk_embedding_hnsw_idx;

-- 2) 换列类型：halfvec(4096)
--    旧表此刻全是占位向量（1024 维），无法 cast 成 halfvec(4096)，
--    所以必须先清空再改类型——PG 不允许在有行的情况下把 vector(1024) 直接 ALTER 成 halfvec(4096)。
--    ⚠️ 用 TRUNCATE 而非 DELETE：本表 FORCE ROW LEVEL SECURITY，
--    DELETE 在没有 app.tenant_id 的会话里会被 RLS <b>静默过滤成 0 行</b>（本项目踩过），
--    留下行让 ALTER TYPE 直接报错；TRUNCATE 不受 RLS 约束（需表 owner 权限）。
TRUNCATE TABLE rag.rag_chunk_embedding;

ALTER TABLE rag.rag_chunk_embedding
    ALTER COLUMN embedding TYPE public.halfvec(4096);

-- 3) 注释随结构一起改（否则文档与列漂移）
COMMENT ON COLUMN rag.rag_chunk_embedding.embedding IS
    'qwen3-embedding:8b 稠密向量，halfvec(4096)；换模型必须全量重建（RAG-D03 §5 代际机制）';

COMMIT;

-- 之后：
--   a) 服务侧配置 RAG_EMBEDDING_BASE_URL / _MODEL / _API_KEY，
--      并设 RAG_EMBEDDING_VECTOR_TYPE=halfvec；维度不单独配，取自 EmbeddingPort.dimension()；
--   b) 用管理端 POST /api/v1/management/reindex 全量重灌向量；
--   c) 校验：SELECT embed_model, count(*) FROM rag_chunk_embedding GROUP BY 1;
--      期望只剩新模型标识，行数等于可检索片段数。
