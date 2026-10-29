/**
 * RAG 全局语料治理票据的界面态（RAG-D11 第四刀·第二刀 §2.7）。
 *
 * ⚠️ **凭据只在内存里**：本 hook 唯一的持有处是 `useState`，
 *    绝不写 sessionStorage / localStorage（后端签发的是一张能写全局桶的短时令牌，
 *    落盘等于把「改所有租户检索结果」的授权留在磁盘 / 其他标签页 / 浏览器扩展里）。
 *
 * ⚠️ **换作用域即作废**（方案 A7）：调用方在档位或租户变化时必须 `revoke()`。
 *    留着上一档的票，等于让「一次为全局语料领的授权」漂在页面上等着被复用；
 *    而 TTL 的上界（900 秒）本来就是为了让它**不能**变成常驻特权。
 *
 * ⚠️ **到期主动丢弃**：后端按 `exp` 判定，本地只能猜，所以留 15 秒余量
 *    （与 `@/api/ragTicket` 同一判据）。宁可让管理员重新点一次「领取票据」
 *    ——每次领取都走授权 + 限流 + 平台审计，多一次不是坏事，
 *    「按钮看着能用、点了吃 401」才是。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  clearRagGovernanceTicket,
  ensureRagGovernanceTicket,
  peekRagGovernanceTicket,
  ragGovernanceTicketRemainingSeconds,
} from '@/api/ragTicket'
import type { RagGovernanceTicket } from '@/api/ragTicket'
import { ApiError } from '@/api/client'

export type RagTicketStatus = 'idle' | 'issuing' | 'ready' | 'error'

export interface UseRagGovernanceTicketResult {
  /** 当前票据（null = 未领取或已作废）；⚠️ 含凭据，界面只准显示 jti 与剩余秒数 */
  ticket: RagGovernanceTicket | null
  status: RagTicketStatus
  /** 领取失败说明（授权不足 / 通道关闭 / 被限流），成功后清空 */
  message: string | null
  /** 剩余秒数（无票为 0） */
  remainingSeconds: number
  /** 领取一张仍然有效的票据（已领且未临近过期则复用，不重复签发） */
  obtain: () => void
  /** 作废当前票据（换档 / 换租户 / 治理完成） */
  revoke: () => void
}

/** 本地丢弃余量（毫秒），与 `@/api/ragTicket` 的判据同源同值。 */
const DROP_MARGIN_MS = 15_000

export function useRagGovernanceTicket(): UseRagGovernanceTicketResult {
  const [ticket, setTicket] = useState<RagGovernanceTicket | null>(peekRagGovernanceTicket)
  const [status, setStatus] = useState<RagTicketStatus>(
    peekRagGovernanceTicket() ? 'ready' : 'idle',
  )
  const [message, setMessage] = useState<string | null>(null)
  const [remainingSeconds, setRemainingSeconds] = useState(0)
  /** 卸载守卫：取票是异步的，页面可能在请求返回前就切走了档位。 */
  const alive = useRef(true)

  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  /** 每秒刷新剩余秒数；到点自动作废（不给「过期但看着还能用」留窗口）。 */
  useEffect(() => {
    if (ticket === null) {
      setRemainingSeconds(0)
      return
    }
    const tick = (): void => {
      const left = ragGovernanceTicketRemainingSeconds(ticket)
      if (Date.parse(ticket.expiresAt) - Date.now() <= DROP_MARGIN_MS) {
        clearRagGovernanceTicket()
        if (alive.current) {
          setTicket(null)
          setStatus('idle')
          setRemainingSeconds(0)
        }
        return
      }
      setRemainingSeconds(left)
    }
    tick()
    const timer = setInterval(tick, 1000)
    return () => clearInterval(timer)
  }, [ticket])

  const obtain = useCallback((): void => {
    setStatus('issuing')
    setMessage(null)
    const run = async (): Promise<void> => {
      try {
        const fresh = await ensureRagGovernanceTicket('PLATFORM')
        if (!alive.current) return
        setTicket(fresh)
        setStatus('ready')
        setMessage(null)
      } catch (cause) {
        if (!alive.current) return
        setTicket(null)
        setStatus('error')
        setMessage(
          cause instanceof ApiError
            ? cause.message
            : '领取治理票据失败，请稍后重试',
        )
      }
    }
    void run()
  }, [])

  const revoke = useCallback((): void => {
    clearRagGovernanceTicket()
    setTicket(null)
    setStatus('idle')
    setMessage(null)
    setRemainingSeconds(0)
  }, [])

  return { ticket, status, message, remainingSeconds, obtain, revoke }
}
