/**
 * 全局未捕获错误安全网（对齐 frontend/src/shared/globalErrorHandlers.ts）。
 *
 * 背景：`main.tsx` 若只做 `createRoot().render()`，没有 window.onerror /
 * unhandledrejection 订阅，则未被 try/catch 兜住的 Promise 拒绝会：
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
