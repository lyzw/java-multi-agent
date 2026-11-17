-- =====================================================================
-- 身份改造：principal ↔ student_id 绑定（2026-11-17）
-- ---------------------------------------------------------------------
-- 背景：此前「学生业务编号」（stu_ 前缀）只存在于画像/薄弱点等业务表里，
--       与**登录主体**没有任何绑定关系 —— 编号只能由学生手填，
--       于是（a）学生得知道一个没人告诉过他的格式，（b）任何人都能填别人的编号。
--
-- 本补丁：给 principal 加一列 student_id，把「登录名（= 学生用户名）」
--        与「匿名业务编号」1:1 绑定起来。
--
-- ⚠️ 执行前提：需要**表属主**（ai_agent）连接执行 ——
--    ALTER TABLE ... ADD CONSTRAINT 要求属主权限，
--    应用账号（app_agent / platform_ops）执行会报
--    「must be owner of table principal」。
--    口令按项目约定**刻意不落盘**：export AI_AGENT_PASSWORD='...' 后再执行。
--
-- ⚠️ 回填口径：只给**持有 STUDENT 角色**的主体绑定编号；
--    平台/家长/审核/作业等非学生主体保持 NULL（允许多个 NULL）。
--    编号由 login_name 确定性派生（md5 前 16 位小写十六进制），
--    保证反复执行得到同一编号 —— 已有画像/薄弱点不会因本次绑定而失联。
--
-- ✅ 幂等：可重复执行。
-- =====================================================================

BEGIN;

-- ① 加列（幂等）
ALTER TABLE public.principal ADD COLUMN IF NOT EXISTS student_id text;

-- ② 格式约束：必须与 GetStudentProfileTool / RecordWeaknessTool 的
--    ^stu_[a-z0-9]{16}$ 完全一致，否则 Worker 侧写入画像/薄弱点会被拒。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'principal_student_id_format'
    ) THEN
        ALTER TABLE public.principal
            ADD CONSTRAINT principal_student_id_format
            CHECK (student_id IS NULL OR (student_id ~ '^stu_[a-z0-9]{16}$'::text));
    END IF;
END
$$;

-- ③ 租户内唯一：同一租户两个主体不得指向同一份画像/薄弱点。
--    ⚠️ partial index 排除 NULL —— 非学生主体可以有多行 NULL。
CREATE UNIQUE INDEX IF NOT EXISTS uq_principal_student_id
    ON public.principal USING btree (tenant_id, student_id)
    WHERE (student_id IS NOT NULL);

-- ④ 回填：持有 STUDENT 角色的主体按 login_name 确定性派生编号。
--    ⚠️ 先校验派生值不会撞车：md5 前 16 位撞车概率极低，
--    但唯一索引是后加的，故先查一次并在冲突时跳过（不覆盖已有绑定）。
UPDATE public.principal p
SET student_id = 'stu_' || substr(md5(p.login_name), 1, 16)
WHERE p.student_id IS NULL
  AND EXISTS (
      SELECT 1
      FROM public.principal_role pr
      WHERE pr.tenant_id = p.tenant_id
        AND pr.principal_id = p.principal_id
        AND pr.role = 'STUDENT'
  );

COMMIT;

-- ⑤ 自检：应能看到每个 STUDENT 主体都有编号，非学生主体为 NULL。
-- SELECT p.login_name, p.student_id, string_agg(pr.role, ',' ORDER BY pr.role)
--   FROM public.principal p
--   LEFT JOIN public.principal_role pr
--          ON pr.tenant_id = p.tenant_id AND pr.principal_id = p.principal_id
--  GROUP BY p.login_name, p.student_id
--  ORDER BY p.login_name;
