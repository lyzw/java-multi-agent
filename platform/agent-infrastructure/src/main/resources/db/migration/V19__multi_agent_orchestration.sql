-- =====================================================================
-- V19：多级 Agent 编排（Agent Multi-Agent Orchestration Change Set v1.1）
--
-- 对标设计文档：
--   docs/多级 Agent优化/06_配置数据模型与生命周期设计.md
--     §变更补充：多级 Agent 数据模型与发布闭包（v1.1）
--     · AgentDefinition 增补 orchestration
--     · 关系数据 cap_agent_child_ref / agent_invocation
--     · 发布闭包算法（递归展开 + 环检测 + 规范化指纹 + CAS）
--   docs/多级 Agent优化/07_执行链集成设计.md
--     §变更补充：AgentInvocation 运行时委派（v1.1）
--
-- 技术基线：PostgreSQL 17（DEC-13）
--
-- 不可妥协的设计约束：
--   1. cap_agent_def.orchestration **默认 '{}'** —— 旧行不改写（迁移不得改写历史
--      不可变 release），缺省在**解析层**解释为 LEAF（MA-T01）。
--   2. cap_agent_child_ref 是**发布关系投影**，权威定义仍是 release 里的
--      childAgentRefs；本表允许重建，release 永不改写。
--   3. agent_invocation 只保存**摘要**（input_digest / output_digest），
--      不保存敏感输入/输出明文。
--   4. agent_invocation 的 (tenant_id, invocation_id) 唯一 → 重复投递
--      不创建重复逻辑调用（MA-T10）；终态写入用 fencing_token 做 CAS（MA-T12）。
--   5. 委派目标必须**固定版本**（child_release_version NOT NULL），
--      禁止 latest 漂移别名。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. cap_agent_def 增加 orchestration 列
--
-- ⚠️ 默认 '{}' 而不是 NULL：NOT NULL + DEFAULT 让「没填」与「填坏了」在
--    存储层就分得开 —— 填坏了会是别的 JSON 值，而不是悄悄变 NULL。
--    '{}' 在解析层等价于 LEAF（MA-T01）。
--
-- ⚠️ 本迁移**不改写**任何历史行的内容：DEFAULT 只影响之后插入的行；
--    存量行的 orchestration 读出即 '{}'，行为与迁移前完全一致。
-- ---------------------------------------------------------------------

ALTER TABLE cap_agent_def
    ADD COLUMN IF NOT EXISTS orchestration JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN cap_agent_def.orchestration IS
    '多级 Agent 编排声明（06 变更补充 · v1.1）。{} = 缺省 LEAF；'
    '子 Agent 引用必须固定 release（禁止 latest）。';

-- ---------------------------------------------------------------------
-- 2. cap_agent_child_ref（父子关系投影）
--
-- ⚠️ **不是权威来源**：权威定义是不可变 Agent release 里的 childAgentRefs。
--    本表把「边」投影出来，便于查询、影响分析与发布校验。
--
-- ⚠️ 唯一约束防止同一父 release 重复声明同一子 release：
--    重复声明会让子调用次数预算被重复计入，且闭包的边集合出现重复。
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS cap_agent_child_ref (
    tenant_id              UUID NOT NULL,
    parent_agent_id        UUID NOT NULL,
    parent_release_version INTEGER NOT NULL,
    child_agent_id         UUID NOT NULL,
    child_release_version  INTEGER NOT NULL,
    -- 声明顺序：SEQUENTIAL 模式按此顺序执行（FR-027）
    ordinal                INTEGER NOT NULL,
    required               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cap_agent_child_ref_pk PRIMARY KEY (
        tenant_id, parent_agent_id, parent_release_version,
        child_agent_id, child_release_version),
    CONSTRAINT cap_agent_child_ref_ordinal_ck CHECK (ordinal >= 0),
    CONSTRAINT cap_agent_child_ref_version_ck CHECK (
        parent_release_version > 0 AND child_release_version > 0),
    -- ⚠️ 复合外键保证「引用的是本租户真实存在的版本行」，
    --    且顺带保证租户一致（复合外键的第一列就是 tenant_id）。
    CONSTRAINT cap_agent_child_ref_parent_fk FOREIGN KEY (
        tenant_id, parent_agent_id, parent_release_version)
        REFERENCES cap_agent_def (tenant_id, agent_id, version) ON DELETE CASCADE,
    CONSTRAINT cap_agent_child_ref_child_fk FOREIGN KEY (
        tenant_id, child_agent_id, child_release_version)
        REFERENCES cap_agent_def (tenant_id, agent_id, version) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_cap_agent_child_ref_parent
    ON cap_agent_child_ref (tenant_id, parent_agent_id, parent_release_version, ordinal);
CREATE INDEX IF NOT EXISTS idx_cap_agent_child_ref_child
    ON cap_agent_child_ref (tenant_id, child_agent_id, child_release_version);

COMMENT ON TABLE cap_agent_child_ref IS
    '父子 Agent 关系的发布投影（06 变更补充 v1.1）。权威来源是 Agent release '
    '中的 childAgentRefs；本表可重建，release 不可改写。';

-- ---------------------------------------------------------------------
-- 3. agent_invocation（委派调用运行记录）
--
-- ⚠️ **只存摘要**：input_digest / output_digest 是摘要，不是明文。
--    子 Agent 的输入/输出原文若需留存，引用受控存储并按原读取授权规则处理。
--
-- ⚠️ 状态语义（07 变更补充「状态与失败语义」）：
--    QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED / TIMED_OUT / UNKNOWN。
--    UNKNOWN 仅表示「结果确实不确定且无法证明未执行」，
--    **不得**用来表示普通错误或账本写入失败（MA-T13）。
--
-- ⚠️ parent_invocation_id 为空 = 根调用。
--    父子调用共享同一 trace_id（可追溯性 NFR-011）。
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS agent_invocation (
    invocation_id        UUID NOT NULL,
    tenant_id            UUID NOT NULL,
    run_id               UUID NOT NULL,
    parent_invocation_id UUID,
    parent_agent_id      UUID NOT NULL,
    parent_agent_version INTEGER NOT NULL,
    target_agent_id      UUID NOT NULL,
    target_agent_version INTEGER NOT NULL,
    status               TEXT NOT NULL,
    -- 委派深度：根调用 = 1，直接子调用 = 2
    depth                INTEGER NOT NULL,
    trace_id             TEXT NOT NULL,
    input_digest         TEXT,
    output_digest        TEXT,
    reason_code          TEXT,
    -- 世代令牌：Worker 接管安全（MA-T12）——旧世代带过期 token 提交必须失败
    fencing_token        BIGINT NOT NULL,
    started_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at          TIMESTAMPTZ,
    CONSTRAINT agent_invocation_pk PRIMARY KEY (tenant_id, invocation_id),
    CONSTRAINT agent_invocation_status_ck CHECK (status IN (
        'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'UNKNOWN')),
    CONSTRAINT agent_invocation_depth_ck CHECK (depth > 0),
    CONSTRAINT agent_invocation_version_ck CHECK (
        parent_agent_version > 0 AND target_agent_version > 0),
    CONSTRAINT agent_invocation_fencing_ck CHECK (fencing_token > 0),
    -- ⚠️ 根调用的 parent_invocation_id 必须为空；非根调用必须非空。
    --    否则「父子关系」会出现悬空根，无法据此重建调用树。
    CONSTRAINT agent_invocation_parent_ck CHECK (
        (depth = 1 AND parent_invocation_id IS NULL)
        OR (depth > 1 AND parent_invocation_id IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_agent_invocation_run
    ON agent_invocation (tenant_id, run_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_invocation_parent
    ON agent_invocation (tenant_id, parent_invocation_id);
CREATE INDEX IF NOT EXISTS idx_agent_invocation_status
    ON agent_invocation (tenant_id, status, started_at DESC);

COMMENT ON TABLE agent_invocation IS
    '委派调用运行记录（06 变更补充 v1.1 / FR-022）。只存摘要不存明文；'
    '重复投递不创建重复逻辑调用（MA-T10）；终态写入用 fencing_token 做 CAS（MA-T12）。';

-- ---------------------------------------------------------------------
-- 4. RLS 租户隔离
--
-- ⚠️ 两张表都是**租户级** → 必须 ENABLE + FORCE ROW LEVEL SECURITY，
--    并沿用既有 current_tenant_id() 策略（V1 机制）。
--    未设置租户上下文时默认拒绝（fail-closed）。
-- ---------------------------------------------------------------------

DO $$
DECLARE
    t TEXT;
    tenant_tables TEXT[] := ARRAY[
        'cap_agent_child_ref', 'agent_invocation'
    ];
BEGIN
    FOREACH t IN ARRAY tenant_tables LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_policies
            WHERE schemaname = current_schema() AND tablename = t
        ) THEN
            EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
            EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
            EXECUTE format(
                'CREATE POLICY %I ON %I USING (tenant_id = current_tenant_id()) '
                'WITH CHECK (tenant_id = current_tenant_id())',
                t || '_tenant_isolation', t);
        END IF;
    END LOOP;
END;
$$;
