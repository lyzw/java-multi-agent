/**
 * 登录页（FE-D1 F1；FE-01 P1）
 *
 * ⛔ L1 失败提示统一为「租户、账号或密码不正确」
 *    理由：后端所有失败路径耗时一致且不区分原因（D-001 §3.2）。
 *    前端若自行区分「账号不存在」即**重建了后端刻意消除的枚举面**。
 *
 * ⛔ 不做「忘记密码」/「注册」/「多租户切换」
 *    · 找回密码需邮箱/手机号，而 PIPL 要求我们**不采集**这些（D-001 §7）
 *    · 注册涉及监护人同意流程，本期只做受控发号
 *    · 多租户切换会扩大一次登录可访问的范围（架构上亦不存在）
 */

import { useState, type FormEvent } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { login, ApiError } from '@/api/client'
import type { NormalizedError } from '@/api/errors'
import { useSession } from '@/auth/session'
import { ErrorView } from '@/shared/ErrorView'

interface LocationState {
  from?: string
}

export function LoginPage(): JSX.Element {
  const { signIn, expiredNotice } = useSession()
  const navigate = useNavigate()
  const location = useLocation()

  const [tenantId, setTenantId] = useState('')
  const [loginName, setLoginName] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<NormalizedError | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async (event: FormEvent): Promise<void> => {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      const response = await login({ tenantId, loginName, password })
      signIn(response.accessToken, response.expiresIn)
      const state = location.state as LocationState | null
      navigate(state?.from ?? '/', { replace: true })
    } catch (cause) {
      if (cause instanceof ApiError) {
        setError(cause.normalized)
      } else {
        setError({
          status: 0,
          code: 'UNKNOWN',
          message: '登录失败，请稍后重试',
          fieldErrors: {},
        })
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login">
      <h1>错题归因辅导</h1>

      {expiredNotice ? (
        <p role="status" className="login__expired">
          登录已过期，请重新登录
        </p>
      ) : null}

      <form onSubmit={(e) => void handleSubmit(e)} noValidate>
        <div className="field">
          <label htmlFor="tenantId">租户标识</label>
          <input
            id="tenantId"
            name="tenantId"
            type="text"
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
            autoComplete="organization"
            required
          />
        </div>

        <div className="field">
          <label htmlFor="loginName">账号</label>
          <input
            id="loginName"
            name="loginName"
            type="text"
            value={loginName}
            onChange={(e) => setLoginName(e.target.value)}
            autoComplete="username"
            required
          />
        </div>

        <div className="field">
          <label htmlFor="password">密码</label>
          <input
            id="password"
            name="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </div>

        <button type="submit" disabled={submitting}>
          {submitting ? '登录中…' : '登录'}
        </button>
      </form>

      {error !== null ? <ErrorView error={error} /> : null}

      {/*
        ⛔ 刻意不提供：忘记密码、注册、多租户切换
        理由见文件头注释，非遗漏。
      */}
    </main>
  )
}
