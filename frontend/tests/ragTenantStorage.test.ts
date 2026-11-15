/**
 * 问题 6 行为级专项实测（旧报告 E2E_TEST_REPORT.md 遗留，2026-11-14 四轮优化）
 *
 * ⚠️ 上轮（§6.5.4）修了「两页共用 key 却格式冲突」，但只有
 *    `runStep.contract.test.ts` 的**静态源码文本**守卫（证明代码里有这些字）。
 *    这里改为**真实读写 localStorage + 时间旅行**，验证：
 *      · 7 天 TTL 真的过期并清 key（不是「写了 TTL 常量」）
 *      · 历史裸字符串仍可读（兼容）
 *      · 脏数据 fail-closed，不把原文当租户标识
 *
 * ⛔ 不 mock window.localStorage —— mock 掉的存储测不出真实读写链。
 *    两页「不得直接读写」的结构守卫在 runStep.contract.test.ts，不在此重复。
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  RAG_TENANT_STORAGE_KEY,
  RAG_TENANT_STORAGE_TTL_MS,
  readStoredTenantId,
  rememberTenantId,
} from '@/features/rag/ragTenantStorage'

const T0 = new Date('2026-01-01T00:00:00.000Z').getTime()

beforeEach(() => {
  window.localStorage.clear()
  vi.useFakeTimers()
  vi.setSystemTime(T0)
})

afterEach(() => {
  vi.useRealTimers()
  window.localStorage.clear()
})

describe('rag.tenantId：7 天 TTL 的真实时间旅行', () => {
  it('写入后立即可读，且落库格式是 {value,timestamp} JSON', () => {
    rememberTenantId('tenant-a')
    const raw = window.localStorage.getItem(RAG_TENANT_STORAGE_KEY)
    expect(raw).not.toBeNull()
    expect(JSON.parse(raw as string)).toEqual({ value: 'tenant-a', timestamp: T0 })
    expect(readStoredTenantId()).toBe('tenant-a')
  })

  it('⛔ 第 7 天边界仍可读；7 天 + 1ms 后过期、清空并返回空串', () => {
    rememberTenantId('tenant-a')

    vi.setSystemTime(T0 + RAG_TENANT_STORAGE_TTL_MS)
    expect(readStoredTenantId()).toBe('tenant-a')

    vi.setSystemTime(T0 + RAG_TENANT_STORAGE_TTL_MS + 1)
    expect(readStoredTenantId()).toBe('')
    expect(window.localStorage.getItem(RAG_TENANT_STORAGE_KEY)).toBeNull()
  })

  it('空串表示清除记忆', () => {
    rememberTenantId('tenant-a')
    rememberTenantId('')
    expect(window.localStorage.getItem(RAG_TENANT_STORAGE_KEY)).toBeNull()
    expect(readStoredTenantId()).toBe('')
  })

  it('脏 JSON / 结构不合法时 fail-closed 返回空串，不把原文当租户标识', () => {
    window.localStorage.setItem(RAG_TENANT_STORAGE_KEY, '{not-json')
    expect(readStoredTenantId()).toBe('')
    window.localStorage.setItem(RAG_TENANT_STORAGE_KEY, '{"value":123,"timestamp":1}')
    expect(readStoredTenantId()).toBe('')
    window.localStorage.setItem(RAG_TENANT_STORAGE_KEY, '{"value":"x"}')
    expect(readStoredTenantId()).toBe('')
  })

  it('⛔ 历史裸字符串兼容：直接返回，且下次写入规范化', () => {
    window.localStorage.setItem(RAG_TENANT_STORAGE_KEY, 'legacy-uuid')
    expect(readStoredTenantId()).toBe('legacy-uuid')

    rememberTenantId('legacy-uuid')
    expect(JSON.parse(window.localStorage.getItem(RAG_TENANT_STORAGE_KEY) as string)).toEqual({
      value: 'legacy-uuid',
      timestamp: T0,
    })
  })

  it('TTL 常量本身必须是 7 天（防止被悄悄改成 0 或无限期）', () => {
    expect(RAG_TENANT_STORAGE_TTL_MS).toBe(7 * 24 * 60 * 60 * 1000)
  })
})
