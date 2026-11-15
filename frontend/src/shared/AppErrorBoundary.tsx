/**
 * 顶层错误边界（E2E 四轮优化，2026-11-14）。
 *
 * ⚠️ 与 `ContentErrorBoundary` 的关系：
 *    - `ContentErrorBoundary` 只包**内容区**（AppShell 的 `<Outlet/>`），
 *      出错时用户仍能通过导航换页离开；
 *    - 本边界包住**整个路由树**（含 AppShell 自身、导航、守卫、未授权桥接）。
 *
 * 为什么必须有两个层级：此前一次真实白屏是 `ContentErrorBoundary` 之外、
 * Shell 层渲染抛错——单靠内容区边界挡不住，整棵 `<App/>` 被 React 卸载，
 * 浏览器只剩空 `<div id="root">`，连「退出」「导航」都点不到。
 * 顶层边界保证：**任何一层抛错，都有整页兜底**，且兜底里能给「返回登录 / 重新加载」。
 *
 * ⚠️ 兜底按钮不依赖可能已损坏的路由上下文：用 `window.location.assign`
 *    做整页跳转，避免在坏掉的应用树里调用 `navigate` 二次崩溃。
 */
import { Component, type ReactNode } from 'react'
import { useSession } from '@/auth/session'

function FatalFallback({ onRetry }: { onRetry: () => void }): JSX.Element {
  const { signOut } = useSession()

  const backToLogin = (): void => {
    // ⚠️ 清会话 + 整页跳转，不依赖可能已损坏的 Router 上下文。
    signOut()
    window.location.assign('/login')
  }

  return (
    <div role="alert" className="fatal-error">
      <div className="marked" style={{ maxWidth: 560, margin: '20vh auto var(--s-4)' }}>
        <p className="marked__label">页面出错了</p>
        <p style={{ margin: 0 }}>
          这个应用遇到了一个无法在此页面恢复的错误。你可以重新加载，或返回登录页。
          若反复出现，请把这句话反馈给开发。
        </p>
        <div className="btn-row" style={{ marginTop: 'var(--s-3)' }}>
          <button type="button" className="btn" onClick={backToLogin}>
            返回登录页
          </button>
          <button
            type="button"
            className="btn btn--quiet"
            onClick={() => window.location.reload()}
          >
            重新加载
          </button>
          <button type="button" className="btn btn--quiet" onClick={onRetry}>
            重试本页
          </button>
        </div>
      </div>
    </div>
  )
}

interface Props {
  /** 路由变化时重置错误态，否则新页面会被扣在错误态里。 */
  resetKey: string
  children: ReactNode
}

interface State {
  error: Error | null
}

export class AppErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidUpdate(prev: Props): void {
    if (prev.resetKey !== this.props.resetKey && this.state.error !== null) {
      this.setState({ error: null })
    }
  }

  render(): ReactNode {
    if (this.state.error === null) return this.props.children
    return <FatalFallback onRetry={() => this.setState({ error: null })} />
  }
}
