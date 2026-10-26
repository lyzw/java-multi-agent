-- Skill 两级描述（契约 skill.schema.json · $defs.skillDescription）。
--
-- 契约规定 description 是两级对象：
--   1级 purpose —— 供路由/选型（Agent 挑工具时的选型信号），一句话用途；
--   2级 usage   —— 供选中后执行（分场景 when → how 操作指引），≤20 条。
--
-- ⚠️ 描述不是授权：两级内容只引导已声明能力（declaredTools / declaredMcp），
--    授权真相只有声明列表与 riskCeiling，与描述冲突时按声明执行（fail-closed）。
-- ⚠️ 两级内容均计入 configFingerprint（服务端 CapObjectFingerprint.of(CapSkillDef)）。
-- ⚠️ 存量 description TEXT 列为旧的单级描述：已发布版本行不可变（§6.1），
--    故保留该列只读不写，新语义只走 purpose / usage；不回填、不改旧行。
ALTER TABLE cap_skill_def ADD COLUMN purpose TEXT;
ALTER TABLE cap_skill_def ADD COLUMN usage JSONB;
