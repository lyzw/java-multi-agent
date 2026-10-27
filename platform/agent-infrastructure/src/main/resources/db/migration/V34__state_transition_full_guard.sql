-- =====================================================================
-- V34 · 状态机生产化强制（评审 P0-4 · §3.3/§6.1）
-- =====================================================================
--
-- 【问题】状态机此前只有「半条守卫」：
--   1) run：V1 的 enforce_run_state_transition 只挡「终态不可迁出」，
--      不挡非法正向迁移（如 QUEUED→SUCCEEDED 可直穿）；合法流转靠
--      SQL WHERE 偶然挡住，非法流转无统一守卫。
--   2) platform.tenant：lifecycle_state 是裸 VARCHAR，服务层虽有
--      TenantLifecycle.canTransition 校验，但读/写分离存在 TOCTOU
--      （读到旧状态后并发迁移可绕过校验），且 DB 层无兜底。
--
-- 【本迁移做两件事】
--   A. 升级 run 的迁移守卫为**完整迁移表**（与 core RunState.LEGAL_TRANSITIONS 严格一致）；
--      触发器已在 V1 建好，此处仅 CREATE OR REPLACE FUNCTION，幂等。
--   B. 给 platform.tenant.lifecycle_state 挂同构守卫
--      （与 ports TenantLifecycle.ALLOWED_TRANSITIONS 严格一致）。
--
-- ⚠️ 三处口径必须逐字一致，改动须三处同步：
--      core/run/RunState.java · ports/tenant/TenantLifecycle.java · 本文件
-- =====================================================================

-- ── A. run：完整迁移表（RunState.LEGAL_TRANSITIONS 的 DB 副本）──────
CREATE OR REPLACE FUNCTION enforce_run_state_transition() RETURNS TRIGGER AS $$
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
$$ LANGUAGE plpgsql;

-- ── B. platform.tenant：生命周期守卫（TenantLifecycle 的 DB 副本）──
-- 平台域表无 RLS 但仍须守状态机：服务层校验是「礼仪」，DB 触发器是「法律」。
CREATE OR REPLACE FUNCTION enforce_tenant_lifecycle_transition() RETURNS TRIGGER AS $$
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
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_platform_tenant_lifecycle_guard ON platform.tenant;
CREATE TRIGGER trg_platform_tenant_lifecycle_guard
    BEFORE UPDATE ON platform.tenant
    FOR EACH ROW EXECUTE FUNCTION enforce_tenant_lifecycle_transition();

COMMENT ON TRIGGER trg_platform_tenant_lifecycle_guard ON platform.tenant IS
    '评审 P0-4：lifecycle_state 合法迁移守卫（权威口径 = ports TenantLifecycle.ALLOWED_TRANSITIONS）。'
    '同状态的其他列更新不受影响（IS DISTINCT FROM 判定）。';
