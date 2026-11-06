--
-- PostgreSQL database dump
--

\restrict ABVDtQXCn5tqZfBLcH5IHKZM5ZvV5MBz8HrBEan8wyExd81A4YRy0tRd3ddh4VO

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
-- Name: rag; Type: SCHEMA; Schema: -; Owner: postgres
--

CREATE SCHEMA rag;


ALTER SCHEMA rag OWNER TO postgres;

--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


--
-- Name: rag_chunk_role; Type: TYPE; Schema: rag; Owner: postgres
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


ALTER TYPE rag.rag_chunk_role OWNER TO postgres;

--
-- Name: rag_edition_scope_mode; Type: TYPE; Schema: rag; Owner: postgres
--

CREATE TYPE rag.rag_edition_scope_mode AS ENUM (
    'STRICT',
    'PREFERRED_WITH_FALLBACK',
    'CROSS_EDITION'
);


ALTER TYPE rag.rag_edition_scope_mode OWNER TO postgres;

--
-- Name: rag_grounding_verdict; Type: TYPE; Schema: rag; Owner: postgres
--

CREATE TYPE rag.rag_grounding_verdict AS ENUM (
    'GROUNDED',
    'PARTIALLY_GROUNDED',
    'UNGROUNDED',
    'CONFLICTING'
);


ALTER TYPE rag.rag_grounding_verdict OWNER TO postgres;

--
-- Name: rag_ingest_status; Type: TYPE; Schema: rag; Owner: postgres
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


ALTER TYPE rag.rag_ingest_status OWNER TO postgres;

--
-- Name: rag_license_status; Type: TYPE; Schema: rag; Owner: postgres
--

CREATE TYPE rag.rag_license_status AS ENUM (
    'LICENSED',
    'FAIR_USE_QUOTATION',
    'PENDING',
    'RESTRICTED'
);


ALTER TYPE rag.rag_license_status OWNER TO postgres;

--
-- Name: rag_retrieval_mode; Type: TYPE; Schema: rag; Owner: postgres
--

CREATE TYPE rag.rag_retrieval_mode AS ENUM (
    'EXPLAIN',
    'SOLVE',
    'VERIFY',
    'OVERVIEW'
);


ALTER TYPE rag.rag_retrieval_mode OWNER TO postgres;

--
-- Name: rag_trust_tier; Type: TYPE; Schema: rag; Owner: postgres
--

CREATE TYPE rag.rag_trust_tier AS ENUM (
    'OFFICIAL_PUBLISHER',
    'OFFICIAL_TUTORIAL',
    'PEER_REVIEWED_PAPER',
    'PREPRINT',
    'UNVERIFIED'
);


ALTER TYPE rag.rag_trust_tier OWNER TO postgres;

--
-- Name: rag_verification_method; Type: TYPE; Schema: rag; Owner: postgres
--

CREATE TYPE rag.rag_verification_method AS ENUM (
    'NONE',
    'ISBN',
    'DOI',
    'MANUAL_REVIEW',
    'OFFICIAL_HASH'
);


ALTER TYPE rag.rag_verification_method OWNER TO postgres;

--
-- Name: rag_verification_status; Type: TYPE; Schema: rag; Owner: postgres
--

CREATE TYPE rag.rag_verification_status AS ENUM (
    'PENDING',
    'IN_REVIEW',
    'VERIFIED',
    'REJECTED',
    'REVOKED'
);


ALTER TYPE rag.rag_verification_status OWNER TO postgres;

--
-- Name: forbid_rag_audit_mutation(); Type: FUNCTION; Schema: rag; Owner: postgres
--

CREATE FUNCTION rag.forbid_rag_audit_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'rag_access_audit 为追加写表，不允许 UPDATE/DELETE';
END;
$$;


ALTER FUNCTION rag.forbid_rag_audit_mutation() OWNER TO postgres;

--
-- Name: rag_current_tenant(); Type: FUNCTION; Schema: rag; Owner: postgres
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


ALTER FUNCTION rag.rag_current_tenant() OWNER TO postgres;

--
-- Name: FUNCTION rag_current_tenant(); Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON FUNCTION rag.rag_current_tenant() IS '会话租户上下文。未设置 → NULL（只见全局行）。显式把全局哨兵当租户身份 → 直接报错（越权企图，fail-loud 而非静默放行）。';


--
-- Name: rag_global_tenant_id(); Type: FUNCTION; Schema: rag; Owner: postgres
--

CREATE FUNCTION rag.rag_global_tenant_id() RETURNS uuid
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$ SELECT '00000000-0000-0000-0000-000000000000'::UUID $$;


ALTER FUNCTION rag.rag_global_tenant_id() OWNER TO postgres;

--
-- Name: FUNCTION rag_global_tenant_id(); Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON FUNCTION rag.rag_global_tenant_id() IS '全局作用域的保留租户 ID（哨兵）。真实租户永远取不到这个值。全局行 = tenant_id 等于本值 且 scope = ''PLATFORM''。';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: rag_access_audit; Type: TABLE; Schema: rag; Owner: postgres
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


ALTER TABLE rag.rag_access_audit OWNER TO postgres;

--
-- Name: rag_answer_citation; Type: TABLE; Schema: rag; Owner: postgres
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


ALTER TABLE rag.rag_answer_citation OWNER TO postgres;

--
-- Name: TABLE rag_answer_citation; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON TABLE rag.rag_answer_citation IS '引用溯源链路。逐字引文 + 版本快照 + 判定结论，使回答可回溯到具体位置并可复核。';


--
-- Name: rag_chunk; Type: TABLE; Schema: rag; Owner: postgres
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
    in_answer_zone boolean DEFAULT false NOT NULL,
    block_type text,
    matched_prefix text,
    CONSTRAINT rag_chunk_char_range_chk CHECK ((char_end >= char_start)),
    CONSTRAINT rag_chunk_char_start_chk CHECK ((char_start >= 0)),
    CONSTRAINT rag_chunk_page_chk CHECK ((page_number >= 1)),
    CONSTRAINT rag_chunk_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id = rag.rag_global_tenant_id())) OR ((scope = 'TENANT'::text) AND (tenant_id <> rag.rag_global_tenant_id()))))
);

ALTER TABLE ONLY rag.rag_chunk FORCE ROW LEVEL SECURITY;


ALTER TABLE rag.rag_chunk OWNER TO postgres;

--
-- Name: TABLE rag_chunk; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON TABLE rag.rag_chunk IS '语料片段。结构化切分的产物，**切片边界由版面结构决定**而非固定窗口。';


--
-- Name: COLUMN rag_chunk.tenant_id; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_chunk.tenant_id IS 'platform 权威租户 ID 的引用值。RAG 不存租户信息、不校验租户存在性；隔离由 RLS 保证。';


--
-- Name: COLUMN rag_chunk.scope; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_chunk.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: COLUMN rag_chunk.in_answer_zone; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_chunk.in_answer_zone IS '是否位于答案区（版面级判定快照）。⚠️ false 不等于"确认不在答案区"——需结合 block_type IS NULL 判断是否采集过。';


--
-- Name: COLUMN rag_chunk.block_type; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_chunk.block_type IS '版面块类型（BlockType 枚举名）。NULL = 该片段早于版面事实采集。';


--
-- Name: COLUMN rag_chunk.matched_prefix; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_chunk.matched_prefix IS '块首命中的解答前缀原文（如「解:」）。NULL = 未命中或未采集。';


--
-- Name: rag_chunk_embedding; Type: TABLE; Schema: rag; Owner: postgres
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


ALTER TABLE rag.rag_chunk_embedding OWNER TO postgres;

--
-- Name: TABLE rag_chunk_embedding; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON TABLE rag.rag_chunk_embedding IS '片段向量。索引代际（index_generation）支持换模型时的蓝绿切换与回滚。';


--
-- Name: COLUMN rag_chunk_embedding.scope; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_chunk_embedding.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: rag_dictionary; Type: TABLE; Schema: rag; Owner: postgres
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


ALTER TABLE rag.rag_dictionary OWNER TO postgres;

--
-- Name: COLUMN rag_dictionary.scope; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_dictionary.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 NULL，全租户只读可见）；TENANT=租户私有（tenant_id 必为非空，严格隔离）。''';


--
-- Name: rag_document; Type: TABLE; Schema: rag; Owner: postgres
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


ALTER TABLE rag.rag_document OWNER TO postgres;

--
-- Name: TABLE rag_document; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON TABLE rag.rag_document IS '语料的物理载体。与 source（作品）、edition（版本坐标）分层，支持同一版本下的多载体与单载体撤回。';


--
-- Name: COLUMN rag_document.retrievable; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_document.retrievable IS '载体级可检索标记。撤回时置 FALSE，实现「同版本下只下架单个载体」而不必物理 DELETE。';


--
-- Name: COLUMN rag_document.scope; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_document.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: rag_edition; Type: TABLE; Schema: rag; Owner: postgres
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


ALTER TABLE rag.rag_edition OWNER TO postgres;

--
-- Name: TABLE rag_edition; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON TABLE rag.rag_edition IS '版本坐标系。版本是五轴复合标识，缺一轴则引用不可验证。';


--
-- Name: COLUMN rag_edition.scope; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_edition.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: rag_index_generation; Type: TABLE; Schema: rag; Owner: postgres
--

CREATE TABLE rag.rag_index_generation (
    tenant_id uuid NOT NULL,
    generation integer NOT NULL,
    embed_model text NOT NULL,
    dimension integer NOT NULL,
    status text DEFAULT 'BUILDING'::text NOT NULL,
    indexed_chunks bigint DEFAULT 0 NOT NULL,
    total_chunks bigint DEFAULT 0 NOT NULL,
    note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    activated_at timestamp with time zone,
    retired_at timestamp with time zone,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    build_cursor text,
    CONSTRAINT rag_index_generation_gen_chk CHECK ((generation >= 1)),
    CONSTRAINT rag_index_generation_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id = rag.rag_global_tenant_id())) OR ((scope = 'TENANT'::text) AND (tenant_id <> rag.rag_global_tenant_id())))),
    CONSTRAINT rag_index_generation_status_chk CHECK ((status = ANY (ARRAY['BUILDING'::text, 'ACTIVE'::text, 'RETIRED'::text])))
);

ALTER TABLE ONLY rag.rag_index_generation FORCE ROW LEVEL SECURITY;


ALTER TABLE rag.rag_index_generation OWNER TO postgres;

--
-- Name: COLUMN rag_index_generation.build_cursor; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_index_generation.build_cursor IS '键集分页游标：上一个已处理的 chunk_id（text）。NULL = 尚未开始。⚠️ 不能用索引加偏移：构建期间片段增删会让 OFFSET 错位、跳过片段。';


--
-- Name: rag_ingest_job; Type: TABLE; Schema: rag; Owner: postgres
--

CREATE TABLE rag.rag_ingest_job (
    job_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    source_id uuid NOT NULL,
    status rag.rag_ingest_status DEFAULT 'PENDING'::rag.rag_ingest_status NOT NULL,
    stage text,
    attempt integer DEFAULT 0 NOT NULL,
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


ALTER TABLE rag.rag_ingest_job OWNER TO postgres;

--
-- Name: rag_inspection_record; Type: TABLE; Schema: rag; Owner: postgres
--

CREATE TABLE rag.rag_inspection_record (
    record_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    source_id uuid NOT NULL,
    chunk_id uuid NOT NULL,
    inspector_id uuid NOT NULL,
    expected_role text NOT NULL,
    actual_role text NOT NULL,
    error_type text,
    verdict text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    CONSTRAINT rag_inspection_error_type_chk CHECK (((error_type IS NULL) OR (error_type = ANY (ARRAY['ANSWER_LEAK'::text, 'EXERCISE_NO_LOST'::text, 'LOCATOR_WRONG'::text, 'CHUNK_BOUNDARY'::text, 'OTHER'::text])))),
    CONSTRAINT rag_inspection_verdict_chk CHECK ((verdict = ANY (ARRAY['PASS'::text, 'FAIL'::text]))),
    CONSTRAINT rag_inspection_verdict_error_ck CHECK ((((verdict = 'FAIL'::text) AND (error_type IS NOT NULL)) OR ((verdict = 'PASS'::text) AND (error_type IS NULL))))
);

ALTER TABLE ONLY rag.rag_inspection_record FORCE ROW LEVEL SECURITY;


ALTER TABLE rag.rag_inspection_record OWNER TO postgres;

--
-- Name: rag_knowledge_point; Type: TABLE; Schema: rag; Owner: postgres
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


ALTER TABLE rag.rag_knowledge_point OWNER TO postgres;

--
-- Name: TABLE rag_knowledge_point; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON TABLE rag.rag_knowledge_point IS '知识点本体。教辅语料的语义骨架，用于解决「讲解与练习碎片化」问题。';


--
-- Name: COLUMN rag_knowledge_point.scope; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_knowledge_point.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: rag_knowledge_prerequisite; Type: TABLE; Schema: rag; Owner: postgres
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


ALTER TABLE rag.rag_knowledge_prerequisite OWNER TO postgres;

--
-- Name: COLUMN rag_knowledge_prerequisite.scope; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_knowledge_prerequisite.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: rag_kp_alias; Type: TABLE; Schema: rag; Owner: postgres
--

CREATE TABLE rag.rag_kp_alias (
    alias_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    kp_id uuid NOT NULL,
    alias_text text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL
);

ALTER TABLE ONLY rag.rag_kp_alias FORCE ROW LEVEL SECURITY;


ALTER TABLE rag.rag_kp_alias OWNER TO postgres;

--
-- Name: rag_reviewer_roster; Type: TABLE; Schema: rag; Owner: postgres
--

CREATE TABLE rag.rag_reviewer_roster (
    tenant_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    display_name text NOT NULL,
    can_review boolean DEFAULT true NOT NULL,
    can_co_sign boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    scope text DEFAULT 'TENANT'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT rag_reviewer_roster_name_chk CHECK ((length(btrim(display_name)) > 0)),
    CONSTRAINT rag_reviewer_roster_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id = rag.rag_global_tenant_id())) OR ((scope = 'TENANT'::text) AND (tenant_id <> rag.rag_global_tenant_id())))),
    CONSTRAINT rag_reviewer_roster_sign_chk CHECK (((NOT can_co_sign) OR can_review))
);

ALTER TABLE ONLY rag.rag_reviewer_roster FORCE ROW LEVEL SECURITY;


ALTER TABLE rag.rag_reviewer_roster OWNER TO postgres;

--
-- Name: rag_source; Type: TABLE; Schema: rag; Owner: postgres
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
    inspection_status text DEFAULT 'NOT_REQUIRED'::text NOT NULL,
    CONSTRAINT rag_source_identifier_chk CHECK ((((trust_tier = ANY (ARRAY['OFFICIAL_PUBLISHER'::rag.rag_trust_tier, 'OFFICIAL_TUTORIAL'::rag.rag_trust_tier])) AND (isbn IS NOT NULL)) OR ((trust_tier = ANY (ARRAY['PEER_REVIEWED_PAPER'::rag.rag_trust_tier, 'PREPRINT'::rag.rag_trust_tier])) AND (doi IS NOT NULL)) OR (trust_tier = 'UNVERIFIED'::rag.rag_trust_tier))),
    CONSTRAINT rag_source_inspection_status_chk CHECK ((inspection_status = ANY (ARRAY['NOT_REQUIRED'::text, 'PASSED'::text, 'FAILED'::text]))),
    CONSTRAINT rag_source_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id = rag.rag_global_tenant_id())) OR ((scope = 'TENANT'::text) AND (tenant_id <> rag.rag_global_tenant_id())))),
    CONSTRAINT rag_source_ver_valid_days_chk CHECK ((ver_valid_days > 0)),
    CONSTRAINT rag_source_verified_requires_evidence_chk CHECK (((ver_status <> 'VERIFIED'::rag.rag_verification_status) OR ((ver_evidence IS NOT NULL) AND (verified_at IS NOT NULL))))
);

ALTER TABLE ONLY rag.rag_source FORCE ROW LEVEL SECURITY;


ALTER TABLE rag.rag_source OWNER TO postgres;

--
-- Name: TABLE rag_source; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON TABLE rag.rag_source IS '语料来源登记表。**未通过校验的来源不得进入可检索索引**（fail-closed）。';


--
-- Name: COLUMN rag_source.tenant_id; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_source.tenant_id IS 'platform 权威租户 ID 的引用值。RAG 不存租户信息、不校验租户存在性；隔离由 RLS 保证。';


--
-- Name: COLUMN rag_source.reviewer_id; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_source.reviewer_id IS '审核认领人。回答「这批语料卡在谁手上」，使 SLA 可度量。';


--
-- Name: COLUMN rag_source.ver_reject_reason; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_source.ver_reject_reason IS '拒绝理由。与 ver_evidence（成功证据）语义分离，避免混淆。';


--
-- Name: COLUMN rag_source.scope; Type: COMMENT; Schema: rag; Owner: postgres
--

COMMENT ON COLUMN rag.rag_source.scope IS '''作用域：PLATFORM=平台全局（tenant_id 恒为 rag_global_tenant_id() 哨兵，全租户只读可见）；TENANT=租户私有（严格隔离）。''';


--
-- Name: rag_access_audit rag_access_audit_pkey; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_access_audit
    ADD CONSTRAINT rag_access_audit_pkey PRIMARY KEY (audit_id);


--
-- Name: rag_answer_citation rag_answer_citation_pkey; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_answer_citation
    ADD CONSTRAINT rag_answer_citation_pkey PRIMARY KEY (citation_id);


--
-- Name: rag_chunk_embedding rag_chunk_embedding_gen_pkey; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_chunk_embedding
    ADD CONSTRAINT rag_chunk_embedding_gen_pkey PRIMARY KEY (tenant_id, chunk_id, index_generation);


--
-- Name: rag_chunk rag_chunk_pkey; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_chunk
    ADD CONSTRAINT rag_chunk_pkey PRIMARY KEY (chunk_id);


--
-- Name: rag_chunk rag_chunk_tenant_chunk_unique; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_chunk
    ADD CONSTRAINT rag_chunk_tenant_chunk_unique UNIQUE (tenant_id, chunk_id);


--
-- Name: rag_dictionary rag_dictionary_pkey; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_dictionary
    ADD CONSTRAINT rag_dictionary_pkey PRIMARY KEY (dict_id);


--
-- Name: rag_dictionary rag_dictionary_tenant_id_dict_type_code_key; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_dictionary
    ADD CONSTRAINT rag_dictionary_tenant_id_dict_type_code_key UNIQUE (tenant_id, dict_type, code);


--
-- Name: rag_document rag_document_pkey; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_document
    ADD CONSTRAINT rag_document_pkey PRIMARY KEY (document_id);


--
-- Name: rag_document rag_document_tenant_document_unique; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_document
    ADD CONSTRAINT rag_document_tenant_document_unique UNIQUE (tenant_id, document_id);


--
-- Name: rag_edition rag_edition_pkey; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_edition
    ADD CONSTRAINT rag_edition_pkey PRIMARY KEY (edition_id);


--
-- Name: rag_edition rag_edition_tenant_edition_unique; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_edition
    ADD CONSTRAINT rag_edition_tenant_edition_unique UNIQUE (tenant_id, edition_id);


--
-- Name: rag_edition rag_edition_unique; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_edition
    ADD CONSTRAINT rag_edition_unique UNIQUE (tenant_id, subject, grade, term, publisher, revision_code);


--
-- Name: rag_index_generation rag_index_generation_pk; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_index_generation
    ADD CONSTRAINT rag_index_generation_pk PRIMARY KEY (tenant_id, generation);


--
-- Name: rag_ingest_job rag_ingest_job_pkey; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_ingest_job
    ADD CONSTRAINT rag_ingest_job_pkey PRIMARY KEY (job_id);


--
-- Name: rag_inspection_record rag_inspection_record_pkey; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_inspection_record
    ADD CONSTRAINT rag_inspection_record_pkey PRIMARY KEY (record_id);


--
-- Name: rag_knowledge_point rag_knowledge_point_pkey; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_knowledge_point
    ADD CONSTRAINT rag_knowledge_point_pkey PRIMARY KEY (kp_id);


--
-- Name: rag_knowledge_prerequisite rag_knowledge_prerequisite_pkey; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_knowledge_prerequisite
    ADD CONSTRAINT rag_knowledge_prerequisite_pkey PRIMARY KEY (tenant_id, kp_id, prereq_kp_id);


--
-- Name: rag_kp_alias rag_kp_alias_pkey; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_kp_alias
    ADD CONSTRAINT rag_kp_alias_pkey PRIMARY KEY (alias_id);


--
-- Name: rag_kp_alias rag_kp_alias_unique; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_kp_alias
    ADD CONSTRAINT rag_kp_alias_unique UNIQUE (tenant_id, kp_id, alias_text);


--
-- Name: rag_knowledge_point rag_kp_tenant_kp_unique; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_knowledge_point
    ADD CONSTRAINT rag_kp_tenant_kp_unique UNIQUE (tenant_id, kp_id);


--
-- Name: rag_knowledge_point rag_kp_unique_name; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_knowledge_point
    ADD CONSTRAINT rag_kp_unique_name UNIQUE (tenant_id, subject, grade, canonical_name);


--
-- Name: rag_reviewer_roster rag_reviewer_roster_pk; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_reviewer_roster
    ADD CONSTRAINT rag_reviewer_roster_pk PRIMARY KEY (tenant_id, principal_id);


--
-- Name: rag_source rag_source_pkey; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_source
    ADD CONSTRAINT rag_source_pkey PRIMARY KEY (source_id);


--
-- Name: rag_source rag_source_unique; Type: CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_source
    ADD CONSTRAINT rag_source_unique UNIQUE (tenant_id, source_id);


--
-- Name: rag_audit_tenant_occurred_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_audit_tenant_occurred_idx ON rag.rag_access_audit USING btree (tenant_id, occurred_at DESC);


--
-- Name: rag_audit_tenant_source_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_audit_tenant_source_idx ON rag.rag_access_audit USING btree (tenant_id, source_id, occurred_at DESC);


--
-- Name: rag_chunk_answer_zone_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_chunk_answer_zone_idx ON rag.rag_chunk USING btree (tenant_id, document_id, in_answer_zone) WHERE (in_answer_zone = true);


--
-- Name: rag_chunk_document_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_chunk_document_idx ON rag.rag_chunk USING btree (tenant_id, document_id);


--
-- Name: rag_chunk_embedding_gen_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_chunk_embedding_gen_idx ON rag.rag_chunk_embedding USING btree (tenant_id, index_generation);


--
-- Name: rag_chunk_jieba_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_chunk_jieba_idx ON rag.rag_chunk USING gin (to_tsvector('simple'::regconfig, text_jieba));


--
-- Name: rag_chunk_kp_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_chunk_kp_idx ON rag.rag_chunk USING gin (kp_ids);


--
-- Name: rag_chunk_retrievable_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_chunk_retrievable_idx ON rag.rag_chunk USING btree (tenant_id, page_number) WHERE (role <> ALL (ARRAY['EXERCISE_ANSWER'::rag.rag_chunk_role, 'EXAMPLE_SOLUTION'::rag.rag_chunk_role, 'BOILERPLATE'::rag.rag_chunk_role]));


--
-- Name: rag_chunk_tenant_edition_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_chunk_tenant_edition_idx ON rag.rag_chunk USING btree (tenant_id, edition_id);


--
-- Name: rag_chunk_tenant_role_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_chunk_tenant_role_idx ON rag.rag_chunk USING btree (tenant_id, role);


--
-- Name: rag_citation_run_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_citation_run_idx ON rag.rag_answer_citation USING btree (tenant_id, run_id);


--
-- Name: rag_citation_tenant_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_citation_tenant_idx ON rag.rag_answer_citation USING btree (tenant_id, created_at DESC);


--
-- Name: rag_dictionary_tenant_type_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_dictionary_tenant_type_idx ON rag.rag_dictionary USING btree (tenant_id, dict_type, sort_order, code);


--
-- Name: rag_document_retrievable_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_document_retrievable_idx ON rag.rag_document USING btree (tenant_id, edition_id) WHERE retrievable;


--
-- Name: rag_document_tenant_content_hash_uq; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE UNIQUE INDEX rag_document_tenant_content_hash_uq ON rag.rag_document USING btree (tenant_id, content_hash) WHERE ((ver_status <> 'REVOKED'::rag.rag_verification_status) AND (parse_status <> 'FAILED'::text));


--
-- Name: rag_document_tenant_source_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_document_tenant_source_idx ON rag.rag_document USING btree (tenant_id, source_id);


--
-- Name: rag_index_generation_active_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_index_generation_active_idx ON rag.rag_index_generation USING btree (tenant_id, status);


--
-- Name: rag_index_generation_pending_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_index_generation_pending_idx ON rag.rag_index_generation USING btree (tenant_id, generation) WHERE (status = 'BUILDING'::text);


--
-- Name: rag_ingest_job_status_created_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_ingest_job_status_created_idx ON rag.rag_ingest_job USING btree (status, created_at);


--
-- Name: rag_ingest_job_tenant_status_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_ingest_job_tenant_status_idx ON rag.rag_ingest_job USING btree (tenant_id, status);


--
-- Name: rag_inspection_record_source_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_inspection_record_source_idx ON rag.rag_inspection_record USING btree (tenant_id, source_id, created_at DESC);


--
-- Name: rag_kp_alias_kp_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_kp_alias_kp_idx ON rag.rag_kp_alias USING btree (tenant_id, kp_id);


--
-- Name: rag_reviewer_roster_active_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_reviewer_roster_active_idx ON rag.rag_reviewer_roster USING btree (tenant_id, can_co_sign, enabled) WHERE (enabled = true);


--
-- Name: rag_source_tenant_doi_uq; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE UNIQUE INDEX rag_source_tenant_doi_uq ON rag.rag_source USING btree (tenant_id, doi) WHERE ((doi IS NOT NULL) AND (ver_status <> 'REVOKED'::rag.rag_verification_status));


--
-- Name: rag_source_tenant_isbn_uq; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE UNIQUE INDEX rag_source_tenant_isbn_uq ON rag.rag_source USING btree (tenant_id, isbn) WHERE ((isbn IS NOT NULL) AND (ver_status <> 'REVOKED'::rag.rag_verification_status));


--
-- Name: rag_source_tenant_trust_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_source_tenant_trust_idx ON rag.rag_source USING btree (tenant_id, trust_tier);


--
-- Name: rag_source_tenant_ver_status_idx; Type: INDEX; Schema: rag; Owner: postgres
--

CREATE INDEX rag_source_tenant_ver_status_idx ON rag.rag_source USING btree (tenant_id, ver_status, created_at DESC);


--
-- Name: rag_access_audit trg_rag_audit_append_only; Type: TRIGGER; Schema: rag; Owner: postgres
--

CREATE TRIGGER trg_rag_audit_append_only BEFORE DELETE OR UPDATE ON rag.rag_access_audit FOR EACH ROW EXECUTE FUNCTION rag.forbid_rag_audit_mutation();


--
-- Name: rag_chunk rag_chunk_document_fk; Type: FK CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_chunk
    ADD CONSTRAINT rag_chunk_document_fk FOREIGN KEY (tenant_id, document_id) REFERENCES rag.rag_document(tenant_id, document_id) ON DELETE CASCADE;


--
-- Name: rag_chunk rag_chunk_edition_fk; Type: FK CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_chunk
    ADD CONSTRAINT rag_chunk_edition_fk FOREIGN KEY (tenant_id, edition_id) REFERENCES rag.rag_edition(tenant_id, edition_id) ON DELETE CASCADE;


--
-- Name: rag_answer_citation rag_citation_chunk_fk; Type: FK CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_answer_citation
    ADD CONSTRAINT rag_citation_chunk_fk FOREIGN KEY (chunk_id) REFERENCES rag.rag_chunk(chunk_id) ON DELETE CASCADE;


--
-- Name: rag_document rag_document_edition_fk; Type: FK CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_document
    ADD CONSTRAINT rag_document_edition_fk FOREIGN KEY (tenant_id, edition_id) REFERENCES rag.rag_edition(tenant_id, edition_id) ON DELETE CASCADE;


--
-- Name: rag_document rag_document_source_fk; Type: FK CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_document
    ADD CONSTRAINT rag_document_source_fk FOREIGN KEY (tenant_id, source_id) REFERENCES rag.rag_source(tenant_id, source_id) ON DELETE CASCADE;


--
-- Name: rag_edition rag_edition_source_fk; Type: FK CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_edition
    ADD CONSTRAINT rag_edition_source_fk FOREIGN KEY (tenant_id, source_id) REFERENCES rag.rag_source(tenant_id, source_id) ON DELETE CASCADE;


--
-- Name: rag_chunk_embedding rag_embedding_chunk_fk; Type: FK CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_chunk_embedding
    ADD CONSTRAINT rag_embedding_chunk_fk FOREIGN KEY (tenant_id, chunk_id) REFERENCES rag.rag_chunk(tenant_id, chunk_id) ON DELETE CASCADE;


--
-- Name: rag_ingest_job rag_ingest_source_fk; Type: FK CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_ingest_job
    ADD CONSTRAINT rag_ingest_source_fk FOREIGN KEY (tenant_id, source_id) REFERENCES rag.rag_source(tenant_id, source_id) ON DELETE CASCADE;


--
-- Name: rag_knowledge_prerequisite rag_kp_prereq_kp_fk; Type: FK CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_knowledge_prerequisite
    ADD CONSTRAINT rag_kp_prereq_kp_fk FOREIGN KEY (tenant_id, kp_id) REFERENCES rag.rag_knowledge_point(tenant_id, kp_id) ON DELETE CASCADE;


--
-- Name: rag_knowledge_prerequisite rag_kp_prereq_prereq_fk; Type: FK CONSTRAINT; Schema: rag; Owner: postgres
--

ALTER TABLE ONLY rag.rag_knowledge_prerequisite
    ADD CONSTRAINT rag_kp_prereq_prereq_fk FOREIGN KEY (tenant_id, prereq_kp_id) REFERENCES rag.rag_knowledge_point(tenant_id, kp_id) ON DELETE CASCADE;


--
-- Name: rag_access_audit; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_access_audit ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_answer_citation; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_answer_citation ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_access_audit rag_audit_tenant_isolation; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_audit_tenant_isolation ON rag.rag_access_audit USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_chunk; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_chunk ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_chunk rag_chunk_delete; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_chunk_delete ON rag.rag_chunk FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_chunk_embedding; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_chunk_embedding ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_chunk_embedding rag_chunk_embedding_delete; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_chunk_embedding_delete ON rag.rag_chunk_embedding FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_chunk_embedding rag_chunk_embedding_read; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_chunk_embedding_read ON rag.rag_chunk_embedding FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_chunk_embedding rag_chunk_embedding_update; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_chunk_embedding_update ON rag.rag_chunk_embedding FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_chunk_embedding rag_chunk_embedding_write; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_chunk_embedding_write ON rag.rag_chunk_embedding FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_chunk rag_chunk_read; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_chunk_read ON rag.rag_chunk FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_chunk rag_chunk_update; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_chunk_update ON rag.rag_chunk FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_chunk rag_chunk_write; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_chunk_write ON rag.rag_chunk FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_answer_citation rag_citation_tenant_isolation; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_citation_tenant_isolation ON rag.rag_answer_citation USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_dictionary; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_dictionary ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_dictionary rag_dictionary_delete; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_dictionary_delete ON rag.rag_dictionary FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_dictionary rag_dictionary_read; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_dictionary_read ON rag.rag_dictionary FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_dictionary rag_dictionary_update; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_dictionary_update ON rag.rag_dictionary FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_dictionary rag_dictionary_write; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_dictionary_write ON rag.rag_dictionary FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_document; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_document ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_document rag_document_delete; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_document_delete ON rag.rag_document FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_document rag_document_read; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_document_read ON rag.rag_document FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_document rag_document_update; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_document_update ON rag.rag_document FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_document rag_document_write; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_document_write ON rag.rag_document FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_edition; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_edition ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_edition rag_edition_delete; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_edition_delete ON rag.rag_edition FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_edition rag_edition_read; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_edition_read ON rag.rag_edition FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_edition rag_edition_update; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_edition_update ON rag.rag_edition FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_edition rag_edition_write; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_edition_write ON rag.rag_edition FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_index_generation; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_index_generation ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_index_generation rag_index_generation_read; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_index_generation_read ON rag.rag_index_generation FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_index_generation rag_index_generation_update; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_index_generation_update ON rag.rag_index_generation FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_index_generation rag_index_generation_write; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_index_generation_write ON rag.rag_index_generation FOR INSERT WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_ingest_job; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_ingest_job ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_ingest_job rag_ingest_tenant_isolation; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_ingest_tenant_isolation ON rag.rag_ingest_job USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_inspection_record; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_inspection_record ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_inspection_record rag_inspection_tenant_isolation; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_inspection_tenant_isolation ON rag.rag_inspection_record USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_knowledge_point; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_knowledge_point ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_knowledge_point rag_knowledge_point_delete; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_knowledge_point_delete ON rag.rag_knowledge_point FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_knowledge_point rag_knowledge_point_read; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_knowledge_point_read ON rag.rag_knowledge_point FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_knowledge_point rag_knowledge_point_update; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_knowledge_point_update ON rag.rag_knowledge_point FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_knowledge_point rag_knowledge_point_write; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_knowledge_point_write ON rag.rag_knowledge_point FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_knowledge_prerequisite; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_knowledge_prerequisite ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_knowledge_prerequisite rag_knowledge_prerequisite_delete; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_knowledge_prerequisite_delete ON rag.rag_knowledge_prerequisite FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_knowledge_prerequisite rag_knowledge_prerequisite_read; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_knowledge_prerequisite_read ON rag.rag_knowledge_prerequisite FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_knowledge_prerequisite rag_knowledge_prerequisite_update; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_knowledge_prerequisite_update ON rag.rag_knowledge_prerequisite FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_knowledge_prerequisite rag_knowledge_prerequisite_write; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_knowledge_prerequisite_write ON rag.rag_knowledge_prerequisite FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_kp_alias; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_kp_alias ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_kp_alias rag_kp_alias_tenant_isolation; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_kp_alias_tenant_isolation ON rag.rag_kp_alias USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_reviewer_roster; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_reviewer_roster ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_reviewer_roster rag_reviewer_roster_read; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_reviewer_roster_read ON rag.rag_reviewer_roster FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_reviewer_roster rag_reviewer_roster_update; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_reviewer_roster_update ON rag.rag_reviewer_roster FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_reviewer_roster rag_reviewer_roster_write; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_reviewer_roster_write ON rag.rag_reviewer_roster FOR INSERT WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_source; Type: ROW SECURITY; Schema: rag; Owner: postgres
--

ALTER TABLE rag.rag_source ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_source rag_source_delete; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_source_delete ON rag.rag_source FOR DELETE USING ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_source rag_source_read; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_source_read ON rag.rag_source FOR SELECT USING ((tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()])));


--
-- Name: rag_source rag_source_update; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_source_update ON rag.rag_source FOR UPDATE USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: rag_source rag_source_write; Type: POLICY; Schema: rag; Owner: postgres
--

CREATE POLICY rag_source_write ON rag.rag_source FOR INSERT WITH CHECK (((tenant_id = rag.rag_current_tenant()) AND (scope = 'TENANT'::text)));


--
-- Name: SCHEMA public; Type: ACL; Schema: -; Owner: pg_database_owner
--

GRANT USAGE ON SCHEMA public TO rag_app;


--
-- Name: SCHEMA rag; Type: ACL; Schema: -; Owner: postgres
--

GRANT USAGE ON SCHEMA rag TO rag_app;


--
-- Name: TABLE rag_access_audit; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_access_audit TO rag_app;


--
-- Name: TABLE rag_answer_citation; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_answer_citation TO rag_app;


--
-- Name: TABLE rag_chunk; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_chunk TO rag_app;


--
-- Name: TABLE rag_chunk_embedding; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_chunk_embedding TO rag_app;


--
-- Name: TABLE rag_dictionary; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_dictionary TO rag_app;


--
-- Name: TABLE rag_document; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_document TO rag_app;


--
-- Name: TABLE rag_edition; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_edition TO rag_app;


--
-- Name: TABLE rag_index_generation; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_index_generation TO rag_app;


--
-- Name: TABLE rag_ingest_job; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_ingest_job TO rag_app;


--
-- Name: TABLE rag_inspection_record; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_inspection_record TO rag_app;


--
-- Name: TABLE rag_knowledge_point; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_knowledge_point TO rag_app;


--
-- Name: TABLE rag_knowledge_prerequisite; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_knowledge_prerequisite TO rag_app;


--
-- Name: TABLE rag_kp_alias; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_kp_alias TO rag_app;


--
-- Name: TABLE rag_reviewer_roster; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_reviewer_roster TO rag_app;


--
-- Name: TABLE rag_source; Type: ACL; Schema: rag; Owner: postgres
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE rag.rag_source TO rag_app;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT SELECT,USAGE ON SEQUENCES TO rag_app;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT SELECT,INSERT,DELETE,UPDATE ON TABLES TO rag_app;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: rag; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA rag GRANT SELECT,USAGE ON SEQUENCES TO rag_app;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: rag; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA rag GRANT SELECT,INSERT,DELETE,UPDATE ON TABLES TO rag_app;


--
-- PostgreSQL database dump complete
--

\unrestrict ABVDtQXCn5tqZfBLcH5IHKZM5ZvV5MBz8HrBEan8wyExd81A4YRy0tRd3ddh4VO

