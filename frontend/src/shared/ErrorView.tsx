/**
 * 统一错误展示（FE-01 §4.3 S2：失败必须显示可区分原因）
 */

import type { NormalizedError } from '@/api/errors'

export function ErrorView({
  error,
  onRetry,
}: {
  error: NormalizedError
  onRetry?: () => void
}): JSX.Element {
  return (
    <div role="alert" className="error-view">
      <p className="error-view__message">{error.message}</p>
      {error.correlationId !== undefined ? (
        <p className="error-view__hint">
          排查编号：<code>{error.correlationId}</code>
        </p>
      ) : null}
      {onRetry !== undefined ? (
        <button type="button" onClick={onRetry}>
          重试
        </button>
      ) : null}
    </div>
  )
}

/**
 * 「未开放」提示（FE-01 §4.3 S4）
 *
 * ⛔ 必须区分「接口未开放」与「确实没有数据」——
 *    否则用户以为系统坏了。
 */
export function NotAvailable({
  feature,
  detail,
}: {
  feature: string
  detail?: string
}): JSX.Element {
  return (
    <div className="not-available">
      <p>{feature}功能尚未开放</p>
      {detail !== undefined ? <p className="muted">{detail}</p> : null}
    </div>
  )
}
