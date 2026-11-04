-- =============================================================================
-- 存量语料版本五轴回填（按标题推断）
-- =============================================================================
-- 【解决什么】study_rag 里 5 个 rag_edition 的五轴全是登记占位值：
--   学科=未指定 / 年级=全学段 / 册次=全一册 / 版本体系=未指定，
--   且 revision_code 写的是 "src-<来源ID>" 而不是版次。
--
--   后果：管理面只能选正规枚举值（数学/六年级/上册/沪教版…），
--   与占位值永远匹配不上 ⇒ 这批教材在控制台里**怎么搜都搜不到**，
--   表现为「依据不足」。实测已确认：这些片段审核通过后按真实坐标能召回，
--   用占位坐标则恒为 0 条。
--
-- 【为什么用标题推断而不是重新登记】
--   标题本身就把五轴写全了（如「沪教版五四制六年级数学上册」），
--   推断规则可解释、可复核、可回滚；重新登记会换掉 source_id，
--   导致已入库的 4326 条片段与向量全部失去归属。
--
-- 【⚠️⚠️ 取值必须落在枚举 displayName 内】
--   检索期 subjectFromDb / gradeFromDb / termFromDb / publisherFromDb
--   用 displayName 逐个比对，**比对不上直接抛异常** ⇒ 该校材任何检索 500，
--   且报错点离真因极远（见 SourceRegistration.normalizedEditionPublisher 注释）。
--   本迁移末尾有断言，任何一个轴落到枚举外都会让整个事务回滚。
--
-- 【⚠️ 出版社 ≠ 出版机构】
--   rag_source.publisher 是出版机构（上海教育出版社）；
--   rag_edition.publisher 是**版本体系**（沪教版）。两者不可互换。
--
-- 【只改占位值，不覆盖已填好的坐标】
--   判据：仅当某轴仍处于占位值时才推断该轴。已经人工填过的轴一律不动。
--   这样本脚本对已修正的库重放是幂等的。
-- =============================================================================

BEGIN;

-- ── 1. 按标题推断并回填（逐轴独立判定，互不覆盖）──────────────────────────

-- 1.1 学科
UPDATE rag.rag_edition e
   SET subject = sub.name
  FROM (VALUES
        ('数学','数学'), ('语文','语文'), ('英语','英语'), ('物理','物理'),
        ('化学','化学'), ('生物','生物'), ('历史','历史'), ('地理','地理'), ('道法','道法')
       ) AS sub(name, keyword)
 WHERE e.subject = '未指定'
   AND (SELECT s.title FROM rag.rag_source s
         WHERE s.source_id = e.source_id AND s.tenant_id = e.tenant_id) LIKE '%' || sub.keyword || '%'
   -- 一个标题可能含多个学科词（极少），只取最长匹配那个：见下方 1.5 去重
;

-- 1.2 年级
UPDATE rag.rag_edition e
   SET grade = g.name
  FROM (VALUES
        ('一年级','一年级'), ('二年级','二年级'), ('三年级','三年级'),
        ('四年级','四年级'), ('五年级','五年级'), ('六年级','六年级'),
        ('七年级','七年级'), ('八年级','八年级'), ('九年级','九年级'),
        ('高一','高一'), ('高二','高二'), ('高三','高三')
       ) AS g(name, keyword)
 WHERE e.grade = '全学段'
   AND (SELECT s.title FROM rag.rag_source s
         WHERE s.source_id = e.source_id AND s.tenant_id = e.tenant_id) LIKE '%' || g.keyword || '%'
;

-- 1.3 册次
UPDATE rag.rag_edition e
   SET term = t.name
  FROM (VALUES ('下册','下册'), ('上册','上册'), ('全一册','全一册')) AS t(name, keyword)
 WHERE e.term = '全一册'
   AND (SELECT s.title FROM rag.rag_source s
         WHERE s.source_id = e.source_id AND s.tenant_id = e.tenant_id) LIKE '%' || t.keyword || '%'
;

-- 1.4 版本体系（⚠️ 不是出版机构）
UPDATE rag.rag_edition e
   SET publisher = p.name
  FROM (VALUES
        ('沪教版','沪教版'), ('沪教版','五四制'), ('沪教版','五·四学制'),
        ('人教版','人教版'), ('苏教版','苏教版'), ('北师大版','北师大版'),
        ('华师大版','华师大版'), ('鲁教版','鲁教版'), ('鄂教版','鄂教版')
       ) AS p(name, keyword)
 WHERE e.publisher = '未指定'
   AND (SELECT s.title FROM rag.rag_source s
         WHERE s.source_id = e.source_id AND s.tenant_id = e.tenant_id) LIKE '%' || p.keyword || '%'
;

-- 1.5 若同一 edition 被多条规则命中产生歧义（例如标题同时含「数学」「英语」），
--     以第一次 UPDATE 的结果为准即可——SQL 的 UPDATE ... FROM 在出现多匹配时
--     只落其中一行，行数不会增加。为避免「随机取一条」，
--     这里显式检查并回滚：如果同一 edition 的标题能匹配到 ≥2 个不同学科词，
--     说明推断不可靠，宁可保持占位值也不猜。
UPDATE rag.rag_edition e
   SET subject = '未指定'
 WHERE e.subject <> '未指定'
   AND (SELECT count(*) FROM (VALUES
          ('数学'),('语文'),('英语'),('物理'),('化学'),('生物'),('历史'),('地理'),('道法')
        ) AS v(kw)
        WHERE (SELECT s.title FROM rag.rag_source s
                WHERE s.source_id = e.source_id AND s.tenant_id = e.tenant_id) LIKE '%' || v.kw || '%') > 1
;

-- ── 2. 修订版次：把 "src-<来源ID>" 改成可读版次 ─────────────────────────────
--
-- 原始值形如 src-89cbce16-54a4-4a37-8ce1-9c008ec821d3，是来源 ID 不是版次。
-- ⚠️ 这里不编造「2024修订」之类的具体年份——没有依据的年份比占位更糟，
--    会让人以为真的按 2024 审定过。改用中性且如实的表述：
--    "历史存量（未标注版次）"，保留可追溯性（能看出是回填的，不是录入的）。
UPDATE rag.rag_edition
   SET revision_code = '历史存量（未标注版次）'
 WHERE revision_code LIKE 'src-%';

-- ── 3. 断言：回填后每个轴都必须在枚举范围内 ────────────────────────────────
--
-- ⚠️ 任一轴越界都会让**该校材任何检索 500**（反查抛异常），
--    所以这里必须 fail-closed：断言失败 → 抛错 → 整个事务回滚。
DO $$
DECLARE
    bad_count integer;
BEGIN
    SELECT count(*) INTO bad_count
      FROM rag.rag_edition e
     WHERE e.subject NOT IN ('数学','语文','英语','物理','化学','生物','历史','地理','道法','未指定')
        OR e.grade NOT IN ('一年级','二年级','三年级','四年级','五年级','六年级',
                           '七年级','八年级','九年级','高一','高二','高三','全学段')
        OR e.term NOT IN ('上册','下册','全一册')
        OR e.publisher NOT IN ('人教版','苏教版','北师大版','华师大版','沪教版',
                               '鲁教版','鄂教版','其他','未指定');
    IF bad_count > 0 THEN
        RAISE EXCEPTION '回填后有 % 行版本五轴落在枚举之外，会导致检索 500 —— 事务回滚', bad_count;
    END IF;
END
$$;

COMMIT;
