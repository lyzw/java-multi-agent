-- =====================================================================
-- 应用数据库角色管理脚本（新增 / 更新）
--
-- 用途：为应用创建或更新数据库角色，授予最小必要权限。
--   · 新增角色：不存在时创建
--   · 更新角色：已存在时重置密码与权限
--   · 重复执行安全（幂等）
--
-- ⚠️ 为什么必须有这个脚本（架构要求，非可选）：
--   验证 RLS 是否生效，**必须使用非特权角色**。PostgreSQL 超级用户与
--   BYPASSRLS 角色会绕过所有 RLS 策略，且 FORCE ROW LEVEL SECURITY
--   也拦不住。用超级用户跑跨租户测试会得到「隔离生效」的虚假结论。
--
-- 用法：
--   psql -U <超管> -d <库> -v app_role=app_agent2 -v app_password='...' \
--        -f V__manage_app_role.sql
--
-- 不提供 app_password 时只调整权限、不改密码（避免误覆盖已有凭据）。
--
-- 实现说明：psql 变量无法在 DO 块内直接引用（`:'x'` 是客户端语法），
-- 因此先用 set_config 把参数放入会话变量，再由 DO 块读取。
-- =====================================================================

\echo '=== 应用数据库角色管理 ==='

\if :{?app_role}
\else
    \set app_role 'app_agent2'
\endif

\if :{?app_password}
\else
    \set app_password ''
\endif

\echo '目标角色:' :app_role

-- 把参数传入会话变量，供 DO 块读取
SELECT set_config('my.app_role',   :'app_role',   false),
       set_config('my.app_password', :'app_password', false);

-- ---------------------------------------------------------------------
-- 1. 新增 / 更新角色
--
-- 关键属性：
--   NOSUPERUSER / NOBYPASSRLS  —— 否则绕过所有 RLS 策略，无法用于验证隔离
--   NOCREATEDB / NOCREATEROLE  —— 缩小攻击面
--   LOGIN                      —— 允许建立连接
--
-- 用 DO 块实现幂等：CREATE ROLE 是集群级对象，DROP DATABASE 不会删除它，
-- 直接 CREATE 会因重名失败并连带回滚整段脚本。
-- ---------------------------------------------------------------------
DO $$
DECLARE
    role_name text := current_setting('my.app_role');
    pwd       text := current_setting('my.app_password');
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
        EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS '
                       'NOCREATEDB NOCREATEROLE INHERIT', role_name);
        RAISE NOTICE '角色 % 已创建', role_name;
    ELSE
        EXECUTE format('ALTER ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS '
                       'NOCREATEDB NOCREATEROLE INHERIT', role_name);
        RAISE NOTICE '角色 % 已存在，属性已重置', role_name;
    END IF;

    IF pwd IS NOT NULL AND pwd <> '' THEN
        EXECUTE format('ALTER ROLE %I PASSWORD %L', role_name, pwd);
        RAISE NOTICE '角色 % 密码已更新', role_name;
    ELSE
        RAISE NOTICE '未提供 app_password，跳过密码设置';
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- 2. 授予最小必要权限
--
-- 不授予任何角色成员关系，不授予 BYPASSRLS，不授予超级用户权限。
-- ---------------------------------------------------------------------
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'app_role') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', :'app_role') \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', :'app_role') \gexec

-- DEFAULT 权限：确保后续 Flyway 新建的表也自动带授权，
-- 否则新增表会因缺权限导致集成测试在不知情的情况下失败。
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA PUBLIC GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'app_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA PUBLIC GRANT USAGE, SELECT ON SEQUENCES TO %I', :'app_role') \gexec

-- ---------------------------------------------------------------------
-- 3. 结果校验
--
-- 必须确认 rolsuper 与 rolbypassrls 均为 false —— 任一为 true，
-- 该角色都无法用于验证 RLS。
-- ---------------------------------------------------------------------
\echo ''
\echo '=== 角色属性校验（超级用户/绕过RLS 须为 f）==='
SELECT rolname       AS 角色,
       rolsuper      AS 超级用户,
       rolbypassrls  AS 绕过rls,
       rolcreatedb   AS 可建库,
       rolcanlogin   AS 可登录
FROM pg_roles
WHERE rolname = :'app_role';

\echo ''
\echo '=== 已授予的表权限（前 10 张）==='
SELECT table_name,
       string_agg(DISTINCT privilege_type, ', ' ORDER BY privilege_type) AS 权限
FROM information_schema.role_table_grants
WHERE grantee = :'app_role' AND table_schema = 'public'
GROUP BY table_name
ORDER BY table_name
LIMIT 10;

\echo ''
\echo '=== 下一步：设置环境变量后运行集成测试 ==='
\echo '  export IT_PG_URL="jdbc:postgresql://127.0.0.1:5432/<数据库名>"'
\echo '  export IT_PG_USER="' :app_role '"'
\echo '  export IT_PG_PASSWORD="<你设置的密码>"'
\echo '  mvn -pl agent-infrastructure test -Dtest=TenantSessionBinderIT'