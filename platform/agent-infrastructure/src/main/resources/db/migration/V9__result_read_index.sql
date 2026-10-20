-- =====================================================================
-- V9 迁移：结果读取（G5）所需的按 Run 检索索引
--
-- 背景（P4-D10 §1.2 实测）：
--   结果读取端点要按 (tenant_id, run_id) 取三类数据：
--     · run_step          → 已有 idx_run_step_run (tenant_id, run_id, step_number) ✅
--     · tool_operation    → 已有 idx_tool_operation_run (tenant_id, run_id)     ✅
--     · weakness_record   → ❌ **无任何以 run_id 开头的索引**
--
--   weakness_record 的三个现有索引分别以
--   student_id / knowledge_point / created_at 开头，
--   对「按 run 查」一条都帮不上。
--
--   ⚠️ run_id 上虽然有指向 run(run_id) 的外键，
--      但 PostgreSQL **不会**为外键的引用侧自动建索引
--      （外键约束只保证引用完整性，索引是查询侧的事）。
--      → 按 run 查询会退化为全表扫。
--
-- 影响：RLS 策略把可见行压到单租户后，全表扫的代价仍然按全表计。
--       这是「功能可用但越用越慢」的形态，比报错更难发现。
--
-- 处置：只加索引，**不改表结构**（不动列、不动约束、不动策略）。
--
-- 幂等：IF NOT EXISTS，可重复执行。
-- =====================================================================

CREATE INDEX IF NOT EXISTS idx_weakness_run
    ON weakness_record (tenant_id, run_id);

COMMENT ON INDEX idx_weakness_run IS
    '结果读取（G5）按 Run 检索薄弱点。'
    '外键不会自动为引用侧建索引，故须显式声明；'
    '缺失时按 run 查询会退化为全表扫。';

-- ---------------------------------------------------------------------
-- 执行计划自检：确认查询计划走了新索引，而不是 seq scan
-- ---------------------------------------------------------------------
-- ⚠️ 本段是**自检**，不是业务逻辑。
--    小表上 planner 可能仍选 seq scan（成本估算所致），
--    因此下面只作**提示性输出**，不作为断言；
--    真正的判据见 P4-D10 §4.3 反证 A-1 与集成测试的 EXPLAIN 断言。
--
-- EXPLAIN (ANALYZE, BUFFERS)
-- SELECT knowledge_point, error_type, confidence
--   FROM weakness_record
--  WHERE tenant_id = '00000000-0000-0000-0000-000000000001'::uuid
--    AND run_id     = '00000000-0000-0000-0000-000000000002'::uuid;
