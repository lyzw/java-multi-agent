/**
 * 全局常量 —— 平台身份域（重规划 §7）
 *
 * ⚠️ admin-console 已转型为**纯平台控制台**：登录走 `/api/platform/v1/auth/login`
 * （平台用户名/密码），持 `aud=admin-console-api` 的平台令牌；
 * 与租户身份（aud=agent-platform）令牌层互拒。
 */

/** React Router basename（不含尾斜杠） */
export const APP_BASE_PATH = '/admin'

/** Vite 构建资源 base（含尾斜杠，供子路径资源定位） */
export const ASSET_BASE = '/admin/'

/** 同源 API 前缀（生产由反向代理转发到共享后端 agent-api） */
export const API_BASE = '/api'

/**
 * sessionStorage 键名。
 *
 * ⚠️ 平台会话使用**独立键**：旧租户会话（admin.session）不会与本会话互相污染；
 * 令牌层互拒后旧令牌也已无效。
 */
export const STORAGE_KEY = 'admin-console.platform.session'

/**
 * 6 个最小特权平台角色（与后端 PlatformRoles / application.yml known-roles 严格一致）。
 *
 * ⛔ 角色赋权仅经 out-of-band 运维通道写 platform.platform_user_role；
 *    应用层不提供自授端点（防自举回路）。
 */
export const ROLES = {
  SUPER_ADMIN: 'PLATFORM_SUPER_ADMIN',
  TENANT_LIFECYCLE_ADMIN: 'TENANT_LIFECYCLE_ADMIN',
  RESOURCE_ADMIN: 'PLATFORM_RESOURCE_ADMIN',
  SECURITY_ADMIN: 'PLATFORM_SECURITY_ADMIN',
  AUDITOR: 'PLATFORM_AUDITOR',
  SUPPORT: 'PLATFORM_SUPPORT',
} as const

export type Role = (typeof ROLES)[keyof typeof ROLES]

/** 全部平台角色（导航显隐用；安全边界以执行时后端重授权为准） */
export const PLATFORM_SCOPE_ROLES: Role[] = [
  ROLES.SUPER_ADMIN,
  ROLES.TENANT_LIFECYCLE_ADMIN,
  ROLES.RESOURCE_ADMIN,
  ROLES.SECURITY_ADMIN,
  ROLES.AUDITOR,
  ROLES.SUPPORT,
]

/** 是否持有任一平台角色（仅导航显隐；非安全边界） */
export function hasPlatformScope(roles: string[]): boolean {
  return roles.some((r) => PLATFORM_SCOPE_ROLES.includes(r as Role))
}

