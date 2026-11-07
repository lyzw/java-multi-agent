/**
 * 直接对话（真·LLM）回答正文的 Markdown + LaTeX 渲染器。
 *
 * <p><b>安全边界（fail-closed，平台控制台不放行模型输出的任何 HTML）</b>：
 * <ul>
 *   <li><b>不装 rehype-raw</b>：模型输出里的 <code>&lt;script&gt;</code> /
 *       <code>&lt;img onerror&gt;</code> 只会被当成文本丢弃，不进 DOM。</li>
 *   <li>链接走协议白名单（仅 http/https/mailto），外链一律
 *       <code>noopener noreferrer nofollow</code>；图片<b>不加载外部资源</b>，
 *       以「图片引用」文本展示，避免模型输出触发第三方请求 / 追踪像素。</li>
 *   <li>KaTeX 显式 <code>trust: false</code>（禁 <code>\href</code> / <code>\includegraphics</code>）、
 *       <code>throwOnError: false</code>（半截公式不抛异常、不红字）。</li>
 * </ul>
 *
 * <p><b>流式</b>：逐 token 到达时只渲染 <code>useDeferredValue</code> 合并后的低优先级快照，
 * 完成态立即用精确终稿；不伪造打字机，也不为「看起来流畅」丢 token。
 * 未闭合定界符由 {@link prepareAnswerText} 处理，半截公式按源码显示。
 */

import { memo, useDeferredValue, useMemo } from 'react'
import type { ComponentProps } from 'react'
import ReactMarkdown from 'react-markdown'
import type { Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import 'katex/dist/katex.min.css'
import { prepareAnswerText } from './markdownText'

type ReactMarkdownProps = ComponentProps<typeof ReactMarkdown>

const remarkPlugins: ReactMarkdownProps['remarkPlugins'] = [remarkGfm, remarkMath]

/**
 * 显式关掉 KaTeX 的信任能力。
 * 完成态若真有非法 LaTeX 用琥珀色提示；流式半截公式由 CSS 覆写成中性灰
 * （那是「还没写完」，不是错误，不该闪红）。
 */
const KATEX_OPTIONS = {
  output: 'htmlAndMathml',
  throwOnError: false,
  strict: 'ignore',
  trust: false,
  errorColor: '#b26a00',
} as const

const rehypePlugins: ReactMarkdownProps['rehypePlugins'] = [[rehypeKatex, KATEX_OPTIONS]]

const SAFE_HREF = /^(https?:\/\/|mailto:)/i

const components: Components = {
  a({ href, title, children }) {
    const safe = href !== undefined && SAFE_HREF.test(href) ? href : undefined
    return (
      <a href={safe} title={title} target="_blank" rel="noopener noreferrer nofollow">
        {children}
      </a>
    )
  },
  img({ src, alt }) {
    // 不发起外部图片请求；保留可核对的引用文本。
    return (
      <span className="ai-chat__image-ref" title={src}>
        [图片：{alt || '无替代文本'}]{src ? ` ${src}` : ''}
      </span>
    )
  },
  pre({ children }) {
    return <pre className="ai-chat__code-block">{children}</pre>
  },
  table({ children }) {
    return (
      <div className="ai-chat__table-wrap">
        <table>{children}</table>
      </div>
    )
  },
}

export interface MarkdownAnswerProps {
  /** 模型原始正文（累计增量，只读） */
  content: string
  /** 是否仍在接收 token */
  streaming?: boolean
}

export const MarkdownAnswer = memo(function MarkdownAnswer({
  content,
  streaming = false,
}: MarkdownAnswerProps): JSX.Element {
  // 逐 token 期间用低优先级快照，避免每个 delta 都做一次全量 AST 解析；
  // done 一到立即切回精确终稿，绝不让用户停在旧快照上。
  const deferred = useDeferredValue(content)
  const visible = streaming ? deferred : content
  const text = useMemo(() => prepareAnswerText(visible, streaming), [visible, streaming])

  return (
    <div
      className={`ai-chat__markdown${streaming ? ' ai-chat__markdown--streaming' : ''}`}
      data-testid="ai-chat-markdown"
    >
      <ReactMarkdown
        remarkPlugins={remarkPlugins}
        rehypePlugins={rehypePlugins}
        components={components}
      >
        {text}
      </ReactMarkdown>
    </div>
  )
})
