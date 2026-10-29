/**
 * RAG 语料数据管理 Hook（RAG-D10 管理面）。
 *
 * 单一数据源：`/api/v1/management/**`（rag-management 模块）。
 *
 * ⚠️ **五态纪律**：每个数据区块都要能区分
 *    默认 / 加载 / 空 / 错误 / 超时。混为一谈的后果是
 *    「审核员以为系统坏了」或「以为有语料却看不到」。
 *
 * ⚠️ **409 必须与其它错误分开**：409 = 非法状态转移（治理规则被违反），
 *    **不是**服务故障。典型场景：「已拒绝 → 直接通过」被禁（须先回到「审核中」）、
 *    「已撤回 → 任意」被禁（撤回是终态）。对 409 提示「刷新后重试」是**无效建议**
 *    ——刷新不会让非法转移变合法，只会诱导审核员反复重试一个注定被拒的操作。
 *    故单独建模为 `conflict` 态，文案指向「合法下一步」。
 *
 * ⚠️ **不做自动轮询**：管理面是人工操作，且与检索共用 `/api/v1` 通道。
 * ⚠️ 防竞态：effect 内 `cancelled` 守卫，避免组件卸载后 setState。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  listRagSources,
  getRagStatistics,
  applyRagVerification,
  registerRagSource,
  bulkRegisterRagSources,
  isRagTimeout,
  isRagTargetReady,
  ApiError,
} from '@/api/ragClient'
import type { RagTarget } from '@/api/ragClient'
import type {
  RagSource,
  RagStatistics,
  RagSourceQuery,
  RagRegisterSourceRequest,
  RagBulkSourceItem,
  RagBulkImportResponse,
  RagVerificationRequest,
  RagVerificationStatus,
} from '@/api/ragTypes'

/** 列表状态。 */
export type RagListStatus = 'idle' | 'loading' | 'success' | 'empty' | 'error' | 'timeout'

export interface RagSourceListState {
  status: RagListStatus
  sources: RagSource[]
  error: string | null
}

/** 统计状态。 */
export type RagStatsStatus = 'idle' | 'loading' | 'success' | 'error' | 'timeout'

export interface RagStatsState {
  status: RagStatsStatus
  stats: RagStatistics | null
  error: string | null
}

/**
 * 校验动作状态。
 *
 * ⚠️ `conflict` 单列：非法状态转移。
 */
export type RagActionStatus = 'idle' | 'submitting' | 'success' | 'conflict' | 'error' | 'timeout'

export interface RagActionState {
  status: RagActionStatus
  /** 冲突/错误的具体说明（面向用户的下一步提示） */
  message: string | null
  /** 最近一次成功流转后的来源（供列表就地更新） */
  updated: RagSource | null
}

const IDLE_LIST: RagSourceListState = { status: 'idle', sources: [], error: null }
const IDLE_STATS: RagStatsState = { status: 'idle', stats: null, error: null }
const IDLE_ACTION: RagActionState = { status: 'idle', message: null, updated: null }

const LIST_FALLBACK = '读取语料来源失败，请稍后重试'
const STATS_FALLBACK = '读取统计失败，请稍后重试'

function toListState(cause: unknown): RagSourceListState {
  if (cause instanceof ApiError) {
    if (isRagTimeout(cause)) {
      return { status: 'timeout', sources: [], error: cause.message }
    }
    return { status: 'error', sources: [], error: cause.message }
  }
  return { status: 'error', sources: [], error: LIST_FALLBACK }
}

function toStatsState(cause: unknown): RagStatsState {
  if (cause instanceof ApiError) {
    if (isRagTimeout(cause)) {
      return { status: 'timeout', stats: null, error: cause.message }
    }
    return { status: 'error', stats: null, error: cause.message }
  }
  return { status: 'error', stats: null, error: STATS_FALLBACK }
}

/**
 * 409 冲突文案。
 *
 * ⚠️ 刻意**不给**「刷新后重试」——刷新不会让非法转移变合法。
 */
const CONFLICT_MESSAGE =
  '这一步不被允许：校验状态只能按既定流程流转（撤回是终态，不能复活；已拒绝的来源须先重新认领才能再判定）。请按提示选择合法的下一步。'

function toActionState(cause: unknown): RagActionState {
  if (cause instanceof ApiError) {
    if (isRagTimeout(cause)) {
      return { status: 'timeout', message: cause.message, updated: null }
    }
    // ⚠️ 409 = 非法状态转移（治理规则被违反），单独成态。
    if (cause.status === 409) {
      return { status: 'conflict', message: CONFLICT_MESSAGE, updated: null }
    }
    return { status: 'error', message: cause.message, updated: null }
  }
  return { status: 'error', message: '操作失败，请稍后重试', updated: null }
}

export interface UseRagManagementResult {
  sources: RagSourceListState
  stats: RagStatsState
  action: RagActionState
  /** 批量导入状态（独立的五态，避免与单条审核动作互相干扰） */
  bulk: RagBulkState
  /** 加载来源列表（可带筛选条件） */
  loadSources: (target: RagTarget, query?: RagSourceQuery) => void
  /** 加载统计 */
  loadStats: (target: RagTarget) => void
  /** 执行一次校验状态流转 */
  verify: (target: RagTarget, sourceId: string, payload: RagVerificationRequest) => void
  /** 登记新的语料来源 */
  register: (target: RagTarget, payload: RagRegisterSourceRequest) => void
  /** 批量登记语料来源（数据导入） */
  bulkRegister: (target: RagTarget, registeredBy: string, items: RagBulkSourceItem[]) => void
  /** 重跑最近一次列表查询 */
  retrySources: () => void
  /** 重跑最近一次统计查询 */
  retryStats: () => void
  /** 清空动作提示（关闭后调用） */
  resetAction: () => void
  /** 清空批量导入结果 */
  resetBulk: () => void
  /** 清空全部作用域状态（切换租户或作用域档位时调用） */
  resetAll: () => void
}

/** 批量导入状态。 */
export type RagBulkStatus = 'idle' | 'submitting' | 'success' | 'error' | 'timeout'

export interface RagBulkState {
  status: RagBulkStatus
  /** 最近一次导入的逐条结果（供列表就地展示） */
  response: RagBulkImportResponse | null
  /** 错误/超时说明 */
  error: string | null
}

const IDLE_BULK: RagBulkState = { status: 'idle', response: null, error: null }

export function useRagManagement(): UseRagManagementResult {
  const [sources, setSources] = useState<RagSourceListState>(IDLE_LIST)
  const [stats, setStats] = useState<RagStatsState>(IDLE_STATS)
  const [action, setAction] = useState<RagActionState>(IDLE_ACTION)
  const [bulk, setBulk] = useState<RagBulkState>(IDLE_BULK)

  /** 触发计：每次加载自增，effect 据此重新发请求 */
  const [listNonce, setListNonce] = useState(0)
  const [statsNonce, setStatsNonce] = useState(0)
  /** 最近一次查询参数：retry 复用，避免把表单状态塞进 effect 依赖 */
  const lastQuery = useRef<RagSourceQuery>({})
  const lastTarget = useRef<RagTarget>({ scope: 'TENANT', tenantId: '' })

  useEffect(() => {
    if (listNonce === 0) return
    const target = lastTarget.current
    // ⚠️ 租户档没选租户就不发请求：后端会返回 400，
    //    「没填租户」是本地配置问题，不该伪装成后端故障。
    //    平台全局档不需要租户，因此始终可发。
    if (!isRagTargetReady(target)) return

    let cancelled = false
    setSources({ status: 'loading', sources: [], error: null })

    const run = async (): Promise<void> => {
      try {
        const list = await listRagSources(target, lastQuery.current)
        if (cancelled) return
        setSources({
          status: list.length === 0 ? 'empty' : 'success',
          sources: list,
          error: null,
        })
      } catch (cause) {
        if (cancelled) return
        setSources(toListState(cause))
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [listNonce])

  useEffect(() => {
    if (statsNonce === 0) return
    const target = lastTarget.current
    if (!isRagTargetReady(target)) return

    let cancelled = false
    setStats({ status: 'loading', stats: null, error: null })

    const run = async (): Promise<void> => {
      try {
        const data = await getRagStatistics(target)
        if (cancelled) return
        setStats({ status: 'success', stats: data, error: null })
      } catch (cause) {
        if (cancelled) return
        setStats(toStatsState(cause))
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [statsNonce])

  const loadSources = useCallback((target: RagTarget, query: RagSourceQuery = {}) => {
    lastTarget.current = target
    lastQuery.current = query
    setListNonce((n) => n + 1)
  }, [])

  const loadStats = useCallback((target: RagTarget) => {
    lastTarget.current = target
    setStatsNonce((n) => n + 1)
  }, [])

  const verify = useCallback(
    (target: RagTarget, sourceId: string, payload: RagVerificationRequest) => {
      setAction({ status: 'submitting', message: null, updated: null })
      const run = async (): Promise<void> => {
        try {
          const updated = await applyRagVerification(target, sourceId, payload)
          setAction({ status: 'success', message: null, updated })
        } catch (cause) {
          setAction(toActionState(cause))
        }
      }
      void run()
    },
    [],
  )

  const register = useCallback((target: RagTarget, payload: RagRegisterSourceRequest) => {
    setAction({ status: 'submitting', message: null, updated: null })
    const run = async (): Promise<void> => {
      try {
        const created = await registerRagSource(target, payload)
        setAction({ status: 'success', message: null, updated: created })
      } catch (cause) {
        setAction(toActionState(cause))
      }
    }
    void run()
  }, [])

  const bulkRegister = useCallback(
    (target: RagTarget, registeredBy: string, items: RagBulkSourceItem[]) => {
      setBulk({ status: 'submitting', response: null, error: null })
      const run = async (): Promise<void> => {
        try {
          const response = await bulkRegisterRagSources(target, registeredBy, items)
          setBulk({ status: 'success', response, error: null })
        } catch (cause) {
          if (cause instanceof ApiError && isRagTimeout(cause)) {
            setBulk({ status: 'timeout', response: null, error: cause.message })
          } else if (cause instanceof ApiError) {
            setBulk({ status: 'error', response: null, error: cause.message })
          } else {
            setBulk({ status: 'error', response: null, error: '导入失败，请稍后重试' })
          }
        }
      }
      void run()
    },
    [],
  )

  const retrySources = useCallback(() => {
    setListNonce((n) => n + 1)
  }, [])

  const retryStats = useCallback(() => {
    setStatsNonce((n) => n + 1)
  }, [])

  const resetAction = useCallback(() => {
    setAction(IDLE_ACTION)
  }, [])

  const resetBulk = useCallback(() => {
    setBulk(IDLE_BULK)
  }, [])

  const resetAll = useCallback(() => {
    setSources(IDLE_LIST)
    setStats(IDLE_STATS)
    setAction(IDLE_ACTION)
    setBulk(IDLE_BULK)
    lastQuery.current = {}
  }, [])

  return {
    sources,
    stats,
    action,
    bulk,
    loadSources,
    loadStats,
    verify,
    register,
    bulkRegister,
    retrySources,
    retryStats,
    resetAction,
    resetBulk,
    resetAll,
  }
}

/**
 * 某个来源当前**允许**的校验目标状态。
 *
 * ⚠️ 与后端 `VerificationTransition` 白名单**逐条对齐**（RAG-D07 §2.4 T-1～T-12）。
 *    前端据此**只渲染合法按钮**——让用户点一个注定被 409 的操作是产品缺陷。
 *
 * @param current 当前状态
 * @return 允许的目标状态列表
 */
export function allowedVerificationTargets(
  current: RagVerificationStatus,
): RagVerificationStatus[] {
  switch (current) {
    case 'PENDING':
      return ['IN_REVIEW', 'REVOKED']
    case 'IN_REVIEW':
      return ['VERIFIED', 'REJECTED', 'REVOKED']
    case 'VERIFIED':
      return ['IN_REVIEW', 'REVOKED']
    case 'REJECTED':
      return ['IN_REVIEW', 'REVOKED']
    case 'REVOKED':
      // ⚠️ 终态：无合法后继（T-10）。不渲染任何流转按钮。
      return []
    default:
      return []
  }
}
