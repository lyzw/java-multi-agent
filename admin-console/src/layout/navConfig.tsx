import type { ReactNode } from 'react'
import {
  ApartmentOutlined,
  FileProtectOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import { PLATFORM_SCOPE_ROLES, ROLES, type Role } from '@/config/constants'

export interface NavNode {
  key: string
  label: string
  path: string
  icon: ReactNode
  requiredRoles: Role[]
  description: string
  /** 对应阶段 */
  phase: string
}

/**
 * 平台控制台导航树（重规划 §7 · 纯平台身份域）。
 *
 * ⚠️ 租户域节点（Agent/Tool/Skill/MCP/发布/依赖/监控/配置中心）已随
 *    「平台身份与租户身份分离」摘除——那些页面属租户身份域，
 *    将迁移至租户控制台。角色仅用于导航显隐，安全边界以执行时后端重授权为准。
 */
export const NAV_NODES: NavNode[] = [
  {
    key: 'tenants',
    label: '租户管理',
    path: '/tenants',
    icon: <ApartmentOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    description:
      '租户生命周期状态机（PENDING_SETUP→ACTIVE→SUSPENDED→DECOMMISSIONING…）、配额、能力授权。开通/启停需 TENANT_LIFECYCLE_ADMIN 或 SUPER_ADMIN。',
    phase: '平台域',
  },
  {
    key: 'roles',
    label: '用户与角色',
    path: '/roles',
    icon: <TeamOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    description:
      '平台用户（绝不返回 password_hash）与 6 个最小特权角色的冻结权限矩阵。角色赋权走带外运维通道，应用层不自授。',
    phase: '平台域',
  },
  {
    key: 'audit',
    label: '平台审计',
    path: '/audit',
    icon: <FileProtectOutlined />,
    requiredRoles: [ROLES.SUPER_ADMIN, ROLES.AUDITOR],
    description:
      '平台管理动作审计（登录/租户生命周期/配额/能力授权），读 platform_audit_event，与租户业务审计物理分域。仅追加、只读。',
    phase: '平台域',
  },
]
