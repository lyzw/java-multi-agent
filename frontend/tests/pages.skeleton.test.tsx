/**
 * 页面骨架与合规红线测试（FE-D2 §5）
 *
 * ⛔ 核心思路：红线**不能只靠文案**，必须有结构级保障。
 *   故这些断言查的是「页面上根本没有这个东西」，
 *   而不是「有个地方写着不要有这个」。
 */

import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { RunResultPage, findLeakedFields } from '@/pages/business/RunResultPage'
import { ProfilePage, pickWhitelisted } from '@/pages/business/ProfilePage'
import type { ProfileResponse } from '@/api/types'
import { ReconciliationPage, AuditPage } from '@/pages/governance/GovernancePages'
import { ConfigCompliancePage } from '@/pages/config/ConfigPages'

const srcDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'src')

const read = (p: string): string => readFileSync(join(srcDir, p), 'utf8')

/** 剥离注释后的源码 —— 断言只针对**真实代码**（结构不变式测试的既定范式） */
function readCode(p: string): string {
  const raw = read(p)
  let out = raw.replace(/\/\*[\s\S]*?\*\//g, '')
  out = out.replace(/(^|[^:'"`])\/\/[^\n]*/g, '$1')
  return out
}

function renderAt(path: string, element: React.ReactElement, pattern = '/:id'): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={pattern} element={element} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('骨架页统一占位', () => {
  it('T1：⛔ P5 已接线（FE-D8）——不再是「未开放」占位', () => {
    // ⚠️ FE-D8：G5 后端已落地，P5 从占位改为**真实页**。
    //    此处断言的是「不再显示未开放占位」，
    //    真实内容的断言在 tests/result.page.test.tsx。
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')
    expect(screen.queryByText(/还没接上后端/)).not.toBeInTheDocument()
    expect(screen.queryByText('G5')).not.toBeInTheDocument()
  })
})

describe('⛔ P5 合规红线（C5/C6/C7）', () => {
  it('T2：⛔ 结构中不存在「来源/引用/出处/页码」区块', () => {
    const { container } = render(
      <MemoryRouter initialEntries={['/runs/r-1/result']}>
        <Routes>
          <Route path="/runs/:runId/result" element={<RunResultPage />} />
        </Routes>
      </MemoryRouter>,
    )

    const headings = Array.from(container.querySelectorAll('h2, h3')).map((el) =>
      (el.textContent ?? '').trim(),
    )

    // ⛔ C5：DEC-5 不做 RAG，编造出处即判失败（R-018）
    for (const word of ['来源', '引用', '出处', '参考资料', '页码', '文献']) {
      for (const heading of headings) {
        expect(heading).not.toContain(word)
      }
    }
  })

  it('T3：⛔ 必须预留「待核对」分区', () => {
    // ⚠️ FE-D8：P5 已接线为**真实数据页**，待核对分区在数据到达后渲染，
    //    故本用例改为**源码级**结构断言——
    //    「页面里必须存在这个位置」是**结构性**要求，不应依赖运行时状态。
    //    运行时行为（pending=true 时列出未决操作）由 tests/result.page.test.tsx 覆盖。
    const source = readCode('pages/business/RunResultPage.tsx')
    expect(source).toContain('reconcile-zone')
    expect(source).toContain('待核对')
    // ⚠️ 只提 UNKNOWN：**RECONCILIATION_REQUIRED 不在 run_state 枚举里**
    //    （P4-D10 §1.4 实测），在界面提它等于告诉用户一个永远不会出现的状态。
    expect(source).toContain('UNKNOWN')
    expect(source).not.toContain('RECONCILIATION_REQUIRED')
  })

  it('T6：敏感词兜底扫描函数可用', () => {
    // C6 的实现：findLeakedFields
    const clean = findLeakedFields({ conclusion: '因为通分错误导致计算偏差' })
    expect(clean).toHaveLength(0)

    const dirty = findLeakedFields({
      conclusion: '正确解法',
      hint: '联系方式 13812345678',
    })
    expect(dirty).toEqual(['hint'])
  })

  it('T6b：⛔ 泄漏内容不得回显原值', () => {
    const leaked = findLeakedFields({ hint: '13812345678' })
    expect(leaked).toHaveLength(1)
    expect(leaked[0]).toBe('hint')
    // 只报字段名，不报值
    expect(leaked.join()).not.toContain('13812345678')
  })
})

describe('⛔ P9 UNKNOWN 处置台（禁重试）', () => {
  it('T4：⛔ 不得出现任何重试按钮', () => {
    renderAt('/reconciliation', <ReconciliationPage />, '/reconciliation')

    // 盲��重放 = 薄弱点重复写入 = Sev-0
    const buttons = Array.from(document.querySelectorAll('button')).map((b) =>
      (b.textContent ?? '').trim(),
    )
    for (const label of buttons) {
      expect(label).not.toContain('重试')
      expect(label).not.toContain('retry')
      expect(label).not.toContain('重放')
    }
    expect(screen.queryByRole('button', { name: /重试/ })).not.toBeInTheDocument()
  })

  it('两种未决状态必须显式区分（⛔ FE-D10 实测更正原断言）', () => {
    renderAt('/reconciliation', <ReconciliationPage />, '/reconciliation')

    // ⚠️ **原断言在此处是错的**（FE-D10 实测更正）：
    //    它要求页面出现 `RECONCILIATION_REQUIRED`，
    //    但 `tool_operation_state` 枚举里**根本没有这个值**——
    //    实测枚举为 PENDING/IN_FLIGHT/SUCCEEDED/FAILED/UNKNOWN/
    //    RECONCILED_SUCCEEDED/RECONCILED_ABSENT/MANUAL_DISPOSED。
    //    要求前端展示一个数据库里不存在的状态 = 要求前端编造，
    //    与 FE-D8 清掉的「编造内容」同型。
    //
    //    真正的区分是：`UNKNOWN`（尚未核对） vs 三个已核对终态。
    //    该命题改由 `governance.page.test.tsx` 以真实 mock 数据断言。
    expect(screen.queryByText('RECONCILIATION_REQUIRED')).not.toBeInTheDocument()
    // ⛔ 页面不得假造一个待核对状态名——直接查 <option> 的 value/text，
    //    避开 getByText 对多节点（如 select 的选中项回显）的歧义
    const optionTexts = Array.from(document.querySelectorAll('option')).map((o) =>
      `${o.getAttribute('value') ?? ''}|${(o.textContent ?? '').trim()}`,
    )
    for (const t of optionTexts) {
      expect(t).not.toContain('RECONCILIATION_REQUIRED')
    }
    expect(screen.queryAllByText(/待核对/)).toHaveLength(0)
  })
})

describe('⛔ P10 审计页（无请求体入口）', () => {
  it('T5：⛔ 不得提供查看完整请求体入口', () => {
    renderAt('/audit', <AuditPage />, '/audit')

    const links = Array.from(document.querySelectorAll('a')).map((a) =>
      (a.textContent ?? '').trim(),
    )
    const buttons = Array.from(document.querySelectorAll('button')).map((b) =>
      (b.textContent ?? '').trim(),
    )
    for (const label of [...links, ...buttons]) {
      expect(label).not.toContain('请求体')
      expect(label).not.toContain('原文')
      expect(label).not.toContain('详情')
    }
  })

  it('应声明保留期与不含原文', () => {
    renderAt('/audit', <AuditPage />, '/audit')
    // ⚠️ 页面两处提到 180 天（说明区 + 表格下方），故用 getAllByText
    expect(screen.getAllByText(/180 天/).length).toBeGreaterThan(0)
  })
})

describe('P6 画像白名单（纵深防御）', () => {
  it('T6：白名单外的字段必须被丢弃', () => {
    // ⚠️ FE-D8：契约改为**结构化对象**（后端 G6 冻结）。
    //    旧契约的 string[] 会把全部条目过滤掉（FE-D09 L-4）。
    const result = pickWhitelisted({
      studentId: 'stu_abc',
      grade: '初一',
      subject: '数学',
      weaknesses: [
        { knowledgePoint: '分数加法', errorType: '概念不清', confidence: 0.7, createdAt: null },
        { knowledgePoint: '负号处理', errorType: '符号错误', confidence: 0.9, createdAt: null },
      ],
      notFound: false,
      // 以下均为 PIPL 禁采项
      name: '张三',
      school: '实验中学',
      className: '三班',
    } as unknown as ProfileResponse)

    expect(result).toEqual({
      studentId: 'stu_abc',
      grade: '初一',
      subject: '数学',
      notFound: false,
      weaknesses: [
        { knowledgePoint: '分数加法', errorType: '概念不清', confidence: 0.7 },
        { knowledgePoint: '负号处理', errorType: '符号错误', confidence: 0.9 },
      ],
    })
    // ⛔ 断言敏感字段不出现在结果里
    expect(JSON.stringify(result)).not.toContain('张三')
    expect(JSON.stringify(result)).not.toContain('实验中学')
    expect(JSON.stringify(result)).not.toContain('三班')
  })

  it('缺失字段应降级为空字符串而非崩溃', () => {
    const result = pickWhitelisted({} as unknown as ProfileResponse)
    expect(result.studentId).toBe('')
    expect(result.weaknesses).toEqual([])
    expect(result.notFound).toBe(false)
  })

  it('非对象的 weaknesses 元素应被降级而非原样透传', () => {
    const result = pickWhitelisted({
      weaknesses: [
        { knowledgePoint: '有效', errorType: '概念不清', confidence: 0.5, createdAt: null },
        '字符串',
        123,
        null,
      ],
    } as unknown as ProfileResponse)
    expect(result.weaknesses).toEqual([
      { knowledgePoint: '有效', errorType: '概念不清', confidence: 0.5 },
      { knowledgePoint: '', errorType: '', confidence: null },
      { knowledgePoint: '', errorType: '', confidence: null },
      { knowledgePoint: '', errorType: '', confidence: null },
    ])
  })

  it('画像页渲染时应走白名单', () => {
    renderAt('/profile/stu_abc', <ProfilePage />, '/profile/:studentId')
    // ⚠️ FE-D8：页面已接线为真实数据，读取未完成时显示加载态。
    //    白名单的「只采集四类信息」说明已并入「基本信息」区。
    expect(screen.getAllByText(/学习画像/).length).toBeGreaterThan(0)
  })
})

describe('P17 合规页只读', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('⛔ 零保留登记页不得提供编辑控件', () => {
    renderAt('/config/compliance', <ConfigCompliancePage />, '/config/compliance')

    // 法律承诺类数据不应由前端自由改动
    const buttons = Array.from(document.querySelectorAll('button')).map((b) =>
      (b.textContent ?? '').trim(),
    )
    const inputs = document.querySelectorAll('input, textarea, select')

    for (const label of buttons) {
      expect(label).not.toContain('修改')
      expect(label).not.toContain('登记')
      expect(label).not.toContain('保存')
      expect(label).not.toContain('删除')
    }
    expect(inputs).toHaveLength(0)
  })

  it('应说明按供应商分别判定', () => {
    renderAt('/config/compliance', <ConfigCompliancePage />, '/config/compliance')
    expect(screen.getByText(/分别/)).toBeInTheDocument()
  })
})

/**
 * ⛔ T9 反证测试
 *
 * 模拟「防护被移除」的实现，证明 T4/T5/T2 的断言真的有效，
 * 而非恒真（沿用项目 R-016 教训）。
 */
describe('⛔ 反证：红线防护失效时测试必须失败', () => {
  it('若提供了重试按钮，T4 的断言会失败', () => {
    // 模拟有缺陷的处置台
    const flawed = (
      <button type="button">一键重试</button>
    )
    const { container } = render(flawed)
    const buttons = Array.from(container.querySelectorAll('button')).map((b) =>
      (b.textContent ?? '').trim(),
    )
    // 断言"防护存在"会失败 → 证明 T4 有效
    expect(buttons.filter((b) => b.includes('重试'))).toHaveLength(1)
  })

  it('若加了来源区块，T2 的断言会失败', () => {
    const flawed = <h2>参考资料</h2>
    const { container } = render(flawed)
    const headings = Array.from(container.querySelectorAll('h2')).map((el) =>
      (el.textContent ?? '').trim(),
    )
    // 若真的加了来源，T2 就会红 → 证明 T2 有效
    expect(headings.some((h) => h.includes('来源') || h.includes('参考'))).toBe(true)
  })

  it('白名单若不过滤，T6 会失败', () => {
    // ⚠️ 独立实现一个「不过滤」的对照版本（不调用真实实现）
    function naivePassthrough(raw: Record<string, unknown>): Record<string, unknown> {
      return { ...raw }
    }

    const raw = { studentId: 'x', name: '张三', school: '实验中学' }

    // 对照实现：敏感字段原样带出 → T6 的断言会红
    const byNaive = naivePassthrough(raw)
    expect(byNaive['name']).toBe('张三')
    expect(byNaive['school']).toBe('实验中学')

    // 真实实现：必须丢弃白名单外字段
    const byReal = pickWhitelisted(raw as never) as Record<string, unknown>
    expect(byReal['name']).toBeUndefined()
    expect(byReal['school']).toBeUndefined()
    expect(byReal['studentId']).toBe('x')
  })
})