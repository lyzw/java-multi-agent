/**
 * 学习画像页（演进重做 · 保留白名单渲染纪律）
 *
 * ⛔ 白名单渲染（纵深防御）：即便后端返回姓名/学校/班级，前端也拒绝渲染。
 *    依据：PIPL 采集白名单仅 studentId / 年级 / 学科 / 薄弱点。
 *
 * ⚠️ `notFound` 是合法状态（后端 200 + notFound:true，不用 404 以免构成
 *    studentId 枚举通道）→ 页面据此显示「找不到」，不是错误页。
 */

import { useMemo } from 'react'
import { useParams } from 'react-router-dom'
import { Empty, Loading, ErrorState, Breadcrumbs } from '@/shared/states'
import { useProfile } from '@/features/profile/useProfile'
import type { ProfileResponse } from '@/api/types'
import { PageHead, Panel } from '@/components/ui'

/** 单条薄弱点的白名单视图（只保留契约内的字段）。 */
export interface SafeWeakness {
  knowledgePoint: string
  errorType: string
  confidence: number | null
}

/**
 * 只取白名单字段，其余一律丢弃。
 *
 * ⚠️ **不接受自由文本的知识点/错因**：契约里二者是受控字段，
 *    任何非字符串值一律降级为空串（不原样透传到界面）。
 */
export function pickWhitelisted(raw: ProfileResponse): {
  studentId: string
  grade: string
  subject: string
  notFound: boolean
  weaknesses: SafeWeakness[]
} {
  return {
    studentId: typeof raw.studentId === 'string' ? raw.studentId : '',
    grade: typeof raw.grade === 'string' ? raw.grade : '',
    subject: typeof raw.subject === 'string' ? raw.subject : '',
    notFound: raw.notFound === true,
    weaknesses: Array.isArray(raw.weaknesses)
      ? raw.weaknesses.map((w) => {
          if (w === null || typeof w !== 'object') {
            return { knowledgePoint: '', errorType: '', confidence: null }
          }
          return {
            knowledgePoint:
              typeof w.knowledgePoint === 'string' ? w.knowledgePoint : '',
            errorType: typeof w.errorType === 'string' ? w.errorType : '',
            confidence: typeof w.confidence === 'number' ? w.confidence : null,
          }
        })
      : [],
  }
}

export function ProfilePage(): JSX.Element {
  const { studentId } = useParams<{ studentId: string }>()
  const { profile, loading, error, reload } = useProfile(studentId)

  const safe = useMemo(
    () =>
      profile === null
        ? { studentId: '', grade: '', subject: '', notFound: false, weaknesses: [] }
        : pickWhitelisted(profile),
    [profile],
  )

  if (loading) {
    return (
      <>
        <PageHead title="学习画像" />
        <Loading label="正在读取画像" />
      </>
    )
  }

  if (error !== null) {
    return (
      <>
        <Breadcrumbs
          items={[{ label: '学习画像', to: '/profile' }, { label: studentId ?? '画像' }]}
        />
        <PageHead
          title="学习画像"
          lead={
            <>
              匿名编号 <code>{studentId ?? '缺失'}</code>
            </>
          }
        />
        <ErrorState
          reason={error}
          next="可能是网络问题，或这个匿名编号还没被辅导过。"
          onRetry={reload}
        />
      </>
    )
  }

  if (safe.notFound) {
    return (
      <>
        <Breadcrumbs
          items={[
            { label: '学习画像', to: '/profile' },
            { label: safe.studentId || studentId || '画像' },
          ]}
        />
        <PageHead
          title="学习画像"
          lead={
            <>
              匿名编号 <code>{safe.studentId || studentId || '缺失'}</code>
            </>
          }
        />
        <Empty
          title="找不到这个匿名编号"
          hint="画像按匿名编号保存；请确认编号是否与发起辅导时一致。"
        />
      </>
    )
  }

  return (
    <>
      <Breadcrumbs
        items={[{ label: '学习画像', to: '/profile' }, { label: safe.studentId || '画像' }]}
      />
      <PageHead
        title="学习画像"
        lead={
          <>
            匿名编号 <code>{safe.studentId || '缺失'}</code>
          </>
        }
      />

      <Panel title="薄弱点" icon="activity" note="重复错的知识点排在前面。">
        {safe.weaknesses.length === 0 ? (
          <Empty
            title="还没有记录到薄弱点"
            hint="辅导过的题目会在这里汇总，重复错的知识点排在前面。"
          />
        ) : (
          <ul className="entry-list">
            {safe.weaknesses.map((w, index) => (
              <li className="entry entry--card" key={`${w.knowledgePoint}-${index}`}>
                <span>
                  <span className="entry__title">{w.knowledgePoint}</span>
                  <span className="entry__desc">
                    {w.errorType}
                    {w.confidence !== null ? ` · 置信度 ${w.confidence}` : ''}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </Panel>

      <Panel title="基本信息" icon="info">
        <div className="status-line" style={{ flexWrap: 'wrap', gap: 12 }}>
          <span className="muted">年级</span>
          <span className="chip">{safe.grade || '未填写'}</span>
          <span className="muted">学科</span>
          <span className="chip">{safe.subject || '未填写'}</span>
        </div>
      </Panel>

      {/*
        ⛔ C6：结构上不显示姓名/学校/班级/联系方式——
        画像是学生维度数据，界面只呈现 DEC-6 白名单允许的字段。
      */}
    </>
  )
}
