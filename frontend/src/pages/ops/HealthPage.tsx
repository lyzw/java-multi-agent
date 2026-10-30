/**
 * 系统健康 P13（FE-D3）
 *
 * ✅ **本阶段唯一可接真实数据的页面**——后端有 /actuator/health。
 *
 * ⚠️ 实测该端点返回 500 + 业务错误体。
 *    故此处**不只看 HTTP 状态码**：那会把「200 但内容是错误」误判为健康。
 */

import { useCallback, useEffect, useState } from 'react'
import { Marked, Unavailable, Loading } from '@/shared/states'
import { PageHead, Panel, StatusPill } from '@/components/ui'
import { Icon } from '@/components/Icon'

interface HealthState {
  status: 'UP' | 'DOWN'
  httpStatus: number
  checkedAt: string
  detail?: string
  jwksStatus?: 'UP' | 'DOWN'
}

export function HealthPage(): JSX.Element {
  const [health, setHealth] = useState<HealthState | null>(null)
  const [checking, setChecking] = useState(true)

  const check = useCallback(async (): Promise<void> => {
    setChecking(true)
    try {
      const response = await fetch('/actuator/health')
      const text = await response.text()
      let parsed: unknown = null
      try {
        parsed = JSON.parse(text) as unknown
      } catch {
        parsed = null
      }

      // ⚠️⚠️ 关键：后端可能返回 200 但 body 是业务错误体。
      //    只看 HTTP 状态码会误报为健康 —— 必须校验结构。
      const isHealth =
        typeof parsed === 'object' &&
        parsed !== null &&
        'status' in (parsed as Record<string, unknown>)

      // ⚠️ 同时检查 /api/auth/jwks 作为替代健康检查
      let jwksStatus: 'UP' | 'DOWN' = 'DOWN'
      try {
        const jwksResponse = await fetch('/api/auth/jwks')
        if (jwksResponse.ok) {
          jwksStatus = 'UP'
        }
      } catch {
        jwksStatus = 'DOWN'
      }

      setHealth({
        status: isHealth && (parsed as { status: string }).status === 'UP' ? 'UP' : 'DOWN',
        httpStatus: response.status,
        checkedAt: new Date().toISOString(),
        detail: isHealth
          ? undefined
          : `返回的不是健康结果（HTTP ${response.status}），实际内容是接口错误`,
        jwksStatus,
      })
    } catch (cause) {
      setHealth({
        status: 'DOWN',
        httpStatus: 0,
        checkedAt: new Date().toISOString(),
        detail: cause instanceof Error ? cause.message : '网络错误',
      })
    } finally {
      setChecking(false)
    }
  }, [])

  useEffect(() => {
    void check()
  }, [check])

  return (
    <>
      <PageHead title="系统健康" lead="检查后端是否在运行。" />

      <Panel
        title="当前状态"
        icon="activity"
        actions={
          health !== null ? (
            <button type="button" className="btn btn--quiet" onClick={() => void check()} disabled={checking}>
              <Icon name="refresh" />
              {checking ? '检测中…' : '重新检测'}
            </button>
          ) : null
        }
      >
        {checking && health === null ? (
          <Loading label="正在检测" />
        ) : (
          <div className="status-line" style={{ gap: 'var(--s-4)', flexWrap: 'wrap' }}>
            {/* ⚠️ 状态会从「正常」翻到「异常」，读屏用户听不到这个变化：
                故把状态放进 live region，翻转即播报。 */}
            <span role="status" aria-live="polite">
              {health?.status === 'UP' ? (
                <StatusPill tone="success">正常</StatusPill>
              ) : (
                <StatusPill tone="danger">异常</StatusPill>
              )}
            </span>

            <span className="muted">响应码</span>
            <code>{health?.httpStatus ?? '—'}</code>

            {/* ⚠️ JWKS 端点状态作为替代健康检查 */}
            <span className="muted">JWKS</span>
            {health?.jwksStatus === 'UP' ? (
              <StatusPill tone="success">可达</StatusPill>
            ) : (
              <StatusPill tone="warn">不可达</StatusPill>
            )}

            <span className="muted">检测时间</span>
            {/* ⚠️ datetime 给机器可读值：toLocaleString 的本地化字符串
                人能读，程序解析不了。 */}
            <time className="muted" dateTime={health?.checkedAt}>
              {health !== undefined && health !== null
                ? new Date(health.checkedAt).toLocaleString('zh-CN')
                : '—'}
            </time>
          </div>
        )}
      </Panel>

      {health !== null && health.status === 'DOWN' ? (
        <Marked label="后端没有正常响应" tone="error">
          <p style={{ margin: 0 }}>{health.detail ?? '检查失败'}</p>
          <p className="muted" style={{ margin: 'var(--s-2) 0 0' }}>
            {health.jwksStatus === 'UP'
              ? '健康探针返回异常，但 /api/auth/jwks 可达——后端进程在跑，可能是健康探针配置问题。'
              : '健康探针与 /api/auth/jwks 都不可达——后端进程可能未启动。'}
          </p>
          <p className="muted" style={{ margin: 'var(--s-2) 0 0' }}>
            健康检查当前只返回总状态，不暴露组件明细，因此本页<b>看不出是哪个组件异常</b>——
            数据库、消息通道、磁盘都有可能，需要时请结合后端日志确认，
            不要只看这一页就断定是数据库。
          </p>
        </Marked>
      ) : null}

      <Unavailable
        feature="健康详情"
        blockedBy="G14"
        reason="后端把健康检查设成了最简模式，没暴露组件级明细"
        eta="P4"
      >
        {/*
          ⛔ 刻意不画组件级健康表（数据库/队列/磁盘）：
          后端未暴露该数据。硬画一张表只能填假数据。
        */}
        <Panel title="组件明细" icon="layers">
          <p className="muted" style={{ margin: 0 }}>
            数据库、消息队列、磁盘这些明细现在看不到，
            后端没有通过健康检查接口暴露。
          </p>
        </Panel>
      </Unavailable>
    </>
  )
}