/**
 * 全局未捕获错误安全网（E2E 四轮优化，2026-11-14）。
 *
 * 背景：此前 `main.tsx` 直接 `createRoot().render()`，没有任何
 * `window.onerror` / `unhandledrejection` 订阅。一个没被 try/catch 兜住的
 * Promise 拒绝（如某处 `void asyncFn()` 在 reject 路径无 `.catch`）会：
 *   1. 静默失败——UI 卡在「加载中」或损坏态，用户不知出错；
 *   2. 零遥测——生产环境完全不可观测。
 *
 * 本模块只做「安全网」：统一把两类事件交给 reporter（默认 console.error）。
 * ⚠️ handler 必须纯日志、不修改 UI 状态，避免二次崩溃。
 */
export interface GlobalErrorInfo {
  type: 'error' | 'rejection'
  error: unknown
}

export function installGlobalErrorHandlers(
  reporter: (info: GlobalErrorInfo) => void = console.error,
): () => void {
  const onError = (event: ErrorEvent): void => {
    reporter({ type: 'error', error: event.error ?? event.message })
  }
  const onRejection = (event: PromiseRejectionEvent): void => {
    reporter({ type: 'rejection', error: event.reason })
  }
  window.addEventListener('error', onError)
  window.addEventListener('unhandledrejection', onRejection)
  return () => {
    window.removeEventListener('error', onError)
    window.removeEventListener('unhandledrejection', onRejection)
  }
}
