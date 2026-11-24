-- ============================================================================
-- RAG V1.0.4 · 父子块持久化（RAG-D28 P2-1 / 批 9 摄取链接线）
-- ============================================================================
-- 目标：rag_chunk 增加 parent_chunk_id / chunk_level 两列，使 ParentChildAssembler
--       产出的父子结构能落库，检索侧 RetrievalPolicy.expandParents 才能按父块 ID
--       回带上下文。加列前父子块在落库环节被丢弃、检索读回恒为顶层块——批 8 的
--       P2-1 能力生产不可达（项目铁律 #15）。
--
-- ⚠️ 加列不改表数 / RLS 表数 / 策略数（init-db.sh 的 13 表 / 37 策略期望值不变）。
-- ⚠️ 表级授权（03/04）自动覆盖新列，无需列级 GRANT。
-- ⚠️ parent_chunk_id 是可空外键语义，但**不加物理 FK 约束**：父块与子块同批落库，
--    一次事务内先后无序；且历史数据/跨册软删场景下 FK 会阻碍运维。应用层保证
--    子块的 parent_chunk_id 指向同文档内的父块（ParentChildAssembler 的契约）。
-- ============================================================================

ALTER TABLE rag.rag_chunk
    ADD COLUMN IF NOT EXISTS parent_chunk_id uuid,
    ADD COLUMN IF NOT EXISTS chunk_level integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN rag.rag_chunk.parent_chunk_id IS
    '父块 ID（RAG-D28 P2-1）。NULL=顶层块；非空=本块是子块，检索命中时回带父块送 LLM。';
COMMENT ON COLUMN rag.rag_chunk.chunk_level IS
    '层级：0=父块（上下文聚合）/顶层自然块，1=子块（精确召回、算 embedding）。';

-- 约束：层级只能 0/1；子块必须有父块 ID（与 Chunk 记录构造器同口径）。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'rag_chunk_level_chk'
    ) THEN
        ALTER TABLE rag.rag_chunk ADD CONSTRAINT rag_chunk_level_chk
            CHECK (chunk_level IN (0, 1));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'rag_chunk_child_parent_chk'
    ) THEN
        ALTER TABLE rag.rag_chunk ADD CONSTRAINT rag_chunk_child_parent_chk
            CHECK (chunk_level = 0 OR parent_chunk_id IS NOT NULL);
    END IF;
END $$;

-- 回带查询按父块 ID 批量取回（expandParents → loadByIds 的父块来源）。
CREATE INDEX IF NOT EXISTS rag_chunk_parent_idx
    ON rag.rag_chunk USING btree (tenant_id, parent_chunk_id)
    WHERE parent_chunk_id IS NOT NULL;

-- ============================================================================
-- 回滚（手动执行，不在正常迁移路径）
-- ----------------------------------------------------------------------------
-- DROP INDEX IF EXISTS rag.rag_chunk_parent_idx;
-- ALTER TABLE rag.rag_chunk
--     DROP CONSTRAINT IF EXISTS rag_chunk_child_parent_chk,
--     DROP CONSTRAINT IF EXISTS rag_chunk_level_chk,
--     DROP COLUMN IF EXISTS chunk_level,
--     DROP COLUMN IF EXISTS parent_chunk_id;
-- ============================================================================
