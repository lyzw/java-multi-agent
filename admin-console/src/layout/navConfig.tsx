import type { ReactNode } from 'react'
import {
  ApartmentOutlined,
  FileProtectOutlined,
  MergeCellsOutlined,
  PartitionOutlined,
  RobotOutlined,
  ToolOutlined,
  TeamOutlined,
  UserOutlined,
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
 * 平台控制台导航树（重规划 §7 · 纯平台身份域 + 平台能力目录）。
 *
 * ⚠️ 能力目录四页（Agent/Tool/Skill/MCP）走平台链端点
 *    /api/platform/v1/capabilities/*（平台令牌 aud=admin-console-api）；
 *    旧租户链 /api/cap/* 端点对平台令牌不可达。
 *    角色仅用于导航显隐，安全边界以执行时后端重授权为准。
 */
export const NAV_NODES: NavNode[] = [
  {
    key: 'users',
    label: '平台用户',
    path: '/users',
    icon: <UserOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    description:
      '平台用户全生命周期：新建账号、启用/停用、重置密码、赋予/撤销角色。写端点 /api/platform/v1/users/*（自身保护 + 超管 SoD）。',
    phase: '平台域',
  },
  {
    key: 'roles',
    label: '角色权限矩阵',
    path: '/roles',
    icon: <TeamOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    description:
      '6 个最小特权角色的冻结权限矩阵（代码冻结，运行期不可改），按命名空间分组只读呈现。角色赋权走「平台用户」页或带外运维通道。',
    phase: '平台域',
  },
  {
    key: 'agents',
    label: 'Agent 注册表',
    path: '/agents',
    icon: <RobotOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    description:
      '跨租户 Agent 只读盘点（平台链 /api/platform/v1/capabilities/agents，BYPASSRLS 数据源 + 会话窗）。只有元数据：定义正文、运行输出永不出现（禁区红线），不提供写端点。',
    phase: '能力目录',
  },
  {
    key: 'tools',
    label: 'Tool',
    path: '/tools',
    icon: <ToolOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    description:
      '平台级 Tool 目录与三步发布工作流（申请→审批→执行）。登记/修改经 PlatformRegistrationModal，停用走生命周期申请。',
    phase: '能力目录',
  },
  {
    key: 'skills',
    label: 'Skill',
    path: '/skills',
    icon: <MergeCellsOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    description:
      '平台级 Skill 目录（含 Tool/MCP 闭包声明）与三步发布工作流。声明的对象取不到定义会被 400 拒（闭包自洽）。',
    phase: '能力目录',
  },
  {
    key: 'mcps',
    label: 'MCP Server',
    path: '/mcps',
    icon: <PartitionOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    description:
      '平台级 MCP Server 目录与三步发布工作流。凭据定位符从不回显，重发必须显式重新填写（fail-closed）。',
    phase: '能力目录',
  },
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
    key: 'audit',
    label: '平台审计',
    path: '/audit',
    icon: <FileProtectOutlined />,
    requiredRoles: [ROLES.SUPER_ADMIN, ROLES.AUDITOR],
    description:
      '平台管理动作审计（登录/租户生命周期/配额/能力授权/用户管理），读 platform_audit_event，与租户业务审计物理分域。仅追加、只读。',
    phase: '平台域',
  },
]
