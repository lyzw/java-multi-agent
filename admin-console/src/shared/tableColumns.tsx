import { useCallback, useEffect, useMemo, useState } from 'react'
import type { Key, ReactNode } from 'react'
import { Button, Checkbox, Dropdown, Space } from 'antd'
import type { TableColumnsType } from 'antd'
import { SettingOutlined } from '@ant-design/icons'

/**
 * 列表页「列设置」——让宽度大的表格默认只显示决策必需列，其余列按需勾选。
 *
 * 背景：平台控制台大多是 10~16 列的数据表，固定操作列 + 横向滚动在 1440 屏下
 * 一屏只能看到一半列；把低频列（版本 / 指纹 / 标签等）收进开关，默认视图即可
 * 在一屏内读完，同时不删字段、不改变任何数据契约。
 *
 * 选择按页面 key 存 localStorage：运营在同一页面上反复切换筛选 / 分页时，
 * 列偏好不应被重置；不同页面互不干扰。
 */

type ColumnMeta = {
  key?: Key
  /** ⚠️ antd 的 dataIndex 还允许 number / (string|number)[]，这里只取字符串形态兜底 ——
   *  声明成 unknown 接住全部形态，函数内部再做类型收窄。 */
  dataIndex?: unknown
  /** ⚠️ antd 的 title 可以是渲染函数（ColumnTitle<T>），不是纯 ReactNode ——
   *  这里只做「字符串标题兜底」判断，声明成 unknown 接住两种形态。 */
  title?: unknown
}

/** AntD 列的稳定标识：优先显式 key，其次 dataIndex，最后标题兜底。 */
export function columnKeyOf(column: ColumnMeta): string {
  if (column.key !== undefined && column.key !== null) return String(column.key)
  const dataIndex = column.dataIndex
  if (typeof dataIndex === 'string') return dataIndex
  if (Array.isArray(dataIndex)) return dataIndex.join('.')
  if (typeof column.title === 'string') return column.title
  return ''
}

export interface ColumnVisibilityOptions {
  /** localStorage 隔离键（页面级唯一，例如 'console-agent-registry'） */
  storageKey: string
  /** 默认隐藏的列 key；首次访问或无缓存时生效 */
  defaultHidden?: string[]
  /** 不允许隐藏的列（主键列 / 操作列），保证表格始终可用 */
  lockedKeys?: string[]
  /** 按钮文字 */
  label?: string
}

export interface ColumnVisibilityResult<T> {
  /** 过滤后的列，直接传给 Table 的 columns */
  visibleColumns: TableColumnsType<T>
  /** 放在 Card extra / 页面工具栏的按钮 */
  columnSetting: ReactNode
  hiddenKeys: string[]
}

/** 列显隐状态 + 下拉勾选面板。 */
export function useColumnVisibility<T>(
  columns: TableColumnsType<T>,
  options: ColumnVisibilityOptions,
): ColumnVisibilityResult<T> {
  const { storageKey, defaultHidden = [], lockedKeys = [], label = '列设置' } = options
  const storageId = `admin-console:columns:${storageKey}`

  const [hiddenKeys, setHiddenKeys] = useState<string[]>(() => {
    try {
      const raw = localStorage.getItem(storageId)
      if (raw !== null) {
        const parsed: unknown = JSON.parse(raw)
        if (Array.isArray(parsed)) {
          return parsed.filter((item): item is string => typeof item === 'string')
        }
      }
    } catch {
      // 隐私模式 / 存储不可用时退回默认列集，不影响页面功能
    }
    return defaultHidden
  })

  useEffect(() => {
    try {
      localStorage.setItem(storageId, JSON.stringify(hiddenKeys))
    } catch {
      // 同上：存不进去只影响跨刷新记忆，不影响当前页面
    }
  }, [hiddenKeys, storageId])

  const keyed = useMemo(
    () => columns.map((column) => ({ key: columnKeyOf(column), column })),
    [columns],
  )

  const visibleColumns = useMemo(
    () => keyed.filter(({ key }) => !hiddenKeys.includes(key)).map(({ column }) => column),
    [keyed, hiddenKeys],
  )

  const toggle = useCallback(
    (key: string) => {
      if (lockedKeys.includes(key)) return
      setHiddenKeys((previous) =>
        previous.includes(key)
          ? previous.filter((item) => item !== key)
          : [...previous, key],
      )
    },
    [lockedKeys],
  )

  const columnSetting = (
    <Dropdown
      trigger={['click']}
      placement="bottomRight"
      arrow={false}
      dropdownRender={() => (
        <div className="dc-column-panel">
          <div className="dc-column-panel__head">
            <span>{label}</span>
            <Space size={2}>
              <Button type="link" size="small" onClick={() => setHiddenKeys([])}>
                全部显示
              </Button>
              <Button type="link" size="small" onClick={() => setHiddenKeys(defaultHidden)}>
                恢复默认
              </Button>
            </Space>
          </div>
          <div className="dc-column-panel__body">
            {keyed.map(({ key, column }) => {
              const title = typeof column.title === 'string' ? column.title : key
              return (
                <Checkbox
                  key={key || title}
                  checked={!hiddenKeys.includes(key)}
                  disabled={lockedKeys.includes(key)}
                  onChange={() => toggle(key)}
                >
                  {title}
                </Checkbox>
              )
            })}
          </div>
        </div>
      )}
    >
      <Button icon={<SettingOutlined />} aria-label={label}>
        {label}
      </Button>
    </Dropdown>
  )

  return { visibleColumns, columnSetting, hiddenKeys }
}
