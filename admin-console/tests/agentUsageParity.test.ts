/**
 * O8 反向验证：两工程的 AgentUsageView 必须逐字段同构（平台版不得再缺 estimated）。
 * 后端 SSE v1.1 在供应商 usage:null 时下发 estimated=true，任一端漏接都会把
 * 本地估算当成精确值展示。撤掉 estimated 对齐，本测试必红。
 */
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(__dirname, '../..')

function usageFields(file: string): string[] {
  const src = readFileSync(file, 'utf8')
  const m = src.match(/interface AgentUsageView\s*\{([^}]*)\}/)
  const body = m?.[1]
  if (body === undefined) return []
  return [...body.matchAll(/^\s*(\w+)\??\s*:/gm)]
    .map((x) => x[1] ?? '')
    .filter((name) => name.length > 0)
    .sort()
}

describe('O8 双工程 usage 同构', () => {
  const tenant = usageFields(resolve(root, 'frontend/src/features/agentChat/agentChatState.ts'))
  const platform = usageFields(resolve(root, 'admin-console/src/features/aiChat/agentChatState.ts'))

  it('两工程 AgentUsageView 字段集合一致', () => {
    expect(platform).toEqual(tenant)
    expect(platform).toContain('estimated')
  })

  it('平台端 usage reducer 透传 estimated', () => {
    const src = readFileSync(resolve(root, 'admin-console/src/features/aiChat/agentChatState.ts'), 'utf8')
    expect(src).toMatch(/estimated:\s*event\.estimated\s*===\s*true/)
  })

  it('平台端 SSE 解码解析 estimated', () => {
    const src = readFileSync(resolve(root, 'admin-console/src/api/agentChatApi.ts'), 'utf8')
    expect(src).toMatch(/estimated:\s*p\?\.\['estimated'\]\s*===\s*true/)
  })
})
