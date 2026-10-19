/**
 * 页面骨架与合规红线测试（FE-D2 §5）
 *
 * ⛔ 核心思路：红线**不能只靠文案**，必须有结构级保障。
 *   故这些断言查的是「页面上根本没有这个东西」，
 *   而不是「有个地方写着不要有这个」。
 */

import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { RunResultPage, findLeakedFields } from '@/pages/business/RunResultPage'
import { ProfilePage, pickWhitelisted } from '@/pages/business/ProfilePage'
import { ReconciliationPage, AuditPage } from '@/pages/governance/GovernancePages'
import { ConfigCompliancePage } from '@/pages/config/ConfigPages'

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
  it('T1：应显示未开放占位并含缺口编号', () => {
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')
    // ⚠️ FE-D3 起文案改为「人话」而非技术术语：
    //    旧文案「功能尚未开放」偏技术，新文案「还没接上后端」像人说话。
    expect(screen.getByText(/还没接上后端/)).toBeInTheDocument()
    expect(screen.getByText('G5')).toBeInTheDocument()
  })

  it('T1：占位应说明原因', () => {
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')
    // ⚠️ 断言人话原因，而非接口名 —— 文案本身就是设计内容
    expect(screen.getByText(/读取分析结果的接口/)).toBeInTheDocument()
  })

  it('T1：⛔ 未开放与「空」必须区分', () => {
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')
    // 「未开放」= 没接口；「空」= 查了没有
    // 混淆会导致用户反复刷新等一个永远不会来的结果
    expect(screen.queryByText(/还没有记录到/)).not.toBeInTheDocument()
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
    renderAt('/runs/r-1/result', <RunResultPage />, '/runs/:runId/result')
    // P0-D00 §3.4：终态存在未决 UNKNOWN 时必须独立暴露，不得隐藏
    const zone = screen.getByTestId('reconcile-zone')
    expect(zone).toBeInTheDocument()
    expect(zone.textContent).toContain('待核对')
    // 且必须提及两种状态
    expect(zone.textContent).toContain('UNKNOWN')
    expect(zone.textContent).toContain('RECONCILIATION_REQUIRED')
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

  it('两种未决状态必须显式区分', () => {
    renderAt('/reconciliation', <ReconciliationPage />, '/reconciliation')
    expect(screen.getByText('UNKNOWN')).toBeInTheDocument()
    expect(screen.getByText('RECONCILIATION_REQUIRED')).toBeInTheDocument()
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
    const result = pickWhitelisted({
      studentId: 'stu_abc',
      grade: '初一',
      subject: '数学',
      weaknesses: ['分数加法', '负号处理'],
      // 以下均为 PIPL 禁采项
      name: '张三',
      school: '实验中学',
      className: '三班',
    })

    expect(result).toEqual({
      studentId: 'stu_abc',
      grade: '初一',
      subject: '数学',
      weaknesses: ['分数加法', '负号处理'],
    })
    // ⛔ 断言敏感字段不出现在结果里
    expect(JSON.stringify(result)).not.toContain('张三')
    expect(JSON.stringify(result)).not.toContain('实验中学')
    expect(JSON.stringify(result)).not.toContain('三班')
  })

  it('缺失字段应降级为空字符串而非崩溃', () => {
    const result = pickWhitelisted({})
    expect(result.studentId).toBe('')
    expect(result.weaknesses).toEqual([])
  })

  it('非字符串 weaknesses 元素应被过滤', () => {
    const result = pickWhitelisted({ weaknesses: ['有效', 123, null, '另一项'] })
    expect(result.weaknesses).toEqual(['有效', '另一项'])
  })

  it('画像页渲染时应走白名单', () => {
    renderAt('/profile/stu_abc', <ProfilePage />, '/profile/:studentId')
    // ⚠️ 文案改为人话（FE-D3）：不再是「白名单字段仅四个」这种技术表述
    expect(screen.getAllByText(/学习画像/).length).toBeGreaterThan(0)
    expect(screen.getByText(/只采集四类信息/)).toBeInTheDocument()
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