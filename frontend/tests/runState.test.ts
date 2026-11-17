/**
 * Run 状态映射测试（FE-D1 §9 T7/T8）
 *
 * ⛔ T7 的核心是 TIMED_OUT —— 后端 P2-D05 §4 记载：
 *    初稿漏判该终态导致 SSE 连接永不关闭。
 */

import { describe, it, expect } from 'vitest'
import {
  isTerminalState,
  stateLabel,
  stateTone,
  stateHint,
  isKnownState,
  RUN_STATES,
} from '@/features/run/runState'

describe('Run 状态映射', () => {
  it('T7：后端 6 个状态全部被识别为已知状态', () => {
    expect(RUN_STATES).toEqual([
      'QUEUED',
      'RUNNING',
      'SUCCEEDED',
      'FAILED',
      'CANCELLED',
      'TIMED_OUT',
    ])
    for (const state of RUN_STATES) {
      expect(isKnownState(state)).toBe(true)
    }
  })

  describe('终态判定（依据 RunState.legalTargets() 为空集者）', () => {
    const terminal = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT']
    const nonTerminal = ['QUEUED', 'RUNNING']

    for (const state of terminal) {
      it(`${state} 应为终态`, () => {
        expect(isTerminalState(state)).toBe(true)
      })
    }

    for (const state of nonTerminal) {
      it(`${state} 不应为终态`, () => {
        expect(isTerminalState(state)).toBe(false)
      })
    }

    it('⛔ TIMED_OUT 必须被判定为终态（漏判会导致连接不关闭）', () => {
      expect(isTerminalState('TIMED_OUT')).toBe(true)
    })
  })

  describe('中文标签', () => {
    const labels: Record<string, string> = {
      QUEUED: '排队中',
      RUNNING: '执行中',
      SUCCEEDED: '已完成',
      FAILED: '失败',
      CANCELLED: '已取消',
      TIMED_OUT: '已超时',
    }
    for (const [state, label] of Object.entries(labels)) {
      it(`${state} → ${label}`, () => {
        expect(stateLabel(state)).toBe(label)
      })
    }

    it('未知状态不猜测含义', () => {
      expect(stateLabel('SOME_FUTURE_STATE')).toBe('未知状态')
    })
  })

  describe('色调', () => {
    it('成功为 success', () => {
      expect(stateTone('SUCCEEDED')).toBe('success')
    })
    it('CANCELLED 是中性终态，不与失败混色', () => {
      expect(stateTone('CANCELLED')).toBe('neutral')
    })
    it('⛔ TIMED_OUT 与 FAILED 同为 failure 但标签不同', () => {
      expect(stateTone('TIMED_OUT')).toBe('failure')
      expect(stateTone('FAILED')).toBe('failure')
      // 语义不同：合并会掩盖超时问题
      expect(stateLabel('TIMED_OUT')).not.toBe(stateLabel('FAILED'))
    })
  })

  describe('补充说明', () => {
    it('⛔ N3：FAILED 带 errorCode 时给可读原因，绝不原样透出内部码', () => {
      expect(stateHint('FAILED', 'MODEL_TIMEOUT')).toBe('失败原因：这次分析临时未能完成')
      expect(stateHint('FAILED', 'BUDGET_EXHAUSTED')).toBe('失败原因：服务配置尚未就绪')
    })
    it('⛔ 反证：任何内部错误码都不得出现在面向学生的提示里', () => {
      for (const code of [
        'CONFIG_UNAVAILABLE',
        'BUDGET_EXHAUSTED',
        'AUTHZ_UNAVAILABLE',
        'MODEL_TIMEOUT',
        'PROVIDER_ERROR',
        'SOME_FUTURE_INTERNAL_CODE',
      ]) {
        const hint = stateHint('FAILED', code) ?? ''
        expect(hint).not.toContain(code)
        expect(hint).not.toContain('网络问题')
        expect(hint).not.toContain('稍后再试')
      }
    })
    it('FAILED 无 errorCode 时给出兜底', () => {
      expect(stateHint('FAILED')).toBe('本次执行未完成')
    })
    it('⛔ FAILED + 额度耗尽码：给出可读终态原因，绝不说「执行中」或原样透出码', () => {
      for (const code of [
        'MODEL_RATE_LIMITED',
        'QUOTA_EXHAUSTED',
        'MODEL_RETRY_EXHAUSTED',
        'MAX_ATTEMPTS_EXCEEDED',
      ]) {
        const hint = stateHint('FAILED', code) ?? ''
        expect(hint).toContain('本次分析未成功')
        expect(hint).not.toContain(code)
        expect(hint).not.toContain('执行中')
        expect(stateLabel('FAILED')).not.toBe('执行中')
      }
    })
    it('TIMED_OUT 有独立说明', () => {
      expect(stateHint('TIMED_OUT')).toBe('执行超时未能完成')
    })
    it('⛔ CANCELLED 有独立说明且忽略 errorCode（不与 FAILED 合并）', () => {
      expect(stateHint('CANCELLED')).toBe('本次执行已取消')
      expect(stateHint('CANCELLED', 'MODEL_ERROR')).toBe('本次执行已取消')
      expect(stateHint('CANCELLED')).not.toContain('失败原因')
    })
    it('⛔ TIMED_OUT 忽略 errorCode，不把超时说成服务配置故障', () => {
      expect(stateHint('TIMED_OUT', 'MODEL_TIMEOUT')).toBe('执行超时未能完成')
      expect(stateHint('TIMED_OUT', 'BUDGET_EXHAUSTED')).toBe('执行超时未能完成')
      expect(stateHint('TIMED_OUT', 'BUDGET_EXHAUSTED')).not.toContain('失败原因')
    })
    it('⛔ 反证：把 TIMED_OUT / CANCELLED 合并进 FAILED 分支会被上面抓到', () => {
      const flawedHint = (state: string): string | null =>
        ['FAILED', 'TIMED_OUT', 'CANCELLED'].includes(state) ? '失败原因：…' : null
      expect(flawedHint('TIMED_OUT')).not.toBe(stateHint('TIMED_OUT'))
      expect(flawedHint('CANCELLED')).not.toBe(stateHint('CANCELLED'))
    })
    it('非失败态无说明', () => {
      expect(stateHint('RUNNING')).toBeNull()
      expect(stateHint('SUCCEEDED', 'MODEL_ERROR')).toBeNull()
    })
  })
})

/**
 * ⛔ T8 反证测试
 *
 * 模拟"漏掉 TIMED_OUT"的实现（初稿的真实错误），
 * 证明 T7 的断言能够捕捉到这一遗漏。
 */
describe('⛔ 反证：若遗漏 TIMED_OUT，T7 必须失败', () => {
  /** 模拟有缺陷的终态集合（P2-D05 初稿的真实状态） */
  const FLAWED_TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])

  function isTerminalStateFlawed(state: string): boolean {
    return FLAWED_TERMINAL.has(state)
  }

  it('有缺陷实现会把 TIMED_OUT 判为非终态', () => {
    expect(isTerminalStateFlawed('TIMED_OUT')).toBe(false)
    // 真实实现必须能抓住这一点
    expect(isTerminalState('TIMED_OUT')).toBe(true)
  })

  it('两种实现在其余状态上结论一致（差异仅在 TIMED_OUT）', () => {
    const states = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED']
    for (const state of states) {
      expect(isTerminalStateFlawed(state)).toBe(isTerminalState(state))
    }
  })
})
