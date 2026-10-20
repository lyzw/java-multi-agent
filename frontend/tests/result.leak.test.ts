/**
 * C6 敏感词兜底扫描契约测试（FE-D6 F-24）
 *
 * ⚠️ 背景：`RunResultPage.findLeakedFields` 是 C6「结果不得含身份信息」的
 *    **渲染前兜底扫描**唯一入口。但当前它被导出却**从未被调用**
 *    （`RunResultPage` 的 `leakedFields` 硬编码为 `[]`），即 C6 兜底**当前 inert**。
 *
 *    本文件不接假数据，只**锁住函数契约**——保证一旦 G5 结果接口接上、
 *    开发者显式调用 `findLeakedFields(payload)` 时，它确实能识别泄漏字段，
 *    不会因函数本身有 bug 而让 C6 红线静默失效。
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
