/**
 * 模型正文的安全渲染（2026-10-28 端到端实测后补）。
 *
 * <p>实测现象：MiniMax 回带的讲解是 Markdown
 * （`**正确步骤：**`、`3x = 32`、段落之间空行），
 * 而此前页面用 `<p>{explanation}</p>` 原样输出 ——
 * 后果有两个：① `**` 与反引号**当字面量显示**，学生看到一排星号；
 * ② 空行与换行被 HTML 折叠，整段答案糊成一大块。
 *
 * <p><b>⚠️ 为什么不用 `dangerouslySetInnerHTML` 或现成 Markdown 库</b>：
 * 这段文字是**模型产出**，不是本地可信内容。这里只做两件小事 ——
 * 行内 `**粗体**` / `` `代码` `` 与保留换行 —— 且所有内容始终作为
 * **文本节点**交给 React（React 自身负责转义），
 * 因此不存在把模型输出当 HTML 执行的通道。
 *
 * <p>⛔ 刻意不支持标题、链接、表格、图片：
 * 链接会把学生带出应用，图片/表格不是讲解需要的表达，
 * 少一条解析规则就少一处可被模型文本误导的地方。
 */

import type { ReactNode } from 'react'

/** 行内标记：`**粗体**` 或 `代码`（均不跨行）。 */
const INLINE_TOKEN = /(\*\*[^*\n]+\*\*|`[^`\n]+`)/g

function inline(text: string): ReactNode[] {
  const nodes: ReactNode[] = []
  const re = new RegExp(INLINE_TOKEN.source, 'g')
  let cursor = 0
  let match: RegExpExecArray | null
  while ((match = re.exec(text)) !== null) {
    if (match.index > cursor) nodes.push(text.slice(cursor, match.index))
    const token = match[0]
    if (token.startsWith('**')) {
      nodes.push(<strong key={match.index}>{token.slice(2, -2)}</strong>)
    } else {
      nodes.push(<code key={match.index}>{token.slice(1, -1)}</code>)
    }
    cursor = match.index + token.length
  }
  nodes.push(text.slice(cursor))
  return nodes
}

export function ModelText({ text }: { text: string }): JSX.Element {
  return (
    <p className="model-text">
      {inline(text)}
    </p>
  )
}
