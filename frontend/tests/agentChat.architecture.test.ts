/**
 * 租户前台 · 通用 Agent 编排对话的**架构守卫**（源码级，防空转）。
 *
 * 锁死四类会静默失真的退化：
 * ① 租户链误用平台链 /api/chat/stream 或 /api/agent/chat/**（身份域事故）；
 * ② 租户通道下发了原始推理草稿（平台管理员专属）；
 * ③ 两个前端工程的协议/状态机漂移（同名事件含义不一致）；
 * ④ 新页面 CSS 类没写进主样式层，或路由/导航/入口缺一变成死链。
 *
 * ⚠️ 先断言源码确实读到且含锚点，再做包含/不包含判断，避免空串恒绿。
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const read = (p: string): string => readFileSync(resolve(process.cwd(), p), 'utf8')

function codeOnly(src: string): string {
  const out: string[] = []
  let inBlock = false
  for (const line of src.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (inBlock) {
      if (trimmed.includes('*/')) inBlock = false
      continue
    }
    if (trimmed.startsWith('/*')) {
      inBlock = !trimmed.includes('*/')
      continue
    }
    if (trimmed.startsWith('*') || trimmed.startsWith('//')) continue
    out.push(line)
  }
  return out.join('\n')
}

const transport = read('src/api/agentChatApi.ts')
const transportCode = codeOnly(transport)
const state = read('src/features/agentChat/agentChatState.ts')
const stateCode = codeOnly(state)
const page = read('src/pages/chat/AgentChatPage.tsx')
const app = read('src/App.tsx')
const shell = read('src/layouts/AppShell.tsx')
const chatPage = read('src/pages/chat/GeneralChatPage.tsx')
const layoutCss = codeOnly(read('src/styles/layout.css'))
const tokensCss = codeOnly(read('src/styles/tokens.css'))
const adminTransport = codeOnly(read('../admin-console/src/api/agentChatApi.ts'))
const adminState = codeOnly(read('../admin-console/src/features/aiChat/agentChatState.ts'))
const hook = codeOnly(read('src/features/agentChat/useAgentChat.ts'))
const adminHook = codeOnly(read('../admin-console/src/features/aiChat/useAgentChat.ts'))

/** 精确切源码区间；找不到锚点就红，绝不靠空串恒绿。 */
function regionBetween(src: string, start: string, end: string): string {
  const from = src.indexOf(start)
  expect(from, `应存在区间起点：${start}`).toBeGreaterThanOrEqual(0)
  const to = src.indexOf(end, from)
  expect(to, `应存在区间终点：${end}`).toBeGreaterThan(from)
  return src.slice(from, to)
}

/** 只取 decodeAgentFrame 函数体，避免把错误码 switch 也算进协议事件。 */
function protocolEvents(src: string): string[] {
  const from = src.indexOf('export function decodeAgentFrame')
  const to = src.indexOf('export async function consumeAgentStream')
  expect(from, '应存在 decodeAgentFrame').toBeGreaterThanOrEqual(0)
  expect(to, '应存在 consumeAgentStream').toBeGreaterThan(from)
  return [...src.slice(from, to).matchAll(/case '([a-z][a-z.]*)':/g)].map((m) => m[1] ?? '')
}

describe('租户身份域与推理草稿边界', () => {
  it('防空转：所有源码都实际读到，含预期锚点', () => {
    expect(transport).toContain('AGENT_CHAT_PATH')
    expect(stateCode).toContain('agentChatReducer')
    expect(page).toContain('export function AgentChatPage')
    expect(app).toContain('path="/agent-chat"')
    expect(shell).toContain("to: '/agent-chat'")
    expect(chatPage).toContain('to="/agent-chat"')
    expect(adminTransport).toContain('AGENT_LIVE_STREAM_PATH')
  })

  it('⛔ 租户链端点固定在 /api/knowledge/agent/**，不得复用平台链或纯 LLM 路径', () => {
    expect(transportCode).toContain("AGENT_CHAT_PATH = '/api/knowledge/agent/chat/stream'")
    expect(transportCode).toContain('fetch(path')
    expect(transportCode).not.toContain("'/api/chat/stream'")
    expect(transportCode).not.toContain("'/api/agent/chat/stream'")
    expect(transportCode).not.toContain("'/api/platform/")
    expect(transportCode).toContain("code: 'CAPABILITY_NOT_INSTALLED'")
  })

  it('⛔ 原始推理草稿绝不下发到租户通道（含旧字节码误推）', () => {
    expect(transportCode).toContain("event.type === 'thinking'")
    expect(transportCode).toContain('return false')
    expect(transportCode).toMatch(/params\.thinkingMode,\s*false,/)
    expect(page).toContain('不下发原始推理草稿')
    expect(page).not.toContain('ThinkingBlock')
  })

  it('⛔ live 模式不得回落到样例回放；404 时也不许伪造回答', () => {
    const liveBlock = transportCode.slice(
      transportCode.indexOf('export const liveAgentChatTransport'),
      transportCode.indexOf('export function createSampleAgentTransport'),
    )
    expect(liveBlock.length).toBeGreaterThan(500)
    expect(liveBlock).not.toContain('sampleAgentChatTransport')
    expect(liveBlock).toContain('simulated: false')
    expect(transportCode).toContain("source === 'sample' ? sampleAgentChatTransport : liveAgentChatTransport")
  })

  it('⛔ 半帧不得当终帧；done 收到即收口；error 帧终止整轮', () => {
    expect(transportCode).toContain('tail && frame.data === null')
    expect(transportCode).toContain('if (sawDone || sawTerminal) break')
    expect(transportCode).toContain('reader.cancel()')
    expect(transportCode).toContain('throw new AgentChatFailure(errorFromFrame')
  })
})

describe('双工程协议一致性', () => {
  it('✅ 两个前端 decodeAgentFrame 的事件集合逐项一致（v1.1 共 14 事件）', () => {
    const tenant = protocolEvents(transportCode)
    const platform = protocolEvents(adminTransport)
    expect(tenant).toHaveLength(14)
    expect(platform).toHaveLength(14)
    expect(tenant).toContain('context.checkpoint')
    expect(platform).toContain('context.checkpoint')
    expect(new Set(tenant)).toEqual(new Set(platform))
  })

  it('✅ S3-M13 正式收发：请求 DTO 与真实 body 只放行 contextState，解码器仍不解析', () => {
    for (const [name, source] of [
      ['租户前台', transportCode],
      ['管理平台', adminTransport],
    ] as const) {
      const from = source.indexOf('export interface AgentChatRequest')
      const to = source.indexOf('export interface AgentChatSendContext', from)
      expect(from, `${name} 应存在 AgentChatRequest`).toBeGreaterThanOrEqual(0)
      expect(to).toBeGreaterThan(from)
      const reqBlock = source.slice(from, to)
      expect(reqBlock, `⛔ ${name} 请求接口缺少中性 state 字段：hook 注入的 committed state 无类型出口`)
        .toContain('contextState?: string')
      expect(reqBlock, `⛔ ${name} 响应字段 contextStateVersion 反向进入请求接口：收发方向被污染`)
        .not.toContain('contextStateVersion')

      // 真正的请求字节在 liveAgentChatTransport 的 JSON.stringify 里，
      // 只扫接口 / 包装函数会漏掉「类型有了、body 没拼」的静默空转。
      const body = regionBetween(source, 'body: JSON.stringify({', '}),')
      expect(body, `⛔ ${name} 真实请求体未携带 committed state：S3-M13 前端空转`)
        .toMatch(/contextState\s*:/)
      expect(body, `⛔ ${name} 请求体混入响应字段 contextStateVersion`)
        .not.toContain('contextStateVersion')
      expect([...source.matchAll(/contextState\s*:/g)],
        `⛔ ${name} contextState 请求键出现在 body 白名单之外（regex find，非全串匹配）`)
        .toHaveLength(1)

      const decode = regionBetween(source,
        'export function decodeAgentFrame', 'export async function consumeAgentStream')
      expect(decode, `⛔ ${name} 解码器缺第 14 事件分支`)
        .toContain("case 'context.checkpoint':")
      expect(decode, `⛔ ${name} 解码器缺 done 版本字段`)
        .toContain('contextStateVersion')
      expect(decode, `⛔ ${name} 解码器未知事件兜底被改写：v1 客户端会报错`)
        .toMatch(/default:\s*return null/)
      expect(decode, `⛔ ${name} 解码器出现 contextState 请求键（regex find）`)
        .not.toMatch(/contextState\s*:/)
      expect(decode, `⛔ ${name} 解码器出现请求键字面量 'contextState'`)
        .not.toContain("'contextState'")
      expect(decode, `⛔ ${name} 解码器出现请求键字面量 "contextState"`)
        .not.toContain('"contextState"')
      expect(decode, `⛔ ${name} 前端解析了 state 正文：不可信载荷不得被当指令`)
        .not.toContain('JSON.parse(state)')
      expect(decode, `⛔ ${name} 前端解码了 base64 state：不可信载荷不得被当指令`)
        .not.toContain('atob(')
    }
  })

  it('⛔ S3-M13：hook 是 contextState 唯一注入口；页面参数不得传 state，STOP 不构造请求', () => {
    for (const [name, source] of [
      ['租户前台', hook],
      ['管理平台', adminHook],
    ] as const) {
      expect(source, `⛔ ${name} hook 未从 committed checkpoint 取 state：请求会空转或误送 pending`)
        .toContain('currentContextCheckpoint(stateRef.current)')
      expect(source, `⛔ ${name} hook 未把 state 放进请求 DTO`)
        .toContain('contextState: committed?.state')
      expect(source, `⛔ ${name} 页面参数仍可传 contextState：半截 / 伪造 state 会绕过提交闸门`)
        .toContain("Omit<AgentChatRequest, 'message' | 'history' | 'contextState'>")
      const stop = regionBetween(source, 'const stop = useCallback(', 'const retry = useCallback(')
      expect(stop, `⛔ ${name} STOP 构造了新请求：停止动作不得把半截上下文再送一次`)
        .not.toContain('sendAgentChat')
      expect(stop, `⛔ ${name} STOP 未 abort 在途请求`)
        .toContain('controller.abort()')
    }
  })

  it('⛔ S3-M13：压缩态只在内存暂存，初始为空，禁止本地存储 / 解析 state', () => {
    for (const source of [stateCode, adminState]) {
      expect(source).toContain('pendingContextCheckpoint')
      expect(source).toContain('contextCheckpoint: cp')
      expect(source).toContain('currentContextCheckpoint')
      expect(source).toContain('pendingContextCheckpoint: undefined')
      expect(source).not.toContain('localStorage')
      expect(source).not.toContain('sessionStorage')
      expect(source).not.toContain('JSON.parse(state)')
      expect(source).not.toContain('atob(')
    }
  })

  it('✅ buildSampleFrames 签名同构（两工程共用协议帧构造器语义）', () => {
    for (const source of [transport, adminTransport]) {
      expect(source).toContain('export function buildSampleFrames(')
      expect(source).toContain('scenario: SampleScenario')
      expect(source).toContain('thinkingMode: boolean')
      expect(source).toContain('includeReasoning: boolean')
    }
  })

  it('✅ 两工程状态机的关键分支逐项存在（失败粘滞 / 零命中 / 截断 / 上下文）', () => {
    for (const source of [stateCode, adminState]) {
      expect(source).toContain('stickyError')
      expect(source).toContain('零命中是合法结果')
      expect(source).toContain('truncated: event.truncated')
      expect(source).toContain("status: 'stopped'")
      expect(source).toContain("status: 'failed'")
      expect(source).toContain('conversationTurns')
    }
  })
})

describe('页面、路由与样式完整性', () => {
  it('✅ 页面标注样例回放非真实输出，展示过程时间线与三类终态', () => {
    expect(page).toContain('data-testid="agent-chat-sample-banner"')
    expect(page).toContain('非真实模型输出')
    expect(page).toContain('data-testid="agent-chat-timeline"')
    expect(page).toContain('data-testid="agent-chat-trace-step"')
    expect(page).toContain('data-testid="agent-chat-truncated"')
    expect(page).toContain('data-testid="agent-chat-stopped"')
    expect(page).toContain('data-testid="agent-chat-error"')
    expect(page).toContain('thinking 模式')
  })

  it('✅ 路由 + 侧栏 + 通用问答页入口成对存在，侧栏恰好 18 项', () => {
    expect(app).toContain('<Route path="/agent-chat"')
    expect(app).toContain('<AgentChatPage />')
    expect(shell).toContain("to: '/agent-chat'")
    expect(chatPage).toContain('to="/agent-chat"')
    const navTargets = [...shell.matchAll(/to:\s*'([^']*)'/g)].map((m) => m[1] ?? '')
    expect(navTargets).toHaveLength(18)
    expect(navTargets.filter((t) => t === '/agent-chat')).toHaveLength(1)
  })

  it('✅ 新页面所有静态 achat__ 类都在主样式层定义（不裸奔）', () => {
    const defined = new Set(
      [...`${layoutCss}\n${tokensCss}`.matchAll(/\.([a-zA-Z][\w-]*)/g)].map((m) => m[1] ?? ''),
    )
    const used = new Set(
      [...page.matchAll(/className="([^"]*)"/g)].flatMap((m) => (m[1] ?? '').split(/\s+/)),
    )
    const achat = [...used].filter((c) => c.startsWith('achat__'))
    expect(achat.length).toBeGreaterThan(10)
    const missing = achat.filter((c) => !defined.has(c))
    expect(missing, `未定义的主样式类：${missing.join(', ')}`).toEqual([])
    // 查找表里的动态 detail 类也要定义（css.integrity 扫不到它）。
    expect(defined.has('achat__step-detail--error')).toBe(true)
  })

  it('⛔ 真实端点已接入：不得再宣称后端没有编排端点（404/501 只代表当前环境未部署）', () => {
    const sources: [string, string][] = [
      ['transport', transport],
      ['page', page],
      ['app', app],
    ]
    for (const [name, src] of sources) {
      expect(src.length, `${name} 源码不能为空`).toBeGreaterThan(500)
      expect(src, `${name} 不应声称后端没有编排 SSE 端点`).not.toMatch(
        /后端(目前)?没有[^。\n]*编排(式)?\s*SSE\s*端点/,
      )
      expect(src, `${name} 不应声称端点待实现或需先部署`).not.toMatch(
        /待后端实现|需后端先部署|真实端点尚未部署/,
      )
    }
    // 404 文案只说「当前环境未部署」；真实链路已由后端编排器 + 租户链端点接住。
    expect(transportCode).toContain('当前环境未部署编排对话端点')
    expect(page).toContain('当前环境需已部署租户链编排端点')
  })
})
