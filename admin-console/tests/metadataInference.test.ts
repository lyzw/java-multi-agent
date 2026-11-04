import { describe, it, expect } from 'vitest'
import {
  inferMetadataFromFileName,
  isValidIsbn10,
  isValidIsbn13,
  toFormDefaults,
} from '@/features/rag/metadataInference'

/**
 * 元数据自动推断（交互文档 §4.3 / V1.0.2 §4 Z4）。
 *
 * ⚠️ 守的两条：
 * <ol>
 *   <li><b>能推断的要推断对</b>——这是「把 8 个字段降成 3 个」的前提；</li>
 *   <li><b>推断不出就留空</b>——宁可让人填，也不要猜一个错值。
 *       被猜错的 ISBN 会误导去重与审核，比空白危险得多。</li>
 * </ol>
 */

describe('ISBN 校验位', () => {
  it('ISBN-13 校验位正确才通过', () => {
    // 9787544400000 的校验位是 3
    expect(isValidIsbn13('9787544400008')).toBe(true)
    // 同前缀换掉校验位 → 必须失败
    expect(isValidIsbn13('9787544400000')).toBe(false)
  })

  it('ISBN-10 校验位正确才通过', () => {
    expect(isValidIsbn10('0306406152')).toBe(true)
    expect(isValidIsbn10('0306406153')).toBe(false)
    // 末位 X = 10
    expect(isValidIsbn10('097522980X')).toBe(true)
  })

  it('位数不对一律不通过', () => {
    expect(isValidIsbn13('978754440000')).toBe(false)
    expect(isValidIsbn10('123')).toBe(false)
  })
})

describe('文件名元数据推断', () => {
  it('识别书名、出版社、学科、年级、册次', () => {
    const r = inferMetadataFromFileName('沪教版英语六年级上册.pdf')

    expect(r.publisher).toBe('上海教育出版社')
    expect(r.subject).toBe('英语')
    expect(r.grade).toBe('六年级')
    expect(r.term).toBe('上册')
    // 书名剥离出版社/学科后剩下的主干（不空即可，允许人再改）
    expect(r.title).toBeTruthy()
  })

  it('人教版数学七年级上册', () => {
    const r = inferMetadataFromFileName('人教版数学七年级上册.pdf')

    expect(r.publisher).toBe('人民教育出版社')
    expect(r.subject).toBe('数学')
    expect(r.grade).toBe('七年级')
    expect(r.term).toBe('上册')
  })

  it('从文件名提取并通过校验的 ISBN', () => {
    const r = inferMetadataFromFileName('沪教版英语六年级上册_9787544400008.pdf')

    expect(r.isbn).toBe('9787544400008')
    expect(r.inferredFields.has('isbn')).toBe(true)
    // ⚠️ ISBN 不应残留在书名里
    expect(r.title).not.toContain('9787544400008')
  })

  it('⛔ ISBN 校验位不对时不采信（宁可留空）', () => {
    // 这串数字凑够 13 位但校验位错，多半是内部编号
    const r = inferMetadataFromFileName('教材_1234567890123.pdf')

    expect(r.isbn).toBeNull()
  })

  it('剥离常见噪声后缀（副本/下载重复标记）', () => {
    const r = inferMetadataFromFileName('沪教版英语六年级上册(1) 副本.pdf')

    expect(r.term).toBe('上册')
    expect(r.title).not.toContain('副本')
    expect(r.title).not.toContain('(1)')
  })

  it('识别「全一册」优先于「下册」之类误判', () => {
    expect(inferMetadataFromFileName('苏教版科学全一册.pdf').term).toBe('全一册')
  })

  it('识别初中年级写法（初一/初二）', () => {
    expect(inferMetadataFromFileName('人教版语文初二上册.pdf').grade).toBe('初二')
  })

  it('出版社：取最长命中（不把「上海教育出版社」降级成「教育出版社」）', () => {
    const r = inferMetadataFromFileName('上海教育出版社数学五年级下册.pdf')
    expect(r.publisher).toBe('上海教育出版社')
  })

  it('推断不出的字段一律为 null，不猜', () => {
    const r = inferMetadataFromFileName('my-notes.pdf')

    expect(r.publisher).toBeNull()
    expect(r.subject).toBeNull()
    expect(r.grade).toBeNull()
    expect(r.term).toBeNull()
    expect(r.isbn).toBeNull()
  })

  it('空文件名不抛异常', () => {
    const r = inferMetadataFromFileName('')
    expect(r.title).toBeNull()
    expect(r.inferredFields.size).toBe(0)
  })

  it('inferredFields 如实反映哪些字段是推出来的', () => {
    const r = inferMetadataFromFileName('人教版数学七年级上册.pdf')

    expect(r.inferredFields.has('publisher')).toBe(true)
    expect(r.inferredFields.has('subject')).toBe(true)
    expect(r.inferredFields.has('grade')).toBe(true)
    expect(r.inferredFields.has('term')).toBe(true)
    // 没有 ISBN
    expect(r.inferredFields.has('isbn')).toBe(false)
  })
})

describe('表单初值', () => {
  it('推断出书名时用推断值', () => {
    const fileName = '沪教版英语六年级上册.pdf'
    const d = toFormDefaults(fileName, inferMetadataFromFileName(fileName))
    expect(d.title).toBeTruthy()
    expect(d.publisher).toBe('上海教育出版社')
    expect(d.term).toBe('上册')
  })

  it('推断不出书名时回落到文件名主干（表单永远有可读初值）', () => {
    const fileName = 'my-notes.pdf'
    const d = toFormDefaults(fileName, inferMetadataFromFileName(fileName))
    expect(d.title).toBe('my notes')
    expect(d.publisher).toBe('')
  })
})
