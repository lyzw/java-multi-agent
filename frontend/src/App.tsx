/**
 * 路由表（FE-D2 §1.1 / §2.3）
 *
 * 页面范围：P1–P19 中**可实施的部分**。
 * 未列入的页面（如 P11 家长视图）与其余同页按角色分支，未单独规划。
 *
 * ⛔ RequireRole 目前**只做登录校验**，不做角色判定 ——
 *    因登录响应不含 roles（缺口 G1），理由见 RequireRole.tsx。
 */

import { useEffect } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { SessionProvider, useSession } from '@/auth/session'
import { RequireAuth } from '@/auth/RequireAuth'
import { RequireRole } from '@/auth/RequireRole'
import { setUnauthorizedHandler } from '@/api/client'
import { AppShell } from '@/layouts/AppShell'

import { LoginPage } from '@/pages/LoginPage'
import { DashboardPage } from '@/pages/DashboardPage'
import { NewRunPage } from '@/pages/NewRunPage'
import { RunProgressPage } from '@/pages/RunProgressPage'

import { RunResultPage } from '@/pages/business/RunResultPage'
import { ProfilePage } from '@/pages/business/ProfilePage'
import { RunSearchPage } from '@/pages/business/RunSearchPage'
import {
  ConfigOverviewPage,
  ConfigRuntimePage,
  ConfigToolsPage,
  ConfigCompliancePage,
  AgentsPage,
  ConfigAuditPage,
} from '@/pages/config/ConfigPages'
import {
  ApprovalsPage,
  ReconciliationPage,
  AuditPage,
} from '@/pages/governance/GovernancePages'
import { HealthPage } from '@/pages/ops/HealthPage'

/** 把 API 层的 401 回调接到会话层 */
function UnauthorizedBridge(): null {
  const { onUnauthorized } = useSession()
  useEffect(() => {
    setUnauthorizedHandler(onUnauthorized)
  }, [onUnauthorized])
  return null
}

/** 登录后页面统一走布局 */
function Shell(): JSX.Element {
  return (
    <RequireAuth>
      <AppShell />
    </RequireAuth>
  )
}

function AppRoutes(): JSX.Element {
  return (
    <>
      <UnauthorizedBridge />
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        <Route element={<Shell />}>
          {/* ── 辅导 ── */}
          <Route path="/" element={<DashboardPage />} />
          <Route path="/runs" element={<RequireRole area="runs"><RunSearchPage /></RequireRole>} />
          <Route path="/runs/new" element={<NewRunPage />} />
          <Route path="/runs/:runId" element={<RunProgressPage />} />
          <Route path="/runs/:runId/result" element={<RunResultPage />} />
          <Route path="/profile/:studentId" element={<ProfilePage />} />

          {/* ── 配置（F4，只读骨架）── */}
          <Route path="/config" element={<RequireRole area="config"><ConfigOverviewPage /></RequireRole>} />
          <Route path="/config/runtime" element={<RequireRole area="config"><ConfigRuntimePage /></RequireRole>} />
          <Route path="/config/tools" element={<RequireRole area="config"><ConfigToolsPage /></RequireRole>} />
          <Route path="/config/compliance" element={<RequireRole area="config"><ConfigCompliancePage /></RequireRole>} />
          <Route path="/config/audit" element={<RequireRole area="audit"><ConfigAuditPage /></RequireRole>} />
          <Route path="/agents" element={<RequireRole area="agents"><AgentsPage /></RequireRole>} />

          {/* ── 治理 ── */}
          <Route path="/approvals" element={<RequireRole area="approvals"><ApprovalsPage /></RequireRole>} />
          <Route
            path="/reconciliation"
            element={<RequireRole area="reconciliation"><ReconciliationPage /></RequireRole>}
          />
          <Route path="/audit" element={<RequireRole area="audit"><AuditPage /></RequireRole>} />

          {/* ── 运维 ── */}
          <Route path="/health" element={<RequireRole area="health"><HealthPage /></RequireRole>} />
        </Route>

        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  )
}

export function App(): JSX.Element {
  return (
    <BrowserRouter>
      <SessionProvider>
        <AppRoutes />
      </SessionProvider>
    </BrowserRouter>
  )
}