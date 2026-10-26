import { createBrowserRouter, Navigate } from 'react-router-dom'
import { AdminLayout } from '@/layout/AdminLayout'
import { RequireAuth } from '@/auth/RequireAuth'
import { RequireRole } from '@/auth/RequireRole'
import { LoginPage } from '@/pages/LoginPage'
import { TenantRegistryPage } from '@/pages/TenantRegistryPage'
import { TenantDetailPage } from '@/pages/TenantDetailPage'
import { AuditLogsPage } from '@/pages/audit/AuditLogsPage'
import { PrincipalMatrixPage } from '@/pages/PrincipalMatrixPage'
import { APP_BASE_PATH, ROLES, PLATFORM_SCOPE_ROLES, type Role } from '@/config/constants'

/**
 * 平台控制台路由（重规划 §7）。
 *
 * ⚠️ admin-console 已转型为**纯平台控制台**（aud=admin-console-api）：
 *    仅保留平台身份域页面（租户生命周期 / 平台用户与角色 / 平台审计）。
 *    租户域页面（Agent/Tool/Skill/MCP/发布/依赖/监控）属租户身份域，
 *    将迁移至租户控制台——页面文件暂留仓库，路由与导航已摘除。
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
          // 平台用户与角色（只读；赋权走带外运维通道）
          path: 'roles',
          element: (
            <RequireRole roles={PLATFORM_SCOPE_ROLES}>
              <PrincipalMatrixPage />
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
