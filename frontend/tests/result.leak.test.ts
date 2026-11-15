/**
 * C6 敏感词兜底扫描契约测试（FE-D6 F-24）
 *
 * ⚠️ 背景：`RunResultPage.findLeakedFields` 是 C6「结果不得含身份信息」的
 *    **渲染前兜底扫描**唯一入口，现已在 `RunResultPage` 的 `useMemo` 里真实调用
 *    （早期版本确实硬编码 `[]`、C6 inert，该形态早已不存在）。
 *
 *    本文件锁住**函数契约**：保证它确实能识别泄漏字段、不误杀正常数学内容。
 *    ⚠️ 函数契约 ≠ 页面接入契约：页面是否扫描了全文 / 全部渲染字段，
 *    由 `result.page.test.tsx` 的 T9 行为级用例与 `runStep.contract.test.ts`
 *    的静态锚点共同守卫（四轮优化发现过「截断在前 + 只扫 explanation」的漏洞）。
 */

import { describe, it, expect } from 'vitest'
import { findLeakedFields } from '@/pages/business/RunResultPage'

describe('F-24：findLeakedFields 契约（C6 渲染前敏感词兜底）', () => {
  it('含手机号/身份证的字段应被识别并返回字段名', () => {
    const bad = findLeakedFields({
      step1: '请拨打 13812345678 联系',
      profile: '身份证 110101199001011234',
      note: '正常步骤',
    })
    expect(bad).toContain('step1')
    expect(bad).toContain('profile')
    expect(bad).not.toContain('note')
  })

  it('正常数学内容不应被误杀', () => {
    const ok = findLeakedFields({
      step: '分子分母同乘 √3，把分母里的根式消掉',
      hint: '求 1 2 3 4 5 6 的最小公倍数',
    })
    expect(ok).toEqual([])
  })

  it('空对象不应报任何泄漏', () => {
    expect(findLeakedFields({})).toEqual([])
  })

  it('⛔ 反证：函数若被改坏（恒返回空），上面的识别断言必须转红', () => {
    // 该断言反向证明「识别断言有牙齿」：findLeakedFields 当前确实能识别，
    // 而非测试写得过松（例如恰好断言了恒成立的空数组）。
    const bad = findLeakedFields({ x: '13812345678' })
    expect(bad.length).toBeGreaterThan(0)
  })
})
