-- =====================================================================
-- V4：预算预留唯一性与关联（P3-D04 / FR-012 / R-007）
--
-- 背景：V1 基线已建 budget_account / budget_reservation / usage_record
-- 三张表并声明了 budget_no_overspend CHECK，但**预留从未被写入**，
-- 该约束从未被真实触发过一次。本迁移补齐「预留唯一记录」所需结构：
--
--   1. budget_reservation 关联到具体的 ToolOperation（operation_id）
--   2. (tenant_id, operation_id) 部分唯一索引 → 同一逻辑操作至多一条预留
--   3. state 由自由 TEXT 收紧为三值 CHECK（原先可写入 'resrved' 而无人察觉）
--   4. usage_record 关联 reservation_id，便于对账追溯
--   5. 悬挂预留扫描索引（对账入口）
--
-- ⚠️ 本脚本必须**幂等**：可重复执行而不报错。
--   所有结构变更用 DO $$ ... IF NOT EXISTS / DROP ... IF EXISTS 包裹。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. budget_reservation 关联 ToolOperation
-- ---------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'budget_reservation' AND column_name = 'operation_id'
    ) THEN
        ALTER TABLE budget_reservation ADD COLUMN operation_id UUID;
    END IF;
END;
$$;

COMMENT ON COLUMN budget_reservation.operation_id IS
    '关联的 ToolOperation。同租户下至多一条预留（part_uniq_reservation_operation）——'
    '重复调用不得重复占用额度（FR-012「预留有唯一记录」）。'
    '可空：存量数据与无账本调用的预留允许为空。';

-- ⚠️ 部分唯一索引：operation_id IS NOT NULL 时才生效
--   （存量行 operation_id 为 NULL 不受约束）
CREATE UNIQUE INDEX IF NOT EXISTS part_uniq_reservation_operation
    ON budget_reservation (tenant_id, operation_id)
    WHERE operation_id IS NOT NULL;

COMMENT ON INDEX part_uniq_reservation_operation IS
    '同一 ToolOperation 至多一条预算预留。并发下「先查后插」的两个请求，'
    '后者由此唯一索引失败并回查复用——避免重复扣减额度（D-3）';

-- ---------------------------------------------------------------------
-- 2. state 收紧为三值 CHECK
--
-- ⚠️ V1 中 state 为自由 TEXT NOT NULL DEFAULT 'RESERVED'，
--    拼写错误（如 'resrved'）可静默写入，账实核对时才发现——
--    与「预算失控」同类的静默失效。这里收紧取值域。
-- ---------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'reservation_state_valid'
    ) THEN
        ALTER TABLE budget_reservation
            ADD CONSTRAINT reservation_state_valid
            CHECK (state IN ('RESERVED', 'SETTLED', 'RELEASED'));
    END IF;
END;
$$;

COMMENT ON CONSTRAINT reservation_state_valid ON budget_reservation IS
    '预留状态仅三值。终态（SETTLED/RELEASED）不得回退——'
    '防止「已结算被改回 RESERVED」导致额度被二次占用';

-- ⚠️ 终态不可迁出（应用层是第二道防线，此处为 DB 第一道）
CREATE OR REPLACE FUNCTION enforce_reservation_state_transition() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state IN ('SETTLED', 'RELEASED') AND NEW.state <> OLD.state THEN
        RAISE EXCEPTION '预算预留已终态 %，不可迁出至 %（reservation_id=%）',
            OLD.state, NEW.state, OLD.reservation_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_enforce_reservation_state_transition ON budget_reservation;
CREATE TRIGGER trg_enforce_reservation_state_transition
    BEFORE UPDATE ON budget_reservation
    FOR EACH ROW EXECUTE FUNCTION enforce_reservation_state_transition();

COMMENT ON FUNCTION enforce_reservation_state_transition() IS
    '预算预留终态不可迁出。重复结算/重复释放若实现为 UPDATE，'
    '第二次会因状态已是终态而被拒绝——这是「不重复计费」的 DB 级保证';

-- ---------------------------------------------------------------------
-- 3. usage_record 关联预留
-- ---------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'usage_record' AND column_name = 'reservation_id'
    ) THEN
        ALTER TABLE usage_record ADD COLUMN reservation_id UUID;
    END IF;
END;
$$;

COMMENT ON COLUMN usage_record.reservation_id IS
    '关联的预算预留。对账时用于「计量明细 ↔ 预留明细」双向追溯';

CREATE INDEX IF NOT EXISTS idx_usage_reservation ON usage_record (tenant_id, reservation_id)
    WHERE reservation_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 4. 悬挂预留扫描索引（对账入口）
--
-- 对账要反复扫「state=RESERVED 的预留」，无索引时随数据量线性劣化。
-- ---------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_reservation_state ON budget_reservation (tenant_id, state)
    WHERE state = 'RESERVED';

COMMENT ON INDEX idx_reservation_state IS
    '预算对账的扫描入口：只索引仍占额度的 RESERVED 行，'
    '已结算/已释放的不进入索引（体积随业务增长可控）';

-- ---------------------------------------------------------------------
-- 5. 预留的 run 外键已有（V1 reservation_run_fk），此处补语义注释
-- ---------------------------------------------------------------------

COMMENT ON COLUMN budget_reservation.state IS
    'RESERVED / SETTLED / RELEASED。UNKNOWN 对应的预留**保持 RESERVED**，'
    '绝不自动释放——成本可能已发生（R-007 / P3-D04 §2.4）。'
    '只有对账收敛（RECONCILED_SUCCEEDED → 结算 / RECONCILED_ABSENT → 释放）'
    '或确定失败（FAILED → 释放）才可离开 RESERVED';

COMMENT ON TABLE budget_reservation IS
    '预算预留账。并发不超发的保证来自两条：'
    '（1）应用层单条原子条件 UPDATE（WHERE reserved+settled+? <= total_limit）；'
    '（2）budget_account 上的 budget_no_overspend CHECK 作为最后防线。'
    '二者缺一不可：仅有 CHECK 会出现「随机预算不足」，仅有应用层会在 SQL 写错时失守';
