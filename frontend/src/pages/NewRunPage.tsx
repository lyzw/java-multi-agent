/**
 * 发起辅导页（FE-D1 F2；FE-01 P3）
 *
 * ⛔ 合规红线（FE-D1 §6.3）：
 *   S-1 禁用字段（姓名/学校/班级/身份证/手机号）**根本不存在输入框**
 *   S-2 学生标识固定提示「匿名编号，不要填姓名」
 *   S-3 预检命中 → **阻断提交 + 高亮**，不发请求
 *   S-4 受控数据不落 localStorage
 *
 * ⛔ 幂等键（FE-D1 §5）：失败重试复用同一 key；表单内容变更才换新。
 *    否则重复创建 Run → record_weakness 重复写入 = Sev-0。
 */

import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { createRun, ApiError, getDictionaries } from '@/api/client'
import { isIdempotencyConflict, type NormalizedError } from '@/api/errors'
import {
  resolveIdempotencyKey,
  clearIdempotencyKey,
  discardIdempotencyKey,
} from '@/features/run/idempotency'
import { scanForm, hasViolation, type Violation } from '@/features/compliance/scan'
import { Field } from '@/shared/Field'
import { ErrorView } from '@/shared/ErrorView'
import { PageHead, Panel } from '@/components/ui'
import { Icon } from '@/components/Icon'

/**
 * ⚠️ agentId 为占位配置。
 * 后端无 Agent 管理接口（G2），此处由环境变量注入。
 * 真实多 Agent 切换需后端补 GET /api/agents。
 */
const AGENT_ID = import.meta.env['VITE_AGENT_ID'] ?? 'default-agent'

/**
 * ⚠️ B3：学科/年级改为后端 `/api/reference` 词表单一真相源（见 getDictionaries）。
 *    此处为**离线兜底**常量——端点未就绪或不可达时表单仍可用，不阻断提交。
 */
const FALLBACK_SUBJECTS = ['数学', '物理', '化学', '生物', '语文', '英语'] as const
const FALLBACK_GRADES = ['初一', '初二', '初三'] as const

interface FormState {
  studentId: string
  subject: string
  grade: string
  questionText: string
  studentSolution: string
  correctAnswer: string
}

const EMPTY_FORM: FormState = {
  studentId: '',
  subject: FALLBACK_SUBJECTS[0],
  grade: FALLBACK_GRADES[0],
  questionText: '',
  studentSolution: '',
  correctAnswer: '',
}

export function NewRunPage(): JSX.Element {
  const navigate = useNavigate()
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [violations, setViolations] = useState<Record<string, Violation[]>>({})
  const [error, setError] = useState<NormalizedError | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // ⚠️ B3：运行时从后端拉取业务词表，作为下拉单一真相源；失败回退硬编码兜底。
  const [subjects, setSubjects] = useState<string[]>([...FALLBACK_SUBJECTS])
  const [grades, setGrades] = useState<string[]>([...FALLBACK_GRADES])

  useEffect(() => {
    let cancelled = false
    getDictionaries()
      .then((d) => {
        if (cancelled) return
        if (d.subjects.length > 0) setSubjects(d.subjects)
        if (d.grades.length > 0) setGrades(d.grades)
      })
      .catch(() => {
        /* 端点不可达 → 保留 FALLBACK，不阻断表单 */
      })
    return () => {
      cancelled = true
    }
  }, [])

  const update = (name: keyof FormState) => (value: string): void => {
    setForm((prev) => ({ ...prev, [name]: value }))
  }

  /** 仅文本字段需预检；枚举字段无自由输入 */
  const textFields = useMemo(
    () => ({
      studentId: form.studentId,
      questionText: form.questionText,
      studentSolution: form.studentSolution,
      correctAnswer: form.correctAnswer,
    }),
    [form.studentId, form.questionText, form.studentSolution, form.correctAnswer],
  )

  const isComplete = useMemo(
    () =>
      form.studentId.trim().length > 0 &&
      form.questionText.trim().length > 0 &&
      form.studentSolution.trim().length > 0 &&
      form.correctAnswer.trim().length > 0,
    [form],
  )

  const handleSubmit = async (event: FormEvent): Promise<void> => {
    event.preventDefault()
    setError(null)

    // ⛔ S-3 预检先行：命中即阻断，不发请求
    const found = scanForm(textFields)
    setViolations(found)
    if (hasViolation(found)) {
      setError({
        status: 400,
        code: 'INVALID_REQUEST',
        message: '提交内容包含不可采集的信息，请修改后重试',
        fieldErrors: {},
      })
      return
    }

    setSubmitting(true)
    const input: Record<string, unknown> = {
      studentId: form.studentId.trim(),
      subject: form.subject,
      grade: form.grade,
      questionText: form.questionText.trim(),
      studentSolution: form.studentSolution.trim(),
      correctAnswer: form.correctAnswer.trim(),
    }

    try {
      const idempotencyKey = resolveIdempotencyKey({ agentId: AGENT_ID, input })
      const response = await createRun({
        idempotencyKey,
        agentId: AGENT_ID,
        input,
      })
      clearIdempotencyKey()
      navigate(`/runs/${response.runId}`, { replace: true })
    } catch (cause) {
      if (cause instanceof ApiError) {
        // ⛔ 409 时清除 key 但**不自动换 key 重试** —— 可能造成重复 Run
        if (isIdempotencyConflict(cause.normalized)) {
          discardIdempotencyKey()
        }
        setError(cause.normalized)
      } else {
        setError({
          status: 0,
          code: 'UNKNOWN',
          message: '提交失败，请稍后重试',
          fieldErrors: {},
        })
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      {/*
        ⚠️ 这里曾是 <main className="new-run"> ——
        AppShell 已渲染 <main className="main">，main 嵌套 main
        违反 HTML 规范（一个文档只有一个主内容区）。
        已按 FE-D3 §3.1 迁移为 main__head 结构（F-16）。
      */}
      <PageHead
        title="发起辅导"
        lead="请提交一道错题。我们只采集随机匿名编号、年级、学科与解题过程，不会收集姓名、学校、班级等身份信息。"
      />

      <Panel title="错题信息" icon="file">
        <form onSubmit={(e) => void handleSubmit(e)} noValidate>
          <Field
            name="studentId"
            label="学生匿名编号"
            required
            value={form.studentId}
            onChange={update('studentId')}
            violations={violations['studentId']}
            hint="请填写平台生成的匿名编号，不要填姓名"
          />

          <div className="toolbar">
            <div className="field" style={{ minWidth: 160 }}>
              <label htmlFor="subject">
                学科<span aria-hidden="true"> *</span>
              </label>
              <select
                id="subject"
                name="subject"
                value={form.subject}
                onChange={(e) => update('subject')(e.target.value)}
              >
                {subjects.map((item) => (
                  <option key={item} value={item}>
                    {item}
                  </option>
                ))}
              </select>
            </div>

            <div className="field" style={{ minWidth: 160 }}>
              <label htmlFor="grade">
                年级<span aria-hidden="true"> *</span>
              </label>
              <select
                id="grade"
                name="grade"
                value={form.grade}
                onChange={(e) => update('grade')(e.target.value)}
              >
                {grades.map((item) => (
                  <option key={item} value={item}>
                    {item}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <Field
            name="questionText"
            label="题目内容"
            required
            multiline
            value={form.questionText}
            onChange={update('questionText')}
            violations={violations['questionText']}
          />

          <Field
            name="studentSolution"
            label="我的解题过程"
            required
            multiline
            value={form.studentSolution}
            onChange={update('studentSolution')}
            violations={violations['studentSolution']}
          />

          <Field
            name="correctAnswer"
            label="已知正确答案"
            required
            multiline
            value={form.correctAnswer}
            onChange={update('correctAnswer')}
            violations={violations['correctAnswer']}
          />

          <div className="btn-row">
            <button type="submit" className="btn btn--lg" disabled={submitting || !isComplete}>
              <Icon name="arrow" />
              {submitting ? '提交中…' : '开始分析'}
            </button>
          </div>
        </form>
      </Panel>

      {error !== null ? <ErrorView error={error} /> : null}
    </>
  )
}
