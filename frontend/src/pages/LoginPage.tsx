/**
 * 登录页（FE-D1 F1；FE-01 P1）
 *
 * ⛔ L1 失败提示统一为「租户、账号或密码不正确」
 *    理由：后端所有失败路径耗时一致且不区分原因（D-001 §3.2）。
 *    前端若自行区分「账号不存在」即**重建了后端刻意消除的枚举面**。
 *
 * ⛔ 不做「忘记密码」/「注册」/「多租户切换」
 *    · 找回密码需邮箱/手机号，而 PIPL 要求我们**不采集**这些（D-001 §7）
 *    · 注册涉及监护人同意流程，本期只做受控发号
 *    · 多租户切换会扩大一次登录可访问的范围（架构上亦不存在）
 *
 * ⚠️ 视觉改造（2026-10-31 · UI 现代化）
 *    登录页此前是一张「裸表单」：单栏、无品牌面、无层级，读起来像后台内部工具。
 *    现改为**双栏登录布局**——左栏讲清产品价值（批改纸的产品叙事），
 *    右栏是聚焦的表单卡；窄屏自动折叠为单栏。
 *    ⛔ 仍守「批改纸」纪律：不用彩色主按钮（主按钮仍为墨色）、不用阴影、
 *       批改红只留给错误 —— 现代化不等于换一套设计语言。
 */

import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { login, ApiError } from '@/api/client'
import type { NormalizedError } from '@/api/errors'
import { useSession } from '@/auth/session'
import { ErrorView } from '@/shared/ErrorView'
import { usePageTitle } from '@/shared/usePageTitle'
import { Icon } from '@/components/Icon'

interface LocationState {
  from?: string
}

/**
 * P2-1：记住上次租户标识（2026-11-17）。
 *
 * ⚠️ 存 **localStorage** 而非 sessionStorage：租户标识不是令牌，
 *    也不是任何凭据（它只是「哪个学校」的公开路由键），且本次改造的
 *    目标就是让学生**不必每次手填 36 位 UUID**。
 *    ⛔ 但绝不存密码：密码只在内存中停留，提交即清。
 */
const TENANT_HINT_KEY = 'agent.lastTenantId'

function readTenantHint(): string {
  try {
    return window.localStorage.getItem(TENANT_HINT_KEY) ?? ''
  } catch {
    return ''
  }
}

function writeTenantHint(value: string): void {
  try {
    if (value.trim() === '') {
      window.localStorage.removeItem(TENANT_HINT_KEY)
      return
    }
    window.localStorage.setItem(TENANT_HINT_KEY, value.trim())
  } catch {
    /* 隐私模式/存储禁用 → 记住租户只是便利项，失败不应影响登录 */
  }
}

/**
 * P2-2：连续失败计数与锁定倒计时（2026-11-17）。
 *
 * ⚠️ **为什么在前端计数、而不是让后端区分「密码错」与「账号被锁」**：
 *    后端所有失败路径**刻意返回完全相同的 401 且耗时一致**（D-001 §3.2），
 *    以消除账号枚举面。若为了提示「已被锁定」而让后端回 423，
 *    等于**重建了被刻意消除的枚举面**（可据此探测某账号是否存在）。
 *
 * ✅ 因此这里用**本浏览器的失败计数**给出**诚实但不泄露**的提示：
 *    「连续失败 N 次，账号可能被临时锁定（默认 15 分钟后自动解锁）」——
 *    措辞是「可能」，因为前端确实不知道服务端状态；
 *    同时给出倒计时，让学生**别再猛试**（越试越锁）。
 *
 * ⛔ 计数存 sessionStorage（关标签页即失效）：它不是账号状态，
 *    也不该跨设备累积成「这个账号到处被试」的画像。
 */
const LOGIN_FAIL_KEY = 'agent.loginFailures'

/** 后端默认锁定时长（分钟）—— 与 auth.lock-duration-minutes 一致。 */
const LOCK_MINUTES = 15
/** 连续失败多少次后开始提示可能已被锁定（后端阈值是 5 次）。 */
const WARN_AFTER_FAILURES = 3

interface FailRecord {
  count: number
  /** 首次失败时刻（epoch ms），用于估算解锁时间 */
  firstAt: number
}

function readFailures(): FailRecord {
  try {
    const raw = window.sessionStorage.getItem(LOGIN_FAIL_KEY)
    if (raw === null) return { count: 0, firstAt: 0 }
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return { count: 0, firstAt: 0 }
    const rec = parsed as Record<string, unknown>
    const count = typeof rec['count'] === 'number' ? rec['count'] : 0
    const firstAt = typeof rec['firstAt'] === 'number' ? rec['firstAt'] : 0
    return { count, firstAt }
  } catch {
    return { count: 0, firstAt: 0 }
  }
}

function writeFailures(rec: FailRecord): void {
  try {
    window.sessionStorage.setItem(LOGIN_FAIL_KEY, JSON.stringify(rec))
  } catch {
    /* 存储不可用 → 只是失去提示，不影响登录本身 */
  }
}

function clearFailures(): void {
  try {
    window.sessionStorage.removeItem(LOGIN_FAIL_KEY)
  } catch {
    /* 同上 */
  }
}

/**
 * P2-3：登录后回跳白名单（2026-11-17）。
 *
 * ⚠️ 此前是 `navigate(state?.from ?? '/')` —— **无白名单**。
 *    攻击者可构造 `/login` 链接带 `state.from = 'https://evil.example'`
 *    或任意站内路径（如某个已完成 Run 的进度页），
 *    登录后即被送到那里 —— 既能被用于钓鱼，也能把用户落到
 *    一个「早已完成的旧 run」页面（教师评审实测项 P2-3）。
 *
 * ✅ 现在只接受**以单个斜杠开头的站内相对路径**：
 *    · 必须以 '/' 开头（相对路径 'foo' 会被当相对路径拼接）
 *    · 不能以 '//' 开头（协议相对 URL → 外站）
 *    · 不能含 '\'（部分浏览器会把 '' 当 '/'，形成绕过）
 *    · 不能含控制字符
 */
export function safeRedirect(target: string | undefined): string {
  if (typeof target !== 'string' || target === '') return '/'
  if (!target.startsWith('/')) return '/'
  if (target.startsWith('//')) return '/'
  if (target.includes('\\')) return '/'
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f]/.test(target)) return '/'
  return target
}

/** 左栏产品叙事的三条价值点 —— 只讲产品做什么，不讲技术实现。 */
const VALUE_POINTS = [
  { icon: 'gauge', title: '看得到失分在哪', body: '按知识点拆解每道错题的失分原因，不只给一个分数。' },
  { icon: 'book', title: '补得上有章法', body: '从薄弱点出发排学习顺序，一步一步补，而不是刷题海。' },
  { icon: 'shield', title: '收得少、放心用', body: '只采集匿名编号、年级、学科和薄弱点，不收姓名、学校、班级。' },
] as const

export function LoginPage(): JSX.Element {
  const { signIn, expiredNotice } = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  // 登录页不用 PageHead（无壳、无页头组件），标题在此单独同步
  usePageTitle('登录')

  // ⚠️ P2-1：初始值取上次用过的租户（读一次即可，不必进 state 依赖）。
  const [tenantId, setTenantId] = useState(() => readTenantHint())
  const [loginName, setLoginName] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<NormalizedError | null>(null)
  const [submitting, setSubmitting] = useState(false)
  // ⚠️ P2-2：连续失败次数与剩余等待秒数（0 = 不显示倒计时）
  const [failCount, setFailCount] = useState(() => readFailures().count)
  const [lockSecondsLeft, setLockSecondsLeft] = useState(0)
  // 密码明文开关：⛔ 不做「记住我」——令牌寿命与合规口径见文件头注释
  const [passwordVisible, setPasswordVisible] = useState(false)

  // ⚠️ 表单完整性检查：所有字段都非空才算完整
  const isComplete = tenantId.trim().length > 0 && loginName.trim().length > 0 && password.length > 0

  /**
   * P2-2：锁定倒计时（秒级）。
   *
   * ⚠️ 只在「连续失败达到阈值」后才启动，且**每秒只重算一次**：
   *    倒计时是给学生的等待指引，不是装饰；无谓的高频渲染会拖慢登录页。
   *    倒计时归零后自动停止（不再每秒 setState）。
   */
  useEffect(() => {
    if (failCount < WARN_AFTER_FAILURES) {
      setLockSecondsLeft(0)
      return
    }
    const rec = readFailures()
    if (rec.count === 0 || rec.firstAt === 0) return
    const unlockAt = rec.firstAt + LOCK_MINUTES * 60 * 1000
    // tick 返回 true = 仍在倒计时（left>0）；false = 已归零。
    const tick = (): boolean => {
      const left = Math.max(0, Math.ceil((unlockAt - Date.now()) / 1000))
      setLockSecondsLeft(left)
      return left > 0
    }
    // ⚠️ 归零即停：兑现「倒计时结束后不再每秒 setState」的注释，
    //    避免定时器空转到 failCount 变化 / 组件卸载。
    if (!tick()) return
    const handle = window.setInterval(() => {
      if (!tick()) window.clearInterval(handle)
    }, 1000)
    return () => window.clearInterval(handle)
  }, [failCount])

  const handleSubmit = async (event: FormEvent): Promise<void> => {
    event.preventDefault()
    // ⛔ 缺项防线：按钮不再原生禁用，缺项拦截的责任落到这里
    if (!isComplete) {
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      const response = await login({ tenantId, loginName, password })
      // ⚠️ 把后端回带的**权威** tenantId / principalId 存入会话（2026-10-22）：
      //    RAG 管理面需要它们来构造 X-Tenant-Id 头与审核留痕的「决定人」。
      //    后端认证层本就持有该租户（AuthResult.tenantId），此前未透出，
      //    导致用户进入 RAG 管理页时只能手填 UUID 且无从填对。
      //    ⚠️ 优先用**后端回带值**；后端未回带时才回落到用户输入的 tenantId
      //    （兼容旧后端），但绝不因此报错中断登录。
      // ⚠️ B1（2026-10-23）：登录响应现含 roles，透传给会话层用于导航显隐。
      //    ⚠️ **必须原样透传 `response.roles`（可能是 undefined），不得用 `?? []`**：
      //        `undefined` = 后端**没有回带**该字段（老版本后端）→ 角色未知，
      //        会话层将回补 `/api/auth/roles`；
      //        `[]` = 后端回带了**空数组**（该账号确实无角色）→ 角色已知且为空。
      //    二者在 `?? []` 之后会**坍缩成同一个值**，
      //    于是「后端没回带」被误判成「你没有管理权限」——
      //    管理员会永久看不到管理菜单，且**没有任何提示**。
      signIn(
        response.accessToken,
        response.expiresIn,
        response.tenantId ?? tenantId,
        response.principalId ?? null,
        response.roles,
        // ⚠️ 身份改造（2026-11-17）：登录名＝学生用户名，studentId 由后端派生。
        //    这两个值只用于**展示**；写入身份后端仍按 principalId 重新查库派生。
        response.username ?? null,
        response.studentId ?? null,
      )
      // ⚠️ P2-1：记住租户，下次不必再手填 UUID。
      writeTenantHint(response.tenantId ?? tenantId)
      // ⚠️ P2-2：登录成功即清零失败计数。
      clearFailures()
      setFailCount(0)
      setLockSecondsLeft(0)
      const state = location.state as LocationState | null
      // ⚠️ P2-3：回跳走白名单，避免落到外站或陈旧页面。
      navigate(safeRedirect(state?.from), { replace: true })
    } catch (cause) {
      // ⚠️ P2-2：先记失败次数（成功登录会清零），再展示错误。
      const prev = readFailures()
      const next: FailRecord =
        prev.count === 0 ? { count: 1, firstAt: Date.now() } : { count: prev.count + 1, firstAt: prev.firstAt }
      writeFailures(next)
      setFailCount(next.count)
      if (cause instanceof ApiError) {
        setError(cause.normalized)
      } else {
        setError({
          status: 0,
          code: 'UNKNOWN',
          message: '登录失败，请稍后重试',
          fieldErrors: {},
        })
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login">
      <div className="login__grid">
        {/* ── 左栏：产品叙事 ─────────────────────────────── */}
        <section className="login__aside" aria-hidden="false">
          <div className="login__aside-inner">
            <div className="brand login__brand">
              <span className="brand__mark">
                <Icon name="book" />
              </span>
              <span className="brand__text">错题归因辅导</span>
            </div>

            <h1 className="login__headline">把错题变成<br />下一次的分数</h1>
            <p className="login__sub">
              不只知道错了，还知道为什么错，以及下一步先补哪一块。
            </p>

            <ul className="login__points">
              {VALUE_POINTS.map((point) => (
                <li className="login__point" key={point.title}>
                  <span className="login__point-icon">
                    <Icon name={point.icon} />
                  </span>
                  <span className="login__point-body">
                    <strong className="login__point-title">{point.title}</strong>
                    <span className="login__point-text">{point.body}</span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </section>

        {/* ── 右栏：登录表单 ─────────────────────────────── */}
        <section className="login__panel">
          <div className="login__card">
            <header className="login__card-head">
              <h2 className="login__card-title">登录</h2>
              <p className="login__card-lead">用学校发的账号登录。</p>
            </header>

            {expiredNotice ? (
              <p role="status" className="login__notice">
                <Icon name="clock" className="login__notice-icon" />
                <span>会话已结束，重新登录即可继续。</span>
              </p>
            ) : null}

            <form onSubmit={(e) => void handleSubmit(e)} noValidate>
              {/*
                ⚠️ 2026-10-24：三个输入框不再随全局错误一起标 aria-invalid。
                    旧写法 `aria-invalid={error !== null}` 会在**任何**失败
                    （网络断了、服务端 500、凭据错误）时把三个框全判"非法"——
                    对屏幕阅读器用户等于同时断言"租户是错的、账号是错的、
                    密码也是错的"，而真相可能只是网线掉了。
                    后端归一化从不回 fieldErrors（见 api/errors.ts），
                    故这里只在真有字段级错误时才标（为将来到来保留正确语义），
                    全局失败由下方 ErrorView 的 role="alert" 播报。
              */}
              <div className="field login__field">
                <label htmlFor="tenantId">租户标识</label>
                <div className="login__control">
                  <Icon name="panel" className="login__control-icon" />
                  <input
                    id="tenantId"
                    name="tenantId"
                    type="text"
                    value={tenantId}
                    onChange={(e) => setTenantId(e.target.value)}
                    autoComplete="organization"
                    aria-invalid={error?.fieldErrors['tenantId'] !== undefined}
                    required
                    autoFocus
                  />
                </div>
              </div>

              <div className="field login__field">
                <label htmlFor="loginName">账号</label>
                <div className="login__control">
                  <Icon name="profile" className="login__control-icon" />
                  <input
                    id="loginName"
                    name="loginName"
                    type="text"
                    value={loginName}
                    onChange={(e) => setLoginName(e.target.value)}
                    autoComplete="username"
                    aria-invalid={error?.fieldErrors['loginName'] !== undefined}
                    required
                  />
                </div>
              </div>

              <div className="field login__field">
                <label htmlFor="password">密码</label>
                <div className="login__control">
                  <Icon name="lock" className="login__control-icon" />
                  <input
                    id="password"
                    name="password"
                    type={passwordVisible ? 'text' : 'password'}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    autoComplete="current-password"
                    aria-invalid={error?.fieldErrors['password'] !== undefined}
                    required
                  />
                  {/*
                    ⚠️ 可访问名用**视觉隐藏文本**而不是 aria-label：
                        aria-label="显示密码" 会被 `getByLabelText(/密码/)`
                        一并命中（testing-library 同时匹配 label 与 aria-label），
                        于是密码**输入框**的断言会被这个按钮抢走。
                        隐藏文本只落在按钮内，不参与 label 匹配。
                  */}
                  <button
                    type="button"
                    className="login__toggle"
                    onClick={() => setPasswordVisible((v) => !v)}
                    aria-pressed={passwordVisible}
                  >
                    <Icon name={passwordVisible ? 'eye-off' : 'eye'} />
                    <span className="sr-only">
                      {passwordVisible ? '隐藏密码' : '显示密码'}
                    </span>
                  </button>
                </div>
              </div>

              {/*
                ⚠️ P2-2：连续失败提示。
                    措辞刻意用「可能」—— 前端并不知道服务端是否真的锁定，
                    说成「已被锁定」就是编造；而说成「密码错误」又与后端
                    刻意统一的失败语义冲突。
                    倒计时给出「等多久再试」，避免学生越试越锁。
              */}
              {failCount >= WARN_AFTER_FAILURES ? (
                <p role="status" className="login__hint">
                  已连续 {failCount} 次登录失败。账号可能被临时锁定
                  {lockSecondsLeft > 0
                    ? `，请在 ${Math.floor(lockSecondsLeft / 60)} 分 ${lockSecondsLeft % 60} 秒后再试`
                    : '，请稍后再试'}
                  。
                </p>
              ) : null}

              {error !== null ? <ErrorView error={error} /> : null}

              {/* ⚠️ 缺项提示：按钮不可用时告诉用户为什么 */}
              {!isComplete && (
                <p className="login__hint" role="status">
                  请填写所有必填字段
                </p>
              )}

              <button
                type="submit"
                className="btn btn--block btn--lg login__submit"
                disabled={submitting || !isComplete}
                aria-busy={submitting}
                aria-disabled={!isComplete}
              >
                {submitting ? '登录中…' : '登录'}
              </button>
            </form>

            {/*
              ⛔ 刻意不提供：忘记密码、注册、多租户切换
              理由见文件头注释，非遗漏。
            */}
            <p className="login__foot">
              <Icon name="shield" className="login__foot-icon" />
              <span>不采集姓名、学校、班级等身份信息</span>
            </p>
          </div>
        </section>
      </div>
    </main>
  )
}
