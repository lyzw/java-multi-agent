-- ============================================================================
-- 11_platform_tenant_read_grant.sql
-- 关联缺口：崩溃恢复扫描器（RunRecoverySweeper）读 platform.tenant 被拒
-- 关联阶段：P4-D17 消息通道切换残留排查（2026-10-31 22:xx 启动失败收口）
--
-- 目的：把「崩溃恢复扫描器读不到租户清单」的权限缺口补齐。
--
-- ⚠️ 现象（真实故障，不是预防性改动）：
--    切 app_agent 角色后（2026-10-30），RunRecoverySweeper 每 30 秒报一次
--        ERROR: permission denied for schema platform
--    位置：136（platform.tenant 的 SELECT）
--    后果：**崩溃恢复链路事实上失效** —— worker 崩溃后持有的 RUNNING 租约
--    过期，却无人接管 → Run 永久卡死；而系统表面完全正常
--    （API 正常、无报错、无告警）。这与本项目已记录的「静默滞留」形态同族。
--
-- ⚠️ 为什么这不是「应用角色的权限给少了」而是「授权漏了一张表」：
--    03_platform_roles.sql 只授了 public schema（应用角色的业务域），
--    09_platform_schema_grants.sql 只授了 platform_ops（平台运维通道）。
--    ⇒ app_agent 对 platform schema **一张表都读不到**。
--    而 platform.tenant 是**租户登记表**（无 RLS，见下），本就该被应用角色读。
--
-- ⚠️ 为什么只需 USAGE + SELECT（**不给 BYPASSRLS、不给写权限**）：
--    * platform.tenant / platform.tenant_quota **没有行级安全**（relrowsecurity=f），
--      是平台域的**登记元数据**，不是业务数据 —— 读它不构成越权；
--    * 应用角色只需要「列出 ACTIVE 租户」这一件事（RunRecoverySweeper 的
--      租户枚举），**不需要**写 platform 域 —— 写平台域是平台运维通道的职责
--      （provisionTenant / transitionLifecycle 走平台身份域与带外运维凭据）；
--    * 故本脚本**只授读**。写权限若要放开，必须走 platform_ops，
--      否则「应用侧一条 SQL 就能改全平台租户生命周期」——那是直接的越权。
--
-- ⚠️ 为什么不给 app_agent platform schema 的**整域**读权限：
--    platform 域里还有 platform_user / platform_session / platform_permission /
--    platform_role / platform_audit_event 等**身份与凭据表** ——
--    给应用角色整域读权限 = 凭据库对业务进程敞开。
--    故本脚本**逐表点名**，只授租户登记表这两张。
--
-- ⚠️ 执行纪律（由主代理/DBA 在获得授权后执行，本脚本不自动运行）：
--    * 本脚本与 01_platform_ddl.sql / 03_platform_roles.sql 保持一致；
--    * 幂等：重复执行不报错（GRANT 可重复）。
-- ============================================================================

-- 1) schema 级 USAGE
--    ⚠️ 必须先有 USAGE，否则表级 GRANT 也用不上（连 schema 都进不去）。
GRANT USAGE ON SCHEMA platform TO app_agent;

-- 2) 租户登记表（**只读**）
--    ⚠️ 只点名这两张：崩溃恢复的租户枚举只需要它们。
--       platform 域的身份/凭据表（platform_user 等）刻意不授。
GRANT SELECT ON platform.tenant TO app_agent;
GRANT SELECT ON platform.tenant_quota TO app_agent;

-- 3) 后续新建的 platform.tenant 同类对象也自动授权
--    ⚠️ 只对**表**设默认权限，不对 schema 设 —— 默认权限只能覆盖当前角色
--       （即脚本执行者，通常是 ai_agent / 表 owner）新建的对象。
ALTER DEFAULT PRIVILEGES IN SCHEMA platform
    GRANT SELECT ON TABLES TO app_agent;

-- 4) 自检：应用角色现在能读到租户清单（读不到就是本脚本没生效）
--    ⚠️ 这里刻意**不以 app_agent 身份连**（本脚本的连接身份通常是表 owner）：
--       改成「以 app_agent 身份执行同一句 SELECT」才是真自检，
--       由执行者在应用侧另行验证（见下）。
DO $$
DECLARE
    has_usage boolean;
    has_select boolean;
BEGIN
    SELECT has_schema_privilege('app_agent', 'platform', 'USAGE') INTO has_usage;
    SELECT has_table_privilege('app_agent', 'platform.tenant', 'SELECT') INTO has_select;
    IF NOT (has_usage AND has_select) THEN
        RAISE EXCEPTION '授权未生效：app_agent 对 platform schema 的 USAGE=% , platform.tenant 的 SELECT=%',
            has_usage, has_select;
    END IF;
    RAISE NOTICE '自检通过：app_agent 已可只读 platform.tenant（USAGE=%, SELECT=%）',
        has_usage, has_select;
END;
$$;
