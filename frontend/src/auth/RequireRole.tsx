/**
 * 角色路由守卫（FE-D2 §2.3）
 *
 * ⛔ **仅是体验优化，不是安全边界**（FE-01 §4.2 G-1）。
 *    真正的授权在后端（红线 9：执行时重新授权）。
 *    隐藏入口 ≠ 用户无权访问 —— 后端仍会拒绝。
 *
 * ⚠️ 刻意不引入权限库：依赖越少，攻击面越小（同 FE-D1 理由）。
 *
 * ⚠️ 2026-10-23（B1）：登录响应现已回带 `roles`，本守卫据此做**真实判定**
 *    （见文件尾 `RequireRole` 实现），不再 `void area` 放行。
 *    `ROLE_PERMISSIONS` 是后端 `RoleBasedAuthorizationService` 的**展示用副本**，
 *    仅用于导航显隐/路由引导，安全权威永远在后端。
 */

import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useSession } from './session'

/**
 * ⛔ 与后端 `RoleBasedAuthorizationService` 的角色→区域映射保持一致（展示用副本）。
 *    若此处放宽而后端未放宽，会出现「点得进但全被拒」的困惑；
 *    若此处收紧而后端未收紧，会误拦有权用户。安全以服务端为准。
 */
export const ROLE_PERMISSIONS: Record<string, string[]> = {
  // ⚠️ `ragManagement` 是 RAG 语料管理面（RAG-D10）——审核与撤回属治理动作，
  //    与「辅导」侧的 `rag`（知识检索）分属不同语义，故单列一个区域名。
  TENANT_ADMIN: [
    'config',
    'agents',
    'approvals',
    'reconciliation',
    'audit',
    'health',
    'ragManagement',
  ],
  AGENT_AUTHOR: ['config', 'agents'],
  APPROVER: ['approvals', 'reconciliation'],
  VIEWER: [],
  WORKER: [],
  STUDENT: [],
  PARENT: [],
  TEACHER: [],
}

export function hasRoleAccess(userRoles: string[], area: string): boolean {
  return userRoles.some((role) => {
    const allowed = ROLE_PERMISSIONS[role]
    return allowed !== undefined && allowed.includes(area)
  })
}

export function RequireRole({
  area,
  children,
}: {
  area: string
  children: ReactNode
}): JSX.Element {
  const { authenticated, roles } = useSession()
  const location = useLocation()

  if (!authenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  // ⚠️ B1：真实角色判定。无 area 所需角色 → 跳回工作台（后端仍会二次拦截）。
  //    fail-closed：roles 为空（含旧会话未回带）时一律视为无权，避免误放管理页。
  if (!hasRoleAccess(roles, area)) {
    return <Navigate to="/" replace />
  }

  return <>{children}</>
}