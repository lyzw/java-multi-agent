// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntApp } from 'antd'

// ---- mock API 层（页面本身不触网） ----
vi.mock('@/api/chatAuditConfigApi', async () => {
  const actual = await vi.importActual<typeof import('@/api/chatAuditConfigApi')>(
    '@/api/chatAuditConfigApi',
  )
  return {
    ...actual,
    getChatAuditConfig: vi.fn(),
    saveChatAuditConfig: vi.fn(),
  }
})
import { getChatAuditConfig, saveChatAuditConfig } from '@/api/chatAuditConfigApi'
import type { ChatAuditConfig, ChatAuditConfigResponse } from '@/api/chatAuditConfigApi'
import { ChatAuditConfigPage } from '@/pages/settings/ChatAuditConfigPage'

/** 与后端 ChatAuditConfig.defaults() 一致的回显载荷。 */
function config(overrides: Partial<ChatAuditConfig> = {}): ChatAuditConfig {
  return {
    enabled: true,
    scope: {
      endpoints: ['/api/agent/chat/stream', '/api/knowledge/agent/chat/stream'],
      includePreconnectionFailures: true,
      includeRoutingModelCalls: true,
    },
    sinks: {
      database: { enabled: true },
      file: { enabled: true, directory: '/data/agent/chat-audit', partition: 'day', fsync: false },
    },
    capture: {
      rawRequestBody: true,
      rawAnswer: true,
      thinking: true,
      deltaChunks: true,
      modelRequestMessages: true,
      ragHits: true,
      toolArgsAndResults: true,
      headers: [],
    },
    redaction: { mask: '***', sensitiveFields: ['password', 'token'] },
    sampling: { rate: 1 },
    writer: { async: true, queueCapacity: 10000, onSinkError: 'IGNORE', maxChunksPerRequest: 20000 },
    retention: { days: 30 },
    limits: { maxBodyBytes: 65536, maxAnswerChars: 200000 },
    ...overrides,
  }
}

function response(cfg: ChatAuditConfig, version = 3): ChatAuditConfigResponse {
  return { config: cfg, version, updatedBy: 'admin', updatedAt: '2026-11-18T10:00:00Z' }
}

function renderPage() {
  return render(
    <MemoryRouter>
      <AntApp>
        <ChatAuditConfigPage />
      </AntApp>
    </MemoryRouter>,
  )
}

/**
 * 对话审计配置页 —— 表单渲染 / 校验 / 保存回显。
 *
 * <p>为什么这三类断言缺一不可：
 *  - <b>渲染</b>：后端新增配置键若前端没接上，运维在页面上改不到，
 *    只能改数据库或重启——「前端可配」这句承诺就落空了；
 *  - <b>校验</b>：校验失败被误报成「保存失败」会让人以为请求发出去了却没成功，
 *    于是反复点保存（每次都是 400）；
 *  - <b>保存回显</b>：保存后必须用<b>服务端返回</b>的 config 回填，
 *    否则页面显示的是用户输入值、后端存的可能是被补齐/纠正后的值。
 */
describe('对话审计配置页', () => {
  beforeEach(() => {
    vi.mocked(getChatAuditConfig).mockReset()
    vi.mocked(saveChatAuditConfig).mockReset()
  })

  it('回显：读取配置后渲染关键字段与版本号', async () => {
    vi.mocked(getChatAuditConfig).mockResolvedValue(response(config()))

    renderPage()

    // 版本号来自响应，证明「保存后下一个请求即生效」的版本追踪接上了
    expect(await screen.findByText(/版本 3/)).toBeTruthy()
    await waitFor(() => {
      expect((screen.getByLabelText('审计目录（服务端绝对路径）') as HTMLInputElement).value)
        .toBe('/data/agent/chat-audit')
    })
    // 保留天数 / 采样率也必须是真实回显（不是占位符）
    expect((screen.getByLabelText('保留天数') as HTMLInputElement).value).toBe('30')
    // ⚠️ 采样率控件 step=0.05，antd 会按 2 位小数显示（1 → "1.00"），
    //    因此断言数值而不是字面量：格式化精度不是契约，值才是。
    expect(Number((screen.getByLabelText('采样率') as HTMLInputElement).value)).toBe(1)
  })

  it('读取失败：显示错误并提供重新加载，不渲染假数据', async () => {
    vi.mocked(getChatAuditConfig).mockRejectedValue(new Error('无权限'))

    renderPage()

    expect(await screen.findByText('操作失败')).toBeTruthy()
    expect(screen.getByText('无权限')).toBeTruthy()
    expect(screen.getByRole('button', { name: '重新加载' })).toBeTruthy()
  })

  it('保存：整表提交并以后端返回值回填（含新版本号）', async () => {
    vi.mocked(getChatAuditConfig).mockResolvedValue(response(config(), 3))
    const saved = config({ retention: { days: 7 } })
    vi.mocked(saveChatAuditConfig).mockResolvedValue(response(saved, 4))

    renderPage()
    await screen.findByText(/版本 3/)

    fireEvent.click(screen.getByRole('button', { name: '保存配置' }))

    await waitFor(() => expect(saveChatAuditConfig).toHaveBeenCalledTimes(1))
    const submitted = vi.mocked(saveChatAuditConfig).mock.calls[0]?.[0] as ChatAuditConfig
    // 整表提交：缺字段后端会补默认值，但前端不能只发「改动的那个键」
    expect(submitted.sinks.file.directory).toBe('/data/agent/chat-audit')
    expect(submitted.writer.onSinkError).toBe('IGNORE')

    // 回填来自服务端响应（版本 4），不是用户输入
    expect(await screen.findByText(/版本 4/)).toBeTruthy()
  })

  it('保存失败：显示后端错误，且不谎报成功', async () => {
    vi.mocked(getChatAuditConfig).mockResolvedValue(response(config()))
    vi.mocked(saveChatAuditConfig).mockRejectedValue(new Error('sinks.file.directory 禁止包含 .. 穿越段'))

    renderPage()
    await screen.findByText(/版本 3/)
    fireEvent.click(screen.getByRole('button', { name: '保存配置' }))

    expect(await screen.findByText(/禁止包含 \.\. 穿越段/)).toBeTruthy()
  })

  it('⛔ 校验失败（目录为空）不发请求，也不顶一条全局错误', async () => {
    vi.mocked(getChatAuditConfig).mockResolvedValue(response(config()))

    renderPage()
    await screen.findByText(/版本 3/)

    const dirInput = screen.getByLabelText('审计目录（服务端绝对路径）') as HTMLInputElement
    fireEvent.change(dirInput, { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: '保存配置' }))

    await waitFor(() => {
      expect(screen.getByText('请输入绝对路径')).toBeTruthy()
    })
    // 校验失败 ≠ 保存失败：否则用户以为请求已发出
    expect(saveChatAuditConfig).not.toHaveBeenCalled()
    expect(screen.queryByText('操作失败')).toBeNull()
  })

  it('强制脱敏说明可见，且「记录路由模型调用」为不可操作的保留项', async () => {
    vi.mocked(getChatAuditConfig).mockResolvedValue(response(config()))

    renderPage()
    await screen.findByText(/版本 3/)

    expect(screen.getByText(/Authorization \/ Cookie \/ API Key/)).toBeTruthy()
    // V1 不区分路由调用：开关保留但禁用，避免「打开了却没有任何行为变化」的误判
    const routingItem = screen.getByText('记录路由模型调用').closest('.ant-form-item') as HTMLElement
    expect(routingItem.querySelector('.ant-switch-disabled')).toBeTruthy()
  })
})
