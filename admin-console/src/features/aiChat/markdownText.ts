/**
 * LLM 回答正文 → Markdown/LaTeX 渲染前的纯文本预处理（无 React、无 IO，可单测）。
 *
 * <p><b>为什么需要这一层</b>：
 * <ol>
 *   <li><code>remark-math</code> 只认 <code>$…$</code> / <code>$$…$$</code>（见
 *       micromark-extension-math 的 Syntax BNF），而国内模型极爱输出 LaTeX 传统写法
 *       <code>\[ … \]</code> / <code>\( … \)</code>。不归一化，公式会原样糊在屏幕上。</li>
 *   <li>模型还会写出<b>裸 LaTeX 命令</b>（如 <code>**(\Delta&lt;0)**</code>），
 *       KaTeX 看不到定界符不会接管。完成态把白名单内的命令补成行内数学。</li>
 *   <li><b>流式期间公式是半个的</b>：<code>\[</code> 已到、<code>\]</code> 未到。
 *       若直接丢给 KaTeX，会闪红字 <code>katex-error</code>、甚至把后续正文吞进公式。
 *       故只转换「已成对」的定界符，未闭合的 <code>$</code> 一律转义按原文显示，
 *       闭合后自然切到公式 —— 不伪造内容，也不制造解析错误。</li>
 *   <li>未完成的代码围栏会把后面所有回答变成代码块，必须补齐。</li>
 * </ol>
 *
 * <p><b>纪律</b>：只做「呈现层」改写，绝不改动 {@link ChatMessage.content}。
 * 页面提供「原文」视图用于核对模型原始字节，避免把重排伪装成模型输出。
 */

/** 代码块 / 行内代码：这些区间内的任何字符都不参与定界符识别或裸命令补全。 */
const CODE_SPAN_RE = /```[\s\S]*?```|~~~[\s\S]*?~~~|`[^`\n]*`/g

/** 已经用美元写好的数学：同样要整体跳过（防止二次包裹）。 */
const DOLLAR_MATH_RE = /\$\$[\s\S]*?\$\$|\$[^$\n]*?\$/g

/**
 * 允许在<b>裸文本</b>中自动补成行内数学的命令白名单。
 * 刻意不放 <code>\text</code> / <code>\mathrm</code> 这类「讲解 LaTeX 时会用到」的词，
 * 也刻意不放 <code>\*</code> / <code>\_</code> 等 Markdown 转义符，避免误伤普通文本。
 */
const BARE_LATEX_RE =
  /\\(?:frac|dfrac|tfrac|sqrt|overline|vec|hat|bar)\{[^{}]*\}(?:\{[^{}]*\})?|\\(?:Delta|Gamma|Theta|Lambda|Sigma|Omega|Pi|alpha|beta|gamma|delta|epsilon|varepsilon|theta|lambda|mu|pi|sigma|phi|varphi|omega|infty|partial|sum|prod|int|lim|log|ln|sin|cos|tan|angle|triangle|cdot|ldots|cdots|quad|qquad|times|div|pm|mp|ne|neq|le|leq|ge|geq|approx|sim|equiv|to|rightarrow|Rightarrow|in|notin|subset|subseteq|cup|cap|forall|exists|degree)(?![A-Za-z])/g

type TokenKind =
  | 'bracketOpen'
  | 'bracketClose'
  | 'parenOpen'
  | 'parenClose'
  | 'dollarFlow'
  | 'dollarInline'

interface Token {
  index: number
  kind: TokenKind
}

interface Replacement {
  index: number
  length: number
  text: string
}

/** 在 <code>protectedRe</code> 命中的区间之外，对文本逐段应用 <code>fn</code>。 */
function mapOutside(src: string, protectedRe: RegExp, fn: (gap: string) => string): string {
  let out = ''
  let last = 0
  for (const match of src.matchAll(protectedRe)) {
    const start = match.index ?? 0
    out += fn(src.slice(last, start)) + match[0]
    last = start + match[0].length
  }
  return out + fn(src.slice(last))
}

/** 代码区间之外的片段才交给 <code>fn</code>（含美元数学区间的二次保护，由调用方决定）。 */
function mapOutsideCode(src: string, fn: (gap: string) => string): string {
  return mapOutside(src, CODE_SPAN_RE, fn)
}

/**
 * 补齐未闭合的围栏代码块。
 *
 * 只认「行首 0–3 空格 + 至少 3 个同类反引号/波浪号」，与 CommonMark 一致；
 * 行内三反引号不会误判。流式结束时模型也可能真的截断，完成态同样补齐。
 */
export function closeUnclosedFence(src: string): string {
  let open: { char: string; len: number } | null = null
  for (const line of src.split('\n')) {
    const match = /^ {0,3}(`{3,}|~{3,})/.exec(line)
    if (match === null) continue
    const token = match[1] ?? ''
    const char = token[0] ?? ''
    if (open === null) {
      open = { char, len: token.length }
    } else if (char === open.char && token.length >= open.len) {
      open = null
    }
  }
  if (open === null) return src
  return src + (src.endsWith('\n') ? '' : '\n') + open.char.repeat(open.len) + '\n'
}

/**
 * 把代码块内容换成等长空格（保留换行与索引），供定界符扫描使用。
 * 这样代码块里的 <code>$</code> 不会被误判成公式边界。
 */
function maskCodeBlocks(src: string): string {
  const chars = src.split('')
  let offset = 0
  let fenceStart: number | null = null
  let fence: { char: string; len: number } | null = null
  for (const line of src.split('\n')) {
    const match = /^ {0,3}(`{3,}|~{3,})/.exec(line)
    if (match !== null) {
      const token = match[1] ?? ''
      const char = token[0] ?? ''
      if (fence === null) {
        fence = { char, len: token.length }
        fenceStart = offset
      } else if (char === fence.char && token.length >= fence.len && fenceStart !== null) {
        for (let i = fenceStart; i < offset + line.length && i < chars.length; i += 1) {
          if (chars[i] !== '\n') chars[i] = ' '
        }
        fence = null
        fenceStart = null
      }
    }
    offset += line.length + 1
  }
  return chars.join('')
}

/** 扫描美元定界符；已被反斜杠转义的 <code>\$</code> / <code>\\</code> 跳过。 */
function scanDollarTokens(masked: string): Token[] {
  const tokens: Token[] = []
  let i = 0
  while (i < masked.length) {
    const ch = masked[i]
    if (ch === '\\') {
      i += 2
      continue
    }
    if (ch === '$') {
      if (masked[i + 1] === '$') {
        tokens.push({ index: i, kind: 'dollarFlow' })
        i += 2
      } else {
        tokens.push({ index: i, kind: 'dollarInline' })
        i += 1
      }
      continue
    }
    i += 1
  }
  return tokens
}

/**
 * 转义「未闭合」的美元定界符，让它们按普通字符显示。
 *
 * 配对规则与 micromark-extension-math 对齐：<code>$$</code> 是块围栏（跨行配对），
 * 单个 <code>$</code> 是行内（同一行内配对）。跨行的孤立 <code>$</code> 不配对。
 */
export function escapeUnclosedDollars(src: string): string {
  const masked = maskCodeBlocks(src)
  const tokens = scanDollarTokens(masked)
  const escapes: Token[] = []

  let openFlow: Token | null = null
  let inlinePending: Token[] = []
  let cursor = 0

  const flushLine = (): void => {
    // 一行内若有奇数个单 $，最后一个未配对 → 转义。
    const dangling = inlinePending[inlinePending.length - 1]
    if (dangling !== undefined) escapes.push(dangling)
    inlinePending = []
  }

  for (const token of tokens) {
    const newline = masked.indexOf('\n', cursor)
    if (newline >= 0 && newline < token.index) flushLine()
    cursor = token.index

    if (openFlow !== null) {
      if (token.kind === 'dollarFlow') openFlow = null
      continue
    }
    if (token.kind === 'dollarFlow') {
      openFlow = token
      continue
    }
    if (inlinePending.length > 0) inlinePending.pop()
    else inlinePending.push(token)
  }
  flushLine()
  if (openFlow !== null) escapes.push(openFlow)

  let out = src
  for (const token of escapes.sort((a, b) => b.index - a.index)) {
    if (token.kind === 'dollarFlow') {
      out = `${out.slice(0, token.index)}\\$\\$${out.slice(token.index + 2)}`
    } else {
      out = `${out.slice(0, token.index)}\\$${out.slice(token.index + 1)}`
    }
  }
  return out
}

/** 扫描 <code>\[ \]</code> / <code>\( \)</code>，跳过已转义的反斜杠对。 */
function scanBracketTokens(text: string): Token[] {
  const tokens: Token[] = []
  let i = 0
  while (i < text.length) {
    if (text[i] !== '\\') {
      i += 1
      continue
    }
    const next = text[i + 1]
    if (next === '[') tokens.push({ index: i, kind: 'bracketOpen' })
    else if (next === ']') tokens.push({ index: i, kind: 'bracketClose' })
    else if (next === '(') tokens.push({ index: i, kind: 'parenOpen' })
    else if (next === ')') tokens.push({ index: i, kind: 'parenClose' })
    i += 2
  }
  return tokens
}

/**
 * 把<b>已成对</b>的 LaTeX 括号定界符换成 remark-math 认识的美元定界符。
 *
 * 只转成对的：流式时 <code>\[</code> 先到、<code>\]</code> 未到，保持源码原样，
 * 等闭合的那一帧到达才整体变成公式 —— 不会出现半截 KaTeX 红字。
 */
export function normalizeMathDelimiters(src: string): string {
  const convert = (text: string): string => {
    const brackets: number[] = []
    const parens: number[] = []
    const replacements: Replacement[] = []
    for (const token of scanBracketTokens(text)) {
      switch (token.kind) {
        case 'bracketOpen':
          brackets.push(token.index)
          break
        case 'bracketClose': {
          const open = brackets.pop()
          if (open !== undefined) {
            replacements.push({ index: open, length: 2, text: '$$' })
            replacements.push({ index: token.index, length: 2, text: '$$' })
          }
          break
        }
        case 'parenOpen':
          parens.push(token.index)
          break
        case 'parenClose': {
          const open = parens.pop()
          if (open !== undefined) {
            replacements.push({ index: open, length: 2, text: '$' })
            replacements.push({ index: token.index, length: 2, text: '$' })
          }
          break
        }
        default:
          break
      }
    }
    let out = text
    for (const r of replacements.sort((a, b) => b.index - a.index)) {
      out = out.slice(0, r.index) + r.text + out.slice(r.index + r.length)
    }
    return out
  }
  // 代码块内、已有美元数学内的括号一律不碰。
  return mapOutsideCode(src, (gap) => mapOutside(gap, DOLLAR_MATH_RE, convert))
}

/** 完成态：把裸文本里的白名单 LaTeX 命令补成 <code>$…$</code>。 */
export function normalizeBareLatex(src: string): string {
  const wrap = (gap: string): string => gap.replace(BARE_LATEX_RE, (expr) => `$${expr}$`)
  return mapOutsideCode(src, (gap) => mapOutside(gap, DOLLAR_MATH_RE, wrap))
}

/**
 * 渲染前统一入口。
 *
 * @param source 模型原始增量文本（只读，绝不回写状态）
 * @param streaming 是否仍在接收：流式不补裸 LaTeX（省去逐 token 的全文重扫、
 *                  也避免正文里的公式一会儿是源码一会儿是符号）；完成态补全。
 */
export function prepareAnswerText(source: string, streaming: boolean): string {
  const fenced = closeUnclosedFence(source)
  const paired = normalizeMathDelimiters(fenced)
  const safe = escapeUnclosedDollars(paired)
  return streaming ? safe : normalizeBareLatex(safe)
}
