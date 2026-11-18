/**
 * 顶层错误边界（对齐 frontend 的三层防护：顶层边界 + 内容区边界 + 全局 handler）。
 *
 * ⚠️ admin-console 用 createBrowserRouter（data router）：React Router 会吞掉
 *    路由树内的渲染错误并交给最近的 `errorElement`，普通组件式 ErrorBoundary
 *    挡不住。所以这里提供两层：
 *      1. `AppErrorBoundary`（组件式，class）—— 包住 <App/>，兜 ConfigProvider /
 *         SessionProvider / RouterProvider 自身等「路由之外」的渲染崩溃；
 *      2. `RouteErrorBoundary`（函数式，用 useRouteError）—— 挂到 router 根路由的
 *         errorElement，兜路由树内的渲染 / loader / action 错误。
 *
 * ⚠️ 兜底按钮不依赖可能已损坏的路由上下文：用 `window.location.assign` 做整页
 *    跳转，避免在坏掉的应用树里调用 navigate 二次崩溃。
 */
import { Component, type ErrorInfo, type ReactNode } from 'react'
import { useRouteError } from 'react-router-dom'
import { clearSession } from '@/auth/tokenStorage'
import { APP_BASE_PATH } from '@/config/constants'

/** 把任意 thrown 值安全地转成可展示的简短消息（不做字符串拼接展示 UUID 等标识）。 */
function describeError(error: unknown): string {
  if (error instanceof Error) return error.message
  return '未知错误'
}

function FatalErrorView({
  onBackToLogin,
  onReload,
  onRetry,
  detail,
}: {
  onBackToLogin: () => void
  onReload: () => void
  onRetry?: () => void
  detail?: string
}): JSX.Element {
  return (
    <div role="alert" style={{ maxWidth: 560, margin: '20vh auto', padding: '0 16px' }}>
      <h2 style={{ margin: '0 0 12px' }}>页面出错了</h2>
      <p>
        控制台遇到了一个无法在此页面恢复的错误。你可以重新加载，或返回登录页。
        若反复出现，请把下面的信息反馈给开发。
      </p>
      {detail !== undefined && detail.length > 0 && (
        <details style={{ margin: '12px 0' }}>
          <summary>错误详情</summary>
          <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{detail}</pre>
        </details>
      )}
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginTop: 16 }}>
        <button type="button" onClick={onBackToLogin}>
          返回登录页
        </button>
        <button type="button" onClick={onReload}>
          重新加载
        </button>
        {onRetry !== undefined && (
          <button type="button" onClick={onRetry}>
            重试本页
          </button>
        )}
      </div>
    </div>
  )
}

function backToLogin(): void {
  clearSession()
  window.location.assign(`${APP_BASE_PATH}/login`)
}

interface Props {
  children: ReactNode
}

interface State {
  error: Error | null
}

/** 组件式顶层边界：兜路由之外的渲染崩溃。 */
export class AppErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('admin-console fatal render error', error, info.componentStack)
  }

  render(): ReactNode {
    if (this.state.error === null) return this.props.children
    return (
      <FatalErrorView
        onBackToLogin={backToLogin}
        onReload={() => window.location.reload()}
        onRetry={() => this.setState({ error: null })}
        detail={describeError(this.state.error)}
      />
    )
  }
}

/** data router 的根 errorElement：兜路由树内的渲染 / loader / action 错误。 */
export function RouteErrorBoundary(): JSX.Element {
  const error = useRouteError()
  return (
    <FatalErrorView
      onBackToLogin={backToLogin}
      onReload={() => window.location.reload()}
      detail={describeError(error)}
    />
  )
}
