/**
 * 配置收紧语义的前端镜像（P4-D15 / FE-D11 · C5）。
 *
 * ⛔ **为什么必须是纯函数而不是 UI 内联逻辑**：
 * FE-02 §4 要求「约束落在**控件不存在**或**选项不可选**」，
 * 而「不可选」意味着 UI 必须知道**哪些方向被允许**。
 * 若该判断散落在组件里，改一处漏一处就会出现
 * 「下拉框里出现了不该出现的选项」——这是最危险的形态：
 * 它看起来完全正常，只是多点了一下就会失败。
 *
 * ⚠️ **与后端契约同构**：后端 `ConfigTighteningPolicy` 是权威，
 * 本模块是它的**镜像**，用于**提前收敛**用户可选项。
 * 前端**不得**把这里当作安全边界——真正的强制在后端，
 * 任何绕过 UI 的请求都会被 409 拒绝。
 */

import type { Tightening } from '@/api/types'

/** 审批要求等级（与后端 `ApprovalRequirement` 顺序一致）。 */
export type ApprovalLevel = 'NOT_REQUIRED' | 'OPTIONAL' | 'REQUIRED'

/** 收紧程度：数值越大保护越强（与后端 `strictness` 同构）。 */
export function strictnessOf(level: ApprovalLevel): number {
  return level === 'REQUIRED' ? 2 : level === 'OPTIONAL' ? 1 : 0
}

/** 是否为「收紧」方向（目标比当前更严）。 */
export function isTightening(current: ApprovalLevel, target: ApprovalLevel): boolean {
  return strictnessOf(target) > strictnessOf(current)
}

/**
 * 列出**允许**的审批等级（只含收紧方向）。
 *
 * ⛔ **核心纪律（FE-CFG-01）**：放宽方向**不在返回结果中**，
 * 而不是在选中后报错。FE-02 §4 明确指出后者是错误做法——
 * 「选了才报错」会让用户以为自己理解错了，实际是控件设计错了。
 */
export function allowedApprovalLevels(current: ApprovalLevel): ApprovalLevel[] {
  const all: ApprovalLevel[] = ['NOT_REQUIRED', 'OPTIONAL', 'REQUIRED']
  return all.filter((c) => strictnessOf(c) >= strictnessOf(current))
}

/**
 * 判断一次数值型上限写入是否被允许。
 *
 * @param current 当前生效值（未覆盖时为 yml 声明值）
 * @param next    用户拟写入的值
 * @returns 允许 → true；试图放宽或非正数 → false
 */
export function isNumericTightening(current: string, next: string): boolean {
  const n = Number(next)
  const c = Number(current)
  if (!Number.isFinite(n) || !Number.isFinite(c)) return false
  // ⛔ 非正数一律拒绝：0 或负上限会让 Run 永远无法执行（与后端同规则）
  if (n <= 0) return false
  return n <= c
}

/** 审批矩阵的字段名（`READ=REQUIRED` 中的 `READ`）。 */
export type MatrixKey = 'READ' | 'WRITE_PROFILE' | 'WRITE_EXTERNAL'

/**
 * 解析审批矩阵文本为 `{等级: 要求}`。
 *
 * ⚠️ **必须容忍 yml 的花括号形态**：后端 `declaredValueOf` 对嵌套 map
 * 返回 Java `Map.toString()`，形如 `{READ=NOT_REQUIRED, WRITE_EXTERNAL=REQUIRED}`。
 * P4-D15 D15-B1 就是漏了这个形态导致整个矩阵不可解析——前端必须同步容错。
 */
export function parseApprovalMatrix(raw: string): Partial<Record<MatrixKey, ApprovalLevel>> {
  let text = raw.trim()
  if (text.startsWith('{') && text.endsWith('}')) {
    text = text.slice(1, -1)
  }
  const out: Partial<Record<MatrixKey, ApprovalLevel>> = {}
  for (const part of text.split(',')) {
    const seg = part.trim()
    if (seg.length === 0) continue
    const eq = seg.indexOf('=')
    if (eq <= 0) continue
    const key = seg.slice(0, eq).trim()
    const value = seg.slice(eq + 1).trim()
    if (key === 'READ' || key === 'WRITE_PROFILE' || key === 'WRITE_EXTERNAL') {
      if (value === 'NOT_REQUIRED' || value === 'OPTIONAL' || value === 'REQUIRED') {
        out[key] = value
      }
    }
  }
  return out
}

/** 序列化为后端可解析的矩阵文本（不含花括号）。 */
export function formatApprovalMatrix(map: Partial<Record<MatrixKey, ApprovalLevel>>): string {
  return (Object.keys(map) as MatrixKey[])
    .map((k) => `${k}=${map[k]}`)
    .join(',')
}

/**
 * 校验一次矩阵写入：**任一**等级不得放松。
 *
 * ⚠️ **整体拒绝而非部分放行**：若允许「收紧 A 同时放松 B」，
 * 净效果仍是放松。用户容易只看到自己想改的那一项。
 */
export function isMatrixTightening(
  currentText: string,
  nextText: string,
): boolean {
  const current = parseApprovalMatrix(currentText)
  const next = parseApprovalMatrix(nextText)
  for (const key of Object.keys(next) as MatrixKey[]) {
    const from = current[key]
    const to = next[key]
    if (from === undefined || to === undefined) continue
    if (strictnessOf(to) < strictnessOf(from)) return false
  }
  return true
}

/** CHG 变更理由最少字数（与后端 `MIN_REASON_LENGTH` 一致）。 */
export const MIN_REASON_LENGTH = 10

/** 理由是否满足 CHG 要求。 */
export function isReasonValid(reason: string): boolean {
  return reason.trim().length >= MIN_REASON_LENGTH
}

/**
 * 一次写入是否可提交（UI 级前置校验）。
 *
 * ⚠️ 这**不是**安全边界，只是把明显不可提交的状态挡在按钮外。
 * 真正的强制在后端；前端漏判最多让用户多得到一次 409。
 */
export function canSubmit(
  tightening: Tightening,
  currentValue: string,
  nextValue: string,
  reason: string,
): boolean {
  if (nextValue.trim().length === 0) return false
  if (!isReasonValid(reason)) return false
  if (tightening === 'NEUTRAL') return true
  if (tightening === 'TIGHTEN_RATCHET') {
    return isMatrixTightening(currentValue, nextValue)
  }
  return isNumericTightening(currentValue, nextValue)
}