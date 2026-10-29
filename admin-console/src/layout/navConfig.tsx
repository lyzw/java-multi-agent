import type { ReactNode } from 'react'
import {
  ApartmentOutlined,
  AppstoreOutlined,
  ControlOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  FileProtectOutlined,
  FileSearchOutlined,
  LineChartOutlined,
  MergeCellsOutlined,
  PartitionOutlined,
  RobotOutlined,
  BookOutlined,
  TeamOutlined,
  ToolOutlined,
  ThunderboltOutlined,
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
  /** 所属分组标签（由 NAV_GROUPS 派生，供仪表盘等只读消费） */
  phase: string
}

export interface NavGroup {
  key: string
  label: string
  icon: ReactNode
  requiredRoles: Role[]
  children: NavNode[]
}

/**
 * 平台控制台导航树（分组重构）。
 *
 * ⚠️ **分组的唯一来源是 `NAV_GROUPS`**，`NAV_NODES` 是由它派生的扁平视图
 *    （保留给仪表盘「模块导航」等只读消费方）。
 *    刻意不让两处各写一份——分组与节点一旦分家，
 *    就会出现「侧栏有、仪表盘没有」这类只在某一条路径上可见的偏差。
 *
 * ⚠️ 能力目录四页（Agent/Tool/Skill/MCP）走平台链端点
 *    /api/platform/v1/capabilities/*（平台令牌 aud=admin-console-api）；
 *    旧租户链 /api/cap/* 端点对平台令牌不可达。
 *    角色仅用于导航显隐，安全边界以执行时后端重授权为准。
 */
export const NAV_GROUPS: NavGroup[] = [
  {
    key: 'governance',
    label: '平台治理',
    icon: <ApartmentOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'tenants',
        label: '租户管理',
        path: '/tenants',
        icon: <ApartmentOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '租户生命周期状态机（PENDING_SETUP→ACTIVE→SUSPENDED→DECOMMISSIONING…）、配额、能力授权。开通/启停需 TENANT_LIFECYCLE_ADMIN 或 SUPER_ADMIN。',
        phase: '平台治理',
      },
      {
        key: 'users',
        label: '平台用户',
        path: '/users',
        icon: <UserOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '平台用户全生命周期：新建账号、启用/停用、重置密码、赋予/撤销角色。写端点 /api/platform/v1/users/*（自身保护 + 超管 SoD）。',
        phase: '平台治理',
      },
      {
        key: 'roles',
        label: '角色权限矩阵',
        path: '/roles',
        icon: <TeamOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '6 个最小特权角色的冻结权限矩阵（代码冻结，运行期不可改），按命名空间分组只读呈现，含中文释义。角色赋权走「平台用户」页或带外运维通道。',
        phase: '平台治理',
      },
      {
        key: 'audit',
        label: '平台审计',
        path: '/audit',
        icon: <FileProtectOutlined />,
        requiredRoles: [ROLES.SUPER_ADMIN, ROLES.AUDITOR],
        description:
          '平台管理动作审计（登录/租户生命周期/配额/能力授权/用户管理），读 platform_audit_event，与租户业务审计物理分域。仅追加、只读。',
        phase: '平台治理',
      },
    ],
  },
  {
    key: 'capability',
    label: '能力目录',
    icon: <AppstoreOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
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
        key: 'routing-policies',
        label: '路由策略',
        path: '/routing-policies',
        icon: <MergeCellsOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '有界规则引擎的策略登记与试跑。试跑把草稿原样交给后端 agent-routing 的同一个引擎与守卫'
          + '（前端无第二份求值器，故界面结论与运行期一致）。'
          + '⚠️ 端点属租户域（/api/cap/config/**，租户来自验签令牌），平台令牌可能 401/403；'
          + '此时页面明示「需租户级令牌通道」，不会把「没跑成」显示成「没问题」。',
        phase: '能力目录',
      },
    ],
  },
  {
    key: 'knowledge',
    label: '知识与数据',
    icon: <DatabaseOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'rag',
        label: 'RAG 语料管理',
        path: '/rag',
        icon: <FileSearchOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '教辅语料治理：登记 → 校验流转 → 可检索。独立 RAG 服务（默认 8081，/api/v1/management/*），作用域由 X-Rag-Scope 显式声明：本租户语料需选租户（读写俱全），平台全局语料不选租户（本刀只读）。撤回是终态、无删除入口。',
        phase: '知识与数据',
      },
    ],
  },
  {
    key: 'system',
    label: '系统配置',
    icon: <ControlOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'dictionaries',
        label: '字典配置',
        path: '/dictionaries',
        icon: <BookOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '把前端下拉枚举收敛到 sys_dictionary / sys_dictionary_item 两表维护：新增、改名、停用无需改前端代码。内置字典禁止物理删除，仅可停用（避免历史数据取值悬空）。',
        phase: '系统配置',
      },
    ],
  },
  {
    key: 'observability',
    label: '观测度量',
    icon: <LineChartOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'metrics',
        label: 'Agent 效果指标',
        path: '/metrics',
        icon: <LineChartOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '六大类线上指标（SLA/成本/过程行为/业务效果/用户反馈/风险安全），按 L0 系统事实 → L1 自动校验 → L2 抽样标注 → L3 有噪信号分层标注可信度。⚠️ 未采集显示「未采集」而非 0；样本量 < 30 显示「样本不足」；链路成功 ≠ 任务成功。',
        phase: '观测度量',
      },
      {
        key: 'metrics-entry',
        label: '效果数据录入',
        path: '/metrics-entry',
        icon: <FileSearchOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '手动回填四类效果数据：L1 自动校验回执 / L2 抽样标注（黄金基线）/ 风险事件 / L3 用户反馈。要求 platform.resource.manage；风险事件只接受摘要绝不接收原文（合规红线）。租户侧反馈请勿经此页，走租户端反馈入口。',
        phase: '观测度量',
      },
    ],
  },
  {
    key: 'lab',
    label: 'AI 实验室',
    icon: <ExperimentOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    children: [
      {
        key: 'agent-lab',
        label: 'Agent 效果测试',
        path: '/agent-lab',
        icon: <ThunderboltOutlined />,
        requiredRoles: PLATFORM_SCOPE_ROLES,
        description:
          '挑选 Agent 与用例，发起一次真实执行并观察事件流、工具调用、耗时与预算消耗，用于回归比对与效果验收。',
        phase: 'AI 实验室',
      },
    ],
  },
]

/** 扁平视图（由分组派生）——供仪表盘等只读消费方使用。 */
export const NAV_NODES: NavNode[] = NAV_GROUPS.flatMap((g) => g.children)
