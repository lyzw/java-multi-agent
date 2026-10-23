/**
 * 报表中心数据 Hook（工作台统计 · 报表中心页）
 *
 * 单一数据源：**两个**后端，不是两个前端。
 *   · 平台侧  GET /api/statistics/daily            → 辅导量 / 错题分析 / 学生活跃 / 知识点排行
 *   · RAG 侧  GET /api/v1/management/statistics/…  → 每日检索次数 / 每日导入语料量
 *
 * ⚠️ **为什么平台侧与 RAG 侧要分开成两个状态**：
 *    二者的失败语义完全不同——平台侧挂掉只影响辅导业务数据，
 *    RAG 侧挂掉只影响语料数据。合成一个状态会让「RAG 挂了」
 *    表现为「报表全空」，运维会误判成「平台没有数据」。
 *    故平台侧与 RAG 侧各自独立成态，页面分别呈现。
 *
 * ⚠️ **五态纪律**（沿用 FE-D3）：默认 / 加载 / 成功 / 错误 / 超时各自可辨。
 *    超时与其它错误分开——「查得慢」与「查失败」的下一步建议完全不同。
 *
 * ⚠️ **不做自动轮询**：报表是人工查看的快照，不是实时监控。
 *    自动轮询会在管理员阅读报表时不断发请求，且会把
 *    「什么时候看的快照」搞混（同 RagMaintenancePanels 的手动触发纪律）。
 *
 * ⚠️ 防竞态：effect 内 `cancelled` 守卫，避免组件卸载
 *    （含 StrictMode 双挂载）后 setState。
 */

import { useCallback, useEffect, useState } from 'react'
import {
  getDailyStatistics,
  getRagDailySearches,
  getRagDailyImports,
  getRagStatistics,
  ApiError,
} from '@/api/client'
import { isTimeoutError } from '@/api/errors'
import type {
  DailyStatistics,
  RagDailySearchStatistics,
  RagDailyImportStatistics,
  RagStatistics,
} from '@/api/types'

/** 报表区块状态。 */
export type ReportStatus = 'idle' | 'loading' | 'success' | 'error' | 'timeout'

/** 平台侧聚合状态。 */
export interface PlatformStatsState {
  status: ReportStatus
  data: DailyStatistics | null
  error: string | null
}

/** RAG 侧聚合状态（检索次数 + 导入量 + 语料准入概况）。 */
export interface RagStatsState {
  status: ReportStatus
  searches: RagDailySearchStatistics | null
  imports: RagDailyImportStatistics | null
  corpus: RagStatistics | null
  error: string | null
}

const IDLE_PLATFORM: PlatformStatsState = { status: 'idle', data: null, error: null }
const IDLE_RAG: RagStatsState = {
  status: 'idle',
  searches: null,
  imports: null,
  corpus: null,
  error: null,
}

const PLATFORM_FALLBACK = '读取平台统计失败，请稍后重试'
const RAG_FALLBACK = '读取语料统计失败，请稍后重试'

function toPlatformState(cause: unknown): PlatformStatsState {
  if (cause instanceof ApiError) {
    return {
      status: isTimeoutError(cause.normalized) ? 'timeout' : 'error',
      data: null,
      error: cause.normalized.message,
    }
  }
  return { status: 'error', data: null, error: PLATFORM_FALLBACK }
}

function toRagState(cause: unknown): RagStatsState {
  if (cause instanceof ApiError) {
    return {
      status: isTimeoutError(cause.normalized) ? 'timeout' : 'error',
      searches: null,
      imports: null,
      corpus: null,
      error: cause.normalized.message,
    }
  }
  return { status: 'error', searches: null, imports: null, corpus: null, error: RAG_FALLBACK }
}

export interface UseReportsResult {
  platform: PlatformStatsState
  rag: RagStatsState
  /** 重新拉取两侧数据 */
  reload: () => void
}

/**
 * 加载报表数据。
 *
 * @param tenantId  RAG 侧租户标识（来自会话）；为空时**只**加载平台侧，
 *                  并在 RAG 区块如实说明「需要租户上下文」。
 * @param days      统计天数（后端钳制在 1～90）
 */
export function useReports(tenantId: string, days: number): UseReportsResult {
  const [platform, setPlatform] = useState<PlatformStatsState>(IDLE_PLATFORM)
  const [rag, setRag] = useState<RagStatsState>(IDLE_RAG)

  /** 触发计：每次刷新自增，effect 据此重新发请求 */
  const [nonce, setNonce] = useState(0)

  const tenant = tenantId.trim()

  useEffect(() => {
    let cancelled = false
    setPlatform({ status: 'loading', data: null, error: null })

    const run = async (): Promise<void> => {
      try {
        const data = await getDailyStatistics(days)
        if (cancelled) return
        setPlatform({ status: 'success', data, error: null })
      } catch (cause) {
        if (cancelled) return
        setPlatform(toPlatformState(cause))
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [days, nonce])

  useEffect(() => {
    // ⚠️ 无租户不发 RAG 请求：缺失 X-Tenant-Id 时后端返回 400，
    //    而「没有租户上下文」是本地状态问题，不该伪装成后端故障。
    if (tenant === '') {
      setRag(IDLE_RAG)
      return
    }
    let cancelled = false
    setRag({ status: 'loading', searches: null, imports: null, corpus: null, error: null })

    const run = async (): Promise<void> => {
      try {
        // ⚠️ 三个请求并发发起、整体成态：
        //    「语料增长与准入率」需要 corpus 快照（准入率的分母），
        //    单独拉会多一次往返且可能与日报口径不一致。
        const [searches, imports, corpus] = await Promise.all([
          getRagDailySearches(tenant, days),
          getRagDailyImports(tenant, days),
          getRagStatistics(tenant),
        ])
        if (cancelled) return
        setRag({ status: 'success', searches, imports, corpus, error: null })
      } catch (cause) {
        if (cancelled) return
        setRag(toRagState(cause))
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [tenant, days, nonce])

  const reload = useCallback(() => {
    setNonce((n) => n + 1)
  }, [])

  return { platform, rag, reload }
}
