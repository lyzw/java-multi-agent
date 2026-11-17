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

/**
 * 年级的**等价叫法**（P2-4 · 2026-11-17）。
 *
 * <p><b>为什么需要</b>：教师评审实测发现，同一个年级在两个页面叫法不同 ——
 * 发起辅导页是「初一」，知识检索页是「七年级」。学生看到会以为是
 * 两个不同的设置（乃至两个不同的年级），从而怀疑自己选错了。
 *
 * <p><b>为什么只改显示、不改取值</b>：
 * <ul>
 *   <li>辅导域的年级词表由后端 {@code TutoringVocabulary.GRADES} 决定
 *       （初一/初二/初三），且被 fail-closed 校验；</li>
 *   <li>检索域的年级是**教材版本坐标**（七年级/八年级/九年级），
 *       后端按 {@code displayName()} 精确匹配语料版本，改值即匹配不上。</li>
 * </ul>
 * 两个词表分属**不同领域**，取值都不能动；所以统一的是**给人看的文字**：
 * 显示「初一（七年级）」，取值仍按各域原值提交。
 */
const GRADE_ALIASES: Record<string, string> = {
  初一: '七年级',
  初二: '八年级',
  初三: '九年级',
  七年级: '初一',
  八年级: '初二',
  九年级: '初三',
}

/**
 * 年级的显示文本（带等价叫法）。
 *
 * <p>⚠️ 只影响**下拉选项的可见文字**；提交的取值保持原样。
 * 词表外的取值原样返回（fail-closed：不猜测、不改写）。
 */
export function gradeDisplayLabel(grade: string): string {
  const alias = GRADE_ALIASES[grade]
  return alias === undefined ? grade : `${grade}（${alias}）`
}
