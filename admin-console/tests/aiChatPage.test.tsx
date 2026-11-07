// @vitest-environment jsdom
/**
 * AI 对话页渲染与交互：空态、参数门禁、完整问答流、错误重试、依据不足、停止。
 *
 * 用 stubGlobal('fetch') 走真实 client.ts + aiChatApi + useAiChat + 页面，
 * 是「输入 → 请求 → 接收 → 展示」全链路的端到端（浏览器层）验证。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntApp } from 'antd'
import { ReadableStream } from 'node:stream/web'
import { AiChatPage } from '@/pages/agentlab/AiChatPage'

const HIT = {
  chunkId: '11111111-2222-4333-8444-555555555555',
  text: '判别式小于零时，方程没有实数根。',
  role: 'EXPLANATION',
  score: 0.72,
  edition: '数学·八年级·上册·人教版',
  locator: 'P42 例 3',
  channel: 'DENSE',
  reason: 'SEMANTIC_MATCH',
}

const TENANTS = [
  {
    tenantId: '11111111-1111-4111-8111-111111111111',
    slug: 'demo',
    name: '演示租户',
    lifecycleState: 'ACTIVE',
    plan: null,
    effectivePolicyVersion: 1,
    tenantAuthzVersion: 1,
  },
]

const RETRIEVAL_OK = {
  hits: [HIT],
  versionMismatchDetected: false,
  answerContentSuppressed: false,
  primaryEdition: '数学·八年级·上册·人教版',
}

const GENERATE_OK = {
  answer: '判别式小于零时，方程没有实数根。',
  citations: [HIT],
  generator: 'EXCERPT_ASSEMBLY',
  disclaimer: '本回答为教材原文逐字摘录。',
  versionMismatchDetected: false,
  answerContentSuppressed: false,
}

interface Call {
  url: string
  init: RequestInit
}

const calls: Call[] = []
let fetchImpl: (url: string, init: RequestInit) => Promise<unknown>

function json(status: number, body: unknown): { status: number; ok: boolean; text: () => Promise<string> } {
  return {
    status,
    ok: status >= 200 && status < 300,
    text: () => Promise.resolve(JSON.stringify(body)),
  }
}

function defaultFetch(url: string): Promise<unknown> {
  if (url.includes('/platform/v1/tenants')) return Promise.resolve(json(200, TENANTS))
  if (url.endsWith('/api/v1/retrieval')) return Promise.resolve(json(200, RETRIEVAL_OK))
  if (url.endsWith('/api/v1/retrieval/generate')) return Promise.resolve(json(200, GENERATE_OK))
  return Promise.resolve(json(404, { code: 'NOT_FOUND' }))
}

async function renderPage(): Promise<void> {
  // ⚠️ 用 act async 包住首次渲染，让「读取租户列表」的 effect 在断言前落地，
  //    否则 setTenants 会落在 act 之外（React 警告）且下拉还是空的。
  await act(async () => {
    render(
      <AntApp>
        <AiChatPage />
      </AntApp>,
    )
  })
}

async function pickTenant(): Promise<void> {
  const param = screen.getByTestId('ai-chat-tenant')
  const selector = param.querySelector('.ant-select-selector')
  expect(selector).not.toBeNull()
  fireEvent.mouseDown(selector as Element)
  await waitFor(() => expect(document.querySelector('.ant-select-item-option')).not.toBeNull())
  fireEvent.click(document.querySelector('.ant-select-item-option') as Element)
}

function inputBox(): HTMLElement {
  return screen.getByPlaceholderText(/输入问题/)
}

async function ask(question: string): Promise<void> {
  fireEvent.change(inputBox(), { target: { value: question } })
  await waitFor(() => expect(screen.getByRole('button', { name: /发送/ })).not.toBeDisabled())
  fireEvent.click(screen.getByRole('button', { name: /发送/ }))
}

beforeEach(() => {
  calls.length = 0
  window.sessionStorage.clear()
  fetchImpl = defaultFetch
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init: RequestInit) => {
      calls.push({ url, init })
      return fetchImpl(url, init)
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('AiChatPage · 空态与参数门禁', () => {
  it('✅ 空态展示示例问题与诚实边界；未选租户时发送禁用', async () => {
    await renderPage()
    expect(screen.getByText('这条链路的诚实边界')).toBeInTheDocument()
    expect(screen.getByText('请先选择租户')).toBeInTheDocument()
    expect(screen.getByText('一元二次方程判别式小于零是什么意思？')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /发送/ })).toBeDisabled()
    // 反证：诚实边界必须如实标注「非大模型」与「不伪造打字机」。
    // ⚠️ 文案被 <b> 切成多个文本节点，getByText 匹配不到跨节点内容，用容器 textContent 判。
    const alertText = document.body.textContent ?? ''
    expect(alertText).toContain('打字机')
    expect(alertText).toContain('逐字摘录装配')
    expect(alertText).toContain('平台控制台不展示运行输出')
    await waitFor(() => expect(screen.getByTestId('ai-chat-tenant')).toBeInTheDocument())
  })

  it('✅ 点示例问题回填输入框，不自动发送', async () => {
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: '勾股定理适用于哪些三角形？' }))
    expect(inputBox()).toHaveValue('勾股定理适用于哪些三角形？')
    expect(calls.some((c) => c.url.endsWith('/api/v1/retrieval'))).toBe(false)
  })
})

describe('AiChatPage · 完整问答流', () => {
  it('✅ 输入 → 请求 → 阶段 → 回答落地（含生成方式、说明、引用）', async () => {
    await renderPage()
    await pickTenant()
    await ask('判别式小于零是什么意思？')

    expect(screen.getByText('判别式小于零是什么意思？')).toBeInTheDocument()
    await screen.findByText(GENERATE_OK.answer)
    expect(screen.getByText('EXCERPT_ASSEMBLY')).toBeInTheDocument()
    expect(screen.getByText(/说明：本回答为教材原文逐字摘录/)).toBeInTheDocument()
    expect(screen.getByText(/引用依据（1 处/)).toBeInTheDocument()
    expect(screen.getByText(/共 2 条/)).toBeInTheDocument()

    const retrieval = calls.find((c) => c.url.endsWith('/api/v1/retrieval'))
    expect(new Headers(retrieval?.init.headers).get('X-Tenant-Id')).toBe(TENANTS[0]?.tenantId)
    expect(calls.some((c) => c.url.endsWith('/api/v1/retrieval/generate'))).toBe(true)
  })

  it('✅ 依据不足是合法业务结果：显式标注「本回答无语料依据」，不报故障', async () => {
    fetchImpl = (url) => {
      if (url.includes('/platform/v1/tenants')) return Promise.resolve(json(200, TENANTS))
      if (url.endsWith('/api/v1/retrieval')) {
        return Promise.resolve(json(200, { ...RETRIEVAL_OK, hits: [] }))
      }
      return Promise.resolve(json(200, GENERATE_OK))
    }
    await renderPage()
    await pickTenant()
    await ask('一个语料里没有的问题')

    expect(await screen.findByText('本回答无语料依据。')).toBeInTheDocument()
    expect(screen.queryByText(/RAG 服务不可用/)).not.toBeInTheDocument()
    expect(calls.some((c) => c.url.endsWith('/api/v1/retrieval/generate'))).toBe(false)
  })

  it('✅ 429 失败：错误气泡带错误码与下一步，用户气泡保留，重试可恢复', async () => {
    let fail = true
    fetchImpl = (url) => {
      if (url.includes('/platform/v1/tenants')) return Promise.resolve(json(200, TENANTS))
      if (url.endsWith('/api/v1/retrieval')) return Promise.resolve(json(200, RETRIEVAL_OK))
      if (fail) return Promise.resolve(json(429, { code: 'RATE_LIMITED', message: 'too many' }))
      return Promise.resolve(json(200, GENERATE_OK))
    }
    await renderPage()
    await pickTenant()
    await ask('会失败的问题')

    expect(await screen.findByText('模型调用过于频繁（429）')).toBeInTheDocument()
    expect(screen.getByText('RATE_LIMITED')).toBeInTheDocument()
    expect(screen.getByText('会失败的问题')).toBeInTheDocument()

    fail = false
    fireEvent.click(screen.getByRole('button', { name: /重试/ }))
    expect(await screen.findByText(GENERATE_OK.answer)).toBeInTheDocument()
  })

  it('✅ 停止：显示「已停止」而非失败态', async () => {
    fetchImpl = (url, init): Promise<unknown> => {
      if (url.includes('/platform/v1/tenants')) return Promise.resolve(json(200, TENANTS))
      return new Promise((_resolve, reject) => {
        init.signal?.addEventListener(
          'abort',
          () => reject(new DOMException('aborted', 'AbortError')),
          { once: true },
        )
      })
    }
    await renderPage()
    await pickTenant()
    await ask('需要停止的问题')

    expect(await screen.findByText('检索语料')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /停止/ }))
    expect(await screen.findByText(/已停止/)).toBeInTheDocument()
    expect(screen.queryByText(/RAG 服务不可用/)).not.toBeInTheDocument()
  })
})

describe('AiChatPage · 直接对话通道', () => {
  const directChunks = [
    'event: delta\ndata: 你好\n\n',
    'event: delta\ndata: 世界\n\n',
    'event: done\ndata: {"content":"你好世界","finishReason":"STOP","inputTokens":5,"outputTokens":2}\n\n',
  ]

  beforeEach(() => {
    fetchImpl = (url) => {
      if (url.includes('/platform/v1/tenants')) return Promise.resolve(json(200, TENANTS))
      if (url.endsWith('/api/chat/stream')) {
        const enc = new TextEncoder()
        const body = new ReadableStream<Uint8Array>({
          start(controller) {
            for (const c of directChunks) controller.enqueue(enc.encode(c))
            controller.close()
          },
        })
        return Promise.resolve({ ok: true, status: 200, body })
      }
      return Promise.resolve(json(404, { code: 'NOT_FOUND' }))
    }
  })

  it('✅ 切到「直接对话」后无需租户即可发送，走 /api/chat/stream 真·LLM 流', async () => {
    await renderPage()
    // 默认 corpus，未选租户时发送禁用
    expect(screen.getByRole('button', { name: /发送/ })).toBeDisabled()
    expect(screen.getByTestId('ai-chat-tenant')).toBeInTheDocument()

    // 切到「直接对话」：段控选项 label 带 title="直接对话"（与诚实边界里的
    // 「直接对话是真·LLM」文字节点区分开，后者无该 title），点击即切换通道。
    fireEvent.click(screen.getByTitle('直接对话'))

    await waitFor(() => expect(screen.queryByTestId('ai-chat-tenant')).toBeNull())

    const input = inputBox()
    fireEvent.change(input, { target: { value: '你好' } })
    // 直接对话通道：无需选租户，输入后即可发送
    expect(screen.getByRole('button', { name: /发送/ })).not.toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: /发送/ }))
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/api/chat/stream'))).toBe(true))

    // 流式增量最终渲染出完整正文，并如实标注「真实大模型生成」
    expect(await screen.findByText('你好世界')).toBeInTheDocument()
    expect(await screen.findByText(/真实大模型生成/)).toBeInTheDocument()
  })

  it('✅ 直接对话：思考过程以折叠块展示（推理草稿），与最终答案并存', async () => {
    // 本测试用带思考帧的 SSE 流，独立覆盖「思考过程展示」这一路径。
    fetchImpl = (url) => {
      if (url.includes('/platform/v1/tenants')) return Promise.resolve(json(200, TENANTS))
      if (url.endsWith('/api/chat/stream')) {
        const chunks = [
          'event: thinking\ndata: 让我先算判别式\n\n',
          'event: delta\ndata: 你好\n\n',
          'event: done\ndata: {"content":"你好世界","finishReason":"STOP","inputTokens":5,"outputTokens":2}\n\n',
        ]
        const enc = new TextEncoder()
        const body = new ReadableStream<Uint8Array>({
          start(controller) {
            for (const c of chunks) controller.enqueue(enc.encode(c))
            controller.close()
          },
        })
        return Promise.resolve({ ok: true, status: 200, body })
      }
      return Promise.resolve(json(404, { code: 'NOT_FOUND' }))
    }

    await renderPage()
    fireEvent.click(screen.getByTitle('直接对话'))
    await waitFor(() => expect(screen.queryByTestId('ai-chat-tenant')).toBeNull())

    const input = inputBox()
    fireEvent.change(input, { target: { value: '你好' } })
    expect(screen.getByRole('button', { name: /发送/ })).not.toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: /发送/ }))

    // 思考过程折叠块出现（推理草稿，仅供参考），与最终答案并存
    expect(await screen.findByText(/模型思考过程|模型思考中/)).toBeInTheDocument()
    expect(await screen.findByText(/推理草稿，仅供参考/)).toBeInTheDocument()
    expect(await screen.findByText('你好世界')).toBeInTheDocument()
  })

  it('✅ 直接对话回答按 Markdown + LaTeX 排版，且可一键切回未经重排的原文', async () => {
    const answer = [
      '一元二次方程',
      '',
      '\\[',
      'ax^2+bx+c=0 \\quad (a\\ne 0)',
      '\\]',
      '',
      '当 **(\\Delta<0)** 时：',
      '',
      '- 无实数根',
      '- 有共轭复数根',
    ].join('\n')
    // ⚠️ 真实后端 SseEmitter 会把多行 delta 拆成多个 `data:` 行（空行也不能丢），
    //    这里按真实线上字节构造，顺带锁死解析器对多 data 行的拼接。
    const frame = `event: delta\n${answer
      .split('\n')
      .map((line) => `data: ${line}`)
      .join('\n')}\n\n`
    const done = `event: done\ndata: ${JSON.stringify({ content: answer, finishReason: 'STOP' })}\n\n`
    fetchImpl = (url) => {
      if (url.includes('/platform/v1/tenants')) return Promise.resolve(json(200, TENANTS))
      if (url.endsWith('/api/chat/stream')) {
        const enc = new TextEncoder()
        const body = new ReadableStream<Uint8Array>({
          start(controller) {
            controller.enqueue(enc.encode(frame))
            controller.enqueue(enc.encode(done))
            controller.close()
          },
        })
        return Promise.resolve({ ok: true, status: 200, body })
      }
      return Promise.resolve(json(404, { code: 'NOT_FOUND' }))
    }

    await renderPage()
    fireEvent.click(screen.getByTitle('直接对话'))
    fireEvent.change(inputBox(), { target: { value: '判别式小于零呢' } })
    fireEvent.click(screen.getByRole('button', { name: /发送/ }))

    expect(await screen.findByText('一元二次方程')).toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('.katex-display')).not.toBeNull())
    expect(document.querySelector('.ai-chat__markdown strong')?.textContent).toContain('Δ')
    expect(document.querySelectorAll('.ai-chat__markdown li')).toHaveLength(2)
    // 排版视图不应把 LaTeX 源码糊在页面上
    expect(document.querySelector('.katex-html')?.textContent).not.toContain('\\quad')

    // 「原文」核对：展示的是模型原始字节（`\[` 仍原样），不是渲染前的归一化文本
    fireEvent.click(screen.getByText('原文'))
    const source = document.querySelector('.ai-chat__source')
    expect(source?.textContent).toBe(answer)
    expect(source?.textContent).toContain('\\[')
    expect(document.querySelector('.ai-chat__markdown')).toBeNull()
  })

  it('✅ 直接对话失败后无需选租户即可重试（X-Tenant-Id 门禁只约束语料问答）', async () => {
    let fail = true
    const sse = (payload: string): unknown => {
      const enc = new TextEncoder()
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(enc.encode(payload))
          controller.close()
        },
      })
      return { ok: true, status: 200, body }
    }
    fetchImpl = (url) => {
      if (url.includes('/platform/v1/tenants')) return Promise.resolve(json(200, TENANTS))
      if (url.endsWith('/api/chat/stream')) {
        return Promise.resolve(
          fail
            ? sse('event: error\ndata: {"kind":"RATE_LIMITED","message":"too many"}\n\n')
            : sse('event: done\ndata: {"content":"重试成功","finishReason":"STOP"}\n\n'),
        )
      }
      return Promise.resolve(json(404, { code: 'NOT_FOUND' }))
    }

    await renderPage()
    fireEvent.click(screen.getByTitle('直接对话'))
    fireEvent.change(inputBox(), { target: { value: '会失败的问题' } })
    fireEvent.click(screen.getByRole('button', { name: /发送/ }))

    expect(await screen.findByText('模型调用过于频繁（429）')).toBeInTheDocument()
    expect(screen.queryByTestId('ai-chat-tenant')).toBeNull()

    fail = false
    fireEvent.click(screen.getByRole('button', { name: /重试/ }))
    expect(await screen.findByText('重试成功')).toBeInTheDocument()
    expect(calls.filter((c) => c.url.endsWith('/api/chat/stream'))).toHaveLength(2)
  })
})

describe('AiChatPage · 语料问答不得被 Markdown 重排', () => {
  it('✅ EXCERPT_ASSEMBLY 逐字摘录原样显示：`**` / `$` 不被解释成格式', async () => {
    const excerpt = '**原文** 里的 $x^2$ 不是公式，\\[也不是\\]'
    fetchImpl = (url) => {
      if (url.includes('/platform/v1/tenants')) return Promise.resolve(json(200, TENANTS))
      if (url.endsWith('/api/v1/retrieval')) return Promise.resolve(json(200, RETRIEVAL_OK))
      return Promise.resolve(json(200, { ...GENERATE_OK, answer: excerpt }))
    }
    await renderPage()
    await pickTenant()
    await ask('摘录长什么样？')

    await screen.findByText(excerpt)
    const answerEl = document.querySelector('.ai-chat__answer')
    expect(answerEl?.textContent).toBe(excerpt)
    expect(answerEl?.querySelector('strong')).toBeNull()
    expect(document.querySelector('.ai-chat__markdown')).toBeNull()
  })
})
