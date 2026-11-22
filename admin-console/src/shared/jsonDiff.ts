/**
 * 轻量 JSON 配置 Diff（行级 + 嵌套路径）。
 *
 * <p>⚠️ <b>为什么自己实现</b>：Agent / Tool / Skill 的配置是 JSON 文本，
 * 差值以「叶子值」占绝大多数（改个模型名、改一条提示词），
 * 引 Monaco / diff-match-patch 会带来数 MB 依赖与打包/SSR 适配成本。
 * 这里用「先扁平化成 path → value，再逐 path 比对」的方式，
 * 既能定位到 <code>orchestration.children[0].agentId</code> 这种嵌套路径，
 * 又能给出 additions / deletions / changes 三类结果。
 *
 * <p>⚠️ <b>数组按索引比对</b>（不按内容匹配）：Agent 配置里数组多为
 * 「启用的 skill 列表」「子 Agent 列表」，顺序本身有语义（编排顺序），
 * 按索引比对才看得出「第 2 个子 Agent 被换掉了」。
 */

export type DiffKind = 'added' | 'removed' | 'changed'

export interface JsonDiffLine {
  /** 嵌套路径，如 {@code orchestration.children[0].agentId} */
  path: string
  /** 变更前（删除时为原值，新增时为 null） */
  before: string | null
  /** 变更后（删除时为 null，新增时为新值） */
  after: string | null
  kind: DiffKind
}

/** 把一个 JSON 值扁平化成 path → 展示字符串。 */
function flatten(value: unknown, prefix: string, out: Map<string, string>): void {
  if (value === null || value === undefined) {
    out.set(prefix || '(root)', 'null')
    return
  }
  if (Array.isArray(value)) {
    if (value.length === 0) {
      out.set(prefix || '(root)', '[]')
      return
    }
    value.forEach((item, i) => flatten(item, `${prefix}[${i}]`, out))
    return
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
    if (entries.length === 0) {
      out.set(prefix || '(root)', '{}')
      return
    }
    for (const [k, v] of entries) {
      flatten(v, prefix ? `${prefix}.${k}` : k, out)
    }
    return
  }
  out.set(prefix || '(root)', String(value))
}

function toDisplay(v: string): string {
  // 长文本（提示词）折叠成可读的一行摘要，避免 diff 面板被撑爆
  return v.length > 300 ? `${v.slice(0, 300)}…（共 ${v.length} 字符）` : v
}

/**
 * 计算两个 JSON 文本之间的差异。
 *
 * <p>解析失败时退化为「整段文本视为一条 changed」，而不是抛错 ——
 * 配置里若存了非法 JSON，用户更需要看到原始差异，而不是一个 500。
 */
export function diffJson(beforeText: string | null, afterText: string | null): JsonDiffLine[] {
  if (beforeText === afterText) return []

  let a: unknown
  let b: unknown
  let parsed = true
  try { a = JSON.parse(beforeText ?? 'null') } catch { parsed = false }
  try { b = JSON.parse(afterText ?? 'null') } catch { parsed = false }

  if (!parsed) {
    return [{
      path: '(整体)',
      before: beforeText ?? null,
      after: afterText ?? null,
      kind: beforeText == null ? 'added' : afterText == null ? 'removed' : 'changed',
    }]
  }

  const flatA = new Map<string, string>()
  const flatB = new Map<string, string>()
  flatten(a, '', flatA)
  flatten(b, '', flatB)

  const paths = Array.from(new Set([...flatA.keys(), ...flatB.keys()])).sort()
  const lines: JsonDiffLine[] = []
  for (const path of paths) {
    const va = flatA.get(path)
    const vb = flatB.get(path)
    if (va === vb) continue
    if (va === undefined) {
      lines.push({ path, before: null, after: toDisplay(vb as string), kind: 'added' })
    } else if (vb === undefined) {
      lines.push({ path, before: toDisplay(va), after: null, kind: 'removed' })
    } else {
      lines.push({ path, before: toDisplay(va), after: toDisplay(vb), kind: 'changed' })
    }
  }
  return lines
}

/** 差异统计（列表上直接展示 "+2 ~1 -0"）。 */
export function diffSummary(lines: JsonDiffLine[]): { added: number; removed: number; changed: number } {
  let added = 0, removed = 0, changed = 0
  for (const l of lines) {
    if (l.kind === 'added') added++
    else if (l.kind === 'removed') removed++
    else changed++
  }
  return { added, removed, changed }
}
