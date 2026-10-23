-- =====================================================================
-- 平台运维角色（BYPASSRLS）管理脚本（新增 / 更新）
--
-- 用途：为**平台级配置发布**创建或更新数据库角色。
--
-- ⚠️ 为什么需要这个角色（架构要求，非可选）：
--   cap_config_release / cap_config_pointer / cap_decision_log 的
--   **写策略只放行「本租户的 TENANT 行」**（V15 / V17 注释）。
--   PLATFORM 行（平台级发布头与全局当前指针）**必须**由平台运维角色写入 ——
--   否则任一租户都能单方面替换全平台生效配置，那是直接的越权。
--
-- ⚠️ 角色形状（与 V__manage_app_role.sql 的应用角色**刻意不同**）：
--   · NOSUPERUSER   —— 超级用户绕过的是一切，权限过大；
--   · **BYPASSRLS**  —— 这正是本角色的**唯一**特权，也是它存在的全部理由；
--   · NOCREATEDB / NOCREATEROLE —— 缩小攻击面；
--   · LOGIN / INHERIT。
--
--   ⚠️ **不要给 SUPERUSER**：超级用户会绕过本应被检验的策略，
--     使「运维角色只写平台级行」这条约定失去验证手段。
--
-- ⚠️ 与应用角色的关系：
--   本角色与 app_agent 是**两个独立角色、两套凭据、两条发布通道**：
--     · app_agent  → 租户作用域发布（TENANT），受 RLS 约束；
--     · platform_ops → 平台作用域发布（PLATFORM），绕过 RLS。
--   混用会让「谁是平台运维」这件事在数据库层失去区分。
--
-- 用法：
--   psql -U <超管> -d <库> -v ops_role=platform_ops -v ops_password='...' \
--        -f V__manage_platform_ops_role.sql
--
-- ⚠️ 不提供 ops_password 时只调整权限、不改密码（避免误覆盖既有凭据）。
--
-- ⚠️ 执行完成后，需在应用侧配置（见文末提示）：
--   AGENT_PLATFORM_OPS_ENABLED=true
--   AGENT_PLATFORM_OPS_DATASOURCE_URL=jdbc:postgresql://<host>:<port>/<db>
--   AGENT_PLATFORM_OPS_DATASOURCE_USERNAME=<ops_role>
--   AGENT_PLATFORM_OPS_DATASOURCE_PASSWORD=<ops_password>
-- =====================================================================

\echo '=== 平台运维角色（BYPASSRLS）管理 ==='

\if :{?ops_role}
\else
    \set ops_role 'platform_ops'
\endif

\if :{?ops_password}
\else
    \set ops_password ''
\endif

\echo '目标角色:' :ops_role

-- 把参数传入会话变量，供 DO 块读取
SELECT set_config('my.ops_role',   :'ops_role',   false),
       set_config('my.ops_password', :'ops_password', false);

-- ---------------------------------------------------------------------
-- 1. 新增 / 更新角色
--
-- 关键属性：LOGIN NOSUPERUSER **BYPASSRLS** NOCREATEDB NOCREATEROLE INHERIT
--
-- 用 DO 块实现幂等：CREATE ROLE 是集群级对象，DROP DATABASE 不会删除它，
-- 直接 CREATE 会因重名失败并连带回滚整段脚本。
-- ---------------------------------------------------------------------
DO $$
DECLARE
    role_name text := current_setting('my.ops_role');
    pwd       text := current_setting('my.ops_password');
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
        EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER BYPASSRLS '
                       'NOCREATEDB NOCREATEROLE INHERIT', role_name);
        RAISE NOTICE '角色 % 已创建（NOSUPERUSER + BYPASSRLS）', role_name;
    ELSE
        EXECUTE format('ALTER ROLE %I LOGIN NOSUPERUSER BYPASSRLS '
                       'NOCREATEDB NOCREATEROLE INHERIT', role_name);
        RAISE NOTICE '角色 % 已存在，属性已重置（NOSUPERUSER + BYPASSRLS）', role_name;
    END IF;

    IF pwd IS NOT NULL AND pwd <> '' THEN
        EXECUTE format('ALTER ROLE %I PASSWORD %L', role_name, pwd);
        RAISE NOTICE '角色 % 密码已更新', role_name;
    ELSE
        RAISE NOTICE '未提供 ops_password，跳过密码设置';
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- 2. 授予最小必要权限
--
-- ⚠️ 与应用角色同构的表/序列授权：**只授"能写平台级行"所需的表级权限**，
--    不授任何角色成员关系、不授 SUPERUSER。
--
-- ⚠️ RLS 是**行级**隔离，绕不绕得掉取决于**连接用的角色**，
--    而不是表级授权 —— 因此本段授权是必需的（BYPASSRLS 角色同样需要
--    表级 INSERT/UPDATE 权限才能写行），但它**不是**隔离本身。
-- ---------------------------------------------------------------------
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'ops_role') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', :'ops_role') \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', :'ops_role') \gexec

-- DEFAULT 权限：确保后续新建表也自动带授权，
-- 否则新增表会因缺权限导致平台级发布在不知情的情况下失败。
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA PUBLIC GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'ops_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA PUBLIC GRANT USAGE, SELECT ON SEQUENCES TO %I', :'ops_role') \gexec

-- ---------------------------------------------------------------------
-- 3. 结果校验
--
-- ⚠️ 必须确认 rolbypassrls = t（这是本角色的全部意义）
--    且 rolsuper = f（超级用户会绕过本应被检验的一切）。
-- ---------------------------------------------------------------------
\echo ''
\echo '=== 角色属性校验（绕过rls 须为 t，超级用户 须为 f）==='
SELECT rolname       AS 角色,
       rolsuper      AS 超级用户,
       rolbypassrls  AS 绕过rls,
       rolcreatedb   AS 可建库,
       rolcanlogin   AS 可登录
FROM pg_roles
WHERE rolname = :'ops_role';

\echo ''
\echo '=== 已授予的表权限（前 10 张）==='
SELECT table_name,
       string_agg(DISTINCT privilege_type, ', ' ORDER BY privilege_type) AS 权限
FROM information_schema.role_table_grants
WHERE grantee = :'ops_role' AND table_schema = 'public'
GROUP BY table_name
ORDER BY table_name
LIMIT 10;

-- ---------------------------------------------------------------------
-- 4. 应用侧配置提示
-- ---------------------------------------------------------------------
\echo ''
\echo '=== 下一步：配置应用侧的运维通道 ==='
\echo '  export AGENT_PLATFORM_OPS_ENABLED=true'
\echo '  export AGENT_PLATFORM_OPS_DATASOURCE_URL="jdbc:postgresql://<host>:<port>/<db>"'
\echo '  export AGENT_PLATFORM_OPS_DATASOURCE_PASSWORD="<你设置的密码>"'
-- ⚠️ 用户名用 SELECT 输出而不是 \echo：
--    \echo 会把多个参数用空格拼接，结果是引号里多出前后空格，
--    运维照抄会得到一个带空格的**不存在的用户名**（且报错点离真因极远）。
SELECT '  export AGENT_PLATFORM_OPS_DATASOURCE_USERNAME=' || :'ops_role' AS "下一步";
\echo ''
\echo '⚠️ 未启用时平台级发布会**明确失败**（PlatformOpsUnavailableException），'
\echo '   不会静默回落到应用角色 —— 应用角色写平台级行会被 RLS 拒绝。'
