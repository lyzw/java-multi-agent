/**
 * 权限相关的前端统一处理（权限重构 · 前后端约定的一部分）。
 *
 * 约定：后端对「业务无权限」返回 **HTTP 403 + code=ACCESS_DENIED**，
 * 对「未登录」返回 **HTTP 401 + code=UNAUTHENTICATED**。
 * 拦截器（见 `client.ts`）已保证：
 *   · UNAUTHENTICATED → 清会话 + 跳登录；
 *   · ACCESS_DENIED   → **绝不跳登录**，原样抛出由页面分支处理。
 *
 * 本文件提供页面侧的统一落点：判断 + 跳无权限整页。
 */

import type { NormalizedError } from './errors'
import { isAccessDenied } from './errors'

export { isAccessDenied }

/**
 * 已登录但缺权限（code=ACCESS_DENIED）的统一落点。
 *
 * @param err 归一化错误（来自 ApiError.normalized）
 * @param navigate 传入则跳转到「无权限」整页（保留现场，可返回上一页）；
 *                 不传则由调用方用既有的 setError 呈现 Toast 式提示。
 */
export function notifyAccessDenied(
  err: NormalizedError,
  navigate?: (to: string) => void,
): void {
  if (navigate) {
    const qs = err.message ? `?reason=${encodeURIComponent(err.message)}` : ''
    navigate(`/no-permission${qs}`)
  }
}
