/**
 * 页标题同步（2026-10-24 新增）
 *
 * ⚠️ 为什么需要：SPA 切路由后 document.title 不变 ——
 *    用户在三个标签页里都开着本平台时，看到的全是同一个标题，
 *    分不清哪个是"分析进度"、哪个是"审批台"。
 *    浏览器历史、收藏、屏幕阅读器报页都依赖它。
 *
 * ⛔ 不引入任何依赖：一个 effect + 一个常量后缀足矣。
 */

import { useEffect } from 'react'

const SUFFIX = '错题归因辅导'

/**
 * 把页面标题设为 `${title} · ${SUFFIX}`。
 *
 * ⚠️ 卸载时**不**还原标题：本应用是单文档 SPA，
 *    离开页面只可能是切到另一个同样会设标题的页面；
 *    真出现"没人设标题"的空档（理论上只有卸载根组件），
 *    留给浏览器默认行为比留一句上页的残留标题更诚实。
 */
export function usePageTitle(title: string): void {
  useEffect(() => {
    document.title = `${title} · ${SUFFIX}`
  }, [title])
}
