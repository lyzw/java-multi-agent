/**
 * 用户反馈按钮（P7-3「tenant 反馈按钮」+ P7-4「前端反馈埋点」）
 *
 * <p><b>为什么只有这四个动作</b>：四种取值与后端
 * `AgentMetricPort.FeedbackEvent.FeedbackKind` 逐字一致
 * （THUMB_UP / THUMB_DOWN / RETRY / ABANDON），
 * 且**每一个都是用户明确点出来的**——不靠推断、不靠启发式。
 *
 * <p>⚠️ **刻意不做「自动埋点」**：不根据「用户离开页面」推断 ABANDON，
 * 不根据「用户点了几次」推断 RETRY。理由有二：
 * <ol>
 *   <li>推断出来的信号会**污染指标口径**——「再辅导一道」是换题，不是重试；</li>
 *   <li>L3 本来就是**有噪信号**，再叠一层推断噪点就再也分不清真假了。</li>
 * </ol>
 *
 * <p>⚠️ **合规红线（C6/C7）**：本组件**不采集任何自由文本**，
 * 反馈内容只有枚举 kind；状态只存在内存里（useState），**不落 localStorage**。
 *
 * <p>⚠️ **前端不知道 traceId、也不传 tenantId**：
 * 服务端从已验签令牌派生租户、从 runId 解析会话标识。
 * 浏览器少知道一点，攻击面就少一分。
 */

import { useState } from 'react'
import { recordRunFeedback, ApiError } from '@/api/client'
import type { RunFeedbackKind } from '@/api/types'

interface Option {
  kind: RunFeedbackKind
  /** 按钮文字（面向初中生，短、口语）。 */
  label: string
  /** 说明文案：选中后才展示，避免一屏铺满解释。 */
  hint: string
}

/**
 * 四个动作的文案。
 *
 * ⚠️ 「再讲一次」= 用户希望换个讲法（RETRY），**不是**「换一道题」——
 *    换题由页面底部既有的「再辅导一道」入口承担，两者语义不可混。
 */
const OPTIONS: Option[] = [
  { kind: 'THUMB_UP', label: '有帮助', hint: '这次讲解对你有用。' },
  { kind: 'THUMB_DOWN', label: '没帮助', hint: '这次讲解没解决你的问题。' },
  { kind: 'RETRY', label: '再讲一次', hint: '你想让系统换个讲法再来一次。' },
  { kind: 'ABANDON', label: '先不看了', hint: '你中途放弃了这次辅导。' },
]

/** 未知错误的兜底文案（不编造具体原因）。 */
const FALLBACK_ERROR = '反馈没记上，请稍后再试。'

export interface RunFeedbackProps {
  /** 本次运行的标识（服务端据此解析会话标识）。 */
  runId: string
}

export function RunFeedback({ runId }: RunFeedbackProps): JSX.Element {
  const [submitted, setSubmitted] = useState<RunFeedbackKind | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = (kind: RunFeedbackKind): void => {
    // ⚠️ 提交中不再接收第二次点击：反馈是**累加**的事件流，
    //    连点会记成多条同义反馈，把「用户想法」变成「点击频率」。
    if (submitting) return
    setSubmitting(true)
    setError(null)
    recordRunFeedback(runId, kind)
      .then(() => {
        setSubmitted(kind)
        setSubmitting(false)
      })
      .catch((cause: unknown) => {
        setSubmitting(false)
        setError(
          cause instanceof ApiError ? cause.normalized.message : FALLBACK_ERROR,
        )
      })
  }

  const selected = OPTIONS.find((o) => o.kind === submitted)

  return (
    <section className="sheet" data-testid="run-feedback">
      <h2 className="sheet__title">这次辅导有用吗？</h2>
      <p className="muted">
        点一个就好。你的反馈只用来改进讲解质量，不会影响你的账号和成绩。
      </p>
      <div className="btn-row">
        {OPTIONS.map((option) => (
          <button
            key={option.kind}
            type="button"
            className="btn btn--quiet"
            disabled={submitting}
            aria-pressed={submitted === option.kind}
            onClick={() => submit(option.kind)}
          >
            {option.label}
          </button>
        ))}
      </div>

      {selected !== undefined ? (
        <div className="status status--success" role="status">
          <p className="status__label">已记录：{selected.label}</p>
          <p className="status__hint">{selected.hint}</p>
        </div>
      ) : null}

      {error !== null ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}
    </section>
  )
}
