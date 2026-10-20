/**
 * 归因结果页 P5（FE-D8 接线）
 *
 * ⛔ 三条合规红线的落实方式（结构级，非文案）：
 *   C5 不得显示教材引用/页码/出处 → **结构上不设该区块**
 *   C6 结果不得含身份信息        → 渲染前敏感词兜底扫描
 *   C7 结果不落 localStorage     → 仅内存
 *
 * ⚠️⚠️ **本片删除了此前页面里的全部 6 处编造内容**
 * （FE-D8 §1.1）：编造的解题步骤、编造的错因、编造的知识点讲解。
 * 现在渲染的每一句话都来自后端真实数据。
 *
 * ⚠️ **空态 ≠ 占位**：
 *   「空态」= 接口接上了，确实没有这些内容 → 显示真实空态 + 说明原因；
 *   「占位」= 接口没接上 → 页面不该被访问。
 * 后端 `weaknesses` / `steps` 在真实模型接入前**恒空**，
 * 故本页会稳定显示真实空态——**这是真实状态，不是故障**。
 */

import { useMemo } from 'react'
import { useParams, Link } from 'react-router-dom'
import { Sheet, Empty, Loading, ErrorState } from '@/shared/states'
import { scanText } from '@/features/compliance/scan'
import { useRunResult } from '@/features/run/useRunResult'

/**
 * ⛔ C6 的实现：渲染前的敏感词兜底扫描。
 * 导出而非内联，是为了让接口接入时**必须**显式调用——
 * 内联在渲染路径里的防护，很容易在某次改版时被顺手删掉。
 */
export function findLeakedFields(payload: Record<string, string>): string[] {
  const bad: string[] = []
  for (const [key, value] of Object.entries(payload)) {
    if (scanText(value).length > 0) {
      bad.push(key)
    }
  }
  return bad
}

/** ⛔ 终态判定：与后端 `run_state` 枚举一致（不含 UNKNOWN）。 */
const TERMINAL_STATES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'])

export function RunResultPage(): JSX.Element {
  const { runId } = useParams<{ runId: string }>()
  const { result, loading, error, reload } = useRunResult(runId)

  // ⚠️ C6 扫描只针对**模型产出的文本**（explanation）。
  //    结构性字段（状态、题号）不来自模型，扫描它们只会制造误报。
  const leakedFields: string[] = useMemo(() => {
    if (result === null || result.explanation === null) return []
    return findLeakedFields({ explanation: result.explanation })
  }, [result])

  if (loading) {
    return (
      <>
        <div className="main__head">
          <h1>归因结果</h1>
        </div>
        <Loading label="正在读取结果" />
      </>
    )
  }

  if (error !== null) {
    return (
      <>
        <div className="main__head">
          <h1>归因结果</h1>
          <p className="main__lead">
            运行标识 <code>{runId ?? '缺失'}</code>
          </p>
        </div>
        {/* ⛔ 错误页**不得**露出任何编造内容占位 */}
        <ErrorState
          reason={error}
          next="结果可能还没准备好，或这次运行的标识已失效。"
          onRetry={reload}
        />
      </>
    )
  }

  if (result === null) {
    return (
      <>
        <div className="main__head">
          <h1>归因结果</h1>
        </div>
        <ErrorState reason="没有读到这次运行的结果。" next="请重新发起一次辅导。" />
      </>
    )
  }

  const explanation = result.explanation
  const explanationBlocked = explanation !== null && leakedFields.length > 0
  const isTerminal = TERMINAL_STATES.has(result.state)

  return (
    <>
      <div className="main__head">
        <h1>归因结果</h1>
        <p className="main__lead">
          运行标识 <code>{result.runId}</code>
        </p>
      </div>

      {leakedFields.length > 0 ? (
        <div className="marked" role="alert">
          <p className="marked__label">已拦下不可采集的信息</p>
          <p>结果中这些字段含有不该出现的内容：{leakedFields.join('、')}</p>
        </div>
      ) : null}

      {/* 状态行：真实状态，不编造 */}
      <div className="status-line" style={{ flexWrap: 'wrap', gap: 12 }}>
        <span className="muted">状态</span>
        <span className={isTerminal ? 'chip chip--settled' : 'chip'}>{result.state}</span>
        {result.errorCode !== null ? (
          <>
            <span className="muted">原因码</span>
            <span className="chip chip--mark">{result.errorCode}</span>
          </>
        ) : null}
      </div>

      <Sheet title="讲解" note={isTerminal ? undefined : '这次运行还没结束，内容可能继续变化。'}>
        {result.state === 'FAILED' ? (
          <Empty
            title="这次运行没有成功完成"
            hint="原因码在上方；具体细节不展示，避免把内部信息带出来。"
          />
        ) : explanation === null ? (
          <Empty
            title="还没有讲解内容"
            hint="这次运行尚未产出结果，或还在执行中。"
          />
        ) : explanationBlocked ? (
          // ⚠️ C6：命中敏感词时**拦截该字段**并说明，
          //    既不静默删除（用户会以为模型没提），也不整页报错。
          <Empty
            title="讲解内容里有不该出现的信息，已拦下"
            hint="换一个匿名编号再试，或联系辅导老师确认。"
          />
        ) : (
          <p style={{ margin: 0 }}>{explanation}</p>
        )}
      </Sheet>

      <Sheet title="解题步骤">
        {result.steps.length === 0 ? (
          // ⛔ 真实空态：**不编造步骤**。
          //    步骤表目前没有写入方，因此这里长期为空——这是真实状态。
          <Empty
            title="没有步骤记录"
            hint="系统还没开始逐步记录解题过程；这里不会用示例内容填充。"
          />
        ) : (
          <ol className="steps">
            {result.steps.map((step) => (
              <li className="steps__item" key={step.number}>
                <div className="steps__head">
                  <span className="steps__no">第 {step.number} 步</span>
                  <span className="chip">{step.state}</span>
                </div>
                {step.output !== null ? (
                  <p className="steps__body">{step.output}</p>
                ) : null}
              </li>
            ))}
          </ol>
        )}
      </Sheet>

      <Sheet title="错因归类">
        {result.weaknesses.length === 0 ? (
          // ⛔ 真实空态：**不编造错因**。
          <Empty
            title="这次分析没有记录到错因"
            hint="错因需要模型实际调用记录工具才会产生；系统不会替你编一条。"
          />
        ) : (
          <div className="status-line" style={{ flexWrap: 'wrap', gap: 12 }}>
            {result.weaknesses.map((w, index) => (
              <span className="status-line" key={`${w.knowledgePoint}-${index}`}
                    style={{ flexWrap: 'wrap', gap: 12 }}>
                <span className="muted">错因</span>
                <span className="chip chip--mark">{w.errorType}</span>
                <span className="muted">涉及知识点</span>
                <span className="chip">{w.knowledgePoint}</span>
                {w.confidence !== null ? (
                  <>
                    <span className="muted">置信度</span>
                    <span className="chip">{w.confidence}</span>
                  </>
                ) : null}
              </span>
            ))}
          </div>
        )}
      </Sheet>

      <Sheet title="薄弱点回执">
        <p className="muted" style={{ margin: 0 }}>
          {result.weaknesses.length > 0
            ? '这次分析记录了薄弱点。同一个匿名编号下次还会看到相关提醒。'
            : '这次分析没有写入薄弱点。分析完成后，这道题会写入薄弱点清单。'}
        </p>
      </Sheet>

      {/*
        ⚠️⛔ 待核对分区：**独立、醒目、不并入上方成功区**。

        为什么现在就定死：P0-D00 §3.4 是硬禁令——
        终态存在未决 UNKNOWN 时必须独立暴露。
        权威来源是 `tool_operation.state='UNKNOWN'`（不是 run.state）。
      */}
      <section className="reconcile" data-testid="reconcile-zone">
        <h2 className="reconcile__title">待核对</h2>
        {/*
          ⚠️ 这里**只提 UNKNOWN**：
          `RECONCILIATION_REQUIRED` **不在 run_state 枚举里**（P4-D10 §1.4 实测），
          在界面上提它等于告诉用户一个**永远不会出现的状态**。
          权威来源是 tool_operation.state='UNKNOWN'。
        */}
        <p className="muted">
          当某一步的结果不确定时（状态为 <code>UNKNOWN</code>），结论会在这里单独列出，
          不会混进上面的正常结果。
        </p>
        {result.reconciliation.pending ? (
          <>
            <p className="muted">
              有 {result.reconciliation.operations.length} 项操作的结果还不确定，
              需要人工核对后才能算数。它们**不会**混进上面的正常结果。
            </p>
            <ul className="entry-list">
              {result.reconciliation.operations.map((op) => (
                <li className="entry" key={op.operationId}>
                  <div className="entry__title">{op.toolName}</div>
                  <div className="entry__desc">
                    状态 {op.state} · 操作 {op.operationId}
                  </div>
                </li>
              ))}
            </ul>
          </>
        ) : (
          <p className="muted" style={{ margin: 0 }}>
            当前没有待核对项。
          </p>
        )}
      </section>

      {/*
        ⛔ C5 红线：此处**刻意没有**「参考资料 / 引用 / 出处 / 页码」区块。
        DEC-5 明确不做检索增强，编造出处即判失败（R-018）。
        从结构上不存在该位置，比「做了再隐藏」更可靠。
      */}

      <p style={{ marginTop: 'var(--s-5)' }}>
        <Link to="/runs/new" className="btn">
          再辅导一道
        </Link>
      </p>
    </>
  )
}
