// @vitest-environment jsdom
import { describe, it, expect, vi, beforeAll } from 'vitest'
import { render, screen, within, fireEvent } from '@testing-library/react'
import { ParamListEditor } from '@/pages/catalog/ParamListEditor'
import { PolicyListEditor } from '@/pages/catalog/PolicyListEditor'
import { StringListEditor } from '@/pages/catalog/StringListEditor'

// antd v5 渲染期读 matchMedia；jsdom 默认没有，补一个最小桩
beforeAll(() => {
  if (!window.matchMedia) {
    window.matchMedia = ((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })) as unknown as typeof window.matchMedia
  }
})

/**
 * 取 antd 表格里的**数据行**（排除空态占位行 {@code ant-table-placeholder}）。
 *
 * ⚠️ 断言行数必须用数据行而不是「表格行」：空列表时表格也会渲染一行占位，
 * 用 {@code tbody tr} 会把空态也算进去，测不出「新增一行到底有没有生效」。
 */
function tableRows(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll('tbody tr.ant-table-row'))
}

/**
 * 打开某一行 Select 的下拉并选中指定选项。
 *
 * ⚠️ antd v5 的 Select 必须先 {@code mouseDown} 才会展开下拉（选项渲染在 body 下的弹层里），
 * 且弹层里的选项与已选中项**可能带相同的 title**，故要限定在下拉容器内取。
 */
async function chooseOption(
  container: HTMLElement,
  rowIndex: number,
  label: string,
): Promise<void> {
  const row = tableRows(container)[rowIndex]
  if (!row) throw new Error(`第 ${rowIndex} 行不存在`)
  const trigger = row.querySelector('.ant-select-selector')
  if (!trigger) throw new Error('未找到 Select 触发器')
  fireEvent.mouseDown(trigger)
  const hits = await screen.findAllByTitle(label)
  const option = hits.find((el) => el.closest('.ant-select-dropdown')) ?? hits[0]
  if (!option) throw new Error(`未找到选项：${label}`)
  fireEvent.click(option)
}

/** 取某一行里带指定 aria-label 的输入框。 */
function rowInput(row: HTMLElement, label: string): HTMLInputElement {
  const input = row.querySelector(`input[aria-label="${label}"]`)
  if (!input) throw new Error(`未找到输入框：${label}`)
  return input as HTMLInputElement
}

/** 两个参数的合法入参结构。 */
const PARAM_SCHEMA = JSON.stringify({
  type: 'object',
  properties: {
    alpha: { type: 'string', description: '甲参数' },
    beta: { type: 'number' },
  },
  required: ['beta'],
})

/** 一条 in_allowlist 策略。 */
const POLICY_TEXT = JSON.stringify([{ param: 'mode', op: 'in_allowlist', value: ['a', 'b'] }])

describe('ParamListEditor · 入参结构参数列表', () => {
  it('① 传入含 2 个参数的合法 JSON → 渲染出 2 行', () => {
    const { container } = render(<ParamListEditor value={PARAM_SCHEMA} />)
    expect(tableRows(container)).toHaveLength(2)
    expect(screen.getByDisplayValue('alpha')).toBeTruthy()
    expect(screen.getByDisplayValue('beta')).toBeTruthy()
  })

  it('② 点「+ 新增参数」→ 行数 +1', () => {
    const { container } = render(<ParamListEditor value={PARAM_SCHEMA} />)
    fireEvent.click(screen.getByRole('button', { name: '+ 新增参数' }))
    expect(tableRows(container)).toHaveLength(3)
  })

  it('③ 修改某行的「参数名」→ onChange 回传文本里参数名已更新', () => {
    const onChange = vi.fn<(text: string) => void>()
    const { container } = render(<ParamListEditor value={PARAM_SCHEMA} onChange={onChange} />)

    fireEvent.change(rowInput(tableRows(container)[0]!, '参数名'), { target: { value: 'gamma' } })

    expect(onChange).toHaveBeenCalledTimes(1)
    const payload = JSON.parse(onChange.mock.calls[0]![0])
    expect(payload.properties.gamma).toBeTruthy()
    // ⚠️ 改名后旧名字必须消失（不能两份都留着）
    expect(payload.properties.alpha).toBeUndefined()
  })

  it('④ 点某行的「删除」→ 该参数名不再出现在回传文本里', () => {
    const onChange = vi.fn<(text: string) => void>()
    const { container } = render(<ParamListEditor value={PARAM_SCHEMA} onChange={onChange} />)

    fireEvent.click(within(tableRows(container)[0]!).getByRole('button', { name: '删除' }))

    const payload = JSON.parse(onChange.mock.calls[0]![0])
    expect(payload.properties.alpha).toBeUndefined()
    expect(payload.properties.beta).toBeTruthy()
  })

  it('⑤ 传入非法 JSON → 渲染错误提示，且能看到原文', () => {
    const bad = '{ 这不是 JSON'
    const { container } = render(<ParamListEditor value={bad} />)

    expect(screen.getByText('入参结构不是合法 JSON，无法解析为参数列表')).toBeTruthy()
    expect(screen.getByText('入参结构（inputSchema）不是合法 JSON')).toBeTruthy()
    const area = container.querySelector('textarea') as HTMLTextAreaElement
    expect(area.value).toBe(bad)
  })

  it('⑥ 序列化失败（两个参数同名）→ 渲染「参数定义有误」', () => {
    const onChange = vi.fn<(text: string) => void>()
    const { container } = render(<ParamListEditor value={PARAM_SCHEMA} onChange={onChange} />)

    fireEvent.click(screen.getByRole('button', { name: '+ 新增参数' }))
    const newRow = tableRows(container)[2]!
    fireEvent.change(rowInput(newRow, '参数名'), { target: { value: 'alpha' } })

    expect(screen.getByText('参数定义有误')).toBeTruthy()
    expect(screen.getByText('参数名重复：alpha')).toBeTruthy()
    // ⚠️ 序列化失败时不得回传（父级仍持有上一次合法文本）
    expect(onChange).not.toHaveBeenCalled()
    // ⚠️ 但行内容**保留用户刚改的样子**（不得回滚成空参数名）
    expect(rowInput(tableRows(container)[2]!, '参数名').value).toBe('alpha')
  })

  it('⑥-2 兜底原文改成合法 JSON 后自动切回表格', () => {
    const onChange = vi.fn<(text: string) => void>()
    const { container } = render(<ParamListEditor value="{ 这不是 JSON" onChange={onChange} />)

    const area = container.querySelector('textarea') as HTMLTextAreaElement
    fireEvent.change(area, { target: { value: PARAM_SCHEMA } })

    expect(onChange).toHaveBeenCalledWith(PARAM_SCHEMA)
    expect(tableRows(container)).toHaveLength(2)
    expect(screen.getByDisplayValue('alpha')).toBeTruthy()
  })

  it('⑥-3 空列表时表格仍渲染，并显示空态文案', () => {
    const { container } = render(<ParamListEditor value="" />)
    expect(tableRows(container)).toHaveLength(0)
    expect(screen.getByText('暂无参数，点击『+ 新增参数』添加')).toBeTruthy()
    expect(screen.getByRole('button', { name: '+ 新增参数' })).toBeTruthy()
  })
})

describe('PolicyListEditor · 参数策略列表', () => {
  it('⑦ 传入一条 in_allowlist 策略 → 渲染 1 行，页面出现 mode 与 a,b', () => {
    const { container } = render(<PolicyListEditor value={POLICY_TEXT} />)
    expect(tableRows(container)).toHaveLength(1)
    expect(screen.getByDisplayValue('mode')).toBeTruthy()
    expect(screen.getByDisplayValue('a,b')).toBeTruthy()
  })

  it('⑧ 点「+ 新增策略」→ 行数 +1', () => {
    const { container } = render(<PolicyListEditor value={POLICY_TEXT} />)
    fireEvent.click(screen.getByRole('button', { name: '+ 新增策略' }))
    expect(tableRows(container)).toHaveLength(2)
  })

  it('⑨ 回传文本里 value 的形状随 op 变：in_allowlist 是数组、lte 是数字', async () => {
    const onChange = vi.fn<(text: string) => void>()
    const { container } = render(<PolicyListEditor value={POLICY_TEXT} onChange={onChange} />)

    // in_allowlist：逗号分隔 → 产出仍是**数组**
    fireEvent.change(rowInput(tableRows(container)[0]!, '取值'), { target: { value: 'a,b,c' } })
    const arrayPayload = JSON.parse(onChange.mock.calls[0]![0])
    expect(arrayPayload[0].value).toEqual(['a', 'b', 'c'])

    // 切到 lte：同一个输入框的值必须变成**数字**
    await chooseOption(container, 0, 'lte（数值不得大于阈值）')
    const lteInput = rowInput(tableRows(container)[0]!, '取值')
    expect(lteInput.getAttribute('placeholder')).toBe('阈值（数值）')
    fireEvent.change(lteInput, { target: { value: '10' } })

    const numberPayload = JSON.parse(onChange.mock.calls[onChange.mock.calls.length - 1]![0])
    expect(numberPayload[0].value).toBe(10)
    expect(typeof numberPayload[0].value).toBe('number')
  })

  it('⑩ 重复的 (param, op) → 渲染错误提示', () => {
    const duplicated = JSON.stringify([
      { param: 'mode', op: 'in_allowlist', value: ['a'] },
      { param: 'mode', op: 'in_allowlist', value: ['b'] },
    ])
    const { container } = render(<PolicyListEditor value={duplicated} />)

    expect(tableRows(container)).toHaveLength(2)
    expect(screen.getByText('参数策略有误')).toBeTruthy()
    expect(screen.getByText('参数 mode 的策略 in_allowlist 重复声明')).toBeTruthy()
  })
})

describe('StringListEditor · 字符串列表', () => {
  it('⑪ 传入 2 个条目 → 渲染 2 行', () => {
    const { container } = render(<StringListEditor value={['x', 'y']} />)
    expect(container.querySelectorAll('input')).toHaveLength(2)
    expect(screen.getByDisplayValue('x')).toBeTruthy()
    expect(screen.getByDisplayValue('y')).toBeTruthy()
  })

  it('⑫ 点「+ 新增一行」→ 行数 +1，onChange 收到长度为 3 的数组', () => {
    const onChange = vi.fn<(next: string[]) => void>()
    const { container } = render(<StringListEditor value={['x', 'y']} onChange={onChange} />)

    fireEvent.click(screen.getByRole('button', { name: '+ 新增一行' }))

    expect(container.querySelectorAll('input')).toHaveLength(3)
    expect(onChange).toHaveBeenCalledTimes(1)
    const next = onChange.mock.calls[0]![0]
    expect(next).toHaveLength(3)
    expect(next).toEqual(['x', 'y', ''])
  })

  it('⑬ 点删除 → 行数 -1', () => {
    const onChange = vi.fn<(next: string[]) => void>()
    const { container } = render(<StringListEditor value={['x', 'y']} onChange={onChange} />)

    fireEvent.click(screen.getAllByRole('button', { name: '删除' })[0]!)

    expect(container.querySelectorAll('input')).toHaveLength(1)
    expect(onChange.mock.calls[0]![0]).toEqual(['y'])
  })

  it('⑭ 删除最后一行 → 得到空数组（不自动补空行），仍能再新增', () => {
    const onChange = vi.fn<(next: string[]) => void>()
    const { container } = render(<StringListEditor value={['x']} onChange={onChange} />)

    fireEvent.click(screen.getAllByRole('button', { name: '删除' })[0]!)

    // ⚠️ 「空数组」与「一行空字符串」语义不同：不得凭空补一行
    expect(onChange.mock.calls[0]![0]).toEqual([])
    expect(container.querySelectorAll('input')).toHaveLength(0)
    expect(screen.getByText('暂无条目')).toBeTruthy()

    // 空列表时仍要能点「+ 新增一行」
    fireEvent.click(screen.getByRole('button', { name: '+ 新增一行' }))
    expect(onChange.mock.calls[1]![0]).toEqual([''])
  })
})
