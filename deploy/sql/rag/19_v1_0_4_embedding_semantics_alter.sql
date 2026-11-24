-- ============================================================================
-- RAG V1.0.4 · Embedding 输入模板语义槽位（RAG-D28 P3-1）
-- ============================================================================
-- 目标：rag_chunk 增加 formula_natural_language / caption_or_table_summary 两列，
--       使 D27 §6.1 输入模板的 {formulaNaturalLanguage}/{captionOrTableSummary}
--       两个槽位在首次摄取与重索引两侧都能读到同一份文本。
--
-- ⚠️ 背景（P3-1 诚实边界的收口）：
--   批 10 已在 EmbeddingInputTemplate 备好两个槽位，但切分器不产、DB 不存，
--   工厂传 null——模板语义双写形同虚设。本增量把两列落到 rag_chunk：
--     · formula_natural_language：切分器从 FormulaAnnotation.embeddingView() 取
--       （命中=受控词典自然语言+符号，未命中=仅 LaTeX，禁生成期自猜 DEC-P3）；
--     · caption_or_table_summary：表格块从 TableGrid 取确定性结构摘要（行列数+表头），
--       图片块当前无 VLM caption（P1-7 未落地）→ null，不伪造。
--   重索引扫描 SQL（ChunkIndexer.PENDING_SQL / IndexGenerationBuilder.SCAN_SQL）
--   读取这两列 → 与首次摄取逐字同构，避免「摄取有语义、重算无语义」的向量漂移。
--
-- ⚠️ 加列不改表数 / RLS 表数 / 策略数（init-db.sh 的 13 表 / 37 策略期望值不变）。
-- ⚠️ 表级授权（03/04）自动覆盖新列，无需列级 GRANT。
-- ⚠️ 两列全可空：存量片段早于本改造，NULL = 「当时没有语义槽位」，
--    重索引读取后模板该槽位留空（与改造前行为一致），不回填伪造值。
-- ⚠️ 上线顺序：先跑本文件 → 再部署应用（应用写入会带这两列）。
-- ============================================================================

ALTER TABLE rag.rag_chunk
    ADD COLUMN IF NOT EXISTS formula_natural_language text,
    ADD COLUMN IF NOT EXISTS caption_or_table_summary text;

COMMENT ON COLUMN rag.rag_chunk.formula_natural_language IS
    '公式 embedding 语义（RAG-D28 P3-1）。命中受控词典=LaTeX+自然语言+符号；未命中=仅LaTeX；NULL=非公式块或未采集。';
COMMENT ON COLUMN rag.rag_chunk.caption_or_table_summary IS
    '图注/表格摘要 embedding 文本（RAG-D28 P3-1）。表格=确定性结构摘要（行列数+表头）；图片无VLM caption时NULL。';

-- ============================================================================
-- 回滚（手动执行，不在正常迁移路径）
-- ----------------------------------------------------------------------------
-- ALTER TABLE rag.rag_chunk
--     DROP COLUMN IF EXISTS caption_or_table_summary,
--     DROP COLUMN IF EXISTS formula_natural_language;
-- ============================================================================
