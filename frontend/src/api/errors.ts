/**
 * 错误归一化 —— 兼容后端两套不兼容的错误响应形状（FE-D1 §3.2）
 *
 * ⚠️ 为什么必须做归一化：
 * 后端错误响应存在**两套字段名**，并存且不兼容：
 *   ① ApiExceptionHandler / RunController  →  {code, message}
 *   ② Security 401/403、AuthController 401   →  {error: "unauthorized"}
 * 若前端只认其中一套，另一套会解析成 undefined，错误提示全部退化为空白。
 */

export interface NormalizedError {
  /** HTTP 状态码；网络层失败为 0 */
  status: number
  /** 统一错误码；后端未提供时降级为 HTTP_<status> */
  code: string
  /** 面向用户的中文文案 */
  message: string
  /** 500 时用于排障 */
  correlationId?: string
  /** 400 时的字段级提示 */
  fieldErrors: Record<string, string>
}

/**
 * ⛔ 防「message 无界增长」——
 * 后端 IllegalArgumentException 的 message 是**原样透出**的中文校验文案。
 * 可展示（不含资源标识），但必须兜底长度上限，避免超长文本撑破 UI。
 */
const MAX_MESSAGE_LENGTH = 200

/**
 * ⛔ 凭据失败的唯一文案。
 *
 * 后端把「账号不存在 / 租户不存在 / 密码错误」压成同一个 error 值，
 * 且所有失败路径耗时一致（D-001 §3.2）。前端**必须沿用同一粒度**——
 * 任何细分都会重建被刻意消除的账号枚举面。
 */
const CREDENTIAL_FAILURE_MESSAGE = '租户、账号或密码不正确'

/**
 * 配置收紧拒绝的固定文案（P4-D15 / C5）。
 *
 * ⚠️ **绝不能是「刷新后重试」**：刷新不会让「只能收紧」变松，
 * 那种提示会诱导管理员对一个注定被拒的操作反复重试。
 * 故无论服务端传什么 message，该 code 一律收敛到此句。
 */
const CONFIG_RELAXATION_MESSAGE = '该变更被安全策略拒绝：配置只允许收紧，不允许放松'

function clamp(text: string): string {
  return text.length > MAX_MESSAGE_LENGTH
    ? `${text.slice(0, MAX_MESSAGE_LENGTH)}…`
    : text
}

/** 提取 {code,message} 形状；不匹配返回 null */
function readCodeMessage(body: unknown): { code: string; message: string } | null {
  if (typeof body !== 'object' || body === null) return null
  const record = body as Record<string, unknown>
  if (typeof record['code'] !== 'string') return null
  const message = typeof record['message'] === 'string' ? record['message'] : ''
  return { code: record['code'], message }
}

/** 提取 {error} 形状（Security 过滤器 / AuthController）*/
function readErrorField(body: unknown): string | null {
  if (typeof body !== 'object' || body === null) return null
  const record = body as Record<string, unknown>
  return typeof record['error'] === 'string' ? record['error'] : null
}

/**
 * HTTP 状态码 → 默认文案。
 *
 * 取值严格对应后端已实现的错误码（FE-01 §4.1）。
 * ⚠️ 未列出的状态码走「通用错误」而非猜测具体原因。
 */
function defaultMessage(status: number, code: string): string {
  switch (code) {
    case 'INVALID_REQUEST':
      return '请求有误，请检查填写内容'
    case 'UNAUTHENTICATED':
      return '登录已过期，请重新登录'
    case 'ACCESS_DENIED':
      return '无权执行该操作'
    case 'NOT_FOUND':
      return '资源不存在'
    case 'IDEMPOTENCY_CONFLICT':
      return '该提交与之前的请求不一致，请刷新后重试'
    case 'COMPLIANCE_BLOCKED':
      return '当前数据不符合合规要求'
    case 'UNSUPPORTED_MEDIA_TYPE':
      return '请求格式不受支持'
    default:
      break
  }
  switch (status) {
    case 0:
      return '网络连接失败，请检查网络后重试'
    case 400:
      return '请求有误，请检查填写内容'
    case 401:
      return '登录已过期，请重新登录'
    case 403:
      return '无权执行该操作'
    case 404:
      return '资源不存在'
    case 409:
      return code === 'CONFIG_RELAXATION_REJECTED'
        ? CONFIG_RELAXATION_MESSAGE
        : '请求冲突，请刷新后重试'
    case 422:
      return '请求无法处理'
    default:
      return status >= 500 ? '服务暂时不可用' : '请求失败'
  }
}

/** 归一化 HTTP 错误响应 */
export function normalizeHttpError(
  status: number,
  body: unknown,
): NormalizedError {
  const codeMessage = readCodeMessage(body)
  const errorField = readErrorField(body)

  // ① {code, message} —— 业务错误路径
  if (codeMessage) {
    const correlationId =
      typeof (body as Record<string, unknown>)['correlationId'] === 'string'
        ? ((body as Record<string, unknown>)['correlationId'] as string)
        : undefined
    // ⛔ 凭据错误的两套形状必须收敛为同一文案。
    //    若走默认映射会得到「登录已过期」——语义完全错误，
    //    会让用户以为会话失效而反复重新登录。
    const isCredentialFailure = codeMessage.code === 'INVALID_CREDENTIALS'
    // ⚠️ **P4-D15**：配置收紧拒绝与凭据错误同类——**文案必须收敛**。
    // 若沿用「服务端 message 优先」，则：
    //   ① 后端若改了文案，前端提示随之漂移（提示会误导管理员重试）；
    //   ② 空 message 时才会落到 defaultMessage，使那条 409 映射变成**死代码**。
    // 实测反证 T-4 就是这么发现的：注入 defaultMessage 后 F17 仍绿，
    // 因为请求根本没走到那个分支。故此处**强制覆盖**，与凭据错误一致。
    const isConfigRelaxation = codeMessage.code === 'CONFIG_RELAXATION_REJECTED'
    return {
      status,
      code: codeMessage.code,
      message: isCredentialFailure
        ? CREDENTIAL_FAILURE_MESSAGE
        : isConfigRelaxation
          ? CONFIG_RELAXATION_MESSAGE
          : codeMessage.message.length > 0
            ? clamp(codeMessage.message)
            : defaultMessage(status, codeMessage.code),
      ...(correlationId ? { correlationId } : {}),
      fieldErrors: {},
    }
  }

  // ② {error} —— Security 过滤器 / AuthController 路径
  //    ⚠️ 三种取值映射到不同 code，避免"401 与 403 混为一谈"
  if (errorField) {
    const mapped: Record<string, string> = {
      unauthorized: 'UNAUTHENTICATED',
      forbidden: 'ACCESS_DENIED',
      invalid_credentials: 'INVALID_CREDENTIALS',
    }
    const code = mapped[errorField] ?? `HTTP_${status}`
    // ⛔ 登录失败一律同文案，不区分账号/租户/密码原因
    //    （FE-D1 T1 / FE-AT-01）：后端刻意消除的枚举面，前端不得重建
    const message =
      code === 'INVALID_CREDENTIALS'
        ? CREDENTIAL_FAILURE_MESSAGE
        : defaultMessage(status, code)
    return { status, code, message, fieldErrors: {} }
  }

  // ③ 无法识别（如空体、HTML 错误页）
  return {
    status,
    code: status > 0 ? `HTTP_${status}` : 'NETWORK_ERROR',
    message: defaultMessage(status, 'UNKNOWN'),
    fieldErrors: {},
  }
}

/**
 * 网络层失败（fetch 抛错）归一化
 *
 * ⛔ 刻意**不**把 `cause.message` 透出给用户（F-21）：
 *    原实现写的是 `...(cause instanceof Error && cause.message ? {} : {})`，
 *    三元两边都是 `{}`，恒展开空对象 —— 是死代码，
 *    推测本意是想带出原始异常文案。
 *    但 `fetch` 的异常文案（`Failed to fetch` / `Load failed` /
 *    `net::ERR_CONNECTION_REFUSED`）对终端用户没有任何意义，
 *    还可能泄漏内网细节。保持统一的归一化文案即可。
 */
export function normalizeNetworkError(cause: unknown): NormalizedError {
  // cause 仅保留签名以便将来接入日志；不进入用户可见文案
  void cause
  return {
    status: 0,
    code: 'NETWORK_ERROR',
    message: defaultMessage(0, 'NETWORK_ERROR'),
    fieldErrors: {},
  }
}

/** 是否为「需重新登录」类错误 */
export function isAuthError(error: NormalizedError): boolean {
  return error.status === 401 || error.code === 'UNAUTHENTICATED'
}

/** 是否为幂等冲突 */
export function isIdempotencyConflict(error: NormalizedError): boolean {
  return error.status === 409 && error.code === 'IDEMPOTENCY_CONFLICT'
}

/**
 * 请求超时（fetch + AbortController 主动中断）。
 *
 * ⚠️ 与「网络层失败」（status 0 / NETWORK_ERROR）区分：
 *    超时是「连上了但没在规定时间内回来」，用户下一步是**重试或换问题**；
 *    而纯网络失败提示「检查网络」。两者文案不同，混为一谈会误导。
 */
export function makeTimeoutError(): NormalizedError {
  return {
    status: 0,
    code: 'REQUEST_TIMEOUT',
    message: '请求超时，后端没有在规定时间内返回。可以稍后重试，或把问题说得更具体一些。',
    fieldErrors: {},
  }
}

/** 是否为超时错误 */
export function isTimeoutError(error: NormalizedError): boolean {
  return error.code === 'REQUEST_TIMEOUT'
}

/**
 * 能力未接入（后端**确实没有**该端点，不是故障）。
 *
 * ⚠️ 刻意与「错误态」区分（沿用 FE-D3 五态纪律）：
 *    404/501 表示「生成端点尚未实现」，前端应展示「能力未接入」的冷静提示，
 *    而非红色故障框——否则用户会反复刷新等一个永远不来的结果。
 */
export function isNotImplemented(error: NormalizedError): boolean {
  return error.status === 404 || error.status === 501
}
