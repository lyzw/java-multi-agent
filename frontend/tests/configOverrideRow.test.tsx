/**
 * 配置覆盖行「恢复默认」二次确认测试（E2E 五轮优化，D1）。
 *
 * ⚠️ 守则是「撤掉修复点会红」：若 `onClear(item.key)` 前没有
 * `window.confirm` 守卫，点一下「恢复默认」就会**立即清掉租户覆盖值**，
 * 用户无任何反悔机会。本文件锁住「确认被拒 → 不调用 onClear」这条不变量。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigOverrideRow } from '@/features/config/ConfigOverrideRow'
import type { ConfigOverrideItem } from '@/api/types'

const item: ConfigOverrideItem = {
  key: 'max_retries',
  declaredValue: '5',
  effectiveValue: '3',
  source: 'db_override',
  tightening: 'TIGHTEN',
  reason: null,
  changedBy: null,
  updatedAt: null,
}

describe('ConfigOverrideRow 恢复默认（D1）', () => {
  const onSubmit = vi.fn()
  const onClear = vi.fn()
  let confirmMock = vi.fn()

  beforeEach(() => {
    onSubmit.mockReset()
    onClear.mockReset()
    // ⚠️ 默认拒绝：用于验证「被拒时不误清」
    confirmMock = vi.fn().mockReturnValue(false)
    vi.stubGlobal('confirm', confirmMock)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function renderRow(): void {
    render(
      <table>
        <tbody>
          <ConfigOverrideRow item={item} busy={false} onSubmit={onSubmit} onClear={onClear} />
        </tbody>
      </table>,
    )
  }

  it('点击「恢复默认」前弹出确认对话框', async () => {
    const user = userEvent.setup()
    renderRow()
    await user.click(screen.getByRole('button', { name: '恢复默认' }))
    expect(confirmMock).toHaveBeenCalledTimes(1)
  })

  it('⛔ D1 撤点反证：确认被拒时不得调用 onClear（否则误清覆盖值）', async () => {
    const user = userEvent.setup()
    renderRow()
    await user.click(screen.getByRole('button', { name: '恢复默认' }))
    expect(onClear).not.toHaveBeenCalled()
  })

  it('确认通过时调用 onClear(key)', async () => {
    confirmMock.mockReturnValue(true)
    const user = userEvent.setup()
    renderRow()
    await user.click(screen.getByRole('button', { name: '恢复默认' }))
    expect(onClear).toHaveBeenCalledWith('max_retries')
  })
})
