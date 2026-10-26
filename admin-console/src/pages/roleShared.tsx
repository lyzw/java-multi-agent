import { ROLES } from '@/config/constants'

/** 平台角色中文名（与后端 PlatformRoleController 描述一致） */
export const ROLE_LABELS: Record<string, string> = {
  PLATFORM_SUPER_ADMIN: '平台超级管理员',
  TENANT_LIFECYCLE_ADMIN: '租户生命周期管理员',
  PLATFORM_RESOURCE_ADMIN: '平台资源管理员',
  PLATFORM_SECURITY_ADMIN: '平台安全管理员',
  PLATFORM_AUDITOR: '平台审计员',
  PLATFORM_SUPPORT: '平台支持人员',
}

export const ROLE_COLORS: Record<string, string> = {
  PLATFORM_SUPER_ADMIN: 'red',
  TENANT_LIFECYCLE_ADMIN: 'orange',
  PLATFORM_RESOURCE_ADMIN: 'blue',
  PLATFORM_SECURITY_ADMIN: 'volcano',
  PLATFORM_AUDITOR: 'purple',
  PLATFORM_SUPPORT: 'cyan',
}

/** 当前登录主体是否持有平台超级管理员角色（仅决定超级管理员授予/撤销入口的显隐） */
export function isSuperAdmin(currentRoles: string[]): boolean {
  return currentRoles.includes(ROLES.SUPER_ADMIN)
}
