/**
 * 进度追踪页（FE-D1 F3；FE-01 P4）
 *
 * ⚠️ 两个实测事实决定了本页形态（FE-D1 §7.5）：
 *   ① SSE 是短连接轮询，不是长连接推送 —— 延迟 = 轮询间隔
 *   ② run_event 表**只有 RUN_CREATED 由生产代码写入**，Worker 不写
 *      → 长期拿不到执行中事件
 * 故本页必须能优雅降级为「正在处理」占位。
 *
 * ⛔ 绝不编造进度百分比（FE-01 §4.3 S1）：无依据的进度条 = 假信息。
 * ⛔ 「未开放」须与「暂无数据」区分（S4）。
 */

import { useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { createRunEventPoller, type StreamSnapshot } from '@/features/run/eventStream'
import { stateLabel, stateTone, stateHint, isTerminalState } from '@/features/run/runState'
import { useSession } from '@/auth/session'
import { NotAvailable } from '@/shared/ErrorView'

function formatTime(iso: string): string {
  if (iso.length === 0) return ''
  const parsed = new Date(iso)
  if (Number.isNaN(parsed.getTime())) return iso
  return parsed.toLocaleString('zh-CN')
}

export function RunProgressPage(): JSX.Element {
  const { runId } = useParams<{ runId: string }>()
  const { onUnauthorized } = useSession()

  const [snapshot, setSnapshot] = useState<StreamSnapshot | null>(null)

  useEffect(() => {
    if (runId === undefined) return
    const poller = createRunEventPoller(runId, {
      onSnapshot: setSnapshot,
      onUnauthorized,
    })
    // ⛔ 必须清理，否则定时器泄漏
    return () => poller.stop()
  }, [runId, onUnauthorized])

  /** 从事件中取最新状态 */
  const currentState = useMemo(() => {
    if (snapshot === null) return null
    for (let i = snapshot.events.length - 1; i >= 0; i -= 1) {
      const event = snapshot.events[i]
      if (event !== undefined && event.payload.state !== undefined) {
        return event.payload.state
      }
    }
    return null
  }, [snapshot])

  const errorCode = useMemo(() => {
    if (snapshot === null) return undefined
    for (let i = snapshot.events.length - 1; i >= 0; i -= 1) {
      const event = snapshot.events[i]
      if (event !== undefined && event.payload.errorCode !== undefined) {
        return event.payload.errorCode
      }
    }
    return undefined
  }, [snapshot])

  /** 是否仍在同步：未读到终态且流未结束 */
  const shouldShowSyncing =
    snapshot !== null &&
    !snapshot.done &&
    (currentState === null || !isTerminalState(currentState))

  if (runId === undefined) {
    return <NotAvailable feature="进度追踪" detail="缺少运行标识" />
  }

  const tone = stateTone(currentState ?? '')
  const hint = stateHint(currentState ?? '', errorCode)

  return (
    <main className="run-progress">
      <h1>分析进度</h1>
      <p className="muted">运行标识：<code>{runId}</code></p>

      {/* 状态区 */}
      <section className={`status status--${tone}`} aria-live="polite">
        <p className="status__label">
          {currentState !== null
            ? stateLabel(currentState)
            : snapshot?.done === true
              ? '已结束'
              : '正在处理'}
        </p>
        {hint !== null ? <p className="status__hint">{hint}</p> : null}
      </section>

      {/* 降级占位：请求成功但无事件 —— 如实告知，不假装有进度 */}
      {snapshot !== null && snapshot.degraded && !snapshot.done ? (
        <p className="notice" role="status">
          正在处理，请稍候。详细执行步骤将在功能完善后展示。
        </p>
      ) : null}

      {snapshot !== null && snapshot.error !== null ? (
        <p role="alert" className="error">
          {snapshot.error}
        </p>
      ) : null}

      {/* 时间线 */}
      {snapshot !== null && snapshot.events.length > 0 ? (
        <section className="timeline">
          <h2>执行事件</h2>
          <ol>
            {snapshot.events.map((event) => (
              <li key={event.sequenceNo}>
                <span className="timeline__seq">#{event.sequenceNo}</span>
                <span className="timeline__type">{event.eventType}</span>
                {event.payload.stepType !== undefined ? (
                  <span className="timeline__step">{event.payload.stepType}</span>
                ) : null}
                {event.payload.toolName !== undefined ? (
                  <span className="timeline__tool">{event.payload.toolName}</span>
                ) : null}
                {event.payload.operationState !== undefined ? (
                  <span className="timeline__op">
                    {event.payload.operationState}
                  </span>
                ) : null}
                <time className="timeline__time">{formatTime(event.at)}</time>
              </li>
            ))}
          </ol>
        </section>
      ) : null}

      {/*
        ⛔ 结果区刻意不实现：
        后端无 result:read 端点（G5），且结果页须预留
        UNKNOWN/待核对分区（P0-D00 §3.4 明令不得隐藏），
        接口就绪后再按 FE-01 §P5 实现。
      */}
      <NotAvailable
        feature="归因结果"
        detail="结果查看功能尚未开放，请稍后再来"
      />

      {/* 重连指示：诚实告知数据更新状态 */}
      {shouldShowSyncing ? (
        <p className="muted">正在同步最新状态…</p>
      ) : null}
    </main>
  )
}
