-- =====================================================================
-- PLATFORM_ADMIN 带外授权 —— 平台/运维级治理角色授予模板
-- =====================================================================
--
-- 【定位（P5 · 建应用层 PLATFORM_ADMIN 授权通道的落地件）】
--   PLATFORM_ADMIN 是平台/运维级治理角色，**默认不授予任何主体**：
--     · 应用层权限矩阵（RoleBasedAuthorizationService）只登记映射、不默认授予；
--     · JWT known-roles 白名单只允许签发、不自动赋权；
--     · 真正的赋权落点就是本脚本 —— 直接写 principal_role 权威表。
--
-- 【为什么走 SQL 而不是应用端点】
--   principal_role 是「防止令牌自封管理员」的权威来源（DDL 注释原文）。
--   若提供应用层赋权端点，则「谁能赋权」必然落在某个已有角色身上，
--   形成自举回路：任一 TENANT_ADMIN 只要拿到赋权入口即可自授 PLATFORM_ADMIN。
--   带外 SQL 通道让「赋权者」必须是**能直连生产库的人**——
--   与「平台级对象变更走带外运维通道」的 P0.5 红线同构。
--
-- 【前置】
--   · 以能绕过 RLS 的账号执行（platform_ops 或超管；RLS 下本表对 app_agent
--     仅放行本租户行，跨租户授予必须带外执行）。
--   · 应用侧无需重启：运行时授权以 principal_role 现查为准，
--     主体重新登录换取含新角色的令牌后即生效（authzVersion 随撤权递增，授予不递增）。
--
-- 【幂等】
--   重复执行只命中 ON CONFLICT DO NOTHING，不产生重复行。
--
-- 【用法】
--   psql -h <host> -p <port> -U <platform_ops> -d <target_db> -v ON_ERROR_STOP=1 \
--        -v tenant_id='<租户 UUID>' \
--        -v principal_id='<主体 UUID>' \
--        -f 04_platform_admin_grant.sql
--
-- 【回收】
--   本文件末尾附 revoke 段（注释态）：按需手工解注释执行。
-- =====================================================================

\set ON_ERROR_STOP on

\if :{?tenant_id}
\else
  \echo '❌ 缺少 -v tenant_id=<租户 UUID>'
  \quit
\endif

\if :{?principal_id}
\else
  \echo '❌ 缺少 -v principal_id=<主体 UUID>'
  \quit
\endif

SELECT set_config('my.tenant_id',   :'tenant_id',   false),
       set_config('my.principal_id', :'principal_id', false);

\echo '── 授予前快照：主体与既有角色 ──'
SELECT p.principal_id,
       p.login_name,
       p.status,
       p.authz_version,
       coalesce(string_agg(r.role, ', ' ORDER BY r.role), '（无角色）') AS 既有角色
FROM public.principal p
LEFT JOIN public.principal_role r
       ON r.tenant_id = p.tenant_id AND r.principal_id = p.principal_id
WHERE p.tenant_id = current_setting('my.tenant_id')::uuid
  AND p.principal_id = current_setting('my.principal_id')::uuid
GROUP BY p.principal_id, p.login_name, p.status, p.authz_version;

-- =====================================================================
-- 1. 授予（幂等）
-- =====================================================================
-- ⚠️ 前置断言先于写入：主体不存在时 INSERT 会被外键拒绝，
--    但先查一遍能把「租户/主体拼错」与「角色已存在」区分成可读的错误。
DO $$
DECLARE
    v_count integer;
BEGIN
    SELECT count(*) INTO v_count
    FROM public.principal
    WHERE tenant_id = current_setting('my.tenant_id')::uuid
      AND principal_id = current_setting('my.principal_id')::uuid;
    IF v_count = 0 THEN
        RAISE EXCEPTION '主体不存在：tenant=% principal=%（请核对 UUID，勿用 slug/login_name）',
            current_setting('my.tenant_id'), current_setting('my.principal_id');
    END IF;
END
$$;

INSERT INTO public.principal_role (tenant_id, principal_id, role)
VALUES (current_setting('my.tenant_id')::uuid,
        current_setting('my.principal_id')::uuid,
        'PLATFORM_ADMIN')
ON CONFLICT DO NOTHING;

-- =====================================================================
-- 2. 授予后校验
-- =====================================================================
\echo ''
\echo '=== 授予后快照（应含 PLATFORM_ADMIN）==='
SELECT p.login_name,
       r.role,
       p.authz_version,
       p.status
FROM public.principal p
JOIN public.principal_role r
     ON r.tenant_id = p.tenant_id AND r.principal_id = p.principal_id
WHERE p.tenant_id = current_setting('my.tenant_id')::uuid
  AND p.principal_id = current_setting('my.principal_id')::uuid
ORDER BY r.role;

\echo ''
\echo '=== 生效链路提醒 ==='
\echo '1) 主体重新登录 → 新令牌携带 PLATFORM_ADMIN（known-roles 已登记）；'
\echo '2) 运行时授权以 principal_role 现查为准，旧令牌中的角色声明不构成越权依据；'
\echo '3) 平台级端点（/api/admin/tenants* 等）从默认 403 转为可用；'
\echo '4) 请在变更台账登记：谁、何时、为什么获得平台级权限（本脚本执行即留痕于 psql 会话）。'

-- =====================================================================
-- 3. 回收（默认注释态：需要时解注释，替换 psql 变量为实际值）
-- =====================================================================
-- ⚠️ 回收后：authzVersion 递增逻辑（应用侧撤权）与本次 DELETE 是两条通道；
--    直接 DELETE 后，已签发令牌中的角色声明在校验时会被 principal_role 现查否决。
--
-- DELETE FROM public.principal_role
-- WHERE tenant_id  = '<租户 UUID>'::uuid
--   AND principal_id = '<主体 UUID>'::uuid
--   AND role = 'PLATFORM_ADMIN';
