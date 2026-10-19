/**
 * P13 系统健康（FE-D2 §3.7）
 *
 * ✅ **本阶段唯一可接真实数据的页面**——后端有 /actuator/health。
 *
 * ⚠️ 但当前实测返回 500（根因未诊断，运行中的实例是旧构建产物），
 *    故此处**同时处理**「可用」与「不可用」两种情况，不假装一定能拿到数据。
 *
 * ⛔ **不得**规划依赖详细健康信息的运维页（G14）：
 *    Actuator 为 show-details: never，细节未暴露。
 */

import { useEffect, useState } from 'react'
import { DataUnavailable } from '@/shared/DataUnavailable'

interface HealthState {
  status: 'UP' | 'DOWN' | 'UNKNOWN'
  httpStatus: number
  checkedAt: string
  message?: string
}

export function HealthPage(): JSX.Element {
  const [health, setHealth] = useState<HealthState | null>(null)
  const [checking, setChecking] = useState(true)

  const check = async (): Promise<void> => {
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

      // ⚠️ ⚠️ 关键：后端可能返回 200 但 body 里是业务错误体
      //    （实测 /actuator/health 返回 500 + {code:INTERNAL_ERROR}）。
      //    **不能只看 HTTP 状态码**——那会误报为健康。
      const isHealthPayload =
        typeof parsed === 'object' &&
        parsed !== null &&
        'status' in (parsed as Record<string, unknown>)

      setHealth({
        status: isHealthPayload
          ? ((parsed as { status: string }).status === 'UP' ? 'UP' : 'DOWN')
          : 'DOWN',
        httpStatus: response.status,
        checkedAt: new Date().toISOString(),
        message: isHealthPayload
          ? undefined
          : `响应不是健康结构（HTTP ${response.status}）`,
      })
    } catch (cause) {
      setHealth({
        status: 'DOWN',
        httpStatus: 0,
        checkedAt: new Date().toISOString(),
        message: cause instanceof Error ? cause.message : '网络错误',
      })
    } finally {
      setChecking(false)
    }
  }

  useEffect(() => {
    void check()
  }, [])

  return (
    <>
      <h1>系统健康</h1>
      <p className="muted">
        探测 <code>/actuator/health</code>（匿名可访问）。
      </p>

      <section className="health-card">
        <div className="health-card__row">
          <span>状态</span>
          <strong
            className={`health-badge health-badge--${health?.status.toLowerCase() ?? 'unknown'}`}
            data-testid="health-status"
          >
            {health === null
              ? '检测中…'
              : health.status === 'UP'
                ? '正常'
                : health.status === 'DOWN'
                  ? '异常'
                  : '未知'}
          </strong>
        </div>
        <div className="health-card__row">
          <span>HTTP 状态码</span>
          <code>{health?.httpStatus ?? '—'}</code>
        </div>
        <div className="health-card__row">
          <span>检测时间</span>
          <time>{health ? new Date(health.checkedAt).toLocaleString('zh-CN') : '—'}</time>
        </div>
        {health?.message !== undefined ? (
          <p className="muted">说明：{health.message}</p>
        ) : null}

        <button type="button" onClick={() => void check()} disabled={checking}>
          {checking ? '检测中…' : '重新检测'}
        </button>
      </section>

      {health !== null && health.status === 'DOWN' ? (
        <section className="notice notice--warn">
          <p>
            <strong>健康检查未通过。</strong>
          </p>
          <p className="muted">
            可能原因：后端未启动、数据库不可达，或{' '}
            <code>/actuator/health</code> 当前返回 500
            （实测现象，根因未诊断——运行中的实例为旧构建产物）。
          </p>
          <p className="muted">
            判断后端是否存活，请改用{' '}
            <code>GET /api/auth/jwks</code>（不依赖探针组件，实测稳定返回 200）。
          </p>
        </section>
      ) : null}

      <DataUnavailable
        blockedBy="G14"
        feature="健康详情"
        reason="Actuator 配置为 show-details: never，未暴露组件级明细"
        eta="P4"
      >
        {/*
          ⛔ 刻意不规划组件级健康表（数据库/队列/磁盘）：
          后端未暴露该数据。硬画一张表只能填假数据。
        */}
        <p className="muted">
          组件级明细（数据库、消息队列、磁盘）当前不可见，
          后端未通过 Actuator 暴露。
        </p>
      </DataUnavailable>
    </>
  )
}