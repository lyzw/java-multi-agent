/**
 * 敏感信息预检（FE-D1 §6）—— 合规闸门（体验层）
 *
 * ⛔ 两层闸门，缺一不可：
 *   · 前端预检（本文件）= **体验优化**，提前阻断，避免无谓往返
 *   · 后端扫描（DataClassification.IdentifyingPattern）= **安全边界**，不可绕过
 *
 * 前端预检被绕过时，后端仍会返回 400 拒绝。
 * 故本模块是"减少往返"，不是"保障安全"。
 *
 * ⚠️⚠️ 正则**逐条对齐后端实现**，非自行设计。
 *   来源：agent-core/.../DataClassification.java#IdentifyingPattern
 *
 *   ⚠️ 后端注释记载了一个被契约测试证伪的真实缺陷：
 *      「必须用词边界而非 ^$ 锚定：实际 Prompt 是
 *        「学生 110101… 的错题」这样的句子，整串匹配会完全失效」
 *   → 故下列正则**全部使用前后查找(?<!)(?!)而非 ^$**。
 *   → 本文件初版误用裸 \\d{8,12}，13 位数字会被误判为学号，
 *     已按后端实现修正（此类"正则锚定"缺陷在 P1 已出现过一次）。
 *
 *   ⚠️ 后端为纵深防御的一层，「正则无法穷尽所有编码/隐写形式」。
 *     故前端不得把"预检通过"当作"内容合规"。
 */

export type ViolationKind = 'ID_CARD' | 'MOBILE' | 'STUDENT_ID' | 'EMAIL'

export interface Violation {
  kind: ViolationKind
  /** 面向用户的中文提示（不回显命中内容，避免二次泄漏） */
  message: string
  /** 命中的规则说明，供测试断言与文档追溯 */
  rule: string
}

/**
 * ⚠️ 与后端 `DataClassification.IdentifyingPattern` 一一对应。
 * 改动任一条须同步核对后端，否则前端会漏检（误以为通过而后端拒绝）
 * 或误杀（后端放行而前端阻断，用户无法解释）。
 */
const PATTERNS: { kind: ViolationKind; regex: RegExp; message: string; rule: string }[] = [
  {
    kind: 'ID_CARD',
    // 后端：(?<![0-9A-Za-z])[1-9]\d{5}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[0-9Xx](?![0-9A-Za-z])
    // 前端用等价的宽松式：18 位（末位 X）或 15 位，前后不接字母数字
    regex: /(?<![0-9A-Za-z])\d{17}[\dXx](?![0-9A-Za-z])|(?<![0-9A-Za-z])\d{15}(?![0-9A-Za-z])/,
    message: '内容中疑似包含身份证号，请移除',
    rule: '身份证（15 或 18 位）',
  },
  {
    kind: 'MOBILE',
    // 后端：(?<![0-9])1[3-9]\d{9}(?![0-9])
    regex: /(?<![0-9])1[3-9]\d{9}(?![0-9])/,
    message: '内容中疑似包含手机号，请移除',
    rule: '手机号（11 位，1 开头，第二位 3-9）',
  },
  {
    kind: 'STUDENT_ID',
    // 后端：(?<![0-9])[0-9]{8,12}(?![0-9])
    // ⚠️ 前后查找是必需的：无锚定会让 13 位数字匹配其前 12 位而误判
    regex: /(?<![0-9])\d{8,12}(?![0-9])/,
    message: '内容中疑似包含学号或连续数字标识，请移除',
    rule: '学号（8–12 位数字，前后不接数字）',
  },
  {
    kind: 'EMAIL',
    // 后端：(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}(?![A-Za-z0-9.-])
    regex: /(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}(?![A-Za-z0-9.-])/,
    message: '内容中疑似包含邮箱地址，请移除',
    rule: '邮箱',
  },
]

/**
 * ⚠️ 空格绕过问题（前端比后端更严，有意为之）：
 *
 *   后端正则在**原始文本**上匹配，故 `138 1234 5678`（带空格）
 *   **前后端都漏检** —— 这是一个真实的合规缺口（PIPL 禁采手机号）。
 *
 *   前端的处理是**分模式收紧**，而非全局去空格：
 *   · MOBILE / ID_CARD / EMAIL → 额外对「去空格文本」再匹配一次。
 *     这三类带固定结构，带空格的书写方式几乎不可能是正常数学内容。
 *   · STUDENT_ID → **不做**去空格匹配。
 *     ⚠️ 因为学号就是纯数字，而数学题里「求 1 2 3 4 5 6 7 8」极常见，
 *       全局去空格会把正常题目误判为学号 —— 那是更糟的误杀。
 *
 *   → 结论：`STUDENT_ID` 的空格绕过**需后端修复**，
 *     已记录为 FE-D1 §12 待办；前端不做猜测性收紧。
 */

/** 需要额外做去空格匹配的规则键（结构固定，带空格几乎必为绕过） */
const STRIP_WHITESPACE_SENSITIVE: ReadonlySet<ViolationKind> = new Set<ViolationKind>([
  'ID_CARD',
  'MOBILE',
  'EMAIL',
])

/**
 * 扫描文本中的可识别信息。
 *
 * ⚠️ 返回的 message **不回显命中内容**——
 * 回显等于把用户刚输入的敏感信息又显示一遍。
 */
export function scanText(text: string): Violation[] {
  const violations: Violation[] = []
  if (text.trim().length === 0) return violations

  // 供结构固定类规则使用的去空白变体（见 STRIP_WHITESPACE_SENSITIVE 说明）
  const compact = text.replace(/\s+/g, '')

  for (const pattern of PATTERNS) {
    const hit = pattern.regex.test(text)
    const hitCompact =
      !hit &&
      STRIP_WHITESPACE_SENSITIVE.has(pattern.kind) &&
      compact !== text &&
      pattern.regex.test(compact)

    if (hit || hitCompact) {
      violations.push({
        kind: pattern.kind,
        message: pattern.message,
        rule: pattern.rule,
      })
    }
  }
  return violations
}

/** 字段级扫描结果 */
export type FieldScanResult =
  | { ok: true }
  | { ok: false; violations: Violation[] }

/** 扫描单个字段 */
export function scanField(text: string): FieldScanResult {
  const violations = scanText(text)
  if (violations.length === 0) return { ok: true }
  return { ok: false, violations }
}

/** 扫描整个表单，返回「字段名 → 该字段违规」映射 */
export function scanForm(
  fields: Record<string, string>,
): Record<string, Violation[]> {
  const result: Record<string, Violation[]> = {}
  for (const [name, value] of Object.entries(fields)) {
    const violations = scanText(value)
    if (violations.length > 0) {
      result[name] = violations
    }
  }
  return result
}

export function hasViolation(formViolations: Record<string, Violation[]>): boolean {
  return Object.keys(formViolations).length > 0
}

/** 规则元信息，供测试断言与文档追溯 */
export const SCAN_RULES = PATTERNS.map((p) => ({
  kind: p.kind,
  rule: p.rule,
  source: p.regex.source,
}))
