-- =====================================================================
-- platform 种子数据 —— 生产环境初始化用
-- =====================================================================
--
-- 【用途】
--   在 platform 空库（已执行 01_platform_ddl.sql）之上建立**最小可运行基线**：
--   一个租户、一条预算账户、（可选）一个管理员账号与角色。
--
-- 【幂等】
--   全部 ON CONFLICT DO NOTHING，**可反复执行**：
--     · 不覆盖已有租户 / 账号 / 预算；
--     · 不改已有账号口令（覆盖＝静默改口令，且绕过失败锁定计数）；
--     · 重复执行不会因唯一约束失败而中断
--       （中断会导致后续语句被静默跳过，出现「表在但数据不全」的假象——
--        本项目已因此误判过「约束全部失效」）。
--
-- 【⚠️ RLS 必须先设租户上下文】
--   principal / principal_role / budget_account 三张表是
--   FORCE ROW LEVEL SECURITY + 策略 tenant_id = current_tenant_id()。
--   current_tenant_id() 读的是会话级 GUC `app.tenant_id`：
--     · 未设置 → 返回 NULL → **所有写入被 WITH CHECK 拒绝**（fail-closed）；
--     · 用 set_config(..., false) 设**会话级**（is_local=false），
--       不能用 local=true —— psql 默认 autocommit，每条语句各自一个事务，
--       local 值在提交后即被丢弃，后续 INSERT 读到 NULL，
--       报 "null value in column tenant_id violates not-null constraint"。
--
-- 【⚠️ 管理员口令不入库】
--   本文件**不含**任何口令哈希，也不提供默认口令。
--   生产凭据必须走受控注入流程（见下方 admin_password_hash 参数）：
--   用 PrincipalRepository.PasswordHasher.hash(<强口令>) 生成哈希，
--   经密钥管理/运维通道下发，再以 psql 变量传入。
--   ⚠️ 严禁把明文口令哈希写进版本库（V14 的 dev 种子已明示该纪律）。
--
-- 【用法】
--   psql -h <host> -p <port> -U <owner> -d <target_db> -v ON_ERROR_STOP=1 \
--        -v tenant_slug='t-prod' \
--        -v tenant_name='某某教育科技' \
--        -v budget_limit='100000.00' \
--        -v admin_login='tenant-admin' \
--        -v admin_password_hash='$pbkdf2-sha256$i=600000$...' \
--        -v admin_roles='TENANT_ADMIN' \
--        -f 02_platform_seed.sql
--
--   未传 admin_password_hash 时**跳过**管理员账号创建（只建租户+预算），
--   并在末尾明确提示——不静默、不猜测。
--
-- 【⚠️ 与应用配置的耦合】
--   建出的 tenant_slug 必须与 platform 应用配置的
--   `AGENT_TENANT_SLUGS`（默认 t-a）**完全一致**：
--   Worker 启动期按 slug 解析租户，slug 不存在即 fail-closed 拒绝启动，
--   症状是「消息静默不发布、Run 永远不动」——
--   这是本项目踩过的「系统表面完全正常但链路已断」形态。
-- =====================================================================

\set ON_ERROR_STOP on

-- ── 可覆盖参数（默认值面向生产，slug 需与 AGENT_TENANT_SLUGS 一致）────────
\if :{?tenant_slug}
\else
  \set tenant_slug 't-prod'
\endif

\if :{?tenant_name}
\else
  \set tenant_name '生产租户'
\endif

\if :{?budget_limit}
\else
  \set budget_limit '100000.00'
\endif

\if :{?budget_currency}
\else
  \set budget_currency 'CNY'
\endif

\if :{?admin_login}
\else
  \set admin_login 'tenant-admin'
\endif

\if :{?admin_display_name}
\else
  \set admin_display_name '租户管理员'
\endif

-- ⚠️ 默认留空 = 不建管理员账号（生产凭据须受控注入，不落版本库）
\if :{?admin_password_hash}
\else
  \set admin_password_hash ''
\endif

\if :{?admin_roles}
\else
  \set admin_roles 'TENANT_ADMIN'
\endif

-- ⚠️ **实测结论（2026-10-23 远程 PG 18.6 实跑）**：psql 的 \if **不支持**
--    字符串比较。写作 `\if :'admin_password_hash' = ''` 会报
--      error: unrecognized value "'' = " for "\if expression": Boolean expected
--    且 psql **不中断**，而是**静默走 else 分支**——
--    即「判断条件写错」表现为「分支走反」，极难察觉。
--    故此处一律用 \gset 取布尔值再判断（已实测：\if :has_hash 正确取 t/f）。
SELECT (length(:'admin_password_hash') > 0) AS has_admin_password \gset

-- 可选：是否同时建一个基线 Agent（版本 1 + 执行快照）
\if :{?include_agent_baseline}
\else
  \set include_agent_baseline false
\endif

\if :{?baseline_agent_name}
\else
  \set baseline_agent_name 'agent1'
\endif

\echo '── platform 种子参数 ──'
\echo '  租户 slug      :' :tenant_slug
\echo '  租户名称       :' :tenant_name
\echo '  预算上限       :' :budget_limit :budget_currency
\echo '  管理员登录名   :' :admin_login
\echo '  管理员角色     :' :admin_roles
\echo '  管理员口令哈希 : （未提供 → 不创建账号）'
\echo '  基线 Agent     :' :include_agent_baseline


-- =====================================================================
-- 1. 租户
-- =====================================================================
-- ⚠️ tenant 是**全局表，不启用 RLS**（租户自身即隔离单元），
--    故此处无需设置 app.tenant_id。
--    slug 约束：^[a-z0-9][a-z0-9-]{1,62}$
BEGIN;

INSERT INTO public.tenant (tenant_id, slug, name, status)
VALUES (gen_random_uuid(), :'tenant_slug', :'tenant_name', 'ACTIVE')
ON CONFLICT (slug) DO NOTHING;

COMMIT;


-- =====================================================================
-- 2. 设置租户上下文（会话级）
-- =====================================================================
-- ⚠️ 必须在事务**外**用会话级设置：若把 set_config 放进事务并提交，
--    local 值随即失效，后续语句读不到租户。
--    这里用 false（会话级）并在本文件末尾复位。
SELECT set_config('app.tenant_id',
                  (SELECT tenant_id::text FROM public.tenant WHERE slug = :'tenant_slug'),
                  false);


-- =====================================================================
-- 3. 预算账户
-- =====================================================================
-- ⚠️ 工具调用会先做预算预留（agent.budget.reserve-per-tool-call），
--    预算账户缺失 → 预留失败 → 工具调用被拒。
--    DB 层最后防线是 budget_no_overspend CHECK
--    （reserved_amount + settled_amount <= total_limit）。
--    唯一键：budget_account_unique (tenant_id, currency)
BEGIN;

INSERT INTO public.budget_account
    (tenant_id, currency, total_limit, reserved_amount, settled_amount)
SELECT t.tenant_id, :'budget_currency', :'budget_limit'::numeric, 0, 0
FROM public.tenant t
WHERE t.slug = :'tenant_slug'
ON CONFLICT (tenant_id, currency) DO NOTHING;

COMMIT;


-- =====================================================================
-- 4. 管理员账号 + 角色
-- =====================================================================
-- ⚠️ login_name 是**全局唯一**（uq_principal_login_name）：
--    跨租户撞名会直接失败——这是防撞号的设计，不是缺陷。
--    角色约束：^[A-Z][A-Z_]{1,31}$（principal_role_not_blank）
\if :has_admin_password

BEGIN;

INSERT INTO public.principal
    (tenant_id, login_name, password_hash, display_name, status)
SELECT t.tenant_id, :'admin_login', :'admin_password_hash', :'admin_display_name', 'ACTIVE'
FROM public.tenant t
WHERE t.slug = :'tenant_slug'
ON CONFLICT (login_name) DO NOTHING;

INSERT INTO public.principal_role (tenant_id, principal_id, role)
SELECT p.tenant_id, p.principal_id, btrim(r.role)
FROM public.principal p
CROSS JOIN LATERAL unnest(string_to_array(:'admin_roles', ',')) AS r(role)
WHERE p.login_name = :'admin_login'
ON CONFLICT (tenant_id, principal_id, role) DO NOTHING;

COMMIT;

\else

\echo '⚠️ 未提供 admin_password_hash —— 跳过管理员账号创建。'
\echo '   租户与预算已就绪；管理员请走受控凭据注入流程后单独创建。'

\endif


-- =====================================================================
-- 5. 可选：基线 Agent（版本 1 + 执行快照）
-- =====================================================================
-- 说明：Agent 属**业务数据**，生产通常由运营在控制台创建。
--      这里提供一份最小基线，便于冒烟验证「Run 能起来」。
--      run_config 内的限额须与平台配置对齐（agent.run.* / agent.budget.*）：
--        maxCostPerRun  ≤ agent.run.max-cost-per-run
--        maxLlmCalls    ≤ agent.run.max-llm-calls
--        maxSteps       ≤ agent.run.max-steps
--      超出时执行期会 fail-closed 拒绝。
\if :include_agent_baseline

BEGIN;

INSERT INTO public.agent (tenant_id, name, description)
SELECT t.tenant_id, :'baseline_agent_name', '生产初始化基线 Agent'
FROM public.tenant t
WHERE t.slug = :'tenant_slug'
ON CONFLICT (tenant_id, name) DO NOTHING;

INSERT INTO public.agent_version (tenant_id, agent_id, version_number, definition)
SELECT a.tenant_id, a.agent_id, 1, '{"kind":"tutor"}'::jsonb
FROM public.agent a
JOIN public.tenant t ON t.tenant_id = a.tenant_id
WHERE t.slug = :'tenant_slug' AND a.name = :'baseline_agent_name'
ON CONFLICT (tenant_id, agent_id, version_number) DO NOTHING;

INSERT INTO public.execution_snapshot
    (tenant_id, version_id, agent_config, prompt_config, tool_contracts, run_config)
SELECT v.tenant_id, v.version_id, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb,
       jsonb_build_object(
           'maxCostPerRun', '2.00',
           'maxLlmCalls', 12,
           'maxSteps', 20,
           'approvalRequired', 'OPTIONAL')
FROM public.agent_version v
JOIN public.agent a ON a.agent_id = v.agent_id
JOIN public.tenant t ON t.tenant_id = a.tenant_id
WHERE t.slug = :'tenant_slug' AND a.name = :'baseline_agent_name'
  AND v.version_number = 1
  -- ⚠️ **幂等关键（实测修正）**：execution_snapshot 的唯一键是
  --    (tenant_id, snapshot_id)，而 snapshot_id 是**每次新生成的 UUID**，
  --    故单靠 ON CONFLICT DO NOTHING **拦不住重复插入**——
  --    重复执行会静默多插一份快照（实测：执行 3 次得到 2 份快照）。
  --    必须显式排除「该版本已有快照」。
  AND NOT EXISTS (
      SELECT 1 FROM public.execution_snapshot s
       WHERE s.tenant_id = v.tenant_id AND s.version_id = v.version_id
  )
ON CONFLICT DO NOTHING;

COMMIT;

\endif


-- =====================================================================
-- 6. 复位租户上下文 —— 放在**校验之后**
-- =====================================================================
-- ⚠️ **顺序说明（实测发现并修正）**：本节原置于校验之前，
--    复位后 app.tenant_id 为空 → current_tenant_id() 返回 NULL →
--    校验块以**非特权角色**（app_agent）读 budget_account 时被 RLS 挡住，
--    读到 0 行 → 误报「缺少预算账户」并 RAISE EXCEPTION。
--    即「校验自己把上下文清了」造成假失败。
--    故此处改为：校验块**带着**租户上下文读 RLS 表，校验完再复位。
--    复位的意义仍然成立：不让残留的 app.tenant_id 泄漏到后续脚本，
--    避免后续语句在错误租户下写入（症状离真因极远）。

-- 为校验块重新设置租户上下文（非特权角色读 RLS 表所必需）
SELECT set_config('app.tenant_id',
                  (SELECT tenant_id::text FROM public.tenant WHERE slug = :'tenant_slug'),
                  false);

-- ⚠️ **实测结论**：psql 的变量替换**不发生在美元引号（$$）字符串内部**，
--    故 DO 块里写 :'tenant_slug' 不会被替换成字面量。
--    惯用解法是把值放进会话 GUC，再在块内 current_setting() 取回。
SELECT set_config('seed.tenant_slug', :'tenant_slug', false);
SELECT set_config('seed.admin_login', :'admin_login', false);

DO $$
DECLARE
    v_tenant   uuid;
    v_budget   int;
    v_principal int;
    v_roles    int;
BEGIN
    SELECT tenant_id INTO v_tenant FROM public.tenant WHERE slug = current_setting('seed.tenant_slug', TRUE);
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION '种子失败：租户 % 不存在', current_setting('seed.tenant_slug', TRUE);
    END IF;

    SELECT count(*) INTO v_budget FROM public.budget_account WHERE tenant_id = v_tenant;
    IF v_budget = 0 THEN
        RAISE EXCEPTION '种子失败：租户 % 缺少预算账户（工具调用会被预算预留拒绝）', v_tenant;
    END IF;

    -- 管理员账号是可选的（凭据可后注入），故只做存在性提示，不报错
    SELECT count(*) INTO v_principal FROM public.principal WHERE login_name = current_setting('seed.admin_login', TRUE);
    IF v_principal = 0 THEN
        RAISE NOTICE '提示：管理员账号 % 尚未创建（未提供口令哈希时属预期）', current_setting('seed.admin_login', TRUE);
    ELSE
        SELECT count(*) INTO v_roles
          FROM public.principal_role pr
          JOIN public.principal p USING (principal_id)
         WHERE p.login_name = current_setting('seed.admin_login', TRUE);
        IF v_roles = 0 THEN
            RAISE EXCEPTION '种子不完整：账号 % 已存在但**没有**角色，无法登录后操作', current_setting('seed.admin_login', TRUE);
        END IF;
    END IF;

    RAISE NOTICE '✅ platform 种子校验通过：租户=1，预算账户=%，管理员账号=%', v_budget, v_principal;
END;
$$;


-- =====================================================================
-- 7. 复位租户上下文（校验之后）
-- =====================================================================
SELECT set_config('app.tenant_id', '', false);
