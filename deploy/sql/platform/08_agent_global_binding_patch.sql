-- =====================================================================
-- 08_agent_global_binding_patch.sql
-- 存量库升级：Agent 从「租户从属」改为「全局实体 + 多对多绑定」
-- ---------------------------------------------------------------------
-- 目标模型：
--   agent / agent_version / cap_agent_def / cap_agent_child_ref /
--   execution_snapshot 去掉 tenant_id，成为全局定义；
--   public.tenant_agent_binding 承载租户与 Agent 的配置关联。
--
-- 执行前提：
--   1) 先备份；本脚本会改写表结构，事务失败会整体回滚。
--   2) 若不同租户存在同名 Agent，先人工改名；本脚本拒绝静默覆盖。
--   3) 应用代码需与本脚本同步发布，否则旧 SQL 仍带 tenant_id 会报错。
--   4) **本脚本建表但不授权**：表级授权统一在 03_platform_roles.sql。
--      存量库的正确顺序是「先跑本脚本 → 再重跑 03」（新库是 01 → 03）。
--      跳过 03 ⇒ tenant_agent_binding 无授权 ⇒ 应用角色一访问即 permission denied，
--      而报错点在业务代码，离「表没授权」的真实成因极远。
--
-- 【⚠️ 脚本内部有硬顺序：必须先摘 RLS 策略、再删 tenant_id 列】
--   租户策略（tenant_id = current_tenant_id()）依赖 tenant_id 列；
--   列先删会报 "cannot drop column tenant_id ... other objects depend on it"，
--   而本脚本整体在事务里 ⇒ 整笔回滚 ⇒ 存量库迁移零效果，且不留下任何痕迹。
--   演练时真炸过一次，故固化在第 2.1 步。
-- =====================================================================

BEGIN;

-- 同名 Agent 在全局命名空间下必须唯一；不自动改名，避免把猜测当事实。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM public.agent GROUP BY name HAVING count(*) > 1) THEN
    RAISE EXCEPTION '存在跨租户同名 Agent，升级前请先改名（全局 name 必须唯一）';
  END IF;
END $$;

-- 1. 建绑定表，并把存量 Agent 的 tenant_id 原样转成一条绑定。
CREATE TABLE IF NOT EXISTS public.tenant_agent_binding (
    binding_id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    pinned_version integer,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT tenant_agent_binding_pkey PRIMARY KEY (binding_id),
    CONSTRAINT tenant_agent_binding_tenant_agent_key UNIQUE (tenant_id, agent_id),
    CONSTRAINT tenant_agent_binding_status_ck CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'SUSPENDED'::text]))),
    CONSTRAINT tenant_agent_binding_pinned_version_ck CHECK ((pinned_version IS NULL) OR (pinned_version > 0)),
    CONSTRAINT tenant_agent_binding_tenant_fk FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE,
    CONSTRAINT tenant_agent_binding_agent_fk FOREIGN KEY (agent_id) REFERENCES public.agent(agent_id) ON DELETE CASCADE
);

INSERT INTO public.tenant_agent_binding (tenant_id, agent_id, status, pinned_version, config)
SELECT tenant_id, agent_id, 'ACTIVE', NULL, '{}'::jsonb
FROM public.agent
ON CONFLICT (tenant_id, agent_id) DO NOTHING;

ALTER TABLE public.tenant_agent_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tenant_agent_binding FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_agent_binding_tenant_isolation ON public.tenant_agent_binding;
CREATE POLICY tenant_agent_binding_tenant_isolation ON public.tenant_agent_binding
    USING ((tenant_id = public.current_tenant_id()))
    WITH CHECK ((tenant_id = public.current_tenant_id()));

CREATE INDEX IF NOT EXISTS idx_tenant_agent_binding_agent
    ON public.tenant_agent_binding USING btree (agent_id, status);
CREATE INDEX IF NOT EXISTS idx_tenant_agent_binding_tenant
    ON public.tenant_agent_binding USING btree (tenant_id, status);

-- 2. 先摘掉所有引用旧复合键的外键，再改列与约束。
ALTER TABLE public.run
    DROP CONSTRAINT IF EXISTS run_snapshot_fk;
ALTER TABLE public.execution_snapshot
    DROP CONSTRAINT IF EXISTS execution_snapshot_version_fk;
ALTER TABLE public.cap_agent_child_ref
    DROP CONSTRAINT IF EXISTS cap_agent_child_ref_child_fk,
    DROP CONSTRAINT IF EXISTS cap_agent_child_ref_parent_fk;
ALTER TABLE public.cap_agent_def
    DROP CONSTRAINT IF EXISTS cap_agent_def_agent_fk;
ALTER TABLE public.agent_version
    DROP CONSTRAINT IF EXISTS agent_version_agent_fk;
ALTER TABLE public.agent
    DROP CONSTRAINT IF EXISTS agent_tenant_id_fkey;

-- 2.1 ⛔ **必须先摘 RLS 与策略，再删 tenant_id 列**。
--     租户策略（tenant_id = current_tenant_id()）**依赖 tenant_id 列**，
--     列先删会报：
--       ERROR: cannot drop column tenant_id of table agent
--              because other objects depend on it
--       DETAIL: policy agent_tenant_isolation on table agent depends on column tenant_id
--     —— 本脚本整体在事务里，一处报错 ⇒ 整笔回滚 ⇒ 存量库迁移零效果，
--     且报错点在 ALTER TABLE 而非「策略」，排查方向会被带偏。
--     （顺序颠倒的版本在迁移演练中被真炸过一次，故此处固化为硬顺序。）
--
--     全局定义表不再需要租户 RLS；绑定表才是租户隔离边界。
ALTER TABLE public.agent NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.agent DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS agent_tenant_isolation ON public.agent;

ALTER TABLE public.agent_version NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.agent_version DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS agent_version_tenant_isolation ON public.agent_version;

ALTER TABLE public.cap_agent_def NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.cap_agent_def DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS cap_agent_def_tenant_isolation ON public.cap_agent_def;

ALTER TABLE public.cap_agent_child_ref NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.cap_agent_child_ref DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS cap_agent_child_ref_tenant_isolation ON public.cap_agent_child_ref;

ALTER TABLE public.execution_snapshot NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.execution_snapshot DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS execution_snapshot_tenant_isolation ON public.execution_snapshot;

ALTER TABLE public.agent
    DROP CONSTRAINT IF EXISTS agent_tenant_agent_unique,
    DROP CONSTRAINT IF EXISTS agent_name_per_tenant,
    ADD CONSTRAINT agent_name_key UNIQUE (name);
ALTER TABLE public.agent DROP COLUMN IF EXISTS tenant_id;

ALTER TABLE public.agent_version
    DROP CONSTRAINT IF EXISTS agent_version_tenant_version_unique,
    DROP CONSTRAINT IF EXISTS agent_version_unique,
    ADD CONSTRAINT agent_version_unique UNIQUE (agent_id, version_number);
ALTER TABLE public.agent_version DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE public.agent_version
    ADD CONSTRAINT agent_version_agent_fk
    FOREIGN KEY (agent_id) REFERENCES public.agent(agent_id) ON DELETE CASCADE;

ALTER TABLE public.cap_agent_def
    DROP CONSTRAINT IF EXISTS cap_agent_def_pk,
    ADD CONSTRAINT cap_agent_def_pk PRIMARY KEY (agent_id, version);
ALTER TABLE public.cap_agent_def DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE public.cap_agent_def
    ADD CONSTRAINT cap_agent_def_agent_fk
    FOREIGN KEY (agent_id) REFERENCES public.agent(agent_id) ON DELETE CASCADE;

ALTER TABLE public.cap_agent_child_ref
    DROP CONSTRAINT IF EXISTS cap_agent_child_ref_pk,
    ADD CONSTRAINT cap_agent_child_ref_pk
    PRIMARY KEY (parent_agent_id, parent_release_version, child_agent_id, child_release_version);
ALTER TABLE public.cap_agent_child_ref DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE public.cap_agent_child_ref
    ADD CONSTRAINT cap_agent_child_ref_child_fk
    FOREIGN KEY (child_agent_id, child_release_version) REFERENCES public.cap_agent_def(agent_id, version) ON DELETE CASCADE,
    ADD CONSTRAINT cap_agent_child_ref_parent_fk
    FOREIGN KEY (parent_agent_id, parent_release_version) REFERENCES public.cap_agent_def(agent_id, version) ON DELETE CASCADE;

ALTER TABLE public.execution_snapshot
    DROP CONSTRAINT IF EXISTS execution_snapshot_tenant_snapshot_unique;
ALTER TABLE public.execution_snapshot DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE public.execution_snapshot
    ADD CONSTRAINT execution_snapshot_version_fk
    FOREIGN KEY (version_id) REFERENCES public.agent_version(version_id);

ALTER TABLE public.run
    ADD CONSTRAINT run_snapshot_fk
    FOREIGN KEY (snapshot_id) REFERENCES public.execution_snapshot(snapshot_id);

-- 3. 索引改成全局键。
DROP INDEX IF EXISTS public.idx_agent_version_agent;
CREATE INDEX idx_agent_version_agent ON public.agent_version USING btree (agent_id);
DROP INDEX IF EXISTS public.idx_cap_agent_def_tenant;
CREATE INDEX idx_cap_agent_def_agent ON public.cap_agent_def USING btree (agent_id, version DESC);
DROP INDEX IF EXISTS public.idx_cap_agent_child_ref_child;
CREATE INDEX idx_cap_agent_child_ref_child ON public.cap_agent_child_ref USING btree (child_agent_id, child_release_version);
DROP INDEX IF EXISTS public.idx_cap_agent_child_ref_parent;
CREATE INDEX idx_cap_agent_child_ref_parent ON public.cap_agent_child_ref USING btree (parent_agent_id, parent_release_version, ordinal);

-- 4. 已生成的 TENANT 发布头中若还带 agent，必须清理：
--    新模型下 agent 只出现在 PLATFORM 发布头，TENANT 发布头不再承载 agent。
UPDATE public.cap_config_release
   SET object_versions = (
         SELECT coalesce(jsonb_agg(v), '[]'::jsonb)
         FROM jsonb_array_elements(object_versions) v
         WHERE v->>'type' <> 'agent'
       )
 WHERE scope = 'TENANT'
   AND object_versions @> '[{"type":"agent"}]'::jsonb;

COMMENT ON TABLE public.agent IS '全局 Agent 实体。不归属任何租户；租户通过 public.tenant_agent_binding 建立多对多分配关系。';
COMMENT ON TABLE public.cap_agent_def IS 'Agent 结构化定义（全局）。租户可用性由 tenant_agent_binding 决定。';
COMMENT ON TABLE public.tenant_agent_binding IS '租户与全局 Agent 的多对多配置关联。';

COMMIT;
