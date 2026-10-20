/**
 * 角色路由守卫（FE-D2 §2.3）
 *
 * ⛔ **仅是体验优化，不是安全边界**（FE-01 §4.2 G-1）。
 *    真正的授权在后端（红线 9：执行时重新授权）。
 *    隐藏入口 ≠ 用户无权访问 —— 后端仍会拒绝。
 *
 * ⚠️ 刻意不引入权限库：依赖越少，攻击面越小（同 FE-D1 理由）。
 *
 * ⚠️⚠️ **`ROLE_PERMISSIONS` 是后端 `RoleBasedAuthorizationService.java:32` 的第二份副本**
 * （FE-D4 §1.8 F-22）：
 *    · 后端变更**不会**让这边编译失败，两边会静默漂移
 *    · 当前无调用方（`RequireRole` 内 `void area` 刻意放行），也无 UI 消费
 *    · G1（登录响应不含 `roles`）补齐前，任何真实角色判定都只能是猜
 * 故本文件保留契约定义 + 测试锁住语义，**不接假实现**。
 * 真正接入时必须以服务端 roles 为准，并删除这份副本。
 */

import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useSession } from './session'

/**
 * ⛔ 与后端 `ROLE_PERMISSIONS` 保持一致。
 *    若此处放宽而后端未放宽，会出现「点得进但全被拒」的困惑。
 *    来源：RoleBasedAuthorizationService.java:32
 */
export const ROLE_PERMISSIONS: Record<string, string[]> = {
  TENANT_ADMIN: ['config', 'agents', 'approvals', 'reconciliation', 'audit', 'health'],
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
  const { authenticated } = useSession()
  const location = useLocation()

  if (!authenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  /*
   * ⚠️ 无法做角色判定：登录响应不含 roles（缺口 G1）。
   *
   * ⛔ 此处**刻意放行**，而不是伪造角色再判定：
   *    猜一个「假定有权限」会让无权用户看到管理页面入口，
   *    体验上像是系统坏了；
   *    而真正无权的用户点进去会被后端 403，
   *    那才是权威结论。
   *
   * → 角色级菜单隐藏与守卫，待 G1 补齐后再实现（见 FE-D2 §2.3）。
   */
  void area
  return <>{children}</>
}