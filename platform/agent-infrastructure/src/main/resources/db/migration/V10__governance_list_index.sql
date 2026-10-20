-- V10：治理列表查询索引（P4-D13 / G7~G10）
--
-- ⚠️ 为什么需要本迁移：
-- 现有 run 表索引全部是**部分索引**且不含 created_at
--   idx_run_correlation        (tenant_id, correlation_id)
--   idx_run_lease              (tenant_id, lease_expires_at) WHERE state='RUNNING'
--   idx_run_state_queued       (tenant_id, state)             WHERE state='QUEUED'
-- 它们无法支撑「按 created_at 倒序翻页」的全量扫描——没有可用的顺序。
--
-- ⚠️ 为什么是 (tenant_id, created_at DESC, run_id DESC) 而不是 (tenant_id, created_at DESC)：
--   created_at 是 **timestamptz**（微秒精度），同一微秒内的多行排序不确定。
--   缺第二分量时游标分页会在同刻行上重复或漏读——这正是 P4-D13 反证 A-2 要锁的。

-- Run 列表（P7 检索台 / G7）
CREATE INDEX IF NOT EXISTS idx_run_tenant_created
    ON run (tenant_id, created_at DESC, run_id DESC);

-- 审批列表（P8 审批台 / G8）
-- ⚠️ 现有 idx_approval_pending (tenant_id, state) WHERE state='PENDING'
--    不含 created_at，且只覆盖 PENDING 一个状态；
--    治理页需要「不限状态」的历史查询，故另建全序索引。
CREATE INDEX IF NOT EXISTS idx_approval_tenant_created
    ON approval (tenant_id, created_at DESC, approval_id DESC);

-- 操作列表（P9 处置台 / G9）
-- ⚠️ 现有 idx_tool_operation_unknown (tenant_id, state) WHERE state='UNKNOWN'
--    同样不含 created_at，无法支撑游标翻页。
CREATE INDEX IF NOT EXISTS idx_tool_operation_tenant_created
    ON tool_operation (tenant_id, created_at DESC, operation_id DESC);

-- 审计列表（P10 审计页 / G10）
-- ⚠️ 现有 idx_audit_tenant_time (tenant_id, created_at DESC) 缺第二分量，
--    游标分页在同刻行上不满足全序 → 重复/漏读。
--    重复建一个看似冗余的索引是有意的：全序无法用单列索引表达。
CREATE INDEX IF NOT EXISTS idx_audit_tenant_time_id
    ON audit_record (tenant_id, created_at DESC, audit_id DESC);