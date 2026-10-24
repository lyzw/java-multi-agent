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
        {/*
          ⚠️ 星号改用既有的 .req（批改红）：它是**视觉**标记，必须 aria-hidden
            ——「必填」的语义由 input/textarea 上的 required + aria-required 承担。
            此前星号是裸文本且控件不带 required，
            读屏只会念出字段名，用户**听不出哪些是必填**。
        */}
        {required ? <span className="req" aria-hidden="true"> *</span> : null}
      </label>

      {multiline ? (
        <textarea
          id={name}
          name={name}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={hasViolation}
          aria-describedby={describedBy}
          required={required}
          aria-required={required}
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
          required={required}
          aria-required={required}
        />
      )}

      {hint !== undefined ? (
        <p id={hintId} className="field__hint">
          {hint}
        </p>
      ) : null}

      {hasViolation ? (
        <ul id={errorId} role="alert" className="field__errors">
          {/*
            ⚠️ key 必须含索引（F-20）：
            旧写法 `key={violation.kind}` 在同一 kind 命中多次时
            （如文本里出现**两个手机号**）会产生重复 key，
            React 据此复用节点 → 第二条违规提示不渲染，
            用户改完第一条仍被拒，反复试错。
          */}
          {violations.map((violation, index) => (
            <li key={`${violation.kind}-${index}`}>{violation.message}</li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
