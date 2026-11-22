import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useState } from 'react'
import {
  Alert, App as AntApp, Button, Empty, Modal, Segmented, Space, Table, Tag, Tooltip, Typography,
} from 'antd'
import type { TableColumnsType } from 'antd'
import dayjs from 'dayjs'
import { DeleteOutlined, DiffOutlined, HistoryOutlined, SwapOutlined } from '@ant-design/icons'
import { cleanupAgentVersions, getPlatformAgentDefinitionVersions } from '@/api/adminApi'
import type { AgentDefinition } from '@/api/types'
import { diffJson, diffSummary, toJsonDiffLines, type JsonDiffLine, type JsonTextLine } from '@/shared/jsonDiff'
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
  /** 打开指定版本与前一版本的配置对比（供「变更历史」列表双击触发）。 */
  diffVersion: (versionNumber: number) => void
  /**
   * 直接用<b>记录自带的</b> before / after 配置打开对比（供「变更记录」双击触发）。
   *
   * <p>⚠️ 优先走这条路：变更记录已经把前后两份配置随记录下发，
   * 不再按版本号反推 —— 版本号不连续时反推会取错基准。
   */
  diffConfig: (payload: {
    before: string | null | undefined
    after: string | null | undefined
    label?: string
  }) => void
}

export const AgentVersionHistory = forwardRef<AgentVersionHistoryHandle, { agentId: string }>(
  function AgentVersionHistory({ agentId }, ref): JSX.Element {
    const { message, modal } = AntApp.useApp()
    const [rows, setRows] = useState<AgentDefinition[]>([])
    const [loading, setLoading] = useState(false)
    const [diffFrom, setDiffFrom] = useState<AgentDefinition | null>(null)
    const [diffTo, setDiffTo] = useState<AgentDefinition | null>(null)
    const [diffOpen, setDiffOpen] = useState(false)
    /** 记录自带的 before/after（变更记录双击走这里，不依赖版本反推）。 */
    const [rawDiff, setRawDiff] = useState<{
      before: string | null
      after: string | null
      label: string
    } | null>(null)

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
      setRawDiff(null)
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

    const diffConfig = useCallback((payload: {
      before: string | null | undefined
      after: string | null | undefined
      label?: string
    }) => {
      if (payload.after == null) {
        message.info('该记录没有配置变更内容（建行 / 分配租户等管理动作）')
        return
      }
      setRawDiff({
        before: payload.before ?? null,
        after: payload.after,
        label: payload.label ?? '配置变更',
      })
    }, [message])

    useImperativeHandle(ref, () => ({ diffVersion, diffConfig }), [diffVersion, diffConfig])

    const closeDiff = () => {
      setDiffOpen(false)
      setDiffFrom(null)
      setDiffTo(null)
      setRawDiff(null)
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

        <ConfigDiffModal
          open={diffOpen || rawDiff !== null}
          onClose={closeDiff}
          before={diffFrom}
          after={diffTo}
          raw={rawDiff}
        />
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
  /** 记录自带的 before/after 原始配置（优先于 before/after 两个版本对象）。 */
  raw?: { before: string | null; after: string | null; label: string } | null
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

function ConfigDiffModal({
  open, onClose, before, after, raw,
}: ConfigDiffModalProps): JSX.Element | null {
  const [view, setView] = useState<'unified' | 'side'>('side')

  /**
   * ⚠️ 两种数据来源，优先级：记录自带的 raw > 版本对象。
   * raw 是变更记录随记录下发的「确定的 before/after」，
   * 不依赖版本号相邻推算，因此优先。
   */
  /**
   * 前后两份配置的 JSON 原文。
   * ⚠️ 对比以 **JSON 文本** 呈现：规范化缩进后逐行标注变更，
   * 用户看到的就是一份完整、可复制的 JSON，而不是被拆散的路径表。
   */
  const jsonText = useMemo<{ before: string | null; after: string | null }>(() => {
    if (raw) {
      return { before: raw.before, after: raw.after }
    }
    if (!before || !after) return { before: null, after: null }
    return {
      before: JSON.stringify(toComparable(before), null, 2),
      after: JSON.stringify(toComparable(after), null, 2),
    }
  }, [raw, before, after])

  const lines: JsonDiffLine[] = useMemo(
    () => diffJson(jsonText.before, jsonText.after),
    [jsonText],
  )

  const beforeLines = useMemo(
    () => toJsonDiffLines(jsonText.before, jsonText.after, 'before'),
    [jsonText],
  )
  const afterLines = useMemo(
    () => toJsonDiffLines(jsonText.before, jsonText.after, 'after'),
    [jsonText],
  )

  useEffect(() => { if (!open) setView('side') }, [open])

  const hasContent = raw ? raw.after != null : after != null
  if (!open || !hasContent) return null

  const stat = diffSummary(lines)
  const title = raw
    ? raw.label
    : `${before ? `v${before.version}` : '（无）'} → v${after?.version} 配置对比`

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
          <span>{title}</span>
          {!raw && (
            <Tag color={after?.publishedAt != null || after?.published ? 'green' : 'orange'}>
              {after?.publishedAt != null || after?.published ? '已发布' : '草稿'}
            </Tag>
          )}
          <Tag color="green">+{stat.added}</Tag>
          <Tag color="red">-{stat.removed}</Tag>
          <Tag color="gold">~{stat.changed}</Tag>
        </Space>
      }
    >
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {raw
              ? (raw.before == null
                ? '对比基准：无（首个版本，变更前不存在配置）'
                : '对比基准：变更记录中保存的变更前配置')
              : (before
                ? `对比基准：v${before.version}（${before.createdAt ? dayjs(before.createdAt).format('YYYY-MM-DD HH:mm') : '时间未知'}）`
                : '对比基准：无（首个版本）')}
            {!raw && after && (
              <>
                {' · '}
                本版本：v{after.version}
                {after.createdAt ? `（${dayjs(after.createdAt).format('YYYY-MM-DD HH:mm')}）` : ''}
              </>
            )}
          </Typography.Text>
          <Segmented
            value={view}
            onChange={(v) => setView(v as 'unified' | 'side')}
            options={[
              { label: '并排 JSON', value: 'side' },
              { label: '统一 JSON', value: 'unified' },
            ]}
          />
        </Space>

        {lines.length === 0 ? (
          <Alert type="success" showIcon message="两版配置完全一致（仅版本号/时间不同）" />
        ) : view === 'side' ? (
          <SideBySideJson
            beforeLines={beforeLines}
            afterLines={afterLines}
            beforeLabel={raw ? '变更前' : `v${before?.version ?? '?'}（变更前）`}
            afterLabel={raw ? '变更后' : `v${after?.version ?? '?'}（变更后）`}
          />
        ) : (
          <UnifiedJson lines={afterLines} allLines={lines} />
        )}
      </Space>
    </Modal>
  )
}

const KIND_STYLE: Record<'added' | 'removed' | 'changed', { bg: string; color: string; sign: string; label: string }> = {
  added: { bg: '#E9F5F1', color: '#1C6E5E', sign: '+', label: '新增' },
  removed: { bg: '#FCEAE7', color: '#B23A2E', sign: '-', label: '删除' },
  changed: { bg: '#FFF5E3', color: '#A9761B', sign: '~', label: '修改' },
}

/**
 * JSON 行渲染框 —— 等宽字体、保留缩进、行内高亮变更。
 *
 * <p>⚠️ 每行保留原始缩进与逗号，整块拼起来就是一份<b>合法 JSON</b>，
 * 用户可以直接复制出去用；而不是被拆成「路径 + 值」的表格。
 */
function JsonLines({
  lines,
  emptyText,
}: {
  lines: JsonTextLine[]
  emptyText: string
}): JSX.Element {
  if (lines.length === 0) {
    return <div style={{ color: '#8B9791', fontFamily: 'monospace', fontSize: 12, padding: 8 }}>{emptyText}</div>
  }
  return (
    <div style={{
      border: '1px solid #E8EBE6', borderRadius: 6, padding: '8px 0',
      maxHeight: 460, overflow: 'auto', fontFamily: 'ui-monospace, Menlo, monospace', fontSize: 12,
      lineHeight: 1.6, background: '#FFFFFF',
    }}>
      {lines.map((l, i) => {
        const s = l.kind ? KIND_STYLE[l.kind] : null
        return (
          <div
            key={`${i}-${l.path}`}
            style={{
              display: 'flex',
              background: s ? s.bg : 'transparent',
              borderLeft: s ? `3px solid ${s.color}` : '3px solid transparent',
              paddingRight: 8,
            }}
          >
            {/* 变更标记列：保持等宽，未变更行留空格 */}
            <span style={{
              width: 16, flex: 'none', textAlign: 'center',
              color: s ? s.color : '#C9D1CC', userSelect: 'none',
            }}>
              {s ? s.sign : '·'}
            </span>
            <pre style={{
              margin: 0, whiteSpace: 'pre', overflow: 'visible',
              color: s ? s.color : '#1B2624', fontWeight: s ? 600 : 400,
            }}>
              {l.text}
            </pre>
          </div>
        )
      })}
    </div>
  )
}

/**
 * 并排 JSON 视图：左「变更前」、右「变更后」，各自是完整 JSON。
 *
 * <p>⚠️ 两侧<b>行号对齐</b>用同一套结构渲染（都基于 after 结构的下标），
 * 因此左栏缺失的行以空白占位，避免用户横向误读。
 */
function SideBySideJson({
  beforeLines, afterLines, beforeLabel, afterLabel,
}: {
  beforeLines: JsonTextLine[]
  afterLines: JsonTextLine[]
  beforeLabel: string
  afterLabel: string
}): JSX.Element {
  return (
    <div style={{ display: 'flex', gap: 12 }}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{beforeLabel}</Typography.Text>
        <JsonLines lines={beforeLines} emptyText="（变更前不存在该配置）" />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{afterLabel}</Typography.Text>
        <JsonLines lines={afterLines} emptyText="（变更后不存在该配置）" />
      </div>
    </div>
  )
}

/**
 * 统一 JSON 视图：只渲染「变更后」的完整 JSON，
 * 但每行按差异着色（新增 / 修改），删除的行以摘要列出。
 *
 * <p>⚠️ <b>为什么删除行单独列出</b>：统一视图保留的是<b>变更后</b>结构的完整性
 * （依然是一份合法 JSON）；被删除的行塞进 JSON 里会破坏语法。
 * 因此删除项在 JSON 下方以「已删除」清单呈现，既不破损 JSON 也不丢信息。
 */
function UnifiedJson({
  lines, allLines,
}: {
  lines: JsonTextLine[]
  allLines: JsonDiffLine[]
}): JSX.Element {
  const removed = allLines.filter((l) => l.kind === 'removed')
  return (
    <Space direction="vertical" size="small" style={{ width: '100%' }}>
      <JsonLines lines={lines} emptyText="（变更后不存在该配置）" />
      {removed.length > 0 && (
        <div style={{
          border: '1px solid #F3D9D4', borderRadius: 6, padding: 8,
          background: '#FCEAE7', fontFamily: 'monospace', fontSize: 12,
        }}>
          <Typography.Text strong style={{ color: '#B23A2E' }}>
            已删除的配置项（{removed.length}）
          </Typography.Text>
          {removed.map((l) => (
            <div key={l.path} style={{ color: '#B23A2E', wordBreak: 'break-all', whiteSpace: 'pre-wrap' }}>
              − {l.path}: {l.before}
            </div>
          ))}
        </div>
      )}
    </Space>
  )
}
