/**
 * 内容区错误边界（2026-10-28 端到端实测后补）。
 *
 * ⚠️ **它存在的理由是一次真实的白屏**：`/config/tools` 的 Skill 目录里
 * 一个 `description` 字段被当成字符串渲染（后端实际回带 `{purpose, usage}` 对象），
 * React 抛「Objects are not valid as a React child」，**整棵应用树被卸载** ——
 * 用户看到的是全白页面，连左侧导航和「退出」都点不到，只能手动刷新。
 * 一个字段的显示问题不该有能力清空整个界面。
 *
 * <p>边界只包内容区、不包导航：出错时用户仍能换页面离开，
 * 这也正是「重试」以外的真实出路。
 *
 * ⚠️ `resetKey` 传路由 pathname：错误态下用户点了别的菜单，
 * 就必须让新页面正常渲染，否则边界会把新页面也扣在错误态里。
 */

import { Component, type ReactNode } from 'react'
import { ErrorState } from './states'

interface Props {
  resetKey: string
  children: ReactNode
}

interface State {
  error: Error | null
}

export class ContentErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidUpdate(prev: Props): void {
    if (prev.resetKey !== this.props.resetKey && this.state.error !== null) {
      this.setState({ error: null })
    }
  }

  render(): ReactNode {
    if (this.state.error === null) return this.props.children
    return (
      <ErrorState
        reason={`这个页面在渲染时出错了：${this.state.error.message}`}
        next="换一页或重新进入这个页面通常可以继续；若反复出现，请把上面这句话反馈给开发。"
        onRetry={() => this.setState({ error: null })}
        retryLabel="重新渲染本页"
      />
    )
  }
}
