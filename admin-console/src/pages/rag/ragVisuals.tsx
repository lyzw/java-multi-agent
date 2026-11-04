/**
 * RAG 语料管理 · 视觉稿核心组件（RAG-D14 §五）
 *
 * ⚠️ **为什么要单独立一套组件，而不是散在页面里用 Tag**：
 *    RAG-D12 §1.2 指出的复杂度之一是「同一套规则在多层重复」。
 *    视觉层同理——角色着色、治理态徽标、阈值刻度如果每个页面各写一份，
 *    必然出现「这一页答案类是红色、那一页是橙色」的语义漂移。
 *    集中在此，保证全站口径唯一（RAG-D13 PU-1「单一真源可视化」）。
 *
 * ⚠️ **术语直接对齐后端枚举**：
 *    {@link ChunkRoleTag} 的 role 取值就是 `ChunkRole` 的枚举名；
 *    {@link IngestStateBadge} 的 state 就是任务三态；
 *    不自造前端专有词，避免"前端一套词、后端一套词"。
 */

import { Progress, Space, Tag, Tooltip, Typography } from 'antd'
import type { ReactNode } from 'react'

const { Text } = Typography

/* ------------------------------------------------------------------ */
/* ① 片段角色着色（ChunkRole，RAG-D14 §5.3）                            */
/* ------------------------------------------------------------------ */

/** 角色分组：按教学敏感度归并，而非逐类配色（一眼识别风险）。 */
export type ChunkRoleGroup = 'answer' | 'hint' | 'question' | 'cite' | 'noise'

/**
 * `ChunkRole` 枚举名 → 分组。
 *
 * ⚠️ 与 `rag-core` 的 `ChunkRole.isAnswerBearing()` / `citableAsExplanation()` 语义一致：
 *    - answer  = isAnswerBearing()（EXAMPLE_SOLUTION / EXERCISE_ANSWER）
 *    - cite    = citableAsExplanation()（EXPLANATION / APPENDIX / PAPER_BODY / PAPER_METHOD）
 *    - noise   = isNoise()（BOILERPLATE）
 *    后端新增角色时若未在此登记，退回 question 分组而非抛错——
 *    宁可标成"中性"，也不能让整页崩掉。
 */
const ROLE_GROUP: Record<string, ChunkRoleGroup> = {
  EXAMPLE_SOLUTION: 'answer',
  EXERCISE_ANSWER: 'answer',
  EXERCISE_HINT: 'hint',
  EXERCISE: 'question',
  EXAMPLE: 'question',
  EXPLANATION: 'cite',
  APPENDIX: 'cite',
  PAPER_BODY: 'cite',
  PAPER_METHOD: 'cite',
  BOILERPLATE: 'noise',
}

/** 分组 → antd Tag 颜色（与 RAG-D14 视觉稿的语义色一致）。 */
const GROUP_COLOR: Record<ChunkRoleGroup, string> = {
  answer: 'red',
  hint: 'orange',
  question: 'blue',
  cite: 'green',
  noise: 'default',
}

/** 分组 → 中文名（用于图例）。 */
export const CHUNK_ROLE_GROUP_LABEL: Record<ChunkRoleGroup, string> = {
  answer: '答案类（泄题风险）',
  hint: '提示',
  question: '题目/例题',
  cite: '可引用正文',
  noise: '噪声（丢弃）',
}

/** 角色中文名（后端可能新增角色，故用回退而非严格映射）。 */
const ROLE_LABEL: Record<string, string> = {
  EXPLANATION: '讲解',
  EXAMPLE: '例题',
  EXAMPLE_SOLUTION: '例题解答',
  EXERCISE: '习题',
  EXERCISE_ANSWER: '习题答案',
  EXERCISE_HINT: '习题提示',
  PAPER_BODY: '论文正文',
  PAPER_METHOD: '论文方法',
  APPENDIX: '附录',
  BOILERPLATE: '噪声',
}

export function chunkRoleGroup(role: string | null | undefined): ChunkRoleGroup {
  return ROLE_GROUP[role ?? ''] ?? 'question'
}

export function chunkRoleLabel(role: string | null | undefined): string {
  return ROLE_LABEL[role ?? ''] ?? role ?? '未知'
}

/** 该角色是否会被讲解模式排除（用于"已抑制"提示）。 */
export function isAnswerBearingRole(role: string | null | undefined): boolean {
  return chunkRoleGroup(role) === 'answer'
}

export interface ChunkRoleTagProps {
  role: string | null | undefined
  /** 是否显示"泄题风险"角标（答案类）。 */
  showRisk?: boolean
}

/** 片段角色标签。 */
export function ChunkRoleTag({ role, showRisk = false }: ChunkRoleTagProps): JSX.Element {
  const group = chunkRoleGroup(role)
  return (
    <Space size={4} wrap>
      <Tag color={GROUP_COLOR[group]}>{chunkRoleLabel(role)}</Tag>
      {showRisk && group === 'answer' && <Tag color="red">泄题风险</Tag>}
    </Space>
  )
}

/** 角色图例（解析预览 / 抽检页顶部说明用）。 */
export function ChunkRoleLegend(): JSX.Element {
  const groups: ChunkRoleGroup[] = ['answer', 'hint', 'question', 'cite', 'noise']
  return (
    <Space size={12} wrap>
      {groups.map((g) => (
        <Space key={g} size={4}>
          <Tag color={GROUP_COLOR[g]} style={{ marginInlineEnd: 0 }}>
            {' '}
          </Tag>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {CHUNK_ROLE_GROUP_LABEL[g]}
          </Text>
        </Space>
      ))}
    </Space>
  )
}

/* ------------------------------------------------------------------ */
/* ② 任务三态（写面 / 运维，RAG-D14 §5.1）                              */
/* ------------------------------------------------------------------ */

/**
 * 任务三态。
 *
 * ⚠️ **这是前端对后端 `IngestJobStatus` 六态的收敛视图（靶点 A）**：
 *    后端内部仍走 PARSING→CHUNKING→EMBEDDING→INDEXING（那是执行细节），
 *    但**界面只呈现四态**：SUBMITTED / PROCESSING / READY / FAILED。
 *    用户在任务页不应看到"切片中""嵌入中"这种实现阶段——
 *    他要判断的是"这册书能不能用"，不是流水线走到哪一步。
 */
export type IngestState = 'SUBMITTED' | 'PROCESSING' | 'READY' | 'FAILED'

/** 后端 `IngestJobStatus` → 前端三态。 */
export function toIngestState(status: string | null | undefined): IngestState {
  switch (status) {
    case 'PENDING':
    case 'REJECTED':
      return 'SUBMITTED'
    case 'SUCCEEDED':
      return 'READY'
    case 'FAILED':
      return 'FAILED'
    case 'PARSING':
    case 'CHUNKING':
    case 'EMBEDDING':
    case 'INDEXING':
      return 'PROCESSING'
    default:
      return 'SUBMITTED'
  }
}

const INGEST_STATE_META: Record<
  IngestState,
  { label: string; color: string }
> = {
  SUBMITTED: { label: '已提交', color: 'default' },
  PROCESSING: { label: '处理中', color: 'processing' },
  READY: { label: '已就绪', color: 'green' },
  FAILED: { label: '失败', color: 'red' },
}

export const INGEST_STATE_LABEL: Record<IngestState, string> = {
  SUBMITTED: INGEST_STATE_META.SUBMITTED.label,
  PROCESSING: INGEST_STATE_META.PROCESSING.label,
  READY: INGEST_STATE_META.READY.label,
  FAILED: INGEST_STATE_META.FAILED.label,
}

export function IngestStateBadge({ state }: { state: IngestState }): JSX.Element {
  const meta = INGEST_STATE_META[state]
  return <Tag color={meta.color}>{meta.label}</Tag>
}

export interface IngestProgressProps {
  state: IngestState
  /**
   * 进度 0–100。
   *
   * ⚠️ 后端**没有独立的 progress 列**，进度编码在 `ingest_job.stage` 字符串尾部
   *    （如 `"PARSING 60"`）。调用方应先用 `parseStagePercent(stage)` 解析后传入；
   *    解析不出来就传 null —— 显示假百分比比不显示更糟。
   */
  progress?: number | null
}

/**
 * 单进度条（**不展示内部阶段名**，靶点 A）。
 *
 * ⚠️ 进度语义：READY 恒 100；FAILED 保留失败时的位置（让人知道"卡在哪一截"）；
 *    PROCESSING 用后端给的整数百分比；SUBMITTED 为 0。
 */
export function IngestProgress({ state, progress }: IngestProgressProps): JSX.Element {
  const pct =
    state === 'READY' ? 100 : state === 'SUBMITTED' ? 0 : Math.max(0, Math.min(100, progress ?? 0))
  return (
    <Progress
      percent={pct}
      size="small"
      status={state === 'FAILED' ? 'exception' : state === 'PROCESSING' ? 'active' : 'normal'}
      format={(p) => `${p}%`}
    />
  )
}

/* ------------------------------------------------------------------ */
/* ③ 治理四态（审核 / 来源，RAG-D14 §5.2）                              */
/* ------------------------------------------------------------------ */

/**
 * 治理四态。
 *
 * ⚠️ 后端 `RagVerificationStatus` 实际有 5 个值（PENDING / IN_REVIEW / VERIFIED /
 *    REJECTED / REVOKED）。界面统一收敛为四态：IN_REVIEW 归入 PENDING（都是"未决"），
 *    REJECTED 归入 REVOKED（都是"不可用"）。**不丢信息**——原始枚举仍在中文字段展示。
 */
export type GovernanceState = 'PENDING' | 'VERIFIED' | 'REVOKED' | 'FAILED'

const GOV_META: Record<GovernanceState, { label: string; color: string }> = {
  PENDING: { label: '待处理', color: 'default' },
  VERIFIED: { label: '已通过', color: 'green' },
  REVOKED: { label: '已撤回', color: 'red' },
  FAILED: { label: '失败', color: 'orange' },
}

function toGovernanceState(status: string | null | undefined): GovernanceState {
  switch (status) {
    case 'VERIFIED':
      return 'VERIFIED'
    case 'REVOKED':
    case 'REJECTED':
      return 'REVOKED'
    case 'FAILED':
      return 'FAILED'
    default:
      return 'PENDING'
  }
}

export function GovernanceBadge({
  status,
  title,
}: {
  status: string | null | undefined
  title?: string
}): JSX.Element {
  const state = toGovernanceState(status)
  const meta = GOV_META[state]
  const tag = <Tag color={meta.color}>{meta.label}</Tag>
  // 收敛后的标签可能丢粒度：原始枚举放 tooltip，信息不丢。
  return title || (status && status !== state) ? (
    <Tooltip title={title ?? `原始状态：${status}`}>{tag}</Tooltip>
  ) : (
    tag
  )
}

/* ------------------------------------------------------------------ */
/* ④ 单分数量纲 + 门禁阈值刻度（靶点 C，RAG-D14 §5.4）                   */
/* ------------------------------------------------------------------ */

/** 分数门禁阈值（与后端 `rag.retrieval.min-score` 标定值一致：0.38）。 */
export const RAG_SCORE_THRESHOLD = 0.38

export interface RelevanceMeterProps {
  /** 相关性分（0~1）。 */
  score: number
  /** 阈值（默认 0.38）。 */
  threshold?: number
  /** 是否已被策略抑制（答案类）。抑制时分值仍展示但置灰。 */
  suppressed?: boolean
}

/**
 * 相关性单值 + 阈值刻度条。
 *
 * ⚠️ **只展示一个 `relevance`，不展示 RRF 融合分**（靶点 C）：
 *    后端的 `score` 是相关性（余弦 / ts_rank），`fusionScore` 是排序用的 RRF 值，
 *    两者量纲不同。把 RRF 也画上来会让用户以为"有两套分"——
 *    那正是 RAG-D12 认定的复杂度来源之一。排序信息对用户无价值，故不暴露。
 */
export function RelevanceMeter({
  score,
  threshold = RAG_SCORE_THRESHOLD,
  suppressed = false,
}: RelevanceMeterProps): JSX.Element {
  const pct = Math.max(0, Math.min(1, score)) * 100
  const thr = Math.max(0, Math.min(1, threshold)) * 100
  const pass = score >= threshold
  return (
    <Space direction="vertical" size={2} style={{ width: '100%' }}>
      <Space size={8}>
        <Text strong style={{ color: suppressed ? undefined : pass ? '#389e0d' : '#cf1322' }}>
          {suppressed ? '已按策略排除' : score.toFixed(3)}
        </Text>
        {!suppressed && (
          <Tag color={pass ? 'green' : 'red'}>{pass ? '通过门禁' : '未达门禁'}</Tag>
        )}
      </Space>
      <Tooltip title={`相关性 ${score.toFixed(3)} / 门槛 ${threshold}`}>
        <Progress
          percent={pct}
          size="small"
          showInfo={false}
          strokeColor={suppressed ? '#d9d9d9' : pass ? '#389e0d' : '#cf1322'}
          trailColor="#f0f0f0"
          format={() => ''}
        />
        <Text type="secondary" style={{ fontSize: 11 }}>
          门槛 {threshold}（刻度 {thr.toFixed(0)}%）
        </Text>
      </Tooltip>
    </Space>
  )
}

/* ------------------------------------------------------------------ */
/* ⑤ 版本范围标识（RAG-D14 §5.5）                                       */
/* ------------------------------------------------------------------ */

/** 版本范围 Mode 的中文说明。 */
export const SCOPE_MODE_LABEL: Record<string, string> = {
  STRICT: '严格锁定',
  PREFERRED_WITH_FALLBACK: '首选 + 同册回退',
  CROSS_EDITION: '跨版本',
}

/** 版本范围选择项（下拉用）。 */
export const SCOPE_MODE_OPTIONS = [
  { value: 'STRICT', label: 'STRICT · 严格锁定' },
  { value: 'PREFERRED_WITH_FALLBACK', label: 'PREFERRED_WITH_FALLBACK · 首选+回退' },
  { value: 'CROSS_EDITION', label: 'CROSS_EDITION · 跨版本' },
]

/** 检索意图（RetrievalMode）标签。 */
export const RETRIEVAL_MODE_LABEL: Record<string, string> = {
  EXPLAIN: '讲解',
  SOLVE: '解题',
  VERIFY: '核对',
  OVERVIEW: '概览',
}

export const RETRIEVAL_MODE_OPTIONS = [
  { value: 'EXPLAIN', label: 'EXPLAIN · 讲解' },
  { value: 'SOLVE', label: 'SOLVE · 解题' },
  { value: 'VERIFY', label: 'VERIFY · 核对' },
  { value: 'OVERVIEW', label: 'OVERVIEW · 概览' },
]

/* ------------------------------------------------------------------ */
/* ⑥ 召回解释卡（RAG-D13 §3.3.3 / 靶点 D/E）                             */
/* ------------------------------------------------------------------ */

/** 召回通道中文名。 */
const CHANNEL_LABEL: Record<string, string> = {
  DENSE_VECTOR: '稠密向量',
  LEXICAL: '词法',
  KNOWLEDGE_POINT: '知识点聚合',
  PREREQUISITE_EXPANSION: '先修扩展',
  GENERATION_BASIS: '生成依据',
}

/** 命中原因中文名。 */
const REASON_LABEL: Record<string, string> = {
  DIRECT_MATCH: '直接命中',
  ALIAS_MATCH: '别名命中',
  KP_AGGREGATED: '知识点补全',
  PREREQUISITE: '先修扩展',
}

export function recallChannelLabel(channel: string | null | undefined): string {
  return CHANNEL_LABEL[channel ?? ''] ?? channel ?? '—'
}

export function recallReasonLabel(reason: string | null | undefined): string {
  return REASON_LABEL[reason ?? ''] ?? reason ?? '—'
}

/**
 * 过滤前后数量序列（靶点 D）。
 *
 * ⚠️ **这是"诊断召回不足还是过滤过严"的关键指标**（RAG-D05 §7）：
 *    只给最终命中数，用户无法判断"没结果"是语料没有、还是被过滤光了。
 *    把每一层过滤后的数量显式列出，规则效果才是可验证的。
 */
export interface FunnelStep {
  label: string
  count: number
  /** 相对上一层的剔除数（首层为空）。 */
  dropped?: number
}

export function RecallFunnel({ steps }: { steps: FunnelStep[] }): JSX.Element {
  return (
    <Space size={8} wrap>
      {steps.map((s, i) => (
        <Space key={`${s.label}-${i}`} size={4}>
          {i > 0 && <Text type="secondary">→</Text>}
          <Tooltip
            title={
              s.dropped != null && s.dropped > 0
                ? `${s.label}：剩 ${s.count} 条（本层剔除 ${s.dropped} 条）`
                : `${s.label}：剩 ${s.count} 条`
            }
          >
            <Tag color={s.dropped != null && s.dropped > 0 ? 'orange' : undefined}>
              {`${s.label} ${s.count}`}
            </Tag>
          </Tooltip>
        </Space>
      ))}
    </Space>
  )
}

/**
 * 主因归因的展示文案。
 *
 * ⚠️ 归因由**后端**计算（`RetrievalFunnel.dominantDrop()`），前端只做翻译：
 *    若前端自己比较三个差值，两处算法必然漂移，
 *    而"诊断结论"出错比不显示更危险——它会把人引向错误的处置。
 */
const DOMINANT_DROP_META: Record<
  string,
  { label: string; color: string; hint: string }
> = {
  NONE: {
    label: '各层均无剔除',
    color: 'green',
    hint: '候选全部通过了版本/角色/门禁，结果条数由 topK 决定。',
  },
  ROLE: {
    label: '主因：角色过滤（泄题闸门）',
    color: 'red',
    hint: '被滤掉的多为答案类内容——语料中答案占比高。这是防泄题的正常结果，不是故障。',
  },
  VERSION: {
    label: '主因：版本过滤',
    color: 'orange',
    hint: '多数候选与学生的教材版本不符——很可能是学生正在用的那一版尚未录入语料。',
  },
  GATE: {
    label: '主因：分数门禁',
    color: 'gold',
    hint: '语料里有意料中的内容，但相关性都低于门槛——可考虑补充语料或复核门槛标定值。',
  },
}

export interface RecallFunnelSummaryProps {
  funnel: {
    candidates: number
    afterVersion: number
    afterRole: number
    afterGate: number
    returned: number
    droppedByVersion: number
    droppedByRole: number
    droppedByGate: number
    dominantDrop: string
  }
}

/**
 * 完整漏斗摘要：四段数量 + 主因归因结论。
 *
 * ⚠️ 这是「召回解释」页的诊断核心：它回答"这次没结果，是语料里没有、
 *    还是被过滤光了"——只给命中数回答不了这个问题（RAG-D05 §7）。
 */
export function RecallFunnelSummary({ funnel }: RecallFunnelSummaryProps): JSX.Element {
  const meta = DOMINANT_DROP_META[funnel.dominantDrop] ?? DOMINANT_DROP_META.NONE!
  const steps: FunnelStep[] = [
    { label: '召回', count: funnel.candidates },
    { label: '版本过滤', count: funnel.afterVersion, dropped: funnel.droppedByVersion },
    { label: '角色过滤', count: funnel.afterRole, dropped: funnel.droppedByRole },
    { label: '门禁', count: funnel.afterGate, dropped: funnel.droppedByGate },
    { label: '返回', count: funnel.returned },
  ]
  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <RecallFunnel steps={steps} />
      <Space size={8} wrap>
        <Tag color={meta.color}>{meta.label}</Tag>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {meta.hint}
        </Text>
      </Space>
    </Space>
  )
}

/* ------------------------------------------------------------------ */
/* ⑦ 结构化空态（PU-3 fail-closed 可见）                                */
/* ------------------------------------------------------------------ */

export interface RagEmptyProps {
  /** 'neutral' 普通空；'insufficient' 依据不足；'suppressed' 被策略抑制。 */
  kind?: 'neutral' | 'insufficient' | 'suppressed'
  text: ReactNode
}

/**
 * 带原因的空态。
 *
 * ⚠️ 绝不用空白代替"没有结果"：空白无法区分
 *    「语料库里确实没有」与「被策略藏起来了」——这两件事的处置完全不同。
 */
export function RagEmpty({ kind = 'neutral', text }: RagEmptyProps): JSX.Element {
  const color = kind === 'insufficient' ? '#d46b08' : kind === 'suppressed' ? '#cf1322' : undefined
  return (
    <div style={{ padding: '32px 0', textAlign: 'center' }}>
      <Text type={color ? undefined : 'secondary'} style={{ color }}>
        {text}
      </Text>
    </div>
  )
}
