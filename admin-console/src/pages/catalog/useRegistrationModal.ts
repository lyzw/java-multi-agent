import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'

/**
 * 登记弹窗的开合状态（第三十七批 · C37-3）。
 *
 * <p><b>⚠️ 为什么登记入口要带一个查询参数</b>：
 * 「登记」既可以从目录页的按钮点开，也可以从深链
 * {@code /tools/new} 直接到达。若深链只是跳回目录页而不打开弹窗，
 * 作者会看到「什么都没有发生」—— 而他明明点了一个「登记」链接。
 * 故深链跳转带上 {@code ?register=1}，由本 Hook 消费后**清掉该参数**
 * （清掉是为了刷新时不反复弹窗）。
 *
 * <p><b>⚠️ 为什么不在 Hook 里做数据加载</b>：
 * 登记弹窗的内容由目录页自己渲染，本 Hook 只管「开还是关」。
 */
export interface RegistrationModalState {
  /** 弹窗是否打开 */
  open: boolean
  /** 打开弹窗 */
  openModal: () => void
  /** 关闭弹窗 */
  closeModal: () => void
}

/**
 * 登记弹窗的开合状态。
 *
 * @returns 弹窗状态与操作
 */
export function useRegistrationModal(): RegistrationModalState {
  const [searchParams, setSearchParams] = useSearchParams()
  const [open, setOpen] = useState(false)

  useEffect(() => {
    if (searchParams.get('register') !== '1') return
    setOpen(true)
    // ⚠️ 清掉参数：否则刷新页面会再次弹窗（作者可能已经填了一半）。
    const next = new URLSearchParams(searchParams)
    next.delete('register')
    setSearchParams(next, { replace: true })
  }, [searchParams, setSearchParams])

  const openModal = useCallback(() => {
    setOpen(true)
  }, [])

  const closeModal = useCallback(() => {
    setOpen(false)
  }, [])

  return { open, openModal, closeModal }
}
