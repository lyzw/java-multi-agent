-- =============================================================================
-- V1.0.2 活库等价 ALTER（四期：审核员名录）
-- =============================================================================
-- 纪律：01_rag_ddl.sql 是「终态快照」，本文件是它的**等价增量**，用于已存在的活库。
--       切勿对已存在的库重放 01_rag_ddl.sql（它是 CREATE TABLE，非幂等）。
--
-- 【解决什么】交互文档 §4.6「签发表单」要求「复核人」从下拉选人，
--   而不是手输 UUID。但 RAG 与平台是**两个独立数据库**
--   （RAG=study_rag / 平台=agent_dev），RAG 侧看不到 public.principal，
--   且 RAG 没有任何 HTTP 客户端可回调平台接口。
--
-- 【为什么不跨库查询】
--   1. RAG 库与平台库不同库，同实例跨库查询需要额外的 dblink/外部表，
--      且会让 RAG 依赖平台的表结构（耦合方向错误）；
--   2. 平台 principal 表当前为空（IAM 发号尚未开始），跨库也查不到人；
--   3. D-001 §3.2 明确：「JWT 里的 roles 只是声明，实际生效的角色以
--      **库中登记**为准」——所以「谁是审核员」本就该有一份登记。
--
-- 【本表是什么】RAG 自己的**审核员名录**：记录「本租户有哪些主体可以做审核/复核」。
--   ⚠️ 它不是 IAM 的替代品，而是 IAM 在 RAG 侧的**授权投影**：
--   只登记「能在 RAG 做审核」这一件事，不存密码、不存登录名、不存真实姓名。
--
-- ⚠️ 上线顺序：先跑本文件 → 再部署应用（读路径缺表会直接 SQL 报错）。
-- =============================================================================

BEGIN;

-- ── 1. 审核员名录 ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS rag.rag_reviewer_roster (
    tenant_id     uuid NOT NULL,
    -- 主体 ID（与平台 JWT 的 sub 一致；RAG 只认这个 ID，不认登录名）
    principal_id  uuid NOT NULL,
    -- 显示名：⚠️ 必须是人能认出的内部标识，**不得含真实姓名**（对齐 DEC-6 / PIPL）
    display_name  text NOT NULL,
    -- 是否可做「复核人」（第二位签署人）。⚠️ 可执行审核 ≠ 可做复核：
    -- 复核是双人签署的第二签，通常只授予少数资深审核员。
    can_review    boolean NOT NULL DEFAULT true,
    can_co_sign   boolean NOT NULL DEFAULT false,
    -- 名录条目是否启用（停用不删除：历史签署记录仍要能解析出「当时是谁」）
    enabled       boolean NOT NULL DEFAULT true,
    scope         text NOT NULL DEFAULT 'TENANT',
    created_at    timestamp with time zone NOT NULL DEFAULT now(),
    updated_at    timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT rag_reviewer_roster_pk PRIMARY KEY (tenant_id, principal_id),
    -- ⚠️ 显示名不得为空：下拉里出现空条目等于让人盲选
    CONSTRAINT rag_reviewer_roster_name_chk CHECK (length(btrim(display_name)) > 0),
    -- ⚠️ 做复核的人必须也能审核（否则「能复核但不能审核」是矛盾状态）
    CONSTRAINT rag_reviewer_roster_sign_chk CHECK (NOT can_co_sign OR can_review),
    CONSTRAINT rag_reviewer_roster_scope_ck CHECK (
        (scope = 'PLATFORM' AND tenant_id = rag.rag_global_tenant_id())
        OR (scope = 'TENANT' AND tenant_id <> rag.rag_global_tenant_id())
    )
);

-- ⚠️ 停用条目保留但不参与下拉：需要一个「只看启用」的检索路径
CREATE INDEX IF NOT EXISTS rag_reviewer_roster_active_idx
    ON rag.rag_reviewer_roster USING btree (tenant_id, can_co_sign, enabled)
    WHERE enabled = true;

-- ── 2. RLS：与其它租户表一致 ────────────────────────────────────────────────
ALTER TABLE rag.rag_reviewer_roster ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag.rag_reviewer_roster FORCE ROW LEVEL SECURITY;

-- ⚠️ 策略按**操作分开**，与 rag_source 现有写法一致：
--    读必须能读到「全局语料」（tenant_id = 全局哨兵），
--    写只能写本租户。合成一条 FOR ALL 会让全局语料读不到（与既有 6 处踩坑同型）。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'rag' AND tablename = 'rag_reviewer_roster'
          AND policyname = 'rag_reviewer_roster_read'
    ) THEN
        CREATE POLICY rag_reviewer_roster_read
            ON rag.rag_reviewer_roster
            FOR SELECT
            TO PUBLIC
            USING (tenant_id = ANY (ARRAY[rag.rag_current_tenant(), rag.rag_global_tenant_id()]));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'rag' AND tablename = 'rag_reviewer_roster'
          AND policyname = 'rag_reviewer_roster_write'
    ) THEN
        CREATE POLICY rag_reviewer_roster_write
            ON rag.rag_reviewer_roster
            FOR INSERT
            TO PUBLIC
            WITH CHECK (tenant_id = rag.rag_current_tenant());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'rag' AND tablename = 'rag_reviewer_roster'
          AND policyname = 'rag_reviewer_roster_update'
    ) THEN
        CREATE POLICY rag_reviewer_roster_update
            ON rag.rag_reviewer_roster
            FOR UPDATE
            TO PUBLIC
            USING (tenant_id = rag.rag_current_tenant())
            WITH CHECK (tenant_id = rag.rag_current_tenant());
    END IF;
END
$$;

COMMIT;

-- =============================================================================
-- 回滚（仅删表；⚠️ 会丢失名录，需单独审批）
-- =============================================================================
-- BEGIN;
-- DROP TABLE IF EXISTS rag.rag_reviewer_roster;
-- COMMIT;
