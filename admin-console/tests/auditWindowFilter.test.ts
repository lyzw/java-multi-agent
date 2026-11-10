import { describe, it, expect } from 'vitest'
import {
  NO_LIMIT_WINDOW_HOURS,
  WINDOW_FILTERS,
  windowFrom,
} from '@/pages/audit/AuditLogsPage'

/**
 * 2026-11-10 E2E 缺陷 D-04 守卫：审计页时间窗下拉不得出现 null option value。
 *
 * AntD Select 对 `value: null` 的选项会打
 * "`value` in Select options should not be `null`" 控制台警告，且选中态不可靠。
 * 「不限」改用显式数值哨兵 NO_LIMIT_WINDOW_HOURS，在 windowFrom 里映射回 null。
 *
 * ⚠️ 反向验证：把 `value: null` 加回 WINDOW_FILTERS，第一个用例立刻变红。
 */
describe('平台审计时间窗筛选', () => {
  it('所有 option value 都是非空的有限数值（无 null / undefined / NaN）', () => {
    for (const option of WINDOW_FILTERS) {
      expect(option.value).not.toBeNull()
      expect(option.value).not.toBeUndefined()
      expect(typeof option.value).toBe('number')
      expect(Number.isFinite(option.value)).toBe(true)
    }
    expect(new Set(WINDOW_FILTERS.map((o) => o.value)).size).toBe(WINDOW_FILTERS.length)
  })

  it('「不限」哨兵映射为 null（不带 from 参数），其余时间窗给出 ISO 起始时刻', () => {
    expect(windowFrom(NO_LIMIT_WINDOW_HOURS)).toBeNull()
    const iso = windowFrom(24)
    expect(iso).not.toBeNull()
    expect(Number.isNaN(Date.parse(iso as string))).toBe(false)
  })

  it('24 小时窗口确实回退约 24 小时，不是把哨兵当 0 小时', () => {
    const before = Date.now()
    const parsed = Date.parse(windowFrom(24) as string)
    const hoursAgo = (before - parsed) / 3_600_000
    expect(hoursAgo).toBeGreaterThan(23.9)
    expect(hoursAgo).toBeLessThan(24.1)
  })
})
