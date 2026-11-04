// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { IngestJobTag, IngestProgress, parseStagePercent } from '@/pages/rag/ragUi'

/** 渲染层守卫：状态文案、百分比解析、撤回中性化。 */
describe('摄取状态渲染', () => {
  it('百分比从 stage 解析，解析不出返回 null（不猜数字）', () => {
    expect(parseStagePercent('PARSING 60')).toBe(60)
    expect(parseStagePercent('EMBEDDING 5')).toBe(5)
    expect(parseStagePercent('PARSING')).toBe(null)
    expect(parseStagePercent(null)).toBe(null)
    expect(parseStagePercent('PARSING 120')).toBe(null) // 越界不认
    expect(parseStagePercent('PARSING abc')).toBe(null)
  })

  it('进行中 + 百分比 → 「解析中 60%」', () => {
    render(<IngestJobTag status="PARSING" stage="PARSING 60" />)
    expect(screen.getByText('解析中 60%')).toBeTruthy()
  })

  it('进行中无百分比 → 只显示状态词，不显示 0%', () => {
    render(<IngestJobTag status="CHUNKING" stage={null} />)
    expect(screen.getByText('切分中')).toBeTruthy()
    expect(screen.queryByText(/0%/)).toBeNull()
  })

  it('撤回取消 → 「已取消」，不是「处理失败」', () => {
    render(<IngestJobTag status="REJECTED" sourceRevokedCancel />)
    expect(screen.getByText('已取消')).toBeTruthy()
    expect(screen.queryByText('处理失败')).toBeNull()
  })

  it('失败展示「处理失败」', () => {
    render(<IngestJobTag status="FAILED" errorDetail="PARSING 失败: 加密" />)
    expect(screen.getByText('处理失败')).toBeTruthy()
  })

  it('进度条只在进行中渲染，终态为 null', () => {
    const { container: c1 } = render(<IngestProgress status="EMBEDDING" stage="EMBEDDING 40" />)
    expect(c1.querySelector('.ant-progress')).toBeTruthy()
    const { container: c2 } = render(<IngestProgress status="SUCCEEDED" />)
    expect(c2.querySelector('.ant-progress')).toBeNull()
  })
})
