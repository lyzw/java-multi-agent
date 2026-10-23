/**
 * 路由表（FE-D2 §1.1 / §2.3）
 *
 * 页面范围：P1–P19 中**可实施的部分**。
 * 未列入的页面（如 P11 家长视图）与其余同页按角色分支，未单独规划。
 *
 * ⚠️ 2026-10-23（B1）：RequireRole 现已按登录回带的 roles 做**真实判定**。
 *    学生侧页面（/、/runs、/runs/new、/runs/:runId、/profile、/rag）**不挂 area**，
 *    任何已登录用户可见；配置/治理/运维页挂 area，无角色则跳回工作台。
 */

import { useEffect, useState, type FormEvent } from 'react'
import { BrowserRouter, Routes, Route, Navigate, useNavigate } from 'react-router-dom'
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
import { RagSearchPage } from '@/pages/rag/RagSearchPage'
import { RagManagementPage } from '@/pages/rag/RagManagementPage'
import {
  ConfigOverviewPage,
  ConfigRuntimePage,
  ConfigToolsPage,
  ConfigCompliancePage,
  AgentsPage,
} from '@/pages/config/ConfigPages'
import {
  ApprovalsPage,
  ReconciliationPage,
  AuditPage,
} from '@/pages/governance/GovernancePages'
import { HealthPage } from '@/pages/ops/HealthPage'

/**
 * 学习画像查询页（B6 修死链：原 /profile/current 为无效路由）。
 *
 * ⚠️ 会话不含「当前学生」标识，故由用户按学号/编号查询。
 *    提交后跳转到 /profile/:studentId（ProfilePage 按后端 200+notFound 诚实呈现）。
 */
function ProfileLookup(): JSX.Element {
  const navigate = useNavigate()
  const [studentId, setStudentId] = useState('')

  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault()
    const id = studentId.trim()
    if (id.length === 0) return
    navigate(`/profile/${encodeURIComponent(id)}`, { replace: true })
  }

  return (
    <div className="main__head">
      <h1>学习画像</h1>
      <p className="main__lead">输入学生编号，查看其薄弱知识点与归因。</p>
      <form onSubmit={handleSubmit} noValidate style={{ marginTop: 'var(--s-4)' }}>
        <div className="field" style={{ maxWidth: 360 }}>
          <label htmlFor="lookup-student">学生编号</label>
          <input
            id="lookup-student"
            value={studentId}
            onChange={(e) => setStudentId(e.target.value)}
            placeholder="例如 S-2026-0001"
            autoComplete="off"
          />
        </div>
        <button type="submit" className="btn" disabled={studentId.trim().length === 0}>
          查看画像
        </button>
      </form>
    </div>
  )
}

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
          {/* ── 辅导（学生侧，不挂 area，任何已登录用户可见）── */}
          <Route path="/" element={<DashboardPage />} />
          <Route path="/runs" element={<RunSearchPage />} />
          <Route path="/runs/new" element={<NewRunPage />} />
          <Route path="/runs/:runId" element={<RunProgressPage />} />
          <Route path="/runs/:runId/result" element={<RunResultPage />} />
          <Route path="/profile" element={<ProfileLookup />} />
          <Route path="/profile/:studentId" element={<ProfilePage />} />
          <Route path="/rag" element={<RagSearchPage />} />
          {/* ⚠️ RAG 管理面（RAG-D10）：语料登记、校验审核、索引统计。
              与 /rag 检索面是**两个方向的闸门**，故单独成页。 */}
          <Route
            path="/rag/management"
            element={<RequireRole area="ragManagement"><RagManagementPage /></RequireRole>}
          />

          {/* ── 配置（F4，只读骨架）── */}
          <Route path="/config" element={<RequireRole area="config"><ConfigOverviewPage /></RequireRole>} />
          <Route path="/config/runtime" element={<RequireRole area="config"><ConfigRuntimePage /></RequireRole>} />
          <Route path="/config/tools" element={<RequireRole area="config"><ConfigToolsPage /></RequireRole>} />
          <Route path="/config/compliance" element={<RequireRole area="config"><ConfigCompliancePage /></RequireRole>} />
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