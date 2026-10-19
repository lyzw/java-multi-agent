/**
 * 治理页组：P8 审批 / P9 UNKNOWN 处置 / P10 审计（FE-D2 §3.2/3.4）
 *
 * ⛔⛔ P9 禁止「一键重试」—— 这是 Sev-0 级别的结构约束：
 *   record_weakness 的幂等键含 run_id，
 *   盲目重放会产生**重复写入薄弱点**。
 *   故本文件刻意不提供任何重试按钮，连"占位重试"都不写。
 */

import {
  DataUnavailable,
  TableSkeleton,
} from '@/shared/DataUnavailable'

/* ── P8 审批台 ────────────────────────────────────────── */

export function ApprovalsPage(): JSX.Element {
  return (
    <>
      <h1>审批台</h1>
      <p className="muted">待人工审批的工具调用。</p>

      <DataUnavailable
        blockedBy="G8"
        feature="审批"
        reason="审批流尚未实现；当前 ToolInvocationGate 对需审批工具一律 fail-closed 拒绝"
        eta="P3-D05"
      >
        {/*
          ⚠️ 审批必须展示三项，缺一不可（FR-014）：
            参数摘要 + 目标资源 + 有效期
          缺「有效期」会导致过期申请被误批。
        */}
        <TableSkeleton
          columns={['目标资源', '工具', '参数摘要', '有效期至', '操作']}
          rows={4}
        />

        <section className="notice notice--warn">
          <p>
            <strong>过期与撤销一律拒绝</strong>，不静默通过。
          </p>
          <p className="muted">
            静默通过等于让失效的授权继续生效，
            违反「执行时重新授权」（P0-D03 红线 9）。
          </p>
        </section>
      </DataUnavailable>
    </>
  )
}

/* ── P9 UNKNOWN 处置台 ────────────────────────────────── */

export function ReconciliationPage(): JSX.Element {
  const selected: string | null = null

  return (
    <>
      <h1>未知操作处置</h1>
      <p className="muted">
        副作用未知的工具操作需人工对账后处置。
      </p>

      <DataUnavailable
        blockedBy="G9"
        feature="未知操作处置"
        reason="后端无 unknown:dispose 端点；预算预留失败时的 FAILED/UNKNOWN 账本也尚未接通（P3-D04 在建）"
        eta="P3-D03"
      >
        {/*
          ⚠️ 两种状态必须**显式区分**，不能合并成「异常任务」：

          UNKNOWN                  —— 不确定副作用是否发生，须先对账
          RECONCILIATION_REQUIRED  —— 已确认需要人工介入处置

          合并会让运维误判紧急程度。
        */}
        <section className="reconcile-kinds">
          <div>
            <h3>
              <code>UNKNOWN</code>
            </h3>
            <p className="muted">
              不确定副作用是否发生。<b>必须先对账</b>，不可直接重试。
            </p>
          </div>
          <div>
            <h3>
              <code>RECONCILIATION_REQUIRED</code>
            </h3>
            <p className="muted">
              已确认需要人工介入。<b>按处置流程走，不得盲目重放</b>。
            </p>
          </div>
        </section>

        <TableSkeleton
          columns={['操作标识', '状态', '工具', '发生时间', '租约到期', '处置']}
          rows={4}
        />

        {/*
          ⛔⛔ 此处**刻意没有任何重试按钮**。

          理由：record_weakness 幂等键 = student_id + run_id + knowledge_point，
          换 runId 重放会被视为新操作 → 重复写入薄弱点（P0-D00 §3.4 失败判定）。

          待接口就绪后，处置动作只能是：
            · 标记为已对账（确认副作用未发生）
            · 上报异常
            · 人工补偿（幂等键显式指定）
          **不得**提供「一键重试」。

          selected 状态仅为占位，接口就绪后由处置表单驱动。
        */}
        {selected !== null ? (
          <p className="muted">已选中：{selected}（处置表单待接口就绪）</p>
        ) : null}

        <section className="notice notice--danger">
          <p>
            <strong>本页不提供「一键重试」</strong>。
          </p>
          <p>
            盲目重放会被记为新操作，导致薄弱点<strong>重复写入</strong>
            （幂等键含 run_id，换 run 即失效）。
          </p>
        </section>
      </DataUnavailable>
    </>
  )
}

/* ── P10 审计查询 ─────────────────────────────────────── */

export function AuditPage(): JSX.Element {
  return (
    <>
      <h1>审计查询</h1>
      <p className="muted">合规审计记录（保留 180 天）。</p>

      <DataUnavailable
        blockedBy="G10"
        feature="审计查询"
        reason="后端无 audit:read 端点；审计表为 append-only（V6 迁移）但无读取接口"
        eta="P3-D07"
      >
        <TableSkeleton
          columns={['时间', '主体', '动作', '对象', '结果', '关联标识']}
          rows={8}
        />

        {/*
          ⛔ 审计日志**不含提示词原文**（保留 180 天）。
          故本页**不提供**「查看完整请求体」入口——从结构上杜绝。

          审计的价值在于「谁在何时做了什么」，
          而非还原完整内容——后者会让审计台变成数据泄漏面。
        */}
        <section className="notice notice--info">
          <p>
            <strong>审计记录不含提示词原文</strong>，保留 180 天。
          </p>
          <p className="muted">
            本页不提供「查看完整请求体」入口。
            如需还原请求内容，须走独立的授权流程而非审计台。
          </p>
        </section>
      </DataUnavailable>
    </>
  )
}