/**
 * 治理页组（FE-D3）：P8 审批 / P9 未知操作处置 / P10 审计
 *
 * ⛔⛔ P9 禁止「一键重试」—— Sev-0 级结构约束：
 *   record_weakness 幂等键含 run_id，盲目重放会产生**重复写入薄弱点**。
 *   故本文件刻意不含任何重试按钮，连占位重试都不写。
 */

import { Sheet, Unavailable, Marked, Empty } from '@/shared/states'

/* ── P8 审批台 ───────────────────────────────────────── */

export function ApprovalsPage(): JSX.Element {
  return (
    <>
      <div className="main__head">
        <h1>审批台</h1>
        <p className="main__lead">等着你确认才能执行的操作。</p>
      </div>

      <Unavailable
        feature="审批"
        blockedBy="G8"
        reason="审批流还没做；目前需要审批的操作一律直接拒绝"
        eta="P3-D05"
      >
        <Sheet title="待审批">
          <Empty title="没有等待审批的操作" hint="需要人工确认的操作会出现在这里。" />
        </Sheet>

        <Sheet title="审批时需要看什么">
          {/*
            ⚠️ 审批必须展示三项，缺一不可（FR-014）：
              参数摘要 + 目标资源 + 有效期
            缺「有效期」会导致过期申请被误批。
          */}
          <div className="status-line" style={{ flexWrap: 'wrap', gap: 12 }}>
            <span className="chip">参数摘要</span>
            <span className="chip">目标资源</span>
            <span className="chip">有效期至</span>
          </div>
          <p className="muted" style={{ marginTop: 'var(--s-3)', marginBottom: 0 }}>
            过期和被撤销的申请一律拒绝，不会静默通过——
            静默通过等于让失效的授权继续生效。
          </p>
        </Sheet>
      </Unavailable>
    </>
  )
}

/* ── P9 未知操作处置 ─────────────────────────────────── */

export function ReconciliationPage(): JSX.Element {
  return (
    <>
      <div className="main__head">
        <h1>未知操作处置</h1>
        <p className="main__lead">结果不确定的操作，要人工核对后才能继续。</p>
      </div>

      <Unavailable
        feature="未知操作处置"
        blockedBy="G9"
        reason="后端还没有提供处置接口"
        eta="P3-D03"
      >
        <Sheet title="两种待处理状态">
          {/*
            ⚠️ 两种状态必须**显式区分**，不能合并成「异常任务」——
            合并会让运维误判紧急程度。
          */}
          <div
            style={{
              display: 'grid',
              gap: 'var(--s-4)',
              gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))',
            }}
          >
            <div>
              <h3 className="mono" style={{ fontSize: 'var(--t-body)' }}>
                UNKNOWN
              </h3>
              <p className="muted" style={{ margin: 0 }}>
                不确定操作是否真的执行了。<strong>必须先核对</strong>，不能直接重试。
              </p>
            </div>
            <div>
              <h3 className="mono" style={{ fontSize: 'var(--t-body)' }}>
                RECONCILIATION_REQUIRED
              </h3>
              <p className="muted" style={{ margin: 0 }}>
                已确认需要人工处理。<strong>按处置流程走</strong>，不能盲目重放。
              </p>
            </div>
          </div>
        </Sheet>

        <Sheet title="待处置列表">
          <Empty title="没有待处置的操作" hint="出现不确定的操作时会列在这里。" />
        </Sheet>

        {/*
          ⛔⛔ 此处**刻意没有任何重试按钮**。

          理由：record_weakness 幂等键 = student_id + run_id + knowledge_point，
          换 runId 重放会被视为新操作 → 重复写入薄弱点（P0-D00 §3.4 失败判定）。

          接口就绪后允许的处置只有：
            · 标记为已核对（确认未发生副作用）
            · 上报异常
            · 人工补偿（幂等键显式指定）
          **不得**提供「一键重试」。
        */}
        <Marked label="这一页为什么没有重试按钮" tone="error">
          <p style={{ margin: 0 }}>
            薄弱点的记录会用运行标识做去重。换个标识重试，系统会当成一次新操作，
            于是同一个错误被记两次。
          </p>
        </Marked>
      </Unavailable>
    </>
  )
}

/* ── P10 审计查询 ────────────────────────────────────── */

export function AuditPage(): JSX.Element {
  return (
    <>
      <div className="main__head">
        <h1>审计查询</h1>
        <p className="main__lead">谁在什么时候做了什么。记录保留 180 天。</p>
      </div>

      <Unavailable
        feature="审计查询"
        blockedBy="G10"
        reason="后端还没有提供审计查询接口"
        eta="P3-D07"
      >
        <Sheet title="审计记录">
          <Empty title="没有可查的记录" hint="接口接上后，可按时间、操作人和动作筛选。" />
        </Sheet>

        {/*
          ⛔ 审计日志**不含提示词原文**（保留 180 天）。
          故本页**不提供**「查看完整请求体」入口——从结构上杜绝。

          审计的价值在于「谁在何时做了什么」，
          而非还原完整内容——后者会让审计台变成数据泄漏面。
        */}
        <Marked label="审计记录里没有什么">
          <p style={{ margin: 0 }}>
            审计记录不保存题目原文和解题过程。
            需要还原内容要走单独的授权流程。
          </p>
        </Marked>
      </Unavailable>
    </>
  )
}