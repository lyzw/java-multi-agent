import {
  Alert,
  Card,
  Collapse,
  Descriptions,
  Empty,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { AgentDefinition, AgentItem } from '@/api/types'
import { parseOrchestrationJson, type ChildRefFormValue } from '../orchestration/orchestrationForm'

/** 旧体系实体行（`agent` 表）里与权威定义**同名**的字段 —— 两处可能不一致。 */
function prettyJson(raw: string | null): string {
  if (!raw) return '（无）'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/** 编排模式的展示色。 */
const MODE_COLOR: Record<string, string> = {
  LEAF: 'default',
  ROUTER: 'blue',
  SEQUENTIAL: 'purple',
}

export interface AgentOverviewProps {
  /** 旧体系实体行（`agent` 表） */
  agent: AgentItem | null
  /** 权威定义（`cap_agent_def`） */
  definition: AgentDefinition | null
  /** 权威定义是否取不到 */
  definitionUnavailable: boolean
}

/**
 * Agent 详情 · **全部信息**（第三十五批）。
 *
 * <p><b>⚠️ 「所有 Agent 信息」= 两个模型的全集</b>：
 * <ul>
 *   <li><b>租户实体行</b>（{@code agent} 表 / {@code GET /api/agents/{id}}）：
 *       标识 / 名称 / 描述 / 版本数 / 当前版本号 / 最近发布 / 版本快照；</li>
 *   <li><b>多级定义</b>（{@code cap_agent_def} 表 /
 *       {@code GET /api/cap/agents/{id}/definition}）：
 *       定义版本 / 发布状态 / 角色 / 启用技能 / 技能包 / 能力标签 /
 *       收紧覆盖 / 编排声明。</li>
 * </ul>
 *
 * <p><b>⚠️ 两份定义不是同一份东西</b>（FR-002 / 06 §1.4）：
 * {@code cap_agent_def} 是**结构化真源**，{@code agent_version} 是**历史快照归档**。
 * 二者并列呈现（而不是合并成一份），运维才能判断「哪一份才是当前生效的」。
 *
 * <p><b>⚠️ 编排声明按结构展开</b>，而不只是甩一段 JSON 原文 ——
 * 原文适合机器核对，结构适合人判断「它到底会委派给谁、限制是多少」。
 */
export function AgentOverview({
  agent,
  definition,
  definitionUnavailable,
}: AgentOverviewProps): JSX.Element {
  // ⚠️ **只读展示用宽松解析**：解析失败时保留原文让用户看到，而不是把页面搞崩。
  //    （回填表单走严格版 —— 见 `agentDraftForm.definitionToDraft`。）
  const orchestration = parseOrchestrationJson(definition?.orchestration)
  const hasOrchestration = (definition?.orchestration ?? '').trim() !== ''
  const children = orchestration.values.childAgentRefs ?? []

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      {/* ── ① 租户实体行（旧体系）── */}
      <Card size="small" title="租户实体行（agent 表 · 旧体系）">
        <Descriptions bordered column={2} size="small" items={[
          { key: 'id', label: 'Agent 标识', children: <Typography.Text code>{agent?.agentId ?? '-'}</Typography.Text> },
          { key: 'name', label: '名称', children: agent?.name ?? '-' },
          { key: 'desc', label: '描述', children: agent?.description ?? '-' },
          {
            key: 'versions',
            label: '版本数',
            children: agent && agent.versionCount > 0
              ? <Tag color="blue">当前 v{agent.latestVersionNumber} · 共 {agent.versionCount}</Tag>
              : <Tag color="orange">尚无版本</Tag>,
          },
          { key: 'published', label: '最近发布', children: agent?.latestPublishedAt ?? '-' },
        ]} />
      </Card>

      {/* ── ② 多级定义（权威真源）── */}
      <Alert
        type={definition ? 'info' : 'warning'}
        showIcon
        message={
          definition
            ? `多级定义（cap_agent_def）· v${definition.version}${definition.published ? ' · 已发布' : ' · 未发布'}`
            : '该 Agent 尚无多级定义（cap_agent_def）'
        }
        description={
          definition
            ? '下面这些字段来自结构化真源；发布版本的 definition 快照是「另一份东西」，在页面末尾单列。'
            : '关系字段（角色 / 启用技能 / 技能包 / 能力标签 / 编排声明）取不到权威定义 —— ' +
              '在「编辑」页签中提交会「清空」这些关系，需显式确认。'
        }
      />

      {definition && (
        <Card size="small" title="多级定义字段（cap_agent_def）">
          <Descriptions bordered column={2} size="small" items={[
            { key: 'name', label: '名称', children: definition.name ?? '-' },
            { key: 'desc', label: '描述', children: definition.description ?? '-' },
            { key: 'roles', label: '角色', children: definition.roles.join('、') || '-' },
            { key: 'skills', label: '启用技能', children: definition.enabledSkills.join('、') || '-' },
            { key: 'bundles', label: '技能包', children: definition.skillBundles.join('、') || '-' },
            { key: 'tags', label: '能力标签', children: definition.capabilityTags.join('、') || '-' },
            {
              key: 'overrides',
              label: '收紧覆盖',
              children: <pre style={{ margin: 0, fontSize: 11 }}>{definition.overrides || '{}'}</pre>,
              span: 2,
            },
          ]} />
        </Card>
      )}

      {/* ── ③ 编排声明（结构化）── */}
      <Card size="small" title="编排声明（orchestration）">
        {!hasOrchestration ? (
          <Empty description="未声明编排（= LEAF · 不允许委派）" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Descriptions bordered column={2} size="small" items={[
              {
                key: 'mode',
                label: '编排模式',
                children: (
                  <Tag color={MODE_COLOR[orchestration.values.mode] ?? 'default'}>
                    {orchestration.values.mode}
                  </Tag>
                ),
              },
              {
                key: 'routing',
                label: '路由策略引用',
                children: orchestration.values.routingPolicyRef ?? '-',
              },
              {
                key: 'contract',
                label: '输出契约引用',
                children: orchestration.values.resultContractRef ?? '-',
              },
              {
                key: 'depth',
                label: '最大委派深度',
                children:
                  orchestration.values.maxDelegationDepth ?? '-',
              },
              {
                key: 'invocations',
                label: '最大子调用次数',
                children: orchestration.values.maxChildInvocations ?? '-',
              },
              {
                key: 'timeout',
                label: '委派超时（毫秒）',
                children: orchestration.values.timeoutMs ?? '-',
              },
              {
                key: 'failure',
                label: '失败策略',
                children: orchestration.values.failurePolicy ?? '-',
              },
            ]} />

            <Card size="small" title={`声明的子 Agent（${children.length}）`} styles={{ body: { paddingBottom: 0 } }}>
              {children.length > 0 ? (
                <Table<ChildRefFormValue>
                  rowKey={(r) => `${r.id}@${r.version}`}
                  size="small"
                  pagination={false}
                  dataSource={children}
                  columns={[
                    {
                      title: '子 Agent',
                      dataIndex: 'id',
                      render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
                    },
                    {
                      title: '固定版本',
                      dataIndex: 'version',
                      render: (v: number | null) => (v ? <Tag color="blue">v{v}</Tag> : '-'),
                    },
                    {
                      title: '是否必需',
                      dataIndex: 'required',
                      render: (v: boolean) => (v ? <Tag color="red">必需</Tag> : <Tag>可选</Tag>),
                    },
                  ]}
                />
              ) : (
                <Empty description="未声明任何子 Agent" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              )}
            </Card>

            {/* ⚠️ 原文保留：结构化视图只覆盖了本表单支持的字段，
                原文能看出「服务端还存了什么本表单没展示的东西」。 */}
            <Collapse
              items={[{
                key: 'raw',
                label: '编排声明原文（JSON）',
                children: (
                  <pre style={{ margin: 0, fontSize: 11, maxHeight: 300, overflow: 'auto' }}>
                    {prettyJson(definition?.orchestration ?? null)}
                  </pre>
                ),
              }]}
            />
          </Space>
        )}
      </Card>

      {/* ── ④ 旧体系的版本快照（不可变）── */}
      {agent?.latestDefinition && (
        <Alert
          type="info"
          showIcon
          message="当前版本 definition（旧体系不可变快照）"
          description={
            <pre style={{ margin: 0, maxHeight: 360, overflow: 'auto', fontSize: 12 }}>
              {prettyJson(agent.latestDefinition)}
            </pre>
          }
        />
      )}

      {definitionUnavailable && (
        <Alert
          type="warning"
          showIcon
          message="关系字段当前无从回填"
          description="权威定义（cap_agent_def）读取失败或不存在。编辑页签中的关系字段会呈现为空，提交前需显式确认。"
        />
      )}
    </Space>
  )
}

export default AgentOverview
