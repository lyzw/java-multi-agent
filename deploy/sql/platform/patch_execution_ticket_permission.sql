-- =====================================================================
-- 平台权限补丁：执行票证（platform.tenant.execution_ticket）
--
-- 【为什么需要本文件】权限矩阵的**运行期权威源是代码**
--   （PlatformRolePermissionMatrix），platform.platform_permission 是治理副本。
--   新增权限码时两边都要改：只改代码，「权限矩阵」页与审计检索会读不到说明；
--   只改库，运行期照旧默认拒绝 —— 两边不一致时**没有任何报错**。
--
-- 【幂等】两条 INSERT 都带 NOT EXISTS 守卫，可重复执行。
-- 【范围】只登记权限本身 + 绑定超级管理员。其余角色一律不绑：
--   该权限等于「可扮演任意租户的成员发起执行」，
--   能启停租户（TENANT_LIFECYCLE_ADMIN）不等于能替租户成员执行。
-- 【执行】以 platform schema 的 owner（ai_agent）执行；
--   platform_ops 虽有 BYPASSRLS 但对 platform schema 无写权限（RLS ≠ 表权限）。
-- =====================================================================

INSERT INTO platform.platform_permission (id, code, description)
SELECT 'e77d46fa-c981-40fd-8b97-9d54bc136a70',
       'platform.tenant.execution_ticket',
       '为租户成员签发短时执行票证（跨身份域执行，可扮演租户发起 Run）'
WHERE NOT EXISTS (SELECT 1 FROM platform.platform_permission
                  WHERE code = 'platform.tenant.execution_ticket');

INSERT INTO platform.platform_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM platform.platform_role r
JOIN platform.platform_permission p ON p.code = 'platform.tenant.execution_ticket'
WHERE r.name = 'PLATFORM_SUPER_ADMIN'
  AND NOT EXISTS (SELECT 1 FROM platform.platform_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id);
