/**
 * 检索空结果成因分类守卫（P1-1 · 2026-11-17 Code Review 修复）。
 *
 * ⚠️ 本文件锁死 `classifyEmptyRetrieval` 的**判定顺序**与**不猜测**纪律：
 *    · 缺漏斗 → emptyUnknown（老服务不返回漏斗，绝不臆断成「没语料」）
 *    · candidates=0 优先级最高，压过任何 dominantDrop（「没教材」比「被滤掉」更根本）
 *    · dominantDrop 三值一一映射，不串味
 *    · dominantDrop 未知时按 dropped 计数兜底排序，全 0 → emptyUnknown
 *
 * 反向验证口径：任一分支判定顺序被调换、或改成「无脑 emptyNoCorpus」，本文件必红。
 */
import { describe, it, expect } from 'vitest'
import { classifyEmptyRetrieval } from '@/features/rag/useRagSearch'
import type { RagRetrievalResponse, RagRetrievalFunnel } from '@/api/types'

function funnel(overrides: Partial<RagRetrievalFunnel> = {}): RagRetrievalFunnel {
  return {
    candidates: 10,
    afterVersion: 10,
    afterRole: 10,
    afterGate: 10,
    returned: 10,
    droppedByVersion: 0,
    droppedByRole: 0,
    droppedByGate: 0,
    dominantDrop: 'NONE',
    ...overrides,
  }
}

function resp(f: RagRetrievalFunnel | null | undefined): RagRetrievalResponse {
  return {
    hits: [],
    versionMismatchDetected: false,
    answerContentSuppressed: false,
    primaryEdition: '2024秋',
    funnel: f,
  }
}

describe('classifyEmptyRetrieval：空结果成因按漏斗如实归因，不甩锅给学生', () => {
  it('⛔ 缺漏斗（null / undefined）→ emptyUnknown，绝不臆断成「这个版本没有语料」', () => {
    expect(classifyEmptyRetrieval(null)).toBe('emptyUnknown')
    expect(classifyEmptyRetrieval(resp(undefined))).toBe('emptyUnknown')
    expect(classifyEmptyRetrieval(resp(null))).toBe('emptyUnknown')
  })

  it('⛔ candidates=0 优先级压过 dominantDrop：没教材比被滤掉更根本', () => {
    // 即便服务端误报 dominantDrop=VERSION，也必须判为「没有语料」——顺序对调会红。
    expect(classifyEmptyRetrieval(resp(funnel({
      candidates: 0,
      dominantDrop: 'VERSION',
      droppedByVersion: 8,
    })))).toBe('emptyNoCorpus')
  })

  it('candidates>0 时 dominantDrop 三值一一映射，不串味', () => {
    expect(classifyEmptyRetrieval(resp(funnel({ dominantDrop: 'VERSION' }))))
      .toBe('emptyVersionFiltered')
    expect(classifyEmptyRetrieval(resp(funnel({ dominantDrop: 'ROLE' }))))
      .toBe('emptyRoleGated')
    expect(classifyEmptyRetrieval(resp(funnel({ dominantDrop: 'GATE' }))))
      .toBe('emptyScoreGated')
  })

  it('dominantDrop 未知时按 dropped 计数兜底：谁剔除最多归因谁', () => {
    expect(classifyEmptyRetrieval(resp(funnel({
      dominantDrop: 'UNKNOWN',
      droppedByVersion: 5,
      droppedByRole: 2,
      droppedByGate: 9,
    })))).toBe('emptyScoreGated')
    expect(classifyEmptyRetrieval(resp(funnel({
      dominantDrop: '',
      droppedByVersion: 7,
      droppedByRole: 1,
      droppedByGate: 3,
    })))).toBe('emptyVersionFiltered')
    expect(classifyEmptyRetrieval(resp(funnel({
      dominantDrop: 'NONE',
      droppedByVersion: 1,
      droppedByRole: 6,
      droppedByGate: 2,
    })))).toBe('emptyRoleGated')
  })

  it('兜底时三类剔除全为 0 → emptyUnknown，不凭空造一个成因', () => {
    expect(classifyEmptyRetrieval(resp(funnel({
      dominantDrop: 'NONE',
      droppedByVersion: 0,
      droppedByRole: 0,
      droppedByGate: 0,
    })))).toBe('emptyUnknown')
  })
})
