-- =====================================================================
-- rag_ops 数据库角色与授权 —— 平台全局语料治理通道（RAG-D11 §1.3 / 第二刀）
-- =====================================================================
--
-- 【这条通道为什么必须存在】
--   `rag_source_write` 等策略形如 `tenant_id = rag_current_tenant() AND scope='TENANT'`：
--   应用角色 **rag_app 按设计写不出全局行**，而那正是越权闸门本身（反证 P4 钉着它）。
--   所以「平台能在控制台维护全局语料」不能靠放宽 rag_app ——
--   放宽它等于给**每一个租户**都发了一条够得着全局桶的写路径。
--   唯一正确的形状是：**独立角色 + 独立连接池**，把这条能力物理限长在一个入口上。
--
-- 【为什么这里是 BYPASSRLS 而 rag_app 严禁】
--   全局桶的行谓词就是「tenant_id = 哨兵」，而 RLS 策略里拿不到这个判据
--   （rag_current_tenant() 撞哨兵直接 RAISE 42501 —— 那是封堵越权企图的设计，不能绕）。
--   因此 ops 通道只能靠「不走策略」来写全局行：BYPASSRLS。
--
--   ⚠️⚠️ 代价说清楚：BYPASSRLS 一旦拿到手，**数据库层不再限制它写哪个桶**，
--     限长完全由应用侧三道结构约束承担：
--       ① 会话窗外取不到连接（GuardedRagOpsDataSource）；
--       ② ops 事务入口不接收租户参数，哨兵桶由实现内部绑定（写不出别的桶，
--          不是「有入口但被 if 拦住」）；
--       ③ A 档窗口只发 SELECT。
--     外加本文件给的**最小授权**（无 DELETE）与库内 CHECK 约束
--     （`scope='PLATFORM' ⇔ tenant_id = 哨兵` —— CHECK 不是策略，BYPASSRLS 绕不掉它）。
--   正因为第 ② 条是承重墙，**本角色不得用于任何其它用途**：
--     不给 rag_app 加成员关系、不给 DELETE、不给 schema 建对象权。
--
-- 【为什么授权比 rag_app 窄】
--   · SELECT 全库：读不是风险，而漏授权会让报错点落在业务代码、离真因极远；
--   · INSERT/UPDATE 只给全局治理真正要写的 6 张表；
--   · **一张表都不给 DELETE**：语料治理没有删除语义（撤回是终态流转，不是删行）；
--   · DEFAULT PRIVILEGES 只给 SELECT：将来新增的表**不会**静默变成可全局写。
--
-- 【幂等】
--   可反复执行：角色存在则重置属性，不存在则创建；不提供口令时不改口令。
--
-- 【用法】
--   /opt/homebrew/opt/libpq/bin/psql -h <rds-host> -U <库 owner> -d study_rag \
--        -v ON_ERROR_STOP=1 -v rag_ops_password='<新口令>' -f 05_rag_ops_role.sql
--   执行完用 scripts/check-rag-ops-role.sh 复核（它把「能执行」变成一条退出码）。
--
--   ⚠️ 口令由密钥管理注入，不要写进本文件或命令行历史（本机走 -v 读环境变量）。
-- =====================================================================

\set ON_ERROR_STOP on

\if :{?rag_ops_role}
\else
  \set rag_ops_role 'rag_ops'
\endif

-- ⚠️ 不提供则不设口令（只调整权限，不动既有凭据）
\if :{?rag_ops_password}
\else
  \set rag_ops_password ''
\endif

\echo '── 角色参数 ──'
\echo '  运维角色 :' :rag_ops_role

-- psql 变量无法在 DO 块内直接引用（:'x' 是客户端语法），先放进会话变量。
-- ⚠️ 结果集必须丢弃（`\g /dev/null`）：set_config 会**原样回显它写入的值**，
--    也就是把口令打进日志。03_rag_roles.sql 的同款写法有这个既有缺陷，一并修掉。
SELECT set_config('my.rag_ops_role', :'rag_ops_role', false),
       set_config('my.rag_ops_password', :'rag_ops_password', false) \g /dev/null


-- =====================================================================
-- 1. 创建 / 重置运维角色（BYPASSRLS 是本通道的存在理由，但必须 NOBYPASS 之外的全关）
-- =====================================================================
DO $$
DECLARE
    role_name text := current_setting('my.rag_ops_role');
    pwd       text := current_setting('my.rag_ops_password');
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
        EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER BYPASSRLS '
                       'NOCREATEDB NOCREATEROLE NOINHERIT', role_name);
        RAISE NOTICE '角色 % 已创建（NOSUPERUSER + BYPASSRLS + NOINHERIT）', role_name;
    ELSE
        EXECUTE format('ALTER ROLE %I LOGIN NOSUPERUSER BYPASSRLS '
                       'NOCREATEDB NOCREATEROLE NOINHERIT', role_name);
        RAISE NOTICE '角色 % 已存在，属性已重置', role_name;
    END IF;

    IF pwd IS NOT NULL AND pwd <> '' THEN
        EXECUTE format('ALTER ROLE %I PASSWORD %L', role_name, pwd);
        RAISE NOTICE '角色 % 口令已更新', role_name;
    ELSE
        RAISE NOTICE '未提供 rag_ops_password，跳过 % 的口令设置', role_name;
    END IF;
END
$$;

-- NOINHERIT 之外还要**主动收权**：GRANT 是叠加的，重跑本脚本只会补授权限，
-- 不会撤掉历史遗留（例如早期版本给过 DELETE）。先 REVOKE 再 GRANT，
-- 「最小授权」才是可重复达成的状态，而不是一次性的愿望。
SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA rag FROM %I', :'rag_ops_role') \gexec
SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA rag FROM %I', :'rag_ops_role') \gexec

-- 口令有效期与超时护栏：
--   · statement_timeout / idle_in_transaction_session_timeout 把「一把长事务卡住全局桶」
--     变成数据库层就会打断的事（不是把风险留在应用里等人发现）；
--   · 默认 search_path 与应用侧 connection-init-sql 同一个值，避免「未限定名解析不到」。
-- ⚠️ 刻意**不设** app.tenant_id 的角色级默认值：连接是池化的，
--    角色级默认租户会把上一个持有连接者的作用域伪装成当前请求的作用域
--    （RagSessionScope 的注释记录了同一个坑）。
-- ⚠️⚠️ search_path 这条**不能用 %L**（实测踩到，反证 P13-5 就是为此而钉的）：
--    format('... SET search_path = %L', 'rag, public') 会写成
--      ALTER ROLE rag_ops SET search_path = 'rag, public'
--    引号包住整个列表时，PG 把它存成**一个**标识符 `"rag, public"`（含逗号的名字），
--    于是角色级默认看似设上了、实际解析不到任何表：
--      SHOW search_path → "rag, public"（带引号，实测）
--      to_regclass('rag_source') → NULL（实测；不带引号时为 rag.rag_source）
--    而**没有任何报错**。应用侧不受影响（HikariCP 的 connection-init-sql 是会话内
--    `SET search_path TO rag, public`，走的是列表语法），受害的是带外 psql 运维——
--    恰恰是这个角色默认值存在的理由。列表型 GUC 必须逐元素给：
SELECT format('ALTER ROLE %I SET statement_timeout = %L', :'rag_ops_role', '30s') \gexec
SELECT format('ALTER ROLE %I SET idle_in_transaction_session_timeout = %L', :'rag_ops_role', '15s') \gexec
SELECT format('ALTER ROLE %I SET search_path = %s', :'rag_ops_role', 'rag, public') \gexec


-- =====================================================================
-- 2. 授权（⚠️ 逐条 \gexec：整段放一个事务里，任一条失败会连带回滚全部 GRANT，
--        表现为「授权数 = 0」却无明显报错 —— 见 03_rag_roles.sql 的同名注释）
-- =====================================================================
SELECT format('GRANT USAGE ON SCHEMA rag TO %I', :'rag_ops_role') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'rag_ops_role') \gexec

-- 2.1 读：全库 SELECT（含跨租户盘点的元数据视图，第三刀用）
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA rag TO %I', :'rag_ops_role') \gexec

-- 2.2 写：只给全局治理真正要写的 6 张表，且一律不含 DELETE
SELECT format('GRANT INSERT, UPDATE ON rag.rag_source TO %I', :'rag_ops_role') \gexec
SELECT format('GRANT INSERT, UPDATE ON rag.rag_document TO %I', :'rag_ops_role') \gexec
SELECT format('GRANT UPDATE ON rag.rag_chunk TO %I', :'rag_ops_role') \gexec
SELECT format('GRANT INSERT, UPDATE ON rag.rag_chunk_embedding TO %I', :'rag_ops_role') \gexec
SELECT format('GRANT INSERT, UPDATE ON rag.rag_dictionary TO %I', :'rag_ops_role') \gexec
SELECT format('GRANT INSERT ON rag.rag_access_audit TO %I', :'rag_ops_role') \gexec

-- 2.3 未来新增的表**只**继承 SELECT —— 全局可写面必须显式扩，不会静默长出来
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA rag GRANT SELECT ON TABLES TO %I', :'rag_ops_role') \gexec


-- =====================================================================
-- 3. 校验（三段都是给「跑脚本的人」看的答案，不是装饰）
-- =====================================================================
-- 3.1 属性：super 必须 f；bypassrls **必须 t**（这条通道就是靠它写全局桶）；
--     同时确认它不是任何角色的成员、没有带角色级 app.tenant_id 默认值。
\echo ''
\echo '=== 角色属性（超级用户须为 f，绕过rls 须为 t，成员关系须为空）==='
SELECT r.rolname      AS 角色,
       r.rolsuper     AS 超级用户,
       r.rolbypassrls AS 绕过rls,
       r.rolcanlogin  AS 可登录,
       r.rolinherit   AS 可继承,
       COALESCE(r.rolconfig::text, '-') AS 角色级默认,
       COALESCE((SELECT string_agg(m.rolname, ',')
                 FROM pg_auth_members am
                 JOIN pg_roles m ON m.oid = am.roleid
                 WHERE am.member = r.oid), '-') AS 所属角色
FROM pg_roles r
WHERE r.rolname = :'rag_ops_role';

-- 3.2 授权面：应看到 11 张表有 SELECT、6 张表有写、**任何一行都不该出现 DELETE**。
\echo ''
\echo '=== 已获授权（写权限应仅限 6 张表，且无 DELETE）==='
SELECT table_name                                AS 表,
       string_agg(DISTINCT privilege_type, ', ' ORDER BY privilege_type) AS 权限
FROM information_schema.role_table_grants
WHERE grantee = :'rag_ops_role' AND table_schema = 'rag'
GROUP BY table_name
ORDER BY table_name;

-- 3.3 闸门未被打开的直接证据：应用侧策略仍以 `scope = 'TENANT'` 收口。
--     ⚠️ pg_policies 的真实列是 roles(name[]) / cmd / qual / with_check，
--        别按想象写 rolecheck/forper —— 那会在共享库上抛「column does not exist」，
--        而这条查询是给人看证据的，失败就等于本次变更没有留痕。
\echo ''
\echo '=== 全局写闸门仍在（with_check 须含 scope = TENANT；roles 须仍是 public）==='
SELECT tablename   AS 表,
       policyname  AS 策略,
       cmd         AS 命令,
       array_to_string(roles, ',') AS 适用角色,
       COALESCE(with_check, '-')  AS 写入校验
FROM pg_policies
WHERE schemaname = 'rag' AND cmd IN ('INSERT', 'UPDATE')
  AND tablename IN ('rag_source', 'rag_chunk', 'rag_chunk_embedding', 'rag_dictionary')
ORDER BY tablename, policyname;

\echo ''
\echo '✅ rag_ops 角色与最小授权完成。'
\echo '   ⚠️ 用该角色另开连接跑 scripts/check-rag-ops-role.sh 复核能力面。'
\echo '   ⚠️ 该角色一旦出现在主数据源配置里，RagStartupSelfCheck 必须拒绝启动。'
