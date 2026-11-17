/**
 * 模型正文的 Markdown + LaTeX 渲染（2026-11-17 · 教师评审 P0-3）。
 *
 * <p><b>为什么必须有这一层</b>：教师评审实测，归因讲解里大量
 * {@code $$2(x+3)=2x+6$$}、{@code | 维度 | 判断 |} 表格、{@code ##} / {@code ###}
 * 标题**原样显示**（页面里 0 个公式渲染节点）——对初中生就是乱码。
 * 此前 {@code ModelText} 只认粗体与行内代码，其余全是字面量。
 *
 * <p><b>安全边界（fail-closed）</b>：模型输出是**不可信内容**，绝不进 DOM 的 HTML 通道。
 * <ul>
 *   <li><b>不装 rehype-raw</b>：{@code <script>} / {@code <img onerror>} 只被当文本丢弃。</li>
 *   <li>链接走协议白名单（仅 http/https/mailto），外链一律
 *       {@code noopener noreferrer nofollow}；<b>图片不加载外部资源</b>
 *       （避免模型输出触发第三方请求 / 追踪像素），改以「图片引用」文本展示。</li>
 *   <li>KaTeX 显式 {@code trust: false}（禁 \href / \includegraphics）、
 *       {@code throwOnError: false}（半截公式不抛异常、不红字）。</li>
 * </ul>
 *
 * <p><b>流式</b>：逐 token 到达时用 {@code useDeferredValue} 的低优先级快照，
 * 完成态立即切精确终稿；未闭合定界符由 {@link prepareAnswerText} 处理
 * （半截公式按源码显示，不闪红、不吞正文）。
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
 * 非法 LaTeX 用琥珀色提示；流式半截公式按源码显示（那是「还没写完」，不是错误）。
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
      <span className="model-markdown__image-ref" title={src}>
        [图片：{alt || '无替代文本'}]{src ? ` ${src}` : ''}
      </span>
    )
  },
  pre({ children }) {
    return <pre className="model-markdown__code-block">{children}</pre>
  },
  table({ children }) {
    return (
      <div className="model-markdown__table-wrap">
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
  /** 附加类名（供各页面挂自己的样式作用域） */
  className?: string
}

export const MarkdownAnswer = memo(function MarkdownAnswer({
  content,
  streaming = false,
  className,
}: MarkdownAnswerProps): JSX.Element {
  // 逐 token 期间用低优先级快照，避免每个 delta 都做一次全量 AST 解析；
  // 完成态立即切回精确终稿，绝不让用户停在旧快照上。
  const deferred = useDeferredValue(content)
  const visible = streaming ? deferred : content
  const text = useMemo(() => prepareAnswerText(visible, streaming), [visible, streaming])

  const classes = ['model-markdown', streaming ? 'model-markdown--streaming' : '', className ?? '']
    .filter((c) => c !== '')
    .join(' ')

  return (
    <div className={classes} data-testid="model-markdown">
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
