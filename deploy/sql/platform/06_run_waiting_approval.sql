-- ============================================================================
-- 06_run_waiting_approval.sql
-- 关联缺口：G2（HITL 人工审批恒拒）
-- 关联阶段：M1-1 / M1-2（操作身份前移 + PENDING 语义 + RUN 可暂停）
--
-- 目的：为 Run 增加「等待人工审批」挂起态 WAITING_APPROVAL，
--       使需审批的工具在登记待办后能让出 worker 线程与租约（PARK），
--       待审批决定到达再由 WakeupConsumer 唤醒并 RESUME（不重跑模型）。
--
-- ⚠️ 执行纪律（由主代理/DBA 在获得授权后执行，本脚本不自动运行）：
--   * ALTER TYPE ... ADD VALUE 不可回滚（PG 不支持删除枚举值）；
--     如需回退，请用全新 schema 重建，不依赖删枚举。
--   * 本脚本与 01_platform_ddl.sql 的最新快照保持一致；
--     SchemaDriftIT 以 01 快照为准做漂移校验。
-- ============================================================================

-- 1) 扩展 run_state 枚举，加入挂起态
--    ⚠️ BEFORE 'CANCELLED'：PG 的 ADD VALUE 默认追加到**末尾**，
--    而 01_platform_ddl.sql 的快照里 WAITING_APPROVAL 位于 RUNNING 之后。
--    位置不一致会让「快照 vs 活库」的比对出现无谓差异（漂移误报）。
ALTER TYPE public.run_state ADD VALUE IF NOT EXISTS 'WAITING_APPROVAL' BEFORE 'CANCELLED';

-- 2) run_completed_at_consistent：WAITING_APPROVAL 为非终态，
--    completed_at 必须为 NULL（与 QUEUED / RUNNING 同组）
ALTER TABLE public.run DROP CONSTRAINT IF EXISTS run_completed_at_consistent;
ALTER TABLE public.run ADD CONSTRAINT run_completed_at_consistent
    CHECK (
        (
            (state = ANY (ARRAY[
                'QUEUED'::public.run_state,
                'RUNNING'::public.run_state,
                'WAITING_APPROVAL'::public.run_state
            ])) AND (completed_at IS NULL)
        ) OR (
            (state = ANY (ARRAY[
                'CANCELLED'::public.run_state,
                'SUCCEEDED'::public.run_state,
                'FAILED'::public.run_state,
                'TIMED_OUT'::public.run_state
            ])) AND (completed_at IS NOT NULL)
        )
    );

-- 3) enforce_run_state_transition：放开 RUNNING↔WAITING_APPROVAL 与 WAITING_APPROVAL 的收敛迁移
CREATE OR REPLACE FUNCTION public.enforce_run_state_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.state IS DISTINCT FROM OLD.state THEN
        IF OLD.state = 'QUEUED'
           AND NEW.state IN ('RUNNING', 'CANCELLED') THEN
            RETURN NEW;
        END IF;
        IF OLD.state = 'RUNNING'
           AND NEW.state IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'WAITING_APPROVAL') THEN
            RETURN NEW;
        END IF;
        IF OLD.state = 'WAITING_APPROVAL'
           AND NEW.state IN ('RUNNING', 'CANCELLED', 'TIMED_OUT', 'FAILED') THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'Run 非法状态迁移 % → %（run_id=%）',
            OLD.state, NEW.state, OLD.run_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
