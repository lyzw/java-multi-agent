/**
 * 进度追踪页（FE-D1 F3；FE-01 P4；**FE-D9 接 G3**）
 *
 * ⚠️ 两个实测事实决定了本页形态（FE-D1 §7.5）：
 *   ① SSE 是短连接轮询，不是长连接推送 —— 延迟 = 轮询间隔
 *   ② run_event 表**只有 RUN_CREATED 由生产代码写入**，Worker 不写
 *      → 长期拿不到执行中事件
 * 故本页必须能优雅降级为「正在处理」占位。
 *
 * ⚠️⚠️ **FE-D9：状态不再只靠事件**（G3 已由 P4-D09 落地）：
 *   · **状态权威 = `GET /api/runs/{runId}`**（`run.state` 的数据库快照）
 *   · **事件流 = 时间线补充**（非状态权威：SSE 是轮询，可能落后）
 *   · 两者冲突时**以 G3 为准**（FE-D9 §2.2）
 *   · **到达终态即停一切轮询**——否则本页会每 3 秒打一次后端直到用户离开
 *     （SSE 永远收不到终态事件，因为 Worker 不写 `run_event`）。
 *
 * ⛔ 绝不编造进度百分比（FE-01 §4.3 S1）：无依据的进度条 = 假信息。
 * ⛔ 「未开放」须与「暂无数据」区分（S4）。
 */

import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { createRunEventPoller, type StreamSnapshot } from '@/features/run/eventStream'
import { useRunStatus } from '@/features/run/useRunStatus'
import { stateLabel, stateTone, stateHint, isTerminalState } from '@/features/run/runState'
import { useSession } from '@/auth/session'
import { Unavailable } from '@/shared/states'

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

  // ⚠️ FE-D9：状态权威来自 G3（run.state 快照），不是事件。
  //    与 SSE 共用限流桶 EVENT_QUERY（30 次/60 秒）→ 状态轮询间隔 10 秒。
  const { status, error: statusError, done: statusDone } = useRunStatus(runId)

  // ⛔ 必须清理，否则定时器泄漏。pollerRef 让我们能在**读到终态时主动停掉**它。
  const pollerRef = useRef<{ stop: () => void } | null>(null)

  useEffect(() => {
    if (runId === undefined) return
    const poller = createRunEventPoller(runId, {
      onSnapshot: setSnapshot,
      onUnauthorized,
    })
    pollerRef.current = poller
    // ⛔ 必须清理，否则定时器泄漏
    return () => {
      poller.stop()
      pollerRef.current = null
    }
  }, [runId, onUnauthorized])

  // ⚠️⚠️ FE-D9 §2.2：**状态以 G3 为准**。
  //    事件只是流水，可能落后于 run.state（SSE 是短连接轮询）。
  //    两者**保持为独立变量**，在渲染处显式选择——不混成一个字段再比较。
  const eventState = useMemo(() => {
    if (snapshot === null) return null
    for (let i = snapshot.events.length - 1; i >= 0; i -= 1) {
      const event = snapshot.events[i]
      if (event !== undefined && event.payload.state !== undefined) {
        return event.payload.state
      }
    }
    return null
  }, [snapshot])

  const currentState = status?.state ?? eventState

  // ⚠️ 到达终态即停一切轮询：SSE 流在本项目**永远收不到终态事件**
  //    （run_event 只有 RUN_CREATED），故若只靠 SSE，本页会一直打后端。
  useEffect(() => {
    if (statusDone) {
      pollerRef.current?.stop()
    }
  }, [statusDone])

  const errorCode = useMemo(() => {
    // ⚠️ FE-D9：G3 的 errorCode 是**权威**（run 表的 error_code 列）；
    //    事件里的 errorCode 只作补充。
    if (status?.errorCode != null) return status.errorCode
    if (snapshot === null) return undefined
    for (let i = snapshot.events.length - 1; i >= 0; i -= 1) {
      const event = snapshot.events[i]
      if (event !== undefined && event.payload.errorCode !== undefined) {
        return event.payload.errorCode
      }
    }
    return undefined
  }, [status, snapshot])

  /**
   * 是否仍在同步。
   *
   * ⚠️ FE-D9：以 **G3 状态**为准判定是否已结束——
   *    SSE 的 `snapshot.done` 只有收到终态事件才为真，而本项目
   *    `run_event` 只有 `RUN_CREATED` → 它**永远**不会为真。
   *    若以它为准，页面会永远显示「正在同步最新状态…」。
   */
  const shouldShowSyncing =
    !statusDone && (currentState === null || !isTerminalState(currentState))

  if (runId === undefined) {
    return (
      <>
        <div className="main__head">
          <h1>分析进度</h1>
        </div>
        {/*
          ⚠️ 这里曾是 NotAvailable（shared/ErrorView.tsx）。
          FE-D2 §2.1 已决策「DataUnavailable 取代散落的 NotAvailable」，
          理由：一致性本身就是信息 —— 两套未开放表达会让用户无法判断
          哪些功能能用、哪些在开发中，更无法得知由谁承接（F-18）。
          NotAvailable 不带 blockedBy / eta，进度页改用 Unavailable 后可追溯缺口编号。
        */}
        <Unavailable
          feature="进度追踪"
          blockedBy="G3"
          reason="缺少运行标识，无法定位该次辅导"
        />
        {/* ⚠️ 此处仍标注 G3：缺的是**运行标识**（前端入参），
            不是后端端点缺失（G3 已由 P4-D09 关闭）。 */}
      </>
    )
  }

  const tone = stateTone(currentState ?? '')
  const hint = stateHint(currentState ?? '', errorCode)

  return (
    <>
      {/*
        ⚠️ 这里曾是 <main className="run-progress"> ——
        AppShell 已渲染 <main className="main">，main 嵌套 main
        违反 HTML 规范（一个文档只有一个主内容区），
        屏幕阅读器会把它当作第二个主内容区。
        已按 FE-D3 §3.1 迁移为 main__head 结构（F-16）。
      */}
      <div className="main__head">
        <h1>分析进度</h1>
        <p className="main__lead">运行标识：<code>{runId}</code></p>
      </div>

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

      {/*
        降级占位：请求成功但无事件 —— 如实告知，不假装有进度。

        ⚠️⚠️ FE-D9：**终态时必须抑制**（真实缺陷修复）。
        原条件只有 `degraded && !snapshot.done`——而 `snapshot.done`
        永远为假（`run_event` 只有 RUN_CREATED，收不到终态事件），
        于是 Run **早已完成**时页面仍显示「正在处理，请稍候」——
        **对已完成的运行说「请稍候」是直接的误导**。
        故改为以 G3 状态（statusDone）为准。
      */}
      {snapshot !== null && snapshot.degraded && !snapshot.done && !statusDone ? (
        <p className="notice" role="status">
          正在处理，请稍候。详细执行步骤将在功能完善后展示。
        </p>
      ) : null}

      {snapshot !== null && snapshot.error !== null ? (
        <p role="alert" className="error">
          {snapshot.error}
        </p>
      ) : null}

      {/*
        ⚠️ FE-D9：状态接口失败 → **降级**为事件推导的状态，
        并**明确告知**「暂时读不到最新状态」——
        ⛔ 不得显示成「系统故障」（那会让人以为这次辅导失败了），
        ⛔ 也不得静默（那会让人以为状态是新鲜的）。
      */}
      {statusError !== null ? (
        <p className="notice" role="status">
          暂时读不到最新状态，下面显示的是事件流里的进度。
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
        ⚠️⚠️ FE-D9：**G5 已由 P4-D10 关闭**，本页此处的
        「后端尚未提供 result:read 端点」是**过时的谎言**——
        结果页（FE-D8）已接上真实数据。
        故改为**链接**到结果页，而不是继续显示占位。
      */}
      {statusDone && status?.state === 'SUCCEEDED' ? (
        <p style={{ marginTop: 'var(--s-5)' }}>
          <Link to={`/runs/${encodeURIComponent(runId)}/result`} className="btn">
            查看归因结果
          </Link>
        </p>
      ) : null}

      {/* 重连指示：诚实告知数据更新状态 */}
      {shouldShowSyncing ? (
        <p className="muted">正在同步最新状态…</p>
      ) : null}
    </>
  )
}
