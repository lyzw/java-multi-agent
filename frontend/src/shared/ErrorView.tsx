/**
 * 统一错误展示（FE-01 §4.3 S2：失败必须显示可区分原因）
 */

import type { NormalizedError } from '@/api/errors'

/**
 * 统一错误展示（FE-01 §4.3 S2：失败必须显示可区分原因）
 *
 * ⚠️ 2026-10-24：补渲染 fieldErrors。
 *    后端当前的归一化路径从不回字段级错误（见 api/errors.ts），
 *    所以这里平时是空列表；但登录表单已按「有字段错误才标
 *    aria-invalid」改造——**两边语义必须对齐**：
 *    哪天门真的开始回 fieldErrors，提示要能立刻显示出来，
 *    而不是又退化成一句笼统的「请求有误」。
 */
export function ErrorView({
  error,
  onRetry,
}: {
  error: NormalizedError
  onRetry?: () => void
}): JSX.Element {
  const fieldEntries = Object.entries(error.fieldErrors ?? {})
  return (
    <div role="alert" className="error-view">
      <p className="error-view__message">{error.message}</p>
      {fieldEntries.length > 0 ? (
        <ul className="field__errors">
          {fieldEntries.map(([field, message]) => (
            <li key={field}>{message}</li>
          ))}
        </ul>
      ) : null}
      {error.correlationId !== undefined ? (
        <p className="error-view__hint">
          排查编号：<code>{error.correlationId}</code>
        </p>
      ) : null}
      {onRetry !== undefined ? (
        <button type="button" className="btn btn--quiet" onClick={onRetry}>
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
