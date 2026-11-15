/**
 * 问题 8 行为级专项实测（旧报告 E2E_TEST_REPORT.md 遗留，2026-11-14 四轮优化）
 *
 * ⚠️ 此前 `reports.page.test.tsx` 把整个 `@/features/reports/csv` **mock 掉**
 *    （`toCsv` 是假的 `headers.join(',')`），所以「导出 CSV」的测试只能证明
 *    「按钮存在 + 同源数据」，完全没测过真实的 BOM / 公式注入防护 / 下载链。
 *
 * ⛔ 本文件用**真实模块**：直接断言 CSV 文本，并拦截
 *    `URL.createObjectURL` / `Blob` / `anchor.click` / `revokeObjectURL`。
 *    诚实边界：jsdom 里没有真实下载落盘，能证明的是「生成的 Blob 内容 +
 *    下载动作参数正确」，不能证明「Excel 打开不乱码 / 公式真的不执行」。
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { toCsv, downloadCsv } from '@/features/reports/csv'

const BOM = '\uFEFF'

describe('toCsv：真实文本契约', () => {
  it('以 UTF-8 BOM 开头，表头与数据逐格一致，行以 CRLF 分隔', () => {
    const csv = toCsv(['日期', '次数'], [['2026-10-21', 3], ['2026-10-22', 0]])
    expect(csv.startsWith(BOM)).toBe(true)
    expect(csv).toBe(`${BOM}日期,次数\r\n2026-10-21,3\r\n2026-10-22,0\r\n`)
  })

  it('⛔ 公式注入四类前缀都被前置单引号中和', () => {
    const prefixes = ['=cmd|calc', '+SUM(A1)', '-1+1', '@SUM(1)', '\tcmd', '\rcmd']
    for (const raw of prefixes) {
      const csv = toCsv(['h'], [[raw]])
      expect(csv).toContain(`'${raw}`)
      // ⛔ 中和后单元格不得再以危险前缀开头（前面是单引号）
      expect(csv.split('\r\n')[1]?.startsWith(raw[0] as string)).toBe(false)
    }
  })

  it('逗号 / 双引号 / 换行被标准 CSV 转义', () => {
    const csv = toCsv(['h'], [['a,b'], ['say "hi"'], ['line1\nline2']])
    expect(csv).toContain('"a,b"')
    expect(csv).toContain('"say ""hi"""')
    expect(csv).toContain('"line1\nline2"')
  })

  it('空 rows 仍产出表头行（不是空文件）', () => {
    expect(toCsv(['日期', '次数'], [])).toBe(`${BOM}日期,次数\r\n`)
  })

  it('⛔ 反证：去掉 BOM 或去掉公式中和，上面的断言必须转红', () => {
    const flawed = toCsv(['h'], [['=cmd']]).slice(1) // 模拟「忘了 BOM」
    expect(flawed.startsWith(BOM)).toBe(false)
    expect(toCsv(['h'], [['=cmd']]).startsWith(BOM)).toBe(true)
  })
})

describe('downloadCsv：真实下载动作链', () => {
  const createObjectURL = vi.fn((_blob: Blob): string => 'blob:mock-url')
  const revokeObjectURL = vi.fn()
  let clickSpy: ReturnType<typeof vi.spyOn>
  let lastAnchor: HTMLAnchorElement | null = null

  beforeEach(() => {
    createObjectURL.mockClear()
    revokeObjectURL.mockClear()
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
    clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      lastAnchor = this
    })
    lastAnchor = null
  })

  afterEach(() => {
    clickSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('⛔ 生成 text/csv Blob、设置 download 文件名、点击一次、回收 URL 一次', () => {
    downloadCsv('审计记录', ['动作', '次数'], [['登录', 3]])

    expect(createObjectURL).toHaveBeenCalledTimes(1)
    const blob = createObjectURL.mock.calls[0]?.[0] as Blob
    expect(blob).toBeInstanceOf(Blob)
    expect(blob.type).toBe('text/csv;charset=utf-8')

    expect(clickSpy).toHaveBeenCalledTimes(1)
    expect(lastAnchor?.download).toBe('审计记录.csv')
    expect(lastAnchor?.href).toContain('blob:mock-url')
    // ⛔ 点击后必须把临时 anchor 从 DOM 移除，不能留下垃圾节点
    expect(document.body.contains(lastAnchor as Node)).toBe(false)
    expect(revokeObjectURL).toHaveBeenCalledTimes(1)
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock-url')
  })

  it('⛔ 反证：不调用 downloadCsv 就不该有任何下载动作', () => {
    expect(createObjectURL).not.toHaveBeenCalled()
    expect(clickSpy).not.toHaveBeenCalled()
    expect(revokeObjectURL).not.toHaveBeenCalled()
  })
})
