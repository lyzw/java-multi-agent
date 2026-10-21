/**
 * 可写配置行（P4-D15 / FE-D11 · C3 + C5 + C7）。
 *
 * ⛔ **本组件是 FE-02 §4 的落地**：「约束要落在『控件不存在』或
 * 『选项不可选』，而不是『选了会报错』」。具体表现为：
 *
 *   1. **审批矩阵**：下拉框**只列收紧方向**，放宽值根本不在选项中；
 *   2. **数值上限**：输入框给出 max=当前值，且提交按钮在放宽时被 disabled
 *      ——不是提交后弹错误；
 *   3. **变更理由**：输入框即必填，为空时提交按钮 disabled；
 *   4. **L1 锁定项**：本页根本**不渲染**它们（后端白名单外即不可写）。
 *
 * ⚠️ **前端校验不是安全边界**：真正的强制在后端
 * （`ConfigTighteningPolicy`），任何绕过 UI 的请求都会被 409 拒绝。
 * 这里做的是**提前收敛**，让用户在提交前就看到约束，而不是提交后挨骂。
 */

import { useState } from 'react'
import type { ConfigOverrideItem } from '@/api/types'
import {
  allowedApprovalLevels,
  canSubmit,
  isNumericTightening,
  isReasonValid,
  parseApprovalMatrix,
  formatApprovalMatrix,
  type ApprovalLevel,
  type MatrixKey,
} from '@/features/config/tightening'

const LEVEL_LABELS: Record<ApprovalLevel, string> = {
  NOT_REQUIRED: '不需要审批',
  OPTIONAL: '可选审批',
  REQUIRED: '必须审批',
}

/** 依据收紧方向给出人类可读的约束说明。 */
function constraintHint(item: ConfigOverrideItem): string {
  switch (item.tightening) {
    case 'TIGHTEN':
      return '只能调小。放宽需要重新走变更评审。'
    case 'TIGHTEN_RATCHET':
      return '只能收紧：下拉框里只列比当前更严的方向。'
    default:
      return '可直接修改。'
  }
}

export function ConfigOverrideRow({
  item,
  busy,
  onSubmit,
  onClear,
}: {
  item: ConfigOverrideItem
  busy: boolean
  onSubmit: (key: string, value: string, reason: string) => void
  onClear: (key: string) => void
}): JSX.Element {
  const [editing, setEditing] = useState(false)
  const [reason, setReason] = useState('')
  const [matrix, setMatrix] = useState<Partial<Record<MatrixKey, ApprovalLevel>>>({})

  const current = item.effectiveValue ?? item.declaredValue ?? ''
  const overridden = item.source === 'db_override'

  // 审批矩阵的初始值来自**当前生效值**（可能是 yml 的花括号形态）
  const currentLevels = parseApprovalMatrix(current)
  const isMatrix = item.tightening === 'TIGHTEN_RATCHET'

  const [nextValue, setNextValue] = useState(current)
  const nextText = isMatrix ? formatApprovalMatrix(matrix) : nextValue
  const submittable = canSubmit(item.tightening, current, nextText, reason)

  function openEditor(): void {
    setNextValue(current)
    setMatrix(currentLevels)
    setReason('')
    setEditing(true)
  }

  function submit(): void {
    if (!submittable) return
    onSubmit(item.key, isMatrix ? formatApprovalMatrix(matrix) : nextValue, reason)
    setEditing(false)
  }

  return (
    <tr data-testid={`override-row-${item.key}`}>
      <th scope="row">
        <code>{item.key}</code>
        {overridden && (
          <span className="chip chip--settled" data-testid={`source-${item.key}`}>
            已按租户覆盖
          </span>
        )}
      </th>
      <td>
        <code data-testid={`effective-${item.key}`}>{current || '—'}</code>
        {item.declaredValue !== null && overridden && (
          <div className="muted">
            声明值 <code>{item.declaredValue}</code>
          </div>
        )}
      </td>
      <td>
        <span className="muted">{constraintHint(item)}</span>
        {item.reason && (
          <div className="muted" data-testid={`reason-${item.key}`}>
            上次理由：{item.reason}
          </div>
        )}
      </td>
      <td>
        {!editing ? (
          <div className="btn-row">
            <button type="button" onClick={openEditor}>
              调整
            </button>
            {overridden && (
              <button
                type="button"
                onClick={() => onClear(item.key)}
                disabled={busy}
              >
                恢复默认
              </button>
            )}
          </div>
        ) : (
          <div className="editor-panel">
            {isMatrix ? (
              /* ⛔ FE-CFG-01：只列收紧方向，放宽值不在选项中 */
              <div data-testid={`matrix-editor-${item.key}`}>
                {(Object.keys(currentLevels) as MatrixKey[]).map((key) => (
                  <label key={key} className="field">
                    <span>{key}</span>
                    <select
                      aria-label={`${item.key} ${key}`}
                      value={matrix[key] ?? currentLevels[key]}
                      onChange={(e) =>
                        setMatrix((m) => ({
                          ...m,
                          [key]: e.target.value as ApprovalLevel,
                        }))
                      }
                    >
                      {/* ⚠️ 只渲染 allowedApprovalLevels —— 放宽项不在 DOM 中 */}
                      {allowedApprovalLevels(
                        currentLevels[key] ?? 'NOT_REQUIRED',
                      ).map((level) => (
                        <option key={level} value={level}>
                          {LEVEL_LABELS[level]}
                        </option>
                      ))}
                    </select>
                  </label>
                ))}
              </div>
            ) : (
              <label className="field">
                <span>新值</span>
                <input
                  aria-label={`${item.key} 新值`}
                  value={nextValue}
                  onChange={(e) => setNextValue(e.target.value)}
                />
              </label>
            )}

            <label className="field">
              <span>变更理由（不少于 10 字）</span>
              <input
                aria-label={`${item.key} 变更理由`}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </label>

            {/* ⚠️ 数值类：放宽时**当场**说明为什么不能提交，而不是提交后报错 */}
            {item.tightening === 'TIGHTEN' &&
              nextValue.trim().length > 0 &&
              !isNumericTightening(current, nextValue) && (
                <p className="field__error" role="alert">
                  只能收紧：当前为 <code>{current}</code>，新值必须小于或等于它。
                </p>
              )}
            {!isReasonValid(reason) && (
              <p className="field__error" role="alert">
                变更理由不少于 10 字。
              </p>
            )}

            <div className="btn-row">
              <button
                type="button"
                onClick={submit}
                disabled={!submittable || busy}
                data-testid={`submit-${item.key}`}
              >
                {busy ? '提交中' : '提交变更'}
              </button>
              <button type="button" onClick={() => setEditing(false)}>
                取消
              </button>
            </div>
          </div>
        )}
      </td>
    </tr>
  )
}