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
import { PublishCenterPage } from '@/pages/publish/PublishCenterPage'
import { PublishRequestDetailPage } from '@/pages/publish/PublishRequestDetailPage'
import { PublishReleasesPage } from '@/pages/publish/PublishReleasesPage'
import { DependencyGraphPage } from '@/pages/dependencies/DependencyGraphPage'
import { ImpactAnalysisPage } from '@/pages/dependencies/ImpactAnalysisPage'
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
          path: 'skills',
          element: (
            <RequireRole roles={TENANT_SCOPE_ROLES}>
              <SkillCatalogPage />
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
