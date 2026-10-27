-- =====================================================================
-- run_step  fencing 守卫（P7-2）
-- =====================================================================
--
-- 【为什么需要】
--   run_step 从 P2 起就存在，但历史上**零生产写入方**，故 P3-D02 §L-1 明确：
--   「当前无写入路径…不构成现实漏洞；但 D6/D7 实现写入时**必须**同步加
--    enforce_fencing_on_step 类触发器，否则红线 5 在步骤层面失效」。
--   P7-2 补全了写入方（JdbcRunStepRepository），此处闭合该缺口——
--   旧世代 Worker 若回写/覆盖步骤，DB 层直接拒绝（token 回退）。
--
-- 【复用既有函数】
--   enforce_fencing_on_write() 已在 V1 基线定义，语义为
--   「NEW.fencing_token < OLD.fencing_token → 拒绝」。
--   INSERT 时 OLD 为 NULL，比较为 NULL（非 true）→ 放行；
--   UPDATE（含 ON CONFLICT DO UPDATE 覆盖）时校验 → 旧世代覆盖被拒。
--   该函数对 run / run_step 同构适用，无需新建函数。
-- =====================================================================

CREATE TRIGGER trg_run_step_fencing_guard
    BEFORE UPDATE ON public.run_step
    FOR EACH ROW EXECUTE FUNCTION public.enforce_fencing_on_write();
