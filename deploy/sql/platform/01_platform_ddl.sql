-- =====================================================================
-- platform 完整结构（pg_dump --schema-only 导出）
-- 【来源】迁移脚本 V1→V34 在全新空库上逐个实跑（0 错误）后 pg_dump 导出，
--   再在第二个空库复放验证结构等价（65 表 / 680 列 / 255 约束 / 15 枚举 /
--   15 函数 / 172 索引 / 33 触发器 / 42 RLS 开启 / 50 RLS 策略）。
--   ⚠️ 不是从远程 agent_dev 导的 —— 远程 Flyway 只到 V27，
--   缺 V31 platform 身份域 / V32 字典 / V33 指标 / V34 状态守卫的对象；
--   以远程为源会把这四个版本的对象从仓库里永久丢掉。
-- 【地位】db/migration 下 V1~V34 已随本次收口删除，本文件即结构唯一权威定义。
-- 【扩展】platform 用的 gen_random_uuid() 是 PG13+ 内置，无需 pgcrypto。
-- 【执行】psql -v ON_ERROR_STOP=1 -f 01_platform_ddl.sql（空库，owner 身份）
-- =====================================================================

--
-- PostgreSQL database dump
--

\restrict 7n5BwTLQ98SyQlJI9AzRgvd5zufib8dfScFR50w7nO6vxha9QwBmqtNtVUeYEMc

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
-- Name: platform; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA IF NOT EXISTS platform;


--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA IF NOT EXISTS public;


--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA public IS 'standard public schema';


--
-- Name: auth_source; Type: TYPE; Schema: platform; Owner: -
--

CREATE TYPE platform.auth_source AS ENUM (
    'LOCAL',
    'OIDC',
    'SSO'
);


--
-- Name: user_status; Type: TYPE; Schema: platform; Owner: -
--

CREATE TYPE platform.user_status AS ENUM (
    'ACTIVE',
    'DISABLED',
    'LOCKED'
);


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
    IF NEW.state IS DISTINCT FROM OLD.state THEN
        IF OLD.state = 'QUEUED'
           AND NEW.state IN ('RUNNING', 'CANCELLED') THEN
            RETURN NEW;
        END IF;
        IF OLD.state = 'RUNNING'
           AND NEW.state IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT') THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'Run 非法状态迁移 % → %（run_id=%）',
            OLD.state, NEW.state, OLD.run_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: enforce_tenant_lifecycle_transition(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_tenant_lifecycle_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.lifecycle_state IS DISTINCT FROM OLD.lifecycle_state THEN
        IF OLD.lifecycle_state = 'PENDING_SETUP'
           AND NEW.lifecycle_state IN ('ACTIVE', 'CANCELLED') THEN
            RETURN NEW;
        END IF;
        IF OLD.lifecycle_state = 'ACTIVE'
           AND NEW.lifecycle_state IN ('SUSPENDED', 'DECOMMISSIONING') THEN
            RETURN NEW;
        END IF;
        IF OLD.lifecycle_state = 'SUSPENDED'
           AND NEW.lifecycle_state = 'ACTIVE' THEN
            RETURN NEW;
        END IF;
        IF OLD.lifecycle_state = 'DECOMMISSIONING'
           AND NEW.lifecycle_state = 'DECOMMISSIONED' THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION '租户非法生命周期迁移 % → %（tenant_id=%）',
            OLD.lifecycle_state, NEW.lifecycle_state, OLD.id
            USING ERRCODE = 'restrict_violation';
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
-- Name: agent_eval_sample; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.agent_eval_sample (
    sample_id uuid DEFAULT gen_random_uuid() NOT NULL,
    trace_id text NOT NULL,
    tenant_id uuid NOT NULL,
    verdict text NOT NULL,
    hallucination boolean,
    instruction_followed boolean,
    note text,
    sampler text NOT NULL,
    sampled_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT agent_eval_sample_verdict_ck CHECK ((verdict = ANY (ARRAY['SUCCESS'::text, 'PARTIAL'::text, 'FAIL'::text])))
);


--
-- Name: TABLE agent_eval_sample; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON TABLE platform.agent_eval_sample IS '人工/标注平台抽检打标（L2 黄金基线）。⚠️ 样本量 n < 30 时不得显示为百分比 —— 用 2 条样本算出的 50% 幻觉率是噪声，不是指标。此时一律展示「样本不足」。';


--
-- Name: agent_risk_event; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.agent_risk_event (
    risk_id uuid DEFAULT gen_random_uuid() NOT NULL,
    trace_id text NOT NULL,
    tenant_id uuid NOT NULL,
    event_type text NOT NULL,
    detail_digest text,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT agent_risk_event_type_ck CHECK ((event_type = ANY (ARRAY['SAFETY_BLOCK'::text, 'UNAUTHORIZED_CALL'::text, 'SENSITIVE_OUTPUT'::text])))
);


--
-- Name: TABLE agent_risk_event; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON TABLE platform.agent_risk_event IS '安全事件明细。⚠️ **合规红线：只存摘要，绝不落原文**。命中敏感信息的原文一旦入库就构成二次泄露，等于把「拦截住了」变成「存下来了」。';


--
-- Name: COLUMN agent_risk_event.detail_digest; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON COLUMN platform.agent_risk_event.detail_digest IS '事件摘要（如命中模式的类型代号 + 哈希前 8 位），不含任何原文片段。';


--
-- Name: agent_task_outcome; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.agent_task_outcome (
    outcome_id uuid DEFAULT gen_random_uuid() NOT NULL,
    trace_id text NOT NULL,
    tenant_id uuid NOT NULL,
    checker_code text NOT NULL,
    external_ref text,
    verified boolean NOT NULL,
    evidence_digest text,
    checked_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE agent_task_outcome; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON TABLE platform.agent_task_outcome IS '外部终态校验回执（L1）：工单是否真创建、数据是否真写入、文件是否真生成、查询结果是否合规。⚠️ 自动校验成功率必须**并排显示覆盖率** —— 只校验了 3% 的任务时，「成功率 99%」这句话本身就是误导。';


--
-- Name: agent_trace_feedback; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.agent_trace_feedback (
    feedback_id uuid DEFAULT gen_random_uuid() NOT NULL,
    trace_id text NOT NULL,
    tenant_id uuid NOT NULL,
    kind text NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT agent_trace_feedback_kind_ck CHECK ((kind = ANY (ARRAY['THUMB_UP'::text, 'THUMB_DOWN'::text, 'RETRY'::text, 'ABANDON'::text])))
);


--
-- Name: TABLE agent_trace_feedback; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON TABLE platform.agent_trace_feedback IS '用户显式反馈与行为信号（L3）。⚠️ **不能单独当真值**：下游工具故障、网络抖动、UI 卡顿都会导致差评。差评率上涨的处置是**触发抽样复核**，而不是直接判定 Agent 劣化。';


--
-- Name: agent_trace_metric; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.agent_trace_metric (
    trace_id text NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    agent_id uuid,
    agent_version integer,
    scenario text,
    model_id text,
    started_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    duration_ms bigint,
    terminal_state text NOT NULL,
    termination_reason text NOT NULL,
    input_tokens bigint,
    output_tokens bigint,
    cost_amount numeric(18,6),
    currency text,
    tool_rounds integer,
    tool_call_total integer,
    tool_call_failed integer,
    tool_call_invalid integer,
    tool_call_duplicate integer,
    loop_suspected_count integer,
    max_rounds_reached boolean,
    clarification_count integer,
    think_ms_total bigint,
    tool_ms_total bigint,
    auto_checked boolean,
    auto_verified boolean,
    sampled boolean,
    human_verdict text,
    hallucination boolean,
    instruction_followed boolean,
    feedback_up integer,
    feedback_down integer,
    retry_count integer,
    abandoned boolean,
    safety_blocked_count integer,
    unauthorized_attempt_count integer,
    sensitive_output_hits integer,
    trace_id_source text NOT NULL,
    metric_version integer DEFAULT 1 NOT NULL,
    computed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT agent_trace_metric_reason_ck CHECK ((termination_reason = ANY (ARRAY['COMPLETED'::text, 'USER_CANCELLED'::text, 'MODEL_ERROR'::text, 'TOOL_TIMEOUT'::text, 'TOOL_5XX'::text, 'MAX_ROUNDS'::text, 'SAFETY_BLOCK'::text, 'TOKEN_BUDGET'::text, 'OTHER'::text]))),
    CONSTRAINT agent_trace_metric_source_ck CHECK ((trace_id_source = ANY (ARRAY['RUN_TRACE_ID'::text, 'INVOCATION_TRACE_ID'::text, 'CORRELATION_FALLBACK'::text]))),
    CONSTRAINT agent_trace_metric_state_ck CHECK ((terminal_state = ANY (ARRAY['SUCCEEDED'::text, 'FAILED'::text, 'CANCELLED'::text, 'TIMED_OUT'::text]))),
    CONSTRAINT agent_trace_metric_verdict_ck CHECK (((human_verdict IS NULL) OR (human_verdict = ANY (ARRAY['SUCCESS'::text, 'PARTIAL'::text, 'FAIL'::text]))))
);


--
-- Name: TABLE agent_trace_metric; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON TABLE platform.agent_trace_metric IS 'P7 指标宽表：一条 Trace 一行。指标是事实表的**确定函数** —— 重复采集必须得到同一结果，故写入一律 INSERT ... ON CONFLICT (trace_id) DO UPDATE（幂等）。';


--
-- Name: COLUMN agent_trace_metric.terminal_state; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON COLUMN platform.agent_trace_metric.terminal_state IS '⚠️ 链路终态 **≠ 任务成功**。SUCCEEDED 只说明框架没崩、走完了终止逻辑，是否真的达成了用户目标由 human_verdict / auto_verified 回答。两者绝不可混用。';


--
-- Name: COLUMN agent_trace_metric.termination_reason; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON COLUMN platform.agent_trace_metric.termination_reason IS '归一化终止原因（9 值，P7 §3.1 映射规则为唯一权威）。⚠️ 兜底必须是 OTHER 而不是丢弃 —— 丢弃未识别原因会让「终止原因分布」的合计小于总数，看板上出现「不知道哪去了」的黑洞。';


--
-- Name: COLUMN agent_trace_metric.tool_rounds; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON COLUMN platform.agent_trace_metric.tool_rounds IS 'P7-2 起为**真实口径**：取自 run_step 的 TOOL 步骤计数（JdbcRunStepRepository 已补全写入方）。历史未埋点的旧 Run 查不到步骤行 → 如实为 NULL（未采集），绝不退回退化近似。注意：run_step 仅覆盖 P7-2 之后执行的 Run；此前数据的步数类指标显示「未采集」。';


--
-- Name: agent_trace_tool_call; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.agent_trace_tool_call (
    trace_id text NOT NULL,
    tenant_id uuid NOT NULL,
    tool_name text NOT NULL,
    call_count integer NOT NULL,
    failed_count integer DEFAULT 0 NOT NULL,
    invalid_count integer DEFAULT 0 NOT NULL,
    duplicate_count integer DEFAULT 0 NOT NULL,
    total_duration_ms bigint,
    max_duration_ms bigint,
    started_at timestamp with time zone NOT NULL
);


--
-- Name: TABLE agent_trace_tool_call; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON TABLE platform.agent_trace_tool_call IS '工具维度指标（按 Trace × 工具名）。⚠️ 不存 P95 —— 分位数无法从计数与求和还原，强行用 max 冒充 P95 会让尾部延迟被高估。看板呈现均值 + 最大值。';


--
-- Name: mv_agent_metric_hourly; Type: MATERIALIZED VIEW; Schema: platform; Owner: -
--

CREATE MATERIALIZED VIEW platform.mv_agent_metric_hourly AS
 SELECT date_trunc('hour'::text, started_at) AS bucket_start,
    tenant_id,
    agent_id,
    agent_version,
    scenario,
    count(*) AS trace_count,
    count(*) FILTER (WHERE (terminal_state = 'SUCCEEDED'::text)) AS succeeded_count,
    count(*) FILTER (WHERE (terminal_state = 'FAILED'::text)) AS failed_count,
    count(*) FILTER (WHERE (terminal_state = 'CANCELLED'::text)) AS cancelled_count,
    count(*) FILTER (WHERE (terminal_state = 'TIMED_OUT'::text)) AS timed_out_count,
    count(*) FILTER (WHERE (termination_reason = 'COMPLETED'::text)) AS reason_completed,
    count(*) FILTER (WHERE (termination_reason = 'USER_CANCELLED'::text)) AS reason_user_cancelled,
    count(*) FILTER (WHERE (termination_reason = 'MODEL_ERROR'::text)) AS reason_model_error,
    count(*) FILTER (WHERE (termination_reason = 'TOOL_TIMEOUT'::text)) AS reason_tool_timeout,
    count(*) FILTER (WHERE (termination_reason = 'TOOL_5XX'::text)) AS reason_tool_5xx,
    count(*) FILTER (WHERE (termination_reason = 'MAX_ROUNDS'::text)) AS reason_max_rounds,
    count(*) FILTER (WHERE (termination_reason = 'SAFETY_BLOCK'::text)) AS reason_safety_block,
    count(*) FILTER (WHERE (termination_reason = 'TOKEN_BUDGET'::text)) AS reason_token_budget,
    count(*) FILTER (WHERE (termination_reason = 'OTHER'::text)) AS reason_other,
    COALESCE(sum(input_tokens), (0)::numeric) AS input_tokens,
    COALESCE(sum(output_tokens), (0)::numeric) AS output_tokens,
    COALESCE(sum(cost_amount), (0)::numeric) AS cost_amount,
    count(*) FILTER (WHERE ((COALESCE(input_tokens, (0)::bigint) + COALESCE(output_tokens, (0)::bigint)) > 20000)) AS oversized_token_traces,
    COALESCE(sum(tool_call_total), (0)::bigint) AS tool_call_total,
    COALESCE(sum(tool_call_failed), (0)::bigint) AS tool_call_failed,
    COALESCE(sum(tool_call_invalid), (0)::bigint) AS tool_call_invalid,
    COALESCE(sum(tool_call_duplicate), (0)::bigint) AS tool_call_duplicate,
    COALESCE(sum(loop_suspected_count), (0)::bigint) AS loop_suspected_total,
    count(*) FILTER (WHERE max_rounds_reached) AS max_rounds_traces,
    COALESCE(sum(duration_ms), (0)::numeric) AS duration_ms_sum,
    count(*) FILTER (WHERE auto_checked) AS auto_checked_count,
    count(*) FILTER (WHERE auto_verified) AS auto_verified_count,
    count(*) FILTER (WHERE sampled) AS sampled_count,
    count(*) FILTER (WHERE (human_verdict = 'SUCCESS'::text)) AS human_success_count,
    count(*) FILTER (WHERE hallucination) AS hallucination_count,
    count(*) FILTER (WHERE (safety_blocked_count > 0)) AS safety_blocked_traces,
    count(*) FILTER (WHERE (unauthorized_attempt_count > 0)) AS unauthorized_traces,
    count(*) FILTER (WHERE (sensitive_output_hits > 0)) AS sensitive_output_traces
   FROM platform.agent_trace_metric
  GROUP BY (date_trunc('hour'::text, started_at)), tenant_id, agent_id, agent_version, scenario
  WITH NO DATA;


--
-- Name: MATERIALIZED VIEW mv_agent_metric_hourly; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON MATERIALIZED VIEW platform.mv_agent_metric_hourly IS '小时桶聚合，供 Grafana 时序面板直读。⚠️ 刷新期间会读到上一版数据 —— 查询响应体必须带上 refreshed_at，前端展示数据时效，不能假装是实时的。刷新：REFRESH MATERIALIZED VIEW CONCURRENTLY platform.mv_agent_metric_hourly;';


--
-- Name: platform_audit_event; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.platform_audit_event (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    actor_id uuid NOT NULL,
    actor_type character varying(32) DEFAULT 'PLATFORM_USER'::character varying NOT NULL,
    action character varying(128) NOT NULL,
    target_type character varying(64),
    target_id uuid,
    decision character varying(16) NOT NULL,
    reason_code character varying(64),
    correlation_id uuid,
    ip inet,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    ticket_scope character varying(32),
    CONSTRAINT platform_audit_event_ticket_scope_ck CHECK (((ticket_scope IS NULL) OR (ticket_scope = ANY (ARRAY['PLATFORM'::character varying, 'TENANT'::character varying, 'ALL'::character varying]))))
);


-- ⚠️ COMMENT ON ... IS 只接受**单个字符串字面量**，不接受 || 拼接表达式
--    （实测：psql 在 `|| '非票据类…'` 处报 syntax error at or near "||"，
--     整个快照在空库上直接建不起来）。要分行只能靠 psql 的续行，不能靠表达式。
COMMENT ON COLUMN platform.platform_audit_event.ticket_scope IS '治理票据声明的档位＝作用域（同一根轴的两个说法：票据只带 rag_scope 一个声明，值域即 RAG 侧 RagCorpusScope 三档）。非票据类审计行为 NULL，这是常态而不是缺失。⚠️ 写入方只准落这三个规范值：被拒请求里的档位是调用方可控文本，规范集之外一律写 NULL —— 把未校验文本塞进带 CHECK 的列，表现是「审计写入 SQLException 把一次 503 拒绝变成 500」，即闸门自己制造故障。追责口径（方案 §5 A4）= actor_id（谁）+ 本列（哪档）+ decision + occurred_at。';


--
-- Name: platform_mfa_credential; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.platform_mfa_credential (
    id uuid NOT NULL,
    platform_user_id uuid NOT NULL,
    type character varying(32) NOT NULL,
    ext_subject character varying(256),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: platform_permission; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.platform_permission (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code character varying(128) NOT NULL,
    description character varying(256)
);


--
-- Name: platform_role; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.platform_role (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(64) NOT NULL,
    description character varying(256),
    built_in boolean DEFAULT true NOT NULL
);


--
-- Name: platform_role_permission; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.platform_role_permission (
    role_id uuid NOT NULL,
    permission_id uuid NOT NULL
);


--
-- Name: platform_session; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.platform_session (
    id uuid NOT NULL,
    platform_user_id uuid NOT NULL,
    revoked boolean DEFAULT false NOT NULL,
    mfa_verified boolean DEFAULT false NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: platform_user; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.platform_user (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    username character varying(128) NOT NULL,
    email character varying(256),
    display_name character varying(128),
    status platform.user_status DEFAULT 'ACTIVE'::platform.user_status NOT NULL,
    auth_source platform.auth_source NOT NULL,
    password_hash character varying(256),
    mfa_required boolean DEFAULT false NOT NULL,
    last_login_at timestamp with time zone,
    authz_version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: platform_user_role; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.platform_user_role (
    platform_user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    granted_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: sys_dictionary; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.sys_dictionary (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    dict_code character varying(64) NOT NULL,
    dict_name character varying(128) NOT NULL,
    description character varying(512),
    built_in boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE sys_dictionary; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON TABLE platform.sys_dictionary IS '字典类型：一组取值的命名容器（如 http.method）';


--
-- Name: COLUMN sys_dictionary.built_in; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON COLUMN platform.sys_dictionary.built_in IS 'TRUE=与代码枚举强绑定，禁止删除与改 code；FALSE=纯业务数据，可自由维护';


--
-- Name: sys_dictionary_item; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.sys_dictionary_item (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    dict_code character varying(64) NOT NULL,
    item_code character varying(128) NOT NULL,
    item_label character varying(256) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    built_in boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: COLUMN sys_dictionary_item.item_code; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON COLUMN platform.sys_dictionary_item.item_code IS '写入业务表的实际取值（后端枚举校验的就是它）';


--
-- Name: COLUMN sys_dictionary_item.item_label; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON COLUMN platform.sys_dictionary_item.item_label IS '展示给人的中文标签（可改，不影响已落库数据）';


--
-- Name: tenant; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.tenant (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(128) NOT NULL,
    slug character varying(64) NOT NULL,
    status character varying(32) NOT NULL,
    plan character varying(32),
    lifecycle_state character varying(32) NOT NULL,
    effective_policy_version bigint DEFAULT 1 NOT NULL,
    tenant_authz_version bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: tenant_capability_grant; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.tenant_capability_grant (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    resource_code character varying(128) NOT NULL,
    scope character varying(32) NOT NULL,
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    version bigint DEFAULT 1 NOT NULL
);


--
-- Name: tenant_quota; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.tenant_quota (
    tenant_id uuid NOT NULL,
    agent_limit integer,
    run_concurrency_limit integer,
    budget_limit numeric(14,2)
);


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
-- Name: agent_invocation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_invocation (
    invocation_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    parent_invocation_id uuid,
    parent_agent_id uuid NOT NULL,
    parent_agent_version integer NOT NULL,
    target_agent_id uuid NOT NULL,
    target_agent_version integer NOT NULL,
    status text NOT NULL,
    depth integer NOT NULL,
    trace_id text NOT NULL,
    input_digest text,
    output_digest text,
    reason_code text,
    fencing_token bigint NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    requested_target_agent_id uuid,
    routing_decision jsonb,
    policy_ref text,
    policy_fingerprint text,
    CONSTRAINT agent_invocation_depth_ck CHECK ((depth > 0)),
    CONSTRAINT agent_invocation_fencing_ck CHECK ((fencing_token > 0)),
    CONSTRAINT agent_invocation_parent_ck CHECK ((((depth = 1) AND (parent_invocation_id IS NULL)) OR ((depth > 1) AND (parent_invocation_id IS NOT NULL)))),
    CONSTRAINT agent_invocation_status_ck CHECK ((status = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text, 'SUCCEEDED'::text, 'FAILED'::text, 'CANCELLED'::text, 'TIMED_OUT'::text, 'UNKNOWN'::text]))),
    CONSTRAINT agent_invocation_version_ck CHECK (((parent_agent_version > 0) AND (target_agent_version > 0)))
);

ALTER TABLE ONLY public.agent_invocation FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE agent_invocation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.agent_invocation IS '委派调用运行记录（06 变更补充 v1.1 / FR-022）。只存摘要不存明文；重复投递不创建重复逻辑调用（MA-T10）；终态写入用 fencing_token 做 CAS（MA-T12）。';


--
-- Name: COLUMN agent_invocation.requested_target_agent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.agent_invocation.requested_target_agent_id IS '模型给出的原始候选目标（路由降级**前**）。与 target_agent_id（实际生效的目标）并存：两者不同即说明发生了降级（no_match_action=DEFAULT_TARGET 降到兜底目标，或 on_conflict=CANDIDATE_WINS 采纳了模型候选）。⚠️ 存量行为 NULL：当时模型原始候选只在日志里留痕、没有任何持久化副本，无法可靠回填（据日志回填 = 用猜测冒充事实，比空值更有害）——NULL 的语义是「**当时未记录**」，**不是**「没有降级」。';


--
-- Name: COLUMN agent_invocation.routing_decision; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.agent_invocation.routing_decision IS '路由**决策快照**（10 号设计 §6.1）：policyRef / policyFingerprint / mode / canonicalInputSha256 / evaluationTrace / resolution（稳定码）/ matchedRuleId / matchedRuleSha256 / candidateFromModel / finalTarget。⚠️ **不存 canonicalInput 明文**：重放不变式只需要它的 SHA-256，而本表的红线是「只存摘要不存明文」（与 input_digest 同口径）——把路由输入抄进 jsonb 等于新开一条明文旁路。⚠️ 非 ROUTER 模式（LEAF/ORCHESTRATE）与存量行为 NULL，语义是「当时未做路由决策」；据此判断降级与否要看 requested_target_agent_id，不要用本列 IS NULL。';


--
-- Name: COLUMN agent_invocation.policy_ref; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.agent_invocation.policy_ref IS '本次决策所用的路由策略**固定版本引用**（id@version，与 routing_decision->>policyRef 同值）。⚠️ 单列存储是因为重放不变式的主键半边（policyRef, canonicalInputSha256）要能**直接查询/建索引**，不该依赖 jsonb 里的一个键 —— 键名一改，历史查询就静默返回 0 行（本项目反复出现的「看起来在查、实际查不到」缺陷类）。';


--
-- Name: COLUMN agent_invocation.policy_fingerprint; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.agent_invocation.policy_fingerprint IS '决策当时那份策略的**内容指纹**（cap_routing_policy.config_fingerprint 的副本）。⚠️ 是副本而非外键：策略版本行不可发布后修改（forbid_published_def_mutation），指纹理论上不会漂移；但「策略后来被 DEPRECATED / 治理侧回填」这类变更仍会改变读到的元数据，运行记录必须冻结**当时**看到的值，否则历史 Run 的复核结论会随治理动作变。';


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
-- Name: cap_agent_child_ref; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_agent_child_ref (
    tenant_id uuid NOT NULL,
    parent_agent_id uuid NOT NULL,
    parent_release_version integer NOT NULL,
    child_agent_id uuid NOT NULL,
    child_release_version integer NOT NULL,
    ordinal integer NOT NULL,
    required boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT cap_agent_child_ref_ordinal_ck CHECK ((ordinal >= 0)),
    CONSTRAINT cap_agent_child_ref_version_ck CHECK (((parent_release_version > 0) AND (child_release_version > 0)))
);

ALTER TABLE ONLY public.cap_agent_child_ref FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_agent_child_ref; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_agent_child_ref IS '父子 Agent 关系的发布投影（06 变更补充 v1.1）。权威来源是 Agent release 中的 childAgentRefs；本表可重建，release 不可改写。';


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
    orchestration jsonb DEFAULT '{}'::jsonb NOT NULL,
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
-- Name: COLUMN cap_agent_def.orchestration; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_agent_def.orchestration IS '多级 Agent 编排声明（06 变更补充 · v1.1）。{} = 缺省 LEAF；子 Agent 引用必须固定 release（禁止 latest）。';


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
    tenant_id uuid,
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
    scope text DEFAULT 'TENANT'::text NOT NULL,
    CONSTRAINT cap_config_publish_request_operation_ck CHECK ((operation = ANY (ARRAY['PUBLISH'::text, 'ROLLBACK'::text, 'LIFECYCLE_TRANSITION'::text]))),
    CONSTRAINT cap_config_publish_request_scope_ck CHECK ((((scope = 'TENANT'::text) AND (tenant_id IS NOT NULL)) OR ((scope = 'PLATFORM'::text) AND (tenant_id IS NULL)))),
    CONSTRAINT cap_config_publish_request_state_ck CHECK ((state = ANY (ARRAY['PENDING'::text, 'APPROVED'::text, 'REJECTED'::text, 'APPLIED'::text, 'FAILED'::text]))),
    CONSTRAINT cap_config_publish_request_two_person_ck CHECK (((approver_id IS NULL) OR (approver_id <> requester_id)))
);

ALTER TABLE ONLY public.cap_config_publish_request FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_config_publish_request; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_config_publish_request IS '配置变更申请单（P6R4 管理端点 · 第三十四批起亦承载平台级变更）：承载**待审批**的发布 / 回滚 / 生命周期迁移方案。双人复核的载体 —— 申请与审批是两次独立的、带身份的调用。第三十四批起支持 PLATFORM 作用域（平台级 Tool / Skill / MCP Server 的变更）。';


--
-- Name: COLUMN cap_config_publish_request.state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_config_publish_request.state IS '审批流程状态（PENDING/APPROVED/REJECTED/APPLIED/FAILED）；**不是**配置对象自身的生命周期（见 cap_*_def.lifecycle_state）。';


--
-- Name: COLUMN cap_config_publish_request.scope; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_config_publish_request.scope IS '变更作用域：TENANT（租户级对象，tenant_id 必填）/ PLATFORM（平台级对象，tenant_id 必为 NULL）。PLATFORM 行对应用角色连接不可见（RLS 严格相等），只能经平台运维角色（BYPASSRLS）读写。';


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
-- Name: cap_result_contract; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_result_contract (
    tenant_id uuid NOT NULL,
    contract_id text NOT NULL,
    version integer NOT NULL,
    schema_json jsonb NOT NULL,
    config_fingerprint text NOT NULL,
    schema_version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    lifecycle_state public.cap_lifecycle_state DEFAULT 'DRAFT'::public.cap_lifecycle_state NOT NULL,
    CONSTRAINT cap_result_contract_fingerprint_ck CHECK (((config_fingerprint IS NOT NULL) AND (length(config_fingerprint) > 0))),
    CONSTRAINT cap_result_contract_version_ck CHECK ((version > 0))
);

ALTER TABLE ONLY public.cap_result_contract FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_result_contract; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_result_contract IS '委派输出契约（06 变更补充 v1.1 · MA-T16）。按 (tenant_id, contract_id, version) 寻址；Agent 的 resultContractRef 必须指向已发布的固定版本。';


--
-- Name: COLUMN cap_result_contract.contract_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_result_contract.contract_id IS '输出契约标识（可读字符串，与 cap_tool_def.id / cap_skill_def.id 同构）。⚠️ 不可改为 UUID：对外 schema 契约（common.schema.json#/$defs/id）把契约 id 定义为可读字符串，治理通道也按字符串接收；曾声明为 UUID 导致发布事务在 非 UUID id 上整笔回滚（V23 修复）。';


--
-- Name: COLUMN cap_result_contract.schema_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_result_contract.schema_json IS '契约定义本身（JSON Schema），不是契约实例。发布期固化，运行期只读。';


--
-- Name: COLUMN cap_result_contract.lifecycle_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_result_contract.lifecycle_state IS '生命周期状态（§6.6）：DRAFT/VALIDATED/PUBLISHED/DEPRECATED。DEPRECATED = 已停用：新发布不可再引用（06 §3.2），存量引用按版本过渡。';


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
-- Name: cap_routing_policy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cap_routing_policy (
    tenant_id uuid NOT NULL,
    policy_id text NOT NULL,
    version integer NOT NULL,
    mode text NOT NULL,
    input_schema jsonb NOT NULL,
    evaluation jsonb NOT NULL,
    default_target text,
    no_match_action text NOT NULL,
    on_conflict text NOT NULL,
    sample_set jsonb DEFAULT '[]'::jsonb NOT NULL,
    config_fingerprint text NOT NULL,
    schema_version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    lifecycle_state public.cap_lifecycle_state DEFAULT 'DRAFT'::public.cap_lifecycle_state NOT NULL,
    CONSTRAINT cap_routing_policy_conflict_ck CHECK ((on_conflict = ANY (ARRAY['RULE_WINS'::text, 'CANDIDATE_WINS'::text]))),
    CONSTRAINT cap_routing_policy_default_target_ck CHECK (((no_match_action <> 'DEFAULT_TARGET'::text) OR (default_target IS NOT NULL))),
    CONSTRAINT cap_routing_policy_eval_ck CHECK (
CASE
    WHEN (mode = ANY (ARRAY['RULES_ONLY'::text, 'RULE_FIRST'::text])) THEN ((jsonb_typeof(evaluation) = 'array'::text) AND (jsonb_array_length(evaluation) > 0))
    ELSE (jsonb_typeof(evaluation) = 'array'::text)
END),
    CONSTRAINT cap_routing_policy_fingerprint_ck CHECK (((config_fingerprint IS NOT NULL) AND (length(config_fingerprint) > 0))),
    CONSTRAINT cap_routing_policy_mode_ck CHECK ((mode = ANY (ARRAY['RULES_ONLY'::text, 'RULE_FIRST'::text, 'SEMANTIC'::text]))),
    CONSTRAINT cap_routing_policy_nomatch_ck CHECK ((no_match_action = ANY (ARRAY['REJECT'::text, 'DEFAULT_TARGET'::text]))),
    CONSTRAINT cap_routing_policy_version_ck CHECK ((version > 0))
);

ALTER TABLE ONLY public.cap_routing_policy FORCE ROW LEVEL SECURITY;


--
-- Name: TABLE cap_routing_policy; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cap_routing_policy IS '路由策略（10 号设计 §4.2 · 取代第十四批的「策略枚举 + 有序目标列表」）。按 (tenant_id, policy_id, version) 寻址；Agent 的 routingPolicyRef 必须指向已发布的固定版本。文法与守卫由 agent-routing 的 PolicyCodec/PublishGuards 判定，本表只存文本与行级一致性。';


--
-- Name: COLUMN cap_routing_policy.mode; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_routing_policy.mode IS '谁选目标。RULES_ONLY = 只由规则决定，模型零参与（推荐默认，确定性最强）；RULE_FIRST = 规则优先，无命中再走 default_target/语义；SEMANTIC = 由模型按 §3 契约选目标。⚠️ 旧的 EXPLICIT_TARGETS / ORDERED_FALLBACK 已随未上线的旧模型删除。';


--
-- Name: COLUMN cap_routing_policy.input_schema; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_routing_policy.input_schema IS '路由输入词汇表（JSON 对象）。规则里的每个 in.<path> 必须在此声明且类型相容（守卫 V3）—— 字段名拼错的规则不会报错，只会永远不命中，于是「写了不生效」在线上表现为「经常走兜底」。';


--
-- Name: COLUMN cap_routing_policy.evaluation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_routing_policy.evaluation IS '有序规则列表（JSON 数组）。⚠️ 数组顺序是路由语义（首个命中即止），因此内容指纹计算时**保留数组顺序**，只递归排序对象键 —— 与 ClosureFingerprint 的做法刻意不同。';


--
-- Name: COLUMN cap_routing_policy.default_target; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_routing_policy.default_target IS '兜底目标；no_match_action=DEFAULT_TARGET 时必填（与代码侧 CapRoutingPolicyDef 同口径，两处都得拒才不会出现库能存、代码造不出）。⚠️ 不含版本号：目标的固定版本由父 Agent 的 childAgentRefs 提供 —— 版本真相只有一份。';


--
-- Name: COLUMN cap_routing_policy.no_match_action; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_routing_policy.no_match_action IS '无命中处置。REJECT = 拒绝委派（不猜）；DEFAULT_TARGET = 降级到 default_target。兜底必须指名道姓，「取列表第一个」已删除。';


--
-- Name: COLUMN cap_routing_policy.on_conflict; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_routing_policy.on_conflict IS '规则命中与模型候选不一致时的裁决。RULE_WINS = 规则赢（默认）；CANDIDATE_WINS = 候选赢。仅在 mode 让模型参与时有意义。';


--
-- Name: COLUMN cap_routing_policy.sample_set; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_routing_policy.sample_set IS '发布期回放样本（JSON 数组，可为 []）。守卫 V8/V9 用它把「策略实际会怎么路由」变成发布期就能看到的证据。⚠️ 样本不得声明 ctx.tenantId：租户只来自服务端（G-6）。';


--
-- Name: COLUMN cap_routing_policy.lifecycle_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_routing_policy.lifecycle_state IS '生命周期状态（§6.6）：DRAFT/VALIDATED/PUBLISHED/DEPRECATED。DEPRECATED = 已停用：新发布不可再引用（06 §3.2），存量引用按版本过渡。';


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
    purpose text,
    usage jsonb,
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
    http_method text,
    http_url text,
    http_timeout_ms integer,
    CONSTRAINT cap_tool_def_http_binding_ck CHECK ((((kind = 'http'::text) AND (http_method IS NOT NULL) AND (http_url IS NOT NULL) AND (http_timeout_ms IS NOT NULL) AND (http_method = ANY (ARRAY['GET'::text, 'POST'::text, 'PUT'::text, 'PATCH'::text, 'DELETE'::text, 'HEAD'::text])) AND (http_timeout_ms > 0) AND (http_timeout_ms <= 300000) AND (http_url ~* '^https?://[^[:space:]]+$'::text)) OR ((kind <> 'http'::text) AND (http_method IS NULL) AND (http_url IS NULL) AND (http_timeout_ms IS NULL)))),
    CONSTRAINT cap_tool_def_kind_ck CHECK ((kind = ANY (ARRAY['native'::text, 'mcp'::text, 'http'::text]))),
    CONSTRAINT cap_tool_def_mcp_binding_ck CHECK ((((kind = 'mcp'::text) AND (mcp_server_id IS NOT NULL) AND (mcp_tool_name IS NOT NULL)) OR ((kind = 'native'::text) AND (mcp_server_id IS NULL) AND (mcp_tool_name IS NULL)) OR ((kind = 'http'::text) AND (mcp_server_id IS NULL) AND (mcp_tool_name IS NULL)))),
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
-- Name: COLUMN cap_tool_def.http_method; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_tool_def.http_method IS '远程 HTTP 请求工具的方法（GET/POST/PUT/PATCH/DELETE/HEAD）。仅 kind=http 时非空。';


--
-- Name: COLUMN cap_tool_def.http_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_tool_def.http_url IS '远程 HTTP 请求工具的目标 URL（绝对地址，http/https）。仅 kind=http 时非空。';


--
-- Name: COLUMN cap_tool_def.http_timeout_ms; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cap_tool_def.http_timeout_ms IS '远程 HTTP 请求工具的单次请求超时（毫秒，正整数，上限 300000）。仅 kind=http 时非空。';


--
-- Name: CONSTRAINT cap_tool_def_http_binding_ck ON cap_tool_def; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT cap_tool_def_http_binding_ck ON public.cap_tool_def IS 'HTTP 工具的形状一致性：kind=http 时方法/URL/超时必须齐全且合法；kind≠http 时三者必须全空。⚠️ 这不是 SSRF 防线 —— 运行期出站防护见设计文档 §13.1。';


--
-- Name: CONSTRAINT cap_tool_def_kind_ck ON cap_tool_def; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT cap_tool_def_kind_ck ON public.cap_tool_def IS 'Tool 种类取值域：native（内置）/ mcp（绑定 MCP Server）/ http（远程 HTTP 请求）。';


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
    deadline_at timestamp with time zone,
    budget_amount numeric(18,6),
    trace_id text,
    CONSTRAINT run_attempt_non_negative CHECK ((attempt >= 0)),
    CONSTRAINT run_completed_at_consistent CHECK ((((state = ANY (ARRAY['QUEUED'::public.run_state, 'RUNNING'::public.run_state])) AND (completed_at IS NULL)) OR ((state = ANY (ARRAY['CANCELLED'::public.run_state, 'SUCCEEDED'::public.run_state, 'FAILED'::public.run_state, 'TIMED_OUT'::public.run_state])) AND (completed_at IS NOT NULL)))),
    CONSTRAINT run_fencing_non_negative CHECK ((fencing_token >= 0)),
    CONSTRAINT run_result_consistent CHECK (((state <> 'SUCCEEDED'::public.run_state) OR (result_payload IS NOT NULL)))
);

ALTER TABLE ONLY public.run FORCE ROW LEVEL SECURITY;


--
-- Name: COLUMN run.deadline_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.run.deadline_at IS 'Run 业务截止时刻（NULL = 不限时）。多级 Agent 的「全调用树共享超时上限」以此为根上界；与 lease_expires_at（租约到期）语义不同，不得混用。';


--
-- Name: COLUMN run.budget_amount; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.run.budget_amount IS 'Run 级预算额度（NULL = 不额外限制；非空时按 budget_reservation.run_id 聚合判定）。与租户级 budget_account 是两个独立口径：前者约束单棵调用树，后者约束租户总量。';


--
-- Name: COLUMN run.trace_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.run.trace_id IS '一次用户 Agent 会话任务的唯一标识（P7）。⚠️ 存量行为 NULL —— 当时的会话标识只落在 agent_invocation.trace_id（仅覆盖有委派的链路）或以 correlation_id 代用。采集器对存量 Run 降级取 correlation_id，并在指标行标记 trace_id_source，NULL 的语义是「当时未记录」，不是「不存在这条 Trace」。';


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
-- Name: agent_eval_sample agent_eval_sample_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.agent_eval_sample
    ADD CONSTRAINT agent_eval_sample_pkey PRIMARY KEY (sample_id);


--
-- Name: agent_risk_event agent_risk_event_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.agent_risk_event
    ADD CONSTRAINT agent_risk_event_pkey PRIMARY KEY (risk_id);


--
-- Name: agent_task_outcome agent_task_outcome_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.agent_task_outcome
    ADD CONSTRAINT agent_task_outcome_pkey PRIMARY KEY (outcome_id);


--
-- Name: agent_task_outcome agent_task_outcome_trace_checker_uq; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.agent_task_outcome
    ADD CONSTRAINT agent_task_outcome_trace_checker_uq UNIQUE (trace_id, checker_code);


--
-- Name: agent_trace_feedback agent_trace_feedback_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.agent_trace_feedback
    ADD CONSTRAINT agent_trace_feedback_pkey PRIMARY KEY (feedback_id);


--
-- Name: agent_trace_metric agent_trace_metric_pk; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.agent_trace_metric
    ADD CONSTRAINT agent_trace_metric_pk PRIMARY KEY (trace_id);


--
-- Name: agent_trace_tool_call agent_trace_tool_call_pk; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.agent_trace_tool_call
    ADD CONSTRAINT agent_trace_tool_call_pk PRIMARY KEY (trace_id, tool_name);


--
-- Name: platform_audit_event platform_audit_event_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_audit_event
    ADD CONSTRAINT platform_audit_event_pkey PRIMARY KEY (id);


--
-- Name: platform_mfa_credential platform_mfa_credential_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_mfa_credential
    ADD CONSTRAINT platform_mfa_credential_pkey PRIMARY KEY (id);


--
-- Name: platform_permission platform_permission_code_key; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_permission
    ADD CONSTRAINT platform_permission_code_key UNIQUE (code);


--
-- Name: platform_permission platform_permission_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_permission
    ADD CONSTRAINT platform_permission_pkey PRIMARY KEY (id);


--
-- Name: platform_role platform_role_name_key; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_role
    ADD CONSTRAINT platform_role_name_key UNIQUE (name);


--
-- Name: platform_role_permission platform_role_permission_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_role_permission
    ADD CONSTRAINT platform_role_permission_pkey PRIMARY KEY (role_id, permission_id);


--
-- Name: platform_role platform_role_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_role
    ADD CONSTRAINT platform_role_pkey PRIMARY KEY (id);


--
-- Name: platform_session platform_session_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_session
    ADD CONSTRAINT platform_session_pkey PRIMARY KEY (id);


--
-- Name: platform_user platform_user_email_key; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_user
    ADD CONSTRAINT platform_user_email_key UNIQUE (email);


--
-- Name: platform_user platform_user_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_user
    ADD CONSTRAINT platform_user_pkey PRIMARY KEY (id);


--
-- Name: platform_user_role platform_user_role_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_user_role
    ADD CONSTRAINT platform_user_role_pkey PRIMARY KEY (platform_user_id, role_id);


--
-- Name: platform_user platform_user_username_key; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_user
    ADD CONSTRAINT platform_user_username_key UNIQUE (username);


--
-- Name: sys_dictionary sys_dictionary_dict_code_key; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.sys_dictionary
    ADD CONSTRAINT sys_dictionary_dict_code_key UNIQUE (dict_code);


--
-- Name: sys_dictionary_item sys_dictionary_item_code_unique; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.sys_dictionary_item
    ADD CONSTRAINT sys_dictionary_item_code_unique UNIQUE (dict_code, item_code);


--
-- Name: sys_dictionary_item sys_dictionary_item_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.sys_dictionary_item
    ADD CONSTRAINT sys_dictionary_item_pkey PRIMARY KEY (id);


--
-- Name: sys_dictionary sys_dictionary_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.sys_dictionary
    ADD CONSTRAINT sys_dictionary_pkey PRIMARY KEY (id);


--
-- Name: tenant_capability_grant tenant_capability_grant_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.tenant_capability_grant
    ADD CONSTRAINT tenant_capability_grant_pkey PRIMARY KEY (id);


--
-- Name: tenant_capability_grant tenant_capability_grant_tenant_id_resource_code_key; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.tenant_capability_grant
    ADD CONSTRAINT tenant_capability_grant_tenant_id_resource_code_key UNIQUE (tenant_id, resource_code);


--
-- Name: tenant tenant_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.tenant
    ADD CONSTRAINT tenant_pkey PRIMARY KEY (id);


--
-- Name: tenant_quota tenant_quota_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.tenant_quota
    ADD CONSTRAINT tenant_quota_pkey PRIMARY KEY (tenant_id);


--
-- Name: tenant tenant_slug_key; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.tenant
    ADD CONSTRAINT tenant_slug_key UNIQUE (slug);


--
-- Name: agent_invocation agent_invocation_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_invocation
    ADD CONSTRAINT agent_invocation_pk PRIMARY KEY (tenant_id, invocation_id);


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
-- Name: cap_agent_child_ref cap_agent_child_ref_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_agent_child_ref
    ADD CONSTRAINT cap_agent_child_ref_pk PRIMARY KEY (tenant_id, parent_agent_id, parent_release_version, child_agent_id, child_release_version);


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
-- Name: cap_result_contract cap_result_contract_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_result_contract
    ADD CONSTRAINT cap_result_contract_pk PRIMARY KEY (tenant_id, contract_id, version);


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
-- Name: cap_routing_policy cap_routing_policy_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_routing_policy
    ADD CONSTRAINT cap_routing_policy_pk PRIMARY KEY (tenant_id, policy_id, version);


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
-- Name: idx_agent_metric_agent_time; Type: INDEX; Schema: platform; Owner: -
--

CREATE INDEX idx_agent_metric_agent_time ON platform.agent_trace_metric USING btree (agent_id, agent_version, started_at DESC);


--
-- Name: idx_agent_metric_reason; Type: INDEX; Schema: platform; Owner: -
--

CREATE INDEX idx_agent_metric_reason ON platform.agent_trace_metric USING btree (started_at DESC, termination_reason);


--
-- Name: idx_agent_metric_tenant_time; Type: INDEX; Schema: platform; Owner: -
--

CREATE INDEX idx_agent_metric_tenant_time ON platform.agent_trace_metric USING btree (tenant_id, started_at DESC);


--
-- Name: idx_agent_trace_tool_time; Type: INDEX; Schema: platform; Owner: -
--

CREATE INDEX idx_agent_trace_tool_time ON platform.agent_trace_tool_call USING btree (started_at DESC, tool_name);


--
-- Name: idx_platform_audit_actor; Type: INDEX; Schema: platform; Owner: -
--

CREATE INDEX idx_platform_audit_actor ON platform.platform_audit_event USING btree (actor_id, occurred_at);


--
-- Name: idx_platform_cap_grant_tenant; Type: INDEX; Schema: platform; Owner: -
--

CREATE INDEX idx_platform_cap_grant_tenant ON platform.tenant_capability_grant USING btree (tenant_id);


--
-- Name: idx_platform_session_user; Type: INDEX; Schema: platform; Owner: -
--

CREATE INDEX idx_platform_session_user ON platform.platform_session USING btree (platform_user_id);


--
-- Name: idx_platform_tenant_status; Type: INDEX; Schema: platform; Owner: -
--

CREATE INDEX idx_platform_tenant_status ON platform.tenant USING btree (status);


--
-- Name: idx_platform_user_role_role; Type: INDEX; Schema: platform; Owner: -
--

CREATE INDEX idx_platform_user_role_role ON platform.platform_user_role USING btree (role_id);


--
-- Name: idx_platform_user_role_user; Type: INDEX; Schema: platform; Owner: -
--

CREATE INDEX idx_platform_user_role_user ON platform.platform_user_role USING btree (platform_user_id);


--
-- Name: idx_platform_user_status; Type: INDEX; Schema: platform; Owner: -
--

CREATE INDEX idx_platform_user_status ON platform.platform_user USING btree (status);


--
-- Name: idx_sys_dict_item_dict; Type: INDEX; Schema: platform; Owner: -
--

CREATE INDEX idx_sys_dict_item_dict ON platform.sys_dictionary_item USING btree (dict_code, sort_order, item_code);


--
-- Name: mv_agent_metric_hourly_uq; Type: INDEX; Schema: platform; Owner: -
--

CREATE UNIQUE INDEX mv_agent_metric_hourly_uq ON platform.mv_agent_metric_hourly USING btree (bucket_start, tenant_id, COALESCE(agent_id, '00000000-0000-0000-0000-000000000000'::uuid), COALESCE(agent_version, 0), COALESCE(scenario, ''::text));


--
-- Name: idx_agent_invocation_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_invocation_parent ON public.agent_invocation USING btree (tenant_id, parent_invocation_id);


--
-- Name: idx_agent_invocation_requested_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_invocation_requested_target ON public.agent_invocation USING btree (tenant_id, requested_target_agent_id) WHERE (requested_target_agent_id IS NOT NULL);


--
-- Name: INDEX idx_agent_invocation_requested_target; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON INDEX public.idx_agent_invocation_requested_target IS '按「模型原始候选」反查委派记录 —— 用于统计某个 Agent 被路由降级掉的频次。';


--
-- Name: idx_agent_invocation_routed_fallback; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_invocation_routed_fallback ON public.agent_invocation USING btree (tenant_id, started_at DESC) WHERE ((requested_target_agent_id IS NOT NULL) AND (requested_target_agent_id IS DISTINCT FROM target_agent_id));


--
-- Name: INDEX idx_agent_invocation_routed_fallback; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON INDEX public.idx_agent_invocation_routed_fallback IS '只索引「确实发生过路由降级」的委派记录：requested_target_agent_id 非空且与 target_agent_id 不同。降级记录是全部委派里的一小撮，这个索引让「哪些委派发生了降级」成为一次廉价的范围扫描，而不是全表过滤。';


--
-- Name: idx_agent_invocation_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_invocation_run ON public.agent_invocation USING btree (tenant_id, run_id, started_at DESC);


--
-- Name: idx_agent_invocation_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_invocation_status ON public.agent_invocation USING btree (tenant_id, status, started_at DESC);


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
-- Name: idx_cap_agent_child_ref_child; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_agent_child_ref_child ON public.cap_agent_child_ref USING btree (tenant_id, child_agent_id, child_release_version);


--
-- Name: idx_cap_agent_child_ref_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cap_agent_child_ref_parent ON public.cap_agent_child_ref USING btree (tenant_id, parent_agent_id, parent_release_version, ordinal);


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
-- Name: idx_run_tenant_trace; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_run_tenant_trace ON public.run USING btree (tenant_id, trace_id) WHERE (trace_id IS NOT NULL);


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
-- Name: tenant trg_platform_tenant_lifecycle_guard; Type: TRIGGER; Schema: platform; Owner: -
--

CREATE TRIGGER trg_platform_tenant_lifecycle_guard BEFORE UPDATE ON platform.tenant FOR EACH ROW EXECUTE FUNCTION public.enforce_tenant_lifecycle_transition();


--
-- Name: TRIGGER trg_platform_tenant_lifecycle_guard ON tenant; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON TRIGGER trg_platform_tenant_lifecycle_guard ON platform.tenant IS '评审 P0-4：lifecycle_state 合法迁移守卫（权威口径 = ports TenantLifecycle.ALLOWED_TRANSITIONS）。同状态的其他列更新不受影响（IS DISTINCT FROM 判定）。';


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
-- Name: cap_result_contract trg_cap_result_contract_align_state; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cap_result_contract_align_state BEFORE INSERT OR UPDATE ON public.cap_result_contract FOR EACH ROW EXECUTE FUNCTION public.align_cap_lifecycle_state();


--
-- Name: cap_result_contract trg_cap_result_contract_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cap_result_contract_immutable BEFORE UPDATE ON public.cap_result_contract FOR EACH ROW EXECUTE FUNCTION public.forbid_published_def_mutation();


--
-- Name: cap_routing_policy trg_cap_routing_policy_align_state; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cap_routing_policy_align_state BEFORE INSERT OR UPDATE ON public.cap_routing_policy FOR EACH ROW EXECUTE FUNCTION public.align_cap_lifecycle_state();


--
-- Name: cap_routing_policy trg_cap_routing_policy_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cap_routing_policy_immutable BEFORE UPDATE ON public.cap_routing_policy FOR EACH ROW EXECUTE FUNCTION public.forbid_published_def_mutation();


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
-- Name: run_step trg_run_step_fencing_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_run_step_fencing_guard BEFORE UPDATE ON public.run_step FOR EACH ROW EXECUTE FUNCTION public.enforce_fencing_on_write();


--
-- Name: execution_snapshot trg_snapshot_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_snapshot_immutable BEFORE DELETE OR UPDATE ON public.execution_snapshot FOR EACH ROW EXECUTE FUNCTION public.forbid_snapshot_mutation();


--
-- Name: platform_mfa_credential platform_mfa_credential_platform_user_id_fkey; Type: FK CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_mfa_credential
    ADD CONSTRAINT platform_mfa_credential_platform_user_id_fkey FOREIGN KEY (platform_user_id) REFERENCES platform.platform_user(id);


--
-- Name: platform_role_permission platform_role_permission_permission_id_fkey; Type: FK CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_role_permission
    ADD CONSTRAINT platform_role_permission_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES platform.platform_permission(id);


--
-- Name: platform_role_permission platform_role_permission_role_id_fkey; Type: FK CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_role_permission
    ADD CONSTRAINT platform_role_permission_role_id_fkey FOREIGN KEY (role_id) REFERENCES platform.platform_role(id);


--
-- Name: platform_session platform_session_platform_user_id_fkey; Type: FK CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_session
    ADD CONSTRAINT platform_session_platform_user_id_fkey FOREIGN KEY (platform_user_id) REFERENCES platform.platform_user(id);


--
-- Name: platform_user_role platform_user_role_platform_user_id_fkey; Type: FK CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_user_role
    ADD CONSTRAINT platform_user_role_platform_user_id_fkey FOREIGN KEY (platform_user_id) REFERENCES platform.platform_user(id);


--
-- Name: platform_user_role platform_user_role_role_id_fkey; Type: FK CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.platform_user_role
    ADD CONSTRAINT platform_user_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES platform.platform_role(id);


--
-- Name: sys_dictionary_item sys_dictionary_item_dict_code_fkey; Type: FK CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.sys_dictionary_item
    ADD CONSTRAINT sys_dictionary_item_dict_code_fkey FOREIGN KEY (dict_code) REFERENCES platform.sys_dictionary(dict_code) ON DELETE RESTRICT;


--
-- Name: tenant_capability_grant tenant_capability_grant_tenant_id_fkey; Type: FK CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.tenant_capability_grant
    ADD CONSTRAINT tenant_capability_grant_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);


--
-- Name: tenant_quota tenant_quota_tenant_id_fkey; Type: FK CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.tenant_quota
    ADD CONSTRAINT tenant_quota_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES platform.tenant(id);


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
-- Name: cap_agent_child_ref cap_agent_child_ref_child_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_agent_child_ref
    ADD CONSTRAINT cap_agent_child_ref_child_fk FOREIGN KEY (tenant_id, child_agent_id, child_release_version) REFERENCES public.cap_agent_def(tenant_id, agent_id, version) ON DELETE CASCADE;


--
-- Name: cap_agent_child_ref cap_agent_child_ref_parent_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cap_agent_child_ref
    ADD CONSTRAINT cap_agent_child_ref_parent_fk FOREIGN KEY (tenant_id, parent_agent_id, parent_release_version) REFERENCES public.cap_agent_def(tenant_id, agent_id, version) ON DELETE CASCADE;


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
-- Name: agent_invocation; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.agent_invocation ENABLE ROW LEVEL SECURITY;

--
-- Name: agent_invocation agent_invocation_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY agent_invocation_tenant_isolation ON public.agent_invocation USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


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
-- Name: cap_agent_child_ref; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_agent_child_ref ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_agent_child_ref cap_agent_child_ref_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_agent_child_ref_tenant_isolation ON public.cap_agent_child_ref USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


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
-- Name: cap_result_contract; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_result_contract ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_result_contract cap_result_contract_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_result_contract_tenant_isolation ON public.cap_result_contract USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_result_event; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_result_event ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_result_event cap_result_event_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_result_event_tenant_isolation ON public.cap_result_event USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


--
-- Name: cap_routing_policy; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.cap_routing_policy ENABLE ROW LEVEL SECURITY;

--
-- Name: cap_routing_policy cap_routing_policy_tenant_isolation; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY cap_routing_policy_tenant_isolation ON public.cap_routing_policy USING ((tenant_id = public.current_tenant_id())) WITH CHECK ((tenant_id = public.current_tenant_id()));


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

\unrestrict 7n5BwTLQ98SyQlJI9AzRgvd5zufib8dfScFR50w7nO6vxha9QwBmqtNtVUeYEMc

