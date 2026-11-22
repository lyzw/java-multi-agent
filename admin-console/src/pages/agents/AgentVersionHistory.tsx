import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useState } from 'react'
import { Alert, App as AntApp, Button, Modal, Segmented, Space, Table, Tag, Typography, Empty } from 'antd'
import type { TableColumnsType } from 'antd'
import dayjs from 'dayjs'
import { DeleteOutlined, HistoryOutlined, SwapOutlined } from '@ant-design/icons'
import { cleanupAgentVersions, getAgentVersions } from '@/api/adminApi'
import type { AgentVersion } from '@/api/types'
import { emptyTextWhileLoading } from '@/shared/loadingState'

/**
 * Agent 版本历史面板 —— 展示历史记录、支持清理草稿、双击看 Diff。
 *
 * <p>⚠️ <b>清理只删未发布草稿</b>：已发布版本是线上生效过的，
 * 删除会让审计追溯和版本 Diff 丢失语义。端点 {@link cleanupAgentVersions}
 * 仅清理 {@code published_at IS NULL} 的行。
 *
 * <p>⚠️ <b>Diff 视图是轻量实现</b>：definition 是 JSON 字符串，
 * 我们把前后两个版本都 parse 成对象后按 key 比较 —— 已删 key 标红、
 * 新增 key 标绿、同 key 不同值标黄。不引 Monaco / diff-match-patch，
 * 避免大依赖；Agent definition 规模可控，这种方式足够。
 *
 * @param agentId 要展示版本历史的 Agent 标识
 */
export interface AgentVersionHistoryHandle {
  /** 打开指定版本与前一版本的 Diff（供「变更记录」双击等外部触发）。 */
  diffVersion: (versionNumber: number) => void
}

export const AgentVersionHistory = forwardRef<AgentVersionHistoryHandle, { agentId: string }>(
  function AgentVersionHistory({ agentId }, ref): JSX.Element {
  const { message, modal } = AntApp.useApp()
  const [rows, setRows] = useState<AgentVersion[]>([])
  const [loading, setLoading] = useState(false)
  const [diffFrom, setDiffFrom] = useState<AgentVersion | null>(null)
  const [diffTo, setDiffTo] = useState<AgentVersion | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const v = await getAgentVersions(agentId)
      setRows(v ?? [])
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载版本历史失败')
      setRows([])
    } finally {
      setLoading(false)
    }
  }, [agentId, message])

  useEffect(() => { void load() }, [load])

  const unpublishedCount = useMemo(
    () => rows.filter(r => r.publishedAt == null).length,
    [rows],
  )

  const handleCleanup = () => {
    if (unpublishedCount === 0) {
      message.info('没有可清理的未发布草稿')
      return
    }
    modal.confirm({
      title: '清理未发布草稿版本',
      content: <>
        将删除 <b>{unpublishedCount}</b> 条从未发布的草稿版本。<br/>
        已发布的历史版本（线上生效过）<b>不会被清理</b>，审计追溯不受影响。
      </>,
      okText: '确认清理',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          const resp = await cleanupAgentVersions(agentId)
          message.success(`已清理 ${resp.removed} 条草稿版本`)
          await load()
        } catch (e) {
          message.error(e instanceof Error ? e.message : '清理失败')
        }
      },
    })
  }

  const openDiffForVersion = useCallback((versionNumber: number) => {
    // 双击/外部触发 → 与前一版本对比；当前是最新版本则与次新对比。
    const idx = rows.findIndex(r => r.versionNumber === versionNumber)
    if (idx < 0 || rows.length < 2) {
      message.info('只有一个版本或无该版本记录，无法对比')
      return
    }
    if (idx < rows.length - 1) {
      // rows 按版本号降序 —— idx 小的是大版本号，idx+1 是前一个版本
      setDiffFrom(rows[idx + 1] ?? null)
      setDiffTo(rows[idx] ?? null)
    } else {
      // 当前是最旧版本，找它后面的那个
      setDiffFrom(rows[idx] ?? null)
      setDiffTo(rows[idx - 1] ?? null)
    }
    setDiffOpen(true)
  }, [rows, message])

  useImperativeHandle(ref, () => ({
    diffVersion: (versionNumber: number) => openDiffForVersion(versionNumber),
  }), [openDiffForVersion])

  const handleRowDoubleClick = (row: AgentVersion) => openDiffForVersion(row.versionNumber)

  // Diff 用 Modal 打开 —— 它是"详情里的弹窗"，
  // 交互模式（用户手动关掉）与全局详情配置是两个维度。
  const [diffOpen, setDiffOpen] = useState(false)

  const closeDiff = () => { setDiffOpen(false); setDiffFrom(null); setDiffTo(null) }

  const columns: TableColumnsType<AgentVersion> = [
    {
      title: '版本号',
      dataIndex: 'versionNumber',
      width: 100,
      fixed: 'left',
      render: (v: number, _r, idx) => (
        <Space>
          <strong>v{v}</strong>
          {idx === 0 && <Tag color="blue">最新</Tag>}
        </Space>
      ),
    },
    {
      title: '发布状态',
      dataIndex: 'publishedAt',
      width: 120,
      render: (v: string | null) => v
        ? <Tag color="green">已发布</Tag>
        : <Tag color="orange">草稿（未发布）</Tag>,
    },
    {
      title: '创建时间（版本变更）',
      dataIndex: 'createdAt',
      width: 190,
      sorter: (a, b) => dayjs(a.createdAt).valueOf() - dayjs(b.createdAt).valueOf(),
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm:ss'),
    },
    {
      title: '发布时间',
      dataIndex: 'publishedAt',
      width: 190,
      sorter: (a, b) =>
        dayjs(a.publishedAt ?? 0).valueOf() - dayjs(b.publishedAt ?? 0).valueOf(),
      render: (v: string | null) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '—',
    },
    {
      title: '对比',
      width: 140,
      fixed: 'right',
      render: (_: unknown, row) => (
        <Space size="small">
          <Button size="small" icon={<SwapOutlined />} onClick={() => handleRowDoubleClick(row)}>
            与上一版对比
          </Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Space style={{ marginBottom: 12 }} wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>
          <HistoryOutlined /> 变更历史（{rows.length} 条）
        </Typography.Title>
        {unpublishedCount > 0 && (
          <Tag color="orange">{unpublishedCount} 条未发布草稿</Tag>
        )}
        <Button
          size="small"
          danger={unpublishedCount > 0}
          icon={<DeleteOutlined />}
          onClick={handleCleanup}
          disabled={unpublishedCount === 0}
        >
          清理草稿
        </Button>
      </Space>

      {rows.length === 0 ? (
        emptyTextWhileLoading(loading, <Empty description="暂无版本记录" />)
      ) : (
        <Table<AgentVersion>
          size="small"
          rowKey="versionId"
          columns={columns}
          dataSource={rows}
          loading={loading}
          pagination={{ pageSize: 10, size: 'small' }}
          onRow={row => ({ onDoubleClick: () => handleRowDoubleClick(row) })}
        />
      )}

      <DiffModal
        open={diffOpen}
        onClose={closeDiff}
        from={diffFrom}
        to={diffTo}
      />
    </div>
  )
})

// ─────────────────────────────────────────────────────────────────
// Diff Modal —— 前后两个 Agent 版本的定义对比
// ─────────────────────────────────────────────────────────────────

interface DiffModalProps {
  open: boolean
  onClose: () => void
  from: AgentVersion | null
  to: AgentVersion | null
}

/**
 * Agent 定义版本 Diff（轻量 JSON 键值对比）。
 *
 * <p>⚠️ <b>只比顶层 key</b> —— Agent definition 的结构里嵌套层级深时，
 * 继续递归会把"子 agent 列表顺序调整"这类噪声也标成差异，反而看不清真正改了什么。
 * 我们把嵌套值转成 JSON 字符串逐 key 比，既能看到"哪个 key 变了"又不会过载。
 */
function DiffModal({ open, onClose, from, to }: DiffModalProps): JSX.Element | null {
  const [mode, setMode] = useState<'side' | 'unified'>('side')

  const diff = useMemo(() => {
    if (!from || !to) return []
    return diffAgentDefinition(from.definition, to.definition)
  }, [from, to])

  useEffect(() => { if (!open) setMode('side') }, [open])

  if (!open || !from || !to) return null

  return (
    <Modal
      open={open}
      onCancel={onClose}
      title={
        <Space>
          <SwapOutlined />
          <span>v{from.versionNumber} → v{to.versionNumber}</span>
          <Tag color={to.publishedAt ? 'green' : 'orange'}>
            v{to.versionNumber}{to.publishedAt ? '已发布' : '草稿'}
          </Tag>
        </Space>
      }
      width={960}
      footer={null}
      destroyOnHidden
    >
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        <div style={{ textAlign: 'right' }}>
          <Segmented
            value={mode}
            onChange={v => setMode(v as 'side' | 'unified')}
            options={[
              { label: '并排对比', value: 'side' },
              { label: '统一视图', value: 'unified' },
            ]}
          />
        </div>

        {diff.length === 0 ? (
          <Alert type="success" showIcon message="两版 definition 完全一致" />
        ) : mode === 'side' ? (
          <div style={{ display: 'flex', gap: 16 }}>
            <div style={{ flex: 1 }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>v{from.versionNumber}</Typography.Text>
              <DiffColumn diff={diff} side="from" />
            </div>
            <div style={{ flex: 1 }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>v{to.versionNumber}</Typography.Text>
              <DiffColumn diff={diff} side="to" />
            </div>
          </div>
        ) : (
          <DiffUnified diff={diff} />
        )}
      </Space>
    </Modal>
  )
}

type DiffEntry = {
  key: string
  from: string | null  // null = key 不存在（新增或删除）
  to: string | null
  change: 'added' | 'removed' | 'changed' | 'unchanged'
}

function diffAgentDefinition(rawFrom: string, rawTo: string): DiffEntry[] {
  let a: Record<string, unknown> = {}
  let b: Record<string, unknown> = {}
  try { a = JSON.parse(rawFrom ?? '{}') } catch { a = {} }
  try { b = JSON.parse(rawTo ?? '{}') } catch { b = {} }

  const keys = Array.from(new Set([...Object.keys(a), ...Object.keys(b)]))
  return keys
    .sort()
    .map(key => {
      const hasA = key in a
      const hasB = key in b
      const sv = (v: unknown): string => (typeof v === 'string' ? v : JSON.stringify(v ?? null))
      if (hasA && !hasB) {
        return { key, from: sv(a[key]), to: null, change: 'removed' as const }
      }
      if (!hasA && hasB) {
        return { key, from: null, to: sv(b[key]), change: 'added' as const }
      }
      const eq = JSON.stringify(a[key]) === JSON.stringify(b[key])
      return {
        key,
        from: sv(a[key]),
        to: sv(b[key]),
        change: eq ? 'unchanged' as const : 'changed' as const,
      }
    })
    .filter(d => d.change !== 'unchanged')
}

const CHANGE_COLOR: Record<DiffEntry['change'], string> = {
  added: '#1C6E5E',
  removed: '#B23A2E',
  changed: '#A9761B',
  unchanged: '#8B9791',
}

function DiffColumn({ diff, side }: { diff: DiffEntry[]; side: 'from' | 'to' }): JSX.Element {
  return (
    <div style={{
      border: '1px solid #E8EBE6', borderRadius: 6, padding: 8,
      maxHeight: 420, overflow: 'auto', fontFamily: 'monospace', fontSize: 12,
    }}>
      {diff.map(d => {
        const v = side === 'from' ? d.from : d.to
        const isNull = v == null
        const cls = d.change
        const bg = cls === 'added' && side === 'to' ? '#E9F5F1'
          : cls === 'added' && side === 'from' ? 'transparent'
          : cls === 'removed' && side === 'from' ? '#FCEAE7'
          : cls === 'removed' && side === 'to' ? 'transparent'
          : cls === 'changed' ? '#FFF5E3'
          : 'transparent'
        return (
          <div key={d.key} style={{ padding: '2px 4px', background: bg, whiteSpace: 'pre-wrap' }}>
            <span style={{ color: CHANGE_COLOR[cls], marginRight: 6 }}>
              {cls === 'added' ? '+' : cls === 'removed' ? '-' : '~'}
            </span>
            <span style={{ color: '#586862' }}>{d.key}:</span>{' '}
            <span style={{ color: CHANGE_COLOR[cls] }}>{isNull ? '(无)' : v}</span>
          </div>
        )
      })}
      {diff.length === 0 && <div style={{ color: '#8B9791' }}>（无差异）</div>}
    </div>
  )
}

function DiffUnified({ diff }: { diff: DiffEntry[] }): JSX.Element {
  return (
    <div style={{
      border: '1px solid #E8EBE6', borderRadius: 6, padding: 8,
      maxHeight: 420, overflow: 'auto', fontFamily: 'monospace', fontSize: 12,
    }}>
      {diff.map(d => (
        <div key={d.key} style={{ padding: '3px 4px', borderBottom: '1px dashed #F4F5F1' }}>
          <div>
            <Tag color={
              d.change === 'added' ? 'green'
              : d.change === 'removed' ? 'red'
              : 'gold'
            } style={{ marginRight: 6 }}>
              {d.change === 'added' ? '新增' : d.change === 'removed' ? '删除' : '修改'}
            </Tag>
            <span style={{ color: '#1B2624', fontWeight: 600 }}>{d.key}</span>
          </div>
          {d.change !== 'added' && d.from != null && (
            <div style={{ color: '#B23A2E', paddingLeft: 12 }}>- {d.from}</div>
          )}
          {d.change !== 'removed' && d.to != null && (
            <div style={{ color: '#1C6E5E', paddingLeft: 12 }}>+ {d.to}</div>
          )}
        </div>
      ))}
      {diff.length === 0 && <div style={{ color: '#8B9791', textAlign: 'center', padding: 16 }}>（无差异）</div>}
    </div>
  )
}
