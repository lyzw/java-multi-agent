-- ============================================================================
-- 09_platform_schema_grants.sql
-- 关联缺口：platform 域授权缺口（切 app_agent 后暴露的既有环境问题）
-- 关联阶段：与 G1+G2 同批落地（实连库 IT 全绿的前置）
--
-- 目的：补齐 platform schema 的授权。
--
-- ⚠️ 为什么需要（真实故障，不是预防性改动）：
--    `platform.platform_audit_event` 属**平台域**（平台级审计事件），
--    但角色脚本（03_platform_roles.sql）**只授了 public schema**，
--    于是 platform_ops 与 app_agent 对 platform schema **都没有 USAGE**。
--    后果：任何读/写平台审计表的 IT 直接报
--    `permission denied for schema platform`（与业务逻辑无关的权限错误），
--    而平台运维通道在生产上同样写不了这张表 —— 即「审计通道是断的」。
--
-- ⚠️ 为什么授给 platform_ops（而不是 app_agent）：
--    * platform_ops = 平台运维通道（BYPASSRLS），**本就负责 platform 域**；
--    * app_agent = 应用角色（NOSUPERUSER + NOBYPASSRLS），
--      给它 platform 域写权限会**扩大攻击面**（应用侧 SQL 注入可写平台审计），
--      违反「平台级对象走带外运维通道」的设计纪律。
--    * 因此平台域的 IT 必须用**运维凭据**连接（见 IT_PG_ADMIN_USER）。
--
-- ⚠️ 执行纪律（由主代理/DBA 在获得授权后执行，本脚本不自动运行）：
--    * 本脚本只补 USAGE + 表级 DML，不给 owner 权限、不改 RLS 开关。
--    * 本脚本与 01_platform_ddl.sql 的最新快照保持一致。
-- ============================================================================

-- 1) schema 级 USAGE（否则表级授权也用不上：连 schema 都进不去）
GRANT USAGE ON SCHEMA platform TO platform_ops;

-- 2) platform 域全部既有表 + 序列的读写权限
--    ⚠️ 用 DO 块遍历「已存在」的表：不同环境的 platform 域表集合可能不同
--       （本地 11 张 / RDS 19 张），写死表名会在缺表的环境里报错中断。
DO $$
DECLARE
    t text;
BEGIN
    FOR t IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'platform' AND c.relkind IN ('r', 'p')
    LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON platform.%I TO platform_ops', t);
    END LOOP;
    FOR t IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'platform' AND c.relkind = 'S'
    LOOP
        EXECUTE format('GRANT USAGE, SELECT ON platform.%I TO platform_ops', t);
    END LOOP;
END;
$$;

-- 3) 后续新建的 platform 域对象也自动授权
ALTER DEFAULT PRIVILEGES IN SCHEMA platform
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO platform_ops;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform
    GRANT USAGE, SELECT ON SEQUENCES TO platform_ops;
