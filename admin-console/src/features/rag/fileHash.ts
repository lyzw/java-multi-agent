/**
 * 浏览器侧 SHA-256（即时展示用，非权威）。
 *
 * ⚠️ 前端算的哈希只用于「上传前让管理员看到文件指纹」，<b>绝不作为落库依据</b>：
 *    后端 `ObjectStorePort.sha256()` 会重算内容摘要，前端值若被篡改也不会进库。
 *    因此这里失败（如非安全上下文下 `crypto.subtle` 不可用）不应阻断上传，
 *    调用方应把异常当作「暂不显示预览」处理。
 */

/** 计算文件的 SHA-256，返回小写十六进制（64 位）。 */
export async function computeSha256Hex(file: File): Promise<string> {
  const buf = await file.arrayBuffer()
  const digest = await crypto.subtle.digest('SHA-256', buf)
  const bytes = new Uint8Array(digest)
  let out = ''
  for (const b of bytes) {
    out += b.toString(16).padStart(2, '0')
  }
  return out
}
