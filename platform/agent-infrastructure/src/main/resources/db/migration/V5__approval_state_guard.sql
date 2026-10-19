-- P3-D05 / FR-013：审批状态迁移守卫（DB 第二道防线）
--
-- ⚠️ 应用层（JdbcApprovalRepository）已实现审批消费的全套校验
--    （绑定防篡改 / 过期 / 撤销 / 取消检查 / 幂等消费）。
--    本迁移在 DB 层再加一道：禁止从终态非法迁出、禁止 PENDING/APPROVED
--    跃迁到非法的目标态。两者构成双保险（D-4）。
--
-- 审批状态流转（approval_state）：
--   PENDING  → APPROVED / REJECTED / EXPIRED
--   APPROVED → CONSUMED / EXPIRED / REJECTED
--   CONSUMED / REJECTED / EXPIRED 为终态，不可迁出。

CREATE OR REPLACE FUNCTION enforce_approval_state_transition() RETURNS TRIGGER AS $$
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
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_approval_state_guard ON approval;
CREATE TRIGGER trg_approval_state_guard
    BEFORE UPDATE ON approval
    FOR EACH ROW EXECUTE FUNCTION enforce_approval_state_transition();
