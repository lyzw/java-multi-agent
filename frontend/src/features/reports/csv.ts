/**
 * 报表 CSV 导出（纯前端 · 零依赖）
 *
 * ⚠️ **为什么导出的是「表格里那份数据」**：
 *    每张报表的表格与图表都由**同一个 rows 数组**渲染，
 *    导出直接复用它——保证「屏幕上看到的」与「导出的」逐格一致。
 *    若另起一套导出逻辑，就多一处可能与展示不一致的口径。
 *
 * ⚠️ **带 UTF-8 BOM**：Excel 打开无 BOM 的 UTF-8 CSV 会把中文显示成乱码，
 *    用户会以为导出坏了。BOM 是这里唯一「看不见但必须」的东西。
 *
 * ⚠️ **防公式注入**：单元格内容若以 {@code = + - @} 开头，
 *    Excel / WPS 会当成公式执行（=cmd|... 之类）。本项目的数据来自
 *    知识点名称、错因类型等**自由文本列**，不能假设它一定无害，
 *    故统一前置单引号中和。
 *
 * ⚠️ **不做任何服务端交互**：导出是纯本地动作，不上报、不落库。
 */

const BOM = '\uFEFF'

/** 需要中和的公式前缀（Excel / WPS 触发）。 */
const FORMULA_PREFIX = /^[=+\-@\t\r]/

/** 转义单个单元格。 */
function escapeCell(value: string | number): string {
  let text = typeof value === 'number' ? String(value) : value
  if (FORMULA_PREFIX.test(text)) {
    // ⚠️ 前置单引号：Excel 把它当文本，不当公式
    text = `'${text}`
  }
  if (/[",\n\r]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`
  }
  return text
}

/**
 * 把表头与数据行转成 CSV 文本。
 *
 * @param headers 表头
 * @param rows    数据行（每行长度应与表头一致）
 */
export function toCsv(headers: string[], rows: (string | number)[][]): string {
  const lines = [headers.map(escapeCell).join(',')]
  for (const row of rows) {
    lines.push(row.map(escapeCell).join(','))
  }
  return BOM + lines.join('\r\n') + '\r\n'
}

/**
 * 触发浏览器下载 CSV。
 *
 * @param filename 文件名（不含扩展名）
 * @param headers  表头
 * @param rows     数据行
 */
export function downloadCsv(
  filename: string,
  headers: string[],
  rows: (string | number)[][],
): void {
  const csv = toCsv(headers, rows)
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${filename}.csv`
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  // ⚠️ 必须回收：Blob URL 不释放会一直占着内存（90 天 × 7 张报表的量级不算大，
  //    但这是浏览器里最容易漏掉的一步）
  URL.revokeObjectURL(url)
}
