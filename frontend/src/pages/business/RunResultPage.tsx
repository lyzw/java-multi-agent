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
import { Sheet, Empty, Loading, ErrorState, Breadcrumbs } from '@/shared/states'
import { scanText } from '@/features/compliance/scan'
import { useRunResult } from '@/features/run/useRunResult'
import { RunFeedback } from '@/features/run/RunFeedback'
import { MarkdownAnswer } from '@/shared/MarkdownAnswer'
import { stateLabel } from '@/features/run/runState'
import { PageHead, StatusPill } from '@/components/ui'
import { Icon } from '@/components/Icon'

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

/**
 * 步骤状态 → 中文标签。
 *
 * ⚠️⚠️ **跨层口径（E2E 二轮优化，2026-11-14）**：`run_step.state` 复用了
 *    `public.run_state` 枚举，但生产链路（JdbcRunStepRepository /
 *    JdbcAgentChatAuditRepository）把每一步**单条 INSERT 写死 'RUNNING'**，
 *    表示「该步骤已发生」，**不是「执行中」**；步骤完成以 `completed_at` 判定。
 *    这里曾按 run_state 字面语义把 RUNNING 显示成「执行中」，导致已完成的
 *    Run 里每个步骤都像卡住 —— **必须映射为「已完成」**。
 *
 * ⚠️ 'SUCCEEDED'/'SUCCESS' 仍兼容历史数据与测试夹具；
 *    **未知取值原样显示**（不猜含义，与 runState.ts 的既有原则一致）。
 */
const STEP_STATE_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  SUCCEEDED: '成功',
  FAILED: '失败',
  RUNNING: '已完成',
  QUEUED: '排队中',
  PENDING: '待执行',
  CANCELLED: '已取消',
  TIMED_OUT: '已超时',
}

function stepStateLabel(state: string): string {
  return STEP_STATE_LABELS[state] ?? state
}

/**
 * 步骤状态 → `.steps__item` 修饰类。
 *
 * ⚠️ 「错在第几步」必须**看得见**：
 *    CSS 里 .steps__item--done（绿点）与 .steps__item--bad（红点）
 *    早就定义好了，此前一次都没被引用过——步骤全是同一个中性点。
 *    未知取值**不加**修饰类（保守：不猜哪一步成功了）。
 */
function stepStateModifier(state: string): string {
  switch (state) {
    case 'SUCCESS':
    case 'SUCCEEDED':
    case 'RUNNING':
      // ⚠️ run_step 的 RUNNING 是「已发生」占位值（见上方口径说明），
      //    不是进行中，必须给绿点，否则已完成的步骤全显示中性点。
      return 'steps__item--done'
    case 'FAILED':
      return 'steps__item--bad'
    default:
      return ''
  }
}

export function RunResultPage(): JSX.Element {
  const { runId } = useParams<{ runId: string }>()
  const { result, loading, error, reload } = useRunResult(runId)

  /**
   * ⛔⛔ C6 红线：**所有会渲染出来的文本字段**都要扫，且**必须扫全文**。
   *
   * 修复背景（E2E 四轮优化，2026-11-14 行为级专项实测发现）：
   *   旧实现有两个真实漏洞 ——
   *     ① `explanation.slice(0, 10000)` **截断后才扫描**，而渲染用的是完整文本，
   *        第 10001 字符之后的身份证/手机号既不扫也照常显示；
   *     ② 只喂 `{ explanation }` 一个字段，但页面上 `weaknesses.knowledgePoint`、
   *        `steps[].output`、`reconciliation.operations[].toolName`、`errorCode`
   *        都会渲染却完全不扫。C6「结果不得含身份信息」等于只守了一扇门。
   *   `slice` 的初衷是「避免长文本阻塞渲染」，但 `useMemo([result])` 已经保证
   *   **每次结果只扫一遍**；正则扫描是 O(n)，丢掉尾部换来的性能收益不成立，
   *   反而把合规红线开了一个口子。⛔ 禁止再把 truncate 加回来。
   *
   * 命中后按字段**精准拦截**（只拦命中的区块），不整页报错：
   *   一次偶然复述学号不该让整页结果不可用（沿用 T7 的既有纪律）。
   */
  const leakedFields: string[] = useMemo(() => {
    if (result === null) return []
    const joinFields = (parts: (string | null)[]): string =>
      parts.map((p) => p ?? '').join(' ')
    // ⚠️⛔ **只扫自由文本字段**：结构性标识（errorCode / state / stepType /
    //    step.number / operationId(UUID) / confidence 小数）**不进扫描文本**。
    //    原因：STUDENT_ID 规则是「8–12 位连续数字」，UUID 里的数字段、
    //    confidence 的小数位都可能命中 → 每个正常 Run 都被假阳性拦下。
    //    这类字段不来自模型产出，扫描它们既不合规也无收益（沿用原设计口径）。
    const payload: Record<string, string> = {
      讲解: result.explanation ?? '',
      错因归类: result.weaknesses
        .map((w) => joinFields([w.knowledgePoint, w.errorType]))
        .join(' '),
      解题步骤: result.steps.map((s) => s.output ?? '').join(' '),
      待核对: result.reconciliation.operations.map((o) => o.toolName ?? '').join(' '),
    }
    return findLeakedFields(payload)
  }, [result])

  if (loading) {
    return (
      <>
        <Breadcrumbs items={[{ label: '检索记录', to: '/runs' }, { label: '归因结果' }]} />
        <PageHead title="归因结果" />
        <Loading label="正在读取结果" />
      </>
    )
  }

  if (error !== null) {
    return (
      <>
        <Breadcrumbs items={[{ label: '检索记录', to: '/runs' }, { label: '归因结果' }]} />
        <PageHead
          title="归因结果"
          lead={
            <>
              运行标识 <code>{runId ?? '缺失'}</code>
            </>
          }
        />
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
        <Breadcrumbs items={[{ label: '检索记录', to: '/runs' }, { label: '归因结果' }]} />
        <PageHead title="归因结果" />
        <ErrorState reason="没有读到这次运行的结果。" next="请重新发起一次辅导。" />
      </>
    )
  }

  const explanation = result.explanation
  /** 命中哪个字段就只拦哪个区块，避免「一处偶然复述学号」把整页结果打掉。 */
  const blocked = (field: string): boolean => leakedFields.includes(field)
  const explanationBlocked = explanation !== null && blocked('讲解')
  const isTerminal = TERMINAL_STATES.has(result.state)

  return (
    <>
      <Breadcrumbs items={[{ label: '检索记录', to: '/runs' }, { label: '归因结果' }]} />
      <PageHead
        title="归因结果"
        lead={
          <>
            运行标识 <code>{result.runId}</code>
          </>
        }
      />

      {leakedFields.length > 0 ? (
        <div className="marked" role="alert">
          <p className="marked__label">已拦下不可采集的信息</p>
          <p>结果中这些字段含有不该出现的内容：{leakedFields.join('、')}</p>
        </div>
      ) : null}

      {/* 状态行：真实状态，不编造 */}
      <div className="status-line" style={{ flexWrap: 'wrap', gap: 12 }}>
        <StatusPill
          tone={
            result.state === 'SUCCEEDED'
              ? 'success'
              : result.state === 'FAILED' || result.state === 'TIMED_OUT'
                ? 'danger'
                : 'info'
          }
        >
          {/*
            ⚠️ 标签用中文（stateLabel），英文枚举**不作为状态标签**直接甩给用户。
          */}
          {stateLabel(result.state)}
        </StatusPill>
        {/*
          ⚠️ 中文标签与原始状态码**并存**（与 P9 处置台同一套做法）：
            标签给普通读者（「已完成」），状态码给排障与跨系统对齐
            （搜日志、对导出、跟后端枚举对账都要用原始值）。
        */}
        <span className="muted">状态码</span>
        <span className="mono">{result.state}</span>
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
          // ⚠️ P0-3（2026-11-17）：讲解正文改为 Markdown + KaTeX 渲染。
          //    此前只认粗体/行内码，公式与表格全是源码（教师评审实测 0 个公式节点）。
          <MarkdownAnswer content={explanation} />
        )}
      </Sheet>

      {/* 讲解质量反馈（P7-3/P7-4）：紧跟讲解之后——问的是「这段讲解有没有用」 */}
      <RunFeedback runId={result.runId} />

      <Sheet title="解题步骤">
        {blocked('解题步骤') ? (
          // ⛔ C6：命中字段不得渲染原文，也不得编造替代内容。
          <Empty title="这部分内容里有不该出现的信息，已拦下" hint="换一个匿名编号再试，或联系辅导老师确认。" />
        ) : result.steps.length === 0 ? (
          // ⛔ 真实空态：**不编造步骤**。
          <Empty
            title="没有步骤记录"
            hint="这次运行没有留下步骤记录；这里不会用示例内容填充。"
          />
        ) : (
          <>
            {/* ⚠️ 口径告知：生产链路只写步骤类型与起止时间，不写逐步正文，
                output_payload 恒为空。不说明的话用户会以为「步骤内容丢了」。 */}
            <p className="muted">步骤记录的是执行类型与耗时；逐步讲解请看上方「归因讲解」。</p>
            <ol className="steps">
            {result.steps.map((step) => {
              // ⚠️ 失败步骤的正文用 .steps__wrong（删除线 + 批改红划线），
              //    与红点一起把「错在第几步」标出来。
              const failed = step.state === 'FAILED'
              return (
                <li className={`steps__item ${stepStateModifier(step.state)}`} key={step.number}>
                  <div className="steps__head">
                    <span className="steps__no">第 {step.number} 步</span>
                    <span className={failed ? 'chip chip--mark' : 'chip'}>
                      {stepStateLabel(step.state)}
                    </span>
                  </div>
                  {step.output !== null ? (
                    <p className={failed ? 'steps__body steps__wrong' : 'steps__body'}>
                      {step.output}
                    </p>
                  ) : null}
                </li>
              )
            })}
            </ol>
          </>
        )}
      </Sheet>

      <Sheet title="错因归类">
        {blocked('错因归类') ? (
          // ⛔ C6：命中字段不得渲染原文，也不得编造替代内容。
          <Empty title="这部分内容里有不该出现的信息，已拦下" hint="换一个匿名编号再试，或联系辅导老师确认。" />
        ) : result.weaknesses.length === 0 ? (
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
        {blocked('待核对') ? (
          // ⛔ C6：命中字段不得渲染原文，也不得编造替代内容。
          <p className="muted" style={{ margin: 0 }}>
            这部分内容里有不该出现的信息，已拦下。
          </p>
        ) : result.reconciliation.pending ? (
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
        <Link to="/runs/new" className="btn btn--lg">
          <Icon name="plus" />
          再辅导一道
        </Link>
      </p>
    </>
  )
}
