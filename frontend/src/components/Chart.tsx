/**
 * 报表图表组件（纯 SVG · 零新依赖）
 *
 * ⚠️ **为什么不引图表库**：项目已刻意「不引依赖」（见 useRagManagement 文件头
 *    「依赖越少，攻击面越小」）。图表只需柱状与折线两种，用 SVG 直接画反而
 *    更可控——尤其**颜色纪律**：图表颜色必须走 CSS 变量而非硬编码，
 *    否则主题一变图表就与界面脱节。
 *
 * ⚠️ **用色纪律（承接 FE-D3 R1/R3）**：
 *    批改红（--mark）只用于「错误/失败」语义，成功用 --settled，
 *    其余序列用墨色阶（--ink-2 / --ink-3）。⛔ 不用高饱和蓝绿。
 *
 * ⚠️ **零值不画柱**：值为 0 时画一根 0 高度的柱会被误读为「有数据但极小」。
 *    故零值只画基线，不画柱体。
 *
 * ⚠️ **1:1 渲染 + 横向滚动（2026-10-23 修复）**：
 *    此前图表用 `viewBox` + `width:100%` 自适应——宽区间（90 天）时整图被
 *    等比压到容器宽度内，**坐标轴文字随之缩到 2~3px**，完全不可读。
 *    现在改为**按像素 1:1 绘制**（SVG 宽高用内联 px 固定），
 *    装不下时由外层 `.chart-scroll` 横向滚动——
 *    宁可让用户滑动，也不能让图表变成一条看不懂的细线。
 *
 * ⚠️ **可访问性**：图表旁必有等价的 `<table>`（见 ReportsPage），
 *    图表本身带 `role="img"` + aria-label 摘要，屏幕阅读器用户读表格而非图形。
 */

/** 柱状图数据点。 */
export interface BarDatum {
  /** 轴标签（日期） */
  label: string
  /** 数值 */
  value: number
  /** 该点的补充说明（tooltip / aria） */
  hint?: string
}

/** 折线图数据点。 */
export interface LineDatum {
  label: string
  value: number
  hint?: string
}

/** 序列配色（语义 → CSS 变量）。 */
export type SeriesTone = 'primary' | 'muted' | 'settled' | 'mark' | 'warn'

const TONE_FILL: Record<SeriesTone, string> = {
  primary: 'var(--ink-2)',
  muted: 'var(--ink-3)',
  settled: 'var(--settled)',
  mark: 'var(--mark)',
  warn: 'var(--pill-warn-fg)',
}

/** 每个数据点占据的像素宽（1:1 渲染下的标称间距）。 */
const SLOT_PX = 34

/** 图表最小总宽（点数少时铺满，避免出现一条窄窄的小图）。 */
const MIN_WIDTH_PX = 480

/** 柱子最大宽度（太宽会挤掉相邻柱）。 */
const MAX_BAR_PX = 18

/**
 * 取柱状图的最大值。
 *
 * ⚠️ 至少为 1：全零数据时若返回 0，除法会得到 NaN，柱高变成无效值。
 */
function maxOf(values: number[]): number {
  const max = values.reduce((acc, v) => (v > acc ? v : acc), 0)
  return max < 1 ? 1 : max
}

/**
 * 取**真实**最大值（不做防除零抬升）。
 *
 * ⚠️ 2026-10-24 UX 评审（P1-17）：渲染用的 max 被抬到至少 1，
 *    而这个**被抬高的值**曾被直接写进 aria-label 摘要——全零序列时
 *    屏上是一条贴基线的线（正确的「没有数据」），读屏却听到「峰值 1」。
 *    用假数字描述真状态，与本文件头「不编造」的纪律自相矛盾。
 *    故渲染用 max、叙述用峰值，分开计算。
 */
function trueMaxOf(values: number[]): number {
  return values.reduce((acc, v) => (v > acc ? v : acc), 0)
}

/** 生成「峰值」叙述片段：全零时说「区间内没有数据」，而不是编一个数。 */
function peakPhrase(peak: number, unit: string): string {
  return peak > 0 ? `峰值 ${peak}${unit}` : '区间内没有数据'
}

/**
 * 计算绘图几何。
 *
 * @param count 数据点个数
 * @returns 总宽、每点占宽、柱宽
 */
function geometry(count: number): { width: number; slot: number; bar: number } {
  const width = Math.max(count * SLOT_PX, MIN_WIDTH_PX)
  const slot = width / Math.max(count, 1)
  return { width, slot, bar: Math.max(2, Math.min(MAX_BAR_PX, slot - 8)) }
}

/**
 * 柱状图。
 *
 * ⚠️ 宽度用内联 px 固定（1:1 渲染），不写死像素宽
 *    —— 容器再窄也不会把坐标轴文字压成不可读。
 */
export function BarChart({
  data,
  tone = 'primary',
  height = 140,
  unit = '',
}: {
  data: BarDatum[]
  tone?: SeriesTone
  /** 绘图区高度（像素） */
  height?: number
  /** 数值单位（显示在摘要里） */
  unit?: string
}): JSX.Element {
  const max = maxOf(data.map((d) => d.value))
  const peak = trueMaxOf(data.map((d) => d.value))
  const total = data.reduce((acc, d) => acc + d.value, 0)
  const fill = TONE_FILL[tone]

  if (data.length === 0) {
    return (
      <p className="muted" style={{ margin: 0 }}>
        没有可绘制的数据。
      </p>
    )
  }

  const { width, slot, bar } = geometry(data.length)
  // ⚠️ 标签太密会互相压字：每点占宽不足 30px 时按步长抽稀
  const labelStep = slot < 30 ? Math.ceil(30 / slot) : 1

  return (
    <div
      className="chart-scroll"
      /*
       * ⚠️ 2026-10-24 UX 评审（P2-8）：长区间图表（30/90 天）装不下时
       *    靠横向滚动查看，而图表内部**没有任何可聚焦元素**——
       *    键盘用户无法滚动它（方向键只滚动**焦点所在**的容器）。
       *    故容器本身做成可聚焦的 region，并说明「可横向滚动」。
       */
      tabIndex={0}
      role="region"
      aria-label="柱状图，可横向滚动"
    >
      <svg
        className="chart"
        style={{ width: `${width}px`, height: `${height + 24}px` }}
        viewBox={`0 0 ${width} ${height + 24}`}
        preserveAspectRatio="xMinYMin meet"
        role="img"
        aria-label={`柱状图：共 ${data.length} 个数据点，合计 ${total}${unit}，${peakPhrase(peak, unit)}`}
      >
        {/* 基线 */}
        <line
          x1="0"
          y1={height}
          x2={width}
          y2={height}
          stroke="var(--rule-strong)"
          strokeWidth="1"
        />
        {data.map((d, i) => {
          const barHeight = d.value === 0 ? 0 : Math.max(2, (d.value / max) * (height - 8))
          const x = i * slot + (slot - bar) / 2
          return (
            <g key={d.label}>
              {barHeight > 0 ? (
                <rect x={x} y={height - barHeight} width={bar} height={barHeight} fill={fill} rx="2">
                  <title>{`${d.label}：${d.value}${unit}${d.hint !== undefined ? ` · ${d.hint}` : ''}`}</title>
                </rect>
              ) : null}
              {/* ⛔ 零值也要有基线刻度：否则「这天没有数据」与「这天有数据」在图上无法区分 */}
              <line
                x1={i * slot + slot / 2}
                y1={height}
                x2={i * slot + slot / 2}
                y2={height}
                stroke="var(--rule)"
                strokeWidth="1"
              />
              {i % labelStep === 0 || i === data.length - 1 ? (
                <text x={i * slot + slot / 2} y={height + 14} className="chart__label" textAnchor="middle">
                  {d.label}
                </text>
              ) : null}
            </g>
          )
        })}
      </svg>
    </div>
  )
}

/**
 * 折线图（多序列）。
 *
 * ⚠️ **零值要真的落在轴上**：序列全零时折线会贴在基线，
 *    与「没有这条序列」视觉上无法区分。故图例始终展示全部序列，
 *    并在图例里给出该序列合计。
 */
export function LineChart({
  series,
  height = 160,
  unit = '',
}: {
  /** 序列数组（至少一条） */
  series: { name: string; tone: SeriesTone; data: LineDatum[] }[]
  height?: number
  unit?: string
}): JSX.Element {
  if (series.length === 0) {
    return (
      <p className="muted" style={{ margin: 0 }}>
        没有可绘制的数据。
      </p>
    )
  }

  const length = Math.max(...series.map((s) => s.data.length))
  const { width, slot } = geometry(length)
  const max = maxOf(series.flatMap((s) => s.data.map((d) => d.value)))
  const peak = trueMaxOf(series.flatMap((s) => s.data.map((d) => d.value)))

  const pathFor = (data: LineDatum[]): string => {
    if (data.length === 0) return ''
    return data
      .map((d, i) => {
        const x = i * slot + slot / 2
        const y = height - (d.value / max) * (height - 8)
        return `${i === 0 ? 'M' : 'L'}${x},${y}`
      })
      .join(' ')
  }

  return (
    <>
      <div
        className="chart-scroll"
        /* 同上：容器可聚焦 + 标注「可横向滚动」 */
        tabIndex={0}
        role="region"
        aria-label="折线图，可横向滚动"
      >
        <svg
          className="chart"
          style={{ width: `${width}px`, height: `${height + 24}px` }}
          viewBox={`0 0 ${width} ${height + 24}`}
          preserveAspectRatio="xMinYMin meet"
          role="img"
          aria-label={`折线图：${series.length} 条序列，${peakPhrase(peak, unit)}`}
        >
          <line
            x1="0"
            y1={height}
            x2={width}
            y2={height}
            stroke="var(--rule-strong)"
            strokeWidth="1"
          />
          {series.map((s) => (
            <path
              key={s.name}
              d={pathFor(s.data)}
              fill="none"
              stroke={TONE_FILL[s.tone]}
              strokeWidth="2"
              strokeLinejoin="round"
              strokeLinecap="round"
            />
          ))}
          {/* 刻度只标首、中、尾 —— 30 天全标会糊成一片 */}
          {series[0]?.data.map((d, i, arr) => {
            if (i !== 0 && i !== arr.length - 1 && i !== Math.floor(arr.length / 2)) return null
            const x = i * slot + slot / 2
            const y = height - (d.value / max) * (height - 8)
            return (
              <g key={d.label}>
                <circle cx={x} cy={y} r="2.5" fill={TONE_FILL[series[0]?.tone ?? 'primary']}>
                  <title>{`${d.label}：${d.value}${unit}`}</title>
                </circle>
                <text x={x} y={height + 14} className="chart__label" textAnchor="middle">
                  {d.label}
                </text>
              </g>
            )
          })}
        </svg>
      </div>
      <ul className="chart__legend">
        {series.map((s) => {
          const sum = s.data.reduce((acc, d) => acc + d.value, 0)
          return (
            <li key={s.name} className="chart__legend-item">
              <span
                className="chart__swatch"
                style={{ background: TONE_FILL[s.tone] }}
                aria-hidden="true"
              />
              <span>
                {s.name}：合计 {sum}
                {unit}
              </span>
            </li>
          )
        })}
      </ul>
    </>
  )
}

/**
 * 横向条形图（排行用）。
 *
 * ⚠️ 排行图**必须有数值标签**：横向条形的长度只能给出相对印象，
 *    绝对值要靠右侧数字才读得准。
 */
export function RankBars({
  rows,
  unit = '',
}: {
  /** 行（已按需要的顺序排好） */
  rows: { label: string; value: number; hint?: string }[]
  unit?: string
}): JSX.Element {
  if (rows.length === 0) {
    return (
      <p className="muted" style={{ margin: 0 }}>
        没有可绘制的数据。
      </p>
    )
  }
  const max = maxOf(rows.map((r) => r.value))

  return (
    <ul className="rank">
      {rows.map((r) => (
        <li key={r.label} className="rank__row">
          <span className="rank__label" title={r.label}>
            {r.label}
          </span>
          <span className="rank__track" aria-hidden="true">
            <span className="rank__bar" style={{ width: `${(r.value / max) * 100}%` }} />
          </span>
          <span className="rank__value">
            {r.value}
            {unit}
            {r.hint !== undefined ? ` · ${r.hint}` : ''}
          </span>
        </li>
      ))}
    </ul>
  )
}
