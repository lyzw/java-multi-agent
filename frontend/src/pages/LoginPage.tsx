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
      // ⚠️ 把后端回带的**权威** tenantId / principalId 存入会话（2026-10-22）：
      //    RAG 管理面需要它们来构造 X-Tenant-Id 头与审核留痕的「决定人」。
      //    后端认证层本就持有该租户（AuthResult.tenantId），此前未透出，
      //    导致用户进入 RAG 管理页时只能手填 UUID 且无从填对。
      //    ⚠️ 优先用**后端回带值**；后端未回带时才回落到用户输入的 tenantId
      //    （兼容旧后端），但绝不因此报错中断登录。
      // ⚠️ B1（2026-10-23）：登录响应现含 roles，透传给会话层用于导航显隐。
      //    旧后端未回带时回落为空数组（不影响登录，安全判定仍由后端执行时重授权）。
      signIn(
        response.accessToken,
        response.expiresIn,
        response.tenantId ?? tenantId,
        response.principalId ?? null,
        response.roles ?? [],
      )
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
      <div className="login__panel">
        <h1 className="login__brand">错题归因辅导</h1>
        <p className="login__lead">
          用学校发的账号登录。
          <br />
          我们只采集匿名编号、年级、学科和薄弱点，不收姓名、学校、班级。
        </p>

        {expiredNotice ? (
          <p role="status" className="marked marked--calm">
            <span className="marked__label" style={{ color: 'var(--ink-2)' }}>
              会话已结束
            </span>
            重新登录即可继续
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
              aria-invalid={error !== null}
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
              aria-invalid={error !== null}
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
              aria-invalid={error !== null}
              required
            />
          </div>

          {error !== null ? <ErrorView error={error} /> : null}

          <button type="submit" className="btn" disabled={submitting}>
            {submitting ? '登录中…' : '登录'}
          </button>
        </form>

        {/*
          ⛔ 刻意不提供：忘记密码、注册、多租户切换
          理由见文件头注释，非遗漏。
        */}
      </div>
    </main>
  )
}
