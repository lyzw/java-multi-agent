/**
 * 租户前台「通用知识问答」的流式传输层（真·LLM SSE）。
 *
 * <p><b>端点与身份域</b>：{@code POST /api/knowledge/chat/stream} 挂在<b>租户安全链</b>
 * 上，只认登录回带的租户令牌（{@code aud=agent-platform}）。平台控制台的
 * {@code /api/chat/stream} 属平台身份域（{@code aud=admin-console-api}），
 * 租户令牌打过去必然 401 —— 两条链刻意分开，本文件<b>绝不</b>复用那个前缀。
 *
 * <p><b>为什么用裸 fetch</b>：{@code client.ts#_request} 会 {@code await res.text()}
 * 把整条流读干，长连接无法逐 token 呈现。故这里自行建连、自行分帧，
 * 但错误归一化与 401 处置仍复用 {@code errors.ts} / {@code client.ts} 的唯一实现。
 *
 * <p><b>诚实边界（不要删）</b>：
 * <ol>
 *   <li><b>不伪造进度</b>：{@code delta} 全部来自后端真实 token 帧；
 *       没有打字机特效、没有预估百分比。等待期只显示「正在生成回答」。</li>
 *   <li><b>终帧是应用层收口</b>：收到 {@code event: done} 立即结束，不等 TCP EOF。
 *       SseEmitter.complete() 经反代后 chunked 流可能不干净结束（2026-11-07
 *       实测浏览器 reader 挂起 15s+），等 EOF 会让气泡永远停在「生成中」。</li>
 *   <li><b>断流 ≠ 失败</b>：没收到终帧但流已结束，如实标 {@code truncated}，
 *       保留已到达的正文并提示「连接中断」，不伪装成完整回答、也不伪装成系统故障。</li>
 *   <li><b>推理草稿不下发</b>：租户通道按后端设计没有 {@code event: thinking}；
 *       万一旧字节码仍推该帧，这里显式忽略，绝不展示给租户用户。</li>
 * </ol>
 *
 * <p><b>通用知识 ≠ 教材语料问答</b>：本通道不检索 RAG、不读任何 Run / 学生数据，
 * 回答由模型生成，没有引用与出处。教材依据请走「知识检索」页。
 */

import { notifyUnauthorized } from '@/api/client'
import { normalizeHttpError, type NormalizedError } from '@/api/errors'
import { clearSession, loadSession } from '@/auth/tokenStorage'
import { IncrementalSseParser, type ParsedFrame } from '@/features/run/sse'

/** 租户专用端点（守卫测试会锁死这个字面量，改动须同步后端与守卫）。 */
export const GENERAL_CHAT_PATH = '/api/knowledge/chat/stream'

/** 整条 SSE 长连接的客户端兜底超时（后端 SseEmitter 超时为 120s，这里略宽）。 */
export const GENERAL_CHAT_TIMEOUT_MS = 150_000

/** 单轮历史消息（多轮上下文由页面维护，整体回传；后端无状态）。 */
export interface GeneralChatMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface GeneralChatRequest {
  /** 含本次提问的完整历史，至少一条 user 消息 */
  messages: GeneralChatMessage[]
  /** 场景预设的系统提示词（前端常量，不接受用户自由输入） */
  systemPrompt?: string
}

/** 页面消费的流式事件：每一条都对应一次真实服务端到达。 */
export type GeneralChatStreamEvent =
  | { type: 'delta'; text: string }
  /** done 终帧携带的完整正文（兜底；为空时绝不回撤已收到的增量） */
  | { type: 'answer'; text: string }
  /** truncated=true 表示连接在未收到终帧的情况下结束 */
  | { type: 'finish'; truncated: boolean }

export interface GeneralChatSendContext {
  signal: AbortSignal
  onEvent: (event: GeneralChatStreamEvent) => void
}

/** 归一化后的错误：组件只读这里，不直接判 HTTP 状态码。 */
export interface GeneralChatError {
  code: string
  message: string
  hint: string
  /** 是否值得显示「重试」：401/403/400/404 重试一万次也不会变对 */
  retryable: boolean
}

/** 传输层已归一化的失败（HTTP 非 2xx / SSE error 帧 / 超时）。 */
export class GeneralChatFailure extends Error {
  readonly info: GeneralChatError

  constructor(info: GeneralChatError) {
    super(info.message)
    this.name = 'GeneralChatFailure'
    this.info = info
  }
}

/** 用户主动停止：与「失败」严格区分。 */
export class GeneralChatCancelledError extends Error {
  constructor() {
    super('本次回答已由用户停止')
    this.name = 'GeneralChatCancelledError'
  }
}

export function isGeneralChatCancelled(cause: unknown): boolean {
  return cause instanceof GeneralChatCancelledError
}

export function describeGeneralChatError(cause: unknown): GeneralChatError {
  if (cause instanceof GeneralChatFailure) return cause.info
  return {
    code: 'UNKNOWN',
    message: cause instanceof Error ? cause.message : '未知错误',
    hint: '可重试一次；若仍失败请查看浏览器控制台与后端日志。',
    retryable: true,
  }
}

/** SSE error 帧的 kind → 面向用户的文案（与后端 ModelErrorKind 对齐）。 */
function errorFromFrame(kind: string, message: string): GeneralChatError {
  switch (kind) {
    case 'UNAUTHORIZED':
      return {
        code: kind,
        message: '登录已过期，或模型凭据未授权',
        hint: '重新登录后再试；若仍失败，请核对平台模型凭据是否已装配。',
        retryable: false,
      }
    case 'RATE_LIMITED':
      return {
        code: kind,
        message: '模型调用过于频繁',
        hint: '稍后重试；本页不做自动重试，避免继续消耗配额。',
        retryable: true,
      }
    case 'INVALID_REQUEST':
      return {
        code: kind,
        message: '请求被拒绝（400）',
        hint: '检查问题内容与场景参数是否合法，换一种问法再试。',
        retryable: false,
      }
    case 'CONTENT_BLOCKED':
      return {
        code: kind,
        message: '内容被安全策略拦截',
        hint: '换一种问法；该回答触发了输出护栏（fail-closed）。',
        retryable: false,
      }
    case 'TIMEOUT':
      return {
        code: kind,
        message: '模型响应超时',
        hint: '稍后重试，或把问题拆得更具体一些。',
        retryable: true,
      }
    default:
      return {
        code: kind,
        message: message.length > 0 ? message : '模型服务暂时不可用',
        hint: '稍后重试；持续失败请查看平台模型网关日志。',
        retryable: true,
      }
  }
}

/** HTTP 非 2xx → 面向用户的错误；复用统一归一化，不另造一套错误码。 */
function errorFromHttp(normalized: NormalizedError): GeneralChatError {
  switch (normalized.code) {
    case 'UNAUTHENTICATED':
      return {
        code: normalized.code,
        message: '登录已过期，请重新登录',
        hint: '会话已失效，重新登录后继续提问。',
        retryable: false,
      }
    case 'ACCESS_DENIED':
      return {
        code: normalized.code,
        message: '无权使用通用问答',
        hint: '当前账号没有该能力的访问权限，请联系租户管理员。',
        retryable: false,
      }
    case 'INVALID_REQUEST':
      return {
        code: normalized.code,
        message: '请求被拒绝（400）',
        hint: '检查输入内容是否为空或过长，换一种问法再试。',
        retryable: false,
      }
  }
  switch (normalized.status) {
    case 404:
    case 501:
      return {
        code: normalized.code,
        message: '通用问答能力未接入',
        hint: '后端需部署 /api/knowledge/chat/stream 并重启平台服务；前端不提供必然失败的入口。',
        retryable: false,
      }
    case 429:
      return {
        code: normalized.code,
        message: '提问太频繁（429）',
        hint: '后端已限流，请稍等再试；本页不做自动重试。',
        retryable: true,
      }
    case 0:
      return {
        code: normalized.code,
        message: normalized.code === 'REQUEST_TIMEOUT' ? '等待回答超时' : '网络不可达',
        hint:
          normalized.code === 'REQUEST_TIMEOUT'
            ? '稍后重试，或把问题说得更具体一些。'
            : '确认平台服务可访问、开发代理 /api 规则生效。',
        retryable: true,
      }
    default:
      if (normalized.status >= 500) {
        return {
          code: normalized.code,
          message: '模型服务暂时不可用',
          hint: '稍后重试；持续失败请查看平台服务健康探针。',
          retryable: true,
        }
      }
      return {
        code: normalized.code,
        message: normalized.message,
        hint: '可重试一次；若仍失败请把错误码交给后端排查。',
        retryable: true,
      }
  }
}

/**
 * 发起一次流式对话。
 *
 * ⚠️ 超时用独立 AbortController 桥接外部 signal：用户点「停止」与「整条流超时」
 *    都走同一条 abort 通路，但错误码不同（取消 / REQUEST_TIMEOUT）。
 */
export async function streamGeneralChat(
  params: GeneralChatRequest,
  ctx: GeneralChatSendContext,
): Promise<void> {
  const { signal, onEvent } = ctx
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, GENERAL_CHAT_TIMEOUT_MS)
  const relayAbort = (): void => controller.abort()
  signal.addEventListener('abort', relayAbort, { once: true })
  if (signal.aborted) controller.abort()

  try {
    const headers = new Headers()
    const session = loadSession()
    if (session !== null) headers.set('Authorization', `Bearer ${session.accessToken}`)
    headers.set('Content-Type', 'application/json')
    headers.set('Accept', 'text/event-stream')
    headers.set('X-Accel-Buffering', 'no')

    let response: Response
    try {
      response = await fetch(GENERAL_CHAT_PATH, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          messages: params.messages,
          ...(params.systemPrompt !== undefined ? { systemPrompt: params.systemPrompt } : {}),
        }),
        signal: controller.signal,
      })
    } catch (cause) {
      if (signal.aborted) throw new GeneralChatCancelledError()
      if (timedOut) {
        throw new GeneralChatFailure(
          errorFromHttp({ status: 0, code: 'REQUEST_TIMEOUT', message: '', fieldErrors: {} }),
        )
      }
      throw new GeneralChatFailure(errorFromHttp(normalizeHttpError(0, cause)))
    }

    if (!response.ok) {
      const text = await response.text().catch(() => '')
      if (signal.aborted) throw new GeneralChatCancelledError()
      let body: unknown = null
      try {
        body = text.length > 0 ? (JSON.parse(text) as unknown) : null
      } catch {
        // HTML 错误页 / 空体：归一化层会给出兜底文案
      }
      const normalized = normalizeHttpError(response.status, body)
      // ⚠️ 与 client.ts 同源纪律：仅 UNAUTHENTICATED 才清会话跳登录；
      //    ACCESS_DENIED（业务无权限）绝不跳登录，也不丢掉对话现场。
      if (normalized.code === 'UNAUTHENTICATED') {
        clearSession()
        notifyUnauthorized()
      }
      throw new GeneralChatFailure(errorFromHttp(normalized))
    }

    const reader = response.body?.getReader()
    if (reader === undefined) {
      // 没有可读流：如实收口为截断，不假装成功也不假装失败
      onEvent({ type: 'finish', truncated: true })
      return
    }

    const parser = new IncrementalSseParser()
    const decoder = new TextDecoder()
    let sawDone = false

    const handleFrame = (frame: ParsedFrame): void => {
      switch (frame.event) {
        case 'delta':
          onEvent({ type: 'delta', text: frame.data ?? '' })
          break
        case 'thinking':
          // ⛔ 推理草稿仅对平台管理员可见；租户通道一律忽略（含旧字节码误推）
          break
        case 'done': {
          let content = ''
          try {
            const payload = JSON.parse(frame.data ?? '{}') as { content?: unknown }
            if (typeof payload.content === 'string') content = payload.content
          } catch {
            // 终帧解析失败不阻塞：增量已逐帧到达
          }
          onEvent({ type: 'answer', text: content })
          // done 是应用层终帧，收到即收口，绝不等 TCP EOF
          onEvent({ type: 'finish', truncated: false })
          sawDone = true
          break
        }
        case 'error': {
          let kind = 'PROVIDER_ERROR'
          let message = ''
          try {
            const payload = JSON.parse(frame.data ?? '{}') as {
              kind?: unknown
              message?: unknown
            }
            if (typeof payload.kind === 'string') kind = payload.kind
            if (typeof payload.message === 'string') message = payload.message
          } catch {
            // 保留默认值
          }
          throw new GeneralChatFailure(errorFromFrame(kind, message))
        }
        default:
          break
      }
    }

    try {
      for (;;) {
        const chunk = await reader.read()
        if (chunk.done) break
        if (signal.aborted) throw new GeneralChatCancelledError()
        for (const frame of parser.push(decoder.decode(chunk.value, { stream: true }))) {
          handleFrame(frame)
          if (sawDone) break
        }
        if (sawDone) break
      }
      if (!sawDone) {
        for (const frame of parser.flush()) handleFrame(frame)
      }
      if (!sawDone) onEvent({ type: 'finish', truncated: true })
    } catch (cause) {
      if (cause instanceof GeneralChatFailure) throw cause
      if (signal.aborted) throw new GeneralChatCancelledError()
      if (timedOut) {
        throw new GeneralChatFailure(
          errorFromHttp({ status: 0, code: 'REQUEST_TIMEOUT', message: '', fieldErrors: {} }),
        )
      }
      // 流异常中断：保留已到达的正文，如实标「截断」，不伪装成故障
      onEvent({ type: 'finish', truncated: true })
    } finally {
      // 收到终帧后主动断开，避免后端/反代继续挂着一条已完成的长连接
      reader.cancel().catch(() => {})
    }
  } finally {
    clearTimeout(timer)
    signal.removeEventListener('abort', relayAbort)
  }
}
