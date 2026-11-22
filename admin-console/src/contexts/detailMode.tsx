import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

/**
 * 全局详情查看形式（配置中心可切）。
 *
 * <p>⚠️ <b>全局配置</b>：所有页面的「点击某行看详情」交互统一走此配置，
 * 一处设置全平台生效，避免"这个页是抽屉、那个页是弹窗"的漂移。
 *
 * <p>持久化到 {@code localStorage['cap.detail.mode']}，跨刷新保留。
 * 缺省 {@link 'drawer'} —— 抽屉在列表上方开合，列表状态不丢（平台控制台
 * 大多数详情都是纯只读的，抽屉是最轻的选择）。
 */
export type DetailMode = 'drawer' | 'modal' | 'page'

const STORAGE_KEY = 'cap.detail.mode'
const DEFAULT_MODE: DetailMode = 'drawer'

function readInitial(): DetailMode {
  if (typeof window === 'undefined') return DEFAULT_MODE
  const raw = window.localStorage.getItem(STORAGE_KEY)
  if (raw === 'drawer' || raw === 'modal' || raw === 'page') return raw
  return DEFAULT_MODE
}

interface DetailModeContextValue {
  mode: DetailMode
  setMode: (m: DetailMode) => void
  /** 三种模式的人类可读标签 —— 下拉/单选里直接用 */
  labels: Record<DetailMode, string>
}

const DetailModeContext = createContext<DetailModeContextValue | null>(null)

export const DETAIL_MODE_LABELS: Record<DetailMode, string> = {
  drawer: '右侧抽屉',
  modal: '居中弹窗',
  page: '新页面',
}

/** 全局 Provider：包在 App 外层，让所有页面/组件都能读写详情形式配置。 */
export function DetailModeProvider({ children }: { children: ReactNode }): JSX.Element {
  const [mode, setModeState] = useState<DetailMode>(readInitial)

  const setMode = useCallback((next: DetailMode) => {
    setModeState(next)
    try {
      window.localStorage.setItem(STORAGE_KEY, next)
    } catch {
      /* localStorage 不可用（隐私模式等）时降级为内存态 */
    }
  }, [])

  // 跨 tab 同步：A 页改了抽屉/弹窗，B 页跟着变。
  useEffect(() => {
    const handler = (e: StorageEvent) => {
      if (e.key === STORAGE_KEY && e.newValue) {
        if (e.newValue === 'drawer' || e.newValue === 'modal' || e.newValue === 'page') {
          setModeState(e.newValue)
        }
      }
    }
    window.addEventListener('storage', handler)
    return () => window.removeEventListener('storage', handler)
  }, [])

  const value = useMemo<DetailModeContextValue>(
    () => ({ mode, setMode, labels: DETAIL_MODE_LABELS }),
    [mode, setMode],
  )

  return <DetailModeContext.Provider value={value}>{children}</DetailModeContext.Provider>
}

/** 读全局详情形式配置。 */
export function useDetailMode(): DetailModeContextValue {
  const ctx = useContext(DetailModeContext)
  if (!ctx) {
    // 未包 Provider 时给默认值（不直接抛错，避免局部测试不方便）。
    return {
      mode: DEFAULT_MODE,
      setMode: () => {},
      labels: DETAIL_MODE_LABELS,
    }
  }
  return ctx
}

/** localStorage 底层 key —— 配置页自己读写时用。 */
export const DETAIL_MODE_STORAGE_KEY = STORAGE_KEY
