import { createBrowserRouter, Navigate } from 'react-router-dom'
import { AdminLayout } from '@/layout/AdminLayout'
import { RequireAuth } from '@/auth/RequireAuth'
import { RequireRole } from '@/auth/RequireRole'
import { LoginPage } from '@/pages/LoginPage'
import { TenantRegistryPage } from '@/pages/TenantRegistryPage'
import { TenantDetailPage } from '@/pages/TenantDetailPage'
import { AuditLogsPage } from '@/pages/audit/AuditLogsPage'
import { RoleMatrixPage } from '@/pages/roles/RoleMatrixPage'
import { UsersManagePage } from '@/pages/users/UsersManagePage'
import { AgentRegistryPage } from '@/pages/console/AgentRegistryPage'
import { ToolCatalogPage } from '@/pages/console/ToolCatalogPage'
import { SkillCatalogPage } from '@/pages/console/SkillCatalogPage'
import { McpCatalogPage } from '@/pages/console/McpCatalogPage'
import { APP_BASE_PATH, ROLES, PLATFORM_SCOPE_ROLES, type Role } from '@/config/constants'

/**
 * 平台控制台路由（重规划 §7）。
 *
 * ⚠️ admin-console 是**纯平台控制台**（aud=admin-console-api）：
 *    平台身份域页面（用户/角色/租户/审计）+ 平台能力目录四页
 *    （Agent/Tool/Skill/MCP，走 /api/platform/v1/capabilities/*）。
 *    租户域旧页面文件暂留仓库，路由与导航已摘除。
 */
/** 租户生命周期管理作用域（开通/启停/退役；后端仍按 platform.tenant.* 重授权） */
const LIFECYCLE_SCOPE: Role[] = [ROLES.SUPER_ADMIN, ROLES.TENANT_LIFECYCLE_ADMIN]

/** 平台审计作用域（需 platform.audit.read；后端仍重授权） */
const AUDIT_SCOPE: Role[] = [ROLES.SUPER_ADMIN, ROLES.AUDITOR]

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
        { index: true, element: <Navigate to="/tenants" replace /> },
        {
          path: 'tenants',
          element: <TenantRegistryPage />,
        },
        {
          path: 'tenants/new',
          element: (
            <RequireRole roles={LIFECYCLE_SCOPE}>
              <TenantRegistryPage />
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
          // 平台用户管理（新建/启停/重置密码/角色增删；写端点有 SoD 与自身保护）
          path: 'users',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <UsersManagePage />
            </RequireRole>
          ),
        },
        {
          // 角色权限矩阵（冻结矩阵只读呈现，按命名空间分组）
          path: 'roles',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <RoleMatrixPage />
            </RequireRole>
          ),
        },
        {
          // 跨租户 Agent 注册表（只读盘点；平台链 + BYPASSRLS 会话窗）
          path: 'agents',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <AgentRegistryPage />
            </RequireRole>
          ),
        },
        {
          // 平台级 Tool 目录（三步发布工作流）
          path: 'tools',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <ToolCatalogPage />
            </RequireRole>
          ),
        },
        {
          // 平台级 Skill 目录
          path: 'skills',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <SkillCatalogPage />
            </RequireRole>
          ),
        },
        {
          // 平台级 MCP Server 目录
          path: 'mcps',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <McpCatalogPage />
            </RequireRole>
          ),
        },
        {
          // 平台审计（与租户审计分域；需 platform.audit.read）
          path: 'audit',
          element: (
            <RequireRole roles={AUDIT_SCOPE}>
              <AuditLogsPage />
            </RequireRole>
          ),
        },
      ],
    },
  ],
  { basename: APP_BASE_PATH },
)
