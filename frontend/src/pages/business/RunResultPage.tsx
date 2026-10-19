/**
 * 归因结果页 P5（FE-D3）
 *
 * ⛔ 三条合规红线的落实方式（结构级，非文案）：
 *   C5 不得显示教材引用/页码/出处 → **结构上不设该区块**
 *   C6 结果不得含身份信息        → 渲染前敏感词兜底扫描
 *   C7 结果不落 localStorage     → 仅内存
 *
 * 视觉：这是一张「批完的卷子」—— 步骤序列 + 左侧批注栏。
 */

import { useMemo } from 'react'
import { useParams, Link } from 'react-router-dom'
import { Sheet, Marked, Unavailable } from '@/shared/states'
import { scanText } from '@/features/compliance/scan'

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

export function RunResultPage(): JSX.Element {
  const { runId } = useParams<{ runId: string }>()
  const leakedFields: string[] = useMemo(() => [], [])

  return (
    <>
      <div className="main__head">
        <h1>归因结果</h1>
        <p className="main__lead">
          运行标识 <code>{runId ?? '缺失'}</code>
        </p>
      </div>

      {leakedFields.length > 0 ? (
        <div className="marked" role="alert">
          <p className="marked__label">已拦下不可采集的信息</p>
          <p>结果中这些字段含有不该出现的内容：{leakedFields.join('、')}</p>
        </div>
      ) : null}

      <Unavailable
        feature="归因结果"
        blockedBy="G5"
        reason="后端还没有提供读取分析结果的接口"
        eta="P3"
      >
        {/*
          ⛔ 步骤序列 —— 这里用编号是合理的：
             解题步骤本身就是先后序列（不是装饰性编号）。

          ⚠️ 区块结构现在就定死，接口就绪后只替换取数逻辑。
        */}
        <Sheet title="解题步骤" note="按顺序对照，找出和你的做法不同的地方。">
          <ol className="steps">
            <li className="steps__item steps__item--done">
              <div className="steps__head">
                <span className="steps__no">第 1 步</span>
                <span className="chip chip--settled">你的做法正确</span>
              </div>
              <p className="steps__body">分子分母同乘 √3，把分母里的根式消掉。</p>
            </li>

            <li className="steps__item steps__item--bad">
              <div className="steps__head">
                <span className="steps__no">第 2 步</span>
                <span className="chip chip--mark">这里错了</span>
              </div>
              <p className="steps__body">
                <span className="steps__wrong">分母化为正，符号丢了</span>
              </p>
              <Marked label="为什么错" tone="error">
                <p style={{ margin: 0 }} className="hand">
                  根式分母有理化时，若分母含 a−√b 且 b 大于 a，分母会变成负数。
                  你这里漏了负号，符号就反了。
                </p>
              </Marked>
            </li>

            <li className="steps__item">
              <div className="steps__head">
                <span className="steps__no">第 3 步</span>
              </div>
              <p className="steps__body">分子分母同时除以 √3，得到最简形式。</p>
            </li>
          </ol>
        </Sheet>

        <Sheet title="错因归类">
          <div className="status-line" style={{ flexWrap: 'wrap', gap: 12 }}>
            <span className="muted">错因</span>
            <span className="chip chip--mark">符号处理不当</span>
            <span className="muted">涉及知识点</span>
            <span className="chip">根式分母有理化</span>
          </div>
        </Sheet>

        <Sheet title="薄弱点回执">
          <p className="muted" style={{ margin: 0 }}>
            分析完成后，这道题会写入薄弱点清单。同一个匿名编号下次还会看到相关提醒。
          </p>
        </Sheet>

        {/*
          ⚠️⛔ 待核对分区：**独立、醒目、不并入上方成功区**。

          为什么现在就定死：P0-D00 §3.4 是硬禁令——
          终态存在未决 UNKNOWN 时必须独立暴露。
          若接口就绪时没有这个位置，开发会倾向「先不做」，届时即违规。
        */}
        <section className="reconcile" data-testid="reconcile-zone">
          <h2 className="reconcile__title">待核对</h2>
          <p className="muted">
            当某一步的结果不确定时（状态为 <code>UNKNOWN</code> 或{' '}
            <code>RECONCILIATION_REQUIRED</code>），结论会在这里单独列出，
            不会混进上面的正常结果。
          </p>
          <p className="muted" style={{ margin: 0 }}>
            当前没有待核对项。
          </p>
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
      </Unavailable>
    </>
  )
}