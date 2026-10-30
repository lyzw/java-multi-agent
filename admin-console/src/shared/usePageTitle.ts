/**
 * 页标题同步 —— 平台控制台
 *
 * SPA 切路由后 document.title 不变，多标签页无法区分。
 */

import { useEffect } from 'react'

const SUFFIX = 'Agent 管理控制台'

export function usePageTitle(title: string): void {
  useEffect(() => {
    document.title = `${title} · ${SUFFIX}`
  }, [title])
}
