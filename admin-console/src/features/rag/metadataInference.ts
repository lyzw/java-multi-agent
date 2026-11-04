/**
 * 元数据自动推断（交互文档 §4.3「产出确认」/ V1.0.2 §4 Z4）。
 *
 * <p><b>为什么需要它</b>：当前登记表单要求用户手填「类型/标题/出版社/ISBN/DOI/
 * 载体类型」等 8 个字段，其中**多半是系统本就能从文件名推断的**。
 * 把可推断的信息交给用户手填，是"操作难度高"的主要来源之一。
 *
 * <p><b>⚠️ 推断值一律是「建议」而非「事实」</b>（V1.0.2 §4 Z4 原文：
 * 「回填表单供人确认，不直接落库为权威值」）。调用方必须：
 * <ol>
 *   <li>把推断值标为「待确认」（UI 上打 ✨）；</li>
 *   <li>未经人确认不得提交审核（由后端 {@code metadata_confirmed} 把关）。</li>
 * </ol>
 *
 * <p><b>⚠️ 纯函数，不读网络、不读 DOM</b>：这样才能被单测穷举，
 * 也才能在「重算推断值」时幂等——推断错了一律重算，不叠加。
 */

/** 推断出的元数据（每项都可能为 null = 没推断出来）。 */
export interface InferredMetadata {
  /** 书名（去扩展名、去常见噪声后缀） */
  title: string | null
  /** 出版社（从文件名括号/后缀中识别） */
  publisher: string | null
  /** ISBN（文件名中的 10/13 位数字，经校验位验证才采信） */
  isbn: string | null
  /** 学科 */
  subject: string | null
  /** 年级 */
  grade: string | null
  /**
   * 册次。
   *
   * ⚠️ 只取 DDL 白名单的三种值：上册 / 下册 / 全一册。
   *    推断不出就是 null——**不要**猜一个「上册」，
   *    因为册次错了会导致同名两册的版本坐标冲突（DUP_EDITION）。
   */
  term: '上册' | '下册' | '全一册' | null
  /** 每个字段是否由系统推断（用于 UI 打 ✨ 标记） */
  inferredFields: ReadonlySet<keyof InferredMetadata>
}

/** 学科词表（与 rag_edition.subject 的常见取值对齐）。 */
const SUBJECTS = [
  '语文',
  '数学',
  '英语',
  '物理',
  '化学',
  '生物',
  '历史',
  '地理',
  '政治',
  '道德与法治',
  '科学',
  '信息技术',
] as const

/**
 * 年级词表。
 *
 * ⚠️ 顺序无关：匹配时取**最长命中**，避免「一年级」抢掉「一年级」之外的
 *    「九年级」之类（中文数字长度相同，但「初一年级」这类写法需要整体匹配）。
 */
const GRADES = [
  '一年级',
  '二年级',
  '三年级',
  '四年级',
  '五年级',
  '六年级',
  '七年级',
  '八年级',
  '九年级',
  '初一',
  '初二',
  '初三',
  '高一',
  '高二',
  '高三',
] as const

/**
 * 常见出版社识别词。
 *
 * ⚠️ 刻意做成「包含即命中」而不是精确匹配：文件名里的出版社常被简写
 *    （「人教社」「人教」都指人民教育出版社）。
 * ⚠️ 命中多条时取**最长**的，避免「教育出版社」抢掉「上海教育出版社」。
 */
const PUBLISHERS: readonly { key: string; name: string }[] = [
  { key: '人民教育出版社', name: '人民教育出版社' },
  { key: '人教版', name: '人民教育出版社' },
  { key: '人教社', name: '人民教育出版社' },
  { key: '人教', name: '人民教育出版社' },
  { key: '上海教育出版社', name: '上海教育出版社' },
  { key: '沪教版', name: '上海教育出版社' },
  { key: '沪教', name: '上海教育出版社' },
  { key: '北京师范大学出版社', name: '北京师范大学出版社' },
  { key: '北师大', name: '北京师范大学出版社' },
  { key: '华东师范大学出版社', name: '华东师范大学出版社' },
  { key: '华师大', name: '华东师范大学出版社' },
  { key: '外研社', name: '外语教学与研究出版社' },
  { key: '外语教学与研究出版社', name: '外语教学与研究出版社' },
  { key: '江苏凤凰教育出版社', name: '江苏凤凰教育出版社' },
  { key: '苏教版', name: '江苏凤凰教育出版社' },
  { key: '苏教', name: '江苏凤凰教育出版社' },
  { key: '浙江教育出版社', name: '浙江教育出版社' },
  { key: '浙教版', name: '浙江教育出版社' },
]

/** 文件名里的常见噪声（下载重复后缀、副本标记、扫描标识）。 */
const NOISE_PATTERNS: readonly RegExp[] = [
  /\s*\(\d+\)\s*$/u, // "xxx(1)"
  /\s*（\d+）\s*$/u, // "xxx（1）"
  /[-_\s]*(副本|扫描版|扫描件|高清版|电子版|最新版)\s*$/u,
  /[-_\s]*(copy|final|v\d+)\s*$/iu,
]

/**
 * 从文件名推断元数据。
 *
 * @param fileName 原始文件名（含扩展名）
 * @returns 推断结果；`inferredFields` 标明哪些字段是推出来的
 */
export function inferMetadataFromFileName(fileName: string): InferredMetadata {
  const empty: InferredMetadata = {
    title: null,
    publisher: null,
    isbn: null,
    subject: null,
    grade: null,
    term: null,
    inferredFields: new Set(),
  }
  if (!fileName || fileName.trim() === '') {
    return empty
  }

  // 1) 去扩展名
  const base = stripExtension(fileName.trim())
  if (base === '') {
    return empty
  }

  // 2) 去噪声后缀（反复剥离，如 "xxx(1) 副本"）
  let cleaned = base
  let changed = true
  while (changed) {
    changed = false
    for (const pattern of NOISE_PATTERNS) {
      const next = cleaned.replace(pattern, '')
      if (next !== cleaned) {
        cleaned = next
        changed = true
      }
    }
  }
  cleaned = cleaned.replace(/[_]+/g, ' ').replace(/\s{2,}/g, ' ').trim()

  const inferred = new Set<keyof InferredMetadata>()

  // 3) ISBN：优先 13 位（含连字符），其次 10 位；校验位通过才采信
  const isbnRaw = findIsbn(cleaned)
  let titleSource = cleaned
  if (isbnRaw) {
    inferred.add('isbn')
    // ⚠️ **只剥掉匹配到的字符，不要把分隔符一起吃掉**。
    //    正则允许空格/下划线作分隔，直接 replace(raw) 会把
    //    「上册_9787544400008」整段吃掉，连带吞掉前后片段间的界，
    //    后面的「出版社/学科」识别就再也匹配不到了（实测踩到：
    //    书名被推成 ISBN 本身）。
    //    这里从 raw 里取出真实命中的子串再单独剥离。
    // ⚠️ 用「命中前的文本 + 命中后的文本」重组，而不是按 raw 做字符串替换：
    //    raw 的首尾带着被正则吞进来的分隔符（如 `_9787544400008`），
    //    直接替换会把分隔符一起吃掉，`沪教版...上册` 与后续片段就粘连了。
    titleSource =
      cleaned.slice(0, isbnRaw.start) + ' ' + cleaned.slice(isbnRaw.end)
    titleSource = titleSource
      .replace(/[_]+/g, ' ')
      .replace(/\s{2,}/g, ' ')
      .trim()
  }

  // 4) 出版社：最长命中
  const publisher = matchLongest(titleSource, PUBLISHERS.map((p) => p.key))
  if (publisher) {
    inferred.add('publisher')
  }

  // 5) 学科 / 年级 / 册次
  const subject = SUBJECTS.find((s) => titleSource.includes(s)) ?? null
  if (subject) {
    inferred.add('subject')
  }
  const grade = matchLongest(titleSource, GRADES)
  if (grade) {
    inferred.add('grade')
  }
  const term = matchTerm(titleSource)
  if (term) {
    inferred.add('term')
  }

  // 6) 书名：去掉已被识别为其它字段的片段后剩下的主干
  const title = deriveTitle(titleSource, {
    // ⚠️ 传入**所有**命中的出版社键：一行里常有「沪教版 ... 上海教育出版社」
    publisherKeys: matchAllKeys(titleSource, PUBLISHERS.map((p) => p.key)),
    subject,
    grade,
    term,
  })
  if (title) {
    inferred.add('title')
  }

  return {
    title,
    publisher: publisher ? normalizePublisher(publisher) : null,
    isbn: isbnRaw ? normalizeIsbn(isbnRaw.value) : null,
    subject,
    grade,
    term,
    inferredFields: inferred,
  }
}

/** 去扩展名（只认常见的几种，其余原样）。 */
function stripExtension(name: string): string {
  const dot = name.lastIndexOf('.')
  if (dot <= 0) return name
  const ext = name.slice(dot).toLowerCase()
  const known = ['.pdf', '.epub', '.txt', '.md', '.html', '.htm', '.docx', '.doc', '.png', '.jpg', '.jpeg']
  return known.includes(ext) ? name.slice(0, dot) : name
}

/** 在一组候选词里取**最长**命中。 */
function matchLongest(text: string, candidates: readonly string[]): string | null {
  let best: string | null = null
  for (const candidate of candidates) {
    if (text.includes(candidate) && (best === null || candidate.length > best.length)) {
      best = candidate
    }
  }
  return best
}

/** 返回文本中**所有**命中的候选词。 */
function matchAllKeys(text: string, candidates: readonly string[]): string[] {
  return candidates.filter((c) => text.includes(c))
}

/** 册次：只认 DDL 白名单三种。 */
function matchTerm(text: string): '上册' | '下册' | '全一册' | null {
  // ⚠️ 先匹配「全一册」，否则「一册」可能被别的规则误伤
  if (text.includes('全一册')) return '全一册'
  if (text.includes('上册')) return '上册'
  if (text.includes('下册')) return '下册'
  return null
}

/** 出版社简称 → 全名。 */
function normalizePublisher(key: string): string {
  return PUBLISHERS.find((p) => p.key === key)?.name ?? key
}

/**
 * 在文本中找 ISBN。
 *
 * <p>⚠️ **校验位必须通过**才采信：文件名里的一串数字常常是页码、
 *    期号或内部编号，不是 ISBN。宁可推断不出（留空给人填），
 *    也不要填一个错误编号——错误的 ISBN 会误导去重与审核。
 */
function findIsbn(
  text: string,
): { raw: string; value: string; start: number; end: number } | null {
  // ── ISBN-13：先把「连续的 ISBN 状字符块」整体圈出来，再按数字个数精确判定 ──
  //    ⚠️ 不能写成 /97[89][\d\-\s]{10,18}\d/：`[\d\s]{10,18}` 会把末位数字
  //       一起吞进量词，导致 digits 变成 14 位而校验失败（实测踩到：
  //       9787544400008 被漏判成「不是 ISBN」）。
  //       正确做法是「先粗圈、再去符号数位数」。
  const isbn13 = /97[89][\d\-\s]{10,20}/g
  for (const match of text.matchAll(isbn13)) {
    // 去掉尾部多余的分隔符，避免把后面的下划线/空格算进区间
    const raw = match[0].replace(/[\s\-]+$/, '')
    const digits = raw.replace(/[^\d]/g, '')
    if (digits.length === 13 && isValidIsbn13(digits)) {
      return {
        raw,
        value: digits,
        start: match.index,
        end: match.index + raw.length,
      }
    }
  }
  // ── ISBN-10：末位可为 X ──
  const isbn10 = /(?<![\dXx])\d[\d\-\s]{8,12}[\dXx](?![\dXx])/g
  for (const match of text.matchAll(isbn10)) {
    const raw = match[0]
    const digits = raw.replace(/[^\dXx]/g, '')
    if (digits.length === 10 && isValidIsbn10(digits)) {
      return {
        raw,
        value: digits,
        start: match.index,
        end: match.index + raw.length,
      }
    }
  }
  return null
}

/**
 * ISBN-13 校验（模 10，权重 1/3 交替）。
 *
 * @param digits 13 位纯数字
 */
export function isValidIsbn13(digits: string): boolean {
  if (!/^\d{13}$/.test(digits)) return false
  let sum = 0
  for (let i = 0; i < 12; i += 1) {
    const d = Number(digits[i])
    sum += i % 2 === 0 ? d : d * 3
  }
  const check = (10 - (sum % 10)) % 10
  return check === Number(digits[12])
}

/**
 * ISBN-10 校验（模 11，X = 10）。
 *
 * @param digits 10 位，末位可为 X
 */
export function isValidIsbn10(digits: string): boolean {
  if (!/^\d{9}[\dXx]$/.test(digits)) return false
  let sum = 0
  for (let i = 0; i < 10; i += 1) {
    const ch = digits[i]
    const d = ch === 'X' || ch === 'x' ? 10 : Number(ch)
    sum += (10 - i) * d
  }
  return sum % 11 === 0
}

/** ISBN 展示格式：统一去连字符（与后端比较口径一致）。 */
function normalizeIsbn(value: string): string {
  return value.replace(/[^\dXx]/g, '').toUpperCase()
}

/**
 * 由清洗后的文件名推导书名。
 *
 * <p>做法：把已识别为「出版社/学科/年级/册次」的**全部出现**从标题里剔掉，
 * 剩下的主干就是书名。**剔不干净也没关系**——推断值只作建议，
 * 人会在确认界面里改（这正是「确认」步骤存在的意义）。
 *
 * <p>⚠️ 若剔完后**什么都不剩**（文件名本身就是「沪教版英语六年级上册」这种
 *    全由可识别片段组成的串），则**回落到原串**而不是返回 null：
 *    书名为空会让确认表单出现一个必须人肉重填的空框，
 *    而原串至少是一份可读、可改的初值。
 */
function deriveTitle(
  text: string,
  parts: {
    publisherKeys: readonly string[]
    subject: string | null
    grade: string | null
    term: string | null
  },
): string | null {
  let title = text
  const removable = [...parts.publisherKeys, parts.subject, parts.grade, parts.term].filter(
    (v): v is string => v !== null && v !== '',
  )
  for (const piece of removable) {
    // ⚠️ 去掉**所有**出现：一行里可能同时有「沪教版」与「上海教育出版社」
    title = title.split(piece).join(' ')
  }
  title = title
    .replace(/[[\]【】()（）<>《》]/g, ' ')
    .replace(/[\s\-_·]+/g, ' ')
    .trim()

  if (title !== '') {
    return title
  }
  // 全被剔空：回落到原串（去标点后），保证表单有可读初值
  const fallback = text
    .replace(/[[\]【】()（）<>《》]/g, ' ')
    .replace(/[\s\-_·]+/g, ' ')
    .trim()
  return fallback === '' ? null : fallback
}

/**
 * 把一个文件推断包成「表单初值」。
 *
 * <p>⚠️ 与 {@link inferMetadataFromFileName} 分开：后者是纯推断，
 * 本函数负责**缺省值兜底**（推断不出书名时回落到原文件名主干），
 * 让确认表单永远有一份可读的初值，而不是一片空白。
 */
export function toFormDefaults(
  fileName: string,
  inferred: InferredMetadata,
): {
  title: string
  publisher: string
  isbn: string
  subject: string
  grade: string
  term: string
} {
  const fallbackTitle = stripExtension(fileName.trim())
  return {
    title: inferred.title ?? fallbackTitle,
    publisher: inferred.publisher ?? '',
    isbn: inferred.isbn ?? '',
    subject: inferred.subject ?? '',
    grade: inferred.grade ?? '',
    term: inferred.term ?? '',
  }
}
