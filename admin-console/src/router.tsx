import { createBrowserRouter, Navigate } from 'react-router-dom'
import { AdminLayout } from '@/layout/AdminLayout'
import { RequireAuth } from '@/auth/RequireAuth'
import { RequireRole } from '@/auth/RequireRole'
import { LoginPage } from '@/pages/LoginPage'
import { DashboardPage } from '@/pages/DashboardPage'
import { PlaceholderPage } from '@/pages/PlaceholderPage'
import { TenantRegistryPage } from '@/pages/TenantRegistryPage'
import { TenantDetailPage } from '@/pages/TenantDetailPage'
import { TenantNewPage } from '@/pages/TenantNewPage'
import { AgentRegistryPage } from '@/pages/AgentRegistryPage'
import { AgentDetailPage } from '@/pages/AgentDetailPage'
import { ToolCatalogPage } from '@/pages/catalog/ToolCatalogPage'
import { SkillCatalogPage } from '@/pages/catalog/SkillCatalogPage'
import { McpCatalogPage } from '@/pages/catalog/McpCatalogPage'
import { ToolDetailPage } from '@/pages/catalog/ToolDetailPage'
import { SkillDetailPage } from '@/pages/catalog/SkillDetailPage'
import { McpDetailPage } from '@/pages/catalog/McpDetailPage'
import { ResultContractPage } from '@/pages/catalog/ResultContractPage'
import { PublishCenterPage } from '@/pages/publish/PublishCenterPage'
import { PublishRequestDetailPage } from '@/pages/publish/PublishRequestDetailPage'
import { PublishReleasesPage } from '@/pages/publish/PublishReleasesPage'
import { DependencyGraphPage } from '@/pages/dependencies/DependencyGraphPage'
import { ImpactAnalysisPage } from '@/pages/dependencies/ImpactAnalysisPage'
import { DelegationTopologyPage } from '@/pages/dependencies/DelegationTopologyPage'
import { MonitoringHealthPage } from '@/pages/monitoring/MonitoringHealthPage'
import { MonitoringDeniesPage } from '@/pages/monitoring/MonitoringDeniesPage'
import { AuditLogsPage } from '@/pages/audit/AuditLogsPage'
import { PrincipalMatrixPage } from '@/pages/PrincipalMatrixPage'
import { SettingsPage } from '@/pages/settings/SettingsPage'
import { APP_BASE_PATH, PLATFORM_SCOPE_ROLES, TENANT_SCOPE_ROLES } from '@/config/constants'

export const router = createBrowserRouter(
  [
    { path: '/login', element: <LoginPage /> },
    {
      path: '/',
      element: (
        <RequireAuth>
          <AdminLayout />
        </RequireAuth>
      ),
      children: [
        { index: true, element: <Navigate to="/dashboard" replace /> },
        { path: 'dashboard', element: <DashboardPage /> },
        {
          // 租户管理：页面内部按角色分流（平台注册表 / 我的租户控制台）
          path: 'tenants',
          element: <TenantRegistryPage />,
        },
        {
          path: 'tenants/new',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <TenantNewPage />
            </RequireRole>
          ),
        },
        {
          path: 'tenants/:id',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <TenantDetailPage />
            </RequireRole>
          ),
        },
        {
          path: 'agents',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <AgentRegistryPage />
            </RequireRole>
          ),
        },
        {
          path: 'agents/:id',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <AgentDetailPage />
            </RequireRole>
          ),
        },
        {
          path: 'tools',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <ToolCatalogPage />
            </RequireRole>
          ),
        },
        {
          // ⚠️ 平台级 Tool 详情（第三十四批 · C34-3）：读 = 租户级可见，
          //    写（登记 / 改版 / 停用）= PLATFORM_ADMIN 闸门，由后端强制。
          //    ⚠️ `new` 是保留段：登记新对象与详情共用同一张草稿表单。
          path: 'tools/:id',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <ToolDetailPage />
            </RequireRole>
          ),
        },
        {
          path: 'skills',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <SkillCatalogPage />
            </RequireRole>
          ),
        },
        {
          path: 'skills/:id',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <SkillDetailPage />
            </RequireRole>
          ),
        },
        {
          path: 'mcp',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <McpCatalogPage />
            </RequireRole>
          ),
        },
        {
          path: 'mcp/:id',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <McpDetailPage />
            </RequireRole>
          ),
        },
        {
          // 输出契约登记（多级 Agent v1.1 · 第二十九批 · C29-1 · A6）：
          // 契约是独立对象（不属于任何 Agent），故单独成页。
          path: 'contracts',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <ResultContractPage />
            </RequireRole>
          ),
        },
        {
          // 发布审批中心（P2）：队列 / 审批详情 / 发布时间线
          path: 'publish',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <PublishCenterPage />
            </RequireRole>
          ),
        },
        {
          path: 'publish/requests/:rid',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <PublishRequestDetailPage />
            </RequireRole>
          ),
        },
        {
          path: 'publish/releases',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <PublishReleasesPage />
            </RequireRole>
          ),
        },
        {
          // 关系视图（P3）：能力闭包图谱 + 影响分析（均只读）
          path: 'dependencies',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <DependencyGraphPage />
            </RequireRole>
          ),
        },
        {
          path: 'dependencies/impact',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <ImpactAnalysisPage />
            </RequireRole>
          ),
        },
        {
          // 委派拓扑（第三十四批 · C34-3）：父子 Agent 边的只读拓扑
          path: 'delegations',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <DelegationTopologyPage />
            </RequireRole>
          ),
        },
        {
          // 运行监控（P4）：健康总览 + 拒绝告警（均只读）
          path: 'monitoring',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <MonitoringHealthPage />
            </RequireRole>
          ),
        },
        {
          path: 'monitoring/denies',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <MonitoringDeniesPage />
            </RequireRole>
          ),
        },
        {
          // 审计日志（P5）：ledger 维度筛选 + 详情抽屉（只读，查询本身留痕）
          path: 'audit',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <AuditLogsPage />
            </RequireRole>
          ),
        },
        {
          // 角色矩阵（P5）：主体-角色与授权版本水位（只读）
          path: 'roles',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <PrincipalMatrixPage />
            </RequireRole>
          ),
        },
        {
          // 配置中心（P5）：凭据引用（仅布尔标识）+ 全局参数（占位，平台级）
          path: 'settings',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <SettingsPage />
            </RequireRole>
          ),
        },
        { path: '*', element: <PlaceholderPage node={undefined} /> },
      ],
    },
  ],
  { basename: APP_BASE_PATH },
)
