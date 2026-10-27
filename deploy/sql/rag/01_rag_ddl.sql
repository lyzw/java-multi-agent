-- =====================================================================
-- rag 完整 DDL —— 生产环境初始化用（V1…V8 应用后的结构快照）
-- =====================================================================
--
-- 【用途】
--   建出 RAG 模块的全部数据库对象（schema rag / 扩展 / 枚举类型 / 函数 /
--   表 / 索引 / 约束 / RLS 策略 / 注释）。
--
-- 【来源与可信度】
--   2026-10-27 重新导出：① 在 **agent-pg17（PostgreSQL 17.11 + pgvector）**
--   上建空库 rag_ddl_build；② 按序执行仓库迁移脚本
--   rag/rag-infrastructure/src/main/resources/db/migration/V1 … V8（全部
--   8 个，零报错通过）；③ `pg_dump -n rag --schema-only --no-owner
--   --no-privileges` 导出；④ 删除 pg_dump 的 `\restrict` / `\unrestrict`
--   两行（那是 psql 17.10+ 的元命令，旧版 psql 会当成语法错误）；
--   ⑤ **手工补回两个 public 扩展**——`-n rag` 不导出 public 里的对象，
--   而 `rag_chunk_embedding.embedding` 依赖 `public.vector(1024)`，
--   空库上直接执行会在建表处报 `type "public.vector" does not exist`
--   （2026-10-27 实跑撞到，不是推测）。
--   除这 2 条 CREATE EXTENSION 外，本文件正文与 pg_dump 输出逐字一致。
--   故本文件与仓库迁移脚本**严格同源**——迁移脚本变了，本文件必须重新导出。
--   （此前该文件来自阿里云 RDS 18.6 的导出，V7/V8 段落是手工补写的，
--    与迁移脚本存在漂移风险；本次改为本地实跑导出，漂移面归零。）
--
-- 【⚠️ 阿里云 RDS 的唯一必要改动：HNSW 算子类】
--   本文件按 vanilla PG 生成，索引用的是标准算子类：
--       CREATE INDEX rag_chunk_embedding_hnsw_idx ON rag.rag_chunk_embedding
--           USING hnsw (embedding public.vector_cosine_ops) ...
--   阿里云 pgvector 把该算子改名提供为 `public.rds_vector_cosine_ops`，
--   在 RDS 上直接执行本文件会在这一行报
--   `operator class "vector_cosine_ops" does not exist`。
--   → 在 RDS 上执行前把 `public.vector_cosine_ops` 替换为
--     `public.rds_vector_cosine_ops`（只此一处，其余段落两边通用）。
--
-- 【对象清单（2026-10-27 本地空库实跑核验，非估算）】
--   schema 1 个（rag）· 表 11 张 · 枚举类型 9 个 · 函数 3 个 · RLS 策略 35 条
--   · 扩展 2 个（vector / pgcrypto）· 带 scope 判别列的表 8 张
--   ⚠️ 策略数 11 →（V7）14 →（V8）35 的来源：
--     V7 把 rag_dictionary 的一条混合策略换成读写分四条（+3）；
--     V8 把 7 张语料链表（source/edition/document/chunk/chunk_embedding/
--     knowledge_point/knowledge_prerequisite）各自的 1 条同样拆成 4 条
--     （-7 +28）。35 = 14 + 21。
--   ⚠️ 函数从 2 个变 3 个：V8 新增 `rag.rag_global_tenant_id()`（全局作用域
--     的哨兵租户 ID），并把 `rag_current_tenant()` 加固为
--     「会话身份等于哨兵时直接抛错」——这是全局/租户双作用域唯一的攻击面。
--
-- 【fail-closed 语义在 V8 后的重述】
--   原底线：「未建租户上下文 = 全库不可见」。
--   新底线：「未建租户上下文 = 只见 scope='PLATFORM' 的行」。
--   这不是放宽：可见集合只有平台主动发布的那几行，任何租户私有行仍一行不见。
--   启动自检（RagStartupSelfCheck）与 worker 预检（ReindexRunner）已同步改述为
--   `count(*) … WHERE scope <> 'PLATFORM'` = 0；若这里仍按旧口径写探针，
--   全局语料一上线就会把正常库判成泄漏而拒绝启动。
--
-- 【⚠️ 关键前置：search_path】
--   RAG 全部对象位于**独立 schema `rag`**，而应用 SQL 一律用**未限定表名**
--   （FROM rag_chunk / JOIN rag_edition / ?::rag_chunk_role /
--   rag_current_tenant() / rag_global_tenant_id()）。因此**每条连接都必须把
--   search_path 指向 rag**，否则未限定名解析不到对象，
--   表现为 "relation does not exist" / "function does not exist"。
--   应用侧由 Hikari 的 connection-init-sql 设置；本文件末尾的校验段
--   也需先 `SET search_path TO rag, public;`。
--
--   ⚠️ 另一个坑：本文件里 `SELECT pg_catalog.set_config('search_path', '', false);`
--      是 pg_dump 的标准前置（对象一律带 `rag.` 限定），**不要删**；
--      删了后续 CREATE TYPE 会落到 public。
--
-- 【与 Flyway 的关系】
--   两种初始化路径**二选一**，不要混跑：
--     * 应用启动自动建库（推荐，日常/CI）→ 只配 Flyway，本文件不参与；
--     * 生产手工/离线初始化 → 执行本文件（Flyway 之后会把 V1…V8 记为
--       已应用需要 baseline，或干脆不启 Flyway；否则
--       `Detected applied migration not resolved locally` /
--       重跑 V1 时 CREATE TYPE 冲突直接失败）。
--
-- 【幂等性】
--   本文件**不是**幂等的（无 DROP 保护），且**会**在已装扩展的库上跳过扩展
--   创建（IF NOT EXISTS）。目标库应为空库。
--
-- 【执行方式】
--   psql -h <host> -p <port> -U <超级用户> -d <target_db> \
--        -v ON_ERROR_STOP=1 -f 01_rag_ddl.sql
--
--   ⚠️ ON_ERROR_STOP=1 必加（理由同 platform DDL）。
-- =====================================================================

--
-- PostgreSQL database dump
--


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
-- Name: rag; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA rag;

-- ── [手工补写] public schema 的两个扩展 ─────────────────────────────
-- pg_dump 只带 -n rag，扩展在 public 里因此**不在导出范围内**；
-- 而 rag_chunk_embedding.embedding 依赖 public.vector(1024)——缺这两条，
-- 本文件在空库上会停在 CREATE TABLE rag_chunk_embedding：
--   ERROR: type "public.vector" does not exist（2026-10-27 实跑撞到）
-- ⚠️ 两者均非 trusted 扩展，需要超级用户执行（见 03_rag_roles.sql）。
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


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
    embedding public.vector(1024) NOT NULL,
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
-- Name: rag_ingest_job; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_ingest_job (
    job_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    source_id uuid NOT NULL,
    status rag.rag_ingest_status DEFAULT 'PENDING'::rag.rag_ingest_status NOT NULL,
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
    CONSTRAINT rag_source_identifier_chk CHECK ((((trust_tier = ANY (ARRAY['OFFICIAL_PUBLISHER'::rag.rag_trust_tier, 'OFFICIAL_TUTORIAL'::rag.rag_trust_tier])) AND (isbn IS NOT NULL)) OR ((trust_tier = ANY (ARRAY['PEER_REVIEWED_PAPER'::rag.rag_trust_tier, 'PREPRINT'::rag.rag_trust_tier])) AND (doi IS NOT NULL)) OR (trust_tier = 'UNVERIFIED'::rag.rag_trust_tier))),
    CONSTRAINT rag_source_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id = rag.rag_global_tenant_id())) OR ((scope = 'TENANT'::text) AND (tenant_id <> rag.rag_global_tenant_id())))),
    CONSTRAINT rag_source_ver_valid_days_chk CHECK ((ver_valid_days > 0)),
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

CREATE INDEX rag_chunk_embedding_hnsw_idx ON rag.rag_chunk_embedding USING hnsw (embedding public.vector_cosine_ops) WITH (m='16', ef_construction='64');


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



-- =====================================================================
-- ══ 校验 ══
-- =====================================================================
-- ⚠️ 期望值全部来自 2026-10-27 的实跑导出（rag_ddl_build，V1→V8），
--    不是推算。跑出来的数字若不符，说明本文件与迁移脚本已漂移。

-- ① 表应为 11 张，且全部落在 schema rag
SELECT count(*) AS rag表数量_期望11 FROM pg_tables WHERE schemaname = 'rag';

-- ② 扩展版本（vector 应 ≥ 0.8，ivfflat/hnsw 索引依赖它）
SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector','pgcrypto');

-- ③ 枚举类型应为 9 个
SELECT count(*) AS 枚举类型数_期望9 FROM pg_type t
  JOIN pg_namespace n ON n.oid = t.typnamespace
 WHERE n.nspname = 'rag' AND t.typtype = 'e';

-- ④ RLS 策略应为 35 条（V7 拆分字典 +3，V8 拆分 7 张语料表 +21）
SELECT count(*) AS 策略数_期望35 FROM pg_policies WHERE schemaname = 'rag';

-- ⑤ scope 判别列应落在 8 张表（7 张语料链 + rag_dictionary）
SELECT count(*) AS scope列数_期望8 FROM information_schema.columns
 WHERE table_schema = 'rag' AND column_name = 'scope';

-- ⑥ 双作用域的两个函数都必须在：V8 若没应用，应用侧 SQL 会直接
--    "function rag_global_tenant_id() does not exist"（硬失败，不会静默）
SET search_path TO rag, public;
SELECT count(*) AS 函数数_期望3 FROM pg_proc p
  JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'rag'
   AND p.proname IN ('rag_current_tenant','rag_global_tenant_id','forbid_rag_audit_mutation');

-- ⑦ 全局哨兵的值（与 Java 侧 RagScope.GLOBAL_TENANT_ID 必须同值；
--    漂移的后果是「全局行在应用眼里成了某个普通租户的私有行」）
SELECT rag_global_tenant_id() AS 全局哨兵_期望全零UUID;

-- ⑧ rag_current_tenant() 必须被加固：会话身份等于哨兵时**抛错**而非返回该值
--    （V8 §1 封堵「租户伪装成平台身份读写全局桶」这条唯一的新攻击面）。
--    ⚠️ 用 DO 块捕获：直接写那条 SELECT 会在 ON_ERROR_STOP=1 下中断整个脚本，
--       而「报错」本来才是这里的期望结果。
DO $$
DECLARE got text;
BEGIN
    PERFORM set_config('app.tenant_id', rag_global_tenant_id()::text, false);
    BEGIN
        SELECT rag_current_tenant() INTO got;
        RAISE WARNING '加固缺失：rag_current_tenant() 接受了哨兵身份（返回 %）'
                      ' —— 租户可伪装平台身份读写全局桶', got;
    EXCEPTION WHEN others THEN
        RAISE WARNING '加固生效：%（期望：禁止把全局哨兵租户…作为会话身份）', SQLERRM;
    END;
END
$$;
SELECT set_config('app.tenant_id', '', false);

-- ⑨ 未设置 app.tenant_id 时返回 NULL（fail-closed：NULL 让所有租户相等策略不匹配）
SELECT rag_current_tenant() AS 未设置租户上下文时应为NULL;

-- ⑩ 写/删策略一律不得放行全局桶：出现一条就是「任一租户可发布/删除全局内容」
SELECT count(*) AS 越权写策略数_期望0 FROM pg_policies
 WHERE schemaname = 'rag' AND cmd IN ('INSERT','DELETE')
   AND (qual LIKE '%rag_global_tenant_id%' OR with_check LIKE '%rag_global_tenant_id%');

-- ⑪ 全部 11 张表必须 FORCE ROW LEVEL SECURITY
--    （表 owner / Flyway 所用角色不是超级用户时，无 FORCE 即可绕过 RLS）
SELECT count(*) AS 未强制RLS的表_期望0
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'rag' AND c.relkind = 'r'
   AND NOT (c.relrowsecurity AND c.relforcerowsecurity);

-- ⚠️ **红线检查（必做）**：应用连接角色必须是 **rag_app**
--    （NOSUPERUSER + NOBYPASSRLS）。带 BYPASSRLS 的角色会绕过 RLS，
--    租户隔离形同虚设，而 rag-bootstrap 的 RagStartupSelfCheck 会因此
--    拒绝启动——那是硬性保护，不要试图绕过。
--    ⚠️ 上面 ⑦⑧⑩⑪ 的检查若以超级用户执行会全部失真（超级用户绕过 RLS），
--       结构类断言（①–⑦）用超级用户跑，行为类断言（⑧⑩⑪与红线检查）
--       请用真实应用角色复跑一遍。
SELECT current_user, rolsuper, rolbypassrls
  FROM pg_roles WHERE rolname = current_user;
