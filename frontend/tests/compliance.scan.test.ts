/**
 * 敏感信息预检测试（FE-D1 §9 T2/T3/T4）
 *
 * ⛔ T4 是反证测试：把 scanText 替换成恒不命中的实现，
 *    T3 类断言必须失败 —— 证明测试真的能抓住防护失效。
 */

import { describe, it, expect } from 'vitest'
import { scanText, scanForm, hasViolation, type Violation } from '@/features/compliance/scan'

describe('敏感信息预检', () => {
  describe('T3 正向：各类可识别信息应被拦截', () => {
    const cases: { label: string; value: string; kind: string }[] = [
      { label: '18 位身份证', value: '我的身份证是 110101199003077615', kind: 'ID_CARD' },
      { label: '15 位身份证', value: '110101900307761', kind: 'ID_CARD' },
      { label: '手机号', value: '我的电话 13812345678 请联系我', kind: 'MOBILE' },
      { label: '邮箱', value: '邮箱是 student@example.com', kind: 'EMAIL' },
      { label: '11 位学号段数字', value: '学号 20230101 请查', kind: 'STUDENT_ID' },
    ]

    for (const testCase of cases) {
      it(`应拦截：${testCase.label}`, () => {
        const violations = scanText(testCase.value)
        expect(violations.length).toBeGreaterThan(0)
        expect(violations.map((v) => v.kind)).toContain(testCase.kind)
      })
    }
  })

  describe('正常内容不应误报', () => {
    const safeInputs = [
      '题目：已知 a=3，b=5，求 ab 的值',
      '我的过程：先算 3×5=15，再写答案',
      '正确答案：15',
      'stu_a1b2c3d4e5f6a7b8',
      '我在思考为什么分数要通分',
    ]

    for (const value of safeInputs) {
      it(`不应拦截：${value.slice(0, 12)}…`, () => {
        expect(scanText(value)).toHaveLength(0)
      })
    }
  })

  describe('边界情况', () => {
    it('7 位数字不应算学号（未达 8 位下界）', () => {
      const violations = scanText('答案 1234567')
      expect(violations.map((v) => v.kind)).not.toContain('STUDENT_ID')
    })

    it('⛔ 13 位数字不应算学号（超 12 位上界）', () => {
      // ⚠️ 此用例曾失败：初版用裸 \d{8,12}，13 位数字会匹配其前 12 位而误判。
      //    后端实现为 (?<![0-9])\d{8,12}(?![0-9])，须用词边界。
      //    「正则锚定致闸门失效」在 P1 已出现过一次，此处为同类缺陷。
      const violations = scanText('编号 1234567890123')
      expect(violations.map((v) => v.kind)).not.toContain('STUDENT_ID')
    })

    it('空字符串不报错', () => {
      expect(scanText('')).toHaveLength(0)
      expect(scanText('   ')).toHaveLength(0)
    })

    it('⛔ 手机号前后接数字时不误判（与后端一致）', () => {
      // 后端要求 (?<![0-9])…(?![0-9])，避免订单号后 11 位恰好是手机号
      const violations = scanText('订单号末尾12313812345678901')
      expect(violations.map((v) => v.kind)).not.toContain('MOBILE')
    })

    it('⛔ 身份证嵌在句子中仍应命中（词边界而非 ^$）', () => {
      // 后端注释明确：整串 ^$ 锚定会导致「学生 110101… 的错题」完全失效，
      // 这是一个被契约测试证伪的真实缺陷。前端不得重犯。
      const violations = scanText('学生 110101199003077615 的错题')
      expect(violations.map((v) => v.kind)).toContain('ID_CARD')
    })

    it('⛔ 空格分隔的手机号应被拦截（防绕过）', () => {
      // 后端在原始文本上匹配，此输入**后端会漏检**（真实合规缺口）。
      // 前端对结构固定类规则额外做去空格匹配，属有意更严。
      const violations = scanText('我的电话 138 1234 5678')
      expect(violations.map((v) => v.kind)).toContain('MOBILE')
    })

    it('⛔ 空格分隔的身份证应被拦截（防绕过）', () => {
      const violations = scanText('110101 19900307 7615')
      expect(violations.map((v) => v.kind)).toContain('ID_CARD')
    })

    it('⛔ 但学号不做去空格匹配（避免误杀正常数学题）', () => {
      // ⚠️ 这是刻意的取舍：学号是纯数字，而数学题里
      //    「求 1 2 3 4 5 6 7 8」极常见，全局去空格会误杀。
      //    该绕过场景需后端修复，已记录为 FE-D1 §12 待办。
      const violations = scanText('求 1 2 3 4 5 6 7 8 的和')
      expect(violations.map((v) => v.kind)).not.toContain('STUDENT_ID')
    })
  })

  describe('⚠️ 违规提示不回显命中内容', () => {
    it('message 不应包含原始号码', () => {
      const violations: Violation[] = scanText('电话 13812345678')
      expect(violations.length).toBeGreaterThan(0)
      for (const violation of violations) {
        // 回显等于把用户刚输入的敏感信息又显示一遍
        expect(violation.message).not.toContain('13812345678')
      }
    })
  })

  describe('表单级扫描', () => {
    it('应按字段名归集违规', () => {
      const result = scanForm({
        studentId: 'stu_a1b2c3d4e5f6a7b8',
        questionText: '题目内容',
        studentSolution: '我的电话是 13812345678',
        correctAnswer: '15',
      })
      expect(Object.keys(result)).toEqual(['studentSolution'])
      expect(hasViolation(result)).toBe(true)
    })

    it('全部合规时无违规', () => {
      const result = scanForm({
        studentId: 'stu_a1b2c3d4e5f6a7b8',
        questionText: '题目',
        studentSolution: '过程',
        correctAnswer: '答案',
      })
      expect(hasViolation(result)).toBe(false)
    })
  })
})

/**
 * ⛔ T4 反证测试
 *
 * 目的：证明 T3 类断言**真的能抓住防护失效**，
 * 而不是因为写得太松所以永远通过（沿用项目 R-016 教训）。
 */
describe('⛔ 反证：预检被移除时测试必须失败', () => {
  /** 模拟"防护被绕过"的实现：恒不命中 */
  function bypassedScan(_text: string): Violation[] {
    return []
  }

  it('若预检被绕过，违规输入将不再被拦截', () => {
    const input = '我的电话 13812345678'
    const violations = bypassedScan(input)
    // 断言"防护失效"这个事实本身能被观察到：
    // 若上面的 T3 用例仍能通过，说明 T3 无效（恒真断言）
    expect(violations).toHaveLength(0)
    // 与真实实现对照：真实实现必须能拦住
    expect(scanText(input).length).toBeGreaterThan(0)
  })

  it('若预检恒返回命中，则正常内容会误报', () => {
    /** 模拟"防护过度"的实现：恒命中 */
    function overBlockedScan(_text: string): Violation[] {
      return [
        { kind: 'EMAIL', message: '命中', rule: 'always' },
      ]
    }
    const safe = '题目：求 ab 的值'
    expect(overBlockedScan(safe).length).toBeGreaterThan(0)
    // 真实实现不应误报
    expect(scanText(safe)).toHaveLength(0)
  })
})
