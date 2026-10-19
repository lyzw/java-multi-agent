/**
 * 路由表
 *
 * ⚠️ 路由范围严格限定 F1–F3（FE-D1 §1.1）。
 *    配置管理（P14–P19）、结果、画像、治理页均**不在本片**。
 */

import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { SessionProvider, useSession } from '@/auth/session'
import { RequireAuth } from '@/auth/RequireAuth'
import { setUnauthorizedHandler } from '@/api/client'
import { useEffect } from 'react'
import { LoginPage } from '@/pages/LoginPage'
import { DashboardPage } from '@/pages/DashboardPage'
import { NewRunPage } from '@/pages/NewRunPage'
import { RunProgressPage } from '@/pages/RunProgressPage'

/** 把 API 层的 401 回调接到会话层 */
function UnauthorizedBridge(): null {
  const { onUnauthorized } = useSession()
  useEffect(() => {
    setUnauthorizedHandler(onUnauthorized)
  }, [onUnauthorized])
  return null
}

function AppRoutes(): JSX.Element {
  return (
    <>
      <UnauthorizedBridge />
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/"
          element={
            <RequireAuth>
              <DashboardPage />
            </RequireAuth>
          }
        />
        <Route
          path="/runs/new"
          element={
            <RequireAuth>
              <NewRunPage />
            </RequireAuth>
          }
        />
        <Route
          path="/runs/:runId"
          element={
            <RequireAuth>
              <RunProgressPage />
            </RequireAuth>
          }
        />
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
