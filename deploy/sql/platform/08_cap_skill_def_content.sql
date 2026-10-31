-- ============================================================================
-- 08_cap_skill_def_content.sql
-- 关联缺口：CAP Skill「三级描述」改造（description: purpose / usage / content）
-- 关联阶段：与 G1+G2 同批落地（子代理实施「三级描述」时的遗漏项）
--
-- 目的：为 cap_skill_def 增加 content 列（Skill 描述全文，供大模型理解
--       后续工具调用的完整上下文）。
--
-- ⚠️ 为什么必须补这个迁移：
--    代码（JdbcCapSkillDefRepository）与 01_platform_ddl.sql 快照都已按
--    「三级描述」改造（读写 content 列），但**快照 ≠ 活库** ——
--    活库缺该列会让所有 CAP 相关集成测试在
--    「column "content" does not exist」上整片报错。
--    这类漂移不会在编译期暴露，只在实连库时炸，故以显式迁移脚本补齐。
--
-- ⚠️ 执行纪律（由主代理/DBA 在获得授权后执行，本脚本不自动运行）：
--    * ADD COLUMN 可空 → 不影响既有行（历史行的 content 为 NULL，
--      读取侧 SkillDescription 已把 null 归一为空串）。
--    * 本脚本与 01_platform_ddl.sql 的最新快照保持一致。
-- ============================================================================

ALTER TABLE public.cap_skill_def
    ADD COLUMN IF NOT EXISTS content text;
