/**
 * 列表展示格式化（跨页共享的**展示口径**）。
 *
 * ⚠️ 为什么单独成文件：学科/年级是**后端 fail-closed 回填**的字段
 *    （不在业务词表内一律为 `null`），前端的显示口径必须与之一致——
 *    「有值就显示、没值就显示『—』」这条规则若在两处各写一遍，
 *    迟早会有一处漏掉「只有学科没有年级」的组合。
 */

/** 列表项里可能出现的学科/年级字段（两个都可为空）。 */
export interface SubjectGradeFields {
  subject: string | null
  grade: string | null
}

/**
 * 学科 · 年级 的显示文本。
 *
 * ⚠️ **两个字段都可能为空**：后端只回填业务词表内的取值，
 *    词表外一律给 `null`。所以这里必须处理「只有学科」「只有年级」
 *    「都没有」三种组合，不能想当然地拼 `学科 · 年级`。
 *
 * ⚠️ 空值显示为「—」而不是留白：留白在表格里会被误读成
 *    「这一行还没填」，而「—」明确表示**没有这项数据**。
 */
export function formatSubject(item: SubjectGradeFields): string {
  const parts = [item.subject, item.grade].filter((v): v is string => v !== null)
  return parts.length === 0 ? '—' : parts.join(' · ')
}
