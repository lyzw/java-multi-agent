// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { toRequest } from '@/pages/models/ModelCatalogPage'
import type { ModelConfig } from '@/api/modelCatalogApi'

/**
 * ADR-013 / M1：模型能力 5 项的前端「全量回写」契约。
 *
 * ⚠️ 表格里的启用开关走 `updateModel(id, toRequest({...record, isEnabled}))`
 * 的**全量 PUT**。若 toRequest 漏带任一能力字段，每次点开关都会把该模型的
 * 上下文窗口 / 特性位悄悄打回默认值 —— 后台显示保存成功、运行期预算却变了。
 */
const MODEL: ModelConfig = {
  id: 'm1',
  provider: 'openai',
  scenario: 'agent-chat',
  modelName: 'gpt-4o',
  displayName: 'GPT-4o',
  baseUrl: 'https://api.openai.com/v1',
  isDefault: true,
  supportsTools: true,
  supportsStreaming: true,
  supportsVision: true,
  dimension: null,
  isEnabled: true,
  sortOrder: 1,
  description: '默认聊天模型',
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-01T00:00:00Z',
  contextWindowTokens: 65536,
  maxInputTokens: 61440,
  tokenizerId: 'o200k_base',
  supportsPromptCaching: true,
  supportsTokenizeApi: false,
}

describe('模型配置页 · 上下文与压缩契约', () => {
  it('✅ 启用开关的全量 PUT 原样回写 5 项能力（防覆盖回默认）', () => {
    const payload = toRequest({ ...MODEL, isEnabled: false })
    expect(payload.contextWindowTokens).toBe(65536)
    expect(payload.maxInputTokens).toBe(61440)
    expect(payload.tokenizerId).toBe('o200k_base')
    expect(payload.supportsPromptCaching).toBe(true)
    expect(payload.supportsTokenizeApi).toBe(false)
    expect(payload.isEnabled).toBe(false)
  })

  it('✅ 省略能力字段时按 R10 保守回落（32768 / 28672 / null / false / false）', () => {
    const payload = toRequest({ provider: 'minimax', scenario: 'default', modelName: 'm' })
    expect(payload.contextWindowTokens).toBe(32768)
    expect(payload.maxInputTokens).toBe(28672)
    expect(payload.tokenizerId).toBeNull()
    expect(payload.supportsPromptCaching).toBe(false)
    expect(payload.supportsTokenizeApi).toBe(false)
  })

  it('✅ 只给总窗口不输入上限 → 派生为「总窗口 − 4096」', () => {
    const payload = toRequest({ provider: 'x', scenario: 'default', modelName: 'm', contextWindowTokens: 131072 })
    expect(payload.contextWindowTokens).toBe(131072)
    expect(payload.maxInputTokens).toBe(126976)
  })

  it('✅ 请求体恰好 18 个键（后端 ModelConfigRequest 同形）', () => {
    expect(Object.keys(toRequest(MODEL))).toHaveLength(18)
  })
})
