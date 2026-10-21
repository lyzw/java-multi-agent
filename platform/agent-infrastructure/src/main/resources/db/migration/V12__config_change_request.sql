-- V12：高危配置变更审批工作流（P4-D16 / C7）
--
-- ⚠️ **为什么不复用 approval 表**：
--   approval.operation_id UUID NOT NULL 强绑定 tool_operation（工具操作审批，FR-013）。
--   配置变更**没有**「工具操作」实体。硬塞会引入伪造的 operation_id
--   （= 数据造假）或把它改可空（= 破坏既有约束强度）。故新建独立表。
--
-- ⚠️ **状态机为何多一个 APPLIED**：
--   PENDING → APPROVED → APPLIED
--   「批准」与「已写入配置表」是**两件事**。合并会让审批人无法区分
--   「我批了」与「配置真的变了」——那正是 P4-D15 D15-B3 描述的
--   「看起来生效了其实没生效」。分两步且各自留痕。

-- ── 枚举 ──
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'config_change_state') THEN
        CREATE TYPE config_change_state AS ENUM
            ('PENDING', 'APPROVED', 'REJECTED', 'APPLIED');
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS config_change_request (
    change_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES tenant(tenant_id),
    config_key   TEXT NOT NULL,
    -- 拟写入的值（文本；按键类型解析）。⛔ 申请本身不写配置表
    proposed_value TEXT NOT NULL,
    value_type   TEXT NOT NULL
                 CHECK (value_type IN ('INTEGER', 'DECIMAL', 'ENUM', 'DURATION')),
    -- CHG 理由（P4-D15 已强制 ≥10 字；此处再存一份供审批人阅读）
    reason       TEXT NOT NULL CHECK (length(btrim(reason)) > 0),
    requester_id TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    state        config_change_state NOT NULL DEFAULT 'PENDING',

    approver_id   TEXT,
    decided_at    TIMESTAMPTZ,
    decision_note TEXT,
    applied_at    TIMESTAMPTZ,

    -- ⛔ **双人复核的 DB 兜底**：申请人不得审批自己的申请。
    --    应用层同样强制，但应用层可被绕过（直连 SQL / 未来新端点漏校验），
    --    故 DB 再挡一次——单人审批等于没有审批。
    CONSTRAINT chg_no_self_approval
        CHECK (approver_id IS NULL OR approver_id <> requester_id),

    -- ⛔ 决定必须成组出现：要么都没决定，要么都决定。
    --    杜绝「有审批人无决定时刻」的半截记录（审批人不可追溯时点）。
    CONSTRAINT chg_decision_fields_consistent
        CHECK ((approver_id IS NULL AND decided_at IS NULL)
            OR (approver_id IS NOT NULL AND decided_at IS NOT NULL)),

    -- ⛔ APPLIED 必须有 applied_at：否则「已应用」不可追溯时刻。
    CONSTRAINT chg_applied_has_timestamp
        CHECK ((state = 'APPLIED') = (applied_at IS NOT NULL))
);

COMMENT ON TABLE config_change_request IS
    'P4-D16 高危配置变更审批（L3 键）。PENDING→APPROVED→APPLIED 两步，'
    'APPROVED 与 APPLIED 分离以免「批了」被误读为「已生效」';

-- 治理页游标分页：全序索引（同 V10 的教训——单列 created_at 不满足全序）
CREATE INDEX IF NOT EXISTS idx_config_change_tenant_created
    ON config_change_request (tenant_id, requested_at DESC, change_id DESC);

-- ── 状态机守卫（V5 同构：DB 第二道防线）──
-- ⚠️ 应用层已实现全套校验；此处再加一道，双保险。
--    PENDING   → APPROVED / REJECTED
--    APPROVED  → APPLIED
--    REJECTED / APPLIED 为终态，不可迁出。
CREATE OR REPLACE FUNCTION enforce_config_change_transition() RETURNS TRIGGER AS $$
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
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_config_change_guard ON config_change_request;
CREATE TRIGGER trg_config_change_guard
    BEFORE UPDATE ON config_change_request
    FOR EACH ROW EXECUTE FUNCTION enforce_config_change_transition();

-- ── RLS：与其他表一致 ──
-- ⚠️ FORCE ROW LEVEL SECURITY 不可省略（V8 已踩：owner 不受普通 RLS 约束）。
ALTER TABLE config_change_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE config_change_request FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS config_change_request_tenant_isolation ON config_change_request;
CREATE POLICY config_change_request_tenant_isolation ON config_change_request
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ⚠️ GRANT 逐条执行：任一条失败会连带回滚全部（P3-D08 同形态）。
-- ⚠️ 新表漏 GRANT 的后果不是「功能不可用」而是 permission denied，
--    配置审批整体 fail-closed。
DO $$
BEGIN
    EXECUTE 'GRANT USAGE ON SCHEMA public TO app_agent';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON config_change_request TO app_agent';
END $$;