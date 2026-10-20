-- =====================================================================
-- V1 迁移：RAG 语料与检索基线（RAG-D03）
--
-- 技术基线：PostgreSQL 17 + pgvector（与主平台 platform/ 同库不同 schema）
-- 目标：教材 / 辅导资料 / 学术论文 的版本化语料、知识点本体、
--       片段与向量索引、引用溯源链路。
--
-- ⚠️ 关键设计约束（来自 RAG-D00 决策与 RAG-D03 §2）：
--   1. 向量与元数据同库同事务 —— 片段的「版本/角色/权威等级」与它的
--      向量必须原子更新，否则会出现「已下架内容的向量仍可召回」。
--   2. 租户隔离沿用主平台纪律：显式 tenant_id + RLS + FORCE ROW LEVEL
--      SECURITY，且所有子资源通过复合外键绑定父资源的 tenant_id。
--   3. 版本坐标是**一等公民**：subject/grade/term/publisher/revision
--      五轴齐全才允许入库，缺一轴即无法验证引用。
--   4. 引用溯源链路可审计：answer_citation 记录了「哪次回答、
--      引用了哪个片段的哪个字符区间、逐字引文是什么」。
--   5. 语料正文只在对象存储留存，库内只存引用 + 校验和，
--      避免教学资料全文落库带来的合规与容量风险。
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------
-- 0. 独立 schema
-- ---------------------------------------------------------------------
-- ⚠️ 为什么 RAG 必须用独立 schema 而非 public：
--    本系统与主平台 platform/ **共享同一个 PostgreSQL 实例**。
--    主平台的所有表在 public，若 RAG 也放 public，则：
--      1. 表名冲突风险（rag_tenant 与 tenant 语义相近却不同源）；      2. 迁移脚本的 search_path 互相影响；      3. 两套系统的权限边界无法分开管理。
--    独立 schema 使「谁有权动哪些表」在数据库层可分离。
CREATE SCHEMA IF NOT EXISTS rag;

-- ⚠️ search_path 固定到 rag：保证本脚本创建的对象全部落在 rag 下，
--    不受调用方 search_path 影响（Flyway 在不同环境下行为可能不同）。
SET search_path TO rag, public;

-- ---------------------------------------------------------------------
-- 1. 枚举类型
-- ---------------------------------------------------------------------

-- 来源权威等级（与 rag-core TrustTier 一一对应）
CREATE TYPE rag_trust_tier AS ENUM (
    'OFFICIAL_PUBLISHER',   -- 教材出版社官方出版
    'OFFICIAL_TUTORIAL',    -- 教辅品牌正式出版
    'PEER_REVIEWED_PAPER',  -- 同行评审论文
    'PREPRINT',             -- 预印本/白皮书
    'UNVERIFIED'            -- 未校验（默认，不可被检索到）
);

-- 来源校验状态（fail-closed：非 VERIFIED 不可进入索引）
CREATE TYPE rag_verification_status AS ENUM (
    'PENDING', 'IN_REVIEW', 'VERIFIED', 'REJECTED', 'REVOKED'
);

CREATE TYPE rag_verification_method AS ENUM (
    'NONE', 'ISBN', 'DOI', 'MANUAL_REVIEW', 'OFFICIAL_HASH'
);

-- 片段角色（与 rag-core ChunkRole 一一对应）
CREATE TYPE rag_chunk_role AS ENUM (
    'EXPLANATION', 'EXAMPLE', 'EXAMPLE_SOLUTION',
    'EXERCISE', 'EXERCISE_ANSWER', 'EXERCISE_HINT',
    'PAPER_BODY', 'PAPER_METHOD', 'APPENDIX', 'BOILERPLATE'
);

-- 检索模式
CREATE TYPE rag_retrieval_mode AS ENUM (
    'EXPLAIN', 'SOLVE', 'VERIFY', 'OVERVIEW'
);

-- 版本范围模式
CREATE TYPE rag_edition_scope_mode AS ENUM (
    'STRICT', 'PREFERRED_WITH_FALLBACK', 'CROSS_EDITION'
);

-- 接地判定结论
CREATE TYPE rag_grounding_verdict AS ENUM (
    'GROUNDED', 'PARTIALLY_GROUNDED', 'UNGROUNDED', 'CONFLICTING'
);

-- 入库任务状态
CREATE TYPE rag_ingest_status AS ENUM (
    'PENDING', 'PARSING', 'CHUNKING', 'EMBEDDING', 'INDEXING',
    'SUCCEEDED', 'FAILED', 'REJECTED'
);

-- ---------------------------------------------------------------------
-- 2. 租户与语料来源
-- ---------------------------------------------------------------------

CREATE TABLE rag_tenant (
    tenant_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- id 与 tenant_id 同值：仅为满足「子资源复合外键必须指向父资源唯一键」
    -- 的形式要求（父表缺少 (tenant_id, <pk>) 复合唯一键时无法建立复合外键）。
    id             UUID NOT NULL DEFAULT gen_random_uuid(),
    slug           TEXT NOT NULL UNIQUE,
    name           TEXT NOT NULL,
    -- ⚠️ 允许检索的最低权威等级（DEC-9 精神：门禁只能收紧，不能放松）
    min_trust_tier rag_trust_tier NOT NULL DEFAULT 'OFFICIAL_TUTORIAL',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rag_tenant_slug_format CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    -- ⚠️ 复合唯一键：子资源通过 (tenant_id, <父 id>) 引用父资源，
    --    使子资源无法跨租户引用他人的父资源（主平台 ADR-006 纪律）。
    CONSTRAINT rag_tenant_tenant_id_id_unique UNIQUE (tenant_id, id)
);

COMMENT ON TABLE rag_tenant IS 'RAG 租户。语料与索引按租户隔离。';

-- 语料来源（教材/教辅/论文的**出版元数据**，非正文）
CREATE TABLE rag_source (
    source_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES rag_tenant (tenant_id) ON DELETE CASCADE,

    source_type    TEXT NOT NULL,
    title          TEXT NOT NULL,
    publisher      TEXT NOT NULL,
    -- 出版标识：教材用 ISBN，论文用 DOI
    isbn           TEXT,
    doi            TEXT,

    trust_tier     rag_trust_tier NOT NULL DEFAULT 'UNVERIFIED',
    ver_status     rag_verification_status NOT NULL DEFAULT 'PENDING',
    ver_method     rag_verification_method NOT NULL DEFAULT 'NONE',
    ver_evidence   TEXT,
    verified_at    TIMESTAMPTZ,
    verified_by    UUID,
    -- 校验结论有效期（天）。过期后 ver_status 视为失效，须重新校验。
    ver_valid_days INT NOT NULL DEFAULT 365
                    CONSTRAINT rag_source_ver_valid_days_chk CHECK (ver_valid_days > 0),

    -- 版权状态：'LICENSED' | 'FAIR_USE_QUOTATION' | 'PENDING' | 'RESTRICTED'
    license_status TEXT NOT NULL DEFAULT 'PENDING'
                    CONSTRAINT rag_source_license_chk
                    CHECK (license_status IN ('LICENSED', 'FAIR_USE_QUOTATION', 'PENDING', 'RESTRICTED')),
    -- 正文在对象存储中的引用（正文不入库）
    object_ref     TEXT,
    -- 整册内容校验和：用于检测语料被静默替换
    content_hash   CHAR(64),

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT rag_source_unique UNIQUE (tenant_id, source_id),
    CONSTRAINT rag_source_verified_requires_evidence_chk
        CHECK (ver_status <> 'VERIFIED' OR (ver_evidence IS NOT NULL AND verified_at IS NOT NULL)),
    -- ⚠️ 权威等级与出版标识必须自洽：
    --    教材/教辅等级必须提供 ISBN，论文等级必须提供 DOI，
    --    UNVERIFIED 等级则两者皆可缺失。
    CONSTRAINT rag_source_identifier_chk
        CHECK (
            (trust_tier IN ('OFFICIAL_PUBLISHER', 'OFFICIAL_TUTORIAL') AND isbn IS NOT NULL)
         OR (trust_tier IN ('PEER_REVIEWED_PAPER', 'PREPRINT') AND doi IS NOT NULL)
         OR (trust_tier = 'UNVERIFIED')
        )
);

COMMENT ON TABLE rag_source IS
    '语料来源登记表。**未通过校验的来源不得进入可检索索引**（fail-closed）。';

-- ---------------------------------------------------------------------
-- 3. 版本坐标系
-- ---------------------------------------------------------------------

CREATE TABLE rag_edition (
    edition_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES rag_tenant (tenant_id) ON DELETE CASCADE,
    source_id      UUID NOT NULL,
    subject        TEXT NOT NULL,
    grade          TEXT NOT NULL,
    term           TEXT NOT NULL
                    CONSTRAINT rag_edition_term_chk
                    CHECK (term IN ('上册', '下册', '全一册')),
    publisher      TEXT NOT NULL,
    -- 版次标识：'2024修订' / '2012' / 'v1' 等
    revision_code  TEXT NOT NULL,

    -- 该版本下的课程标准（如《义务教育数学课程标准(2022年版)》）
    curriculum_ref TEXT,
    -- 生效/失效：教材改版后旧版仍需可查（学生可能正在用旧版）
    effective_from DATE,
    effective_to   DATE,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT rag_edition_unique UNIQUE (tenant_id, subject, grade, term, publisher, revision_code),
    CONSTRAINT rag_edition_tenant_edition_unique UNIQUE (tenant_id, edition_id),
    CONSTRAINT rag_edition_source_fk
        FOREIGN KEY (tenant_id, source_id)
        REFERENCES rag_source (tenant_id, source_id) ON DELETE CASCADE
);

COMMENT ON TABLE rag_edition IS
    '版本坐标系。版本是五轴复合标识，缺一轴则引用不可验证。';

-- 知识点本体
CREATE TABLE rag_knowledge_point (
    kp_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES rag_tenant (tenant_id) ON DELETE CASCADE,
    subject        TEXT NOT NULL,
    grade          TEXT NOT NULL,
    canonical_name TEXT NOT NULL,
    -- 别名/别称：教材与教辅对同一概念的不同叫法
    aliases        TEXT[] NOT NULL DEFAULT '{}',
    -- 先修知识点
    prerequisites  UUID[] NOT NULL DEFAULT '{}',
    -- 考纲维度：'难度'|'认知层次'|'题型'
    facets         JSONB NOT NULL DEFAULT '{}'::jsonb,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT rag_kp_unique_name UNIQUE (tenant_id, subject, grade, canonical_name),
    CONSTRAINT rag_kp_tenant_kp_unique UNIQUE (tenant_id, kp_id)
);

COMMENT ON TABLE rag_knowledge_point IS
    '知识点本体。教辅语料的语义骨架，用于解决「讲解与练习碎片化」问题。';

-- 先修关系（独立表：便于查询与校验环路）
CREATE TABLE rag_knowledge_prerequisite (
    tenant_id      UUID NOT NULL REFERENCES rag_tenant (tenant_id) ON DELETE CASCADE,
    kp_id          UUID NOT NULL,
    prereq_kp_id   UUID NOT NULL,
    PRIMARY KEY (tenant_id, kp_id, prereq_kp_id),
    CONSTRAINT rag_kp_prereq_self_chk CHECK (kp_id <> prereq_kp_id),
    CONSTRAINT rag_kp_prereq_kp_fk
        FOREIGN KEY (tenant_id, kp_id)
        REFERENCES rag_knowledge_point (tenant_id, kp_id) ON DELETE CASCADE,
    CONSTRAINT rag_kp_prereq_prereq_fk
        FOREIGN KEY (tenant_id, prereq_kp_id)
        REFERENCES rag_knowledge_point (tenant_id, kp_id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------
-- 4. 文档、片段与向量索引
-- ---------------------------------------------------------------------

-- 文档：语料的**物理载体**（一份 PDF / 一册扫描件）
-- ⚠️ 为什么 source / edition / document 要分三层：
--    · source   = 出版层面的「作品」（人教版八年级上册数学）
--    · edition  = 版本坐标系（2024修订）
--    · document = 物理文件（出版社电子版 PDF / 扫描件）
--    同一 edition 可能有多份 document（官方电子版 + 第三方扫描版），
--    而它们的解析质量与授权状态可能不同。把它们混为一层，
--    就无法表达「同一版本下的多个载体」，
--    也无法在某个载体被撤回时只下架它而不影响整个版本。
CREATE TABLE rag_document (
    document_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES rag_tenant (tenant_id) ON DELETE CASCADE,
    source_id      UUID NOT NULL,
    edition_id     UUID NOT NULL,
    -- 载体类型
    carrier        TEXT NOT NULL DEFAULT 'PDF'
                    CONSTRAINT rag_document_carrier_chk
                    CHECK (carrier IN ('PDF', 'SCAN', 'EPUB', 'HTML', 'TXT')),
    title          TEXT NOT NULL,
    page_count     INT CONSTRAINT rag_document_page_count_chk CHECK (page_count >= 0),
    -- 正文在对象存储中的引用（正文不入库）
    object_ref     TEXT,
    -- 解析状态与工具版本（可复现性）
    parse_status   TEXT NOT NULL DEFAULT 'PENDING'
                    CONSTRAINT rag_document_parse_status_chk
                    CHECK (parse_status IN ('PENDING', 'PARSED', 'CHUNKED', 'FAILED')),
    parser_version TEXT,
    -- 整册校验和：检测语料被静默替换
    content_hash   CHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT rag_document_tenant_document_unique UNIQUE (tenant_id, document_id),
    CONSTRAINT rag_document_source_fk
        FOREIGN KEY (tenant_id, source_id)
        REFERENCES rag_source (tenant_id, source_id) ON DELETE CASCADE,
    CONSTRAINT rag_document_edition_fk
        FOREIGN KEY (tenant_id, edition_id)
        REFERENCES rag_edition (tenant_id, edition_id) ON DELETE CASCADE
);

COMMENT ON TABLE rag_document IS
    '语料的物理载体。与 source（作品）、edition（版本坐标）分层，支持同一版本下的多载体与单载体撤回。';

CREATE TABLE rag_chunk (
    chunk_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES rag_tenant (tenant_id) ON DELETE CASCADE,
    document_id    UUID NOT NULL,
    edition_id     UUID NOT NULL,
    role           rag_chunk_role NOT NULL,
    text           TEXT NOT NULL,

    -- 物理定位：引用可验证性的基础
    page_number    INT NOT NULL CONSTRAINT rag_chunk_page_chk CHECK (page_number >= 1),
    section_path   TEXT NOT NULL,
    exercise_no    TEXT,
    char_start     INT NOT NULL CONSTRAINT rag_chunk_char_start_chk CHECK (char_start >= 0),
    char_end       INT NOT NULL,

    -- 知识点挂接（多对多）
    kp_ids         UUID[] NOT NULL DEFAULT '{}',
    -- 题答配对：答案片段指向对应题目
    exercise_ref   TEXT,

    -- 内容校验和：用于检测语料被静默替换
    content_hash   CHAR(64) NOT NULL,
    -- 嵌入模型版本：换模型必须重建索引（向量不可跨模型比较）
    embed_model    TEXT,
    -- 索引代际：支持蓝绿切换与回滚
    index_generation INT NOT NULL DEFAULT 1,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT rag_chunk_char_range_chk CHECK (char_end >= char_start),
    CONSTRAINT rag_chunk_tenant_chunk_unique UNIQUE (tenant_id, chunk_id),
    CONSTRAINT rag_chunk_document_fk
        FOREIGN KEY (tenant_id, document_id)
        REFERENCES rag_document (tenant_id, document_id) ON DELETE CASCADE,
    CONSTRAINT rag_chunk_edition_fk
        FOREIGN KEY (tenant_id, edition_id)
        REFERENCES rag_edition (tenant_id, edition_id) ON DELETE CASCADE
);

COMMENT ON TABLE rag_chunk IS
    '语料片段。结构化切分的产物，**切片边界由版面结构决定**而非固定窗口。';

-- HNSW 向量索引
-- ⚠️ 维度须与 embed_model 对应；换嵌入模型必须新建代际而非原地改列。
CREATE TABLE rag_chunk_embedding (
    chunk_id       UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    index_generation INT NOT NULL DEFAULT 1,
    embed_model    TEXT NOT NULL,
    -- ⚠️ 1024 维对应 BGE-M3 一类模型；变更须走迁移脚本并全量重建
    embedding      vector(1024) NOT NULL,
    CONSTRAINT rag_embedding_chunk_fk
        FOREIGN KEY (tenant_id, chunk_id)
        REFERENCES rag_chunk (tenant_id, chunk_id) ON DELETE CASCADE,
    CONSTRAINT rag_embedding_tenant_chunk_unique UNIQUE (tenant_id, chunk_id)
);

COMMENT ON TABLE rag_chunk_embedding IS
    '片段向量。索引代际（index_generation）支持换模型时的蓝绿切换与回滚。';

-- HNSW 索引：m=16 / ef_construction=64 为常用起点，实测调优
CREATE INDEX rag_chunk_embedding_hnsw_idx
    ON rag_chunk_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 检索辅助索引
CREATE INDEX rag_chunk_tenant_edition_idx ON rag_chunk (tenant_id, edition_id);
CREATE INDEX rag_chunk_tenant_role_idx ON rag_chunk (tenant_id, role);
CREATE INDEX rag_chunk_kp_idx ON rag_chunk USING gin (kp_ids);
CREATE INDEX rag_chunk_document_idx ON rag_chunk (tenant_id, document_id);
-- ⚠️ 部分索引：只索引可检索角色，排除答案类与噪声
--    这让「泄题闸门」在**存储层**也有一道防线，而不仅靠应用层过滤。
CREATE INDEX rag_chunk_retrievable_idx
    ON rag_chunk (tenant_id, page_number)
    WHERE role NOT IN ('EXERCISE_ANSWER', 'EXAMPLE_SOLUTION', 'BOILERPLATE');

-- ---------------------------------------------------------------------
-- 5. 入库任务与审计
-- ---------------------------------------------------------------------

CREATE TABLE rag_ingest_job (
    job_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES rag_tenant (tenant_id) ON DELETE CASCADE,
    source_id      UUID NOT NULL,
    status         rag_ingest_status NOT NULL DEFAULT 'PENDING',
    -- 解析/切分/嵌入的模型与工具版本（可复现性）
    parser_version TEXT,
    embed_model    TEXT,
    chunk_count    INT,
    error_detail   TEXT,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rag_ingest_source_fk
        FOREIGN KEY (tenant_id, source_id)
        REFERENCES rag_source (tenant_id, source_id) ON DELETE CASCADE
);

-- 语料访问审计：谁在什么时候读了哪个片段（可溯源要求）
CREATE TABLE rag_access_audit (
    audit_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    principal_id   UUID NOT NULL,
    action         TEXT NOT NULL,
    chunk_id       UUID,
    source_id      UUID,
    -- 检索请求的摘要（便于回放）
    request_digest CHAR(64),
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rag_access_audit_action_chk
        CHECK (action IN ('RETRIEVE', 'INGEST', 'CITE', 'EXPORT', 'REVOKE'))
);

-- 审计表追加不可改（与主平台 V6 纪律一致）
CREATE OR REPLACE FUNCTION forbid_rag_audit_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'rag_access_audit 为追加写表，不允许 UPDATE/DELETE';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_rag_audit_append_only
    BEFORE UPDATE OR DELETE ON rag_access_audit
    FOR EACH ROW EXECUTE FUNCTION forbid_rag_audit_mutation();

-- ---------------------------------------------------------------------
-- 6. 引用溯源链路
-- ---------------------------------------------------------------------

CREATE TABLE rag_answer_citation (
    citation_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES rag_tenant (tenant_id) ON DELETE CASCADE,
    -- 产生该引用的对话轮次（与主平台 conversation/run 对齐）
    conversation_id UUID,
    run_id         UUID,
    chunk_id       UUID NOT NULL,
    -- 支撑的声明原文
    supported_claim TEXT NOT NULL,
    -- 逐字引文：供人工核对，不可为空
    verbatim_quote TEXT NOT NULL,
    -- 判定结论
    verdict        rag_grounding_verdict NOT NULL,
    entailment_score REAL NOT NULL CONSTRAINT rag_citation_score_chk
                    CHECK (entailment_score >= 0.0 AND entailment_score <= 1.0),
    -- 引用的版本坐标快照（即使语料后续改版，历史引用仍可解释）
    edition_snapshot JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rag_citation_quote_present_chk CHECK (length(btrim(verbatim_quote)) > 0),
    CONSTRAINT rag_citation_chunk_fk
        FOREIGN KEY (tenant_id, chunk_id)
        REFERENCES rag_chunk (tenant_id, chunk_id) ON DELETE CASCADE
);

COMMENT ON TABLE rag_answer_citation IS
    '引用溯源链路。逐字引文 + 版本快照 + 判定结论，使回答可回溯到具体位置并可复核。';

CREATE INDEX rag_citation_tenant_idx ON rag_answer_citation (tenant_id, created_at DESC);
CREATE INDEX rag_citation_run_idx ON rag_answer_citation (tenant_id, run_id);

-- ---------------------------------------------------------------------
-- 7. 租户隔离（RLS）
-- ---------------------------------------------------------------------
-- ⚠️ 与主平台 ADR-006 纪律一致：
--    FORCE ROW LEVEL SECURITY 使表 owner 也受策略约束，
--    避免应用误用高权限连接造成跨租户泄漏。
--    ⚠️ 但注意：超级用户与 BYPASSRLS 角色可绕过所有策略，
--    对这些角色 FORCE 无效（主平台 E-3 风险，RAG 同样适用）。

ALTER TABLE rag_source              ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_source              FORCE  ROW LEVEL SECURITY;
ALTER TABLE rag_edition             ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_edition             FORCE  ROW LEVEL SECURITY;
ALTER TABLE rag_knowledge_point     ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_knowledge_point     FORCE  ROW LEVEL SECURITY;
ALTER TABLE rag_knowledge_prerequisite ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_knowledge_prerequisite FORCE ROW LEVEL SECURITY;
ALTER TABLE rag_document             ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_document             FORCE  ROW LEVEL SECURITY;
ALTER TABLE rag_chunk               ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_chunk               FORCE  ROW LEVEL SECURITY;
ALTER TABLE rag_chunk_embedding     ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_chunk_embedding     FORCE  ROW LEVEL SECURITY;
ALTER TABLE rag_ingest_job          ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_ingest_job          FORCE  ROW LEVEL SECURITY;
ALTER TABLE rag_access_audit        ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_access_audit        FORCE  ROW LEVEL SECURITY;
ALTER TABLE rag_answer_citation     ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_answer_citation     FORCE  ROW LEVEL SECURITY;

-- 统一的租户上下文函数：应用在事务开始时调用 set_config('app.tenant_id', ...)
-- ⚠️ is_local = true —— 该设置仅在当前事务有效，避免连接池复用导致租户串扰。
--    （主平台 P1-D08 §6 约束 6：set_config(is_local) 必须配合事务）
CREATE OR REPLACE FUNCTION rag_current_tenant() RETURNS UUID AS $$
    SELECT NULLIF(current_setting('app.tenant_id', true), '')::UUID;
$$ LANGUAGE sql STABLE;

CREATE POLICY rag_source_tenant_isolation ON rag_source
    USING (tenant_id = rag_current_tenant())
    WITH CHECK (tenant_id = rag_current_tenant());

CREATE POLICY rag_edition_tenant_isolation ON rag_edition
    USING (tenant_id = rag_current_tenant())
    WITH CHECK (tenant_id = rag_current_tenant());

CREATE POLICY rag_kp_tenant_isolation ON rag_knowledge_point
    USING (tenant_id = rag_current_tenant())
    WITH CHECK (tenant_id = rag_current_tenant());

CREATE POLICY rag_kp_prereq_tenant_isolation ON rag_knowledge_prerequisite
    USING (tenant_id = rag_current_tenant())
    WITH CHECK (tenant_id = rag_current_tenant());

CREATE POLICY rag_document_tenant_isolation ON rag_document
    USING (tenant_id = rag_current_tenant())
    WITH CHECK (tenant_id = rag_current_tenant());

CREATE POLICY rag_chunk_tenant_isolation ON rag_chunk
    USING (tenant_id = rag_current_tenant())
    WITH CHECK (tenant_id = rag_current_tenant());

CREATE POLICY rag_embedding_tenant_isolation ON rag_chunk_embedding
    USING (tenant_id = rag_current_tenant())
    WITH CHECK (tenant_id = rag_current_tenant());

CREATE POLICY rag_ingest_tenant_isolation ON rag_ingest_job
    USING (tenant_id = rag_current_tenant())
    WITH CHECK (tenant_id = rag_current_tenant());

CREATE POLICY rag_audit_tenant_isolation ON rag_access_audit
    USING (tenant_id = rag_current_tenant())
    WITH CHECK (tenant_id = rag_current_tenant());

CREATE POLICY rag_citation_tenant_isolation ON rag_answer_citation
    USING (tenant_id = rag_current_tenant())
    WITH CHECK (tenant_id = rag_current_tenant());
