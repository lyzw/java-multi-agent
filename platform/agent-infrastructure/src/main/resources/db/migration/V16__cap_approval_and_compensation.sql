-- =====================================================================
-- V16：CAP 审批与补偿任务（W-PERM-P3 · §5.3.1 / §5.3.3 / §5.3.5）
-- =====================================================================
--
-- 背景：
--   P2 把 Phase A（能力链授权）与 Phase B（执行准入）接入唯一执行入口，
--   但审批仍是**既有模型**（approval 表：PENDING → APPROVED → CONSUMED，
--   消费即终态，幂等重放返回 CONSUMED）。
--
--   §5.3 要求的审批模型与之**语义不同**：
--   · 状态机：PENDING → RESERVED → INTENT_SUBMITTED → CONSUMED（+EXPIRED/CANCELLED）
--   · 预占与消费**分离**：审批先被某个 invocation 预占，执行意图持久化后才提交，
--     终态关联后才确认消费；
--   · 绑定：§5.3.1 的**双哈希**（approvalScopeHash + effectiveArgsHash）
--     外加 closureFingerprint 与 authzVersion。
--
-- ⚠️ 为什么**另起一张表**而不是改既有 approval 表：
--   两套模型对同一个状态名的语义都不同
--   （既有 PENDING = 未决议；§5.3.3 PENDING = 已批准但未被预占），
--   且终态的幂等语义相反（既有：已消费重放 → 成功；
--   §5.3.3 / §9.1 S6e：已消费重放 → APPROVAL_REPLAY_REJECTED）。
--   混在一张表里，DB 触发器无法同时表达两套状态守卫，
--   应用层也只能靠 if 分支猜「当前这条是哪个模型的审批」。
--
-- ⚠️ 本迁移**幂等**：可重复执行而不报错。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 审批状态枚举（§5.3.3）
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'cap_approval_state') THEN
        CREATE TYPE cap_approval_state AS ENUM (
            'PENDING',          -- 已批准（人工决策完成），尚未被任何调用预占
            'RESERVED',         -- 已被某 invocation 预占（绑定 invocationId）
            'INTENT_SUBMITTED', -- 执行意图已持久化，即将发起外部副作用
            'CONSUMED',         -- 已与终态结果关联并确认
            'EXPIRED',          -- 超过有效期
            'CANCELLED'         -- 补偿取消（预算/PRE/准入失败）
        );
    END IF;
END;
$$;

COMMENT ON TYPE cap_approval_state IS
    '§5.3.3 审批状态机：PENDING → RESERVED → INTENT_SUBMITTED → CONSUMED；'
    '旁路 EXPIRED / CANCELLED。状态只能前进，不得回退到 PENDING。';

-- ---------------------------------------------------------------------
-- 2. cap_approval（§5.3.1 绑定契约 + §5.3.3 状态机）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cap_approval (
    approval_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    -- 被授权主体（§5.3.1 绑定项「tenantId / principalId」）
    principal_id          TEXT NOT NULL,
    -- 预占方调用标识（未预占时为空）。= 账本 operationId（P2 决策）。
    invocation_id         TEXT,
    -- 关联的 ToolOperation（预占时绑定；可空：审批可在 Run 创建前授予）
    operation_id          UUID,
    run_id                UUID,
    tool_id               TEXT NOT NULL,
    target_resource       TEXT NOT NULL,
    -- ── §5.3.1 双哈希绑定 ──────────────────────────────────────────
    approval_scope_hash   TEXT NOT NULL,
    -- ⚠️ §5.3.1 明文：「范围型审批：记录保存完整范围 JSON + hash；
    --    消费时范围重验，失败 → APPROVAL_SCOPE_MISMATCH」。
    --    只存 hash 不存定义 → 消费时<b>无从重验</b>，防参数替换重放形同虚设。
    scope_definition      TEXT NOT NULL,
    effective_args_hash   TEXT NOT NULL,
    -- 本 Run 依赖的闭包指纹（**不**默认绑定全局 configVersion，§5.3.1）
    closure_fingerprint   TEXT NOT NULL,
    -- 生成时的主体授权版本（消费时当前 > 记录 → 失效，§5.5）
    authz_version         BIGINT NOT NULL,
    -- ── 人工决策 ────────────────────────────────────────────────────
    approver_id           TEXT NOT NULL,
    approver_scope        TEXT NOT NULL,
    decided_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ NOT NULL,
    -- ── 状态 ────────────────────────────────────────────────────────
    approval_state        cap_approval_state NOT NULL DEFAULT 'PENDING',
    reserved_at           TIMESTAMPTZ,
    intent_submitted_at   TIMESTAMPTZ,
    consumed_at           TIMESTAMPTZ,
    cancelled_at          TIMESTAMPTZ,
    terminal_status       TEXT,
    cancel_reason         TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 同一 invocation 对同一工具至多一条审批（避免重复授予同一调用）
    CONSTRAINT cap_approval_invocation_tool_unique
        UNIQUE (tenant_id, invocation_id, tool_id),
    CONSTRAINT cap_approval_run_fk FOREIGN KEY (tenant_id, run_id)
        REFERENCES run (tenant_id, run_id) ON DELETE CASCADE,
    -- ⚠️ 复合外键：预占时绑定的 operation 必须属于**同一租户**的真实逻辑操作。
    --    单列 UUID 外键挡不住跨租户引用（UUID 全局唯一，但语义可跨租户），
    --    复合外键把它降级为 DB 级不可能（§6.1 数据边界 / fail-closed）。
    --    删除动作与既有 approval_operation_fk 保持一致（CASCADE）：
    --    逻辑操作不存在时，绑定其上的审批已无意义。
    CONSTRAINT cap_approval_operation_fk FOREIGN KEY (tenant_id, operation_id)
        REFERENCES tool_operation (tenant_id, operation_id) ON DELETE CASCADE
);

-- ⚠️ **幂等补丁：补列**
--   CREATE TABLE IF NOT EXISTS 在「表已存在」时**不会补列** ——
--   若本迁移的早期草案已被应用（表已建但缺这两列），
--   后续代码会直接撞上「column does not exist」，且报错点离真因很远。
--   故显式补列（IF NOT EXISTS → 重复执行无副作用）。
--
-- ⚠️ 用 DEFAULT 回填既有行再 DROP DEFAULT：
--   加 NOT NULL 列到<b>已有数据</b>的表时必须给 DEFAULT，
--   否则 PG 直接拒绝（existing rows contain nulls）。
--   ⚠️ 不可用 UPDATE 回填：本表 FORCE RLS，
--   迁移角色虽是 owner 也受策略约束 → 未设租户上下文时 UPDATE 影响 0 行。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'cap_approval' AND column_name = 'principal_id') THEN
        ALTER TABLE cap_approval ADD COLUMN principal_id TEXT NOT NULL DEFAULT 'unknown';
        ALTER TABLE cap_approval ALTER COLUMN principal_id DROP DEFAULT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'cap_approval' AND column_name = 'scope_definition') THEN
        ALTER TABLE cap_approval ADD COLUMN scope_definition TEXT NOT NULL DEFAULT '{}';
        ALTER TABLE cap_approval ALTER COLUMN scope_definition DROP DEFAULT;
    END IF;
END;
$$;

COMMENT ON TABLE cap_approval IS
    'CAP 审批（§5.3.1 / §5.3.3）。与既有 approval 表是两套模型，不可混用：'
    '本表支持预占、绑定双哈希、状态机 RESERVED→INTENT_SUBMITTED→CONSUMED。';

COMMENT ON COLUMN cap_approval.approval_scope_hash IS
    '§5.3.1 范围哈希：审批允许的操作范围与参数约束的规范化摘要。'
    '消费时用同一份范围定义重新验证实际参数（范围重验）。';
COMMENT ON COLUMN cap_approval.effective_args_hash IS
    '§5.3.1 有效参数哈希：本次全部有效参数的 args_norm_v1 摘要。'
    '防止「参数替换重放」——同键不同参必须被拒。';
COMMENT ON COLUMN cap_approval.scope_definition IS
    '§5.3.1 范围定义全文（JSON）。消费时用它重新验证实际参数是否仍落在范围内；'
    '只存哈希不存定义则无法重验，防参数替换重放形同虚设。';
COMMENT ON COLUMN cap_approval.principal_id IS
    '§5.3.1 绑定项：被授权主体。非本主体的审批一律视为「不存在」。';
COMMENT ON COLUMN cap_approval.authz_version IS
    '生成时的主体授权版本。消费时当前版本 > 本值 → 审批失效（§5.5）。';

CREATE INDEX IF NOT EXISTS idx_cap_approval_tool
    ON cap_approval (tenant_id, tool_id, created_at);
CREATE INDEX IF NOT EXISTS idx_cap_approval_invocation
    ON cap_approval (tenant_id, invocation_id) WHERE invocation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_cap_approval_state
    ON cap_approval (tenant_id, approval_state, created_at);

-- ---------------------------------------------------------------------
-- 3. 审批状态守卫（§5.3.3 · DB 第二道防线）
--
-- ⚠️ 与应用层的 CAS 预占构成双保险：
--    应用层用「期望原状态」做前置条件，DB 层用触发器禁止非法跃迁。
-- ⚠️ 终态（CONSUMED / EXPIRED / CANCELLED）**不可迁出** ——
--    迁出等于让「已被拒绝的审批」重新变得可用。
--
-- ⚠️ **旁路终态（EXPIRED / CANCELLED）可从任一非终态到达**：
--    §5.3.3 原文「可选：EXPIRED / CANCELLED」—— 它们是<b>旁路</b>，
--    不是主链上的一环。若把它们也锁死在某单一前态，
--    「审批在预占前被撤销」「审批过期」这两条正常业务路径就写不进库。
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION enforce_cap_approval_state_transition() RETURNS TRIGGER AS $$
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
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_cap_approval_state_guard ON cap_approval;
CREATE TRIGGER trg_cap_approval_state_guard
    BEFORE UPDATE ON cap_approval
    FOR EACH ROW EXECUTE FUNCTION enforce_cap_approval_state_transition();

COMMENT ON FUNCTION enforce_cap_approval_state_transition() IS
    '§5.3.3 审批状态机守卫：状态只能前进；终态不可迁出；'
    'PENDING→RESERVED→INTENT_SUBMITTED→CONSUMED，旁路 CANCELLED/EXPIRED。';

-- ---------------------------------------------------------------------
-- 4. cap_compensation_task（§5.3.5 跨系统恢复表）
--
-- ⚠️ **补偿失败必须持久化**（§5.3.5）：
--   预算已预留但释放失败 / 审批已预占但取消失败，
--   这两类「悬空」若只记日志就只能靠人肉排查。
--   故补偿框架在补偿失败时落一条任务，由独立调度重放。
-- ⚠️ **补偿必须幂等**：重复释放额度 = 额度凭空放大（比超发更隐蔽）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cap_compensation_task (
    task_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    kind              TEXT NOT NULL,
    target_id         TEXT NOT NULL,
    invocation_id     TEXT,
    payload           TEXT,
    state             TEXT NOT NULL DEFAULT 'PENDING',
    attempts          INTEGER NOT NULL DEFAULT 0,
    last_error        TEXT,
    next_retry_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cap_compensation_kind_valid CHECK (
        kind IN ('CANCEL_APPROVAL', 'RELEASE_BUDGET', 'RETRY_LEDGER_POST')),
    CONSTRAINT cap_compensation_state_valid CHECK (
        state IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT cap_compensation_attempts_valid CHECK (attempts >= 0),
    -- 幂等键：同一租户 + 补偿类型 + 目标 至多一条未完成任务
    CONSTRAINT cap_compensation_target_unique
        UNIQUE (tenant_id, kind, target_id)
);

COMMENT ON TABLE cap_compensation_task IS
    '补偿任务（§5.3.5）。补偿失败时落库，由独立调度重放；'
    '唯一约束保证同一补偿不会重复入队（幂等）。';

CREATE INDEX IF NOT EXISTS idx_cap_compensation_pending
    ON cap_compensation_task (tenant_id, state, created_at);

-- ---------------------------------------------------------------------
-- 5. RLS 租户隔离（ADR-006 / §6.1）
--
-- ⚠️ 两张表都是**租户级**（审批与补偿都属于某个租户的业务事实），
--    故与 cap_execution_intent 等同属 FORCE RLS 名单。
--    未设置租户上下文时默认拒绝（fail-closed）。
--
-- ⚠️ **本段必须幂等**：PostgreSQL 的 CREATE POLICY **不支持 IF NOT EXISTS**
--    （PG 17 实测：语法错误），故用 pg_policies 存在性判断来跳过。
--    否则重复执行迁移会中断在第二条策略上，
--    后续迁移与「迁移报告」都被跳过 —— 幂等性不是锦上添花，是正确性前提。
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
    tenant_tables TEXT[] := ARRAY['cap_approval', 'cap_compensation_task'];
BEGIN
    FOREACH t IN ARRAY tenant_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        IF NOT EXISTS (
            SELECT 1 FROM pg_policies
            WHERE schemaname = 'public'
              AND tablename = t
              AND policyname = t || '_tenant_isolation'
        ) THEN
            EXECUTE format(
                'CREATE POLICY %I ON %I USING (tenant_id = current_tenant_id()) '
                'WITH CHECK (tenant_id = current_tenant_id())',
                t || '_tenant_isolation', t);
        END IF;
    END LOOP;
END;
$$;
