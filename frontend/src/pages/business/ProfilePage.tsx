/**
 * 学习画像页 P6（FE-D8 接线）
 *
 * ⛔ 白名单渲染（纵深防御）：
 *    即便后端将来返回姓名/学校/班级，**前端也应拒绝渲染**。
 *    依据：PIPL 采集白名单仅 studentId / 年级 / 学科 / 薄弱点。
 *
 * ⚠️ FE-D8 修正：`weaknesses` 由 `string[]` 改为**结构化对象**白名单
 *    （后端 G6 冻结为 `{knowledgePoint, errorType, confidence, createdAt}`）。
 *    旧实现按 `string[]` 过滤 → 会**丢掉全部条目**（FE-D09 L-4）。
 *
 * ⚠️ `notFound` 是**合法状态**（后端对不存在的学生返回 200 + notFound:true，
 *    不用 404 以免构成 studentId 枚举通道）→ 页面据此显示「找不到」，
 *    **不是**错误页。
 */

import { useMemo } from 'react'
import { useParams } from 'react-router-dom'
import { Sheet, Empty, Loading, ErrorState } from '@/shared/states'
import { useProfile } from '@/features/profile/useProfile'
import type { ProfileResponse } from '@/api/types'

/** 单条薄弱点的白名单视图（只保留契约内的 4 个字段）。 */
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
          // ⚠️ 元素可能为 null / 字符串 / 数字（后端契约外的形态）。
          //    直接取 w.knowledgePoint 会在 null 上抛 TypeError → 整页白屏。
          //    故先判对象，再逐字段判类型。
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
        <div className="main__head">
          <h1>学习画像</h1>
        </div>
        <Loading label="正在读取画像" />
      </>
    )
  }

  if (error !== null) {
    return (
      <>
        <div className="main__head">
          <h1>学习画像</h1>
          <p className="main__lead">
            匿名编号 <code>{studentId ?? '缺失'}</code>
          </p>
        </div>
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
        <div className="main__head">
          <h1>学习画像</h1>
          <p className="main__lead">
            匿名编号 <code>{safe.studentId || studentId || '缺失'}</code>
          </p>
        </div>
        <Empty
          title="找不到这个匿名编号"
          hint="画像按匿名编号保存；请确认编号是否与发起辅导时一致。"
        />
      </>
    )
  }

  return (
    <>
      <div className="main__head">
        <h1>学习画像</h1>
        <p className="main__lead">
          匿名编号 <code>{safe.studentId || '缺失'}</code>
        </p>
      </div>

      <Sheet title="薄弱点">
        {safe.weaknesses.length === 0 ? (
          // ⛔ 真实空态：错因需要模型实际调用记录工具才会产生。
          <Empty
            title="还没有记录到薄弱点"
            hint="辅导过的题目会在这里汇总，重复错的知识点排在前面。"
          />
        ) : (
          <ul className="entry-list">
            {safe.weaknesses.map((w, index) => (
              <li className="entry" key={`${w.knowledgePoint}-${index}`}>
                <div className="entry__title">{w.knowledgePoint}</div>
                <div className="entry__desc">
                  {w.errorType}
                  {w.confidence !== null ? ` · 置信度 ${w.confidence}` : ''}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Sheet>

      <Sheet title="基本信息">
        <div className="status-line" style={{ flexWrap: 'wrap', gap: 12 }}>
          <span className="muted">年级</span>
          <span className="chip">{safe.grade || '未填写'}</span>
          <span className="muted">学科</span>
          <span className="chip">{safe.subject || '未填写'}</span>
        </div>
      </Sheet>

      {/*
        ⛔ C6：结构上不显示姓名/学校/班级/联系方式——
        画像是学生维度数据，界面只呈现 DEC-6 白名单允许的字段。
      */}
    </>
  )
}
