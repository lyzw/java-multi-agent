/**
 * 字典消费 Hook：把下拉选项的来源从「代码常量」切到「字典表」。
 *
 * ⚠️ **为什么还要 fallback**：字典服务不可达时，表单不该变成一个空下拉——
 *    那会让「服务挂了」伪装成「这个字段没有可选值」。故保留一份静态兜底，
 *    但**如实标记来源**（`source`），管理页会提示当前处于兜底状态。
 *    兜底只保证「可用」，不保证「与数据库一致」——这一点必须可见。
 *
 * ⚠️ **为什么不每个字段各发一次请求**：一个表单里可能有 5～6 个字典下拉，
 *    逐字段请求会让首屏变成 N 次往返。故用进程级缓存 + 一次性拉取，
 *    通过 `useSyncExternalStore` 订阅，缓存更新时所有下拉同时刷新。
 */

import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import {
  getDictionaries,
  type DictionaryItem,
  type DictionaryView,
} from '@/api/dictionaryApi'
import { ApiError } from '@/api/client'

export interface DictOption {
  value: string
  label: string
}

/* ── 进程级缓存 ─────────────────────────────────────────── */

let cache: Record<string, DictionaryItem[]> = {}
let dictionaries: DictionaryView[] = []
let storeState: { version: number } = { version: 0 }
const listeners = new Set<() => void>()

function emit(): void {
  storeState = { version: storeState.version + 1 }
  for (const fn of listeners) fn()
}

function subscribe(fn: () => void): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

function getSnapshot(): number {
  return storeState.version
}

function apply(list: DictionaryView[]): void {
  dictionaries = list
  cache = {}
  for (const d of list) {
    cache[d.dictCode] = d.items
  }
  emit()
}

/* ── 静态兜底（字典不可达时保持可用，但会如实标记）───────── */

/**
 * 兜底选项。
 *
 * ⚠️ 与 `07_platform_dictionary_ddl.sql` 的种子**同值**——两者不一致时以数据库为准。
 */
export const DICT_FALLBACK: Record<string, DictOption[]> = {
  'http.method': ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].map((m) => ({
    value: m,
    label: m,
  })),
  'tool.risk_level': [
    { value: 'READ', label: 'READ（只读）' },
    { value: 'WRITE_PROFILE', label: 'WRITE_PROFILE（内部写）' },
    { value: 'WRITE_EXTERNAL', label: 'WRITE_EXTERNAL（外部写）' },
  ],
  'agent.orchestration_mode': [
    { value: 'LEAF', label: 'LEAF（叶子，不再委派）' },
    { value: 'ROUTER', label: 'ROUTER（按条件路由）' },
    { value: 'SEQUENTIAL', label: 'SEQUENTIAL（串行执行）' },
  ],
  'agent.failure_policy': [
    { value: 'FAIL_FAST', label: 'FAIL_FAST（立即失败）' },
    { value: 'CONTINUE', label: 'CONTINUE（继续）' },
    { value: 'COMPENSATE', label: 'COMPENSATE（补偿）' },
  ],
  'rag.source_type': [
    { value: 'TEXTBOOK', label: '教材' },
    { value: 'WORKBOOK', label: '教辅' },
    { value: 'PAPER', label: '论文' },
    { value: 'OTHER', label: '其他' },
  ],
  'rag.trust_tier': [
    { value: 'OFFICIAL_PUBLISHER', label: '教材出版社官方' },
    { value: 'OFFICIAL_TUTORIAL', label: '教辅品牌正式出版' },
    { value: 'PEER_REVIEWED_PAPER', label: '同行评审论文' },
    { value: 'PREPRINT', label: '预印本 / 白皮书' },
    { value: 'UNVERIFIED', label: '未校验' },
  ],
  'rag.verification_method': [
    { value: 'NONE', label: '无' },
    { value: 'ISBN', label: 'ISBN 校验' },
    { value: 'DOI', label: 'DOI 校验' },
    { value: 'MANUAL_REVIEW', label: '人工逐份比对' },
    { value: 'OFFICIAL_HASH', label: '官方哈希比对' },
  ],
  'rag.license_status': [
    { value: 'LICENSED', label: '已获授权' },
    { value: 'FAIR_USE_QUOTATION', label: '合理引用' },
    { value: 'PENDING', label: '未取得授权' },
    { value: 'RESTRICTED', label: '禁止使用' },
  ],
  'rag.subject': [
    { value: 'MATH', label: '数学' },
    { value: 'CHINESE', label: '语文' },
    { value: 'ENGLISH', label: '英语' },
    { value: 'PHYSICS', label: '物理' },
    { value: 'CHEMISTRY', label: '化学' },
    { value: 'BIOLOGY', label: '生物' },
    { value: 'HISTORY', label: '历史' },
    { value: 'GEOGRAPHY', label: '地理' },
    { value: 'POLITICS', label: '道德与法治' },
  ],
  'rag.grade': [
    { value: 'G7', label: '七年级' },
    { value: 'G8', label: '八年级' },
    { value: 'G9', label: '九年级' },
    { value: 'G1', label: '一年级' },
    { value: 'G2', label: '二年级' },
    { value: 'G3', label: '三年级' },
    { value: 'G4', label: '四年级' },
    { value: 'G5', label: '五年级' },
    { value: 'G6', label: '六年级' },
  ],
  'rag.term': [
    { value: 'FIRST', label: '上册' },
    { value: 'SECOND', label: '下册' },
    { value: 'FULL', label: '全一册' },
  ],
  'rag.publisher': [
    { value: 'RJ', label: '人教版' },
    { value: 'BSD', label: '北师大版' },
    { value: 'SJ', label: '苏教版' },
    { value: 'HJ', label: '沪教版' },
    { value: 'ZJ', label: '浙教版' },
    { value: 'WY', label: '外研版' },
  ],
}

/* ── 装载 ───────────────────────────────────────────────── */

export type DictSource = 'dictionary' | 'fallback'

export interface DictionariesState {
  list: DictionaryView[]
  loading: boolean
  error: string | null
  /** 当前数据是否来自兜底（字典服务不可达） */
  usingFallback: boolean
  reload: () => void
}

let inflight: Promise<DictionaryView[]> | null = null

/** 确保字典已装载（幂等；并发合并为一次请求）。 */
export function ensureDictionaries(): Promise<DictionaryView[]> {
  if (Object.keys(cache).length > 0) {
    return Promise.resolve(dictionaries)
  }
  if (inflight) {
    return inflight
  }
  inflight = getDictionaries(false)
    .then((list) => {
      apply(list)
      return list
    })
    .finally(() => {
      inflight = null
    })
  return inflight
}

/** 装载全部字典（页面级）。 */
export function useDictionaries(): DictionariesState {
  const version = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [nonce, setNonce] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    ensureDictionaries()
      .then(() => {
        if (cancelled) return
        setLoading(false)
      })
      .catch((cause: unknown) => {
        if (cancelled) return
        setLoading(false)
        setError(cause instanceof ApiError ? cause.message : '读取字典失败')
      })
    return () => {
      cancelled = true
    }
  }, [nonce])

  const reload = useCallback(() => {
    // 强制穿透缓存重拉
    cache = {}
    setNonce((n) => n + 1)
  }, [])

  return useMemo(
    () => ({
      list: dictionaries,
      loading,
      error,
      usingFallback: error !== null && Object.keys(cache).length === 0,
      reload,
    }),
    // ⚠️ version 参与依赖：缓存更新后重新计算快照
    [version, loading, error, reload],
  )
}

/**
 * 取某个字典的下拉选项。
 *
 * @param dictCode 字典编码
 * @returns 选项（已过滤停用项），以及数据来自字典还是兜底
 */
export function useDictOptions(dictCode: string): {
  options: DictOption[]
  source: DictSource
} {
  const version = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)

  useEffect(() => {
    // 触发一次装载（幂等，不会重复发请求）
    void ensureDictionaries().catch(() => {
      /* 失败由页面级 useDictionaries 呈现，这里静默走兜底 */
    })
  }, [dictCode])

  return useMemo(() => {
    const items = cache[dictCode]
    if (items && items.length > 0) {
      return {
        options: items
          .filter((i) => i.enabled)
          .sort((a, b) => a.sortOrder - b.sortOrder)
          .map((i) => ({ value: i.itemCode, label: i.itemLabel })),
        source: 'dictionary' as DictSource,
      }
    }
    return {
      options: DICT_FALLBACK[dictCode] ?? [],
      source: 'fallback' as DictSource,
    }
  }, [dictCode, version])
}

/** 字典项标签反查（用于表格把 code 渲染成中文）。 */
export function useDictLabel(dictCode: string): (code: string) => string {
  const { options } = useDictOptions(dictCode)
  return useCallback(
    (code: string) => options.find((o) => o.value === code)?.label ?? code,
    [options],
  )
}
