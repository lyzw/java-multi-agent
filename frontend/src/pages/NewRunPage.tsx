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

import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type FormEvent,
} from 'react'
import { useNavigate } from 'react-router-dom'
import { createRun, ApiError, getDictionaries, listAgents } from '@/api/client'
import type { AgentItem } from '@/api/types'
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
 * 默认 Agent（环境变量注入）。
 *
 * ⚠️ 这里**只作预选**：本租户实际有哪些可用 Agent 由 `GET /api/agents` 决定
 * （2026-10-28 端到端实测：写死 'default-agent' 时，提交恒得 404
 * 「该 Agent 尚无可用的已发布版本」——用户看到的是一个怎么填都提交不了的表单）。
 * 列表可用时以列表为准；不可用时才回落到此常量，让表单仍可提交（不阻断）。
 */
const DEFAULT_AGENT_ID = import.meta.env['VITE_AGENT_ID'] ?? 'default-agent'

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

/**
 * 必填字段清单（顺序 = 表单顺序）。
 *
 * ⚠️ 为什么集中列一份：缺项提示要按**表单顺序**列出，
 *    焦点跳转也要按同一个顺序找第一个空字段 ——
 *    两处各写一份顺序，早晚会对不上（用户被告知缺 A，却被送到 B）。
 *    `id` 同时是 Field 的 name，也是 DOM 里的 input id。
 */
interface RequiredField {
  id: 'studentId' | 'questionText' | 'studentSolution' | 'correctAnswer'
  label: string
}

const REQUIRED_FIELDS: readonly RequiredField[] = [
  { id: 'studentId', label: '学生匿名编号' },
  { id: 'questionText', label: '题目内容' },
  { id: 'studentSolution', label: '我的解题过程' },
  { id: 'correctAnswer', label: '已知正确答案' },
]

export function NewRunPage(): JSX.Element {
  const navigate = useNavigate()
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [violations, setViolations] = useState<Record<string, Violation[]>>({})
  const [error, setError] = useState<NormalizedError | null>(null)
  const [submitting, setSubmitting] = useState(false)

  /** 表单容器：定位字段用（字段 id = name） */
  const formRef = useRef<HTMLFormElement>(null)
  /** 错误摘要：提交失败后把焦点送过来 */
  const errorRef = useRef<HTMLDivElement>(null)

  // ⚠️ B3：运行时从后端拉取业务词表，作为下拉单一真相源；失败回退硬编码兜底。
  const [subjects, setSubjects] = useState<string[]>([...FALLBACK_SUBJECTS])
  const [grades, setGrades] = useState<string[]>([...FALLBACK_GRADES])

  /**
   * 本租户可用的已发布 Agent（`GET /api/agents`）。
   *
   * ⚠️ 列表拿不到时**不阻断表单**：仍用 `DEFAULT_AGENT_ID` 提交，
   * 由后端的 404 说明真实原因 —— 比让用户面对一个没有下拉框、
   * 也不知道能不能提交的表单要好。
   */
  const [agents, setAgents] = useState<AgentItem[]>([])
  const [agentId, setAgentId] = useState<string>(DEFAULT_AGENT_ID)

  useEffect(() => {
    let cancelled = false
    listAgents({ limit: 50 })
      .then((page) => {
        if (cancelled) return
        const published = page.items.filter((a) => a.latestPublishedAt !== null)
        setAgents(published)
        // 预选：环境变量指定的仍在册就保留它，否则取列表首个（后端按最新发布排序）
        setAgentId((prev) =>
          published.some((a) => a.agentId === prev) ? prev : published[0]?.agentId ?? prev,
        )
      })
      .catch(() => {
        /* 端点不可达 → 保持默认 agentId，不阻断表单 */
      })
    return () => {
      cancelled = true
    }
  }, [])

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

  /**
   * 还未填写的必填项（2026-10-24）。
   *
   * ⚠️ 为什么补这个：提交按钮在未填齐时是**不可用**的（这是有意的防线，
   *    pages.test.tsx 锁死了该行为），但一个没有解释的不可用按钮
   *    对用户就是"坏了"。把缺什么列出来，它才从谜语变成指引。
   *
   * ⚠️ 顺序来自 REQUIRED_FIELDS（= 表单顺序），
   *    焦点跳转用的是同一份清单，两处不会各指一个字段。
   */
  const missingFields = useMemo(
    () => REQUIRED_FIELDS.filter((field) => form[field.id].trim().length === 0),
    [form],
  )

  const isComplete = missingFields.length === 0

  /**
   * 「不可用」的视觉补偿（2026-10-24）。
   *
   * ⚠️ 为什么需要内联样式：`.btn:disabled` 的样式挂在**原生禁用**上，
   *    而按钮改用 aria-disabled（为了不夺走焦点），
   *    于是「看起来不可用」得自己补 —— opacity 与 .btn:disabled 对齐。
   *    提交中（aria-busy）时不设 cursor：让样式层的 progress 规则生效。
   *
   * ⛔ 建议后续在样式层补 `.btn[aria-disabled='true']` 收掉这段内联样式
   *    （本轮不改 CSS，故先用内联兜住）。
   */
  const unavailableStyle: CSSProperties | undefined = !isComplete
    ? { opacity: 0.4, cursor: 'not-allowed' }
    : submitting
      ? { opacity: 0.4 }
      : undefined

  /**
   * 把焦点送到第一个未填字段。
   *
   * ⚠️ 为什么必须自己做：按钮改成 aria-disabled（而非 HTML disabled）后，
   *    点它不会触发浏览器的"禁用控件不可聚焦"，焦点仍停在按钮上，
   *    键盘用户不知道该去改哪个框 —— 这里直接把他送到第一个空字段。
   */
  const focusFirstMissingField = (): void => {
    const root = formRef.current
    if (root === null) return
    for (const field of missingFields) {
      const el = root.querySelector<HTMLElement>(`#${field.id}`)
      if (el !== null) {
        el.focus()
        return
      }
    }
  }

  /**
   * 提交失败后把焦点移到错误摘要。
   *
   * ⚠️ 为什么：错误摘要此前渲染在表单**外面**、页面最底部，
   *    视口多半看不到，焦点也不会过去 ——
   *    读屏用户与键盘用户都收不到"提交失败"这个事实。
   */
  useEffect(() => {
    if (error === null) return
    errorRef.current?.focus()
  }, [error])

  const handleSubmit = async (event: FormEvent): Promise<void> => {
    event.preventDefault()

    // ⛔ 连点保护：按钮只用 aria-disabled（为了保住焦点），
    //    没有原生禁用兜底，这里必须自己挡住重复提交。
    if (submitting) return

    setError(null)

    // ⛔ 缺项防线：按钮不再原生禁用，缺项拦截的责任落到这里 ——
    //    否则空表单会被发给后端。
    if (!isComplete) {
      focusFirstMissingField()
      return
    }

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
      const idempotencyKey = resolveIdempotencyKey({ agentId, input })
      const response = await createRun({
        idempotencyKey,
        agentId,
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
        {/*
          ⚠️ noValidate：原生 required 校验的提示是浏览器的（且不可定制、不可翻译一致），
            缺项由本页自己的「还有 N 项必填未填」承担 —— 单一真相源。
        */}
        <form ref={formRef} onSubmit={(e) => void handleSubmit(e)} noValidate>
          <Field
            name="studentId"
            label="学生匿名编号"
            required
            value={form.studentId}
            onChange={update('studentId')}
            violations={violations['studentId']}
            hint="请填写平台生成的匿名编号，不要填姓名"
          />

          {/*
            ⚠️ 只有列到 Agent 才渲染这个下拉：列表为空/不可达时表单保持原样，
            提交仍带默认 agentId —— 由后端的 404 说明真实原因，
            比凭空长出一个只有唯一选项的下拉更诚实。
          */}
          {agents.length > 0 ? (
            <div className="field">
              <label htmlFor="agentId">辅导 Agent</label>
              <select
                id="agentId"
                value={agentId}
                onChange={(e) => setAgentId(e.target.value)}
              >
                {agents.map((a) => (
                  <option key={a.agentId} value={a.agentId}>
                    {a.name ?? a.agentId}（v{a.latestVersionNumber}）
                  </option>
                ))}
              </select>
              <p className="field__hint">
                切换 Agent 只影响<b>接下来</b>发起的分析；已发出的分析继续用它开始时的那份配置。
              </p>
            </div>
          ) : (
            // ⚠️ 没有可用 Agent 时显示警告
            <div className="marked marked--calm">
              <p className="marked__label">没有可用的 Agent</p>
              <p style={{ margin: 0 }}>
                当前租户下没有已发布的 Agent。请联系管理员创建并发布 Agent 后再发起辅导。
              </p>
            </div>
          )}

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

          {/*
            ⚠️ 错误摘要放在**表单内、按钮上方**（2026-10-24）：
                此前它渲染在 Panel 之外、页面最底部 ——
                提交失败时视口里可能什么都看不到，焦点也不会过去。
          */}
          {error !== null ? (
            <div ref={errorRef} tabIndex={-1} style={{ marginBottom: 'var(--s-3)' }}>
              <ErrorView error={error} />
            </div>
          ) : null}

          {/*
            ⚠️ 缺项提示放在**按钮上方**并做成 role="status"：
                原先它在按钮下方、没有 live region，通常还在视口之外 ——
                读屏用户永远走不到提交按钮，也就听不到"还有 N 项必填未填"。
                按钮用 aria-describedby 指回这里，形成"按钮 ↔ 缺什么"的闭环。
          */}
          {missingFields.length > 0 ? (
            <p
              id="missing-fields"
              role="status"
              className="field__hint"
              style={{ marginBottom: 'var(--s-3)' }}
            >
              还有 {missingFields.length} 项必填未填：
              {missingFields.map((field) => field.label).join('、')}
            </p>
          ) : null}

          <div className="btn-row">
            {/*
              ⚠️ 用 aria-disabled 而**不是** HTML disabled（2026-10-24）：
                disabled 的按钮不可聚焦 —— 点下去的瞬间焦点掉回 body，
                键盘用户得从页首重新 Tab，读屏用户更是永远走不到这个按钮，
                于是"还差几项没填"这条提示就成了永远听不到的话。
                aria-disabled 保留可聚焦性，语义由 ARIA 承担。
            */}
            <button
              type="submit"
              className="btn btn--lg"
              aria-disabled={submitting || !isComplete}
              aria-busy={submitting}
              aria-describedby={missingFields.length > 0 ? 'missing-fields' : undefined}
              style={unavailableStyle}
            >
              <Icon name="arrow" />
              {submitting ? '提交中…' : '开始分析'}
            </button>
          </div>
        </form>
      </Panel>
    </>
  )
}
