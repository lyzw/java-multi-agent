-- =====================================================================
-- 补丁：把应用侧已存在的租户登记进平台生命周期注册表
--
-- 【为什么需要本文件】「这个租户能不能执行」在运行期有两处读点：
--   platform.tenant.lifecycle_state  票证签发闸门（PlatformExecutionTicketService:225）
--   public.tenant.status             worker 执行前探针（JdbcTenantLifecycleProbe:42）
--   后者是前者的**同事务镜像**：provisionTenant(:150) 与 transitionLifecycle(:209) 都两张表一起写，
--   所以正规开通的租户两边恒等 —— 这不是设计缺陷。
--   坏在一处**绕过 provisioning 的夹具**：02_platform_seed.sql:231 用裸
--   INSERT INTO public.tenant 造出租户 2187ffed…，只落了镜像、没落权威，
--   于是 2026-10-29 现场：worker 认它（ACTIVE、四个 Agent 归它），取票证却得 404。
--
-- 【语义】本补丁不授予任何权限，只是**把缺的那一半补上**：按应用侧已有的 status
--   为「应用侧已有、平台侧缺登记」的租户补 platform.tenant 行 + 与建租户流程同款的默认配额。
--   已登记的行一律不动（不覆盖 lifecycle_state，避免把控制台停用的租户「救活」）。
--   ⚠️ 只治当前夹具的后遗症；根治是 seed 自己也产出 platform.tenant + tenant_quota 两行。
--
-- 【幂等】NOT EXISTS 守卫，可重复执行；重复执行写入 0 行。
-- 【范围】开发/复放环境。生产上租户生命周期以 platform.tenant 为权威、
--   由控制台按流程创建，不应靠本脚本反向补登记。
-- 【执行】以 platform schema 的 owner（ai_agent）执行；platform_ops 虽有 BYPASSRLS
--   但对 platform schema 无写权限（RLS ≠ 表权限）。
--   ⚠️ 先 dry-run（事务内执行后 ROLLBACK）核对行数，再 COMMIT。
-- =====================================================================

INSERT INTO platform.tenant (id, name, slug, status, plan, lifecycle_state)
SELECT p.tenant_id, p.name, p.slug, p.status, NULL, p.status
FROM public.tenant p
WHERE NOT EXISTS (SELECT 1 FROM platform.tenant t WHERE t.id = p.tenant_id);

-- 默认配额与 JdbcPlatformTenantRepository 建租户时使用的取值一致（50 / 10）。
INSERT INTO platform.tenant_quota (tenant_id, agent_limit, run_concurrency_limit)
SELECT t.id, 50, 10
FROM platform.tenant t
WHERE NOT EXISTS (SELECT 1 FROM platform.tenant_quota q WHERE q.tenant_id = t.id);
