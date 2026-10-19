/**
 * 学习画像页 P6（FE-D3）
 *
 * ⛔ 白名单渲染（纵深防御）：
 *    即便后端将来返回姓名/学校/班级，**前端也应拒绝渲染**。
 *    依据：PIPL 采集白名单仅 studentId / 年级 / 学科 / 薄弱点。
 */

import { useMemo } from 'react'
import { useParams } from 'react-router-dom'
import { Sheet, Unavailable, Empty, Marked } from '@/shared/states'

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
  const safe = useMemo(() => pickWhitelisted({ studentId: studentId ?? '' }), [studentId])

  return (
    <>
      <div className="main__head">
        <h1>学习画像</h1>
        <p className="main__lead">
          匿名编号 <code>{safe.studentId || '缺失'}</code>
        </p>
      </div>

      <Unavailable
        feature="学习画像"
        blockedBy="G6"
        reason="读取画像的工具只给分析流程用，还没有给页面用的接口"
        eta="P3"
      >
        <Sheet title="薄弱点">
          <Empty
            title="还没有记录到薄弱点"
            hint="辅导过的题目会在这里汇总，重复错的知识点排在前面。"
          />
        </Sheet>

        <Sheet title="基本信息">
          <div className="status-line" style={{ flexWrap: 'wrap', gap: 12 }}>
            <span className="muted">年级</span>
            <span className="chip">{safe.grade || '未填写'}</span>
            <span className="muted">学科</span>
            <span className="chip">{safe.subject || '未填写'}</span>
          </div>
        </Sheet>

        <Marked label="为什么这里看不到更多">
          <p className="muted" style={{ margin: 0 }}>
            我们只采集四类信息：随机匿名编号、年级、学科、薄弱点。
            不收姓名、学校、班级，也不收定位和联系方式。
          </p>
        </Marked>
      </Unavailable>
    </>
  )
}