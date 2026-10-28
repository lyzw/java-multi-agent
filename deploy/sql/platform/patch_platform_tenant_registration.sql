-- =====================================================================
-- 补丁：把应用侧已存在的租户登记进平台生命周期注册表
--
-- 【为什么需要本文件】同一个「租户」在当前代码里有**两张表**：
--   public.tenant     应用侧租户（RLS 键；worker 的生命周期探针读这里的 status，
--                     见 JdbcTenantLifecycleProbe:42）
--   platform.tenant   平台侧生命周期登记（ticket 闸门读这里的 lifecycle_state，
--                     见 PlatformExecutionTicketService:225；另有 tenant_quota 外键挂在它上面）
--   两表**同 UUID 但无外键、无共同创建路径**：种子只灌 public.tenant，
--   平台控制台的建租户只灌 platform.tenant（JdbcPlatformTenantRepository:128 起）。
--   于是出现 2026-10-29 的现场：租户在应用侧 ACTIVE、四个 agent 都归它、worker 愿意跑，
--   但取执行票证得到 404 / TENANT_NOT_FOUND —— 闸门问的是另一张表。
--
-- 【语义】本补丁不授予任何权限，只是**镜像**：把「应用侧已有、平台侧缺登记」的租户
--   按它应用侧的 status 补进 platform.tenant，并补一行与建租户流程同款的默认配额。
--   已登记的行一律不动（不覆盖 lifecycle_state，避免把控制台停用的租户「救活」）。
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
