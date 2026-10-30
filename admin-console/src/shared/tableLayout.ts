import type { TableColumnsType } from 'antd'

/**
 * 表格横向滚动总宽的计算（平台控制台前端体验评测 P1-3）。
 *
 * ⚠️ **为什么不能写 `scroll={{ x: 'max-content' }}` 配固定列**：
 *    rc-table 在 `x: 'max-content'` 时按表体的自然内容宽度决定表格宽度，
 *    而 `fixed: 'right'` 的列是**另一层 sticky 覆盖层**，不参与那次测量 ——
 *    于是固定列永远压在最后一列上，横向滚到最右也露不全（实测：
 *    Skill 目录的「状态/声明/版本/发布时间」被操作列盖住）。
 *
 * <p>正确做法：给每一列显式 `width`，再用**各列宽度之和**作为 `scroll.x`。
 * 这样表格有确定宽度，固定列被预留出来，滚到最右最后一列完整可见。
 *
 * @param columns  列定义（每列都应有显式 width）
 * @param fallback 少数列漏配 width 时的兜底宽度（应尽量为 0，即提示漏配）
 */
export function scrollXOf<T>(columns: TableColumnsType<T>, fallback = 160): number {
  return columns.reduce<number>((sum, col) => {
    const width = (col as { width?: number | string }).width
    return sum + (typeof width === 'number' ? width : fallback)
  }, 0)
}
