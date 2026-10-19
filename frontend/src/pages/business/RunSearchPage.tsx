/**
 * Run 检索台 P7（FE-D3）
 *
 * ⚠️ 刻意**不渲染假分页控件**：
 *   后端分页语义尚未冻结（规划建议游标分页，与 Last-Event-ID 一致）。
 *   先做一套页码分页控件，将来换游标时 UI 需重写。
 *   → 待契约冻结后接入。
 */

import { useState } from 'react'
import { Sheet, Unavailable, LoadingRows, Empty } from '@/shared/states'

const COLUMNS = ['运行标识', '状态', '学科', '发起时间', '耗时']

export function RunSearchPage(): JSX.Element {
  const [filters, setFilters] = useState({ status: '', subject: '', range: '' })

  return (
    <>
      <div className="main__head">
        <h1>检索记录</h1>
        <p className="main__lead">按状态、学科和时间找出分析过的题目。</p>
      </div>

      {/*
        筛选控件：可用但无数据。
        ⚠️ 这是诚实的做法——控件本身是确定的，只是数据源还没好。
      */}
      <div className="sheet">
        <div style={{ display: 'flex', gap: 'var(--s-4)', flexWrap: 'wrap' }}>
          <div className="field" style={{ margin: 0, minWidth: 160 }}>
            <label htmlFor="f-status">状态</label>
            <select
              id="f-status"
              value={filters.status}
              onChange={(e) => setFilters({ ...filters, status: e.target.value })}
            >
              <option value="">全部</option>
              <option value="QUEUED">排队中</option>
              <option value="RUNNING">分析中</option>
              <option value="SUCCEEDED">已完成</option>
              <option value="FAILED">失败</option>
              <option value="UNKNOWN">待核对</option>
              <option value="TIMED_OUT">超时</option>
            </select>
          </div>

          <div className="field" style={{ margin: 0, minWidth: 160 }}>
            <label htmlFor="f-subject">学科</label>
            <select
              id="f-subject"
              value={filters.subject}
              onChange={(e) => setFilters({ ...filters, subject: e.target.value })}
            >
              <option value="">全部</option>
              <option value="数学">数学</option>
              <option value="物理">物理</option>
              <option value="化学">化学</option>
              <option value="生物">生物</option>
              <option value="语文">语文</option>
              <option value="英语">英语</option>
            </select>
          </div>

          <div className="field" style={{ margin: 0, minWidth: 160 }}>
            <label htmlFor="f-range">时间范围</label>
            <select
              id="f-range"
              value={filters.range}
              onChange={(e) => setFilters({ ...filters, range: e.target.value })}
            >
              <option value="">不限</option>
              <option value="1d">最近一天</option>
              <option value="7d">最近七天</option>
              <option value="30d">最近三十天</option>
            </select>
          </div>
        </div>
      </div>

      <Unavailable
        feature="检索记录"
        blockedBy="G7"
        reason="后端还没有列表和分页接口，分页方式也没定"
        eta="P3"
      >
        <Sheet title="查询结果">
          {/* ⛔ 用加载态占位：明确「在读」，而不是空白 */}
          <LoadingRows columns={COLUMNS} rows={5} label="正在读取记录" />
        </Sheet>

        <p className="muted">
          接口接上后这里会列出记录。
          如果只显示部分记录却不说范围，容易被误当成全部，所以这一页在接口就绪前
          不会展示任何条目。
        </p>
      </Unavailable>
    </>
  )
}

/** 空态预览：接口正常但确实没有数据时的样子 */
export function SearchEmptyPreview(): JSX.Element {
  return (
    <Empty
      title="没有符合条件的记录"
      hint="放宽时间范围，或换个学科再试。"
    />
  )
}