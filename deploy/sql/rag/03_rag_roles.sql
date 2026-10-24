-- =====================================================================
-- rag 数据库角色与授权 —— 生产环境初始化用
-- =====================================================================
--
-- 【为什么必须单独成文件】
--   本文件含**集群级**操作（CREATE ROLE），需 CREATEROLE 及以上权限
--   （RDS 上为 alicloud_rds_admin 或具备 CREATEROLE 的账号）。
--   01_rag_ddl.sql 用 --no-owner --no-privileges 导出，**不含任何 GRANT**
--   —— 授权必须落在「应用实际使用的角色」上，不能把开发机的 owner 写死进生产库。
--
-- 【角色形状：必须 NOBYPASSRLS（这是硬性约束，不是建议）】
--   RAG 的全部 10 张表都启用了 RLS，策略形如
--       USING (tenant_id = rag_current_tenant()) WITH CHECK (...)
--   rag_current_tenant() 读会话级 GUC `app.tenant_id`；
--   未设置时返回 NULL → 所有行都不可见（fail-closed，默认拒绝）。
--
--   ⚠️⚠️ **若应用连接角色带 BYPASSRLS，租户隔离形同虚设**：
--     · BYPASSRLS 角色绕过 RLS，FORCE ROW LEVEL SECURITY 也拦不住；
--     · 应用侧 rag-bootstrap 的 RagStartupSelfCheck 会以
--       「连接角色具有 BYPASSRLS（会绕过 RLS，租户隔离失效）」
--       **拒绝启动** —— 那是 RLS 租户隔离的硬性保护，不是可忽略的报错。
--     · ai_agent 是**迁移角色**（可建角色 / 跑迁移 / 灌跨租户夹具），
--       其正确用途不是让应用拿它连库。
--
-- 【⚠️ search_path】
--   RAG 全部对象位于 schema rag，而应用 SQL 用**未限定表名**。
--   应用侧已由 HikariCP connection-init-sql 设置：
--       SET search_path TO rag, public
--   手工连库时必须自行执行同一句，否则未限定名解析不到对象
--   （表现为 relation does not exist）。
--
-- 【幂等】
--   可反复执行：角色存在则重置属性，不存在则创建；
--   **不提供口令时不改口令**（避免误覆盖既有凭据）。
--
-- 【用法】
--   psql -h <host> -p <port> -U <超管> -d <target_db> -v ON_ERROR_STOP=1 \
--        -v rag_password='<rag_app 口令>' \
--        -f 03_rag_roles.sql
--
--   ⚠️ 口令建议由密钥管理注入，**不要**写进本文件或命令行历史。
-- =====================================================================

\set ON_ERROR_STOP on

\if :{?rag_role}
\else
  \set rag_role 'rag_app'
\endif

-- ⚠️ 不提供则不设口令（只调整权限，不动既有凭据）
\if :{?rag_password}
\else
  \set rag_password ''
\endif

\echo '── 角色参数 ──'
\echo '  应用角色 :' :rag_role

-- 把参数传入会话变量，供 DO 块读取
-- ⚠️ psql 变量无法在 DO 块内直接引用（:'x' 是客户端语法），
--    故先用 set_config 放入会话变量，再由 DO 块 current_setting() 取回。
SELECT set_config('my.rag_role', :'rag_role', false),
       set_config('my.rag_password', :'rag_password', false);


-- =====================================================================
-- 1. 创建 / 重置应用角色
-- =====================================================================
-- 幂等要点：CREATE ROLE 是**集群级**对象，DROP DATABASE 不会删除它，
--   直接 CREATE 会因重名失败并连带回滚整段脚本 —— 故先判存在再分支。
DO $$
DECLARE
    role_name text := current_setting('my.rag_role');
    pwd       text := current_setting('my.rag_password');
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
        EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS '
                       'NOCREATEDB NOCREATEROLE INHERIT', role_name);
        RAISE NOTICE '角色 % 已创建（NOSUPERUSER + NOBYPASSRLS）', role_name;
    ELSE
        EXECUTE format('ALTER ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS '
                       'NOCREATEDB NOCREATEROLE INHERIT', role_name);
        RAISE NOTICE '角色 % 已存在，属性已重置', role_name;
    END IF;

    IF pwd IS NOT NULL AND pwd <> '' THEN
        EXECUTE format('ALTER ROLE %I PASSWORD %L', role_name, pwd);
        RAISE NOTICE '角色 % 口令已更新', role_name;
    ELSE
        RAISE NOTICE '未提供 rag_password，跳过 % 的口令设置', role_name;
    END IF;
END
$$;


-- =====================================================================
-- 2. 授权
-- =====================================================================
-- ⚠️ **必须逐条 GRANT**：若整段放在一个事务里，任意一条失败会连带回滚
--    全部 GRANT，表现为「授权数 = 0」却无明显报错。
--
-- ⚠️ schema 授权要点：
--   · USAGE ON SCHEMA rag —— 没有它，未限定名根本解析不到对象；
--   · public schema 也要 USAGE —— vector / pgcrypto 扩展与
--     flyway_schema_history 都在 public；
--   · DEFAULT PRIVILEGES 必须设 —— 否则后续新建的表（如后续迁移）
--     **不会**自动带上授权，应用一访问就报 permission denied，
--     而报错点在业务代码，离「表没授权」的真因极远。

SELECT format('GRANT USAGE ON SCHEMA rag TO %I', :'rag_role') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'rag_role') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA rag TO %I', :'rag_role') \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA rag TO %I', :'rag_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA rag GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'rag_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA rag GRANT USAGE, SELECT ON SEQUENCES TO %I', :'rag_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'rag_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO %I', :'rag_role') \gexec


-- =====================================================================
-- 3. 角色属性校验
-- =====================================================================
-- ⚠️ rolsuper 与 rolbypassrls **必须**都是 f。
--    任一为 t：超级用户/BYPASSRLS 会绕过所有 RLS 策略，
--    FORCE ROW LEVEL SECURITY 也拦不住 —— 用它跑跨租户测试会得到
--    「隔离生效」的**虚假结论**。
\echo ''
\echo '=== 角色属性校验（超级用户/绕过rls 须为 f）==='
SELECT rolname       AS 角色,
       rolsuper      AS 超级用户,
       rolbypassrls  AS 绕过rls,
       rolcreatedb   AS 可建库,
       rolcreaterole AS 可建角色,
       rolcanlogin   AS 可登录
FROM pg_roles
WHERE rolname = :'rag_role';

\echo ''
\echo '=== 已获授权的表（应 = 10 张 rag 表）==='
SELECT table_name,
       string_agg(DISTINCT privilege_type, ', ' ORDER BY privilege_type) AS 权限
FROM information_schema.role_table_grants
WHERE grantee = :'rag_role' AND table_schema = 'rag'
GROUP BY table_name
ORDER BY table_name;

\echo ''
\echo '✅ rag 角色与授权完成。'
\echo '   ⚠️ 下一步：用该角色另开连接验证 RLS（超管/BYPASSRLS 验隔离＝假结论）。'
\echo '   ⚠️ 该角色连接后需 SET search_path TO rag, public（应用侧已自动设置）。'
