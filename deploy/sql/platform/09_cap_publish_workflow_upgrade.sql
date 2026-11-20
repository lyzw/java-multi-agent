-- =====================================================================
-- 09_cap_publish_workflow_upgrade.sql
--
-- 为"发布流程管理"需求补充三张表 + 扩展 cap_config_publish_request。
-- 与既有 01_platform_ddl.sql 风格一致：Owner=ai_agent、RLS 走
-- current_tenant_id()、scope CHECK 同模式。
--
-- 组件：
--   1. cap_publish_policy       —— 发布策略配置（GLOBAL / RESOURCE_TYPE / INSTANCE 三层）
--   2. cap_config_draft         —— 变更草稿（独立于 cap_*_def 版本行，承载编辑工作流）
--   3. cap_audit_log            —— 业务审计日志（CREATE / SAVE_DRAFT / PUBLISH_* / ROLLBACK / DISCARD ...）
--   4. ALTER cap_config_publish_request —— 结构化失败字段
-- =====================================================================

SET search_path TO public;

-- ---------------------------------------------------------------------
-- 1. 发布策略配置表
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.cap_publish_policy (
    policy_id        uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    scope            text NOT NULL DEFAULT 'PLATFORM',
    tenant_id        uuid,
    policy_level     text NOT NULL,   -- GLOBAL | RESOURCE_TYPE | INSTANCE
    resource_kind    text,            -- AGENT | SKILL | TOOL | MCP_SERVER | RESULT_CONTRACT | ROUTING_POLICY
    resource_id      text,            -- 单实例覆盖时填写
    publish_mode     text NOT NULL,   -- DIRECT | MANUAL | APPROVAL_MANUAL | APPROVAL_AUTO
    enabled          bool NOT NULL DEFAULT true,
    updated_by       text NOT NULL,
    created_at       timestamptz(6) NOT NULL DEFAULT now(),
    updated_at       timestamptz(6) NOT NULL DEFAULT now(),

    CONSTRAINT cap_publish_policy_scope_ck CHECK (
        (scope = 'PLATFORM' AND tenant_id IS NULL) OR
        (scope = 'TENANT'   AND tenant_id IS NOT NULL)
    ),
    CONSTRAINT cap_publish_policy_level_ck CHECK (
        policy_level IN ('GLOBAL', 'RESOURCE_TYPE', 'INSTANCE')
    ),
    CONSTRAINT cap_publish_policy_mode_ck CHECK (
        publish_mode IN ('DIRECT', 'MANUAL', 'APPROVAL_MANUAL', 'APPROVAL_AUTO')
    ),
    CONSTRAINT cap_publish_policy_target_ck CHECK (
        (policy_level = 'GLOBAL'
            AND resource_kind IS NULL AND resource_id IS NULL)
        OR
        (policy_level = 'RESOURCE_TYPE'
            AND resource_kind IS NOT NULL AND resource_id IS NULL)
        OR
        (policy_level = 'INSTANCE'
            AND resource_kind IS NOT NULL AND resource_id IS NOT NULL)
    )
);

COMMENT ON TABLE  public.cap_publish_policy               IS '资产发布流程策略配置（全局 / 资源类型 / 单实例三层覆盖，按 INSTANCE > RESOURCE_TYPE > GLOBAL 优先级生效）';
COMMENT ON COLUMN public.cap_publish_policy.scope        IS '作用域：PLATFORM（tenant_id 必须为 NULL）/ TENANT（tenant_id 必填）';
COMMENT ON COLUMN public.cap_publish_policy.policy_level IS '策略层级：GLOBAL（全局默认）/ RESOURCE_TYPE（按资源类型）/ INSTANCE（单实例覆盖）';
COMMENT ON COLUMN public.cap_publish_policy.publish_mode IS '发布模式：DIRECT（免审直发）/ MANUAL（手动发布）/ APPROVAL_MANUAL（审批后手动发布）/ APPROVAL_AUTO（审批后自动发布）';

CREATE UNIQUE INDEX IF NOT EXISTS uq_cap_publish_policy_target
    ON public.cap_publish_policy (
        scope,
        COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
        policy_level,
        COALESCE(resource_kind, ''),
        COALESCE(resource_id, '')
    );

CREATE INDEX IF NOT EXISTS idx_cap_publish_policy_scope
    ON public.cap_publish_policy (scope, tenant_id) WHERE enabled = true;

ALTER TABLE ONLY public.cap_publish_policy ENABLE ROW LEVEL SECURITY;

CREATE POLICY cap_publish_policy_read ON public.cap_publish_policy
    FOR SELECT USING (
        (tenant_id IS NULL)
        OR (tenant_id = public.current_tenant_id())
    );
CREATE POLICY cap_publish_policy_write ON public.cap_publish_policy
    FOR INSERT WITH CHECK (tenant_id IS NULL OR tenant_id = public.current_tenant_id());
CREATE POLICY cap_publish_policy_update ON public.cap_publish_policy
    FOR UPDATE USING (tenant_id IS NULL OR tenant_id = public.current_tenant_id())
    WITH CHECK (tenant_id IS NULL OR tenant_id = public.current_tenant_id());


-- ---------------------------------------------------------------------
-- 2. 变更草稿表
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.cap_config_draft (
    draft_id          uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    scope             text NOT NULL DEFAULT 'TENANT',
    tenant_id         uuid,
    resource_kind     text NOT NULL,   -- AGENT | SKILL | TOOL | MCP_SERVER | RESULT_CONTRACT | ROUTING_POLICY
    resource_id       text NOT NULL,
    resource_name     text,
    base_version      int4 NOT NULL DEFAULT 0,   -- 编辑起点版本（首次新建为 0）
    draft_version     int4 NOT NULL DEFAULT 1,  -- 草稿内部递增版本，便于 Diff
    draft_state       text NOT NULL DEFAULT 'DRAFT',
    draft_config      jsonb NOT NULL,
    config_fingerprint text,
    latest_request_id uuid,            -- 关联 cap_config_publish_request（当草稿进入待发布/待审核）
    failure_stage     text,            -- PRE_CHECK | PARAM_VALIDATE | DEP_CHECK | POINTER_CAS | RUNTIME_SYNC
    failure_details   jsonb NOT NULL DEFAULT '[]',
    rejection_reason  text,
    operator_id       text NOT NULL,
    created_at        timestamptz(6) NOT NULL DEFAULT now(),
    updated_at        timestamptz(6) NOT NULL DEFAULT now(),

    CONSTRAINT cap_config_draft_scope_ck CHECK (
        (scope = 'PLATFORM' AND tenant_id IS NULL) OR
        (scope = 'TENANT'   AND tenant_id IS NOT NULL)
    ),
    CONSTRAINT cap_config_draft_state_ck CHECK (
        draft_state IN (
            'DRAFT', 'PENDING_APPROVAL', 'PENDING_PUBLISH',
            'APPROVAL_REJECTED', 'PUBLISH_FAILED', 'DISCARDED'
        )
    ),
    CONSTRAINT cap_config_draft_fstage_ck CHECK (
        failure_stage IS NULL OR failure_stage IN (
            'PRE_CHECK', 'PARAM_VALIDATE', 'DEP_CHECK', 'POINTER_CAS', 'RUNTIME_SYNC'
        )
    ),
    CONSTRAINT cap_config_draft_version_ck CHECK (base_version >= 0 AND draft_version > 0)
);

COMMENT ON TABLE  public.cap_config_draft IS '变更草稿表：独立于 cap_*_def 版本行，承载编辑工作流（DRAFT → PENDING_APPROVAL → PENDING_PUBLISH → PUBLISHED / FAILED / REJECTED / DISCARDED）';
COMMENT ON COLUMN public.cap_config_draft.base_version IS '编辑起点版本号（首次新建为 0，基于已发布版本编辑时为线上 pointer 指向版本）';
COMMENT ON COLUMN public.cap_config_draft.draft_version IS '草稿内部递增版本号（同一次编辑多次保存产生）';
COMMENT ON COLUMN public.cap_config_draft.failure_stage IS '发布失败阶段：PRE_CHECK（前端预检）/ PARAM_VALIDATE（参数校验）/ DEP_CHECK（依赖检查）/ POINTER_CAS（指针并发更新）/ RUNTIME_SYNC（运行时同步）';
COMMENT ON COLUMN public.cap_config_draft.failure_details IS '结构化失败明细 JSON 数组（便于前端展示失败列表/详情）';

-- 同一 (scope, tenant_id, resource_kind, resource_id) 同时只允许一条非终态草稿，
-- 避免并发编辑产生多条互相覆盖。终态（DISCARDED）可保留历史。
CREATE UNIQUE INDEX IF NOT EXISTS uq_cap_config_draft_active
    ON public.cap_config_draft (scope, COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), resource_kind, resource_id)
    WHERE draft_state IN ('DRAFT', 'PENDING_APPROVAL', 'PENDING_PUBLISH', 'APPROVAL_REJECTED', 'PUBLISH_FAILED');

CREATE INDEX IF NOT EXISTS idx_cap_config_draft_tenant_state
    ON public.cap_config_draft (scope, tenant_id, draft_state, updated_at DESC);

ALTER TABLE ONLY public.cap_config_draft ENABLE ROW LEVEL SECURITY;

CREATE POLICY cap_config_draft_read ON public.cap_config_draft
    FOR SELECT USING (tenant_id IS NULL OR tenant_id = public.current_tenant_id());
CREATE POLICY cap_config_draft_write ON public.cap_config_draft
    FOR INSERT WITH CHECK (tenant_id IS NULL OR tenant_id = public.current_tenant_id());
CREATE POLICY cap_config_draft_update ON public.cap_config_draft
    FOR UPDATE USING (tenant_id IS NULL OR tenant_id = public.current_tenant_id())
    WITH CHECK (tenant_id IS NULL OR tenant_id = public.current_tenant_id());


-- ---------------------------------------------------------------------
-- 3. 业务审计日志表
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.cap_audit_log (
    audit_id         uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    scope            text NOT NULL DEFAULT 'PLATFORM',
    tenant_id        uuid,
    resource_kind    text NOT NULL,
    resource_id      text NOT NULL,
    resource_name    text,
    event_type       text NOT NULL,
    from_version     int4,
    to_version       int4,
    from_state       text,
    to_state         text,
    before_config    jsonb,
    after_config     jsonb,
    diff_summary     jsonb NOT NULL DEFAULT '{}',
    request_id       uuid,
    release_id       int8,
    operator_id      text NOT NULL,
    reason           text,
    created_at       timestamptz(6) NOT NULL DEFAULT now(),

    CONSTRAINT cap_audit_log_scope_ck CHECK (
        (scope = 'PLATFORM' AND tenant_id IS NULL) OR
        (scope = 'TENANT'   AND tenant_id IS NOT NULL)
    ),
    CONSTRAINT cap_audit_log_event_ck CHECK (
        event_type IN (
            'CREATE', 'SAVE_DRAFT', 'SUBMIT_APPROVAL', 'APPROVE', 'REJECT',
            'PUBLISH_SUCCESS', 'PUBLISH_FAILED', 'ROLLBACK', 'DISCARD', 'LIFECYCLE'
        )
    )
);

COMMENT ON TABLE  public.cap_audit_log IS '业务审计日志：全量记录 CREATE / SAVE_DRAFT / SUBMIT_APPROVAL / APPROVE / REJECT / PUBLISH_SUCCESS / PUBLISH_FAILED / ROLLBACK / DISCARD / LIFECYCLE';
COMMENT ON COLUMN public.cap_audit_log.before_config IS '变更前完整配置快照（JSONB，允许 NULL：新建时无 before）';
COMMENT ON COLUMN public.cap_audit_log.after_config  IS '变更后完整配置快照（JSONB，允许 NULL：删除/废弃时无 after）';
COMMENT ON COLUMN public.cap_audit_log.diff_summary  IS '字段级变更摘要 JSON（便于列表快速展示 diff）';

CREATE INDEX IF NOT EXISTS idx_cap_audit_log_resource_time
    ON public.cap_audit_log (resource_kind, resource_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_cap_audit_log_tenant_time
    ON public.cap_audit_log (scope, tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_cap_audit_log_event_time
    ON public.cap_audit_log (event_type, created_at DESC);

ALTER TABLE ONLY public.cap_audit_log ENABLE ROW LEVEL SECURITY;

CREATE POLICY cap_audit_log_read ON public.cap_audit_log
    FOR SELECT USING (tenant_id IS NULL OR tenant_id = public.current_tenant_id());
CREATE POLICY cap_audit_log_write ON public.cap_audit_log
    FOR INSERT WITH CHECK (tenant_id IS NULL OR tenant_id = public.current_tenant_id());


-- ---------------------------------------------------------------------
-- 4. 扩展 cap_config_publish_request
-- ---------------------------------------------------------------------

ALTER TABLE public.cap_config_publish_request
    ADD COLUMN IF NOT EXISTS resource_kind text,
    ADD COLUMN IF NOT EXISTS resource_id   text,
    ADD COLUMN IF NOT EXISTS target_version int4,
    ADD COLUMN IF NOT EXISTS fail_stage text,
    ADD COLUMN IF NOT EXISTS fail_details jsonb NOT NULL DEFAULT '[]';

ALTER TABLE public.cap_config_publish_request
    ADD CONSTRAINT cap_config_publish_request_fstage_ck CHECK (
        fail_stage IS NULL OR fail_stage IN (
            'PRE_CHECK', 'PARAM_VALIDATE', 'DEP_CHECK', 'POINTER_CAS', 'RUNTIME_SYNC'
        )
    );

COMMENT ON COLUMN public.cap_config_publish_request.resource_kind  IS '目标资源类型（AGENT / SKILL / TOOL / MCP_SERVER / RESULT_CONTRACT / ROUTING_POLICY）';
COMMENT ON COLUMN public.cap_config_publish_request.resource_id    IS '目标资源 ID';
COMMENT ON COLUMN public.cap_config_publish_request.target_version IS '目标版本号（PUBLISH 通常是新版本号，ROLLBACK 是历史发布的 version）';
COMMENT ON COLUMN public.cap_config_publish_request.fail_stage     IS '发布失败阶段';
COMMENT ON COLUMN public.cap_config_publish_request.fail_details    IS '结构化失败明细 JSON 数组';

CREATE INDEX IF NOT EXISTS idx_cap_publish_request_resource
    ON public.cap_config_publish_request (scope, tenant_id, resource_kind, resource_id, requested_at DESC);

CREATE INDEX IF NOT EXISTS idx_cap_publish_request_failed
    ON public.cap_config_publish_request (scope, tenant_id, state, requested_at DESC)
    WHERE state = 'FAILED';


-- =====================================================================
-- 5. cap_publish_policy 对齐脚本语义：resource_kind / resource_id 必须可空
--    （GLOBAL 级别策略的 CHECK 约束 (resource_kind IS NULL AND resource_id IS NULL)
--    要求这两列可空；部分部署曾错误地加过 NOT NULL）
-- =====================================================================
ALTER TABLE public.cap_publish_policy ALTER COLUMN resource_kind DROP NOT NULL;
ALTER TABLE public.cap_publish_policy ALTER COLUMN resource_id   DROP NOT NULL;
