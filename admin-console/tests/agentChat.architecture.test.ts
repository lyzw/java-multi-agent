/**
 * 通用 Agent 编排对话的**架构守卫**（源码级，防空转）。
 *
 * 目标不是「有没有这段 UI」，而是锁死三类会静默失真的退化：
 * ① live 模式偷偷回落到样例回放（把演示当生产）；
 * ② 平台链误复用租户链 /api/chat/stream 或 /api/knowledge/**（身份域错配）；
 * ③ 协议/状态机关键分支被删（done 当终帧、断流标截断、组件失败不等于整轮失败）。
 *
 * ⚠️ 先断言源码确实读到且含锚点，再断言「不包含」，避免路径写错导致空串恒绿。
 */
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const read = (p: string): string => readFileSync(resolve(process.cwd(), p), 'utf8')

/** 逐行剔除注释，只留代码行；避免文档里引用旧端点造成假红。 */
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
const stateCode = codeOnly(read('src/features/aiChat/agentChatState.ts'))
const page = read('src/pages/agentlab/AgentChatPage.tsx')
const router = read('src/router.tsx')
const nav = read('src/layout/navConfig.tsx')
const hook = codeOnly(read('src/features/aiChat/useAgentChat.ts'))
const tenantHook = codeOnly(read('../frontend/src/features/agentChat/useAgentChat.ts'))
const tenantTransport = codeOnly(read('../frontend/src/api/agentChatApi.ts'))

/** 精确切源码区间；找不到锚点就红，绝不靠空串恒绿。 */
function regionBetween(src: string, start: string, end: string): string {
  const from = src.indexOf(start)
  expect(from, `应存在区间起点：${start}`).toBeGreaterThanOrEqual(0)
  const to = src.indexOf(end, from)
  expect(to, `应存在区间终点：${end}`).toBeGreaterThan(from)
  return src.slice(from, to)
}

describe('通用 Agent 对话 · 身份域与传输边界', () => {
  it('防空转：五份源码都确实读到，且含预期锚点', () => {
    expect(transport).toContain('AGENT_LIVE_STREAM_PATH')
    expect(stateCode).toContain('agentChatReducer')
    expect(page).toContain('export function AgentChatPage')
    expect(router).toContain("import { AgentChatPage }")
    expect(nav).toContain("key: 'agent-chat'")
  })

  it('⛔ 平台链只走 /api/agent/**，不得复用纯 LLM 端点或租户链', () => {
    expect(transportCode).toContain("AGENT_LIVE_STREAM_PATH = '/agent/chat/stream'")
    expect(transportCode).toContain('resolveAgentEndpoint')
    expect(transportCode).toContain('API_BASE')
    expect(transportCode).toContain('fetch(endpoint')
    expect(transportCode).not.toContain("'/api/chat/stream'")
    expect(transportCode).not.toContain("'/api/knowledge/chat/stream'")
    // resolveAgentEndpoint 允许测试注入绝对 URL，但生产代码不得硬编码外部 fetch 目标。
    expect(transportCode).not.toContain("fetch('https")
    expect(transportCode).not.toContain('fetch(`https')
  })

  it('⛔ live 模式不得回落到样例回放；两种传输必须显式可区分', () => {
    const liveBlock = transportCode.slice(
      transportCode.indexOf('export const liveAgentChatTransport'),
      transportCode.indexOf('export function createSampleAgentTransport'),
    )
    expect(liveBlock.length).toBeGreaterThan(500)
    expect(liveBlock).not.toContain('sampleAgentChatTransport')
    expect(liveBlock).toContain('simulated: false')
    expect(transportCode).toContain("source === 'sample' ? sampleAgentChatTransport : liveAgentChatTransport")
    expect(transportCode).toContain("code: 'CAPABILITY_NOT_INSTALLED'")
  })

  it('⛔ 服务端 error 帧必须终止整轮，不能继续推 done 伪装成功', () => {
    expect(transportCode).toContain('throw new AgentChatFailure(errorFromFrame')
    expect(transportCode).toContain("type: 'done', truncated: true")
  })

  it('⛔ 收到 done 即收口，不等 TCP EOF；半帧不得当完整帧', () => {
    expect(transportCode).toContain('if (sawDone) break')
    expect(transportCode).toContain('tail.data !== null')
    expect(transportCode).toContain('reader.cancel()')
  })
})

describe('通用 Agent 对话 · 状态机与页面守卫', () => {
  it('⛔ STOP / failed / truncated 三条分支必须各自独立存在', () => {
    expect(stateCode).toContain("status: 'stopped'")
    expect(stateCode).toContain("status: 'failed'")
    expect(stateCode).toContain('truncated: event.truncated')
    expect(stateCode).toContain("key: 'stop'")
    expect(stateCode).toContain("key: 'done'")
  })

  it('⛔ 组件失败不得吞掉、RAG 零命中不得报故障', () => {
    expect(stateCode).toContain('stickyError')
    expect(stateCode).toContain("status: event.injected ? 'success' : 'info'")
    expect(stateCode).toContain('零命中是合法结果')
    expect(stateCode).toContain('conversationTurns')
  })

  it('✅ 页面必须醒目标注样例回放非真实输出，且展示执行时间线', () => {
    expect(page).toContain('data-testid="agent-chat-sample-banner"')
    expect(page).toContain('样例回放 · 非真实模型输出')
    expect(page).toContain('data-testid="agent-chat-timeline"')
    expect(page).toContain('data-testid="agent-chat-trace-step"')
    expect(page).toContain('data-testid="agent-chat-rag"')
    expect(page).toContain('thinking 模式')
  })

  it('✅ 路由与导航入口成对存在，且 /agent-chat 只有一条导航项', () => {
    expect(router).toContain("path: 'agent-chat'")
    expect(router).toContain('<AgentChatPage />')
    expect(nav).toContain("path: '/agent-chat'")
    const occurrences = nav.split("path: '/agent-chat'").length - 1
    expect(occurrences).toBe(1)
  })

  it('⛔ 真实端点已接入：不得再宣称后端没有编排端点（404/501 只代表当前环境未部署）', () => {
    const sources: [string, string][] = [
      ['transport', transport],
      ['page', page],
      ['nav', nav],
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
    // 404 文案只说「当前环境未部署」；真实链路已由后端编排器 + 双端点接住。
    expect(transportCode).toContain('当前环境未部署编排对话端点')
    expect(page).toContain('当前环境需已部署平台链编排端点')
    expect(nav).toContain('已实现的平台链编排端点')
  })
})

describe('通用 Agent 对话 · 协议 v1.1 S3-M13 正式收发边界', () => {
  it('✅ 14 事件齐备；请求 DTO 与真实 body 只放行 contextState，解码器仍不解析', () => {
    for (const [name, source] of [
      ['管理平台', transportCode],
      ['租户前台', tenantTransport],
    ] as const) {
      const decode = regionBetween(source,
        'export function decodeAgentFrame', 'export async function consumeAgentStream')
      const cases = [...decode.matchAll(/case '([a-z][a-z.]*)':/g)].map((m) => m[1] ?? '')
      expect(cases, `⛔ ${name} 协议事件数不是 14`).toHaveLength(14)
      expect(cases, `⛔ ${name} 缺第 14 事件`).toContain('context.checkpoint')

      const reqBlock = regionBetween(source,
        'export interface AgentChatRequest', 'export interface AgentChatSendContext')
      expect(reqBlock, `⛔ ${name} 请求接口缺少中性 state 字段：hook 注入的 committed state 无类型出口`)
        .toContain('contextState?: string')
      expect(reqBlock, `⛔ ${name} 响应字段 contextStateVersion 反向进入请求接口：收发方向被污染`)
        .not.toContain('contextStateVersion')

      // 真正的请求字节在 liveAgentChatTransport 的 JSON.stringify 里。
      const body = regionBetween(source, 'body: JSON.stringify({', '}),')
      expect(body, `⛔ ${name} 真实请求体未携带 committed state：S3-M13 前端空转`)
        .toMatch(/contextState\s*:/)
      expect(body, `⛔ ${name} 请求体混入响应字段 contextStateVersion`)
        .not.toContain('contextStateVersion')
      expect([...source.matchAll(/contextState\s*:/g)],
        `⛔ ${name} contextState 请求键出现在 body 白名单之外（regex find，非全串匹配）`)
        .toHaveLength(1)

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
      ['管理平台', hook],
      ['租户前台', tenantHook],
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

  it('⛔ 压缩态只在内存暂存，初始为空，禁止本地存储 / 解析 state', () => {
    expect(stateCode).toContain('pendingContextCheckpoint')
    expect(stateCode).toContain('contextCheckpoint: cp')
    expect(stateCode).toContain('currentContextCheckpoint')
    expect(stateCode).toContain('pendingContextCheckpoint: undefined')
    expect(stateCode).not.toContain('localStorage')
    expect(stateCode).not.toContain('sessionStorage')
    expect(stateCode).not.toContain('JSON.parse(state)')
    expect(stateCode).not.toContain('atob(')
  })
})
