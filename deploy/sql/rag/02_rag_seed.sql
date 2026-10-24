-- =====================================================================
-- rag 种子数据 —— 生产环境初始化用
-- =====================================================================
--
-- 【⚠️ RAG 侧没有租户表，这是架构决策，不是缺漏】
--   RAG 迁移 V4（rag_drop_tenant_copy）删除了 rag_tenant 表：
--   租户信息的**权威源始终是 platform**，RAG 只消费 TenantContext，
--   不自建认证、不存租户副本（见 RAG-D00 §4）。
--   故本库**无需**建租户行；rag_* 表的 tenant_id 列是
--   「platform 权威租户 ID 的**引用值**」，无外键、无副本、RAG 不校验其存在性。
--   → 因此本种子脚本**必须**由外部传入 platform 侧的真实 tenant_id，
--     否则灌进去的数据落在**任何租户都取不到**的命名空间里
--     （RLS 策略 tenant_id = rag_current_tenant()，取不到 = 检索永远为空）。
--
-- 【内容性质：演示语料】
--   ⚠️ 下列标题、ISBN、正文**全部是编造的占位内容**，不是真实教材。
--      用途是让「语料维护台」在空库上立刻能看到各状态并走通全流程。
--      生产正式语料由对象存储 + 解析/入库流程灌入，不走本脚本。
--
-- 【覆盖状态】
--   来源 VERIFIED / PENDING / REVOKED；
--   载体 VERIFIED / IN_REVIEW / PENDING / REVOKED / 解析失败(FAILED)；
--   片段「有向量 / 缺向量」两种（缺向量由「重跑索引」补齐）。
--
-- 【幂等】
--   全部 ON CONFLICT DO NOTHING，可反复执行。
--
-- 【⚠️ search_path】
--   应用连接已设 `SET search_path TO rag, public`（HikariCP connection-init-sql）。
--   手工执行时本脚本自带 SET search_path，无需额外处理。
--
-- 【⚠️ 执行身份】
--   应用账号 rag_app 会被 RLS 挡掉（这是**预期的隔离行为**，不是缺陷）。
--   本脚本须用能跨租户绕过 RLS 的账号执行（如 RDS 的 ai_agent / 迁移角色）。
--
-- 【用法】
--   psql -h <host> -p <port> -U <迁移角色> -d study_rag -v ON_ERROR_STOP=1 \
--        -v tenant_id='<platform.tenant.tenant_id 的 UUID>' \
--        -f 02_rag_seed.sql
--
--   tenant_id 必须与 platform 库 tenant 表中目标租户的 tenant_id **完全一致**。
-- =====================================================================

\set ON_ERROR_STOP on

-- ── 必填参数：platform 权威租户 ID ──────────────────────────────────
-- ⚠️ 刻意**不给默认值**：默认值等于「让操作者以为灌对了租户」。
--   漏传时的症状是「检索永远返回空」——报错点在业务代码，
--   离「tenant_id 填错/没填」这个真因极远。
\if :{?tenant_id}
\else
  \echo '❌ 必须用 -v tenant_id=<platform 权威租户 UUID> 指定目标租户'
  \quit
\endif

\echo '── rag 种子参数 ──'
\echo '  目标租户 tenant_id :' :tenant_id

BEGIN;
SET search_path TO rag, public;

-- ---------------------------------------------------------------------
-- ① 来源（作品层）：三种校验状态各一
-- ---------------------------------------------------------------------
-- S1：已校验来源 —— 下面 D1/D2/D3 的合法入库路径都挂在它下面。
INSERT INTO rag.rag_source
    (source_id, tenant_id, source_type, title, publisher, isbn,
     trust_tier, ver_status, ver_method, ver_evidence, verified_at, ver_valid_days,
     license_status, content_hash, created_at, updated_at)
VALUES
    ('9a1c0000-0000-4000-8000-000000000001', :'tenant_id', 'TEXTBOOK',
     '演示教材·八年级数学上册（人教版）', '人教版', '978-7-107-88888-1',
     'OFFICIAL_PUBLISHER', 'VERIFIED', 'ISBN', '演示用 ISBN 校验记录',
     now(), 365, 'LICENSED', md5('demo-s1') || md5('demo-s1'), now(), now())
ON CONFLICT (source_id) DO NOTHING;

-- S2：待审核来源 —— 其载体永远进不了索引（fail-closed 的活样本）。
INSERT INTO rag.rag_source
    (source_id, tenant_id, source_type, title, publisher,
     trust_tier, ver_status, ver_method, ver_valid_days, license_status,
     content_hash, created_at, updated_at)
VALUES
    ('9a1c0000-0000-4000-8000-000000000002', :'tenant_id', 'TEXTBOOK',
     '演示教材·九年级数学下册（苏教版·待审核）', '苏教版',
     'UNVERIFIED', 'PENDING', 'NONE', 365, 'PENDING',
     md5('demo-s2') || md5('demo-s2'), now(), now())
ON CONFLICT (source_id) DO NOTHING;

-- S3：已撤回来源 —— 来源级撤回才是真正阻断检索的那一层。
INSERT INTO rag.rag_source
    (source_id, tenant_id, source_type, title, publisher,
     trust_tier, ver_status, ver_method, ver_valid_days, license_status,
     ver_reject_reason, ver_decided_at, content_hash, created_at, updated_at)
VALUES
    ('9a1c0000-0000-4000-8000-000000000003', :'tenant_id', 'TEXTBOOK',
     '演示教材·七年级数学上册（来源已撤回）', '人教版',
     'UNVERIFIED', 'REVOKED', 'MANUAL_REVIEW', 365, 'RESTRICTED',
     '版权方撤回，停止对外提供', now(), md5('demo-s3') || md5('demo-s3'), now(), now())
ON CONFLICT (source_id) DO NOTHING;

-- ---------------------------------------------------------------------
-- ② 版本坐标（edition）：来源 → 版本五轴
-- ---------------------------------------------------------------------
INSERT INTO rag.rag_edition
    (edition_id, tenant_id, source_id, subject, grade, term, publisher, revision_code,
     curriculum_ref, effective_from, created_at)
VALUES
    ('9a1c0000-0000-4000-9000-000000000001', :'tenant_id',
     '9a1c0000-0000-4000-8000-000000000001', '数学', '八年级', '上册', '人教版', '2024',
     '义务教育教科书·数学·八年级上册', '2024-08-01', now()),
    ('9a1c0000-0000-4000-9000-000000000002', :'tenant_id',
     '9a1c0000-0000-4000-8000-000000000002', '数学', '九年级', '下册', '苏教版', '2024',
     '义务教育教科书·数学·九年级下册', '2024-08-01', now()),
    ('9a1c0000-0000-4000-9000-000000000003', :'tenant_id',
     '9a1c0000-0000-4000-8000-000000000003', '数学', '七年级', '上册', '人教版', '2023',
     '义务教育教科书·数学·七年级上册', '2023-08-01', now())
ON CONFLICT (edition_id) DO NOTHING;

-- ---------------------------------------------------------------------
-- ③ 载体（document）：覆盖五种审核状态 + 解析失败
--    ⚠️ 全部先不给向量，「重跑索引」端点会把 VERIFIED 来源下的补上。
-- ---------------------------------------------------------------------
INSERT INTO rag.rag_document
    (document_id, tenant_id, source_id, edition_id, carrier, title, page_count,
     object_ref, parse_status, parser_version, content_hash, created_at, updated_at,
     ver_status, retrievable, license_status, revoked_at, revoked_reason)
VALUES
    -- D1：官方电子版，已校验、可检索 —— 召回测试的主力样本
    ('9a1c0000-0000-4000-a000-000000000001', :'tenant_id',
     '9a1c0000-0000-4000-8000-000000000001', '9a1c0000-0000-4000-9000-000000000001',
     'EPUB', '八年级上册·第一章 官方电子版', 42, 's3://demo/8a-1.epub',
     'CHUNKED', 'demo-parser-1', md5('demo-d1') || md5('demo-d1'), now(), now(),
     'VERIFIED', true, 'LICENSED', NULL, NULL),
    -- D2：扫描版，审核中 —— 只能推进到 IN_REVIEW，不能直接 VERIFIED
    ('9a1c0000-0000-4000-a000-000000000002', :'tenant_id',
     '9a1c0000-0000-4000-8000-000000000001', '9a1c0000-0000-4000-9000-000000000001',
     'SCAN', '八年级上册·第一章 扫描版', 42, 's3://demo/8a-1-scan.pdf',
     'CHUNKED', 'demo-parser-1', md5('demo-d2') || md5('demo-d2'), now(), now(),
     'IN_REVIEW', false, 'PENDING', NULL, NULL),
    -- D3：已校验但缺向量 —— 「重跑索引」要修的就是它
    ('9a1c0000-0000-4000-a000-000000000003', :'tenant_id',
     '9a1c0000-0000-4000-8000-000000000001', '9a1c0000-0000-4000-9000-000000000001',
     'PDF', '八年级上册·第二章 官方电子版（缺向量待补）', 38, 's3://demo/8a-2.pdf',
     'CHUNKED', 'demo-parser-1', md5('demo-d3') || md5('demo-d3'), now(), now(),
     'VERIFIED', true, 'LICENSED', NULL, NULL),
    -- D4：解析失败 —— 解析器报错的载体
    ('9a1c0000-0000-4000-a000-000000000004', :'tenant_id',
     '9a1c0000-0000-4000-8000-000000000001', '9a1c0000-0000-4000-9000-000000000001',
     'PDF', '八年级上册·附录 解析失败', 6, 's3://demo/8a-appendix.pdf',
     'FAILED', 'demo-parser-1', md5('demo-d4') || md5('demo-d4'), now(), now(),
     'PENDING', false, 'PENDING', NULL, NULL),
    -- D5：刚入库、待审核、无片段
    ('9a1c0000-0000-4000-a000-000000000005', :'tenant_id',
     '9a1c0000-0000-4000-8000-000000000001', '9a1c0000-0000-4000-9000-000000000001',
     'PDF', '八年级上册·封面 刚入库待审', 2, 's3://demo/8a-cover.pdf',
     'PENDING', NULL, md5('demo-d5') || md5('demo-d5'), now(), now(),
     'PENDING', false, 'PENDING', NULL, NULL),
    -- D6：来源待审 → 载体永远进不了索引
    ('9a1c0000-0000-4000-a000-000000000006', :'tenant_id',
     '9a1c0000-0000-4000-8000-000000000002', '9a1c0000-0000-4000-9000-000000000002',
     'EPUB', '九年级下册·第一章 官方电子版（来源待审）', 40, 's3://demo/9b-1.epub',
     'CHUNKED', 'demo-parser-1', md5('demo-d6') || md5('demo-d6'), now(), now(),
     'PENDING', false, 'PENDING', NULL, NULL),
    -- D7：来源已撤回 → 载体级撤回
    -- ⚠️ ver_status='REVOKED' 时 revoked_reason 必须非空（表级 CHECK 约束
    --    rag_document_revoked_reason_chk），故与状态同行写入，不后补。
    ('9a1c0000-0000-4000-a000-000000000007', :'tenant_id',
     '9a1c0000-0000-4000-8000-000000000003', '9a1c0000-0000-4000-9000-000000000003',
     'EPUB', '七年级上册·第一章 官方电子版（已撤回）', 36, 's3://demo/7a-1.epub',
     'CHUNKED', 'demo-parser-1', md5('demo-d7') || md5('demo-d7'), now(), now(),
     'REVOKED', false, 'RESTRICTED', now(),
     '演示：载体级撤回（仅置 retrievable=false，检索期未按该字段过滤）')
ON CONFLICT (document_id) DO NOTHING;

-- ---------------------------------------------------------------------
-- ④ 片段（chunk）：D1/D3 会被重跑索引补向量；D6/D7 因来源未 VERIFIED 永远补不上
--    ⚠️ text_jieba 留空：由重跑索引的切词器填，保证与检索期词法通道同源。
-- ---------------------------------------------------------------------
INSERT INTO rag.rag_chunk
    (chunk_id, tenant_id, document_id, edition_id, role, text, page_number,
     section_path, exercise_no, char_start, char_end, kp_ids, content_hash, created_at)
VALUES
    -- D1 的三个片段
    ('9a1c0000-0000-4000-b000-000000000001', :'tenant_id',
     '9a1c0000-0000-4000-a000-000000000001', '9a1c0000-0000-4000-9000-000000000001',
     'EXPLANATION', '一元一次方程的概念：只含一个未知数，并且未知数的次数是 1 的整式方程。',
     12, '第一章/1.1 一元一次方程', '例1', 0, 41,
     '{}'::uuid[], md5('demo-c1') || md5('demo-c1'), now()),
    ('9a1c0000-0000-4000-b000-000000000002', :'tenant_id',
     '9a1c0000-0000-4000-a000-000000000001', '9a1c0000-0000-4000-9000-000000000001',
     'EXERCISE', '练习：解下列一元一次方程 3x + 5 = 20，写出解方程的三个步骤。',
     13, '第一章/1.1 一元一次方程', '练习1', 0, 38,
     '{}'::uuid[], md5('demo-c2') || md5('demo-c2'), now()),
    ('9a1c0000-0000-4000-b000-000000000003', :'tenant_id',
     '9a1c0000-0000-4000-a000-000000000001', '9a1c0000-0000-4000-9000-000000000001',
     'PAPER_BODY', '第一章 一元一次方程：本章学习用字母表示数，列出一元一次方程并求解。',
     11, '第一章/章前语', NULL, 0, 35,
     '{}'::uuid[], md5('demo-c3') || md5('demo-c3'), now()),
    -- D2 的片段（载体审核中）
    ('9a1c0000-0000-4000-b000-000000000004', :'tenant_id',
     '9a1c0000-0000-4000-a000-000000000002', '9a1c0000-0000-4000-9000-000000000001',
     'EXPLANATION', '扫描版说明：等式两边同时加上同一个数，等式仍然成立。',
     12, '第一章/1.2 等式的性质', '例2', 0, 28,
     '{}'::uuid[], md5('demo-c4') || md5('demo-c4'), now()),
    -- D3 的两个片段（缺向量，重跑索引的目标）
    ('9a1c0000-0000-4000-b000-000000000005', :'tenant_id',
     '9a1c0000-0000-4000-a000-000000000003', '9a1c0000-0000-4000-9000-000000000001',
     'EXPLANATION', '去括号法则：括号前是负号时，去掉括号后各项都要变号。',
     20, '第二章/2.1 去括号', '例3', 0, 30,
     '{}'::uuid[], md5('demo-c5') || md5('demo-c5'), now()),
    ('9a1c0000-0000-4000-b000-000000000006', :'tenant_id',
     '9a1c0000-0000-4000-a000-000000000003', '9a1c0000-0000-4000-9000-000000000001',
     'EXERCISE', '练习：解方程 2(x - 3) = 10，注意去括号时变号。',
     21, '第二章/2.1 去括号', '练习2', 0, 29,
     '{}'::uuid[], md5('demo-c6') || md5('demo-c6'), now()),
    -- D6 的片段（来源待审 → 永不入索引）
    ('9a1c0000-0000-4000-b000-000000000007', :'tenant_id',
     '9a1c0000-0000-4000-a000-000000000006', '9a1c0000-0000-4000-9000-000000000002',
     'EXPLANATION', '二次函数的图像是抛物线，顶点坐标决定开口方向与对称轴。',
     15, '第二章/2.2 二次函数', '例4', 0, 31,
     '{}'::uuid[], md5('demo-c7') || md5('demo-c7'), now()),
    -- D7 的片段（来源已撤回 → 检索必须排除）
    ('9a1c0000-0000-4000-b000-000000000008', :'tenant_id',
     '9a1c0000-0000-4000-a000-000000000007', '9a1c0000-0000-4000-9000-000000000003',
     'EXPLANATION', '有理数的乘方：负数的奇次幂是负数，偶次幂是正数。',
     10, '第一章/1.3 有理数的乘方', '例5', 0, 28,
     '{}'::uuid[], md5('demo-c8') || md5('demo-c8'), now())
ON CONFLICT (chunk_id) DO NOTHING;

COMMIT;

-- 自检：打印各状态计数，便于确认种子生效。
SET search_path TO rag, public;
SELECT ver_status, count(*) AS sources FROM rag.rag_source
 WHERE tenant_id = :'tenant_id' GROUP BY 1 ORDER BY 1;
SELECT ver_status, parse_status, count(*) AS documents FROM rag.rag_document
 WHERE tenant_id = :'tenant_id' GROUP BY 1,2 ORDER BY 1,2;
SELECT count(*) AS chunks,
       count(*) FILTER (WHERE text_jieba = '') AS without_jieba,
       (SELECT count(*) FROM rag.rag_chunk_embedding e
         WHERE e.tenant_id = :'tenant_id') AS embeddings
  FROM rag.rag_chunk WHERE tenant_id = :'tenant_id';

-- =====================================================================
-- 事后回查校验
-- =====================================================================
-- ⚠️ 必须回查：仅凭 psql 的输出判断成败是不够的，
--    psql 的报错可能被上层吞掉而流程仍走到成功分支。
-- ⚠️ **实测结论**：psql 的变量替换**不发生在美元引号（$$）字符串内部**，
--    故 DO 块里写 :'tenant_id' 不会被替换成字面量。
--    惯用解法：先把值放进会话 GUC，再在块内 current_setting() 取回。
SELECT set_config('seed.tenant_id', :'tenant_id', false);

DO $$
DECLARE
    v_src  int;
    v_doc  int;
    v_chunk int;
BEGIN
    SELECT count(*) INTO v_src
      FROM rag.rag_source WHERE tenant_id = current_setting('seed.tenant_id')::uuid;
    SELECT count(*) INTO v_doc
      FROM rag.rag_document WHERE tenant_id = current_setting('seed.tenant_id')::uuid;
    SELECT count(*) INTO v_chunk
      FROM rag.rag_chunk WHERE tenant_id = current_setting('seed.tenant_id')::uuid;

    IF v_src = 0 THEN
        RAISE EXCEPTION 'RAG 种子失败：tenant_id=% 下没有任何来源数据',
                        current_setting('seed.tenant_id');
    END IF;

    RAISE NOTICE '✅ rag 种子校验通过：来源=%，载体=%，片段=%', v_src, v_doc, v_chunk;
END;
$$;
