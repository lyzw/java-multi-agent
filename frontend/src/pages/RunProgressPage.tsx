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
import { Link, useParams, useLocation } from 'react-router-dom'
import { createRunEventPoller, type StreamSnapshot } from '@/features/run/eventStream'
import { useRunStatus } from '@/features/run/useRunStatus'
import { stateLabel, stateTone, stateHint, isTerminalState } from '@/features/run/runState'
import { errorGuidance, runFailureGuidance } from '@/api/errorGuidance'
import { readRetryState, makeRetryState } from '@/features/run/retryForm'
import { useSession } from '@/auth/session'
import { Unavailable, Breadcrumbs } from '@/shared/states'
import { PageHead } from '@/components/ui'
import { Icon } from '@/components/Icon'

function formatTime(iso: string): string {
  if (iso.length === 0) return ''
  const parsed = new Date(iso)
  if (Number.isNaN(parsed.getTime())) return iso
  return parsed.toLocaleString('zh-CN')
}

export function RunProgressPage(): JSX.Element {
  const { runId } = useParams<{ runId: string }>()
  const { onUnauthorized } = useSession()
  // ⚠️ N4：从发起页带过来的原题（location.state，不落存储）。
  const location = useLocation()
  const retry = useMemo(() => readRetryState(location.state), [location.state])

  const [snapshot, setSnapshot] = useState<StreamSnapshot | null>(null)

  // ⚠️ FE-D9：状态权威来自 G3（run.state 快照），不是事件。
  //    与 SSE 共用限流桶 EVENT_QUERY（30 次/60 秒）→ 状态轮询间隔 10 秒。
  const {
    status,
    error: statusError,
    errorInfo: statusErrorInfo,
    notFound: statusNotFound,
    done: statusDone,
    reload: reloadStatus,
    loading: statusLoading,
  } = useRunStatus(runId)

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
      // ⚠️ 确保 poller 存在且已初始化（createRunEventPoller 内部立即启动）
      if (pollerRef.current) {
        pollerRef.current.stop()
        pollerRef.current = null
      }
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

  // ⚠️ N6：状态读取失败同样按 status 分流；403/404/401 是终态，不给刷新/重试。
  //    ⛔ 必须早于 shouldShowSyncing 与停轮询 effect 计算——
  //    否则权限错误时页面会同时说「正在处理 / 正在同步最新状态…」
  //    和「重试不会改变结果」，二者自相矛盾（铁律 15，F8 只修了 404）。
  const statusGuidance = useMemo(
    () => errorGuidance(statusErrorInfo),
    [statusErrorInfo],
  )
  const statusReadIsTerminal =
    statusErrorInfo !== null && !statusNotFound && !statusGuidance.retryable

  // ⚠️ 到达终态即停一切轮询：SSE 流在本项目**永远收不到终态事件**
  //    （run_event 只有 RUN_CREATED），故若只靠 SSE，本页会一直打后端。
  useEffect(() => {
    // ⚠️ 状态读取的终态错误也要停 SSE 轮询：403/401 不会随时间自愈。
    if ((statusDone || statusReadIsTerminal) && pollerRef.current) {
      pollerRef.current.stop()
      pollerRef.current = null
    }
  }, [statusDone, statusReadIsTerminal])

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
   *
 * ⚠️ F7（E2E 2026-11-14）：404（不存在 / 无权查看）**不是「仍在同步」**——
 *    那里什么都不会同步到；继续显示「正在同步最新状态…」是**误导**。
 * ⚠️ N6（二轮优化）：403/401 等**语义终态错误**同理。此前只排除 404，
 *    权限拒绝时页面会一边说「正在处理」一边说「重试不会改变结果」。
 */
  const shouldShowSyncing =
    !statusNotFound &&
    !statusDone &&
    !statusReadIsTerminal &&
    (currentState === null || !isTerminalState(currentState))

  if (runId === undefined) {
    return (
      <>
        <Breadcrumbs items={[{ label: '检索记录', to: '/runs' }, { label: '分析进度' }]} />
        <PageHead title="分析进度" />
        {/*
          ⚠️ 这里曾是 NotAvailable（shared/ErrorView.tsx）。
          FE-D2 §2.1 已决策「DataUnavailable 取代散落的 NotAvailable」，
          NotAvailable 不带 blockedBy / eta，进度页改用 Unavailable 后可追溯缺口编号。
        */}
        <Unavailable
          feature="进度追踪"
          blockedBy="G3"
          reason="缺少运行标识，无法定位该次辅导"
        />
      </>
    )
  }

  const tone = stateTone(currentState ?? '')
  const hint = stateHint(currentState ?? '', errorCode)
  // ⚠️ N3：内部 errorCode 绝不原样甩给学生；按类别给可读文案与差异化 CTA。
  //    service-config（CONFIG_UNAVAILABLE / BUDGET_EXHAUSTED …）= 租户级故障，
  //    「重新发起」是必然失败的死循环，必须移除。
  const failure = useMemo(
    () => runFailureGuidance(errorCode, runId),
    [errorCode, runId],
  )
  // ⚠️ statusGuidance / statusReadIsTerminal 已上移至 currentState 之后，
  //    供 shouldShowSyncing 与停轮询 effect 共用。

  return (
    <>
      <Breadcrumbs items={[{ label: '检索记录', to: '/runs' }, { label: '分析进度' }]} />
      <PageHead
        title="分析进度"
        lead={
          <>
            运行标识：<code>{runId}</code>
          </>
        }
      />

      {/* 状态区 */}
      <section className={`status status--${tone}`} aria-live="polite">
        <p className="status__label">
          {statusNotFound
            ? '资源不存在'
            : statusReadIsTerminal
              ? '无法查看状态'
              : currentState !== null
                ? stateLabel(currentState)
                : snapshot?.done === true
                  ? '已结束'
                  : '正在处理'}
        </p>
        {hint !== null && !statusNotFound ? <p className="status__hint">{hint}</p> : null}
        {statusNotFound ? (
          <p className="status__hint">
            该分析不存在，或不属于当前账号，因此无法查看进度与结果。
          </p>
        ) : null}
        {/*
          ⚠️ 2026-10-24：手动刷新按钮。
             轮询是 10 秒一拍（限流配额约束），用户等不及时应当能主动催一次；
             ⛔ 但只挂在状态区、且刷新中禁用 —— 自动轮询 + 手动连点
             会把 EVENT_QUERY 桶（30 次/60 秒，多运维共享）瞬间烧穿。
        */}
        <p className="status-line" style={{ marginTop: 'var(--s-3)' }}>
          <button
            type="button"
            className="btn btn--quiet"
            onClick={reloadStatus}
            // ⚠️ 铁律 15：终态错误（404 / 403）不得给「刷新状态」——
            //    刷新不会让资源出现，也不会让权限凭空生效。
            disabled={statusLoading || statusNotFound || statusReadIsTerminal}
            aria-busy={statusLoading}
          >
            <Icon name="refresh" />
            {statusLoading ? '刷新中…' : '刷新状态'}
          </button>
          {statusError !== null && !statusNotFound && !statusReadIsTerminal ? (
            <span className="muted">上一次读取失败，可再试一次</span>
          ) : null}
          {statusReadIsTerminal ? (
            <span className="muted">{statusGuidance.next}</span>
          ) : null}
        </p>
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
      {snapshot !== null && snapshot.degraded && !snapshot.done && !statusDone && !statusNotFound && !statusReadIsTerminal ? (
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

        ⚠️ F7（E2E 2026-11-14）：**404 不属于「暂时读不到」**。
        404 = 资源不存在 / 不属于当前主体，重试也不会变；
        此时说「暂时读不到最新状态」并让用户继续等，是**误导**。
      */}
      {statusError !== null && !statusNotFound && !statusReadIsTerminal ? (
        <p className="notice" role="status">
          暂时读不到最新状态，下面显示的是事件流里的进度。
        </p>
      ) : null}
      {/* ⚠️ 403 等终态权限错误：不得说成「暂时读不到」，也不给任何重试暗示 */}
      {statusReadIsTerminal ? (
        <p className="marked" role="alert">
          {statusGuidance.reason}。{statusGuidance.next}
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
          <Link to={`/runs/${encodeURIComponent(runId)}/result`} className="btn btn--lg">
            <Icon name="arrow" />
            查看归因结果
          </Link>
        </p>
      ) : null}

      {/*
        ⚠️ 2026-10-24：失败 / 超时 / 取消终态的下一步。
            此前只有成功态有 CTA，失败态是**死路**——
            用户看完「失败」两个学，页面上没有任何可做的事。
            错题辅导的重试成本极低（同一道题重新提交即可），
            把入口摆在状态旁边，比让人回工作台再点一遍「发起辅导」少三步。
      */}
      {/*
        ⚠️ N3（E2E 二次评估）：原实现对**所有**非成功终态一律给「重新发起这道题」。
           CONFIG_UNAVAILABLE / BUDGET_EXHAUSTED 的根因是租户级配置与预算账户，
           学生重试多少次都只会再失败一次 —— 那是引导用户进死循环。
        ⚠️ N4：临时故障允许重试时，把原题通过 location.state 带回；
           刷新进度页后 state 丢失，文案改为「再辅导一道」，不再名实不符。
      */}
      {statusDone && status != null && status.state !== 'SUCCEEDED' ? (
        <>
          <p
            className="marked marked--calm"
            role="status"
            style={{ marginTop: 'var(--s-4)' }}
          >
            {failure.advice}
          </p>
          <div className="status-line" style={{ marginTop: 'var(--s-3)', flexWrap: 'wrap', gap: 'var(--s-3)' }}>
            {failure.canRetry ? (
              <Link
                to="/runs/new"
                state={
                  retry.retryForm !== undefined
                    ? makeRetryState(retry.retryForm, retry.agentId ?? '')
                    : undefined
                }
                className="btn btn--quiet"
              >
                <Icon name="plus" />
                {retry.retryForm !== undefined ? '重新发起这道题' : '再辅导一道'}
              </Link>
            ) : (
              <Link to="/" className="btn btn--quiet">
                <Icon name="dashboard" />
                回工作台
              </Link>
            )}
            <Link to="/runs" className="btn btn--quiet">
              <Icon name="search" />
              回检索记录
            </Link>
          </div>
        </>
      ) : null}

      {/* 重连指示：诚实告知数据更新状态 */}
      {shouldShowSyncing ? (
        <p className="muted">正在同步最新状态…</p>
      ) : null}
    </>
  )
}
