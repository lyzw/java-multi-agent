-- =====================================================================
-- V14 · 种子一个 Agent 管理员账号（TENANT_ADMIN）
--
-- 用途：为开发/验证环境（租户 t-a）预置一个具备完整管理权限的账号，
--       用于登录平台、管理 Agent、审批配置变更等冒烟测试。
--
-- ⚠️ 这是**种子账号**，仅用于 dev / 验证环境（tenant slug = t-a）。
--    生产环境切勿把明文口令哈希提交进版本库；
--    生产请走独立的、不入库的凭据注入流程（见文件末尾说明）。
--
-- 幂等性：
--   · principal 已存在（按 login_name 全局唯一判断）则不覆盖、不改密；
--   · principal_role 已存在则不重复插入。
--   因此本迁移可安全重复执行（Flyway 仅首次跑，重跑不会因唯一约束中断）。
--
-- RLS 注意：
--   principal / principal_role 是 FORCE ROW LEVEL SECURITY 表，
--   插入前必须设置 session 级租户上下文 app.tenant_id，否则 WITH CHECK 失败。
--   （用 session 级而非 local 级，详见步骤 1 注释。）
-- =====================================================================

-- ⚠️⚠️ **空库上必须可重放**（2026-10-23 实测修复）：
--    本迁移依赖租户 slug = 't-a'，而 't-a' 只由
--    `migration/verify/V1_seed.sql` 创建 —— 那一步在 CI 与本地脚本里都
--    **排在迁移之后**。故在全新库上跑迁移时，'t-a' 尚不存在：
--      · set_config 写入 NULL → principal.tenant_id 违反 NOT NULL → **迁移报错**；
--      · CI 的 `psql -v ON_ERROR_STOP=1` 会让整个 integration job **必红**；
--      · 本地 `dev-it-db.sh` 用 `|| 继续` 吞掉错误 → 迁移「看似成功」，
--        实则 agent-admin 根本没种上（静默跳过）。
--    故此处改为：**'t-a' 不存在就整段跳过并 RAISE NOTICE**，
--    既不报错（空库可重放），也不静默（留下可查的痕迹）。
--    't-a' 存在时的行为与原先完全一致。
DO $$
DECLARE
    v_tenant UUID;
BEGIN
    SELECT tenant_id INTO v_tenant FROM tenant WHERE slug = 't-a';

    IF v_tenant IS NULL THEN
        RAISE NOTICE 'V14: 租户 t-a 不存在，跳过 agent-admin 种子'
                     '（预期行为：种子迁移依赖 dev 租户，种子由 verify/V1_seed.sql 提供）';
        RETURN;
    END IF;

    -- 1) 设置租户上下文
    -- ⚠️ 必须用 session 级（is_local = false），不能用 local（true）：
    --    psql 默认 autocommit，每条语句各自一个事务；若用 local=true，
    --    set_config 所在事务提交后该值即被丢弃，后续 INSERT 读到 NULL
    --    → 触发 "null value in column tenant_id violates not-null constraint"。
    --    session 级在 psql 手动执行与 Flyway（单事务）下均能正确传递。
    PERFORM set_config('app.tenant_id', v_tenant::text, false);

    -- 2) 插入管理员账号（若 login_name 已存在则跳过，避免覆盖真实账号/口令）
    INSERT INTO principal (tenant_id, login_name, password_hash, display_name, status)
    SELECT current_tenant_id(),
           'agent-admin',
           '$pbkdf2-sha256$i=600000$8gJHUVocOBBJ1EC5akzhIw$ggu5RsO//NnYwgqz4ZFFs899LX/IvP+qPTuKUuVB6xc',
           'Agent Admin',
           'ACTIVE'
    WHERE NOT EXISTS (
        SELECT 1 FROM principal WHERE login_name = 'agent-admin'
    )
    ON CONFLICT (login_name) DO NOTHING;

    -- 3) 授予 TENANT_ADMIN 角色（若已授予则跳过）
    INSERT INTO principal_role (tenant_id, principal_id, role)
    SELECT current_tenant_id(), p.principal_id, 'TENANT_ADMIN'
    FROM principal p
    WHERE p.login_name = 'agent-admin'
      AND NOT EXISTS (
            SELECT 1
            FROM principal_role pr
            WHERE pr.tenant_id    = current_tenant_id()
              AND pr.principal_id = p.principal_id
              AND pr.role         = 'TENANT_ADMIN'
      );

    -- 4) 复位租户上下文（session 级，与上面保持一致，确保真正清掉），避免泄漏到后续语句
    PERFORM set_config('app.tenant_id', '', false);
END;
$$;

-- =====================================================================
-- 校验（可选，人工跑迁移后确认）
--   SELECT p.login_name, p.status, pr.role
--   FROM principal p
--   JOIN principal_role pr USING (principal_id)
--   WHERE p.login_name = 'agent-admin';
--
-- 登录凭据（dev 种子，请妥善保存，本文件不会回显明文到日志以外的地方）：
--   login_name : agent-admin
--   password   : IqlnNH#1uX!qZDI@5Gj1Xw
--
-- 生产环境凭据注入（不入库、不提交）：
--   用 PasswordHasher.hash(<你的强口令>) 生成哈希后，
--   通过 flyway 的占位符 / 单独的受控脚本写入，或经运维通道下发。
-- =====================================================================
