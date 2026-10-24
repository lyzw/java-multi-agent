/**
 * 全局常量 —— P0
 *
 * 部署形态：路径部署 + 同源反向代理（见 vite.config.ts 注释）。
 */

/** React Router basename（不含尾斜杠） */
export const APP_BASE_PATH = '/admin'

/** Vite 构建资源 base（含尾斜杠，供子路径资源定位） */
export const ASSET_BASE = '/admin/'

/** 同源 API 前缀（生产由反向代理转发到共享后端 agent-api） */
export const API_BASE = '/api'

/** sessionStorage 键名（与现有前端隔离，独立工程使用独立键） */
export const STORAGE_KEY = 'admin.session'

/** 角色常量（与后端登记值保持一致；安全判定以执行时后端重授权为准） */
export const ROLES = {
  SUPER_ADMIN: 'SUPER_ADMIN',
  PLATFORM_ADMIN: 'PLATFORM_ADMIN',
  TENANT_ADMIN: 'TENANT_ADMIN',
} as const

export type Role = (typeof ROLES)[keyof typeof ROLES]

/** 平台级作用域（租户管理、平台级发布、配置中心）所需角色 */
export const PLATFORM_SCOPE_ROLES: Role[] = [ROLES.SUPER_ADMIN, ROLES.PLATFORM_ADMIN]

/** 租户级管理（Agent/Tool/Skill/MCP、租户内发布、监控、审计）所需角色 */
export const TENANT_SCOPE_ROLES: Role[] = [
  ROLES.SUPER_ADMIN,
  ROLES.PLATFORM_ADMIN,
  ROLES.TENANT_ADMIN,
]

/** 是否具备平台级作用域（租户开通/启停、跨租户管理、配置中心） */
export function hasPlatformScope(roles: string[]): boolean {
  return roles.some((r) => PLATFORM_SCOPE_ROLES.includes(r as Role))
}

/** 是否具备租户级管理作用域（Agent/Tool/Skill/MCP 等） */
export function hasTenantScope(roles: string[]): boolean {
  return roles.some((r) => TENANT_SCOPE_ROLES.includes(r as Role))
}
