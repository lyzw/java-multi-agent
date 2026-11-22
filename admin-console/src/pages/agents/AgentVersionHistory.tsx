import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useState } from 'react'
import {
  Alert, App as AntApp, Button, Empty, Modal, Segmented, Space, Table, Tag, Tooltip, Typography,
} from 'antd'
import type { TableColumnsType } from 'antd'
import dayjs from 'dayjs'
import { DeleteOutlined, DiffOutlined, HistoryOutlined, SwapOutlined } from '@ant-design/icons'
import { cleanupAgentVersions, getPlatformAgentDefinitionVersions } from '@/api/adminApi'
import type { AgentDefinition } from '@/api/types'
import { diffJson, diffSummary, type JsonDiffLine } from '@/shared/jsonDiff'
import { emptyTextWhileLoading } from '@/shared/loadingState'

/**
 * Agent 变更历史（权威定义 {@code cap_agent_def} 的不可变版本行）。
 *
 * <p>⚠️ <b>数据源是权威表不是 {@code agent_version}</b>：后者只是历史快照归档；
 * 运行期语义（提示词 / 模型 / 生成参数 / 编排 / 启用技能）以 {@code cap_agent_def} 为准。
 * 拿旧快照做 diff 会对比出「看起来变了、运行期其实没变」的假差异。
 *
 * <p>⚠️ <b>清理只删未发布草稿</b>：已发布版本是线上生效过的，删除会让审计追溯
 * 与变更对比失去语义。
 *
 * <p>⚠️ <b>双击 = 与该条变更的「前一版本」做配置对比</b>：
 * 变更记录指向 {@code targetVersion}，对比对象是它的<b>前一版本</b>
 * （版本号紧邻的上一个），这样看到的就是「这次保存到底改了什么」。
 */
export interface AgentVersionHistoryHandle {
  /** 打开指定版本与前一版本的配置对比（供「变更记录」双击触发）。 */
  diffVersion: (versionNumber: number) => void
}

export const AgentVersionHistory = forwardRef<AgentVersionHistoryHandle, { agentId: string }>(
  function AgentVersionHistory({ agentId }, ref): JSX.Element {
    const { message, modal } = AntApp.useApp()
    const [rows, setRows] = useState<AgentDefinition[]>([])
    const [loading, setLoading] = useState(false)
    const [diffFrom, setDiffFrom] = useState<AgentDefinition | null>(null)
    const [diffTo, setDiffTo] = useState<AgentDefinition | null>(null)
    const [diffOpen, setDiffOpen] = useState(false)

    const load = useCallback(async () => {
      setLoading(true)
      try {
        const list = await getPlatformAgentDefinitionVersions(agentId)
        setRows(list ?? [])
      } catch (e) {
        message.error(e instanceof Error ? e.message : '加载变更历史失败')
        setRows([])
      } finally {
        setLoading(false)
      }
    }, [agentId, message])

    useEffect(() => { void load() }, [load])

    const unpublishedCount = useMemo(
      () => rows.filter((r) => r.publishedAt == null && !r.published).length,
      [rows],
    )

    /** 按版本号取「前一版本」（版本降序列表中位于其后的那条）。 */
    const previousOf = useCallback(
      (version: number): AgentDefinition | null => {
        const idx = rows.findIndex((r) => r.version === version)
        if (idx < 0) return null
        // rows 是版本降序：idx+1 即更早的版本
        return rows[idx + 1] ?? null
      },
      [rows],
    )

    const openDiff = useCallback((before: AgentDefinition | null, after: AgentDefinition | null) => {
      if (!after) {
        message.info('没有可对比的版本记录')
        return
      }
      if (!before) {
        message.info(`v${after.version} 是首个版本，没有更早版本可对比`)
        return
      }
      setDiffFrom(before)
      setDiffTo(after)
      setDiffOpen(true)
    }, [message])

    const diffVersion = useCallback((versionNumber: number) => {
      const target = rows.find((r) => r.version === versionNumber)
      if (!target) {
        message.info(`未找到 v${versionNumber} 的版本记录`)
        return
      }
      openDiff(previousOf(versionNumber), target)
    }, [rows, previousOf, openDiff, message])

    useImperativeHandle(ref, () => ({ diffVersion }), [diffVersion])

    const closeDiff = () => {
      setDiffOpen(false)
      setDiffFrom(null)
      setDiffTo(null)
    }

    const handleCleanup = () => {
      if (unpublishedCount === 0) {
        message.info('没有可清理的未发布草稿')
        return
      }
      modal.confirm({
        title: '清理未发布草稿版本',
        content: (
          <>
            将删除 <b>{unpublishedCount}</b> 条从未发布的草稿版本。
            <br />
            已发布的历史版本（线上生效过）<b>不会被清理</b>，审计追溯不受影响。
          </>
        ),
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

    const columns: TableColumnsType<AgentDefinition> = [
      {
        title: '版本号',
        dataIndex: 'version',
        width: 110,
        fixed: 'left',
        render: (v: number, _r, idx) => (
          <Space size={4}>
            <strong>v{v}</strong>
            {idx === 0 && <Tag color="blue">最新</Tag>}
          </Space>
        ),
      },
      {
        title: '发布状态',
        width: 120,
        render: (_v, r) =>
          r.publishedAt != null || r.published
            ? <Tag color="green">已发布</Tag>
            : <Tag color="orange">草稿（未发布）</Tag>,
      },
      {
        title: '变更时间',
        width: 180,
        sorter: (a, b) => dayjs(a.createdAt ?? 0).valueOf() - dayjs(b.createdAt ?? 0).valueOf(),
        render: (_v, r) => (r.createdAt ? dayjs(r.createdAt).format('YYYY-MM-DD HH:mm:ss') : '—'),
      },
      {
        title: '发布时间',
        width: 180,
        render: (_v, r) => (r.publishedAt ? dayjs(r.publishedAt).format('YYYY-MM-DD HH:mm:ss') : '—'),
      },
      {
        title: '配置摘要',
        ellipsis: true,
        render: (_v, r) => (
          <Space size={4} wrap>
            <Typography.Text type="secondary">{r.name ?? '—'}</Typography.Text>
            {r.modelRef
              ? <Tag>模型 {r.modelRef}</Tag>
              : <Tag color="red">未绑模型</Tag>}
            <Tag>{r.enabledSkills?.length ?? 0} 个 Skill</Tag>
          </Space>
        ),
      },
      {
        title: '对比',
        width: 160,
        fixed: 'right',
        render: (_v, r) => (
          <Tooltip title="与上一版本对比配置">
            <Button size="small" icon={<DiffOutlined />} onClick={() => diffVersion(r.version)}>
              与上一版对比
            </Button>
          </Tooltip>
        ),
      },
    ]

    return (
      <div>
        <Space style={{ marginBottom: 12 }} wrap>
          <Typography.Title level={5} style={{ margin: 0 }}>
            <HistoryOutlined /> 变更历史（{rows.length} 条）
          </Typography.Title>
          {unpublishedCount > 0 && <Tag color="orange">{unpublishedCount} 条未发布草稿</Tag>}
          <Button
            size="small"
            danger={unpublishedCount > 0}
            icon={<DeleteOutlined />}
            onClick={handleCleanup}
            disabled={unpublishedCount === 0}
          >
            清理草稿
          </Button>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            双击某条记录查看该次变更的配置前后对比
          </Typography.Text>
        </Space>

        {rows.length === 0 ? (
          emptyTextWhileLoading(loading, <Empty description="暂无变更记录" />)
        ) : (
          <Table<AgentDefinition>
            size="small"
            rowKey={(r) => String(r.version)}
            columns={columns}
            dataSource={rows}
            loading={loading}
            pagination={{ pageSize: 10, size: 'small' }}
            onRow={(row) => ({
              onDoubleClick: () => diffVersion(row.version),
              style: { cursor: 'pointer' },
            })}
          />
        )}

        <ConfigDiffModal open={diffOpen} onClose={closeDiff} before={diffFrom} after={diffTo} />
      </div>
    )
  },
)

// ─────────────────────────────────────────────────────────────────
// 配置对比弹窗（真正的行级 / 路径级 diff）
// ─────────────────────────────────────────────────────────────────

interface ConfigDiffModalProps {
  open: boolean
  onClose: () => void
  before: AgentDefinition | null
  after: AgentDefinition | null
}

/** 把结构化定义转成用于 diff 的对象（只取配置语义字段，版本号/时间不参与比对）。 */
function toComparable(def: AgentDefinition): Record<string, unknown> {
  return {
    name: def.name ?? null,
    description: def.description ?? null,
    modelRef: def.modelRef ?? null,
    roles: def.roles ?? [],
    enabledSkills: def.enabledSkills ?? [],
    skillBundles: def.skillBundles ?? [],
    capabilityTags: def.capabilityTags ?? [],
    overrides: safeParse(def.overrides),
    orchestration: safeParse(def.orchestration),
    instructions: safeParse(def.instructions),
    generationParams: safeParse(def.generationParams),
  }
}

function safeParse(raw: string | undefined | null): unknown {
  if (raw == null || raw === '') return null
  try { return JSON.parse(raw) } catch { return raw }
}

function ConfigDiffModal({ open, onClose, before, after }: ConfigDiffModalProps): JSX.Element | null {
  const [view, setView] = useState<'unified' | 'side'>('unified')

  const lines: JsonDiffLine[] = useMemo(() => {
    if (!before || !after) return []
    return diffJson(
      JSON.stringify(toComparable(before), null, 2),
      JSON.stringify(toComparable(after), null, 2),
    )
  }, [before, after])

  useEffect(() => { if (!open) setView('unified') }, [open])

  if (!open || !after) return null

  const stat = diffSummary(lines)

  return (
    <Modal
      open={open}
      onCancel={onClose}
      width={1000}
      footer={null}
      destroyOnHidden
      title={
        <Space wrap>
          <SwapOutlined />
          <span>
            {before ? `v${before.version}` : '（无）'} → v{after.version} 配置对比
          </span>
          <Tag color={after.publishedAt != null || after.published ? 'green' : 'orange'}>
            {after.publishedAt != null || after.published ? '已发布' : '草稿'}
          </Tag>
          <Tag color="green">+{stat.added}</Tag>
          <Tag color="red">-{stat.removed}</Tag>
          <Tag color="gold">~{stat.changed}</Tag>
        </Space>
      }
    >
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {before
              ? `对比基准：v${before.version}（${before.createdAt ? dayjs(before.createdAt).format('YYYY-MM-DD HH:mm') : '时间未知'}）`
              : '对比基准：无（首个版本）'}
            {' · '}
            本版本：v{after.version}
            {after.createdAt ? `（${dayjs(after.createdAt).format('YYYY-MM-DD HH:mm')}）` : ''}
          </Typography.Text>
          <Segmented
            value={view}
            onChange={(v) => setView(v as 'unified' | 'side')}
            options={[
              { label: '统一视图', value: 'unified' },
              { label: '并排视图', value: 'side' },
            ]}
          />
        </Space>

        {lines.length === 0 ? (
          <Alert type="success" showIcon message="两版配置完全一致（仅版本号/时间不同）" />
        ) : view === 'unified' ? (
          <UnifiedDiff lines={lines} />
        ) : (
          <SideBySideDiff lines={lines} before={before} after={after} />
        )}
      </Space>
    </Modal>
  )
}

const KIND_STYLE: Record<JsonDiffLine['kind'], { bg: string; color: string; sign: string; label: string }> = {
  added: { bg: '#E9F5F1', color: '#1C6E5E', sign: '+', label: '新增' },
  removed: { bg: '#FCEAE7', color: '#B23A2E', sign: '-', label: '删除' },
  changed: { bg: '#FFF5E3', color: '#A9761B', sign: '~', label: '修改' },
}

function DiffFrame({ children }: { children: React.ReactNode }): JSX.Element {
  return (
    <div style={{
      border: '1px solid #E8EBE6', borderRadius: 6, padding: 8,
      maxHeight: 460, overflow: 'auto', fontFamily: 'ui-monospace, Menlo, monospace', fontSize: 12,
    }}>
      {children}
    </div>
  )
}

function UnifiedDiff({ lines }: { lines: JsonDiffLine[] }): JSX.Element {
  return (
    <DiffFrame>
      {lines.map((l) => {
        const s = KIND_STYLE[l.kind]
        return (
          <div key={l.path} style={{ padding: '3px 4px', background: s.bg, marginBottom: 2, borderRadius: 3 }}>
            <div style={{ display: 'flex', gap: 6, alignItems: 'baseline' }}>
              <span style={{ color: s.color, width: 12, flex: 'none' }}>{s.sign}</span>
              <Tag color={l.kind === 'added' ? 'green' : l.kind === 'removed' ? 'red' : 'gold'}
                   style={{ marginRight: 0 }}>
                {s.label}
              </Tag>
              <span style={{ color: '#1B2624', fontWeight: 600, wordBreak: 'break-all' }}>{l.path}</span>
            </div>
            {l.kind !== 'added' && l.before != null && (
              <div style={{ color: '#B23A2E', paddingLeft: 20, wordBreak: 'break-all', whiteSpace: 'pre-wrap' }}>
                − {l.before}
              </div>
            )}
            {l.kind !== 'removed' && l.after != null && (
              <div style={{ color: '#1C6E5E', paddingLeft: 20, wordBreak: 'break-all', whiteSpace: 'pre-wrap' }}>
                + {l.after}
              </div>
            )}
          </div>
        )
      })}
    </DiffFrame>
  )
}

function SideBySideDiff({
  lines, before, after,
}: { lines: JsonDiffLine[]; before: AgentDefinition | null; after: AgentDefinition }): JSX.Element {
  return (
    <div style={{ display: 'flex', gap: 12 }}>
      <div style={{ flex: 1 }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {before ? `v${before.version}（变更前）` : '变更前'}
        </Typography.Text>
        <DiffFrame>
          {lines.map((l) => (
            <div key={l.path} style={{ padding: '3px 4px', borderRadius: 3, marginBottom: 2,
              background: l.kind === 'removed' ? KIND_STYLE.removed.bg : 'transparent' }}>
              <div style={{ color: '#586862', wordBreak: 'break-all', fontWeight: 600 }}>{l.path}</div>
              <div style={{ color: '#B23A2E', wordBreak: 'break-all', whiteSpace: 'pre-wrap' }}>
                {l.before ?? '（无）'}
              </div>
            </div>
          ))}
          {lines.length === 0 && <div style={{ color: '#8B9791' }}>（无差异）</div>}
        </DiffFrame>
      </div>
      <div style={{ flex: 1 }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          v{after.version}（变更后）
        </Typography.Text>
        <DiffFrame>
          {lines.map((l) => (
            <div key={l.path} style={{ padding: '3px 4px', borderRadius: 3, marginBottom: 2,
              background: l.kind === 'added' ? KIND_STYLE.added.bg
                : l.kind === 'changed' ? KIND_STYLE.changed.bg : 'transparent' }}>
              <div style={{ color: '#586862', wordBreak: 'break-all', fontWeight: 600 }}>{l.path}</div>
              <div style={{ color: '#1C6E5E', wordBreak: 'break-all', whiteSpace: 'pre-wrap' }}>
                {l.after ?? '（无）'}
              </div>
            </div>
          ))}
          {lines.length === 0 && <div style={{ color: '#8B9791' }}>（无差异）</div>}
        </DiffFrame>
      </div>
    </div>
  )
}
