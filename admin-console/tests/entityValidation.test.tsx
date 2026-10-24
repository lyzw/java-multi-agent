// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { allowlistCell, riskTag } from '@/pages/catalog/displayBits'
import { isValidJson } from '@/pages/agentDefinition'

describe('displayBits · allowlist null≠[] 语义（§4.4）', () => {
  it('null = 未配置（取 ServerAllowedOps 全集）', () => {
    render(<>{allowlistCell(null)}</>)
    expect(screen.getByText('未配置（取全集）')).toBeTruthy()
  })

  it('[] = 显式空（∅），语义区别于 null', () => {
    render(<>{allowlistCell([])}</>)
    expect(screen.getByText('显式空（∅）')).toBeTruthy()
    // ⚠️ 显式空绝不可被归一化成「未配置」——那等于权限放宽
    expect(screen.queryByText('未配置（取全集）')).toBeNull()
  })

  it('显式项正常列出', () => {
    render(<>{allowlistCell(['read', 'write'])}</>)
    expect(screen.getByText('read、write')).toBeTruthy()
  })
})

describe('displayBits · 风险等级渲染', () => {
  it('READ / WRITE_PROFILE / WRITE_EXTERNAL / UNKNOWN 各自可渲染', () => {
    for (const level of ['READ', 'WRITE_PROFILE', 'WRITE_EXTERNAL', 'UNKNOWN']) {
      const { unmount } = render(<>{riskTag(level)}</>)
      expect(screen.getByText(level)).toBeTruthy()
      unmount()
    }
  })
})

describe('agentDefinition · definition JSON 预校验', () => {
  it('合法 JSON 通过', () => {
    expect(isValidJson('{"goal":"x"}')).toBe(true)
    expect(isValidJson('[1,2,3]')).toBe(true)
    expect(isValidJson('  {"a":1}  ')).toBe(true)
  })

  it('空串 / 非法 JSON 拒绝', () => {
    expect(isValidJson('')).toBe(false)
    expect(isValidJson('   ')).toBe(false)
    expect(isValidJson('{oops')).toBe(false)
    expect(isValidJson('not json')).toBe(false)
  })
})
