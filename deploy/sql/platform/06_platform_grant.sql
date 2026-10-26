-- =====================================================================
-- 平台角色赋权（带外运维通道）
-- =====================================================================
--
-- 【铁律】应用层<b>不提供</b>平台角色赋权端点（防「谁赋权谁自封管理员」自举回路）。
--   平台用户与角色的绑定<b>只经本带外 SQL</b>，由已持有足够平台权限的运维人员
--   （如 PLATFORM_SUPER_ADMIN）在 platform_ops 连接上执行。
--
-- 【参数】（执行前替换）
--   :username    —— 目标平台用户名（platform_user.username）
--   :role_name   —— 平台角色名（见 platform_role 内置 6 角色）
--   :grantor     —— 执行赋权的超管用户名（写入 granted_by，用于审计追溯）
--
-- 【职责分离】授予 PLATFORM_SUPER_ADMIN 须由已持有该角色者执行；
--   本脚本不校验 grantor 是否确为超管——该校验由运维流程与平台审计保证。
-- =====================================================================

-- 授予平台角色（幂等：已存在则跳过）
INSERT INTO platform.platform_user_role (platform_user_id, role_id, granted_by)
SELECT u.id, r.id, g.id
FROM platform.platform_user u
CROSS JOIN platform.platform_role r
CROSS JOIN platform.platform_user g
WHERE u.username = :'username'
  AND r.name = :'role_name'
  AND g.username = :'grantor'
ON CONFLICT (platform_user_id, role_id) DO NOTHING;

-- 使被授权者下次登录即携带新授权（失效旧 authz_version 缓存）
UPDATE platform.platform_user u
SET authz_version = authz_version + 1, updated_at = now()
WHERE u.username = :'username';

-- ⚠️ 撤销示例（如误授权或人员离任）：将 below 注释解除并执行
-- DELETE FROM platform.platform_user_role
-- WHERE platform_user_id = (SELECT id FROM platform.platform_user WHERE username = :'username')
--   AND role_id = (SELECT id FROM platform.platform_role WHERE name = :'role_name');
-- UPDATE platform.platform_user u
-- SET authz_version = authz_version + 1, updated_at = now()
-- WHERE u.username = :'username';
