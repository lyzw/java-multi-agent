-- ============================================================================
-- RAG V1.0.5 · 1024 换维双写列 embedding_v2（D29 DEC-P2 / D27 §14.7）
-- ============================================================================
-- 目标：rag_chunk_embedding 增加 embedding_v2 public.halfvec(1024) 列，
--       与既有 embedding public.halfvec(4096) 并存，支持 MRL 换维的蓝绿切流。
--
-- ⚠️ 为什么要「新增列双写」而不是原地改列（D27 §14.7 / §14.11 第 7 条）：
--    ALTER COLUMN TYPE halfvec(4096)→halfvec(1024) 会锁表 + 全表重写 + 服务中断，
--    且切流期间新旧向量空间不可比、混算余弦会静默产出错误分数。
--    正确姿势是「双列并存 + 新代际写新列 + 按 ACTIVE 代际隔离读路径 + 蓝绿切流」：
--      · 旧代际（4096）继续服务 → 读 embedding；
--      · 新代际（1024）后台构建 → 写 embedding_v2；
--      · 切流只改 rag_index_generation 的 ACTIVE 状态，读路径据代际 dimension 选列。
--
-- ⚠️ 列形态：halfvec(1024)。D29 DEC-P2 S2 指出 1024 ≤ halfvec 的 HNSW 上限 4000，
--    indexable() 由 false 变 true，但本次换维【刻意不建 HNSW】——
--    建索引是独立决策，不夹带进换维（D27 §14.11 第 8 条 / 深度报告 RAG 红线）。
--
-- ⚠️ 加列不改表数 / RLS 表数 / 策略数（init-db.sh 的 13 表 / 37 策略期望值不变）。
-- ⚠️ 表级授权（03/04）自动覆盖新列，无需列级 GRANT。
-- ⚠️ 新列全可空：存量向量行早于本改造，embedding_v2=NULL = 「该行属 4096 代际」，
--    不回填、不伪造；只有 1024 代际构建出的行才写本列。
-- ⚠️ 上线顺序：先跑本文件 → 再部署应用（代际构建器按代际写新列）。
-- ============================================================================

ALTER TABLE rag.rag_chunk_embedding
    ADD COLUMN IF NOT EXISTS embedding_v2 public.halfvec(1024);

COMMENT ON COLUMN rag.rag_chunk_embedding.embedding_v2 IS
    'MRL 截断到 1024 维的向量（D29 DEC-P2）。与 embedding halfvec(4096) 并存；NULL=4096 代际行。读路径按 rag_index_generation.dimension 选列。';

-- ============================================================================
-- 回滚（手动执行，不在正常迁移路径）
-- ----------------------------------------------------------------------------
-- ALTER TABLE rag.rag_chunk_embedding DROP COLUMN IF EXISTS embedding_v2;
-- ============================================================================
