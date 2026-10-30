import type { ReactNode } from 'react'

/**
 * 加载期占位文案（平台控制台前端体验评测 P1-1）。
 *
 * ⚠️ **为什么不能直接渲染空态文案**：请求还没回来时，「空」只是「还没知道」，
 *    不是「确实没有」。AntD 的 Table 在 `loading` 时只在表体上盖一层 Spin，
 *    `locale.emptyText` 仍然会渲染在下面 —— 于是屏幕同时出现
 *    「转圈 + 暂无平台用户 / 暂无数据 / 0 个 Agent」。
 *    运营扫一眼会把**加载中的假空态**当成事实（本次评测即因此一度误判
 *    Agent 注册表为「0 个 Agent」，而实际有 4 个）。
 */
export const LOADING_PLACEHOLDER = '加载中…'

/**
 * 加载期渲染中性占位，加载完成后再渲染结论性的空态文案。
 *
 * @param loading 当前是否处于加载态
 * @param text    加载完成后的空态文案（结论性，如「暂无租户」）
 */
export function emptyTextWhileLoading(loading: boolean, text: ReactNode): ReactNode {
  return loading ? LOADING_PLACEHOLDER : text
}
