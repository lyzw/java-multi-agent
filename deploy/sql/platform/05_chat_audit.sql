-- =====================================================================
-- 对话审计插件 · 审计主表（P2）
-- =====================================================================
--
-- 【本脚本做什么】
--   1. public.chat_audit_record  —— 租户级审计原文（1 请求 1 条，envelope 全量），
--      ENABLE + FORCE RLS，策略与既有租户表同构（tenant_id = current_tenant_id()）；
--   2. public.chat_audit_unattributed —— 建连前即被拒的请求（401/400/429 等，
--      身份尚未解析、无租户可绑），**只存元数据，绝不存原文**；
--   3. 两个表的索引。
--
-- 【为什么必须独立成表，而不是复用 run / run_event / audit_record】
--   `AgentChatAuditPort` 明文规定「不存正文」（JdbcAgentChatAuditRepository 拒绝
--   非标量载荷），且写 run 表会牵动 Worker 的 claimTasks 语义。审计插件要存
--   **用户问题 / 模型回答 / thinking / 逐分片**，与那条链路是相反的目标，
--   混在一起只会让「Run 是执行事实」变成「Run 是日志垃圾场」。
--
-- 【为什么 chat_audit_unattributed 不带 queue / worker】
--   本表与 run/outbox 完全无关：审计是旁路，不写 outbox_event、不参与调度，
--   否则一条审计记录就会唤醒 Worker 重跑一次 Run（Sev-0）。
--
-- 【⚠️ 平台域直连对话的租户归属】
--   /api/chat/stream 与 /api/chat/knowledge 是**平台主体**直连模型（无目标租户），
--   这类记录 domain='PLATFORM' 且 tenant_id 用 nil UUID
--   （00000000-0000-0000-0000-000000000000）占位。刻意不用 NULL：
--   · 列 NOT NULL 才能让「RLS 谓词恒为 NULL」这种静默失效不可能发生；
--   · nil UUID 不等于任何真实租户 ⇒ 任何租户都读不到平台直连的原文。
--   真实租户归属的平台链请求（/api/agent/chat/stream 带目标租户）写真实 tenant_id。
--
-- 【授权】
--   表级授权统一在 03_platform_roles.sql（app_agent：public 域 ALL TABLES ⇒
--   新库按 01 → 03 顺序自动覆盖；存量库重跑 03 即可）。
--   受控删除（保留期 / 监护人撤回）在 04_retention_role.sql：
--   DELETE 只给 agent_retention，应用角色被显式 REVOKE。
--
-- 【幂等】可反复执行。
--
-- 【用法】
--   psql -h <host> -U <超管> -d <db> -v ON_ERROR_STOP=1 -f 05_chat_audit.sql
-- =====================================================================

\set ON_ERROR_STOP on

-- ── 1. 审计主表（租户域） ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.chat_audit_record (
    audit_id       uuid        NOT NULL,
    trace_id       text        NOT NULL,
    tenant_id      uuid        NOT NULL,
    domain         text        NOT NULL,
    endpoint       text        NOT NULL,
    principal_id   text,
    started_at     timestamptz NOT NULL,
    completed_at   timestamptz,
    duration_ms    bigint,
    terminal_state text        NOT NULL,
    http_method    text        NOT NULL,
    http_status    integer,
    config_version bigint      NOT NULL,
    envelope       jsonb       NOT NULL,
    content_class  text        NOT NULL DEFAULT 'SENSITIVE_RAW',
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chat_audit_record_pkey PRIMARY KEY (audit_id),
    -- 域只有两种：平台链 / 租户链。多一个值就意味着某条链路没想清楚身份来源。
    CONSTRAINT chat_audit_record_domain_ck CHECK (domain IN ('PLATFORM', 'TENANT')),
    -- 终态白名单与编排器/控制器的收敛口径逐字一致；拼错一个终态会让统计悄悄少一类。
    CONSTRAINT chat_audit_record_terminal_ck CHECK (terminal_state IN (
        'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'CLIENT_DISCONNECTED', 'REJECTED'))
);

COMMENT ON TABLE public.chat_audit_record IS
    '对话审计原文（1 请求 1 条 envelope）；租户级 FORCE RLS；不写 outbox、不参与 Worker 调度。';
COMMENT ON COLUMN public.chat_audit_record.audit_id IS
    '审计记录标识：服务端生成的 UUID。⛔ 不等于 traceId —— traceId 可被客户端经 X-Trace-Id 注入，
     直接当主键会在「客户端传了非 UUID」时让记录整条写不进去（PG 22P02）。';
COMMENT ON COLUMN public.chat_audit_record.trace_id IS
    '链路标识（响应头 X-Trace-Id 同值）；与 envelope.traceId 一致，供按 trace 定位。';
COMMENT ON COLUMN public.chat_audit_record.tenant_id IS
    '归属租户；平台主体直连对话无租户时用 nil UUID 占位（≠ 任何真实租户）。';
COMMENT ON COLUMN public.chat_audit_record.envelope IS
    '完整审计信封：http/identity/request/phases/events/chunks/modelCalls/rag/tools/result/error。';
COMMENT ON COLUMN public.chat_audit_record.content_class IS
    '内容分级：SENSITIVE_RAW（含原文，按 PIPL 处理）为默认值。';
COMMENT ON COLUMN public.chat_audit_record.config_version IS
    '录制时冻结的配置版本，保证「当时的开关组合」可复现。';

ALTER TABLE public.chat_audit_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chat_audit_record FORCE  ROW LEVEL SECURITY;

-- 策略与既有租户表同构（01_platform_ddl.sql 里的 xxx_tenant_isolation 一律如此）。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'chat_audit_record'
          AND policyname = 'chat_audit_record_tenant_isolation'
    ) THEN
        CREATE POLICY chat_audit_record_tenant_isolation ON public.chat_audit_record
            USING ((tenant_id = public.current_tenant_id()))
            WITH CHECK ((tenant_id = public.current_tenant_id()));
    END IF;
END;
$$;

CREATE INDEX IF NOT EXISTS idx_chat_audit_record_tenant_time
    ON public.chat_audit_record (tenant_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_audit_record_endpoint_time
    ON public.chat_audit_record (endpoint, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_audit_record_trace
    ON public.chat_audit_record (trace_id);

-- ── 2. 建连前失败（无已解析租户，只存元数据） ────────────────────────
CREATE TABLE IF NOT EXISTS public.chat_audit_unattributed (
    audit_id         uuid        NOT NULL,
    trace_id         text        NOT NULL,
    started_at       timestamptz NOT NULL,
    endpoint         text        NOT NULL,
    http_method      text        NOT NULL,
    http_status      integer,
    rejection_reason text,
    metadata         jsonb       NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chat_audit_unattributed_pkey PRIMARY KEY (audit_id)
);

COMMENT ON TABLE public.chat_audit_unattributed IS
    '建连前即被拒的对话请求（身份未解析）：只存 URI/query key/状态码/耗时，绝不存原文。';
COMMENT ON COLUMN public.chat_audit_unattributed.metadata IS
    '仅元数据：uri / queryKeys / durationMs / 已脱敏 header 名——不含 body、不含令牌。';

CREATE INDEX IF NOT EXISTS idx_chat_audit_unattributed_time
    ON public.chat_audit_unattributed (started_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_audit_unattributed_endpoint_time
    ON public.chat_audit_unattributed (endpoint, started_at DESC);

-- ── 3. 自检（结构不存在就报错，绝不静默跳过） ────────────────────────
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(t, ', ') INTO missing
    FROM unnest(ARRAY['chat_audit_record', 'chat_audit_unattributed']) AS t
    WHERE to_regclass('public.' || t) IS NULL;
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION '对话审计建表失败，缺失: %', missing;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relname = 'chat_audit_record' AND c.relforcerowsecurity
    ) THEN
        RAISE EXCEPTION 'chat_audit_record 未开启 FORCE RLS——无租户谓词的删除/查询会静默 0 行';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public' AND tablename = 'chat_audit_record'
    ) THEN
        RAISE EXCEPTION 'chat_audit_record 缺少 RLS 策略——开了 RLS 却没有策略等于全表不可见';
    END IF;
END;
$$;

\echo '=== 对话审计表已就绪 ==='
SELECT c.relname AS table_name,
       c.relrowsecurity AS rls_enabled,
       c.relforcerowsecurity AS rls_forced
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relname IN ('chat_audit_record', 'chat_audit_unattributed')
ORDER BY c.relname;
