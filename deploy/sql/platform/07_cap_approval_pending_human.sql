-- ============================================================================
-- 07_cap_approval_pending_human.sql
-- 关联缺口：G1（CAP 路径未接人工审批 · E4「cap_approval 无法表示未决」）
-- 关联阶段：M3-1（CAP 路径 HITL 的数据模型前置）
--
-- 目的：让 cap_approval 能表示「已登记待办、尚未有人决定」。
--
-- ⚠️⚠️ **为什么新增状态值而不是复用 PENDING**：
--    cap_approval 的 PENDING 语义是「**已批准**（人工或平台预授权已完成），
--    尚未被任何调用预占」—— 它是**可直接消耗的授权**。
--    若把「尚未有人决定」也塞进 PENDING，则：
--      * 既有预建审批行（approver_id 非空、state=PENDING）语义被污染；
--      * 「人还没批」与「人已批待消耗」无法区分 → 未批准的副作用动作
--        可能被直接消耗 = Sev-0。
--    故新增独立状态 AWAITING_HUMAN，并只在该状态下放宽 approver_id/decided_at。
--
-- ⚠️ 执行纪律（由主代理/DBA 在获得授权后执行，本脚本不自动运行）：
--    * ALTER TYPE ... ADD VALUE 不可回滚（PG 不支持删除枚举值）；
--      如需回退，请用全新 schema 重建，不依赖删枚举。
--    * 本脚本与 01_platform_ddl.sql 的最新快照保持一致；
--      SchemaDriftIT 以 01 快照为准做漂移校验。
-- ============================================================================

-- 1) 新增「待人工决策」状态（追加到枚举末尾，保持与 Java 枚举顺序一致）
ALTER TYPE public.cap_approval_state ADD VALUE IF NOT EXISTS 'AWAITING_HUMAN';

-- 2) 仅 AWAITING_HUMAN 状态下允许「无审批人 / 未决定时间」
--    ⚠️ 只放宽这两列的可空性，**不改动**既有 PENDING 行的语义与约束。
ALTER TABLE public.cap_approval ALTER COLUMN approver_id DROP NOT NULL;
ALTER TABLE public.cap_approval ALTER COLUMN decided_at DROP NOT NULL;

-- 3) 约束：只有「待人工决策」才允许没有审批人
--    ⚠️ 该 CHECK 对既有行**不生效**（既有行是 PENDING 且 approver_id 非空），
--       故不会因加约束而失败或改变既有数据语义。
ALTER TABLE public.cap_approval DROP CONSTRAINT IF EXISTS cap_approval_pending_needs_approver;
ALTER TABLE public.cap_approval ADD CONSTRAINT cap_approval_pending_needs_approver
    CHECK (
        approval_state <> 'AWAITING_HUMAN'::public.cap_approval_state
        OR approver_id IS NULL
    );
