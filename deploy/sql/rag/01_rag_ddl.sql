-- =====================================================================
-- rag 完整 DDL —— 生产环境初始化用
-- =====================================================================
--
-- 【用途】
--   建出 RAG 模块的全部数据库对象（schema rag / 扩展 / 枚举类型 / 函数 /
--   表 / 索引 / 约束 / RLS 策略 / 注释）。
--
-- 【来源与可信度】
--   取自远程 RDS（rm-cn-hhf50fbhh0001uzo.rwlb.rds.aliyuncs.com:5432，
--   PostgreSQL 18.6），做法是——
--     ① 在该实例上建**空库** rag_ddl_build；
--     ② 顺序执行仓库迁移脚本 rag/rag-infrastructure/src/main/resources/
--        db/migration/V1 … V6（全部 6 个，均零报错通过）；
--     ③ pg_dump --schema-only 导出。
--   故本文件与仓库迁移脚本**严格同源**——迁移脚本变了，本文件必须重新导出。
--
--   ⚠️ **零漂移已核验**：与线上 study_rag（Flyway 已应用到 V5）逐列比对，
--      差异为 0；本文件多出的一张表 rag_dictionary 正是线上**尚未执行**的
--      V6（2026-10-23 新增迁移，尚未提交版本库）。
--      → 若要以线上现状为准（不含 V6），删掉 rag_dictionary 相关段落即可。
--
-- 【对象清单（2026-10-23 远程空库实跑核验，非估算）】
--   schema 1 个（rag）· 表 11 张 · 枚举类型 9 个 · 函数 2 个
--   RLS 策略 11 条 · 扩展 2 个（vector 0.8.1.3 / pgcrypto 1.4）
--
-- 【⚠️ 关键前置：search_path】
--   RAG 全部对象位于**独立 schema `rag`**，而应用 SQL 一律用**未限定表名**
--   （FROM rag_chunk / JOIN rag_edition / ?::rag_chunk_role /
--   rag_current_tenant()）。因此**每条连接都必须把 search_path 指向 rag**，
--   否则未限定名解析不到对象，表现为 "relation does not exist"。
--   应用侧已通过 HikariCP 的 connection-init-sql 实现：
--       SET search_path TO rag, public
--   手工用 psql 连库时必须自行执行同一句，否则后续查询会失败。
--
-- 【⚠️ 扩展创建需要超级用户】
--   CREATE EXTENSION vector / pgcrypto 属非 trusted 扩展，
--   需 RDS 超管账号（或 alicloud_rds_admin）执行；
--   应用账号 rag_app 无权创建。本文件已含这两条 CREATE EXTENSION，
--   若目标库已装好扩展，IF NOT EXISTS 会跳过。
--
-- 【与 Flyway 的关系 —— 二选一，勿混用】
--   ⚠️ RAG 的 rag-bootstrap 引入了 spring-boot-starter-flyway，
--      应用启动时会自动跑 V1→V6。
--      * 生产用 Flyway 迁移 → **不要**执行本文件（避免 Flyway 误判未迁移
--        而重跑 V1，CREATE TYPE 冲突直接失败）。
--      * 生产手工/离线初始化 → 执行本文件。
--
-- 【幂等性】
--   本文件**不是**幂等的（无 DROP 保护），且**会**在已装扩展的库上跳过扩展
--   创建（IF NOT EXISTS）。目标库应为空库。
--
-- 【执行方式】
--   psql -h <host> -p <port> -U <超级用户> -d <target_db> \
--        -v ON_ERROR_STOP=1 -f 01_rag_ddl.sql
--   psql -h <host> -p <port> -U <超级用户> -d <target_db> \
--        -c 'SET search_path TO rag, public;' -c '\i 01_rag_ddl.sql'
--
--   ⚠️ ON_ERROR_STOP=1 必加（理由同 platform DDL）。
-- =====================================================================

--
-- PostgreSQL database dump
--


-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6

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


--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


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
    LANGUAGE sql STABLE
    AS $$
    SELECT NULLIF(current_setting('app.tenant_id', true), '')::UUID;
$$;


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
    CONSTRAINT rag_chunk_char_range_chk CHECK ((char_end >= char_start)),
    CONSTRAINT rag_chunk_char_start_chk CHECK ((char_start >= 0)),
    CONSTRAINT rag_chunk_page_chk CHECK ((page_number >= 1))
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
-- Name: rag_chunk_embedding; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_chunk_embedding (
    chunk_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    index_generation integer DEFAULT 1 NOT NULL,
    embed_model text NOT NULL,
    embedding public.vector(1024) NOT NULL
);

ALTER TABLE ONLY rag.rag_chunk_embedding FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE rag_chunk_embedding; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON TABLE rag.rag_chunk_embedding IS '片段向量。索引代际（index_generation）支持换模型时的蓝绿切换与回滚。';


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
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY rag.rag_dictionary FORCE ROW LEVEL SECURITY;


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
    CONSTRAINT rag_document_carrier_chk CHECK ((carrier = ANY (ARRAY['PDF'::text, 'SCAN'::text, 'EPUB'::text, 'HTML'::text, 'TXT'::text]))),
    CONSTRAINT rag_document_page_count_chk CHECK ((page_count >= 0)),
    CONSTRAINT rag_document_parse_status_chk CHECK ((parse_status = ANY (ARRAY['PENDING'::text, 'PARSED'::text, 'CHUNKED'::text, 'FAILED'::text]))),
    CONSTRAINT rag_document_revoked_reason_chk CHECK (((ver_status <> 'REVOKED'::rag.rag_verification_status) OR (revoked_reason IS NOT NULL)))
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
    CONSTRAINT rag_edition_term_chk CHECK ((term = ANY (ARRAY['上册'::text, '下册'::text, '全一册'::text])))
);

ALTER TABLE ONLY rag.rag_edition FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE rag_edition; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON TABLE rag.rag_edition IS '版本坐标系。版本是五轴复合标识，缺一轴则引用不可验证。';


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
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY rag.rag_knowledge_point FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE rag_knowledge_point; Type: COMMENT; Schema: rag; Owner: -
--

COMMENT ON TABLE rag.rag_knowledge_point IS '知识点本体。教辅语料的语义骨架，用于解决「讲解与练习碎片化」问题。';


--
-- Name: rag_knowledge_prerequisite; Type: TABLE; Schema: rag; Owner: -
--

CREATE TABLE rag.rag_knowledge_prerequisite (
    tenant_id uuid NOT NULL,
    kp_id uuid NOT NULL,
    prereq_kp_id uuid NOT NULL,
    CONSTRAINT rag_kp_prereq_self_chk CHECK ((kp_id <> prereq_kp_id))
);

ALTER TABLE ONLY rag.rag_knowledge_prerequisite FORCE ROW LEVEL SECURITY;


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
    CONSTRAINT rag_source_identifier_chk CHECK ((((trust_tier = ANY (ARRAY['OFFICIAL_PUBLISHER'::rag.rag_trust_tier, 'OFFICIAL_TUTORIAL'::rag.rag_trust_tier])) AND (isbn IS NOT NULL)) OR ((trust_tier = ANY (ARRAY['PEER_REVIEWED_PAPER'::rag.rag_trust_tier, 'PREPRINT'::rag.rag_trust_tier])) AND (doi IS NOT NULL)) OR (trust_tier = 'UNVERIFIED'::rag.rag_trust_tier))),
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

CREATE INDEX rag_chunk_embedding_hnsw_idx ON rag.rag_chunk_embedding USING hnsw (embedding public.rds_vector_cosine_ops) WITH (m='16', ef_construction='64');


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
    ADD CONSTRAINT rag_citation_chunk_fk FOREIGN KEY (tenant_id, chunk_id) REFERENCES rag.rag_chunk(tenant_id, chunk_id) ON DELETE CASCADE;


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
-- Name: rag_chunk_embedding; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_chunk_embedding ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_chunk rag_chunk_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_chunk_tenant_isolation ON rag.rag_chunk USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_answer_citation rag_citation_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_citation_tenant_isolation ON rag.rag_answer_citation USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_dictionary; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_dictionary ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_dictionary rag_dictionary_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_dictionary_tenant_isolation ON rag.rag_dictionary USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_document; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_document ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_document rag_document_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_document_tenant_isolation ON rag.rag_document USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_edition; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_edition ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_edition rag_edition_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_edition_tenant_isolation ON rag.rag_edition USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_chunk_embedding rag_embedding_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_embedding_tenant_isolation ON rag.rag_chunk_embedding USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


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
-- Name: rag_knowledge_prerequisite; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_knowledge_prerequisite ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_knowledge_prerequisite rag_kp_prereq_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_kp_prereq_tenant_isolation ON rag.rag_knowledge_prerequisite USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_knowledge_point rag_kp_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_kp_tenant_isolation ON rag.rag_knowledge_point USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- Name: rag_source; Type: ROW SECURITY; Schema: rag; Owner: -
--

ALTER TABLE rag.rag_source ENABLE ROW LEVEL SECURITY;

--
-- Name: rag_source rag_source_tenant_isolation; Type: POLICY; Schema: rag; Owner: -
--

CREATE POLICY rag_source_tenant_isolation ON rag.rag_source USING ((tenant_id = rag.rag_current_tenant())) WITH CHECK ((tenant_id = rag.rag_current_tenant()));


--
-- PostgreSQL database dump complete
--





-- =====================================================================
-- ══ 校验 ══
-- =====================================================================

-- ① 表应为 11 张，且全部落在 schema rag
SELECT count(*) AS rag表数量 FROM pg_tables WHERE schemaname = 'rag';

-- ② 扩展版本（vector 应 ≥ 0.8，ivfflat/hnsw 索引依赖它）
SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector','pgcrypto');

-- ③ 枚举类型应为 9 个
SELECT count(*) AS 枚举类型数 FROM pg_type t
  JOIN pg_namespace n ON n.oid = t.typnamespace
 WHERE n.nspname = 'rag' AND t.typtype = 'e';

-- ④ RLS 策略应为 11 条
SELECT count(*) AS 策略数 FROM pg_policies WHERE schemaname = 'rag';

-- ⑤ rag_current_tenant() 必须存在，且未设置 app.tenant_id 时返回 NULL
--    （fail-closed：返回 NULL 则所有 RLS 策略拒绝访问）
SET search_path TO rag, public;
SELECT rag_current_tenant() AS 未设置租户上下文时应为NULL;

-- ⚠️ **红线检查（必做）**：应用连接角色必须是 **rag_app**
--    （NOSUPERUSER + NOBYPASSRLS）。带 BYPASSRLS 的角色会绕过 RLS，
--    租户隔离形同虚设，而 rag-bootstrap 的 RagStartupSelfCheck 会因此
--    拒绝启动——那是硬性保护，不要试图绕过。
SELECT current_user, rolsuper, rolbypassrls
  FROM pg_roles WHERE rolname = current_user;
