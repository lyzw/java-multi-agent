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

// ─────────────────────────────────────────────────────────────────
// JSON 文本形态的差异（保留完整层级结构，逐行标注变更）
// ─────────────────────────────────────────────────────────────────

/** 一行 JSON 文本，附带该行对应的配置路径与变更类型。 */
export interface JsonTextLine {
  /** 该行渲染文本（含行首缩进与尾随逗号，可直接拼成完整 JSON） */
  text: string
  /** 该行对应的配置路径（容器行如 {@code orchestration} 也有路径；根为 ''） */
  path: string
  /** 变更类型；{@code null} = 未变更（上下文行） */
  kind: DiffKind | null
}

/**
 * 把一份 JSON 文本拆成「行 + 该行的配置路径 + 变更类型」。
 *
 * <p>⚠️ <b>实现要点</b>：先 {@code JSON.stringify(x, null, 2)} 得到规范化的
 * 多行 JSON（每行恰好是一个 key / 一个数组元素 / 一个括号），
 * 再按行扫描还原该行的<b>配置路径</b>（栈式跟踪括号层级），
 * 最后用 {@link diffJson} 的叶子差异给每行打标记。
 *
 * <p>这样渲染出来<b>就是标准 JSON</b>（缩进、引号、逗号都对），
 * 而不是自拼的近似结构 —— 自拼很容易在嵌套容器处漏掉 key 前缀。
 *
 * <p>⚠️ <b>容器行也标注</b>：新增/删除一整块时，其开闭括号行与内部叶子
 * 都会被标出来，用户能看出「整段被删除了」而不只是零散几行。
 */
export function toJsonDiffLines(
  beforeText: string | null,
  afterText: string | null,
  side: 'before' | 'after',
): JsonTextLine[] {
  const src = side === 'before' ? beforeText : afterText
  const isEmpty = src == null || src === ''
  if (isEmpty) return []

  let value: unknown
  let parsed = true
  try { value = JSON.parse(src) } catch { parsed = false }

  if (!parsed) {
    // 非法 JSON：原文逐行输出，全部标记为变更（不吞内容）
    return src.split('\n').map((text) => ({ text, path: '(非 JSON 原文)', kind: 'changed' as const }))
  }

  const pretty = JSON.stringify(value, null, 2)

  // 该侧（本版本）相对另一侧的差异标记
  const leafKind = new Map<string, DiffKind>()
  const sideValue = side === 'before' ? value : value
  const sidePaths = new Set<string>()
  const collect = (v: unknown, prefix: string) => {
    if (v === null || v === undefined) { sidePaths.add(prefix); return }
    if (Array.isArray(v)) {
      if (v.length === 0) { sidePaths.add(prefix); return }
      v.forEach((item, i) => collect(item, `${prefix}[${i}]`))
      return
    }
    if (typeof v === 'object') {
      // ⚠️ 容器自身也记入路径集合：差异可能落在容器层
      //    （如 `{}` → `{k:1}` 时 diffJson 只在容器路径上给出一条记录），
      //    只记叶子会导致「变更后」侧的容器行漏标。
      if (prefix) sidePaths.add(prefix)
      const entries = Object.entries(v as Record<string, unknown>)
      if (entries.length === 0) { sidePaths.add(prefix); return }
      for (const [k, child] of entries) collect(child, prefix ? `${prefix}.${k}` : k)
      return
    }
    sidePaths.add(prefix)
  }
  collect(sideValue, '')

  for (const l of diffJson(beforeText, afterText)) {
    // ⚠️ 本侧根本不存在该路径（对方新增 / 对方删除）→ 不标。
    //    强行标记会让「新增字段」在左侧也出现一条红线，读起来像被删了。
    if (!sidePaths.has(l.path)) continue
    // ⚠️ 本侧存在即代表「这行在我这有、且和对侧不一样」→ 统一标 changed。
    //    空容器 ↔ 非空容器（`{}` → `{k:1}`）也会落到这里：
    //    diffJson 会产出 removed+added 两条，若分别映射，左侧显示「删除 o」、
    //    右侧显示「新增 o」，实际只是内容变了 —— 归正为 changed 更准确。
    leafKind.set(l.path, 'changed')
  }

  return annotateLines(pretty, leafKind)
}

/**
 * 扫描规范化 JSON 的每一行，还原其配置路径并打上变更标记。
 *
 * <p>行形态只有五种（{@code stringify(_, null, 2)} 的产物）：
 * <pre>
 *   {            / [            容器开
 *   }            / ]            容器闭（可能带逗号）
 *   "key": {     / "key": [     带 key 的容器开
 *   "key": 值,                   键值行
 *   值,                          数组元素行
 * </pre>
 */
function annotateLines(pretty: string, leafKind: Map<string, DiffKind>): JsonTextLine[] {
  const rawLines = pretty.split('\n')
  /** 路径栈：每层记录「当前容器的路径」与「容器类型」 */
  const stack: Array<{ path: string; isArray: boolean; nextIndex: number }> = []
  const out: JsonTextLine[] = []

  const currentPath = (): string => (stack.length === 0 ? '' : stack[stack.length - 1]!.path)

  for (const line of rawLines) {
    const trimmed = line.trim()
    // 容器关闭：先取当前路径（该容器的路径），再出栈
    const isClose = trimmed === '}' || trimmed === '},' || trimmed === ']' || trimmed === '],'
    if (isClose) {
      const node = stack.pop()
      const path = node?.path ?? ''
      out.push({ text: line, path, kind: leafKind.get(path) ?? null })
      continue
    }

    // 容器开启（含带 key 的形式）
    const openMatch = /^("(?:[^"\\]|\\.)*"):\s*(\{|\[)$/.exec(trimmed)
    if (openMatch) {
      const key = JSON.parse(openMatch[1]!) as string
      const parent = stack[stack.length - 1]
      const parentPath = parent?.path ?? ''
      const path = parentPath ? `${parentPath}.${key}` : key
      out.push({ text: line, path, kind: leafKind.get(path) ?? null })
      stack.push({ path, isArray: openMatch[2] === '[', nextIndex: 0 })
      continue
    }
    // 开括号行：只有【栈为空】时才是顶层容器；
    // 在数组内部时它是数组元素的开始，路径必须挂到 `xxx[i]` 上（见下方数组元素分支）。
    if ((trimmed === '{' || trimmed === '[') && stack.length === 0) {
      const isArr = trimmed === '['
      out.push({ text: line, path: '', kind: null })
      stack.push({ path: '', isArray: isArr, nextIndex: 0 })
      continue
    }

    // 键值行
    const kvMatch = /^("(?:[^"\\]|\\.)*"):\s*(.+?),?$/.exec(trimmed)
    if (kvMatch) {
      const key = JSON.parse(kvMatch[1]!) as string
      const parentPath = currentPath()
      const path = parentPath ? `${parentPath}.${key}` : key
      out.push({ text: line, path, kind: leafKind.get(path) ?? null })
      continue
    }

    // 数组元素行（可能是对象/数组的简写单行，也可能是普通值）
    const parent = stack[stack.length - 1]
    if (parent?.isArray) {
      const path = `${parent.path}[${parent.nextIndex}]`
      parent.nextIndex += 1
      // ⚠️ 元素是对象/数组时，这一行只是它的开括号：
      //    需要用「元素路径」作为新容器的路径压栈，后续子键才能拼出
      //    `children[0].agentId` 这样的完整路径。
      if (trimmed === '{' || trimmed === '[') {
        out.push({ text: line, path, kind: leafKind.get(path) ?? null })
        stack.push({ path, isArray: trimmed === '[', nextIndex: 0 })
        continue
      }
      out.push({ text: line, path, kind: leafKind.get(path) ?? null })
      continue
    }

    // 兜底：无法识别的行按无变更输出（保留 JSON 完整性优先）
    out.push({ text: line, path: '', kind: null })
  }
  return out
}
