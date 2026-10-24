import type { ReactNode } from 'react'
import {
  DashboardOutlined,
  ApartmentOutlined,
  RobotOutlined,
  ToolOutlined,
  BulbOutlined,
  ApiOutlined,
  SendOutlined,
  ShareAltOutlined,
  MonitorOutlined,
  FileProtectOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import { PLATFORM_SCOPE_ROLES, TENANT_SCOPE_ROLES, type Role } from '@/config/constants'

export interface NavNode {
  key: string
  label: string
  path: string
  icon: ReactNode
  requiredRoles: Role[]
  description: string
  /** 对应《管理后台开发计划》阶段 */
  phase: string
}

/** 控制台导航树（与设计方案 §4 页面结构对齐） */
export const NAV_NODES: NavNode[] = [
  {
    key: 'dashboard',
    label: '概览仪表盘',
    path: '/dashboard',
    icon: <DashboardOutlined />,
    requiredRoles: [],
    description: '全局实体数量、生命周期分布、待审批队列、运行健康与最近变更。',
    phase: 'P0',
  },
  {
    key: 'tenants',
    label: '租户管理',
    path: '/tenants',
    icon: <ApartmentOutlined />,
    requiredRoles: TENANT_SCOPE_ROLES,
    description: '租户生命周期、配置覆盖、主体与角色、RLS 隔离校验（平台/运营级）。当前账号无平台权限时呈现「我的租户」自服务视图。',
    phase: 'P0.5',
  },
  {
    key: 'agents',
    label: 'Agent 管理',
    path: '/agents',
    icon: <RobotOutlined />,
    requiredRoles: TENANT_SCOPE_ROLES,
    description: '租户级 Agent：列表 / 详情 / 创建 / 发布版本（definition 为不可变快照）。',
    phase: 'P1',
  },
  {
    key: 'tools',
    label: 'Tool 管理',
    path: '/tools',
    icon: <ToolOutlined />,
    requiredRoles: TENANT_SCOPE_ROLES,
    description: '平台级 Tool 目录（只读，含风险等级 / MCP 绑定）；变更经带外运维通道。',
    phase: 'P1',
  },
  {
    key: 'skills',
    label: 'Skill 管理',
    path: '/skills',
    icon: <BulbOutlined />,
    requiredRoles: TENANT_SCOPE_ROLES,
    description: '平台级 Skill 目录（只读，呈现声明工具 / MCP 与 null≠[] 语义）；变更经带外运维通道。',
    phase: 'P1',
  },
  {
    key: 'mcp',
    label: 'MCP 服务',
    path: '/mcp',
    icon: <ApiOutlined />,
    requiredRoles: TENANT_SCOPE_ROLES,
    description: '平台级 MCP Server 目录（只读，信任分级 / 连接风险）；连接测试与清单采集后续前置。',
    phase: 'P1',
  },
  {
    key: 'publish',
    label: '发布与审批',
    path: '/publish',
    icon: <SendOutlined />,
    requiredRoles: TENANT_SCOPE_ROLES,
    description: '三步工作流（申请→双人复核→执行）：申请队列、审批、发布时间线与回滚。平台级对象发布走带外运维通道。',
    phase: 'P2',
  },
  {
    key: 'dependencies',
    label: '关系与依赖',
    path: '/dependencies',
    icon: <ShareAltOutlined />,
    requiredRoles: TENANT_SCOPE_ROLES,
    description: '能力闭包图谱与影响分析（只读）：Agent→Skill→Tool/MCP 依赖全景、变更前影响判据与悬空引用告警。',
    phase: 'P3',
  },
  {
    key: 'monitoring',
    label: '运行监控',
    path: '/monitoring',
    icon: <MonitorOutlined />,
    requiredRoles: TENANT_SCOPE_ROLES,
    description: '健康总览、authzVersion 撤权水位、拒绝告警分诊（只读）：严重/警告/提示三级分档，数据源不可用时显式告警而非显示为空。',
    phase: 'P4',
  },
  {
    key: 'audit',
    label: '审计日志',
    path: '/audit',
    icon: <FileProtectOutlined />,
    requiredRoles: TENANT_SCOPE_ROLES,
    description: '操作日志 ledger 维度筛选与详情、双人复核留痕。',
    phase: 'P5',
  },
  {
    key: 'settings',
    label: '配置中心',
    path: '/settings',
    icon: <SettingOutlined />,
    requiredRoles: PLATFORM_SCOPE_ROLES,
    description: '全局参数、凭据引用登记（仅引用标识，不接触明文）。',
    phase: 'P5',
  },
]
