/**
 * 表单字段（FE-D1 §6.3）
 *
 * ⚠️ 本组件**只渲染白名单字段**。
 *    禁用字段（姓名/学校/班级/身份证/手机号）**根本不存在输入框**，
 *    而非「校验后拒绝」——后者可能因绕过校验而泄漏（PIPL）。
 */

import type { Violation } from '@/features/compliance/scan'

export interface FieldProps {
  name: string
  label: string
  value: string
  onChange: (value: string) => void
  violations?: Violation[]
  hint?: string
  required?: boolean
  multiline?: boolean
}

export function Field({
  name,
  label,
  value,
  onChange,
  violations,
  hint,
  required = false,
  multiline = false,
}: FieldProps): JSX.Element {
  const hasViolation = violations !== undefined && violations.length > 0
  const errorId = `${name}-error`
  const hintId = `${name}-hint`

  const describedBy =
    [hasViolation ? errorId : null, hint !== undefined ? hintId : null]
      .filter(Boolean)
      .join(' ') || undefined

  return (
    <div className="field">
      <label htmlFor={name}>
        {label}
        {required ? <span aria-hidden="true"> *</span> : null}
      </label>

      {multiline ? (
        <textarea
          id={name}
          name={name}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={hasViolation}
          aria-describedby={describedBy}
          rows={4}
        />
      ) : (
        <input
          id={name}
          name={name}
          type="text"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={hasViolation}
          aria-describedby={describedBy}
        />
      )}

      {hint !== undefined ? (
        <p id={hintId} className="field__hint">
          {hint}
        </p>
      ) : null}

      {hasViolation ? (
        <ul id={errorId} role="alert" className="field__errors">
          {violations.map((violation) => (
            <li key={violation.kind}>{violation.message}</li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
