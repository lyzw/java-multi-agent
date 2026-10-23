/**
 * RAG 检索 Hook（RAG 前端模块）
 *
 * 单一数据源：检索走 `POST /api/v1/retrieval`（已接入），
 * 生成走 `POST /api/v1/retrieval/generate`（后端尚未实现，按「未接入」呈现）。
 *
 * ⚠️ 状态机纪律（沿用 FE-D3 五态）：检索与生成**分开**建模，
 *    因为二者的「成功 / 空 / 错误 / 超时 / 未接入」语义不同，混在一起会误判：
 *    · 检索成功但 0 命中 ≠ 故障（empty）
 *    · 生成端点不存在 ≠ 故障（notConnected）
 *    · 后端没在时限内返回 ≠ 网络断了（timeout）
 *
 * ⚠️ 防竞态：effect 内 `cancelled` 守卫，避免组件卸载
 *    （含 StrictMode 双挂载）后 setState。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { retrieveChunks, generateRagAnswer, ApiError } from '@/api/client'
import type { RagRetrievalRequest, RagRetrievalHit, RagRetrievalResponse } from '@/api/types'
import { isTimeoutError, isNotImplemented } from '@/api/errors'

/** 检索状态。 */
export type RetrievalStatus =
  | 'idle'
  | 'loading'
  | 'success'
  | 'empty'
  | 'error'
  | 'timeout'

export interface RetrievalState {
  status: RetrievalStatus
  hits: RagRetrievalHit[]
  response: RagRetrievalResponse | null
  error: string | null
}

/** 生成状态（多一个 notConnected：端点未实现，不是故障）。 */
export type GenerationStatus =
  | 'idle'
  | 'loading'
  | 'success'
  | 'notConnected'
  | 'error'
  | 'timeout'

export interface GenerationState {
  status: GenerationStatus
  answer: string | null
  citations: RagRetrievalHit[]
  error: string | null
}

const IDLE_RETRIEVAL: RetrievalState = {
  status: 'idle',
  hits: [],
  response: null,
  error: null,
}

const IDLE_GENERATION: GenerationState = {
  status: 'idle',
  answer: null,
  citations: [],
  error: null,
}

/** 检索失败兜底文案（不给用户凭空猜测的具体原因） */
const RETRIEVAL_FALLBACK = '检索失败，请稍后重试'
/** 生成失败兜底文案 */
const GENERATION_FALLBACK = '生成回答失败，请稍后重试'

function toRetrievalState(cause: unknown): RetrievalState {
  if (cause instanceof ApiError) {
    if (isTimeoutError(cause.normalized)) {
      return { status: 'timeout', hits: [], response: null, error: cause.normalized.message }
    }
    return { status: 'error', hits: [], response: null, error: cause.normalized.message }
  }
  return { status: 'error', hits: [], response: null, error: RETRIEVAL_FALLBACK }
}

function toGenerationState(cause: unknown): GenerationState {
  if (cause instanceof ApiError) {
    if (isTimeoutError(cause.normalized)) {
      return { status: 'timeout', answer: null, citations: [], error: cause.normalized.message }
    }
    // ⚠️ 404/501 = 端点未实现 → 「未接入」，不是故障。
    if (isNotImplemented(cause.normalized)) {
      return { status: 'notConnected', answer: null, citations: [], error: null }
    }
    return { status: 'error', answer: null, citations: [], error: cause.normalized.message }
  }
  return { status: 'error', answer: null, citations: [], error: GENERATION_FALLBACK }
}

export interface UseRagSearchResult {
  retrieval: RetrievalState
  generation: GenerationState
  /**
   * 提交一次检索 + 生成请求。
   *
   * @param payload  检索参数
   * @param tenantId 租户标识（UUID）：后端据此重建 RLS 租户上下文，
   *                 缺失则后端返回 400。**不可省略**。
   */
  search: (payload: RagRetrievalRequest, tenantId: string) => void
  /** 用上一次的参数重跑（检索 + 生成） */
  retry: () => void
  /** 回到初始态（清空结果） */
  reset: () => void
}

export function useRagSearch(): UseRagSearchResult {
  const [retrieval, setRetrieval] = useState<RetrievalState>(IDLE_RETRIEVAL)
  const [generation, setGeneration] = useState<GenerationState>(IDLE_GENERATION)
  /** 触发计：每次 search 自增，effect 据此重新发请求 */
  const [nonce, setNonce] = useState(0)
  /** 最近一次请求参数：retry 复用，避免把表单状态塞进 effect 依赖 */
  const lastPayload = useRef<RagRetrievalRequest | null>(null)
  /** 最近一次租户标识：retry 复用（租户是 RLS 的作用域，不能丢） */
  const lastTenantId = useRef<string>('')

  useEffect(() => {
    // ⚠️ nonce 为 0 是初始态，不在挂载时自动发请求（避免空 query 调用）。
    if (nonce === 0) return
    const payload = lastPayload.current
    if (payload === null) return

    let cancelled = false
    setRetrieval({ status: 'loading', hits: [], response: null, error: null })
    setGeneration(IDLE_GENERATION)

    const runGeneration = async (hits: RagRetrievalHit[]): Promise<void> => {
      setGeneration({ status: 'loading', answer: null, citations: [], error: null })
      try {
        // ⚠️ B2 修复：生成端点与检索共用 RLS 租户收口，必须透传 X-Tenant-Id，
        //    否则后端 400。租户取自最近一次检索请求（lastTenantId）。
        const result = await generateRagAnswer(
          {
            query: payload.query,
            mode: payload.mode,
            edition: payload.edition,
            hitIds: hits.map((h) => h.chunkId),
          },
          undefined,
          lastTenantId.current,
        )
        if (cancelled) return
        setGeneration({
          status: 'success',
          answer: result.answer,
          citations: result.citations ?? [],
          error: null,
        })
      } catch (cause) {
        if (cancelled) return
        setGeneration(toGenerationState(cause))
      }
    }

    const runRetrieval = async (): Promise<void> => {
      try {
        const resp = await retrieveChunks(payload, undefined, lastTenantId.current)
        if (cancelled) return
        const hits = resp.hits ?? []
        // ⚠️ 0 命中是合法结果（分数门禁过滤后可能为空），不是故障。
        setRetrieval({
          status: hits.length === 0 ? 'empty' : 'success',
          hits,
          response: resp,
          error: null,
        })
        // 检索成功才发起生成：生成依赖检索召回作为 Grounding 依据。
        await runGeneration(hits)
      } catch (cause) {
        if (cancelled) return
        setRetrieval(toRetrievalState(cause))
      }
    }

    void runRetrieval()

    return () => {
      cancelled = true
    }
  }, [nonce])

  const search = useCallback((payload: RagRetrievalRequest, tenantId: string) => {
    lastPayload.current = payload
    lastTenantId.current = tenantId
    setNonce((n) => n + 1)
  }, [])

  const retry = useCallback(() => {
    if (lastPayload.current !== null) setNonce((n) => n + 1)
  }, [])

  const reset = useCallback(() => {
    lastPayload.current = null
    setNonce(0)
    setRetrieval(IDLE_RETRIEVAL)
    setGeneration(IDLE_GENERATION)
  }, [])

  return { retrieval, generation, search, retry, reset }
}
