/**
 * 结构完整性测试（FE-D4 §3 T3/T4/T5/T6/T7）
 *
 * 本文件测的不是「页面好不好看」，而是**结构上有没有缺陷**。
 * 这些缺陷在视觉检查里看不出来（裸奔的元素仍有内容，只是没样式），
 * 只有逐项断言才能发现。
 *
 * ⚠️ 与 design.spec.test.ts 的分工：
 *   design.spec 测**设计纪律**（禁阴影、禁微光、红只做批改…）
 *   本文件测**结构与契约完整性**（main 不嵌套、CSS 有定义、占位统一…）
 */

import { describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { RunProgressPage } from '@/pages/RunProgressPage'
import { Field } from '@/shared/Field'
import { SessionProvider } from '@/auth/session'

const here = dirname(fileURLToPath(import.meta.url))
const srcDir = join(here, '..', 'src')
const read = (p: string): string => readFileSync(join(srcDir, p), 'utf8')

/**
 * ⚠️ 剥离注释后的源码 —— 断言只针对**真实代码**。
 *
 * 为什么必须剥离：本项目大量注释是**必要的设计说明**
 * （如「这里曾是 <main className="new-run">，因嵌套而迁移」）。
 * design.spec.test.ts 已踩过同一个坑：最初断言「P9 段落不含『一键重试』」，
 * 结果把解释为什么不提供重试的注释也判违规 → 改为只查实际渲染元素。
 *
 * 所以这里统一：注释里可以写任何字样，代码里不行。
 */
function readCode(p: string): string {
  const raw = read(p)
  // 块注释
  let out = raw.replace(/\/\*[\s\S]*?\*\//g, '')
  // 行注释（保守处理：不去动字符串内的 //，本项目 URL 均无反斜杠转义场景）
  out = out.replace(/(^|[^:'"`])\/\/[^\n]*/g, '$1')
  return out
}

const layout = read('styles/layout.css')
const tokens = read('styles/tokens.css')

/** AppShell 已渲染一个 main；布局内的页面不得再渲染 main */
const SHELL_LEVEL_PAGES = [
  'pages/RunProgressPage.tsx',
  'pages/NewRunPage.tsx',
  'pages/DashboardPage.tsx',
  'pages/business/RunResultPage.tsx',
  'pages/business/ProfilePage.tsx',
  'pages/business/RunSearchPage.tsx',
  'pages/config/ConfigPages.tsx',
  'pages/governance/GovernancePages.tsx',
  'pages/ops/HealthPage.tsx',
]

describe('T3：⛔ 布局内页面不得嵌套 main（F-16）', () => {
  it('AppShell 自身应渲染唯一的主内容区', () => {
    const shell = read('layouts/AppShell.tsx')
    expect(shell).toMatch(/<main className="main">/)
  })

  it.each(SHELL_LEVEL_PAGES)('%s 不得再渲染 <main>', (page) => {
    const source = readCode(page)
    // ⛔ main 嵌套 main 违反 HTML 规范：屏幕阅读器会当成第二个主内容区
    expect(source).not.toMatch(/<main[\s>]/)
  })

  it('登录页不在 AppShell 内，保留 <main> 是合法的', () => {
    // 这条是**护栏**：防止有人把登录页也改成 div，破坏其独立语义
    expect(readCode('pages/LoginPage.tsx')).toMatch(/<main className="login">/)
  })
})

describe('T4：F-17 缺失的 CSS 类已有定义', () => {
  /**
   * ⚠️ 不能断言 `.timeline__step {` 这样的完整串 ——
   *     实现里三个同类标签是合并选择器写的（`.a, .b, .c {`），
   *     断言完整串会假失败。改为「类名出现在选择器位置」。
   */
  it.each([
    'timeline',
    'timeline__seq',
    'timeline__type',
    'timeline__op',
    'timeline__step',
    'timeline__tool',
    'timeline__time',
    'error-view',
    'error-view__message',
    'error-view__hint',
  ])('%s 应在主样式层有定义', (cls) => {
    expect(layout).toMatch(new RegExp(`\\.${cls}[\\s,:{]`))
  })

  it('时间线列表项应用线分隔而非阴影', () => {
    expect(layout).toMatch(/\.timeline li\s*\{[^}]*border-bottom:\s*1px solid var\(--rule\)/s)
  })

  it('⛔ 不应再残留 new-run / run-progress 这两个已废弃的页面根类', () => {
    // 页面已迁移到 main__head，旧类不应继续出现在组件里（注释里的迁移说明除外）
    expect(readCode('pages/NewRunPage.tsx')).not.toContain('new-run')
    expect(readCode('pages/RunProgressPage.tsx')).not.toContain('run-progress')
  })

  it('时间线样式应遵守版式纪律（无圆角阴影）', () => {
    expect(layout).not.toMatch(/box-shadow:\s*(?!none)/i)
  })
})

describe('T5：进度页未开放占位应可追溯缺口（F-18）', () => {
  it('应使用 Unavailable 而非散落的 NotAvailable', () => {
    // 只查真实代码：注释里要解释「为什么不再是 NotAvailable」，那是设计说明
    const source = readCode('pages/RunProgressPage.tsx')
    expect(source).not.toMatch(/NotAvailable/)
    expect(source).toMatch(/Unavailable/)
  })

  it('归因结果占位应带阻塞编号 G5 与承接阶段', () => {
    // ⚠️ RunProgressPage 内部 useSession() → 必须包 SessionProvider
    // ⚠️ 还必须给出 :runId 路由参数，否则走「缺少运行标识」的另一分支
    render(
      <MemoryRouter initialEntries={['/runs/run-abc-123']}>
        <SessionProvider>
          <Routes>
            <Route path="/runs/:runId" element={<RunProgressPage />} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>,
    )
    expect(screen.getByText(/归因结果还没接上后端/)).toBeInTheDocument()
    expect(screen.getByText('G5')).toBeInTheDocument()
    // 承接阶段也应如实呈现
    expect(screen.getByText(/P3 阶段承接/)).toBeInTheDocument()
  })

  it('缺少运行标识时也应带阻塞编号', () => {
    // 无匹配路由 → useParams 拿不到 runId → 走缺少标识分支
    render(
      <MemoryRouter initialEntries={['/runs/']}>
        <SessionProvider>
          <RunProgressPage />
        </SessionProvider>
      </MemoryRouter>,
    )
    expect(screen.getByText(/进度追踪还没接上后端/)).toBeInTheDocument()
    expect(screen.getByText('G3')).toBeInTheDocument()
  })
})

describe('T6：Field 同 kind 多条违规的 key 必须唯一（F-20）', () => {
  /**
   * ⚠️ 探针实测结论（推翻了我最初的判断）：
   *    用 `key={violation.kind}` 时，两条**都会渲染**（不是漏渲染），
   *    但 React 会发出
   *      "Encountered two children with the same key ... the behavior is
   *       unsupported and could change in a future version."
   *
   *    所以真实危害是：
   *      ① 控制台警告噪音 —— 会掩盖其他真实警告
   *      ② **未定义行为**：React 官方明说将来版本可能变成
   *         duplicated and/or omitted（重复或遗漏）
   *      ③ 列表内容变化（中间增删）时 fiber 复用错位，DOM 状态错乱
   *
   *    ⛔ 最初我断言「第二条不渲染」——**被探针证伪**，两条都渲染。
   *       据此改为断言「无重复 key 警告」，这才是可证伪的判据。
   */
  it('两个手机号不应触发 React 重复 key 警告', () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    render(
      <Field
        name="studentSolution"
        label="我的解题过程"
        value=""
        onChange={() => {}}
        violations={[
          { kind: 'MOBILE', message: '检测到手机号（第 1 处）', rule: 'MOBILE_CN' },
          { kind: 'MOBILE', message: '检测到手机号（第 2 处）', rule: 'MOBILE_CN' },
        ]}
      />,
    )

    // 两条内容都必须渲染（漏一条同样是缺陷）
    expect(screen.getByText('检测到手机号（第 1 处）')).toBeInTheDocument()
    expect(screen.getByText('检测到手机号（第 2 处）')).toBeInTheDocument()

    const keyWarnings = errorSpy.mock.calls
      .map((args) => args.map(String).join(' '))
      .filter((text) => text.includes('same key'))
    expect(keyWarnings).toEqual([])

    errorSpy.mockRestore()
  })

  it('⛔ 反证：key 改回 violation.kind 时本用例必须转红', () => {
    // 这条不测产品代码，测的是「上面的断言确实有牙齿」——
    // 证明重复 key 是**可检测**的，而不是测试写松了。
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    function DupKey(): React.ReactElement {
      const items = [
        { kind: 'MOBILE', message: 'a' },
        { kind: 'MOBILE', message: 'b' },
      ]
      return (
        <ul>
          {items.map((v) => (
            <li key={v.kind}>{v.message}</li>
          ))}
        </ul>
      )
    }
    render(<DupKey />)

    const sawWarning = errorSpy.mock.calls
      .map((args) => args.map(String).join(' '))
      .some((text) => text.includes('same key'))
    errorSpy.mockRestore()

    expect(sawWarning).toBe(true) // 重复 key 确实会被告警 → 上面的断言有效
  })
})

describe('T7：degraded 应为累计判定（F-19）', () => {
  it('累计事件数 > 0 时永不应标记降级', () => {
    // 读源码断言：degraded 必须由 merged（累计）而非 newEvents（本轮）决定
    const source = readCode('features/run/eventStream.ts')
    expect(source).toMatch(/degraded:\s*merged\.length === 0/)
    expect(source).not.toMatch(/degraded:\s*newEvents\.length === 0/)
  })
})

describe('样式 token 引用完整性', () => {
  it('新样式引用的 token 均已定义', () => {
    for (const token of [
      '--font-mono',
      '--rule',
      '--rule-strong',
      '--ink',
      '--ink-2',
      '--ink-3',
      '--t-note',
      '--s-1',
      '--s-2',
      '--s-3',
    ]) {
      expect(tokens).toContain(`${token}:`)
    }
  })
})
