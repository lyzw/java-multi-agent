/**
 * 业务页组：P5 结果 / P6 画像 / P7 检索（FE-D2 §3.1/3.5/3.6）
 *
 * ⛔ P5 三条合规红线的落实方式（不是靠文案，是靠结构）：
 *   C5 不得显示教材引用/页码/出处 → **结构上不设「来源」区块**
 *   C6 结果不得含身份信息        → 渲染前敏感词兜底扫描
 *   C7 结果不落 localStorage     → 仅内存
 */

import { useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import {
  DataUnavailable,
  BlockSkeleton,
  TableSkeleton,
} from '@/shared/DataUnavailable'
import { scanText } from '@/features/compliance/scan'

/* ── P5 归因结果页 ────────────────────────────────────── */

/**
 * ⛔ C6 红线的实现：结果渲染前的敏感词兜底扫描。
 *
 * 导出而非内联，是为了让接口接入时**必须**显式调用它——
 * 内联在渲染路径里的防护，很容易在某次改版时被顺手删掉。
 */
export function findLeakedFields(
  payload: Record<string, string>,
): string[] {
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

  /*
   * ⛔ C6 红线落地。
   *
   * 当前结果数据为 null（接口未就绪），故无泄漏可言；
   * 但**兜底逻辑必须现在存在**——接口接上后现写极易遗漏。
   * 接口就绪时在取数处调用 findLeakedFields()，命中即拒绝渲染。
   */
  const leakedFields: string[] = []

  return (
    <>
      <h1>归因结果</h1>
      <p className="muted">运行标识：<code>{runId ?? '（缺失）'}</code></p>

      {leakedFields.length > 0 ? (
        <p role="alert" className="error">
          检测到结果中含不可采集的信息，已阻止渲染：
          {leakedFields.join('、')}
        </p>
      ) : null}

      <DataUnavailable
        blockedBy="G5"
        feature="归因结果"
        reason="后端尚未提供 result:read 端点"
        eta="P3"
      >
        {/*
          ⛔⛔ 区块结构现在就定死。
          接口就绪后只替换取数逻辑，**不改结构**。

          ⚠️ 尤其「待核对」分区必须现在就存在：
          P0-D00 §3.4 明确规定终态存在未决 UNKNOWN 时
          必须独立暴露，「等接口来了再补」= 违反禁令。
        */}
        <section className="result-block">
          <h2>归因结论</h2>
          <p className="muted">
            预期含：错因分类、置信度、涉及知识点。
          </p>
        </section>

        <section className="result-block">
          <h2>讲解区</h2>
          <p className="muted">预期含：分步骤讲解。</p>
        </section>

        <section className="result-block">
          <h2>解题步骤</h2>
          <p className="muted">预期含：可执行的步骤列表。</p>
        </section>

        <section className="result-block">
          <h2>薄弱点更新回执</h2>
          <p className="muted">预期含：写入结果与幂等键状态。</p>
        </section>

        {/*
          ⚠️⛔ 待核对分区：**独立、不可折叠、不可合并进成功态**。

          为什么现在就要有：接口就绪时若没有这个位置，
          开发会倾向于「先不做，等需要再加」，
          而 P0-D00 §3.4 是硬禁令（P0 阶段确立的失败判定项）。
        */}
        <section className="reconcile-zone" data-testid="reconcile-zone">
          <h2>⚠️ 待核对（未决操作）</h2>
          <p className="muted">
            当工具调用进入 <code>UNKNOWN</code> 或{' '}
            <code>RECONCILIATION_REQUIRED</code> 时，
            结论在此独立呈现，<b>不并入上方成功区</b>。
          </p>
          <p className="muted">
            当前无待核对项（结果接口未就绪）。
          </p>
        </section>

        {/*
          ⛔ C5 红线：此处**刻意没有**「参考资料 / 引用 / 出处 / 页码」区块。
          DEC-5 明确不做 RAG，编造出处即判失败（R-018）。
          从结构上不存在该位置，比「做了再隐藏」更可靠。
        */}
      </DataUnavailable>
    </>
  )
}

/* ── P6 学习画像页 ────────────────────────────────────── */

/**
 * ⛔ 白名单渲染（纵深防御）
 *
 * 即便后端将来返回姓名/学校/班级，**前端也应拒绝渲染**。
 * 依据：PIPL 采集白名单仅 studentId / 年级 / 学科 / 薄弱点。
 */
interface RawProfile {
  studentId?: unknown
  grade?: unknown
  subject?: unknown
  weaknesses?: unknown
  name?: unknown
  school?: unknown
  className?: unknown
}

/** 只取白名单字段，其余一律丢弃 */
export function pickWhitelisted(raw: RawProfile): {
  studentId: string
  grade: string
  subject: string
  weaknesses: string[]
} {
  return {
    studentId: typeof raw.studentId === 'string' ? raw.studentId : '',
    grade: typeof raw.grade === 'string' ? raw.grade : '',
    subject: typeof raw.subject === 'string' ? raw.subject : '',
    weaknesses: Array.isArray(raw.weaknesses)
      ? raw.weaknesses.filter((w): w is string => typeof w === 'string')
      : [],
  }
}

export function ProfilePage(): JSX.Element {
  const { studentId } = useParams<{ studentId: string }>()
  const [rejected] = useState<string[]>([])

  // ⛔ C6 红线：渲染前对模型输出做敏感词兜底扫描
  const rawSample = useMemo(() => ({ studentId: studentId ?? '' }), [studentId])
  const safe = useMemo(() => pickWhitelisted(rawSample), [rawSample])
  void rejected

  return (
    <>
      <h1>学习画像</h1>
      <p className="muted">匿名编号：<code>{safe.studentId || '（缺失）'}</code></p>

      <DataUnavailable
        blockedBy="G6"
        feature="学习画像"
        reason="get_student_profile 仅为 Worker 侧工具，尚无面向前端的画像查询端点"
        eta="P3"
      >
        <BlockSkeleton title="薄弱点清单" rows={4} />
        <TableSkeleton columns={['知识点', '错误类型', '出现次数', '最近出现']} rows={4} />

        <section className="notice notice--info">
          <p>
            <strong>白名单字段仅四个</strong>：随机 studentId、年级、学科、薄弱点。
          </p>
          <p className="muted">
            即便后端将来返回姓名、学校、班级，前端也会拒绝渲染（纵深防御）。
            <code>pickWhitelisted()</code> 是这层防护的实现。
          </p>
        </section>
      </DataUnavailable>
    </>
  )
}

/* ── P7 Run 检索台 ────────────────────────────────────── */

export function RunSearchPage(): JSX.Element {
  const [filters] = useState<{
    status: string
    subject: string
    timeRange: string
  }>({ status: '', subject: '', timeRange: '' })
  void filters

  return (
    <>
      <h1>Run 检索台</h1>
      <p className="muted">按状态、时间、学科检索运行记录。</p>

      <DataUnavailable
        blockedBy="G7"
        feature="Run 检索"
        reason="后端无 run:read 端点，亦无分页/排序契约（无 page / size / sort 参数）"
        eta="P3"
      >
        {/*
          ⚠️ 刻意**不渲染假分页控件**：
          后端分页语义尚未冻结（规划建议游标分页，与 Last-Event-ID 一致）。
          先做一套页码分页控件，将来换游标时 UI 需重写。
          → 待契约冻结后接入，此处仅占位。
        */}
        <section className="filter-bar">
          <label>
            状态
            <select disabled defaultValue="">
              <option value="">全部</option>
            </select>
          </label>
          <label>
            学科
            <select disabled defaultValue="">
              <option value="">全部</option>
            </select>
          </label>
          <label>
            时间范围
            <select disabled defaultValue="">
              <option value="">全部</option>
            </select>
          </label>
        </section>

        <TableSkeleton
          columns={['运行标识', '状态', '学科', '发起时间', '耗时', '操作']}
          rows={6}
        />

        <p className="muted">
          ⚠️ 若用事件流反推列表，会得到<b>残缺且误导</b>的结果集
          （Worker 不写 run_event，仅有首条事件）。
        </p>
      </DataUnavailable>
    </>
  )
}