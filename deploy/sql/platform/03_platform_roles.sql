-- =====================================================================
-- platform 数据库角色与授权 —— 生产环境初始化用
-- =====================================================================
--
-- 【为什么必须单独成文件】
--   本文件含**集群级**操作（CREATE ROLE），需 CREATEROLE 及以上权限
--   （RDS 上为 alicloud_rds_admin 或具备 CREATEROLE 的账号）。
--   而 01_platform_ddl.sql 用 --no-owner --no-privileges 导出，
--   **不含任何 GRANT** —— 因为授权必须落在「应用实际使用的角色」上，
--   不能把开发机的 owner 写死进生产库。
--
-- 【两个角色的形状是刻意不同的】
--   · app_agent   —— NOSUPERUSER + **NO**BYPASSRLS
--       应用运行时连接用的角色。带 BYPASSRLS 会让租户隔离形同虚设，
--       而 FORCE ROW LEVEL SECURITY 也拦不住超管/BYPASSRLS 角色。
--   · platform_ops —— NOSUPERUSER + **BYPASSRLS**
--       仅用于「平台级配置发布」（写 cap_config_release / cap_config_pointer /
--       cap_decision_log 的 PLATFORM 行）。这三张表的写策略**只放行本租户的
--       TENANT 行**，平台级行必须由此角色写入，否则任一租户都能单方面
--       替换全平台生效配置——那是直接的越权。
--   ⚠️ 不要给任一角色 SUPERUSER：超级用户绕过的是一切，会让
--      「运维角色只写平台级行」这条约定失去验证手段。
--
-- 【⚠️ 两套凭据必须分开】
--   app_agent 与 platform_ops 是**两条独立发布通道**、两套凭据：
--     app_agent     → 租户作用域发布（TENANT），受 RLS 约束；
--     platform_ops  → 平台作用域发布（PLATFORM），绕过 RLS。
--   混用会让「谁是平台运维」在数据库层失去区分。
--   应用侧对应配置（V17 / application.yml）：
--     AGENT_PLATFORM_OPS_ENABLED=true
--     AGENT_PLATFORM_OPS_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db>
--     AGENT_PLATFORM_OPS_DATASOURCE_USERNAME=platform_ops
--     AGENT_PLATFORM_OPS_DATASOURCE_PASSWORD=<口令>
--   ⚠️ enabled=true 但缺 url/username → 应用**拒绝启动**（fail-closed）。
--   ⚠️ 未启用时平台级发布**明确失败**（PlatformOpsUnavailableException），
--      不会静默回落到应用角色去写、再被 RLS 拒掉。
--
-- 【幂等】
--   可反复执行：角色存在则重置属性，不存在则创建；
--   **不提供口令时不改口令**（避免误覆盖既有凭据）。
--
-- 【用法】
--   psql -h <host> -p <port> -U <超管> -d <target_db> -v ON_ERROR_STOP=1 \
--        -v app_password='<app_agent 口令>' \
--        -v ops_password='<platform_ops 口令>' \
--        -f 03_platform_roles.sql
--
--   ⚠️ 口令建议由密钥管理注入，**不要**写进本文件或命令行历史。
-- =====================================================================

\set ON_ERROR_STOP on

\if :{?app_role}
\else
  \set app_role 'app_agent'
\endif

\if :{?ops_role}
\else
  \set ops_role 'platform_ops'
\endif

-- ⚠️ 不提供则不设口令（只调整权限，不动既有凭据）
\if :{?app_password}
\else
  \set app_password ''
\endif

\if :{?ops_password}
\else
  \set ops_password ''
\endif

\echo '── 角色参数 ──'
\echo '  应用角色   :' :app_role
\echo '  运维角色   :' :ops_role

-- 把参数传入会话变量，供 DO 块读取
-- ⚠️ psql 变量无法在 DO 块内直接引用（:'x' 是客户端语法），
--    故先用 set_config 放入会话变量，再由 DO 块 current_setting() 取回。
SELECT set_config('my.app_role',   :'app_role',   false),
       set_config('my.app_password', :'app_password', false),
       set_config('my.ops_role',   :'ops_role',   false),
       set_config('my.ops_password', :'ops_password', false);


-- =====================================================================
-- 1. 创建 / 重置两个角色
-- =====================================================================
-- 幂等要点：CREATE ROLE 是**集群级**对象，DROP DATABASE 不会删除它，
--   直接 CREATE 会因重名失败并连带回滚整段脚本 —— 故先判存在再分支。
DO $$
DECLARE
    role_name text := current_setting('my.app_role');
    pwd       text := current_setting('my.app_password');
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
        RAISE NOTICE '未提供 app_password，跳过 % 的口令设置', role_name;
    END IF;
END
$$;

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
        RAISE NOTICE '角色 % 已存在，属性已重置', role_name;
    END IF;

    IF pwd IS NOT NULL AND pwd <> '' THEN
        EXECUTE format('ALTER ROLE %I PASSWORD %L', role_name, pwd);
        RAISE NOTICE '角色 % 口令已更新', role_name;
    ELSE
        RAISE NOTICE '未提供 ops_password，跳过 % 的口令设置', role_name;
    END IF;
END
$$;


-- =====================================================================
-- 2. 授权
-- =====================================================================
-- ⚠️ **必须逐条 GRANT**：若整段放在一个事务里，任意一条失败会连带回滚
--    全部 GRANT，表现为「授权数 = 0」却无明显报错。
--
-- ⚠️ **DEFAULT 权限必须设**：上面的 GRANT 只覆盖**已存在**的表；
--   后续新建的表（如 V15 的 cap_*）**不会**自动带上授权 →
--   应用角色一访问就报 permission denied，而报错点在业务代码，
--   离「表没授权」的真实成因极远。
--   这与「迁移清单与现实脱节」是同一族失败形态：系统表面完全正常。

-- ── 应用角色 ────────────────────────────────────────────────────────
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'app_role') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', :'app_role') \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', :'app_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA PUBLIC GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'app_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA PUBLIC GRANT USAGE, SELECT ON SEQUENCES TO %I', :'app_role') \gexec

-- ── 平台运维角色 ─────────────────────────────────────────────────────
-- ⚠️ 同样需要表级 INSERT/UPDATE 权限才能写行：
--    RLS 是**行级**隔离，绕不绕得掉取决于**连接用的角色**，
--    而不是表级授权 —— 表级授权是必需的，但它**不是**隔离本身。
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'ops_role') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', :'ops_role') \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', :'ops_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA PUBLIC GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'ops_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA PUBLIC GRANT USAGE, SELECT ON SEQUENCES TO %I', :'ops_role') \gexec


-- =====================================================================
-- 2b. platform 域授权（原 09 / 11 号脚本收编 · 2026-11-01）
-- =====================================================================
-- 【为什么 platform 域要单独授权】
--   03 历史上只授了 public schema，切 app_agent 后暴露两处真实故障：
--   ① platform_ops 对 platform schema 无 USAGE → 平台审计通道是断的
--     （`permission denied for schema platform`，与业务逻辑无关的权限错误）；
--   ② app_agent 读不到 platform.tenant → RunRecoverySweeper（崩溃恢复扫描器）
--     每 30 秒报错一次，worker 崩溃后无人接管 → Run 永久卡死（静默滞留）。
--
-- 【授权形状是刻意不对称的】
--   · platform_ops（BYPASSRLS 运维通道）＝ platform 域的本职 → 整域 DML；
--   · app_agent（应用角色，NOBYPASSRLS）＝ **只读两张租户登记表**
--     （platform.tenant / platform.tenant_quota，无 RLS 的登记元数据），
--     不给整域、不给写权限 —— platform 域还有 platform_user /
--     platform_audit_event 等身份与凭据表，整域放开＝凭据库对业务进程敞开。
-- =====================================================================

-- ── 运维角色：platform 域整域 DML ──────────────────────────────────
-- ⚠️ 用 DO 块遍历「已存在」的表：不同环境的 platform 域表集合可能不同，
--    写死表名会在缺表的环境里报错中断。
SELECT format('GRANT USAGE ON SCHEMA platform TO %I', :'ops_role') \gexec
DO $$
DECLARE
    t text;
    ops text := current_setting('my.ops_role');
BEGIN
    FOR t IN
        SELECT c.relname FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'platform' AND c.relkind IN ('r', 'p')
    LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON platform.%I TO %I', t, ops);
    END LOOP;
    FOR t IN
        SELECT c.relname FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'platform' AND c.relkind = 'S'
    LOOP
        EXECUTE format('GRANT USAGE, SELECT ON platform.%I TO %I', t, ops);
    END LOOP;
END;
$$;
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'ops_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT USAGE, SELECT ON SEQUENCES TO %I', :'ops_role') \gexec

-- ── 应用角色：仅租户登记表只读 ────────────────────────────────────
SELECT format('GRANT USAGE ON SCHEMA platform TO %I', :'app_role') \gexec
SELECT format('GRANT SELECT ON platform.tenant TO %I', :'app_role') \gexec
SELECT format('GRANT SELECT ON platform.tenant_quota TO %I', :'app_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT SELECT ON TABLES TO %I', :'app_role') \gexec

-- ── 应用角色：P7 指标遥测表（2026-11-01 补） ──────────────────────
-- 【为什么给 app_agent 这几张】
--   platform 域的 agent_* 指标 6 张表是 Run 执行热路径上的应用自身遥测
--   （JdbcAgentMetricRepository / JdbcRiskEventRepository，每次 Run 采集 upsert），
--   走 platform_ops 运维通道（max-pool-size=2 的低频小池）会挤爆连接池，
--   且它们是非敏感遥测数据（不含身份/凭据）—— 与 platform_user 等身份表性质不同。
--   其余 platform 身份/管理表（platform_user / role / audit / tenant 写 / sys_dictionary）
--   不给：那些路径全部改走 platform_ops 专用数据源（PlatformOpsDataSourceProvider
--   .platformIdentityDataSource()，见 AuthConfiguration / ApplicationConfiguration 装配）。
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON platform.agent_trace_metric TO %I', :'app_role') \gexec
SELECT format('GRANT SELECT, INSERT, DELETE ON platform.agent_trace_tool_call TO %I', :'app_role') \gexec
SELECT format('GRANT SELECT, INSERT ON platform.agent_trace_feedback TO %I', :'app_role') \gexec
SELECT format('GRANT SELECT, INSERT ON platform.agent_eval_sample TO %I', :'app_role') \gexec
SELECT format('GRANT SELECT, INSERT ON platform.agent_risk_event TO %I', :'app_role') \gexec
SELECT format('GRANT SELECT, INSERT ON platform.agent_task_outcome TO %I', :'app_role') \gexec
SELECT format('GRANT SELECT ON platform.mv_agent_metric_hourly TO %I', :'app_role') \gexec

-- ── 应用角色：授权决策流水（D6 / P2，2026-11-02 补） ────────────────
-- 【为什么只给 INSERT，且必须显式 REVOKE SELECT】
--   这张表是「谁在什么时候、以什么主体、对什么资源、被判允许还是拒绝」的**全平台**流水。
--   · 给 SELECT = 任一租户的主体可枚举其它租户的授权决策（谁、对什么、放行还是拒绝），
--     属跨租户元数据泄漏；而应用侧唯一的写者是 DefaultAccessGate 的决策记录器，
--     它只需要 INSERT。
--   · 读能力只给 platform_ops（上「整域 DML」已覆盖）—— 取证走运维通道，不走业务进程。
--   ⚠️ 必须显式 REVOKE：上面「应用角色：仅租户登记表只读」里的
--     `ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT SELECT ON TABLES TO app_role`
--     会让**此脚本之后新建的任何 platform 表**自动获得 SELECT。
--     只写 GRANT INSERT 而不 REVOKE，会留下「本次修好了、下次建表又漏」的复发路径。
SELECT format('GRANT INSERT ON platform.authz_decision_log TO %I', :'app_role') \gexec
SELECT format('REVOKE SELECT ON platform.authz_decision_log FROM %I', :'app_role') \gexec

-- ── 自检：授权未生效即报错（fail-closed）──────────────────────────
DO $$
DECLARE
    app  text := current_setting('my.app_role');
    ops  text := current_setting('my.ops_role');
BEGIN
    IF NOT (has_schema_privilege(ops, 'platform', 'USAGE')
            AND has_table_privilege(ops, 'platform.platform_audit_event', 'SELECT')) THEN
        RAISE EXCEPTION '授权未生效：platform_ops 对 platform 域的 USAGE/SELECT 缺失';
    END IF;
    IF NOT (has_schema_privilege(app, 'platform', 'USAGE')
            AND has_table_privilege(app, 'platform.tenant', 'SELECT')) THEN
        RAISE EXCEPTION '授权未生效：app_agent 对 platform.tenant 的 USAGE/SELECT 缺失';
    END IF;
    -- 决策流水：必须能写（否则判定发生却不留痕迹，且失败被限频吞掉 → 静默无审计）
    IF NOT has_table_privilege(app, 'platform.authz_decision_log', 'INSERT') THEN
        RAISE EXCEPTION '授权未生效：app_agent 对 platform.authz_decision_log 缺 INSERT —— '
                        '决策日志会在每次判定时写入失败，且失败被限频告警吞掉（静默无审计）';
    END IF;
    -- 反向：不得能读（该表含全平台各租户的判定记录）
    IF has_table_privilege(app, 'platform.authz_decision_log', 'SELECT') THEN
        RAISE EXCEPTION '越权：app_agent 竟可 SELECT platform.authz_decision_log —— '
                        '该表含全平台各租户的判定记录，读必须走 platform_ops 运维通道';
    END IF;
    RAISE NOTICE '自检通过：platform 域授权就位（ops 整域 DML / app 仅租户登记表只读 + 决策流水只写）';
END;
$$;


-- =====================================================================
-- 3. 角色属性校验
-- =====================================================================
-- ⚠️ app_agent：rolsuper 与 rolbypassrls **必须**都是 f。
--    任一为 t，该角色都无法用于验证 RLS —— 超级用户绕过的是一切，
--    FORCE ROW LEVEL SECURITY 也拦不住，用它跑跨租户测试会得到
--    「隔离生效」的**虚假结论**。
-- ⚠️ platform_ops：rolbypassrls 必须是 t（这是该角色的全部意义），
--    同时 rolsuper 必须是 f。
\echo ''
\echo '=== 角色属性校验 ==='
SELECT rolname       AS 角色,
       rolsuper      AS 超级用户,
       rolbypassrls  AS 绕过rls,
       rolcreatedb   AS 可建库,
       rolcreaterole AS 可建角色,
       rolcanlogin   AS 可登录
FROM pg_roles
WHERE rolname IN (:'app_role', :'ops_role')
ORDER BY rolname;

\echo ''
\echo '=== 应用角色已获授权的表（前 10 张）==='
SELECT table_name,
       string_agg(DISTINCT privilege_type, ', ' ORDER BY privilege_type) AS 权限
FROM information_schema.role_table_grants
WHERE grantee = :'app_role' AND table_schema = 'public'
GROUP BY table_name
ORDER BY table_name
LIMIT 10;

\echo ''
\echo '=== 授权总数校验（应 = 表数 × 4 + 序列数）==='
SELECT count(*) AS 授权条目数
FROM information_schema.role_table_grants
WHERE grantee = :'app_role' AND table_schema = 'public';

\echo ''
\echo '✅ platform 角色与授权完成。'
\echo '   ⚠️ 下一步：用 app_agent 另开连接验 RLS（超管/BYPASSRLS 验隔离＝假结论）。'
\echo '   ⚠️ 平台级发布需配置：AGENT_PLATFORM_OPS_ENABLED=true 及运维数据源。'
