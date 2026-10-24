-- =====================================================================
-- platform 完整 DDL —— 生产环境初始化用
-- =====================================================================
--
-- 【用途】
--   把 platform 模块的**全部**数据库对象一次性建出来（表 / 类型 / 函数 /
--   触发器 / 索引 / 约束 / RLS 策略 / 注释 / 序列），用于生产环境的
--   空库初始化，或用于与线上库做结构比对（漂移核对）。
--
-- 【来源与可信度】
--   本文件**不是手写的**，也不是从线上脏库导出的：
--   取自远程 RDS（rm-cn-hhf50fbhh0001uzo.rwlb.rds.aliyuncs.com:5432，
--   PostgreSQL 18.6），做法是——
--     ① 在该实例上建**空库** agent_ddl_build；
--     ② 顺序执行仓库迁移脚本 platform/agent-infrastructure/src/main/
--        resources/db/migration/V1 … V18（全部 18 个，均零报错通过）；
--     ③ pg_dump --schema-only --schema=public 导出。
--   故本文件与仓库迁移脚本**严格同源**——迁移脚本变了，本文件必须重新导出。
--
--   ⚠️ **零漂移已核验**：导出结果与线上 agent_dev 的 28 张既有业务表逐列比对，
--      差异为 0；本文件多出的 14 张表（13 张 cap_* + execution_capability_snapshot）
--      正是线上尚未执行的 V15–V18。线上 agent_dev 当前 flyway 基线停在 V13。
--
-- 【对象清单（2026-10-23 远程空库实跑核验，非估算）】
--   表 42 张 · 索引 119 个（其中本文件内显式 CREATE INDEX 53 个）
--   枚举类型 13 个 · 函数 14 个 · 触发器 19 个 · 检查约束 89 个
--   RLS 策略 46 条 · 启用且 FORCE ROW LEVEL SECURITY 的表 38 张
--
-- 【前置条件】
--   1. PostgreSQL 17+（文件含 SET transaction_timeout，需 PG 17）。
--   2. 目标库必须为空库（或至少不含同名对象）。本文件**不做** DROP，
--      重复执行会因对象已存在而报错——这是刻意的（见下方「幂等性」）。
--   3. 数据库角色（app_agent / platform_ops）需**先**执行
--      03_platform_roles.sql 建好；本文件已用 --no-owner --no-privileges
--      导出，不含任何 GRANT。
--
-- 【与 Flyway 的关系 —— 二选一，勿混用】
--   ⚠️⚠️ platform 的迁移脚本由 Flyway 管理（ADR-011）。本文件是**平铺快照**，
--      用途是「空库一次性初始化」与「结构漂移比对」。
--      * 若生产环境用 Flyway 迁移 → **不要**执行本文件，
--        让 Flyway 按 V1→V18 建库，避免两套版本管理打架。
--      * 若生产环境要手工/离线初始化（无 Flyway）→ 执行本文件，
--        并自行记录版本基线。
--      混用的后果：Flyway 看到 flyway_schema_history 为空，会认为「未迁移」，
--      于是重跑 V1，而 V1 的 CREATE TYPE 冲突直接失败。
--
-- 【幂等性】
--   本文件**不是**幂等的（无 IF NOT EXISTS / DROP 保护）。
--   这是刻意的：初始化脚本若在「已有数据的库」上静默跳过，
--   会出现「表在但结构是旧的」——比直接报错更难排查。
--   要重复执行请先 DROP DATABASE 重建。
--
-- 【执行方式】
--   psql -h <host> -p <port> -U <owner> -d <target_db> \
--        -v ON_ERROR_STOP=1 -f 01_platform_ddl.sql
--
--   ⚠️ ON_ERROR_STOP=1 必加：否则 psql 遇错继续跑后续语句，
--      最终报「成功」但库里缺表——这类「谎报成功」本项目已多次踩坑。
--
-- 【执行后校验】
--   见文件末尾的「══ 校验 ══」小节（已内联在下方）。
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
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--
-- ⚠️ **已移除 `CREATE SCHEMA public;`**（2026-10-23 远程实测）：
--    public schema 在**每个** PostgreSQL 数据库中都已存在，
--    pg_dump 因 --schema=public 而把它一并导出，
--    直接执行会报 `ERROR: schema "public" already exists`，
--    且该错误发生在文件开头 —— 后续所有语句都不会被执行。
--    故此处删去该语句（public 本就存在，无需创建）。

--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA public IS 'standard public schema';


--
-- Name: agent_message_state; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.agent_message_state AS ENUM (
    'PENDING',
    'INFLIGHT'
);


--
-- Name: approval_requirement; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.approval_requirement AS ENUM (
    'NOT_REQUIRED',
    'OPTIONAL',
    'REQUIRED'
);


--
-- Name: approval_state; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.approval_state AS ENUM (
    'PENDING',
    'APPROVED',
    'REJECTED',
    'EXPIRED',
    'CONSUMED'
);


--
-- Name: cap_approval_state; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.cap_approval_state AS ENUM (
    'PENDING',
    'RESERVED',
    'INTENT_SUBMITTED',
    'CONSUMED',
    'EXPIRED',
    'CANCELLED'
);


--
-- Name: TYPE cap_approval_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TYPE public.cap_approval_state IS '§5.3.3 审批状态机：PENDING → RESERVED → INTENT_SUBMITTED → CONSUMED；旁路 EXPIRED / CANCELLED。状态只能前进，不得回退到 PENDING。';


--
-- Name: cap_intent_state; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.cap_intent_state AS ENUM (
    'RESERVED',
    'INTENT_SUBMITTED',
    'CONSUMED',
    'CANCELLED',
    'EXPIRED'
);


--
-- Name: cap_lifecycle_state; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.cap_lifecycle_state AS ENUM (
    'DRAFT',
    'VALIDATED',
    'PUBLISHED',
    'DEPRECATED'
);


--
-- Name: TYPE cap_lifecycle_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TYPE public.cap_lifecycle_state IS '配置对象生命周期状态（§6.6 / 06 §3）：DRAFT→VALIDATED→PUBLISHED→DEPRECATED。与 published_at 并存：前者是流程状态，后者是不可变触发器的依据。';


--
-- Name: cap_release_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.cap_release_status AS ENUM (
    'PREPARING',
    'PUBLISHED',
    'ROLLED_BACK'
);


--
-- Name: cap_result_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.cap_result_status AS ENUM (
    'SUCCEEDED',
    'FAILED',
    'UNKNOWN'
);


--
-- Name: config_change_state; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.config_change_state AS ENUM (
    'PENDING',
    'APPROVED',
    'REJECTED',
    'APPLIED'
);


--
-- Name: outbox_state; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.outbox_state AS ENUM (
    'PENDING',
    'PUBLISHED',
    'FAILED'
);


--
-- Name: run_state; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.run_state AS ENUM (
    'QUEUED',
    'RUNNING',
    'CANCELLED',
    'SUCCEEDED',
    'FAILED',
    'TIMED_OUT'
);


--
-- Name: tool_operation_state; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.tool_operation_state AS ENUM (
    'PENDING',
    'IN_FLIGHT',
    'SUCCEEDED',
    'FAILED',
    'UNKNOWN',
    'RECONCILED_SUCCEEDED',
    'RECONCILED_ABSENT',
    'MANUAL_DISPOSED'
);


--
-- Name: tool_risk_level; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.tool_risk_level AS ENUM (
    'READ',
    'WRITE_PROFILE',
    'WRITE_EXTERNAL'
);


--
-- Name: align_cap_lifecycle_state(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.align_cap_lifecycle_state() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
        BEGIN
            IF NEW.published_at IS NOT NULL AND NEW.lifecycle_state <> 'DEPRECATED' THEN
                NEW.lifecycle_state := 'PUBLISHED';
            ELSIF NEW.published_at IS NULL AND NEW.lifecycle_state = 'PUBLISHED' THEN
                -- ⚠️ **显式报错而非静默降级**：声称「已发布」却没有 published_at
                --    是一个真实的配置错误。若这里悄悄改回 DRAFT，
                --    「发布」会看起来成功而对象其实仍是草稿 ——
                --    失败现场离真因极远，且未发布对象会被当成已发布使用。
                RAISE EXCEPTION 'lifecycle_state=PUBLISHED 必须同时设置 published_at（表 %）',
                    TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
            END IF;
            RETURN NEW;
        END;
        $$;


--
-- Name: current_tenant_id(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
    SELECT NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID;
$$;


--
-- Name: FUNCTION current_tenant_id(); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.current_tenant_id() IS '返回当前事务的租户上下文。未设置 app.tenant_id 时返回 NULL，所有 RLS 策略将拒绝访问——默认拒绝，符合 fail-closed';


--
-- Name: enforce_approval_state_transition(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_approval_state_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- 终态不可迁出（CONSUMED / REJECTED / EXPIRED）
    IF OLD.state IN ('CONSUMED', 'REJECTED', 'EXPIRED') AND NEW.state <> OLD.state THEN
        RAISE EXCEPTION '审批终态 % 不可迁移到 %（operation_id=%）',
            OLD.state, NEW.state, OLD.operation_id USING ERRCODE = 'restrict_violation';
    END IF;

    -- PENDING 只能进入 APPROVED / REJECTED / EXPIRED
    IF OLD.state = 'PENDING' AND NEW.state NOT IN ('APPROVED', 'REJECTED', 'EXPIRED') THEN
        RAISE EXCEPTION '审批 PENDING 只能迁移到 APPROVED/REJECTED/EXPIRED，收到 %',
            NEW.state USING ERRCODE = 'restrict_violation';
    END IF;

    -- APPROVED 只能进入 CONSUMED / EXPIRED / REJECTED
    IF OLD.state = 'APPROVED' AND NEW.state NOT IN ('CONSUMED', 'EXPIRED', 'REJECTED') THEN
        RAISE EXCEPTION '审批 APPROVED 只能迁移到 CONSUMED/EXPIRED/REJECTED，收到 %',
            NEW.state USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: enforce_cap_approval_state_transition(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_cap_approval_state_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.approval_state IN ('CONSUMED', 'EXPIRED', 'CANCELLED')
       AND NEW.approval_state <> OLD.approval_state THEN
        RAISE EXCEPTION 'CAP 审批终态 % 不可迁移到 %（approval_id=%）',
            OLD.approval_state, NEW.approval_state, OLD.approval_id
            USING ERRCODE = 'restrict_violation';
    END IF;

    IF NEW.approval_state = 'RESERVED' AND OLD.approval_state <> 'PENDING' THEN
        RAISE EXCEPTION 'CAP 审批只能从 PENDING 迁移到 RESERVED，收到 % ← %',
            NEW.approval_state, OLD.approval_state USING ERRCODE = 'restrict_violation';
    END IF;

    IF NEW.approval_state = 'INTENT_SUBMITTED' AND OLD.approval_state <> 'RESERVED' THEN
        RAISE EXCEPTION 'CAP 审批只能从 RESERVED 迁移到 INTENT_SUBMITTED，收到 % ← %',
            NEW.approval_state, OLD.approval_state USING ERRCODE = 'restrict_violation';
    END IF;

    IF NEW.approval_state = 'CONSUMED' AND OLD.approval_state <> 'INTENT_SUBMITTED' THEN
        RAISE EXCEPTION 'CAP 审批只能从 INTENT_SUBMITTED 迁移到 CONSUMED，收到 % ← %',
            NEW.approval_state, OLD.approval_state USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: FUNCTION enforce_cap_approval_state_transition(); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.enforce_cap_approval_state_transition() IS '§5.3.3 审批状态机守卫：状态只能前进；终态不可迁出；PENDING→RESERVED→INTENT_SUBMITTED→CONSUMED，旁路 CANCELLED/EXPIRED。';


--
-- Name: enforce_config_change_transition(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_config_change_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.state IN ('REJECTED', 'APPLIED') AND NEW.state <> OLD.state THEN
        RAISE EXCEPTION '配置变更终态 % 不可迁移到 %（change_id=%）',
            OLD.state, NEW.state, OLD.change_id USING ERRCODE = 'restrict_violation';
    END IF;

    IF OLD.state = 'PENDING' AND NEW.state NOT IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION '配置变更 PENDING 只能迁移到 APPROVED/REJECTED，收到 %',
            NEW.state USING ERRCODE = 'restrict_violation';
    END IF;

    -- ⚠️ APPROVED 只能 → APPLIED；**不能**退回 PENDING。
    --    退回会让「已批准但未应用」的申请重新排队，等于让审批人白批。
    IF OLD.state = 'APPROVED' AND NEW.state <> 'APPLIED' THEN
        RAISE EXCEPTION '配置变更 APPROVED 只能迁移到 APPLIED，收到 %',
            NEW.state USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: enforce_fencing_on_write(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_fencing_on_write() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.fencing_token < OLD.fencing_token THEN
        RAISE EXCEPTION 'fencing token 回退被拒绝：% < %（run_id=%）',
            NEW.fencing_token, OLD.fencing_token, OLD.run_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: enforce_reservation_state_transition(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_reservation_state_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.state IN ('SETTLED', 'RELEASED') AND NEW.state <> OLD.state THEN
        RAISE EXCEPTION '预算预留已终态 %，不可迁出至 %（reservation_id=%）',
            OLD.state, NEW.state, OLD.reservation_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: FUNCTION enforce_reservation_state_transition(); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.enforce_reservation_state_transition() IS '预算预留终态不可迁出。重复结算/重复释放若实现为 UPDATE，第二次会因状态已是终态而被拒绝——这是「不重复计费」的 DB 级保证';


--
-- Name: enforce_run_state_transition(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_run_state_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.state IN ('CANCELLED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT')
       AND NEW.state <> OLD.state THEN
        RAISE EXCEPTION 'Run 终态 % 不可迁移到 %（run_id=%）',
            OLD.state, NEW.state, OLD.run_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: forbid_attempt_after_terminal(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.forbid_attempt_after_terminal() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    op_state tool_operation_state;
BEGIN
    SELECT state INTO op_state
    FROM tool_operation
    WHERE tenant_id = NEW.tenant_id AND operation_id = NEW.operation_id
    FOR UPDATE;

    IF op_state IN ('SUCCEEDED', 'RECONCILED_SUCCEEDED', 'RECONCILED_ABSENT', 'MANUAL_DISPOSED') THEN
        RAISE EXCEPTION '逻辑操作已终态 %，禁止新增 attempt（operation_id=%）',
            op_state, NEW.operation_id USING ERRCODE = 'restrict_violation';
    END IF;
    -- UNKNOWN 状态下新增 attempt 等同盲目重试，必须拒绝
    IF op_state = 'UNKNOWN' THEN
        RAISE EXCEPTION '逻辑操作处于 UNKNOWN，禁止新增 attempt（等同盲目重试，Sev-0）。'
            '须先完成对账（operation_id=%）', NEW.operation_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: forbid_audit_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.forbid_audit_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- P4-D04 D-1：仅 DELETE 且会话已声明受控清理主体时放行
    IF TG_OP = 'DELETE'
       AND NULLIF(current_setting('agent.retention.actor', TRUE), '') IS NOT NULL THEN
        RETURN OLD;
    END IF;

    RAISE EXCEPTION '审计记录为追加不可改（R-3）：禁止 % 操作 audit_record（audit_id=%）。'
        , TG_OP, OLD.audit_id
        USING ERRCODE = 'restrict_violation';
END;
$$;


--
-- Name: FUNCTION forbid_audit_mutation(); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.forbid_audit_mutation() IS 'V6 审计防篡改，P4-D04 增加受控清理通道：DELETE 仅在会话声明 agent.retention.actor 时放行，UPDATE 永远拒绝。注意这是意图声明而非访问控制——任意会话都可自行 set_config，真正的防线在 DB 凭据分离。';


--
-- Name: forbid_published_def_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.forbid_published_def_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_version TEXT;
    v_old_state TEXT;
    v_new_state TEXT;
BEGIN
    IF OLD.published_at IS NULL THEN
        -- 草稿行可编辑（06 §3「草稿编辑不修改已发布版本行」）
        RETURN NEW;
    END IF;

    v_old_state := to_jsonb(OLD) ->> 'lifecycle_state';
    v_new_state := to_jsonb(NEW) ->> 'lifecycle_state';

    IF v_old_state = 'PUBLISHED' AND v_new_state = 'DEPRECATED'
       AND (to_jsonb(OLD) - 'lifecycle_state' - 'updated_at')
           IS NOT DISTINCT FROM
           (to_jsonb(NEW) - 'lifecycle_state' - 'updated_at') THEN
        -- 纯停用：内容一字未改，只改生命周期状态 → 放行
        RETURN NEW;
    END IF;

    v_version := to_jsonb(OLD) ->> 'version';
    RAISE EXCEPTION '% 为已发布不可变版本行，禁止修改（version=%）',
        TG_TABLE_NAME, v_version USING ERRCODE = 'restrict_violation';
END;
$$;


--
-- Name: FUNCTION forbid_published_def_mutation(); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.forbid_published_def_mutation() IS '已发布版本行内容不可改写（§6.1 / 06 §1.1）。草稿行可编辑；已发布行仅允许 PUBLISHED→DEPRECATED 的纯元数据停用（06 §3.4），其余一律拒绝。DELETE 不受此约束：删除/保留期链路必须能物理删除（V8 erasure）。';


--
-- Name: forbid_snapshot_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.forbid_snapshot_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'execution_snapshot 为不可变快照，禁止修改（snapshot_id=%）',
        OLD.snapshot_id USING ERRCODE = 'restrict_violation';
END;
$$;


--
-- Name: touch_agent_message_queue_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.touch_agent_message_queue_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;


--
-- Name: touch_tenant_config_override_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.touch_tenant_config_override_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: agent; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent (
    agent_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.agent FORCE ROW LEVEL SECURITY;


--
-- Name: agent_message_dead_letter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_message_dead_letter (
    dead_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    queue_id uuid NOT NULL,
    run_id uuid NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    attempts integer NOT NULL,
    error text NOT NULL,
    failed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT agent_message_dead_attempts_non_negative CHECK ((attempts >= 0))
);

ALTER TABLE ONLY public.agent_message_dead_letter FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE agent_message_dead_letter; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.agent_message_dead_letter IS 'P4-D17 消息死信：重试耗尽或被拒的消息。只进不出，须人工介入';


--
-- Name: agent_message_queue; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_message_queue (
    queue_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    state public.agent_message_state DEFAULT 'PENDING'::public.agent_message_state NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    claimed_by text,
    claimed_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT agent_message_attempts_non_negative CHECK ((attempts >= 0)),
    CONSTRAINT agent_message_claim_complete CHECK ((((state = 'INFLIGHT'::public.agent_message_state) AND (claimed_by IS NOT NULL) AND (claimed_at IS NOT NULL)) OR ((state = 'PENDING'::public.agent_message_state) AND (claimed_by IS NULL) AND (claimed_at IS NULL))))
);

ALTER TABLE ONLY public.agent_message_queue FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE agent_message_queue; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.agent_message_queue IS 'P4-D17 PostgreSQL 异步消息队列：RabbitMQ 的可切换替代载体。at-least-once + 可见性超时回收 + 有限重试，与 Rabbit 通道语义对齐';


--
-- Name: agent_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_version (
    version_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    version_number integer NOT NULL,
    definition jsonb NOT NULL,
    published_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT agent_version_number_positive CHECK ((version_number > 0))
);

ALTER TABLE ONLY public.agent_version FORCE ROW LEVEL SECURITY;


--
-- Name: approval; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.approval (
    approval_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    state public.approval_state DEFAULT 'PENDING'::public.approval_state NOT NULL,
    requirement public.approval_requirement NOT NULL,
    argument_digest text NOT NULL,
    target_resource text NOT NULL,
    approver_scope text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    approver_id text,
    decided_at timestamp with time zone,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT approval_consumed_consistent CHECK (((state <> 'CONSUMED'::public.approval_state) OR (consumed_at IS NOT NULL))),
    CONSTRAINT approval_decided_consistent CHECK (((state = 'PENDING'::public.approval_state) OR (approver_id IS NOT NULL)))
);

ALTER TABLE ONLY public.approval FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE approval; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.approval IS '审批绑定具体 operation + 参数摘要 + 目标资源 + 有效期。消费审批前必须检查 Run 是否已取消（P0-D03 §2）';


--
-- Name: audit_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_record (
    audit_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    actor_id text NOT NULL,
    action text NOT NULL,
    resource_type text NOT NULL,
    resource_id text,
    detail jsonb DEFAULT '{}'::jsonb NOT NULL,
    correlation_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.audit_record FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE audit_record; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.audit_record IS '审计记录（P3-D07 / R-1 / R-2 / R-3）。追加不可改：UPDATE 一律拒绝，DELETE 仅在受控清理通道下放行（会话须声明 agent.retention.actor 且须落 erasure_certificate）。180 天保留期由 RetentionJob 通过该通道执行清理。';


--
-- Name: budget_account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.budget_account (
    account_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    currency text DEFAULT 'CNY'::text NOT NULL,
    total_limit numeric(18,6) NOT NULL,
    reserved_amount numeric(18,6) DEFAULT 0 NOT NULL,
    settled_amount numeric(18,6) DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT budget_amounts_non_negative CHECK (((total_limit >= (0)::numeric) AND (reserved_amount >= (0)::numeric) AND (settled_amount >= (0)::numeric))),
    CONSTRAINT budget_no_overspend CHECK (((reserved_amount + settled_amount) <= total_limit))
);

ALTER TABLE ONLY public.budget_account FORCE ROW LEVEL SECURITY;


--
-- Name: CONSTRAINT budget_no_overspend ON budget_account; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT budget_no_overspend ON public.budget_account IS '数据库层最后防线：即使应用层并发检查有漏，DB 约束也拒绝超发（R-007）';


--
-- Name: budget_reservation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.budget_reservation (
    reservation_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    account_id uuid NOT NULL,
    run_id uuid NOT NULL,
    reserved_amount numeric(18,6) NOT NULL,
    settled_amount numeric(18,6) DEFAULT 0 NOT NULL,
    state text DEFAULT 'RESERVED'::text NOT NULL,
    release_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    settled_at timestamp with time zone,
    operation_id uuid,
    CONSTRAINT reservation_amount_positive CHECK ((reserved_amount > (0)::numeric)),
    CONSTRAINT reservation_settled_valid CHECK (((settled_amount >= (0)::numeric) AND (settled_amount <= reserved_amount))),
    CONSTRAINT reservation_state_valid CHECK ((state = ANY (ARRAY['RESERVED'::text, 'SETTLED'::text, 'RELEASED'::text])))
);

ALTER TABLE ONLY public.budget_reservation FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE budget_reservation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.budget_reservation IS '预算预留账。并发不超发的保证来自两条：（1）应用层单条原子条件 UPDATE（WHERE reserved+settled+? <= total_limit）；（2）budget_account 上的 budget_no_overspend CHECK 作为最后防线。二者缺一不可：仅有 CHECK 会出现「随机预算不足」，仅有应用层会在 SQL 写错时失守';


--
-- Name: COLUMN budget_reservation.state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_reservation.state IS 'RESERVED / SETTLED / RELEASED。UNKNOWN 对应的预留**保持 RESERVED**，绝不自动释放——成本可能已发生（R-007 / P3-D04 §2.4）。只有对账收敛（RECONCILED_SUCCEEDED → 结算 / RECONCILED_ABSENT → 释放）或确定失败（FAILED → 释放）才可离开 RESERVED';


--
-- Name: COLUMN budget_reservation.operation_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.budget_reservation.operation_id IS '关联的 ToolOperation。同租户下至多一条预留（part_uniq_reservation_operation）——重复调用不得重复占用额度（FR-012「预留有唯一记录」）。可空：存量数据与无账本调用的预留允许为空。';


--
-- Name: CONSTRAINT reservation_state_valid ON budget_reservation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT reservation_state_valid ON public.budget_reservation IS '预留状态仅三值。终态（SETTLED/RELEASED）不得回退——防止「已结算被改回 RESERVED」导致额度被二次占用';


--
-- Name: cap_agent_def; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_agent_def (
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    version integer NOT NULL,
    name text,
    description text,
    roles jsonb DEFAULT '[]'::jsonb NOT NULL,
    enabled_skills jsonb DEFAULT '[]'::jsonb NOT NULL,
    skill_bundles jsonb DEFAULT '[]'::jsonb NOT NULL,
    capability_tags jsonb DEFAULT '[]'::jsonb NOT NULL,
    inherits text,
    overrides jsonb DEFAULT '{}'::jsonb NOT NULL,
    config_fingerprint text NOT NULL,
    schema_version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    lifecycle_state public.cap_lifecycle_state DEFAULT 'DRAFT'::public.cap_lifecycle_state NOT NULL,
    CONSTRAINT cap_agent_def_no_inherits_ck CHECK ((inherits IS NULL)),
    CONSTRAINT cap_agent_def_version_positive CHECK ((version > 0))
);

ALTER TABLE ONLY public.cap_agent_def FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_agent_def; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_agent_def IS 'Agent 结构化定义（租户级，§6.1 / 06 §1.4）。迁移期与 agent_version 双写，本表为结构化真源。';


--
-- Name: COLUMN cap_agent_def.lifecycle_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_agent_def.lifecycle_state IS '生命周期状态（§6.6）：DRAFT/VALIDATED/PUBLISHED/DEPRECATED。';


--
-- Name: cap_approval; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_approval (
    approval_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    principal_id text NOT NULL,
    invocation_id text,
    operation_id uuid,
    run_id uuid,
    tool_id text NOT NULL,
    target_resource text NOT NULL,
    approval_scope_hash text NOT NULL,
    scope_definition text NOT NULL,
    effective_args_hash text NOT NULL,
    closure_fingerprint text NOT NULL,
    authz_version bigint NOT NULL,
    approver_id text NOT NULL,
    approver_scope text NOT NULL,
    decided_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    approval_state public.cap_approval_state DEFAULT 'PENDING'::public.cap_approval_state NOT NULL,
    reserved_at timestamp with time zone,
    intent_submitted_at timestamp with time zone,
    consumed_at timestamp with time zone,
    cancelled_at timestamp with time zone,
    terminal_status text,
    cancel_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.cap_approval FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_approval; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_approval IS 'CAP 审批（§5.3.1 / §5.3.3）。与既有 approval 表是两套模型，不可混用：本表支持预占、绑定双哈希、状态机 RESERVED→INTENT_SUBMITTED→CONSUMED。';


--
-- Name: COLUMN cap_approval.principal_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_approval.principal_id IS '§5.3.1 绑定项：被授权主体。非本主体的审批一律视为「不存在」。';


--
-- Name: COLUMN cap_approval.approval_scope_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_approval.approval_scope_hash IS '§5.3.1 范围哈希：审批允许的操作范围与参数约束的规范化摘要。消费时用同一份范围定义重新验证实际参数（范围重验）。';


--
-- Name: COLUMN cap_approval.scope_definition; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_approval.scope_definition IS '§5.3.1 范围定义全文（JSON）。消费时用它重新验证实际参数是否仍落在范围内；只存哈希不存定义则无法重验，防参数替换重放形同虚设。';


--
-- Name: COLUMN cap_approval.effective_args_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_approval.effective_args_hash IS '§5.3.1 有效参数哈希：本次全部有效参数的 args_norm_v1 摘要。防止「参数替换重放」——同键不同参必须被拒。';


--
-- Name: COLUMN cap_approval.authz_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_approval.authz_version IS '生成时的主体授权版本。消费时当前版本 > 本值 → 审批失效（§5.5）。';


--
-- Name: cap_authz_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_authz_version (
    tenant_id uuid NOT NULL,
    principal_id text NOT NULL,
    authz_version bigint DEFAULT 1 NOT NULL,
    version integer DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT cap_authz_version_positive CHECK ((authz_version >= 1))
);

ALTER TABLE ONLY public.cap_authz_version FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_authz_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_authz_version IS 'DB 化单调授权版本（§6.1 / 06 §1.5）。递增必须用 UPDATE...RETURNING 原子自增，禁止应用先读后加一。读取失败一律 fail-closed（AUTHZ_UNAVAILABLE）。';


--
-- Name: cap_compensation_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_compensation_task (
    task_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    kind text NOT NULL,
    target_id text NOT NULL,
    invocation_id text,
    payload text,
    state text DEFAULT 'PENDING'::text NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    last_error text,
    next_retry_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT cap_compensation_attempts_valid CHECK ((attempts >= 0)),
    CONSTRAINT cap_compensation_kind_valid CHECK ((kind = ANY (ARRAY['CANCEL_APPROVAL'::text, 'RELEASE_BUDGET'::text, 'RETRY_LEDGER_POST'::text]))),
    CONSTRAINT cap_compensation_state_valid CHECK ((state = ANY (ARRAY['PENDING'::text, 'RUNNING'::text, 'COMPLETED'::text, 'FAILED'::text])))
);

ALTER TABLE ONLY public.cap_compensation_task FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_compensation_task; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_compensation_task IS '补偿任务（§5.3.5）。补偿失败时落库，由独立调度重放；唯一约束保证同一补偿不会重复入队（幂等）。';


--
-- Name: cap_config_pointer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_config_pointer (
    pointer_key text NOT NULL,
    scope text NOT NULL,
    tenant_id uuid,
    current_release_id bigint,
    current_publish_seq bigint,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT cap_config_pointer_complete_ck CHECK ((((current_release_id IS NULL) AND (current_publish_seq IS NULL)) OR ((current_release_id IS NOT NULL) AND (current_publish_seq IS NOT NULL)))),
    CONSTRAINT cap_config_pointer_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id IS NULL) AND (pointer_key = 'PLATFORM'::text)) OR ((scope = 'TENANT'::text) AND (tenant_id IS NOT NULL) AND (pointer_key = ('TENANT:'::text || (tenant_id)::text))))),
    CONSTRAINT cap_config_pointer_seq_ck CHECK (((current_publish_seq IS NULL) OR (current_publish_seq >= 0)))
);

ALTER TABLE ONLY public.cap_config_pointer FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_config_pointer; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_config_pointer IS '当前发布指针（§6.3）。当前版本**只**由指针指向的 PUBLISHED release 决定，绝不以 max(seq) 选择、绝不扫描历史行。CAS 基线为 current_publish_seq。';


--
-- Name: cap_config_publish_request; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_config_publish_request (
    request_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    operation text NOT NULL,
    payload jsonb NOT NULL,
    reason text NOT NULL,
    requester_id text NOT NULL,
    state text DEFAULT 'PENDING'::text NOT NULL,
    approver_id text,
    decision_note text,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    decided_at timestamp with time zone,
    applied_at timestamp with time zone,
    failure_reason text,
    CONSTRAINT cap_config_publish_request_operation_ck CHECK ((operation = ANY (ARRAY['PUBLISH'::text, 'ROLLBACK'::text, 'LIFECYCLE_TRANSITION'::text]))),
    CONSTRAINT cap_config_publish_request_state_ck CHECK ((state = ANY (ARRAY['PENDING'::text, 'APPROVED'::text, 'REJECTED'::text, 'APPLIED'::text, 'FAILED'::text]))),
    CONSTRAINT cap_config_publish_request_two_person_ck CHECK (((approver_id IS NULL) OR (approver_id <> requester_id)))
);

ALTER TABLE ONLY public.cap_config_publish_request FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_config_publish_request; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_config_publish_request IS '配置变更申请单（P6R4 管理端点）：承载**待审批**的发布 / 回滚 / 生命周期迁移方案。双人复核的载体 —— 申请与审批是两次独立的、带身份的调用。';


--
-- Name: COLUMN cap_config_publish_request.state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_config_publish_request.state IS '审批流程状态（PENDING/APPROVED/REJECTED/APPLIED/FAILED）；**不是**配置对象自身的生命周期（见 cap_*_def.lifecycle_state）。';


--
-- Name: cap_config_publish_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.cap_config_publish_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: SEQUENCE cap_config_publish_seq; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON SEQUENCE public.cap_config_publish_seq IS '配置发布全局单调序号（§6.3）。仅用于追溯发布事件，不用于选择当前版本。';


--
-- Name: cap_config_release; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_config_release (
    release_id bigint NOT NULL,
    config_publish_seq bigint NOT NULL,
    scope text NOT NULL,
    tenant_id uuid,
    object_versions jsonb NOT NULL,
    aggregate_fingerprint text,
    status public.cap_release_status DEFAULT 'PREPARING'::public.cap_release_status NOT NULL,
    requester_id text,
    approver_id text,
    effective_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT cap_config_release_scope_ck CHECK ((((scope = 'PLATFORM'::text) AND (tenant_id IS NULL)) OR ((scope = 'TENANT'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT cap_config_release_two_person_ck CHECK (((approver_id IS NULL) OR (requester_id IS NULL) OR (approver_id <> requester_id)))
);

ALTER TABLE ONLY public.cap_config_release FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_config_release; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_config_release IS '配置发布头（§6.1 / 06 §1.7）。当前版本只认**指针指向的 PUBLISHED release**，绝不以 max(seq) 选择，也绝不扫描历史行判断当前。';


--
-- Name: cap_config_release_release_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.cap_config_release ALTER COLUMN release_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.cap_config_release_release_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: cap_decision_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_decision_log (
    decision_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    run_id uuid,
    invocation_id text NOT NULL,
    authz_decision text NOT NULL,
    exec_decision text NOT NULL,
    exec_status text NOT NULL,
    ledger_status text NOT NULL,
    reason_code text NOT NULL,
    authz_version bigint,
    closure_fingerprint text,
    config_publish_seq bigint,
    release_id bigint,
    principal_id text,
    tool_id text,
    decision_kind text DEFAULT 'INVOCATION'::text NOT NULL,
    phase text DEFAULT 'A'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT cap_decision_log_authz_ck CHECK ((authz_decision = ANY (ARRAY['ALLOW'::text, 'DENY'::text]))),
    CONSTRAINT cap_decision_log_exec_ck CHECK ((exec_decision = ANY (ARRAY['ALLOW'::text, 'DENY'::text, 'NOT_EVALUATED'::text]))),
    CONSTRAINT cap_decision_log_invocation_ck CHECK (((decision_kind <> 'INVOCATION'::text) OR (run_id IS NOT NULL))),
    CONSTRAINT cap_decision_log_kind_ck CHECK ((decision_kind = ANY (ARRAY['INVOCATION'::text, 'PUBLISH'::text, 'ROLLBACK'::text, 'LIFECYCLE'::text]))),
    CONSTRAINT cap_decision_log_ledger_ck CHECK ((ledger_status = ANY (ARRAY['PENDING'::text, 'RECORDED'::text, 'RETRYING'::text, 'NEEDS_MANUAL'::text]))),
    CONSTRAINT cap_decision_log_status_ck CHECK ((exec_status = ANY (ARRAY['NOT_STARTED'::text, 'RUNNING'::text, 'SUCCEEDED'::text, 'FAILED'::text, 'UNKNOWN'::text])))
);

ALTER TABLE ONLY public.cap_decision_log FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_decision_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_decision_log IS '四字段决策日志 + ledger 维度（§5.1 / §7.4 / 08 §7）。四字段 NOT NULL 从存储层强制「完整率 100%」；本表不存任何业务参数，天然脱敏（NFR-06）。';


--
-- Name: cap_execution_intent; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_execution_intent (
    intent_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid,
    invocation_id text NOT NULL,
    tool_id text NOT NULL,
    tool_version integer,
    args_fingerprint text,
    approval_id text,
    intent_state public.cap_intent_state DEFAULT 'RESERVED'::public.cap_intent_state NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.cap_execution_intent FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_execution_intent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_execution_intent IS '执行意图记录（§6.1）。必须先于外部副作用写入（P2 步骤 13a）；崩溃恢复据此区分"未执行"与"结果未知"。';


--
-- Name: cap_mcp_server_def; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_mcp_server_def (
    id text NOT NULL,
    version integer NOT NULL,
    transport text NOT NULL,
    endpoint text,
    command text,
    auth_kind text NOT NULL,
    trust_tier text NOT NULL,
    exposed_tool_allowlist jsonb,
    connection_risk text,
    dynamic_discovery boolean DEFAULT false NOT NULL,
    config_fingerprint text NOT NULL,
    credential_ref text,
    schema_version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    lifecycle_state public.cap_lifecycle_state DEFAULT 'DRAFT'::public.cap_lifecycle_state NOT NULL,
    CONSTRAINT cap_mcp_server_def_auth_ck CHECK ((auth_kind = ANY (ARRAY['token'::text, 'oauth'::text, 'none'::text]))),
    CONSTRAINT cap_mcp_server_def_endpoint_ck CHECK ((((transport = ANY (ARRAY['http'::text, 'sse'::text])) AND (endpoint IS NOT NULL)) OR ((transport = 'stdio'::text) AND (command IS NOT NULL)))),
    CONSTRAINT cap_mcp_server_def_no_dynamic_ck CHECK ((dynamic_discovery = false)),
    CONSTRAINT cap_mcp_server_def_transport_ck CHECK ((transport = ANY (ARRAY['stdio'::text, 'http'::text, 'sse'::text]))),
    CONSTRAINT cap_mcp_server_def_trust_ck CHECK ((trust_tier = ANY (ARRAY['internal'::text, 'partner'::text, 'public'::text]))),
    CONSTRAINT cap_mcp_server_def_version_positive CHECK ((version > 0))
);


--
-- Name: TABLE cap_mcp_server_def; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_mcp_server_def IS 'MCP Server 定义（platform 级，§6.1）。dynamic_discovery 恒为 false（N4）；凭据仅存 credential_ref。';


--
-- Name: COLUMN cap_mcp_server_def.lifecycle_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_mcp_server_def.lifecycle_state IS '生命周期状态（§6.6）：DRAFT/VALIDATED/PUBLISHED/DEPRECATED。';


--
-- Name: cap_result_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_result_event (
    event_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid,
    invocation_id text NOT NULL,
    terminal_event_id text,
    result_status public.cap_result_status NOT NULL,
    result_payload jsonb,
    reason_code text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.cap_result_event FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_result_event; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_result_event IS '工具调用的确定结果事件（§6.1）。UNIQUE(tenant_id, invocation_id) 是"只重试落账不重执行"的存储层保障。';


--
-- Name: cap_skill_def; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_skill_def (
    id text NOT NULL,
    version integer NOT NULL,
    name text,
    description text,
    declared_tools jsonb DEFAULT '[]'::jsonb NOT NULL,
    declared_mcp jsonb DEFAULT '[]'::jsonb NOT NULL,
    risk_ceiling text NOT NULL,
    param_policies jsonb DEFAULT '{}'::jsonb NOT NULL,
    inherits jsonb,
    required_caps jsonb DEFAULT '[]'::jsonb NOT NULL,
    namespace text DEFAULT 'platform'::text NOT NULL,
    config_fingerprint text NOT NULL,
    schema_version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    lifecycle_state public.cap_lifecycle_state DEFAULT 'DRAFT'::public.cap_lifecycle_state NOT NULL,
    CONSTRAINT cap_skill_def_namespace_ck CHECK ((namespace = 'platform'::text)),
    CONSTRAINT cap_skill_def_no_inherits_ck CHECK ((inherits IS NULL)),
    CONSTRAINT cap_skill_def_risk_ck CHECK ((risk_ceiling = ANY (ARRAY['READ'::text, 'WRITE_PROFILE'::text, 'WRITE_EXTERNAL'::text]))),
    CONSTRAINT cap_skill_def_version_positive CHECK ((version > 0))
);


--
-- Name: TABLE cap_skill_def; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_skill_def IS 'Skill 定义（platform 级，§6.1）。inherits 在 V1 写入即拒（N1）。';


--
-- Name: COLUMN cap_skill_def.lifecycle_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_skill_def.lifecycle_state IS '生命周期状态（§6.6）：DRAFT/VALIDATED/PUBLISHED/DEPRECATED。';


--
-- Name: cap_tool_def; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_tool_def (
    id text NOT NULL,
    version integer NOT NULL,
    name text,
    kind text NOT NULL,
    risk_level text NOT NULL,
    mcp_server_id text,
    mcp_tool_name text,
    input_schema jsonb DEFAULT '{}'::jsonb NOT NULL,
    param_policies jsonb DEFAULT '{}'::jsonb NOT NULL,
    namespace text DEFAULT 'platform'::text NOT NULL,
    config_fingerprint text NOT NULL,
    schema_version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    lifecycle_state public.cap_lifecycle_state DEFAULT 'DRAFT'::public.cap_lifecycle_state NOT NULL,
    CONSTRAINT cap_tool_def_kind_ck CHECK ((kind = ANY (ARRAY['native'::text, 'mcp'::text]))),
    CONSTRAINT cap_tool_def_mcp_binding_ck CHECK ((((kind = 'mcp'::text) AND (mcp_server_id IS NOT NULL) AND (mcp_tool_name IS NOT NULL)) OR ((kind = 'native'::text) AND (mcp_server_id IS NULL) AND (mcp_tool_name IS NULL)))),
    CONSTRAINT cap_tool_def_namespace_ck CHECK ((namespace = 'platform'::text)),
    CONSTRAINT cap_tool_def_risk_ck CHECK ((risk_level = ANY (ARRAY['READ'::text, 'WRITE_PROFILE'::text, 'WRITE_EXTERNAL'::text]))),
    CONSTRAINT cap_tool_def_version_positive CHECK ((version > 0))
);


--
-- Name: TABLE cap_tool_def; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_tool_def IS 'Tool 定义（platform 级，§6.1）。(id,version) 不可变版本行；当前生效版本由 cap_config_release 解析。';


--
-- Name: COLUMN cap_tool_def.lifecycle_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_tool_def.lifecycle_state IS '生命周期状态（§6.6）：DRAFT/VALIDATED/PUBLISHED/DEPRECATED。';


--
-- Name: config_change_request; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.config_change_request (
    change_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    config_key text NOT NULL,
    proposed_value text NOT NULL,
    value_type text NOT NULL,
    reason text NOT NULL,
    requester_id text NOT NULL,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    state public.config_change_state DEFAULT 'PENDING'::public.config_change_state NOT NULL,
    approver_id text,
    decided_at timestamp with time zone,
    decision_note text,
    applied_at timestamp with time zone,
    CONSTRAINT chg_applied_has_timestamp CHECK (((state = 'APPLIED'::public.config_change_state) = (applied_at IS NOT NULL))),
    CONSTRAINT chg_decision_fields_consistent CHECK ((((approver_id IS NULL) AND (decided_at IS NULL)) OR ((approver_id IS NOT NULL) AND (decided_at IS NOT NULL)))),
    CONSTRAINT chg_no_self_approval CHECK (((approver_id IS NULL) OR (approver_id <> requester_id))),
    CONSTRAINT config_change_request_reason_check CHECK ((length(btrim(reason)) > 0)),
    CONSTRAINT config_change_request_value_type_check CHECK ((value_type = ANY (ARRAY['INTEGER'::text, 'DECIMAL'::text, 'ENUM'::text, 'DURATION'::text])))
);

ALTER TABLE ONLY public.config_change_request FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE config_change_request; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.config_change_request IS 'P4-D16 高危配置变更审批（L3 键）。PENDING→APPROVED→APPLIED 两步，APPROVED 与 APPLIED 分离以免「批了」被误读为「已生效」';


--
-- Name: consent_withdrawal; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.consent_withdrawal (
    withdrawal_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    student_id text NOT NULL,
    withdrawn_at timestamp with time zone DEFAULT now() NOT NULL,
    due_at timestamp with time zone NOT NULL,
    state text DEFAULT 'PENDING'::text NOT NULL,
    erased_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT consent_due_after_withdraw_chk CHECK ((due_at >= withdrawn_at)),
    CONSTRAINT consent_state_chk CHECK ((state = ANY (ARRAY['PENDING'::text, 'ERASED'::text, 'FAILED'::text]))),
    CONSTRAINT consent_student_id_format_chk CHECK ((student_id ~ '^stu_[a-z0-9]{16}$'::text))
);

ALTER TABLE ONLY public.consent_withdrawal FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE consent_withdrawal; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.consent_withdrawal IS '监护人撤回同意登记（FR-019）。登记后应在 due_at 前完成删除，完成情况由 state 与 erasure_certificate 共同举证。';


--
-- Name: COLUMN consent_withdrawal.due_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.consent_withdrawal.due_at IS '删除截止时点 = withdrawn_at + 宽限期（默认 30 天）。宽限期是**上限**：允许提前删，不得超过。';


--
-- Name: erasure_certificate; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.erasure_certificate (
    certificate_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    reason text NOT NULL,
    actor text NOT NULL,
    executed_at timestamp with time zone DEFAULT now() NOT NULL,
    window_key text NOT NULL,
    subject_key text NOT NULL,
    detail jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT erasure_actor_nonempty_chk CHECK (((char_length(actor) >= 1) AND (char_length(actor) <= 128))),
    CONSTRAINT erasure_reason_chk CHECK ((reason = ANY (ARRAY['RETENTION_EXPIRED'::text, 'CONSENT_WITHDRAWN'::text]))),
    CONSTRAINT erasure_subject_key_nonempty_chk CHECK (((char_length(subject_key) >= 1) AND (char_length(subject_key) <= 128))),
    CONSTRAINT erasure_window_key_chk CHECK ((window_key ~ '^\d{4}-\d{2}-\d{2}$'::text))
);

ALTER TABLE ONLY public.erasure_certificate FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE erasure_certificate; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.erasure_certificate IS '删除凭证（P4-D04）。保留期到期清理与撤回同意删除都必须留凭证，否则「我们按承诺删了」在合规举证中不成立。本表刻意**不设**自动清理：自清理会销毁自己的证据链（见 P4-D04 边界 L-4）。';


--
-- Name: execution_capability_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.execution_capability_snapshot (
    run_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    effective_closure jsonb NOT NULL,
    agent_version integer NOT NULL,
    config_version bigint NOT NULL,
    config_fingerprint text NOT NULL,
    authz_version bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT execution_capability_snapshot_version_positive CHECK ((agent_version > 0))
);

ALTER TABLE ONLY public.execution_capability_snapshot FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE execution_capability_snapshot; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.execution_capability_snapshot IS 'Run 级能力闭包快照（§6.1 / 06 §1.6）。config_version 与 config_fingerprint 并存，分别承担版本追溯与内容一致性校验。';


--
-- Name: execution_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.execution_snapshot (
    snapshot_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    version_id uuid NOT NULL,
    agent_config jsonb NOT NULL,
    prompt_config jsonb NOT NULL,
    tool_contracts jsonb NOT NULL,
    run_config jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.execution_snapshot FORCE ROW LEVEL SECURITY;


--
-- Name: COLUMN execution_snapshot.run_config; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.execution_snapshot.run_config IS 'DEC-9 配置快照：maxCostPerRun/maxLlmCalls/maxSteps/approvalRequired。Run 创建时固化，改配置只影响新 Run。审计导出不得含学生可识别信息';


--
-- Name: idempotency_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.idempotency_record (
    record_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    key_namespace text NOT NULL,
    idempotency_key text NOT NULL,
    request_digest text NOT NULL,
    resource_type text NOT NULL,
    resource_id uuid,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT idempotency_digest_consistent CHECK ((length(request_digest) > 0))
);

ALTER TABLE ONLY public.idempotency_record FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE idempotency_record; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.idempotency_record IS 'ADR-003 幂等键命名空间。键的构造规则见 P1-D05 幂等与三层 Attempt 规范。同键同摘要返回原 resource_id；同键异摘要返回冲突';


--
-- Name: object_access_grant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.object_access_grant (
    grant_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    object_key text NOT NULL,
    principal_id text NOT NULL,
    permission text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.object_access_grant FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE object_access_grant; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.object_access_grant IS '预签名 URL 授权记录。每次读取须重新校验当前授权，撤权后旧引用立即失效';


--
-- Name: outbox_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outbox_event (
    outbox_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    state public.outbox_state DEFAULT 'PENDING'::public.outbox_state NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    last_error text,
    published_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT outbox_attempts_non_negative CHECK ((attempts >= 0))
);

ALTER TABLE ONLY public.outbox_event FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE outbox_event; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.outbox_event IS '与 Run 创建在同一事务写入（红线 3）。发布失败可重试；重复投递由消费端幂等处理';


--
-- Name: principal; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.principal (
    principal_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    login_name text NOT NULL,
    password_hash text NOT NULL,
    display_name text,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    failed_attempts integer DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    authz_version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT principal_authz_version_positive CHECK ((authz_version >= 1)),
    CONSTRAINT principal_failed_attempts_nonneg CHECK ((failed_attempts >= 0)),
    CONSTRAINT principal_login_name_format CHECK ((login_name ~ '^[a-z0-9][a-z0-9._-]{2,63}$'::text)),
    CONSTRAINT principal_password_hash_present CHECK ((password_hash ~~ '$pbkdf2-sha256$i=%$%'::text)),
    CONSTRAINT principal_status_enum CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'DISABLED'::text, 'LOCKED'::text])))
);

ALTER TABLE ONLY public.principal FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE principal; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.principal IS 'D-001 平台自建认证的账号主体表。刻意不采集邮箱/手机号/真实姓名（PIPL + DEC-6）。login_name 全局唯一以防跨租户撞名。';


--
-- Name: COLUMN principal.password_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.principal.password_hash IS 'PBKDF2-HMAC-SHA256 哈希。任何查询路径都不得返回此列到 API 响应或日志。';


--
-- Name: COLUMN principal.authz_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.principal.authz_version IS '授权版本，撤销授权时递增。执行时重新授权据此拒绝陈旧上下文（红线 9）。';


--
-- Name: principal_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.principal_role (
    tenant_id uuid NOT NULL,
    principal_id uuid NOT NULL,
    role text NOT NULL,
    granted_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT principal_role_not_blank CHECK ((role ~ '^[A-Z][A-Z_]{1,31}$'::text))
);

ALTER TABLE ONLY public.principal_role FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE principal_role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.principal_role IS 'D-001 账号角色。这是「防止令牌自封管理员」的权威来源：JWT 中的 roles 仅为声明，实际生效角色以此表为准。';


--
-- Name: rate_limit_bucket; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rate_limit_bucket (
    tenant_id uuid NOT NULL,
    bucket_key text NOT NULL,
    window_start timestamp with time zone NOT NULL,
    count bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT rate_limit_bucket_count_nonneg CHECK ((count >= 0)),
    CONSTRAINT rate_limit_bucket_key_valid CHECK ((bucket_key <> ''::text))
);

ALTER TABLE ONLY public.rate_limit_bucket FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE rate_limit_bucket; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.rate_limit_bucket IS '固定窗口限流计数（P3-D08 / D-2 预算击穿）。UNIQUE(tenant_id,bucket_key,window_start) 是原子 upsert 的基础，不可移除；index idx_rate_limit_cleanup 为过期窗口清理入口，本层刻意不提供自动清理调度器。';


--
-- Name: run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.run (
    run_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    idempotency_key text NOT NULL,
    request_digest text NOT NULL,
    state public.run_state DEFAULT 'QUEUED'::public.run_state NOT NULL,
    fencing_token bigint DEFAULT 0 NOT NULL,
    lease_owner text,
    lease_expires_at timestamp with time zone,
    attempt integer DEFAULT 0 NOT NULL,
    cancel_requested_at timestamp with time zone,
    input_payload jsonb NOT NULL,
    result_payload jsonb,
    error_code text,
    error_message text,
    correlation_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT run_attempt_non_negative CHECK ((attempt >= 0)),
    CONSTRAINT run_completed_at_consistent CHECK ((((state = ANY (ARRAY['QUEUED'::public.run_state, 'RUNNING'::public.run_state])) AND (completed_at IS NULL)) OR ((state = ANY (ARRAY['CANCELLED'::public.run_state, 'SUCCEEDED'::public.run_state, 'FAILED'::public.run_state, 'TIMED_OUT'::public.run_state])) AND (completed_at IS NOT NULL)))),
    CONSTRAINT run_fencing_non_negative CHECK ((fencing_token >= 0)),
    CONSTRAINT run_result_consistent CHECK (((state <> 'SUCCEEDED'::public.run_state) OR (result_payload IS NOT NULL)))
);

ALTER TABLE ONLY public.run FORCE ROW LEVEL SECURITY;


--
-- Name: run_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.run_event (
    event_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    sequence_no bigint NOT NULL,
    event_type text NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT run_event_seq_positive CHECK ((sequence_no > 0))
);

ALTER TABLE ONLY public.run_event FORCE ROW LEVEL SECURITY;


--
-- Name: run_step; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.run_step (
    step_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    step_number integer NOT NULL,
    state public.run_state NOT NULL,
    step_type text NOT NULL,
    input_payload jsonb,
    output_payload jsonb,
    error_class text,
    fencing_token bigint NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT run_step_number_positive CHECK ((step_number > 0))
);

ALTER TABLE ONLY public.run_step FORCE ROW LEVEL SECURITY;


--
-- Name: student_profile; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.student_profile (
    student_id text NOT NULL,
    tenant_id uuid NOT NULL,
    grade text NOT NULL,
    subject text NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT student_profile_grade_chk CHECK ((grade = ANY (ARRAY['初一'::text, '初二'::text, '初三'::text]))),
    CONSTRAINT student_profile_id_format_chk CHECK ((student_id ~ '^stu_[a-z0-9]{16}$'::text)),
    CONSTRAINT student_profile_subject_chk CHECK ((subject = ANY (ARRAY['数学'::text, '物理'::text, '化学'::text, '语文'::text, '英语'::text, '生物'::text])))
);

ALTER TABLE ONLY public.student_profile FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE student_profile; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.student_profile IS '学生画像（DEC-6 白名单）。刻意不含姓名/学校/班级/联系方式——不是「暂未采集」，而是**结构上无法采集**，从源头杜绝越界。';


--
-- Name: COLUMN student_profile.student_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.student_profile.student_id IS '随机标识，不可反推身份。禁止使用姓名、学号等可识别值。';


--
-- Name: COLUMN student_profile.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.student_profile.updated_at IS '画像保留期 12 个月，由清理任务按此字段判定。';


--
-- Name: tenant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant (
    tenant_id uuid DEFAULT gen_random_uuid() NOT NULL,
    slug text NOT NULL,
    name text NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT tenant_slug_format CHECK ((slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'::text))
);


--
-- Name: TABLE tenant; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.tenant IS '租户。注意：本表为全局表，不启用 RLS（租户自身即隔离单元）';


--
-- Name: tenant_config_override; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_config_override (
    tenant_id uuid NOT NULL,
    config_key text NOT NULL,
    value_text text NOT NULL,
    value_type text NOT NULL,
    reason text NOT NULL,
    changed_by text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT tenant_config_override_reason_check CHECK ((length(btrim(reason)) > 0)),
    CONSTRAINT tenant_config_override_value_type_check CHECK ((value_type = ANY (ARRAY['INTEGER'::text, 'DECIMAL'::text, 'ENUM'::text, 'DURATION'::text])))
);

ALTER TABLE ONLY public.tenant_config_override FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE tenant_config_override; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.tenant_config_override IS 'P4-D15 租户配置覆盖层：仅存覆盖值，缺省回落 yml 默认值；生效机制为 Run 快照隔离（R-021）';


--
-- Name: tool_operation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tool_operation (
    operation_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    step_id uuid,
    tool_name text NOT NULL,
    risk_level public.tool_risk_level NOT NULL,
    state public.tool_operation_state DEFAULT 'PENDING'::public.tool_operation_state NOT NULL,
    idempotency_key text NOT NULL,
    request_digest text NOT NULL,
    tool_arguments jsonb NOT NULL,
    result_payload jsonb,
    fencing_token bigint NOT NULL,
    reconciliation_source text,
    reconciled_at timestamp with time zone,
    manual_disposed_by text,
    manual_disposed_at timestamp with time zone,
    budget_reservation_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT tool_operation_manual_needs_actor CHECK (((state <> 'MANUAL_DISPOSED'::public.tool_operation_state) OR ((manual_disposed_by IS NOT NULL) AND (manual_disposed_at IS NOT NULL)))),
    CONSTRAINT tool_operation_unknown_needs_disposition CHECK (((state <> 'RECONCILED_SUCCEEDED'::public.tool_operation_state) OR (reconciled_at IS NOT NULL)))
);

ALTER TABLE ONLY public.tool_operation FORCE ROW LEVEL SECURITY;


--
-- Name: COLUMN tool_operation.budget_reservation_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.tool_operation.budget_reservation_id IS '关联预算预留。UNKNOWN 状态下不得自动释放预留——成本可能已发生（R-007）';


--
-- Name: tool_operation_attempt; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tool_operation_attempt (
    attempt_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    state public.tool_operation_state NOT NULL,
    fencing_token bigint NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    error_class text,
    error_message text,
    result_unknown boolean DEFAULT false NOT NULL,
    CONSTRAINT tool_attempt_number_positive CHECK ((attempt_number > 0)),
    CONSTRAINT tool_attempt_unknown_flag CHECK (((state <> 'UNKNOWN'::public.tool_operation_state) OR (result_unknown = true)))
);

ALTER TABLE ONLY public.tool_operation_attempt FORCE ROW LEVEL SECURITY;


--
-- Name: usage_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.usage_record (
    usage_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    step_id uuid,
    operation_id uuid,
    model_id text NOT NULL,
    input_tokens bigint DEFAULT 0 NOT NULL,
    output_tokens bigint DEFAULT 0 NOT NULL,
    cost_amount numeric(18,6) DEFAULT 0 NOT NULL,
    currency text DEFAULT 'CNY'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    reservation_id uuid,
    CONSTRAINT usage_tokens_non_negative CHECK (((input_tokens >= 0) AND (output_tokens >= 0)))
);

ALTER TABLE ONLY public.usage_record FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE usage_record; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.usage_record IS '模型用量账。幂等：以 (tenant_id, operation_id) 防止重复结算同一逻辑操作';


--
-- Name: COLUMN usage_record.reservation_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.usage_record.reservation_id IS '关联的预算预留。对账时用于「计量明细 ↔ 预留明细」双向追溯';


--
-- Name: weakness_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.weakness_record (
    weakness_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    student_id text NOT NULL,
    run_id uuid NOT NULL,
    knowledge_point text NOT NULL,
    error_type text NOT NULL,
    confidence numeric(3,2),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT weakness_confidence_chk CHECK (((confidence IS NULL) OR ((confidence >= (0)::numeric) AND (confidence <= (1)::numeric)))),
    CONSTRAINT weakness_error_type_chk CHECK ((error_type = ANY (ARRAY['概念不清'::text, '公式记错'::text, '符号错误'::text, '计算失误'::text, '审题偏差'::text, '步骤缺失'::text]))),
    CONSTRAINT weakness_kp_length_chk CHECK (((char_length(knowledge_point) >= 1) AND (char_length(knowledge_point) <= 100)))
);

ALTER TABLE ONLY public.weakness_record FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE weakness_record; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.weakness_record IS '薄弱点记录。首期唯一的有副作用工具 record_weakness 的落点，用于实证验证红线 4/5/7（Attempt 区分、fencing、UNKNOWN 对账）。';


--
-- Name: agent_message_dead_letter agent_message_dead_letter_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_message_dead_letter
    ADD CONSTRAINT agent_message_dead_letter_pkey PRIMARY KEY (dead_id);


--
-- Name: agent_message_queue agent_message_queue_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_message_queue
    ADD CONSTRAINT agent_message_queue_pkey PRIMARY KEY (queue_id);


--
-- Name: agent agent_name_per_tenant; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent
    ADD CONSTRAINT agent_name_per_tenant UNIQUE (tenant_id, name);


--
-- Name: agent agent_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent
    ADD CONSTRAINT agent_pkey PRIMARY KEY (agent_id);


--
-- Name: agent agent_tenant_agent_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent
    ADD CONSTRAINT agent_tenant_agent_unique UNIQUE (tenant_id, agent_id);


--
-- Name: agent_version agent_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_version
    ADD CONSTRAINT agent_version_pkey PRIMARY KEY (version_id);


--
-- Name: agent_version agent_version_tenant_version_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_version
    ADD CONSTRAINT agent_version_tenant_version_unique UNIQUE (tenant_id, version_id);


--
-- Name: agent_version agent_version_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_version
    ADD CONSTRAINT agent_version_unique UNIQUE (tenant_id, agent_id, version_number);


--
-- Name: CONSTRAINT agent_version_unique ON agent_version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT agent_version_unique ON public.agent_version IS '复合唯一约束：同一租户下 agent 版本号唯一。跨租户 agent_id 相同不冲突';


--
-- Name: approval approval_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval
    ADD CONSTRAINT approval_pkey PRIMARY KEY (approval_id);


--
-- Name: audit_record audit_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_record
    ADD CONSTRAINT audit_record_pkey PRIMARY KEY (audit_id);


--
-- Name: budget_account budget_account_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_account
    ADD CONSTRAINT budget_account_pkey PRIMARY KEY (account_id);


--
-- Name: budget_account budget_account_tenant_account_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_account
    ADD CONSTRAINT budget_account_tenant_account_unique UNIQUE (tenant_id, account_id);


--
-- Name: budget_account budget_account_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_account
    ADD CONSTRAINT budget_account_unique UNIQUE (tenant_id, currency);


--
-- Name: budget_reservation budget_reservation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_reservation
    ADD CONSTRAINT budget_reservation_pkey PRIMARY KEY (reservation_id);


--
-- Name: cap_agent_def cap_agent_def_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_agent_def
    ADD CONSTRAINT cap_agent_def_pk PRIMARY KEY (tenant_id, agent_id, version);


--
-- Name: cap_approval cap_approval_invocation_tool_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_approval
    ADD CONSTRAINT cap_approval_invocation_tool_unique UNIQUE (tenant_id, invocation_id, tool_id);


--
-- Name: cap_approval cap_approval_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_approval
    ADD CONSTRAINT cap_approval_pkey PRIMARY KEY (approval_id);


--
-- Name: cap_authz_version cap_authz_version_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_authz_version
    ADD CONSTRAINT cap_authz_version_pk PRIMARY KEY (tenant_id, principal_id);


--
-- Name: cap_compensation_task cap_compensation_target_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_compensation_task
    ADD CONSTRAINT cap_compensation_target_unique UNIQUE (tenant_id, kind, target_id);


--
-- Name: cap_compensation_task cap_compensation_task_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_compensation_task
    ADD CONSTRAINT cap_compensation_task_pkey PRIMARY KEY (task_id);


--
-- Name: cap_config_pointer cap_config_pointer_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_config_pointer
    ADD CONSTRAINT cap_config_pointer_pkey PRIMARY KEY (pointer_key);


--
-- Name: cap_config_publish_request cap_config_publish_request_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_config_publish_request
    ADD CONSTRAINT cap_config_publish_request_pkey PRIMARY KEY (request_id);


--
-- Name: cap_config_release cap_config_release_config_publish_seq_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_config_release
    ADD CONSTRAINT cap_config_release_config_publish_seq_key UNIQUE (config_publish_seq);


--
-- Name: cap_config_release cap_config_release_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_config_release
    ADD CONSTRAINT cap_config_release_pkey PRIMARY KEY (release_id);


--
-- Name: cap_decision_log cap_decision_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_decision_log
    ADD CONSTRAINT cap_decision_log_pkey PRIMARY KEY (decision_id);


--
-- Name: cap_execution_intent cap_execution_intent_invocation_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_execution_intent
    ADD CONSTRAINT cap_execution_intent_invocation_unique UNIQUE (tenant_id, invocation_id);


--
-- Name: cap_execution_intent cap_execution_intent_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_execution_intent
    ADD CONSTRAINT cap_execution_intent_pkey PRIMARY KEY (intent_id);


--
-- Name: cap_mcp_server_def cap_mcp_server_def_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_mcp_server_def
    ADD CONSTRAINT cap_mcp_server_def_pk PRIMARY KEY (id, version);


--
-- Name: cap_result_event cap_result_event_invocation_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_result_event
    ADD CONSTRAINT cap_result_event_invocation_unique UNIQUE (tenant_id, invocation_id);


--
-- Name: cap_result_event cap_result_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_result_event
    ADD CONSTRAINT cap_result_event_pkey PRIMARY KEY (event_id);


--
-- Name: cap_skill_def cap_skill_def_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_skill_def
    ADD CONSTRAINT cap_skill_def_pk PRIMARY KEY (id, version);


--
-- Name: cap_tool_def cap_tool_def_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_tool_def
    ADD CONSTRAINT cap_tool_def_pk PRIMARY KEY (id, version);


--
-- Name: config_change_request config_change_request_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.config_change_request
    ADD CONSTRAINT config_change_request_pkey PRIMARY KEY (change_id);


--
-- Name: consent_withdrawal consent_student_uniq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consent_withdrawal
    ADD CONSTRAINT consent_student_uniq UNIQUE (tenant_id, student_id);


--
-- Name: consent_withdrawal consent_withdrawal_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consent_withdrawal
    ADD CONSTRAINT consent_withdrawal_pkey PRIMARY KEY (withdrawal_id);


--
-- Name: erasure_certificate erasure_certificate_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.erasure_certificate
    ADD CONSTRAINT erasure_certificate_pkey PRIMARY KEY (certificate_id);


--
-- Name: erasure_certificate erasure_idempotency_uniq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.erasure_certificate
    ADD CONSTRAINT erasure_idempotency_uniq UNIQUE (tenant_id, reason, window_key, subject_key);


--
-- Name: execution_capability_snapshot execution_capability_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.execution_capability_snapshot
    ADD CONSTRAINT execution_capability_snapshot_pkey PRIMARY KEY (run_id);


--
-- Name: execution_snapshot execution_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.execution_snapshot
    ADD CONSTRAINT execution_snapshot_pkey PRIMARY KEY (snapshot_id);


--
-- Name: execution_snapshot execution_snapshot_tenant_snapshot_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.execution_snapshot
    ADD CONSTRAINT execution_snapshot_tenant_snapshot_unique UNIQUE (tenant_id, snapshot_id);


--
-- Name: idempotency_record idempotency_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_record
    ADD CONSTRAINT idempotency_record_pkey PRIMARY KEY (record_id);


--
-- Name: idempotency_record idempotency_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_record
    ADD CONSTRAINT idempotency_unique UNIQUE (tenant_id, key_namespace, idempotency_key);


--
-- Name: object_access_grant object_access_grant_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.object_access_grant
    ADD CONSTRAINT object_access_grant_pkey PRIMARY KEY (grant_id);


--
-- Name: object_access_grant object_grant_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.object_access_grant
    ADD CONSTRAINT object_grant_unique UNIQUE (tenant_id, object_key, principal_id, permission);


--
-- Name: outbox_event outbox_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_event
    ADD CONSTRAINT outbox_event_pkey PRIMARY KEY (outbox_id);


--
-- Name: principal principal_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.principal
    ADD CONSTRAINT principal_pkey PRIMARY KEY (principal_id);


--
-- Name: rate_limit_bucket rate_limit_bucket_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rate_limit_bucket
    ADD CONSTRAINT rate_limit_bucket_unique UNIQUE (tenant_id, bucket_key, window_start);


--
-- Name: run_event run_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_event
    ADD CONSTRAINT run_event_pkey PRIMARY KEY (event_id);


--
-- Name: run_event run_event_seq_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_event
    ADD CONSTRAINT run_event_seq_unique UNIQUE (tenant_id, run_id, sequence_no);


--
-- Name: CONSTRAINT run_event_seq_unique ON run_event; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT run_event_seq_unique ON public.run_event IS 'SSE 续传依据：同一 Run 内序号唯一且单调递增。游标过期按协议重置';


--
-- Name: run run_idempotency_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run
    ADD CONSTRAINT run_idempotency_unique UNIQUE (tenant_id, idempotency_key);


--
-- Name: CONSTRAINT run_idempotency_unique ON run; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT run_idempotency_unique ON public.run IS 'ADR-003：同租户同幂等键只允许一个 Run。同键不同摘要由应用层判定为冲突';


--
-- Name: run run_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run
    ADD CONSTRAINT run_pkey PRIMARY KEY (run_id);


--
-- Name: run_step run_step_number_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_step
    ADD CONSTRAINT run_step_number_unique UNIQUE (tenant_id, run_id, step_number);


--
-- Name: run_step run_step_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_step
    ADD CONSTRAINT run_step_pkey PRIMARY KEY (step_id);


--
-- Name: run run_tenant_run_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run
    ADD CONSTRAINT run_tenant_run_unique UNIQUE (tenant_id, run_id);


--
-- Name: student_profile student_profile_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.student_profile
    ADD CONSTRAINT student_profile_pk PRIMARY KEY (tenant_id, student_id);


--
-- Name: tenant_config_override tenant_config_override_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_config_override
    ADD CONSTRAINT tenant_config_override_pkey PRIMARY KEY (tenant_id, config_key);


--
-- Name: tenant tenant_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_pkey PRIMARY KEY (tenant_id);


--
-- Name: tenant tenant_slug_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_slug_key UNIQUE (slug);


--
-- Name: tool_operation_attempt tool_attempt_number_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_operation_attempt
    ADD CONSTRAINT tool_attempt_number_unique UNIQUE (tenant_id, operation_id, attempt_number);


--
-- Name: tool_operation_attempt tool_operation_attempt_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_operation_attempt
    ADD CONSTRAINT tool_operation_attempt_pkey PRIMARY KEY (attempt_id);


--
-- Name: tool_operation tool_operation_idempotency_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_operation
    ADD CONSTRAINT tool_operation_idempotency_unique UNIQUE (tenant_id, idempotency_key);


--
-- Name: CONSTRAINT tool_operation_idempotency_unique ON tool_operation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT tool_operation_idempotency_unique ON public.tool_operation IS '幂等核心：同租户同幂等键只有一个逻辑操作。重复调用复用同一行而非新建';


--
-- Name: tool_operation tool_operation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_operation
    ADD CONSTRAINT tool_operation_pkey PRIMARY KEY (operation_id);


--
-- Name: tool_operation tool_operation_tenant_op_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_operation
    ADD CONSTRAINT tool_operation_tenant_op_unique UNIQUE (tenant_id, operation_id);


--
-- Name: usage_record usage_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.usage_record
    ADD CONSTRAINT usage_record_pkey PRIMARY KEY (usage_id);


--
-- Name: weakness_record weakness_idempotency_uniq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.weakness_record
    ADD CONSTRAINT weakness_idempotency_uniq UNIQUE (tenant_id, student_id, run_id, knowledge_point);


--
-- Name: CONSTRAINT weakness_idempotency_uniq ON weakness_record; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT weakness_idempotency_uniq ON public.weakness_record IS '幂等最后防线：同一 (租户,学生,Run,知识点) 只允许一条。应用层并发检查无法阻止竞态，此约束是唯一可靠保证。';


--
-- Name: weakness_record weakness_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.weakness_record
    ADD CONSTRAINT weakness_record_pkey PRIMARY KEY (weakness_id);


--
-- Name: idx_agent_message_dead_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_message_dead_tenant ON public.agent_message_dead_letter USING btree (tenant_id, failed_at);


--
-- Name: idx_agent_message_inflight; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_message_inflight ON public.agent_message_queue USING btree (claimed_at) WHERE (state = 'INFLIGHT'::public.agent_message_state);


--
-- Name: idx_agent_message_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_message_pending ON public.agent_message_queue USING btree (next_attempt_at, created_at) WHERE (state = 'PENDING'::public.agent_message_state);


--
-- Name: idx_agent_message_tenant_state; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_message_tenant_state ON public.agent_message_queue USING btree (tenant_id, state);


--
-- Name: idx_agent_version_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_version_agent ON public.agent_version USING btree (tenant_id, agent_id);


--
-- Name: idx_approval_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_pending ON public.approval USING btree (tenant_id, state) WHERE (state = 'PENDING'::public.approval_state);


--
-- Name: idx_approval_tenant_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_tenant_created ON public.approval USING btree (tenant_id, created_at DESC, approval_id DESC);


--
-- Name: idx_audit_tenant_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_tenant_time ON public.audit_record USING btree (tenant_id, created_at DESC);


--
-- Name: idx_audit_tenant_time_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_tenant_time_id ON public.audit_record USING btree (tenant_id, created_at DESC, audit_id DESC);


--
-- Name: idx_cap_agent_def_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_agent_def_tenant ON public.cap_agent_def USING btree (tenant_id, agent_id, version DESC);


--
-- Name: idx_cap_approval_invocation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_approval_invocation ON public.cap_approval USING btree (tenant_id, invocation_id) WHERE (invocation_id IS NOT NULL);


--
-- Name: idx_cap_approval_state; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_approval_state ON public.cap_approval USING btree (tenant_id, approval_state, created_at);


--
-- Name: idx_cap_approval_tool; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_approval_tool ON public.cap_approval USING btree (tenant_id, tool_id, created_at);


--
-- Name: idx_cap_compensation_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_compensation_pending ON public.cap_compensation_task USING btree (tenant_id, state, created_at);


--
-- Name: idx_cap_config_pointer_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_config_pointer_scope ON public.cap_config_pointer USING btree (scope, tenant_id);


--
-- Name: idx_cap_config_publish_request_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_config_publish_request_tenant ON public.cap_config_publish_request USING btree (tenant_id, requested_at DESC);


--
-- Name: idx_cap_config_release_current; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_config_release_current ON public.cap_config_release USING btree (scope, tenant_id, config_publish_seq DESC) WHERE (status = 'PUBLISHED'::public.cap_release_status);


--
-- Name: idx_cap_decision_log_invocation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_decision_log_invocation ON public.cap_decision_log USING btree (tenant_id, run_id, invocation_id, created_at);


--
-- Name: idx_cap_decision_log_release; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_decision_log_release ON public.cap_decision_log USING btree (tenant_id, release_id, created_at);


--
-- Name: idx_cap_execution_intent_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_execution_intent_pending ON public.cap_execution_intent USING btree (tenant_id, intent_state, created_at);


--
-- Name: idx_cap_execution_intent_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_execution_intent_run ON public.cap_execution_intent USING btree (tenant_id, run_id, created_at DESC);


--
-- Name: idx_cap_result_event_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_result_event_run ON public.cap_result_event USING btree (tenant_id, run_id, created_at DESC);


--
-- Name: idx_cap_tool_def_fingerprint; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_tool_def_fingerprint ON public.cap_tool_def USING btree (config_fingerprint);


--
-- Name: idx_config_change_tenant_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_config_change_tenant_created ON public.config_change_request USING btree (tenant_id, requested_at DESC, change_id DESC);


--
-- Name: idx_consent_pending_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_consent_pending_due ON public.consent_withdrawal USING btree (tenant_id, due_at) WHERE (state = 'PENDING'::text);


--
-- Name: idx_erasure_tenant_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_erasure_tenant_time ON public.erasure_certificate USING btree (tenant_id, executed_at DESC);


--
-- Name: idx_exec_cap_snapshot_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_exec_cap_snapshot_tenant ON public.execution_capability_snapshot USING btree (tenant_id, created_at DESC);


--
-- Name: idx_idempotency_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_idempotency_expiry ON public.idempotency_record USING btree (expires_at);


--
-- Name: idx_outbox_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_pending ON public.outbox_event USING btree (tenant_id, created_at) WHERE (state = 'PENDING'::public.outbox_state);


--
-- Name: idx_principal_tenant_login; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_principal_tenant_login ON public.principal USING btree (tenant_id, login_name);


--
-- Name: idx_rate_limit_cleanup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rate_limit_cleanup ON public.rate_limit_bucket USING btree (window_start);


--
-- Name: idx_reservation_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reservation_run ON public.budget_reservation USING btree (tenant_id, run_id);


--
-- Name: idx_reservation_state; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reservation_state ON public.budget_reservation USING btree (tenant_id, state) WHERE (state = 'RESERVED'::text);


--
-- Name: INDEX idx_reservation_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON INDEX public.idx_reservation_state IS '预算对账的扫描入口：只索引仍占额度的 RESERVED 行，已结算/已释放的不进入索引（体积随业务增长可控）';


--
-- Name: idx_run_correlation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_run_correlation ON public.run USING btree (tenant_id, correlation_id);


--
-- Name: idx_run_event_cursor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_run_event_cursor ON public.run_event USING btree (tenant_id, run_id, sequence_no);


--
-- Name: idx_run_lease; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_run_lease ON public.run USING btree (tenant_id, lease_expires_at) WHERE (state = 'RUNNING'::public.run_state);


--
-- Name: idx_run_state_queued; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_run_state_queued ON public.run USING btree (tenant_id, state) WHERE (state = 'QUEUED'::public.run_state);


--
-- Name: idx_run_step_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_run_step_run ON public.run_step USING btree (tenant_id, run_id, step_number);


--
-- Name: idx_run_tenant_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_run_tenant_created ON public.run USING btree (tenant_id, created_at DESC, run_id DESC);


--
-- Name: idx_tool_attempt_operation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tool_attempt_operation ON public.tool_operation_attempt USING btree (tenant_id, operation_id, attempt_number);


--
-- Name: idx_tool_operation_pending_reconcile; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tool_operation_pending_reconcile ON public.tool_operation USING btree (tenant_id, created_at) WHERE (state = 'UNKNOWN'::public.tool_operation_state);


--
-- Name: idx_tool_operation_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tool_operation_run ON public.tool_operation USING btree (tenant_id, run_id);


--
-- Name: idx_tool_operation_tenant_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tool_operation_tenant_created ON public.tool_operation USING btree (tenant_id, created_at DESC, operation_id DESC);


--
-- Name: idx_tool_operation_unknown; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tool_operation_unknown ON public.tool_operation USING btree (tenant_id, state) WHERE (state = 'UNKNOWN'::public.tool_operation_state);


--
-- Name: idx_usage_operation_once; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_usage_operation_once ON public.usage_record USING btree (tenant_id, operation_id) WHERE (operation_id IS NOT NULL);


--
-- Name: idx_usage_reservation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_usage_reservation ON public.usage_record USING btree (tenant_id, reservation_id) WHERE (reservation_id IS NOT NULL);


--
-- Name: idx_weakness_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_weakness_created ON public.weakness_record USING btree (created_at);


--
-- Name: idx_weakness_knowledge; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_weakness_knowledge ON public.weakness_record USING btree (tenant_id, knowledge_point);


--
-- Name: idx_weakness_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_weakness_run ON public.weakness_record USING btree (tenant_id, run_id);


--
-- Name: INDEX idx_weakness_run; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON INDEX public.idx_weakness_run IS '结果读取（G5）按 Run 检索薄弱点。外键不会自动为引用侧建索引，故须显式声明；缺失时按 run 查询会退化为全表扫。';


--
-- Name: idx_weakness_student; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_weakness_student ON public.weakness_record USING btree (tenant_id, student_id, updated_at DESC);


--
-- Name: part_uniq_reservation_operation; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX part_uniq_reservation_operation ON public.budget_reservation USING btree (tenant_id, operation_id) WHERE (operation_id IS NOT NULL);


--
-- Name: INDEX part_uniq_reservation_operation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON INDEX public.part_uniq_reservation_operation IS '同一 ToolOperation 至多一条预算预留。并发下「先查后插」的两个请求，后者由此唯一索引失败并回查复用——避免重复扣减额度（D-3）';


--
-- Name: uq_principal_login_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_principal_login_name ON public.principal USING btree (login_name);


--
-- Name: uq_principal_role; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_principal_role ON public.principal_role USING btree (tenant_id, principal_id, role);


--
-- Name: agent_message_queue trg_agent_message_queue_touch; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_agent_message_queue_touch BEFORE UPDATE ON public.agent_message_queue FOR EACH ROW EXECUTE FUNCTION public.touch_agent_message_queue_updated_at();


--
-- Name: approval trg_approval_state_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_approval_state_guard BEFORE UPDATE ON public.approval FOR EACH ROW EXECUTE FUNCTION public.enforce_approval_state_transition();


--
-- Name: audit_record trg_audit_record_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_record_append_only BEFORE DELETE OR UPDATE ON public.audit_record FOR EACH ROW EXECUTE FUNCTION public.forbid_audit_mutation();


--
-- Name: cap_agent_def trg_cap_agent_def_align_state; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cap_agent_def_align_state BEFORE INSERT OR UPDATE ON public.cap_agent_def FOR EACH ROW EXECUTE FUNCTION public.align_cap_lifecycle_state();


--
-- Name: cap_agent_def trg_cap_agent_def_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cap_agent_def_immutable BEFORE UPDATE ON public.cap_agent_def FOR EACH ROW EXECUTE FUNCTION public.forbid_published_def_mutation();


--
-- Name: cap_approval trg_cap_approval_state_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cap_approval_state_guard BEFORE UPDATE ON public.cap_approval FOR EACH ROW EXECUTE FUNCTION public.enforce_cap_approval_state_transition();


--
-- Name: cap_mcp_server_def trg_cap_mcp_server_def_align_state; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cap_mcp_server_def_align_state BEFORE INSERT OR UPDATE ON public.cap_mcp_server_def FOR EACH ROW EXECUTE FUNCTION public.align_cap_lifecycle_state();


--
-- Name: cap_mcp_server_def trg_cap_mcp_server_def_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cap_mcp_server_def_immutable BEFORE UPDATE ON public.cap_mcp_server_def FOR EACH ROW EXECUTE FUNCTION public.forbid_published_def_mutation();


--
-- Name: cap_skill_def trg_cap_skill_def_align_state; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cap_skill_def_align_state BEFORE INSERT OR UPDATE ON public.cap_skill_def FOR EACH ROW EXECUTE FUNCTION public.align_cap_lifecycle_state();


--
-- Name: cap_skill_def trg_cap_skill_def_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cap_skill_def_immutable BEFORE UPDATE ON public.cap_skill_def FOR EACH ROW EXECUTE FUNCTION public.forbid_published_def_mutation();


--
-- Name: cap_tool_def trg_cap_tool_def_align_state; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cap_tool_def_align_state BEFORE INSERT OR UPDATE ON public.cap_tool_def FOR EACH ROW EXECUTE FUNCTION public.align_cap_lifecycle_state();


--
-- Name: cap_tool_def trg_cap_tool_def_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cap_tool_def_immutable BEFORE UPDATE ON public.cap_tool_def FOR EACH ROW EXECUTE FUNCTION public.forbid_published_def_mutation();


--
-- Name: config_change_request trg_config_change_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_config_change_guard BEFORE UPDATE ON public.config_change_request FOR EACH ROW EXECUTE FUNCTION public.enforce_config_change_transition();


--
-- Name: tenant_config_override trg_config_override_touch; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_config_override_touch BEFORE UPDATE ON public.tenant_config_override FOR EACH ROW EXECUTE FUNCTION public.touch_tenant_config_override_updated_at();


--
-- Name: budget_reservation trg_enforce_reservation_state_transition; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_enforce_reservation_state_transition BEFORE UPDATE ON public.budget_reservation FOR EACH ROW EXECUTE FUNCTION public.enforce_reservation_state_transition();


--
-- Name: run trg_fencing_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_fencing_guard BEFORE UPDATE ON public.run FOR EACH ROW EXECUTE FUNCTION public.enforce_fencing_on_write();


--
-- Name: tool_operation_attempt trg_forbid_attempt_after_terminal; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_forbid_attempt_after_terminal BEFORE INSERT ON public.tool_operation_attempt FOR EACH ROW EXECUTE FUNCTION public.forbid_attempt_after_terminal();


--
-- Name: run trg_run_state_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_run_state_guard BEFORE UPDATE ON public.run FOR EACH ROW EXECUTE FUNCTION public.enforce_run_state_transition();


--
-- Name: execution_snapshot trg_snapshot_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_snapshot_immutable BEFORE DELETE OR UPDATE ON public.execution_snapshot FOR EACH ROW EXECUTE FUNCTION public.forbid_snapshot_mutation();


--
-- Name: agent_message_dead_letter agent_message_dead_letter_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_message_dead_letter
    ADD CONSTRAINT agent_message_dead_letter_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: agent_message_queue agent_message_queue_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_message_queue
    ADD CONSTRAINT agent_message_queue_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: agent_message_queue agent_message_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_message_queue
    ADD CONSTRAINT agent_message_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.run(tenant_id, run_id) ON DELETE CASCADE;


--
-- Name: agent agent_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent
    ADD CONSTRAINT agent_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: agent_version agent_version_agent_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_version
    ADD CONSTRAINT agent_version_agent_fk FOREIGN KEY (tenant_id, agent_id) REFERENCES public.agent(tenant_id, agent_id) ON DELETE CASCADE;


--
-- Name: approval approval_operation_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approval
    ADD CONSTRAINT approval_operation_fk FOREIGN KEY (tenant_id, operation_id) REFERENCES public.tool_operation(tenant_id, operation_id) ON DELETE CASCADE;


--
-- Name: budget_account budget_account_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_account
    ADD CONSTRAINT budget_account_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: cap_agent_def cap_agent_def_agent_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_agent_def
    ADD CONSTRAINT cap_agent_def_agent_fk FOREIGN KEY (tenant_id, agent_id) REFERENCES public.agent(tenant_id, agent_id) ON DELETE CASCADE;


--
-- Name: cap_approval cap_approval_operation_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_approval
    ADD CONSTRAINT cap_approval_operation_fk FOREIGN KEY (tenant_id, operation_id) REFERENCES public.tool_operation(tenant_id, operation_id) ON DELETE CASCADE;


--
-- Name: cap_approval cap_approval_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_approval
    ADD CONSTRAINT cap_approval_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.run(tenant_id, run_id) ON DELETE CASCADE;


--
-- Name: cap_config_pointer cap_config_pointer_current_release_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_config_pointer
    ADD CONSTRAINT cap_config_pointer_current_release_id_fkey FOREIGN KEY (current_release_id) REFERENCES public.cap_config_release(release_id);


--
-- Name: cap_execution_intent cap_execution_intent_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_execution_intent
    ADD CONSTRAINT cap_execution_intent_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.run(tenant_id, run_id) ON DELETE CASCADE;


--
-- Name: cap_result_event cap_result_event_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_result_event
    ADD CONSTRAINT cap_result_event_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.run(tenant_id, run_id) ON DELETE CASCADE;


--
-- Name: config_change_request config_change_request_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.config_change_request
    ADD CONSTRAINT config_change_request_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: consent_withdrawal consent_withdrawal_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consent_withdrawal
    ADD CONSTRAINT consent_withdrawal_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: erasure_certificate erasure_certificate_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.erasure_certificate
    ADD CONSTRAINT erasure_certificate_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: execution_capability_snapshot execution_capability_snapshot_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.execution_capability_snapshot
    ADD CONSTRAINT execution_capability_snapshot_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.run(tenant_id, run_id) ON DELETE CASCADE;


--
-- Name: execution_snapshot execution_snapshot_version_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.execution_snapshot
    ADD CONSTRAINT execution_snapshot_version_fk FOREIGN KEY (tenant_id, version_id) REFERENCES public.agent_version(tenant_id, version_id);


--
-- Name: outbox_event outbox_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_event
    ADD CONSTRAINT outbox_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.run(tenant_id, run_id) ON DELETE CASCADE;


--
-- Name: principal_role principal_role_principal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.principal_role
    ADD CONSTRAINT principal_role_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.principal(principal_id) ON DELETE CASCADE;


--
-- Name: principal principal_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.principal
    ADD CONSTRAINT principal_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: budget_reservation reservation_account_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_reservation
    ADD CONSTRAINT reservation_account_fk FOREIGN KEY (tenant_id, account_id) REFERENCES public.budget_account(tenant_id, account_id) ON DELETE RESTRICT;


--
-- Name: budget_reservation reservation_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_reservation
    ADD CONSTRAINT reservation_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.run(tenant_id, run_id) ON DELETE CASCADE;


--
-- Name: run_event run_event_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_event
    ADD CONSTRAINT run_event_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.run(tenant_id, run_id) ON DELETE CASCADE;


--
-- Name: run run_snapshot_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run
    ADD CONSTRAINT run_snapshot_fk FOREIGN KEY (tenant_id, snapshot_id) REFERENCES public.execution_snapshot(tenant_id, snapshot_id);


--
-- Name: run_step run_step_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_step
    ADD CONSTRAINT run_step_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.run(tenant_id, run_id) ON DELETE CASCADE;


--
-- Name: run run_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run
    ADD CONSTRAINT run_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: student_profile student_profile_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.student_profile
    ADD CONSTRAINT student_profile_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: tenant_config_override tenant_config_override_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_config_override
    ADD CONSTRAINT tenant_config_override_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: tool_operation_attempt tool_attempt_operation_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_operation_attempt
    ADD CONSTRAINT tool_attempt_operation_fk FOREIGN KEY (tenant_id, operation_id) REFERENCES public.tool_operation(tenant_id, operation_id) ON DELETE CASCADE;


--
-- Name: tool_operation tool_operation_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_operation
    ADD CONSTRAINT tool_operation_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.run(tenant_id, run_id) ON DELETE CASCADE;


--
-- Name: usage_record usage_run_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.usage_record
    ADD CONSTRAINT usage_run_fk FOREIGN KEY (tenant_id, run_id) REFERENCES public.run(tenant_id, run_id) ON DELETE CASCADE;


--
-- Name: weakness_record weakness_record_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.weakness_record
    ADD CONSTRAINT weakness_record_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.run(run_id) ON DELETE CASCADE;


--
-- Name: weakness_record weakness_record_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.weakness_record
    ADD CONSTRAINT weakness_record_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: weakness_record weakness_student_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.weakness_record
    ADD CONSTRAINT weakness_student_fk FOREIGN KEY (tenant_id, student_id) REFERENCES public.student_profile(tenant_id, student_id) ON DELETE CASCADE;


--
-- Name: agent; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent ENABLE ROW LEVEL SECURITY;

--
-- Name: agent_message_dead_letter; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_message_dead_letter ENABLE ROW LEVEL SECURITY;

--
-- Name: agent_message_dead_letter agent_message_dead_letter_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_message_dead_letter_tenant_isolation ON public.agent_message_dead_letter USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: agent_message_queue; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_message_queue ENABLE ROW LEVEL SECURITY;

--
-- Name: agent_message_queue agent_message_queue_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_message_queue_tenant_isolation ON public.agent_message_queue USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: agent agent_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_tenant_isolation ON public.agent USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: POLICY agent_tenant_isolation ON agent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON POLICY agent_tenant_isolation ON public.agent IS '租户隔离策略。tenant_id 必须等于当前事务租户上下文，否则拒绝';


--
-- Name: agent_version; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_version ENABLE ROW LEVEL SECURITY;

--
-- Name: agent_version agent_version_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_version_tenant_isolation ON public.agent_version USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: approval; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.approval ENABLE ROW LEVEL SECURITY;

--
-- Name: approval approval_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY approval_tenant_isolation ON public.approval USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: audit_record; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.audit_record ENABLE ROW LEVEL SECURITY;

--
-- Name: audit_record audit_record_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY audit_record_tenant_isolation ON public.audit_record USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: budget_account; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.budget_account ENABLE ROW LEVEL SECURITY;

--
-- Name: budget_account budget_account_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY budget_account_tenant_isolation ON public.budget_account USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: budget_reservation; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.budget_reservation ENABLE ROW LEVEL SECURITY;

--
-- Name: budget_reservation budget_reservation_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY budget_reservation_tenant_isolation ON public.budget_reservation USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_agent_def; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_agent_def ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_agent_def cap_agent_def_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_agent_def_tenant_isolation ON public.cap_agent_def USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_approval; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_approval ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_approval cap_approval_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_approval_tenant_isolation ON public.cap_approval USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_authz_version; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_authz_version ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_authz_version cap_authz_version_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_authz_version_tenant_isolation ON public.cap_authz_version USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_compensation_task; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_compensation_task ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_compensation_task cap_compensation_task_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_compensation_task_tenant_isolation ON public.cap_compensation_task USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_config_pointer; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_config_pointer ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_config_pointer cap_config_pointer_read; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_config_pointer_read ON public.cap_config_pointer FOR SELECT USING (((tenant_id IS NULL) OR (tenant_id = public.current_tenant_id())));


--
-- Name: POLICY cap_config_pointer_read ON cap_config_pointer; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON POLICY cap_config_pointer_read ON public.cap_config_pointer IS 'PLATFORM 指针只读共享；TENANT 指针仅本租户可见。';


--
-- Name: cap_config_pointer cap_config_pointer_update; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_config_pointer_update ON public.cap_config_pointer FOR UPDATE USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_config_pointer cap_config_pointer_write; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_config_pointer_write ON public.cap_config_pointer FOR INSERT WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: POLICY cap_config_pointer_write ON cap_config_pointer; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON POLICY cap_config_pointer_write ON public.cap_config_pointer IS '租户只能写自己的 TENANT 指针。PLATFORM 指针须由平台运维角色执行。';


--
-- Name: cap_config_publish_request; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_config_publish_request ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_config_publish_request cap_config_publish_request_insert; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_config_publish_request_insert ON public.cap_config_publish_request FOR INSERT WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_config_publish_request cap_config_publish_request_read; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_config_publish_request_read ON public.cap_config_publish_request FOR SELECT USING ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_config_publish_request cap_config_publish_request_update; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_config_publish_request_update ON public.cap_config_publish_request FOR UPDATE USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_config_release; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_config_release ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_config_release cap_config_release_read; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_config_release_read ON public.cap_config_release FOR SELECT USING (((tenant_id IS NULL) OR (tenant_id = public.current_tenant_id())));


--
-- Name: POLICY cap_config_release_read ON cap_config_release; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON POLICY cap_config_release_read ON public.cap_config_release IS 'PLATFORM 作用域行只读共享（平台对象版本）；TENANT 作用域行仅本租户可见。';


--
-- Name: cap_config_release cap_config_release_update; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_config_release_update ON public.cap_config_release FOR UPDATE USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_config_release cap_config_release_write; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_config_release_write ON public.cap_config_release FOR INSERT WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: POLICY cap_config_release_write ON cap_config_release; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON POLICY cap_config_release_write ON public.cap_config_release IS '租户只能写自己的 TENANT 作用域发布。PLATFORM 作用域发布须由平台运维角色执行。';


--
-- Name: cap_decision_log; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_decision_log ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_decision_log cap_decision_log_read; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_decision_log_read ON public.cap_decision_log FOR SELECT USING (((tenant_id IS NULL) OR (tenant_id = public.current_tenant_id())));


--
-- Name: POLICY cap_decision_log_read ON cap_decision_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON POLICY cap_decision_log_read ON public.cap_decision_log IS 'PLATFORM 发布类审计只读共享；租户调用类审计仅本租户可见。';


--
-- Name: cap_decision_log cap_decision_log_update; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_decision_log_update ON public.cap_decision_log FOR UPDATE USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_decision_log cap_decision_log_write; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_decision_log_write ON public.cap_decision_log FOR INSERT WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: POLICY cap_decision_log_write ON cap_decision_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON POLICY cap_decision_log_write ON public.cap_decision_log IS '租户只能写自己租户作用域的审计行。PLATFORM 发布审计须由平台运维角色写入。';


--
-- Name: cap_execution_intent; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_execution_intent ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_execution_intent cap_execution_intent_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_execution_intent_tenant_isolation ON public.cap_execution_intent USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_result_event; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_result_event ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_result_event cap_result_event_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_result_event_tenant_isolation ON public.cap_result_event USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: config_change_request; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.config_change_request ENABLE ROW LEVEL SECURITY;

--
-- Name: config_change_request config_change_request_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY config_change_request_tenant_isolation ON public.config_change_request USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: consent_withdrawal; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.consent_withdrawal ENABLE ROW LEVEL SECURITY;

--
-- Name: consent_withdrawal consent_withdrawal_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY consent_withdrawal_tenant_isolation ON public.consent_withdrawal USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: POLICY consent_withdrawal_tenant_isolation ON consent_withdrawal; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON POLICY consent_withdrawal_tenant_isolation ON public.consent_withdrawal IS '租户隔离。撤回登记含 student_id——未成年人数据，跨租户泄漏即 Sev-0。';


--
-- Name: erasure_certificate; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.erasure_certificate ENABLE ROW LEVEL SECURITY;

--
-- Name: erasure_certificate erasure_certificate_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY erasure_certificate_tenant_isolation ON public.erasure_certificate USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: execution_capability_snapshot; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.execution_capability_snapshot ENABLE ROW LEVEL SECURITY;

--
-- Name: execution_capability_snapshot execution_capability_snapshot_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY execution_capability_snapshot_tenant_isolation ON public.execution_capability_snapshot USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: execution_snapshot; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.execution_snapshot ENABLE ROW LEVEL SECURITY;

--
-- Name: execution_snapshot execution_snapshot_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY execution_snapshot_tenant_isolation ON public.execution_snapshot USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: idempotency_record; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.idempotency_record ENABLE ROW LEVEL SECURITY;

--
-- Name: idempotency_record idempotency_record_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY idempotency_record_tenant_isolation ON public.idempotency_record USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: object_access_grant; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.object_access_grant ENABLE ROW LEVEL SECURITY;

--
-- Name: object_access_grant object_access_grant_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY object_access_grant_tenant_isolation ON public.object_access_grant USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: outbox_event; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.outbox_event ENABLE ROW LEVEL SECURITY;

--
-- Name: outbox_event outbox_event_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY outbox_event_tenant_isolation ON public.outbox_event USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: principal; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.principal ENABLE ROW LEVEL SECURITY;

--
-- Name: principal_role; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.principal_role ENABLE ROW LEVEL SECURITY;

--
-- Name: principal_role principal_role_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY principal_role_tenant_isolation ON public.principal_role USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: principal principal_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY principal_tenant_isolation ON public.principal USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: rate_limit_bucket; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.rate_limit_bucket ENABLE ROW LEVEL SECURITY;

--
-- Name: rate_limit_bucket rate_limit_bucket_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY rate_limit_bucket_tenant_isolation ON public.rate_limit_bucket USING ((tenant_id = (current_setting('app.tenant_id'::text, true))::uuid)) WITH CHECK ((tenant_id = (current_setting('app.tenant_id'::text, true))::uuid));


--
-- Name: run; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.run ENABLE ROW LEVEL SECURITY;

--
-- Name: run_event; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.run_event ENABLE ROW LEVEL SECURITY;

--
-- Name: run_event run_event_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY run_event_tenant_isolation ON public.run_event USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: run_step; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.run_step ENABLE ROW LEVEL SECURITY;

--
-- Name: run_step run_step_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY run_step_tenant_isolation ON public.run_step USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: run run_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY run_tenant_isolation ON public.run USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: student_profile; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.student_profile ENABLE ROW LEVEL SECURITY;

--
-- Name: student_profile student_profile_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY student_profile_tenant_isolation ON public.student_profile USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: POLICY student_profile_tenant_isolation ON student_profile; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON POLICY student_profile_tenant_isolation ON public.student_profile IS '租户隔离。student_profile 直接承载未成年人数据，是跨租户泄漏后果最严重的表（R-004 / Sev-0）。';


--
-- Name: tenant_config_override; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.tenant_config_override ENABLE ROW LEVEL SECURITY;

--
-- Name: tenant_config_override tenant_config_override_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_config_override_tenant_isolation ON public.tenant_config_override USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: tool_operation; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.tool_operation ENABLE ROW LEVEL SECURITY;

--
-- Name: tool_operation_attempt; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.tool_operation_attempt ENABLE ROW LEVEL SECURITY;

--
-- Name: tool_operation_attempt tool_operation_attempt_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tool_operation_attempt_tenant_isolation ON public.tool_operation_attempt USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: tool_operation tool_operation_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tool_operation_tenant_isolation ON public.tool_operation USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: usage_record; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.usage_record ENABLE ROW LEVEL SECURITY;

--
-- Name: usage_record usage_record_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY usage_record_tenant_isolation ON public.usage_record USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: weakness_record; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.weakness_record ENABLE ROW LEVEL SECURITY;

--
-- Name: weakness_record weakness_record_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY weakness_record_tenant_isolation ON public.weakness_record USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- PostgreSQL database dump complete
--



-- =====================================================================
-- ══ 校验 ══
-- 以下查询全部应返回预期值；不符即说明初始化不完整。
-- =====================================================================

-- ① 表数量应为 42
SELECT count(*) AS 表数量
  FROM pg_tables WHERE schemaname = 'public';

-- ② RLS：启用的表应 = 38，强制（FORCE）应 = 38，策略应 = 46
--    （策略少于 10 条即说明迁移未全量应用）
SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE c.relkind = 'r' AND n.nspname = 'public' AND c.relrowsecurity)       AS 启用RLS的表,
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE c.relkind = 'r' AND n.nspname = 'public' AND c.relforcerowsecurity) AS 强制RLS的表,
       (SELECT count(*) FROM pg_policies WHERE schemaname = 'public')                AS 策略数;

-- ③ 枚举类型应为 13 个
SELECT count(*) AS 枚举类型数 FROM pg_type t
  JOIN pg_namespace n ON n.oid = t.typnamespace
 WHERE n.nspname = 'public' AND t.typtype = 'e';

-- ④ 触发器应为 19 个（审计追加不可改 / 审批状态守卫 / 限流桶清理等不变量）
SELECT count(*) AS 触发器数 FROM pg_trigger WHERE NOT tgisinternal;

-- ⑤ 检查约束应为 89 个
SELECT count(*) AS 检查约束数 FROM pg_constraint c
  JOIN pg_namespace n ON n.oid = c.connamespace
 WHERE n.nspname = 'public' AND c.contype = 'c';

-- ⑤ 关键函数必须在（缺任一 → RLS 上下文取不到租户）
SELECT proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'public'
   AND proname IN ('current_tenant_id','forbid_audit_mutation','enforce_approval_state_transition')
 ORDER BY 1;

-- ⚠️ **红线检查（必做）**：当前连接角色若带 BYPASSRLS，
--    上面所有「隔离生效」的结论都是假的（超级用户能绕过 RLS）。
--    验证隔离必须用 app_agent（NOSUPERUSER + NOBYPASSRLS）另开连接执行。
SELECT current_user, rolsuper, rolbypassrls
  FROM pg_roles WHERE rolname = current_user;
