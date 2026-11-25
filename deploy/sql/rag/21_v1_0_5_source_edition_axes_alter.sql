-- ============================================================================
-- RAG V1.0.5 · 来源自身保存版本五轴（D28 G1-b 缺口修复）
-- ============================================================================
-- 目标：修复「第二个同五轴来源登记后上传载体退化为默认版本坐标」的缺口。
--
-- 根因：rag_edition_unique = (tenant_id, subject, grade, term, publisher,
--       revision_code)，不含 source_id。来源 A 建好真实五轴版本后，来源 B
--       用同一五轴登记被 ON CONFLICT DO NOTHING 吞掉；上传时 resolveEdition()
--       只按 rag_edition.source_id=当前来源 找，找不到就建
--       src-<sourceId>/未指定/全学段 默认坐标，STRICT 五轴检索必然 0 候选。
--
-- 方案：把「该来源属于哪个版本」显式存到 rag_source 的五个可空列（归一后的
--       值，与 rag_edition 的写入口径完全一致）。登记时写入，解析时按来源
--       五轴 → rag_edition 自然键查共享版本；旧来源（无五轴）仍按
--       rag_edition.source_id 回落，保持存量行为。
--
-- ⚠️ 为什么不改 rag_edition.source_id（D16 §3.4 明令禁止）：那个字段记录
--    「谁首次创建该坐标」，改它会让已有载体的版本归属漂移；本方案不动它。
-- ⚠️ 为什么不加「来源×版本」关联表：自然键版本是共享的，来源只需保存自己
--    的版本坐标即可解析，无需新增结构/RLS 策略/策略基线。
-- ⚠️ 五列全可空且追加在表尾：02 种子 INSERT 不带列名，新列有 NULL 默认值，
--    存量 INSERT 不会错位；NULL = 旧来源未登记五轴（走 source_id 回落）。
-- ⚠️ 加列不改表数 / RLS 表数 / 策略数（init-db.sh 的 13 表 / 37 策略期望不变）。
-- ⚠️ 表级授权（03/04）自动覆盖新列，无需列级 GRANT。
-- ============================================================================

ALTER TABLE rag.rag_source
    ADD COLUMN IF NOT EXISTS edition_subject text,
    ADD COLUMN IF NOT EXISTS edition_grade text,
    ADD COLUMN IF NOT EXISTS edition_term text,
    ADD COLUMN IF NOT EXISTS edition_publisher text,
    ADD COLUMN IF NOT EXISTS edition_revision_code text;

COMMENT ON COLUMN rag.rag_source.edition_subject IS
    '归一后的版本五轴·学科；与 rag_edition.subject 同口径。NULL=存量来源未登记五轴。';
COMMENT ON COLUMN rag.rag_source.edition_grade IS
    '归一后的版本五轴·年级；与 rag_edition.grade 同口径。';
COMMENT ON COLUMN rag.rag_source.edition_term IS
    '版本五轴·册次；与 rag_edition.term 同口径。';
COMMENT ON COLUMN rag.rag_source.edition_publisher IS
    '归一后的版本五轴·出版社（版本体系名，如「沪教版」）；与 rag_edition.publisher 同口径。';
COMMENT ON COLUMN rag.rag_source.edition_revision_code IS
    '版本五轴·版次码；与 rag_edition.revision_code 同口径。';

-- 回填优先级：① 该来源已上传载体实际挂到的非默认版本；② 该来源名下的非默认
-- 版本；③ 该来源名下的任意版本（含历史默认坐标，保持旧行为）。
-- 第 ① 条覆盖 D08 G1-b 实测样本：来源登记时共享了别人的坐标，之后人工把
-- 载体/片段改挂到正确版本，本回填让库与后续应用解析口径一致。
UPDATE rag.rag_source s
SET edition_subject = e.subject,
    edition_grade = e.grade,
    edition_term = e.term,
    edition_publisher = e.publisher,
    edition_revision_code = e.revision_code
FROM (
    SELECT DISTINCT ON (tenant_id, source_id) tenant_id, source_id, edition_id
    FROM rag.rag_document
    ORDER BY tenant_id, source_id, created_at DESC, document_id
) d
JOIN rag.rag_edition e
  ON e.tenant_id = d.tenant_id AND e.edition_id = d.edition_id
WHERE s.tenant_id = d.tenant_id AND s.source_id = d.source_id
  AND e.revision_code NOT LIKE 'src-%'
  AND s.edition_subject IS NULL;

UPDATE rag.rag_source s
SET edition_subject = e.subject,
    edition_grade = e.grade,
    edition_term = e.term,
    edition_publisher = e.publisher,
    edition_revision_code = e.revision_code
FROM rag.rag_edition e
WHERE e.tenant_id = s.tenant_id AND e.source_id = s.source_id
  AND e.revision_code NOT LIKE 'src-%'
  AND s.edition_subject IS NULL;

UPDATE rag.rag_source s
SET edition_subject = e.subject,
    edition_grade = e.grade,
    edition_term = e.term,
    edition_publisher = e.publisher,
    edition_revision_code = e.revision_code
FROM rag.rag_edition e
WHERE e.tenant_id = s.tenant_id AND e.source_id = s.source_id
  AND s.edition_subject IS NULL;

-- ============================================================================
-- 回滚（手动执行，不在正常迁移路径）
-- ----------------------------------------------------------------------------
-- ALTER TABLE rag.rag_source DROP COLUMN IF EXISTS edition_revision_code;
-- ALTER TABLE rag.rag_source DROP COLUMN IF EXISTS edition_publisher;
-- ALTER TABLE rag.rag_source DROP COLUMN IF EXISTS edition_term;
-- ALTER TABLE rag.rag_source DROP COLUMN IF EXISTS edition_grade;
-- ALTER TABLE rag.rag_source DROP COLUMN IF EXISTS edition_subject;
-- ============================================================================
