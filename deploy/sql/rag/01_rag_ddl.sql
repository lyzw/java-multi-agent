-- =====================================================================
-- rag 完整结构（pg_dump --schema-only 导出 + 手工补写扩展头）
-- 【来源】迁移脚本 V1→V8 在全新空库逐个实跑（0 错误）后 pg_dump 导出，
--   再空库复放验证等价（11 表 / 135 列 / 53 约束 / 9 枚举 / 36 索引 /
--   11 RLS 开启 / 35 RLS 策略）。
--   远程 study_rag 已按本形状就地收敛（2026-10-29 只读实测：rag 11 张基表、
--   8 张有 scope 列、rag.*.tenant_id 无一条可空、全局字典 4 行在哨兵桶、
--   rag_chunk_embedding_hnsw_idx 不存在、库里已无 flyway_schema_history 表）。
-- 【地位】db/migration 下 V1~V8 已删除，本文件即 rag 结构唯一权威定义。
--
-- 【存量库改动纪律 —— 原 scripts/rag-v7-to-v8-retrofit-check.sh 的教训，脚本已退役】
--   「空库复放全绿」只证明**新库**能得到这个形状，不证明**已有数据的库**升得上来：
--   新库里回填 UPDATE 影响 0 行，被旧表示法钉住的那条 CHECK 根本不会被触发，
--   顺序写错了也测不出来。这个坑实测存在过 —— V8 的字典收敛段先执行
--   `UPDATE … SET tenant_id = 哨兵 WHERE tenant_id IS NULL`、后删 V7 的
--   rag_dictionary_scope_ck（PLATFORM ⇒ tenant_id IS NULL），
--   在已发布 V7 全局字典的库上 ERROR: violates check constraint，在全新库上全绿。
--   ⇒ 要改存量库**不要重放本文件**（它是 CREATE TABLE，不是幂等 ALTER）；
--     按 04_migrate_embedding_halfvec_4096.sql 写列级迁移，顺序恒为
--     **先摘约束 → 再回填数据 → 最后收 NOT NULL / 重建约束**（顺序是正确性要求，不是风格问题），
--     并沿用它的两条：事务内先 dry-run（结尾 ROLLBACK）、共享库禁止 DROP DATABASE/SCHEMA。
--   ⚠️ 退役理由如实记录：它的被测对象（V7→V8 迁移路径）随 826d77b 删迁移链一起消失，
--     且现库已不可能处于 NULL 表示法（tenant_id 全表 NOT NULL），脚本实跑第一步就 die。
--     它另外两条仍然有效的教训不丢（已核对确实还在被使用，不是「我以为写过了」）：
--       ① 跨桶 UPDATE/DELETE 被 RLS 挡住的表现是**影响 0 行、不报错**（只有 INSERT 才抛），
--          所以断言要核对真实状态、别断言错误文案 —— 落在
--          scripts/rag-dict-scope-counterproof.sh 的 super_val() 注释与 P7-1 用例；
--       ② 无租户上下文时 rag_current_tenant() 返回 NULL 且**不报错**（自动提交会话里 set_config
--          随即失效），夹具清理/断言前必须先在同一事务内 set_config('app.tenant_id', …, true) ——
--          落在 JdbcTenantScopedGeneration 的类注释与 rag-dict 脚本的 app_psql_as()。
--
-- 【手工补写】pg_dump 不导出扩展本体，而 rag_chunk_embedding.embedding 依赖
--   public.halfvec(4096)（同一 vector 扩展提供的半精度类型），缺下面两条会停在
--   CREATE TABLE rag.rag_chunk_embedding：
--     ERROR: type "public.vector" does not exist
--   两者均非 trusted 扩展，需超级用户执行。
-- 【执行】psql -v ON_ERROR_STOP=1 -f 01_rag_ddl.sql（空库，超级用户）
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;

--
-- PostgreSQL database dump
--

\restrict t8pUS8At5cIIAhb4ncTUOYOc7ptKrO1SHfyUGaObtZaFz6gr6Q0tLJwttwkuYrK

-- Dumped from database version 17.11 (Debian 17.11-1.pgdg13+2)
-- Dumped by pg_dump version 17.11 (Debian 17.11-1.pgdg13+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA IF NOT EXISTS public;

-- ── [手工补写] 见文末说明：vector / pgcrypto 扩展


--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA public IS 'standard public schema';


--
-- Name: rag; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA IF NOT EXISTS rag;

-- ── [手工补写] public schema 的两个扩展 ─────────────────────────────
-- pg_dump 不导出扩展本体，而 rag_chunk_embedding.embedding 依赖 public.halfvec(4096)：
-- 缺这两条，本文件在空库上会停在 CREATE TABLE rag.rag_chunk_embedding
--   ERROR: type "public.vector" does not exist
-- ⚠️ 两者均非 trusted 扩展，需要超级用户执行。


--
-- Name: rag_chunk_role; Type: TYPE; Schema: rag; Owner: -
--

CREATE TYPE rag.rag_chunk_role AS ENUM (
    'EXPLANATION',
    'EXAMPLE',
    'EXAMPLE_SOLUTION',
    'EXERCISE',
    'EXERCISE_ANSWER',
    'EXERCISE_HINT',
    'PAPER_BODY',
    'PAPER_METHOD',
    'APPENDIX',
    'BOILERPLATE'
);


--
-- Name: rag_edition_scope_mode; Type: TYPE; Schema: rag; Owner: -
--

CREATE TYPE rag.rag_edition_scope_mode AS ENUM (
    'STRICT',
    'PREFERRED_WITH_FALLBACK',
    'CROSS_EDITION'
);


--
-- Name: rag_grounding_verdict; Type: TYPE; Schema: rag; Owner: -
--

CREATE TYPE rag.rag_grounding_verdict AS ENUM (
    'GROUNDED',
    'PARTIALLY_GROUNDED',
    'UNGROUNDED',
    'CONFLICTING'
);


--
-- Name: rag_ingest_status; Type: TYPE; Schema: rag; Owner: -
--

CREATE TYPE rag.rag_ingest_status AS ENUM (
    'PENDING',
    'PARSING',
    'CHUNKING',
    'EMBEDDING',
    'INDEXING',
    'SUCCEEDED',
    'FAILED',
    'REJECTED'
);


--
-- Name: rag_license_status; Type: TYPE; Schema: rag; Owner: -
--

CREATE TYPE rag.rag_license_status AS ENUM (
    'LICENSED',
    'FAIR_USE_QUOTATION',
    'PENDING',
    'RESTRICTED'
);


--
-- Name: rag_retrieval_mode; Type: TYPE; Schema: rag; Owner: -
--

CREATE TYPE rag.rag_retrieval_mode AS ENUM (
    'EXPLAIN',
    'SOLVE',
    'VERIFY',
    'OVERVIEW'
);


--
-- Name: rag_trust_tier; Type: TYPE; Schema: rag; Owner: -
--

CREATE TYPE rag.rag_trust_tier AS ENUM (
    'OFFICIAL_PUBLISHER',
    'OFFICIAL_TUTORIAL',
    'PEER_REVIEWED_PAPER',
    'PREPRINT',
    'UNVERIFIED'
);


--
-- Name: rag_verification_method; Type: TYPE; Schema: rag; Owner: -
--

CREATE TYPE rag.rag_verification_method AS ENUM (
    'NONE',
    'ISBN',
    'DOI',
    'MANUAL_REVIEW',
    'OFFICIAL_HASH'
);


--
-- Name: rag_verification_status; Type: TYPE; Schema: rag; Owner: -
--

CREATE TYPE rag.rag_verification_status AS ENUM (
    'PENDING',
    'IN_REVIEW',
    'VERIFIED',
    'REJECTED',
    'REVOKED'
);


--
-- Name: forbid_rag_audit_mutation(); Type: FUNCTION; Schema: rag; Owner: -
--

CREATE FUNCTION rag.forbid_rag_audit_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'rag_access_audit 为追加写表，不允许 UPDATE/DELETE';
END;
$$;


--
-- Name: rag_current_tenant(); Type: FUNCTION; Schema: rag; Owner: -
--

CREATE FUNCTION rag.rag_current_tenant() RETURNS uuid
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
    v text := current_setting('app.tenant_id', true);
BEGIN
    IF v IS NULL OR btrim(v) = '' THEN
        RETURN NULL;
    END IF;
    -- ⚠️ 先转 UUID 再比哨兵：非法值沿用 V1 的 22P02 抛错行为，
    --    不额外发明一套宽松的正则前置校验（会把合法但带大括号的写法误杀）。
    IF v::uuid = rag.rag_global_tenant_id() THEN
        RAISE EXCEPTION '禁止把全局哨兵租户 % 作为会话身份（越权企图）', v
            USING ERRCODE = '42501';
    END IF;
    RETURN v::uuid;
END
$$;


--
-- Name: FUNCTION rag_current_tenant(); Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON FUNCTION rag.rag_current_tenant() IS '会话租户上下文。未设置 → NULL（只见全局行）。显式把全局哨兵当租户身份 → 直接报错（越权企图，fail-loud 而非静默放行）。';


--
-- Name: rag_global_tenant_id(); Type: FUNCTION; Schema: rag; Owner: -
--

CREATE FUNCTION rag.rag_global_tenant_id() RETURNS uuid
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$ SELECT '00000000-0000-0000-0000-000000000000'::UUID $$;


--
-- Name: FUNCTION rag_global_tenant_id(); Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON FUNCTION rag.rag_global_tenant_id() IS '全局作用域的保留租户 ID（哨兵）。真实租户永远取不到这个值。全局行 = tenant_id 等于本值 且 scope = ''PLATFORM''。';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: rag_access_audit; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_access_audit (
    audit_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    principal_id uuid,
    action text NOT NULL,
    chunk_id uuid,
    source_id uuid,
    request_digest character(64),
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT rag_access_audit_action_chk CHECK ((action = ANY (ARRAY['RETRIEVE'::text, 'INGEST'::text, 'CITE'::text, 'EXPORT'::text, 'REVOKE'::text, 'ADMIT'::text, 'REJECT'::text])))
);

ALTER TABLE ONLY rag.rag_access_audit FORCE ROW LEVEL SECURITY;


--
-- Name: rag_answer_citation; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_answer_citation (
    citation_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    conversation_id uuid,
    run_id uuid,
    chunk_id uuid NOT NULL,
    supported_claim text NOT NULL,
    verbatim_quote text NOT NULL,
    verdict rag.rag_grounding_verdict NOT NULL,
    entailment_score real NOT NULL,
    edition_snapshot jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT rag_citation_quote_present_chk CHECK ((length(btrim(verbatim_quote)) > 0)),
    CONSTRAINT rag_citation_score_chk CHECK (((entailment_score >= (0.0)::double precision) AND (entailment_score <= (1.0)::double precision)))
);

ALTER TABLE ONLY rag.rag_answer_citation FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE rag_answer_citation; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON TABLE rag.rag_answer_citation IS '引用溯源链路。逐字引文 + 版本快照 + 判定结论，使回答可回溯到具体位置并可复核。';


--
-- Name: rag_chunk; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_chunk (
    chunk_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    document_id uuid NOT NULL,
    edition_id uuid NOT NULL,
    role rag.rag_chunk_role NOT NULL,
    text text NOT NULL,
    page_number integer NOT NULL,
    section_path text NOT NULL,
    exercise_no text,
    char_start integer NOT NULL,
    char_end integer NOT NULL,
    kp_ids uuid[] DEFAULT '{}'::uuid[] NOT NULL,
    exercise_ref text,
    content_hash character(64) NOT NULL,
    embed_model text,
    index_generation integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    text_jieba text DEFAULT ''::text NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    CONSTRAINT rag_chunk_char_range_chk CHECK ((char_end >= char_start)),
    CONSTRAINT rag_chunk_char_start_chk CHECK ((char_start >= 0)),
    CONSTRAINT rag_chunk_page_chk CHECK ((page_number >= 1)),
    CONSTRAINT rag_chunk_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id = rag.rag_global_tenant_id())) OR ((scope = 'TENANT'::text) AND (tenant_id <> rag.rag_global_tenant_id()))))
);

ALTER TABLE ONLY rag.rag_chunk FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE rag_chunk; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON TABLE rag.rag_chunk IS '语料片段。结构化切分的产物，**切片边界由版面结构决定**而非固定窗口。';


--
-- Name: COLUMN rag_chunk.tenant_id; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON COLUMN rag.rag_chunk.tenant_id IS 'platform 权威租户 ID 的引用值。RAG 不存租户信息、不校验租户存在性；隔离由 RLS 保证。';


--
-- Name: COLUMN rag_chunk.scope; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON COLUMN rag.rag_chunk.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: rag_chunk_embedding; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_chunk_embedding (
    chunk_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    index_generation integer DEFAULT 1 NOT NULL,
    embed_model text NOT NULL,
    embedding public.halfvec(4096) NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    CONSTRAINT rag_chunk_embedding_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id = rag.rag_global_tenant_id())) OR ((scope = 'TENANT'::text) AND (tenant_id <> rag.rag_global_tenant_id()))))
);

ALTER TABLE ONLY rag.rag_chunk_embedding FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE rag_chunk_embedding; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON TABLE rag.rag_chunk_embedding IS '片段向量。索引代际（index_generation）支持换模型时的蓝绿切换与回滚。';


--
-- Name: COLUMN rag_chunk_embedding.scope; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON COLUMN rag.rag_chunk_embedding.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: rag_dictionary; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_dictionary (
    dict_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    dict_type text NOT NULL,
    code text NOT NULL,
    label text NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    CONSTRAINT rag_dictionary_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id = rag.rag_global_tenant_id())) OR ((scope = 'TENANT'::text) AND (tenant_id <> rag.rag_global_tenant_id()))))
);

ALTER TABLE ONLY rag.rag_dictionary FORCE ROW LEVEL SECURITY;


--
-- Name: COLUMN rag_dictionary.scope; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON COLUMN rag.rag_dictionary.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 NULL，全租户只读可见）；TENANT=租户私有（tenant_id 必为非空，严格隔离）。''';


--
-- Name: rag_document; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_document (
    document_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    source_id uuid NOT NULL,
    edition_id uuid NOT NULL,
    carrier text DEFAULT 'PDF'::text NOT NULL,
    title text NOT NULL,
    page_count integer,
    object_ref text,
    parse_status text DEFAULT 'PENDING'::text NOT NULL,
    parser_version text,
    content_hash character(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    ver_status rag.rag_verification_status DEFAULT 'PENDING'::rag.rag_verification_status NOT NULL,
    retrievable boolean DEFAULT false NOT NULL,
    revoked_at timestamp with time zone,
    revoked_reason text,
    license_status rag.rag_license_status DEFAULT 'PENDING'::rag.rag_license_status NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    CONSTRAINT rag_document_carrier_chk CHECK ((carrier = ANY (ARRAY['PDF'::text, 'SCAN'::text, 'EPUB'::text, 'HTML'::text, 'TXT'::text]))),
    CONSTRAINT rag_document_page_count_chk CHECK ((page_count >= 0)),
    CONSTRAINT rag_document_parse_status_chk CHECK ((parse_status = ANY (ARRAY['PENDING'::text, 'PARSED'::text, 'CHUNKED'::text, 'FAILED'::text]))),
    CONSTRAINT rag_document_revoked_reason_chk CHECK (((ver_status <> 'REVOKED'::rag.rag_verification_status) OR (revoked_reason IS NOT NULL))),
    CONSTRAINT rag_document_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id = rag.rag_global_tenant_id())) OR ((scope = 'TENANT'::text) AND (tenant_id <> rag.rag_global_tenant_id()))))
);

ALTER TABLE ONLY rag.rag_document FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE rag_document; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON TABLE rag.rag_document IS '语料的物理载体。与 source（作品）、edition（版本坐标）分层，支持同一版本下的多载体与单载体撤回。';


--
-- Name: COLUMN rag_document.retrievable; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON COLUMN rag.rag_document.retrievable IS '载体级可检索标记。撤回时置 FALSE，实现「同版本下只下架单个载体」而不必物理 DELETE。';


--
-- Name: COLUMN rag_document.scope; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON COLUMN rag.rag_document.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: rag_edition; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_edition (
    edition_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    source_id uuid NOT NULL,
    subject text NOT NULL,
    grade text NOT NULL,
    term text NOT NULL,
    publisher text NOT NULL,
    revision_code text NOT NULL,
    curriculum_ref text,
    effective_from date,
    effective_to date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    CONSTRAINT rag_edition_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id = rag.rag_global_tenant_id())) OR ((scope = 'TENANT'::text) AND (tenant_id <> rag.rag_global_tenant_id())))),
    CONSTRAINT rag_edition_term_chk CHECK ((term = ANY (ARRAY['上册'::text, '下册'::text, '全一册'::text])))
);

ALTER TABLE ONLY rag.rag_edition FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE rag_edition; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON TABLE rag.rag_edition IS '版本坐标系。版本是五轴复合标识，缺一轴则引用不可验证。';


--
-- Name: COLUMN rag_edition.scope; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON COLUMN rag.rag_edition.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: rag_inspection_record; Type: TABLE; Schema: rag; Owner: -
--

-- ⚠️ V1.0.2 §5 R2 / 设计 §2.2.3：人工抽检明细。**只追加不修改**——它是质量判定的证据，
--    门禁证据包（D09 G1 ②③④）直接引用这些记录。改历史记录等于伪造证据。
CREATE TABLE rag.rag_inspection_record (
    record_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    source_id uuid NOT NULL,
    chunk_id uuid NOT NULL,
    inspector_id uuid NOT NULL,
    expected_role text NOT NULL,
    actual_role text NOT NULL,
    -- 错误类型；结论为 PASS 时必须为 NULL（由应用层 InspectionRecord 不变式保证）
    error_type text,
    verdict text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    CONSTRAINT rag_inspection_verdict_chk CHECK ((verdict = ANY (ARRAY['PASS'::text, 'FAIL'::text]))),
    CONSTRAINT rag_inspection_error_type_chk CHECK ((error_type IS NULL OR error_type = ANY (ARRAY['ANSWER_LEAK'::text, 'EXERCISE_NO_LOST'::text, 'LOCATOR_WRONG'::text, 'CHUNK_BOUNDARY'::text, 'OTHER'::text]))),
    -- ⚠️ 结论与错误类型必须自洽：FAIL 必有类型、PASS 必无类型
    CONSTRAINT rag_inspection_verdict_error_ck CHECK ((((verdict = 'FAIL'::text) AND (error_type IS NOT NULL)) OR ((verdict = 'PASS'::text) AND (error_type IS NULL))))
);

ALTER TABLE ONLY rag.rag_inspection_record FORCE ROW LEVEL SECURITY;


--
-- Name: rag_kp_alias; Type: TABLE; Schema: rag; Owner: -
--

-- ⚠️ V1.0.2 §5 R7 / §8.3 / 设计 §2.2.3：知识点别名表。
--    **不复用 rag_dictionary**：后者是通用 dict_type/code 词表，
--    无法表达「某 kp_id 的别名集合」这一关系。
--    ⚠️ KnowledgePoint.aliases（TEXT[]）的内存匹配机制已存在，
--    本表是它的人工维护入口与持久化载体。
CREATE TABLE rag.rag_kp_alias (
    alias_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    kp_id uuid NOT NULL,
    alias_text text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL
);

ALTER TABLE ONLY rag.rag_kp_alias FORCE ROW LEVEL SECURITY;


--
-- Name: rag_ingest_job; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_ingest_job (
    job_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    source_id uuid NOT NULL,
    status rag.rag_ingest_status DEFAULT 'PENDING'::rag.rag_ingest_status NOT NULL,
    -- ⚠️ V1.0.2 §8.3：阶段内进度（与 status 的「阶段级」枚举正交）。
    --    status 表达在哪个阶段，stage 表达该阶段做到什么程度（如 'PARSING 60'）。
    stage text,
    -- ⚠️ V1.0.2 §8.3：重试次数。重试=新建行，本列记录「这是第几次尝试」，旧行留痕不覆盖。
    attempt integer DEFAULT 0 NOT NULL,
    -- ⚠️ V1.0.2 §8.3：触发人（去哨兵后为真实主体），审计留痕用。
    created_by uuid,
    parser_version text,
    embed_model text,
    chunk_count integer,
    error_detail text,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY rag.rag_ingest_job FORCE ROW LEVEL SECURITY;


--
-- Name: rag_knowledge_point; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_knowledge_point (
    kp_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    subject text NOT NULL,
    grade text NOT NULL,
    canonical_name text NOT NULL,
    aliases text[] DEFAULT '{}'::text[] NOT NULL,
    prerequisites uuid[] DEFAULT '{}'::uuid[] NOT NULL,
    facets jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    CONSTRAINT rag_knowledge_point_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id = rag.rag_global_tenant_id())) OR ((scope = 'TENANT'::text) AND (tenant_id <> rag.rag_global_tenant_id()))))
);

ALTER TABLE ONLY rag.rag_knowledge_point FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE rag_knowledge_point; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON TABLE rag.rag_knowledge_point IS '知识点本体。教辅语料的语义骨架，用于解决「讲解与练习碎片化」问题。';


--
-- Name: COLUMN rag_knowledge_point.scope; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON COLUMN rag.rag_knowledge_point.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: rag_knowledge_prerequisite; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_knowledge_prerequisite (
    tenant_id uuid NOT NULL,
    kp_id uuid NOT NULL,
    prereq_kp_id uuid NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    CONSTRAINT rag_knowledge_prerequisite_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id = rag.rag_global_tenant_id())) OR ((scope = 'TENANT'::text) AND (tenant_id <> rag.rag_global_tenant_id())))),
    CONSTRAINT rag_kp_prereq_self_chk CHECK ((kp_id <> prereq_kp_id))
);

ALTER TABLE ONLY rag.rag_knowledge_prerequisite FORCE ROW LEVEL SECURITY;


--
-- Name: COLUMN rag_knowledge_prerequisite.scope; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON COLUMN rag.rag_knowledge_prerequisite.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: rag_source; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_source (
    source_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    source_type text NOT NULL,
    title text NOT NULL,
    publisher text NOT NULL,
    isbn text,
    doi text,
    trust_tier rag.rag_trust_tier DEFAULT 'UNVERIFIED'::rag.rag_trust_tier NOT NULL,
    ver_status rag.rag_verification_status DEFAULT 'PENDING'::rag.rag_verification_status NOT NULL,
    ver_method rag.rag_verification_method DEFAULT 'NONE'::rag.rag_verification_method NOT NULL,
    ver_evidence text,
    verified_at timestamp with time zone,
    verified_by uuid,
    ver_valid_days integer DEFAULT 365 NOT NULL,
    license_status rag.rag_license_status DEFAULT 'PENDING'::rag.rag_license_status NOT NULL,
    object_ref text,
    content_hash character(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    review_started_at timestamp with time zone,
    reviewer_id uuid,
    ver_reject_reason text,
    ver_decided_at timestamp with time zone,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    -- ⚠️ V1.0.2 §5 R2 / 设计 §2.2.4：抽检状态，是 IN_REVIEW→VERIFIED 迁移的**前置守卫**落点。
    --    默认 NOT_REQUIRED；InspectionGuard 只认 PASSED，故默认值不会误放行。
    --    ⚠️⚠️ **本列必须追加在表尾，不得插在中间**：
    --    02_rag_seed.sql 的 INSERT 不带列名（{@code INSERT ... VALUES (...)}），
    --    在中间插入一列会让后续所有值整体错位一个位置，
    --    直接触发 CHECK 违约（真库实测已踩到：inspection_status 收到 'TENANT'）。
    --    追加在表尾则既有不带列名的 INSERT 依然按原顺序对齐。
    inspection_status text DEFAULT 'NOT_REQUIRED'::text NOT NULL,
    CONSTRAINT rag_source_identifier_chk CHECK ((((trust_tier = ANY (ARRAY['OFFICIAL_PUBLISHER'::rag.rag_trust_tier, 'OFFICIAL_TUTORIAL'::rag.rag_trust_tier])) AND (isbn IS NOT NULL)) OR ((trust_tier = ANY (ARRAY['PEER_REVIEWED_PAPER'::rag.rag_trust_tier, 'PREPRINT'::rag.rag_trust_tier])) AND (doi IS NOT NULL)) OR (trust_tier = 'UNVERIFIED'::rag.rag_trust_tier))),
    CONSTRAINT rag_source_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id = rag.rag_global_tenant_id())) OR ((scope = 'TENANT'::text) AND (tenant_id <> rag.rag_global_tenant_id())))),
    CONSTRAINT rag_source_ver_valid_days_chk CHECK ((ver_valid_days > 0)),
    CONSTRAINT rag_source_inspection_status_chk CHECK ((inspection_status = ANY (ARRAY['NOT_REQUIRED'::text, 'PASSED'::text, 'FAILED'::text]))),
    CONSTRAINT rag_source_verified_requires_evidence_chk CHECK (((ver_status <> 'VERIFIED'::rag.rag_verification_status) OR ((ver_evidence IS NOT NULL) AND (verified_at IS NOT NULL))))
);

ALTER TABLE ONLY rag.rag_source FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE rag_source; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON TABLE rag.rag_source IS '语料来源登记表。**未通过校验的来源不得进入可检索索引**（fail-closed）。';


--
-- Name: COLUMN rag_source.tenant_id; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON COLUMN rag.rag_source.tenant_id IS 'platform 权威租户 ID 的引用值。RAG 不存租户信息、不校验租户存在性；隔离由 RLS 保证。';


--
-- Name: COLUMN rag_source.reviewer_id; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON COLUMN rag.rag_source.reviewer_id IS '审核认领人。回答「这批语料卡在谁手上」，使 SLA 可度量。';


--
-- Name: COLUMN rag_source.ver_reject_reason; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON COLUMN rag.rag_source.ver_reject_reason IS '拒绝理由。与 ver_evidence（成功证据）语义分离，避免混淆。';


--
-- Name: COLUMN rag_source.scope; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON COLUMN rag.rag_source.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: rag_access_audit rag_access_audit_pkey; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_access_audit
    ADD CONSTRAINT rag_access_audit_pkey PRIMARY KEY (audit_id);


--
-- Name: rag_answer_citation rag_answer_citation_pkey; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_answer_citation
    ADD CONSTRAINT rag_answer_citation_pkey PRIMARY KEY (citation_id);


--
-- Name: rag_chunk_embedding rag_chunk_embedding_pkey; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_chunk_embedding
    ADD CONSTRAINT rag_chunk_embedding_pkey PRIMARY KEY (chunk_id);


--
-- Name: rag_chunk rag_chunk_pkey; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_chunk
    ADD CONSTRAINT rag_chunk_pkey PRIMARY KEY (chunk_id);


--
-- Name: rag_chunk rag_chunk_tenant_chunk_unique; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_chunk
    ADD CONSTRAINT rag_chunk_tenant_chunk_unique UNIQUE (tenant_id, chunk_id);


--
-- Name: rag_dictionary rag_dictionary_pkey; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_dictionary
    ADD CONSTRAINT rag_dictionary_pkey PRIMARY KEY (dict_id);


--
-- Name: rag_dictionary rag_dictionary_tenant_id_dict_type_code_key; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_dictionary
    ADD CONSTRAINT rag_dictionary_tenant_id_dict_type_code_key UNIQUE (tenant_id, dict_type, code);


--
-- Name: rag_document rag_document_pkey; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_document
    ADD CONSTRAINT rag_document_pkey PRIMARY KEY (document_id);


--
-- Name: rag_document rag_document_tenant_document_unique; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_document
    ADD CONSTRAINT rag_document_tenant_document_unique UNIQUE (tenant_id, document_id);


--
-- Name: rag_edition rag_edition_pkey; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_edition
    ADD CONSTRAINT rag_edition_pkey PRIMARY KEY (edition_id);


--
-- Name: rag_edition rag_edition_tenant_edition_unique; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_edition
    ADD CONSTRAINT rag_edition_tenant_edition_unique UNIQUE (tenant_id, edition_id);


--
-- Name: rag_edition rag_edition_unique; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_edition
    ADD CONSTRAINT rag_edition_unique UNIQUE (tenant_id, subject, grade, term, publisher, revision_code);


--
-- Name: rag_chunk_embedding rag_embedding_tenant_chunk_unique; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_chunk_embedding
    ADD CONSTRAINT rag_embedding_tenant_chunk_unique UNIQUE (tenant_id, chunk_id);


--
-- Name: rag_ingest_job rag_ingest_job_pkey; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_ingest_job
    ADD CONSTRAINT rag_ingest_job_pkey PRIMARY KEY (job_id);


--
-- Name: rag_inspection_record rag_inspection_record_pkey; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_inspection_record
    ADD CONSTRAINT rag_inspection_record_pkey PRIMARY KEY (record_id);


--
-- Name: rag_kp_alias rag_kp_alias_pkey; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_kp_alias
    ADD CONSTRAINT rag_kp_alias_pkey PRIMARY KEY (alias_id);


--
-- Name: rag_kp_alias rag_kp_alias_unique; Type: CONSTRAINT; Schema: rag; Owner: -
--

-- ⚠️ 同一知识点下同一别名只允许一条：重复别名只会让匹配变慢，不增加召回。
ALTER TABLE ONLY rag.rag_kp_alias
    ADD CONSTRAINT rag_kp_alias_unique UNIQUE (tenant_id, kp_id, alias_text);


--
-- Name: rag_knowledge_point rag_knowledge_point_pkey; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_knowledge_point
    ADD CONSTRAINT rag_knowledge_point_pkey PRIMARY KEY (kp_id);


--
-- Name: rag_knowledge_prerequisite rag_knowledge_prerequisite_pkey; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_knowledge_prerequisite
    ADD CONSTRAINT rag_knowledge_prerequisite_pkey PRIMARY KEY (tenant_id, kp_id, prereq_kp_id);


--
-- Name: rag_knowledge_point rag_kp_tenant_kp_unique; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_knowledge_point
    ADD CONSTRAINT rag_kp_tenant_kp_unique UNIQUE (tenant_id, kp_id);


--
-- Name: rag_knowledge_point rag_kp_unique_name; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_knowledge_point
    ADD CONSTRAINT rag_kp_unique_name UNIQUE (tenant_id, subject, grade, canonical_name);


--
-- Name: rag_source rag_source_pkey; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_source
    ADD CONSTRAINT rag_source_pkey PRIMARY KEY (source_id);


--
-- Name: rag_source rag_source_unique; Type: CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_source
    ADD CONSTRAINT rag_source_unique UNIQUE (tenant_id, source_id);


--
-- Name: rag_audit_tenant_occurred_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_audit_tenant_occurred_idx ON rag.rag_access_audit USING btree (tenant_id, occurred_at DESC);


--
-- Name: rag_audit_tenant_source_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_audit_tenant_source_idx ON rag.rag_access_audit USING btree (tenant_id, source_id, occurred_at DESC);


--
-- Name: rag_chunk_document_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_chunk_document_idx ON rag.rag_chunk USING btree (tenant_id, document_id);


--
-- Name: rag_chunk_embedding_hnsw_idx; Type: INDEX; Schema: rag; Owner: -
--

-- ⚠️ 本索引在 halfvec(4096) 下**刻意不存在**（2026-10-28 换真实嵌入模型）：
--   pgvector 的 HNSW/IVFFlat 对 halfvec 只支持到 4000 维（vector 2000 维），
--   4096 维能存不能建 ANN 索引，稠密检索退化为全表精确扫描。
--   当前语料量级（数百片段）下精确扫描既快又无召回损失；
--   规模化后再二选一：MRL 截断到 ≤4000 维，或改用二进制量化（bit 可到 64000 维）。
--   旧定义留档：USING hnsw (embedding public.vector_cosine_ops) WITH (m='16', ef_construction='64')
-- CREATE INDEX rag_chunk_embedding_hnsw_idx ON rag.rag_chunk_embedding USING hnsw (embedding public.halfvec_cosine_ops) WITH (m='16', ef_construction='64');


--
-- Name: rag_chunk_jieba_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_chunk_jieba_idx ON rag.rag_chunk USING gin (to_tsvector('simple'::regconfig, text_jieba));


--
-- Name: rag_chunk_kp_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_chunk_kp_idx ON rag.rag_chunk USING gin (kp_ids);


--
-- Name: rag_chunk_retrievable_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_chunk_retrievable_idx ON rag.rag_chunk USING btree (tenant_id, page_number) WHERE (role <> ALL (ARRAY['EXERCISE_ANSWER'::rag.rag_chunk_role, 'EXAMPLE_SOLUTION'::rag.rag_chunk_role, 'BOILERPLATE'::rag.rag_chunk_role]));


--
-- Name: rag_chunk_tenant_edition_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_chunk_tenant_edition_idx ON rag.rag_chunk USING btree (tenant_id, edition_id);


--
-- Name: rag_chunk_tenant_role_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_chunk_tenant_role_idx ON rag.rag_chunk USING btree (tenant_id, role);


--
-- Name: rag_citation_run_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_citation_run_idx ON rag.rag_answer_citation USING btree (tenant_id, run_id);


--
-- Name: rag_citation_tenant_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_citation_tenant_idx ON rag.rag_answer_citation USING btree (tenant_id, created_at DESC);


--
-- Name: rag_dictionary_tenant_type_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_dictionary_tenant_type_idx ON rag.rag_dictionary USING btree (tenant_id, dict_type, sort_order, code);


--
-- Name: rag_document_retrievable_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_document_retrievable_idx ON rag.rag_document USING btree (tenant_id, edition_id) WHERE retrievable;


--
-- Name: rag_document_tenant_source_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_document_tenant_source_idx ON rag.rag_document USING btree (tenant_id, source_id);


--
-- Name: rag_source_tenant_trust_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_source_tenant_trust_idx ON rag.rag_source USING btree (tenant_id, trust_tier);


--
-- Name: rag_ingest_job_tenant_status_idx; Type: INDEX; Schema: rag; Owner: -
--

-- ⚠️ V1.0.2 §8.3 / P5：该表原为「零二级索引」。
--    消费循环按 status='PENDING' + FOR UPDATE SKIP LOCKED 领取，Sweeper 按
--    status IN (进行中) AND started_at < now()-interval 扫描超时——无索引则每次全表扫描。
CREATE INDEX rag_ingest_job_tenant_status_idx ON rag.rag_ingest_job USING btree (tenant_id, status);


--
-- Name: rag_ingest_job_status_created_idx; Type: INDEX; Schema: rag; Owner: -
--

-- ⚠️ V1.0.2 §8.3：跨租户/全局视角的领取与积压统计按 (status, created_at) 排序。
CREATE INDEX rag_ingest_job_status_created_idx ON rag.rag_ingest_job USING btree (status, created_at);


--
-- Name: rag_document_tenant_content_hash_uq; Type: INDEX; Schema: rag; Owner: -
--

-- ⚠️ V1.0.2 §2.2.2 / N1：载体级去重兜底——**部分唯一索引**，必须排除非活跃载体：
--    ① ver_status='REVOKED' 的载体经新来源重传同文件是合法路径；
--    ② parse_status='FAILED' 的载体同文件重传重跑是失败处置的唯一活口。
--    若不加这两个条件，唯一索引会直接堵死上述两条合法链路（并与 T-10 终态纪律冲突）。
--    ⚠️ 本索引的 WHERE 必须与应用层预检（IngestDedupPrecheck）逐字同口径，否则两层打架。
CREATE UNIQUE INDEX rag_document_tenant_content_hash_uq ON rag.rag_document
    USING btree (tenant_id, content_hash)
    WHERE (ver_status <> 'REVOKED'::rag.rag_verification_status AND parse_status <> 'FAILED'::text);


--
-- Name: rag_source_tenant_isbn_uq; Type: INDEX; Schema: rag; Owner: -
--

-- ⚠️ V1.0.2 §2.2.2 / N1：来源级去重兜底——**部分唯一索引**，必须排除 REVOKED 来源。
--    RAG-D07 T-10：REVOKED 是终态，重新准入必须**新建 source_id**；同一本被撤回的书
--    重新登记会产生「新 source_id + 同 ISBN」。若不加 ver_status<>'REVOKED'，
--    该合法路径会被唯一索引直接拒绝。
CREATE UNIQUE INDEX rag_source_tenant_isbn_uq ON rag.rag_source
    USING btree (tenant_id, isbn)
    WHERE (isbn IS NOT NULL AND ver_status <> 'REVOKED'::rag.rag_verification_status);


--
-- Name: rag_source_tenant_doi_uq; Type: INDEX; Schema: rag; Owner: -
--

-- ⚠️ V1.0.2 §2.2.2 / N1：DOI 同 ISBN 口径（同源部分唯一索引）。
CREATE UNIQUE INDEX rag_source_tenant_doi_uq ON rag.rag_source
    USING btree (tenant_id, doi)
    WHERE (doi IS NOT NULL AND ver_status <> 'REVOKED'::rag.rag_verification_status);


--
-- Name: rag_inspection_record_source_idx; Type: INDEX; Schema: rag; Owner: -
--

-- ⚠️ 抽检记录按来源查询（抽检台/证据包），并支撑「按来源聚合判定」。
CREATE INDEX rag_inspection_record_source_idx ON rag.rag_inspection_record
    USING btree (tenant_id, source_id, created_at DESC);


--
-- Name: rag_kp_alias_kp_idx; Type: INDEX; Schema: rag; Owner: -
--

-- ⚠️ 摄取时按知识点批量取别名，必须按 kp_id 建索引。
CREATE INDEX rag_kp_alias_kp_idx ON rag.rag_kp_alias
    USING btree (tenant_id, kp_id);


--
-- Name: rag_source_tenant_ver_status_idx; Type: INDEX; Schema: rag; Owner: -
--

CREATE INDEX rag_source_tenant_ver_status_idx ON rag.rag_source USING btree (tenant_id, ver_status, created_at DESC);


--
-- Name: rag_access_audit trg_rag_audit_append_only; Type: TRIGGER; Schema: rag; Owner: -
--

CREATE TRIGGER trg_rag_audit_append_only BEFORE DELETE OR UPDATE ON rag.rag_access_audit FOR EACH ROW EXECUTE FUNCTION rag.forbid_rag_audit_mutation();


--
-- Name: rag_chunk rag_chunk_document_fk; Type: FK CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_chunk
    ADD CONSTRAINT rag_chunk_document_fk FOREIGN KEY (tenant_id, document_id) REFERENCES rag.rag_document(tenant_id, document_id) ON DELETE CASCADE;


--
-- Name: rag_chunk rag_chunk_edition_fk; Type: FK CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_chunk
    ADD CONSTRAINT rag_chunk_edition_fk FOREIGN KEY (tenant_id, edition_id) REFERENCES rag.rag_edition(tenant_id, edition_id) ON DELETE CASCADE;


--
-- Name: rag_answer_citation rag_citation_chunk_fk; Type: FK CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_answer_citation
    ADD CONSTRAINT rag_citation_chunk_fk FOREIGN KEY (chunk_id) REFERENCES rag.rag_chunk(chunk_id) ON DELETE CASCADE;


--
-- Name: rag_document rag_document_edition_fk; Type: FK CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_document
    ADD CONSTRAINT rag_document_edition_fk FOREIGN KEY (tenant_id, edition_id) REFERENCES rag.rag_edition(tenant_id, edition_id) ON DELETE CASCADE;


--
-- Name: rag_document rag_document_source_fk; Type: FK CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_document
    ADD CONSTRAINT rag_document_source_fk FOREIGN KEY (tenant_id, source_id) REFERENCES rag.rag_source(tenant_id, source_id) ON DELETE CASCADE;


--
-- Name: rag_edition rag_edition_source_fk; Type: FK CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_edition
    ADD CONSTRAINT rag_edition_source_fk FOREIGN KEY (tenant_id, source_id) REFERENCES rag.rag_source(tenant_id, source_id) ON DELETE CASCADE;


--
-- Name: rag_chunk_embedding rag_embedding_chunk_fk; Type: FK CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_chunk_embedding
    ADD CONSTRAINT rag_embedding_chunk_fk FOREIGN KEY (tenant_id, chunk_id) REFERENCES rag.rag_chunk(tenant_id, chunk_id) ON DELETE CASCADE;


--
-- Name: rag_ingest_job rag_ingest_source_fk; Type: FK CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_ingest_job
    ADD CONSTRAINT rag_ingest_source_fk FOREIGN KEY (tenant_id, source_id) REFERENCES rag.rag_source(tenant_id, source_id) ON DELETE CASCADE;


--
-- Name: rag_knowledge_prerequisite rag_kp_prereq_kp_fk; Type: FK CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_knowledge_prerequisite
    ADD CONSTRAINT rag_kp_prereq_kp_fk FOREIGN KEY (tenant_id, kp_id) REFERENCES rag.rag_knowledge_point(tenant_id, kp_id) ON DELETE CASCADE;


--
-- Name: rag_knowledge_prerequisite rag_kp_prereq_prereq_fk; Type: FK CONSTRAINT; Schema: rag; Owner: -
--

ALTER TABLE ONLY rag.rag_knowledge_prerequisite
    ADD CONSTRAINT rag_kp_prereq_prereq_fk FOREIGN KEY (tenant_id, prereq_kp_id) REFERENCES rag.rag_knowledge_point(tenant_id, kp_id) ON DELETE CASCADE;


--
-- Name: rag_access_audit; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_access_audit ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_answer_citation; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_answer_citation ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_access_audit rag_audit_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_audit_tenant_isolation ON rag.rag_access_audit USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_chunk; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_chunk ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_chunk rag_chunk_delete; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_chunk_delete ON rag.rag_chunk FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_chunk_embedding; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_chunk_embedding ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_chunk_embedding rag_chunk_embedding_delete; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_chunk_embedding_delete ON rag.rag_chunk_embedding FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_chunk_embedding rag_chunk_embedding_read; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_chunk_embedding_read ON rag.rag_chunk_embedding FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_chunk_embedding rag_chunk_embedding_update; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_chunk_embedding_update ON rag.rag_chunk_embedding FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_chunk_embedding rag_chunk_embedding_write; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_chunk_embedding_write ON rag.rag_chunk_embedding FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_chunk rag_chunk_read; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_chunk_read ON rag.rag_chunk FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_chunk rag_chunk_update; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_chunk_update ON rag.rag_chunk FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_chunk rag_chunk_write; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_chunk_write ON rag.rag_chunk FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_answer_citation rag_citation_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_citation_tenant_isolation ON rag.rag_answer_citation USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_dictionary; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_dictionary ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_dictionary rag_dictionary_delete; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_dictionary_delete ON rag.rag_dictionary FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_dictionary rag_dictionary_read; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_dictionary_read ON rag.rag_dictionary FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_dictionary rag_dictionary_update; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_dictionary_update ON rag.rag_dictionary FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_dictionary rag_dictionary_write; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_dictionary_write ON rag.rag_dictionary FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_document; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_document ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_document rag_document_delete; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_document_delete ON rag.rag_document FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_document rag_document_read; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_document_read ON rag.rag_document FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_document rag_document_update; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_document_update ON rag.rag_document FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_document rag_document_write; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_document_write ON rag.rag_document FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_edition; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_edition ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_edition rag_edition_delete; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_edition_delete ON rag.rag_edition FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_edition rag_edition_read; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_edition_read ON rag.rag_edition FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_edition rag_edition_update; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_edition_update ON rag.rag_edition FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_edition rag_edition_write; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_edition_write ON rag.rag_edition FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_ingest_job; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_ingest_job ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_ingest_job rag_ingest_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_ingest_tenant_isolation ON rag.rag_ingest_job USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_inspection_record; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_inspection_record ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_inspection_record rag_inspection_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_inspection_tenant_isolation ON rag.rag_inspection_record USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_kp_alias; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_kp_alias ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_kp_alias rag_kp_alias_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_kp_alias_tenant_isolation ON rag.rag_kp_alias USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_knowledge_point; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_knowledge_point ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_knowledge_point rag_knowledge_point_delete; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_knowledge_point_delete ON rag.rag_knowledge_point FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_knowledge_point rag_knowledge_point_read; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_knowledge_point_read ON rag.rag_knowledge_point FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_knowledge_point rag_knowledge_point_update; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_knowledge_point_update ON rag.rag_knowledge_point FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_knowledge_point rag_knowledge_point_write; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_knowledge_point_write ON rag.rag_knowledge_point FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_knowledge_prerequisite; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_knowledge_prerequisite ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_knowledge_prerequisite rag_knowledge_prerequisite_delete; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_knowledge_prerequisite_delete ON rag.rag_knowledge_prerequisite FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_knowledge_prerequisite rag_knowledge_prerequisite_read; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_knowledge_prerequisite_read ON rag.rag_knowledge_prerequisite FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_knowledge_prerequisite rag_knowledge_prerequisite_update; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_knowledge_prerequisite_update ON rag.rag_knowledge_prerequisite FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_knowledge_prerequisite rag_knowledge_prerequisite_write; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_knowledge_prerequisite_write ON rag.rag_knowledge_prerequisite FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_source; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_source ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_source rag_source_delete; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_source_delete ON rag.rag_source FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_source rag_source_read; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_source_read ON rag.rag_source FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_source rag_source_update; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_source_update ON rag.rag_source FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_source rag_source_write; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_source_write ON rag.rag_source FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- PostgreSQL database dump complete
--

\unrestrict t8pUS8At5cIIAhb4ncTUOYOc7ptKrO1SHfyUGaObtZaFz6gr6Q0tLJwttwkuYrK

